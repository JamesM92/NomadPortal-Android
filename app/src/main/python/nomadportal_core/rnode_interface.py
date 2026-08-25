"""NomadRNodeInterface — a real RNS.Interface for RNode LoRa hardware
connected over Android USB-serial.

Why this file exists instead of using RNS's own RNodeInterface: RNS
ships two variants, neither usable here.

- `RNS.Interfaces.RNodeInterface` (the desktop one) explicitly refuses
  to run on Android at all: `__init__` raises `SystemError` the moment
  `RNS.vendor.platformutils.is_android()` is true.
- `RNS.Interfaces.Android.RNodeInterface` exists specifically for
  Android, but depends on `usbserial4a`/`jnius` (pyjnius) — packages
  built for the Kivy/python-for-android toolchain. Chaquopy has no
  pyjnius; neither package is installable or usable here. Confirmed
  directly against both files' real source (the pinned `rns==1.3.9`
  package), not assumed.

So a custom `Interface` subclass is required — the same shape this
app's own `rns_ble_interface.py` already uses for Bluetooth mesh: real
protocol logic lives in Python, actual byte I/O is delegated to a
Kotlin object across the Chaquopy boundary. The KISS/RNode wire
protocol itself below (frame format, command bytes, detect/config/
validate sequence) is a direct, unmodified port of upstream RNS's own
`RNodeInterface.py` (confirmed line-by-line against that file, not from
memory) — a real protocol spec, not something to reinvent. Only the
transport layer (pyserial -> a Kotlin USB-serial bridge,
`RnodeUsbBridge` in `connectivity/rnode/RnodeUsbBridge.kt`) is new.
"""

import threading
import time

import RNS
from RNS.Interfaces.Interface import Interface


class KISS:
    FEND = 0xC0
    FESC = 0xDB
    TFEND = 0xDC
    TFESC = 0xDD

    CMD_UNKNOWN = 0xFE
    CMD_DATA = 0x00
    CMD_FREQUENCY = 0x01
    CMD_BANDWIDTH = 0x02
    CMD_TXPOWER = 0x03
    CMD_SF = 0x04
    CMD_CR = 0x05
    CMD_RADIO_STATE = 0x06
    CMD_RADIO_LOCK = 0x07
    CMD_ST_ALOCK = 0x0B
    CMD_LT_ALOCK = 0x0C
    CMD_DETECT = 0x08
    CMD_LEAVE = 0x0A
    CMD_READY = 0x0F
    CMD_STAT_RX = 0x21
    CMD_STAT_TX = 0x22
    CMD_STAT_RSSI = 0x23
    CMD_STAT_SNR = 0x24
    CMD_STAT_BAT = 0x27
    CMD_BLINK = 0x30
    CMD_PLATFORM = 0x48
    CMD_MCU = 0x49
    CMD_FW_VERSION = 0x50
    CMD_RESET = 0x55

    DETECT_REQ = 0x73
    DETECT_RESP = 0x46

    RADIO_STATE_OFF = 0x00
    RADIO_STATE_ON = 0x01

    CMD_ERROR = 0x90
    ERROR_INITRADIO = 0x01
    ERROR_TXFAILED = 0x02
    ERROR_EEPROM_LOCKED = 0x03
    ERROR_QUEUE_FULL = 0x04
    ERROR_MEMORY_LOW = 0x05
    ERROR_MODEM_TIMEOUT = 0x06

    PLATFORM_AVR = 0x90
    PLATFORM_ESP32 = 0x80
    PLATFORM_NRF52 = 0x70

    ERROR_MESSAGES = {
        ERROR_INITRADIO: "Radio initialization failed",
        ERROR_TXFAILED: "Transmission failed",
        ERROR_EEPROM_LOCKED: "EEPROM locked",
        ERROR_QUEUE_FULL: "Data queue overflowed",
        ERROR_MEMORY_LOW: "Memory low",
        ERROR_MODEM_TIMEOUT: "Modem timeout",
    }

    @staticmethod
    def escape(data: bytes) -> bytes:
        data = data.replace(bytes([KISS.FESC]), bytes([KISS.FESC, KISS.FESC ^ 0x20]))
        data = data.replace(bytes([KISS.FEND]), bytes([KISS.FESC, KISS.FEND ^ 0x20]))
        return data


class NomadRNodeInterface(Interface):
    """RNS interface speaking KISS to RNode hardware over Android
    USB-serial, via a Kotlin `RnodeUsbBridge` object handed in through
    Chaquopy. See this module's own doc comment for why RNS's own
    Android RNode interfaces can't be used instead.
    """

    FREQ_MIN = 137000000
    FREQ_MAX = 3000000000

    REQUIRED_FW_VER_MAJ = 1
    REQUIRED_FW_VER_MIN = 52

    DEFAULT_IFAC_SIZE = 8
    HW_MTU = 508

    DETECT_TIMEOUT = 5.0
    FW_WAIT_TIMEOUT = 1.0
    CONFIG_DELAY = 0.15

    def __init__(self, owner, configuration, bridge):
        """`bridge` is a live `RnodeUsbBridge` Kotlin object, already
        connected to the target device by the time this is constructed
        — see `orchestrator.set_rnode_bridge`'s own doc comment for why
        connect happens on the Kotlin side first (USB permission is a
        real Android system flow, not something Python can drive)."""
        super().__init__()

        c = Interface.get_config_obj(configuration)
        self.owner = owner
        self.name = c["name"] if "name" in c else "RNode"
        self.bridge = bridge

        self.frequency = int(c["frequency"]) if "frequency" in c else 867500000
        self.bandwidth = int(c["bandwidth"]) if "bandwidth" in c else 125000
        self.txpower = int(c["txpower"]) if "txpower" in c else 7
        self.sf = int(c["spreadingfactor"]) if "spreadingfactor" in c else 8
        self.cr = int(c["codingrate"]) if "codingrate" in c else 5
        self.st_alock = float(c["st_alock"]) if "st_alock" in c else None
        self.lt_alock = float(c["lt_alock"]) if "lt_alock" in c else None

        mode_str = c["mode"] if "mode" in c else "roaming"
        # Same MODE_* mapping this app already uses for BLE/TCP interfaces
        # (see the nomadportal-android-bluetooth-mesh-implicit-relay
        # memory) — RNode is a radio link to devices this phone doesn't
        # control, the same real reason BLE neighbors default to
        # MODE_ROAMING rather than MODE_FULL.
        self.mode = {
            "full": Interface.MODE_FULL,
            "roaming": Interface.MODE_ROAMING,
            "boundary": Interface.MODE_BOUNDARY,
            "gateway": Interface.MODE_GATEWAY,
            "access_point": Interface.MODE_ACCESS_POINT,
        }.get(mode_str, Interface.MODE_ROAMING)

        self.IN = True
        self.OUT = True
        self.online = False
        self.detached = False
        self.bitrate = 10000
        self.rxb = 0
        self.txb = 0
        # Base Interface.__init__ sets HW_MTU to None; force it back —
        # same real bug/workaround this app's own BLE interface and
        # Columba's real RNode interface both document (RNS Transport
        # truncates packet.data by 3 bytes when HW_MTU is None).
        self.HW_MTU = NomadRNodeInterface.HW_MTU
        self.mtu = RNS.Reticulum.MTU
        self.AUTOCONFIGURE_MTU = False
        self.FIXED_MTU = True

        self.detected = False
        self.firmware_ok = False
        self.interface_ready = False
        self.platform = None
        self.mcu = None
        self.maj_version = 0
        self.min_version = 0
        self.state = KISS.RADIO_STATE_OFF

        self.r_frequency = None
        self.r_bandwidth = None
        self.r_txpower = None
        self.r_sf = None
        self.r_cr = None
        self.r_state = None

        self._running = threading.Event()
        self._read_thread = None
        self._read_lock = threading.Lock()

        self._running.set()
        self._read_thread = threading.Thread(
            target=self._read_loop, name=f"NomadRNode-read-{self.name}", daemon=True,
        )
        self._read_thread.start()

        try:
            self._configure_device()
        except Exception as e:
            RNS.log(f"NomadRNodeInterface[{self.name}]: failed to configure — {e}", RNS.LOG_ERROR)

    # ------------------------------------------------------------------
    # Bring-up
    # ------------------------------------------------------------------

    def _configure_device(self):
        self._detect()

        deadline = time.time() + self.DETECT_TIMEOUT
        while not self.detected and time.time() < deadline:
            time.sleep(0.1)
        if not self.detected:
            raise IOError(f"Could not detect RNode for {self}")

        fw_deadline = time.time() + self.FW_WAIT_TIMEOUT
        while not self.firmware_ok and time.time() < fw_deadline:
            time.sleep(0.02)
        if not self.firmware_ok:
            raise IOError(
                f"RNode firmware version {self.maj_version}.{self.min_version} is too old "
                f"(requires at least {self.REQUIRED_FW_VER_MAJ}.{self.REQUIRED_FW_VER_MIN})"
            )

        RNS.log(
            f"{self}: detected platform={hex(self.platform or 0)}, "
            f"firmware={self.maj_version}.{self.min_version}",
            RNS.LOG_INFO,
        )

        self._init_radio()

        if self._validate_radio_state():
            self.interface_ready = True
            self.online = True
            RNS.log(f"{self} is configured and online", RNS.LOG_INFO)
        else:
            raise IOError(
                f"After configuring {self}, the reported radio parameters did not "
                "match the requested configuration"
            )

    def _init_radio(self):
        self._set_frequency()
        time.sleep(self.CONFIG_DELAY)
        self._set_bandwidth()
        time.sleep(self.CONFIG_DELAY)
        self._set_tx_power()
        time.sleep(self.CONFIG_DELAY)
        self._set_spreading_factor()
        time.sleep(self.CONFIG_DELAY)
        self._set_coding_rate()
        time.sleep(self.CONFIG_DELAY)
        if self.st_alock is not None:
            self._set_st_alock()
            time.sleep(self.CONFIG_DELAY)
        if self.lt_alock is not None:
            self._set_lt_alock()
            time.sleep(self.CONFIG_DELAY)
        self._set_radio_state(KISS.RADIO_STATE_ON)
        time.sleep(self.CONFIG_DELAY)

    def _validate_radio_state(self) -> bool:
        deadline = time.time() + 3.0
        while time.time() < deadline:
            with self._read_lock:
                if self.r_state == KISS.RADIO_STATE_ON:
                    break
            time.sleep(0.1)

        with self._read_lock:
            r_frequency, r_bandwidth = self.r_frequency, self.r_bandwidth
            r_sf, r_cr, r_state = self.r_sf, self.r_cr, self.r_state

        if r_frequency is not None and abs(self.frequency - r_frequency) > 100:
            RNS.log(f"{self}: frequency mismatch (wanted {self.frequency}, got {r_frequency})", RNS.LOG_ERROR)
            return False
        if r_bandwidth is not None and r_bandwidth != self.bandwidth:
            RNS.log(f"{self}: bandwidth mismatch", RNS.LOG_ERROR)
            return False
        if r_sf is not None and r_sf != self.sf:
            RNS.log(f"{self}: spreading factor mismatch", RNS.LOG_ERROR)
            return False
        if r_cr is not None and r_cr != self.cr:
            RNS.log(f"{self}: coding rate mismatch", RNS.LOG_ERROR)
            return False
        if r_state != KISS.RADIO_STATE_ON:
            RNS.log(f"{self}: radio state not ON ({r_state})", RNS.LOG_ERROR)
            return False
        return True

    # ------------------------------------------------------------------
    # Outbound commands
    # ------------------------------------------------------------------

    def _detect(self):
        self._write(bytes([
            KISS.FEND, KISS.CMD_DETECT, KISS.DETECT_REQ, KISS.FEND,
            KISS.CMD_FW_VERSION, 0x00, KISS.FEND,
            KISS.CMD_PLATFORM, 0x00, KISS.FEND,
            KISS.CMD_MCU, 0x00, KISS.FEND,
        ]))

    def _set_frequency(self):
        f = self.frequency
        data = KISS.escape(bytes([f >> 24 & 0xFF, f >> 16 & 0xFF, f >> 8 & 0xFF, f & 0xFF]))
        self._write(bytes([KISS.FEND, KISS.CMD_FREQUENCY]) + data + bytes([KISS.FEND]))

    def _set_bandwidth(self):
        b = self.bandwidth
        data = KISS.escape(bytes([b >> 24 & 0xFF, b >> 16 & 0xFF, b >> 8 & 0xFF, b & 0xFF]))
        self._write(bytes([KISS.FEND, KISS.CMD_BANDWIDTH]) + data + bytes([KISS.FEND]))

    def _set_tx_power(self):
        self._write(bytes([KISS.FEND, KISS.CMD_TXPOWER, self.txpower, KISS.FEND]))

    def _set_spreading_factor(self):
        self._write(bytes([KISS.FEND, KISS.CMD_SF, self.sf, KISS.FEND]))

    def _set_coding_rate(self):
        self._write(bytes([KISS.FEND, KISS.CMD_CR, self.cr, KISS.FEND]))

    def _set_st_alock(self):
        at = int(self.st_alock * 100)
        data = KISS.escape(bytes([at >> 8 & 0xFF, at & 0xFF]))
        self._write(bytes([KISS.FEND, KISS.CMD_ST_ALOCK]) + data + bytes([KISS.FEND]))

    def _set_lt_alock(self):
        at = int(self.lt_alock * 100)
        data = KISS.escape(bytes([at >> 8 & 0xFF, at & 0xFF]))
        self._write(bytes([KISS.FEND, KISS.CMD_LT_ALOCK]) + data + bytes([KISS.FEND]))

    def _set_radio_state(self, state):
        self.state = state
        self._write(bytes([KISS.FEND, KISS.CMD_RADIO_STATE, state, KISS.FEND]))

    def _write(self, data: bytes):
        written = self.bridge.write(bytearray(data))
        if written != len(data):
            raise IOError(f"{self}: USB write only sent {written} of {len(data)} bytes")

    def process_outgoing(self, data: bytes):
        if not self.online:
            return
        frame = bytes([KISS.FEND, KISS.CMD_DATA]) + KISS.escape(data) + bytes([KISS.FEND])
        self._write(frame)
        self.txb += len(data)

    # ------------------------------------------------------------------
    # Inbound
    # ------------------------------------------------------------------

    def _process_incoming(self, data: bytes):
        self.rxb += len(data)

        def deliver():
            self.owner.inbound(data, self)

        threading.Thread(target=deliver, daemon=True).start()

    def _read_loop(self):
        in_frame = False
        escape = False
        command = KISS.CMD_UNKNOWN
        data_buffer = bytearray()

        while self._running.is_set():
            try:
                raw = self.bridge.read()
                data = bytes(raw) if raw is not None else b""
                if len(data) == 0:
                    time.sleep(0.01)
                    continue

                for byte in data:
                    if in_frame and byte == KISS.FEND and command == KISS.CMD_DATA:
                        in_frame = False
                        self._process_incoming(bytes(data_buffer))
                        data_buffer = bytearray()
                    elif byte == KISS.FEND:
                        in_frame = True
                        command = KISS.CMD_UNKNOWN
                        data_buffer = bytearray()
                    elif in_frame and len(data_buffer) < 512:
                        if escape:
                            if byte == KISS.TFEND:
                                data_buffer.append(KISS.FEND)
                            elif byte == KISS.TFESC:
                                data_buffer.append(KISS.FESC)
                            else:
                                data_buffer.append(byte)
                            escape = False
                        elif byte == KISS.FESC:
                            escape = True
                        elif command == KISS.CMD_UNKNOWN:
                            command = byte
                        elif command == KISS.CMD_DATA:
                            data_buffer.append(byte)
                        else:
                            self._handle_status_byte(command, data_buffer, byte)
                            if command not in (KISS.CMD_FREQUENCY, KISS.CMD_BANDWIDTH, KISS.CMD_FW_VERSION):
                                data_buffer = bytearray()
                            else:
                                data_buffer.append(byte)
            except Exception as e:
                if self._running.is_set():
                    RNS.log(f"{self}: read loop error — {e}", RNS.LOG_ERROR)
                    time.sleep(0.1)

    def _handle_status_byte(self, command, data_buffer, byte):
        """Multi-byte status replies (frequency/bandwidth/firmware) need
        their bytes accumulated across calls; single-byte ones apply
        immediately. Matches the real byte layout confirmed against
        upstream RNodeInterface.py's own readLoop."""
        with self._read_lock:
            if command == KISS.CMD_FREQUENCY:
                if len(data_buffer) == 3:
                    b = bytes(data_buffer) + bytes([byte])
                    self.r_frequency = (b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3]
            elif command == KISS.CMD_BANDWIDTH:
                if len(data_buffer) == 3:
                    b = bytes(data_buffer) + bytes([byte])
                    self.r_bandwidth = (b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3]
            elif command == KISS.CMD_TXPOWER:
                self.r_txpower = byte
            elif command == KISS.CMD_SF:
                self.r_sf = byte
            elif command == KISS.CMD_CR:
                self.r_cr = byte
            elif command == KISS.CMD_RADIO_STATE:
                self.r_state = byte
            elif command == KISS.CMD_FW_VERSION:
                if len(data_buffer) == 1:
                    self.maj_version = data_buffer[0]
                    self.min_version = byte
                    self._validate_firmware()
            elif command == KISS.CMD_PLATFORM:
                self.platform = byte
            elif command == KISS.CMD_MCU:
                self.mcu = byte
            elif command == KISS.CMD_DETECT:
                if byte == KISS.DETECT_RESP:
                    self.detected = True
            elif command == KISS.CMD_ERROR:
                RNS.log(f"{self}: RNode error 0x{byte:02X} — {KISS.ERROR_MESSAGES.get(byte, 'unknown')}", RNS.LOG_ERROR)

    def _validate_firmware(self):
        if self.maj_version > self.REQUIRED_FW_VER_MAJ:
            self.firmware_ok = True
        elif self.maj_version == self.REQUIRED_FW_VER_MAJ and self.min_version >= self.REQUIRED_FW_VER_MIN:
            self.firmware_ok = True

    # ------------------------------------------------------------------

    def detach(self):
        self._running.clear()
        self.online = False
        self.detached = True
        try:
            self.bridge.disconnect()
        except Exception:
            pass

    def should_ingress_limit(self):
        return False

    def __str__(self):
        return f"NomadRNodeInterface[{self.name}]"
