"""A from-scratch esptool-protocol (SLIP + ESP32 ROM bootloader) client for
flashing the *official* RNode firmware (`markqvist/RNode_Firmware`) onto an
ESP32-family board over Android USB-serial.

Why this exists instead of just calling `esptool`: `esptool` (and RNS's own
bundled `rnodeconf.py` — see below) both talk to the board through a real
OS serial device path via `pyserial`. Android's USB-host model has no such
path without root — the exact same limitation `rnode_interface.py`'s own
doc comment documents for RNode's *operational* KISS interface applies
identically here. So, same shape as that file: real protocol logic lives
in Python, actual byte I/O (plus the DTR/RTS lines needed to reset the
chip into its ROM bootloader) is delegated to a Kotlin
`Esp32FlashBridge` object handed in across the Chaquopy boundary.

**The protocol and the board/offset table below are not guessed** — both
were confirmed directly against real source this session:
- The SLIP framing, command header layout, checksum algorithm, SYNC
  payload, and FLASH_BEGIN/FLASH_DATA/FLASH_END payload formats are a
  direct port of the real, current `espressif/esptool` source
  (`esptool/loader.py`).
- The classic DTR/RTS "enter bootloader" reset sequence is a direct port
  of `esptool/reset.py`'s `ClassicReset`/`HardReset`.
- The image-header flash_mode/flash_freq/flash_size patch (applied only
  to the `.bootloader` segment, matching real esptool's own
  `_update_image_flash_params` address-gated behavior) uses esptool's
  real `FLASH_MODES`/`FLASH_FREQUENCY`/`FLASH_SIZES` tables.
- The per-board offset/chip/flash-size table below is transcribed
  directly from **this project's own pinned `rns==1.3.9` dependency** —
  `RNS.Utilities.rnodeconf` (real source, already vendored into this
  exact build via the `rns` pip package) drives the real, official
  `rnodeconf`/NomadNet installer's own per-board `esptool write_flash`
  invocations. Reusing its real, working offsets/flags is safer than
  re-deriving them from board schematics — this app just can't reuse
  `rnodeconf` itself directly, for the same pyserial/Android reason as
  everything else in this module's doc comment.

**Scope, per explicit direction**: official firmware only. Boards that
`rnodeconf.py` itself flashes via a *different* mechanism (nRF52-based
boards — Heltec T114, RAK4631 — use Nordic DFU, not esptool at all) are
deliberately absent from [BOARDS] below; the Settings flasher screen
should treat any release asset not present in this table as
"not supported by this flasher yet" rather than guessing.
"""

import struct
import time

# ---------------------------------------------------------------------------
# Real per-board table, transcribed from RNS.Utilities.rnodeconf (see this
# module's own doc comment). `bootloader_offset` is where esptool's real
# BOOTLOADER_FLASH_OFFSET-gated header patch applies — 0x1000 for classic
# ESP32, 0x0 for ESP32-S3 (confirmed per-board against rnodeconf.py's own
# `--chip`/offset arguments, not assumed uniform).
# ---------------------------------------------------------------------------
BOARDS = {
    # key: (chip, flash_size, bootloader_offset)
    "tbeam": ("esp32", "4MB", 0x1000),
    "tbeam_sx1262": ("esp32", "4MB", 0x1000),
    "lora32v10": ("esp32", "4MB", 0x1000),
    "lora32v20": ("esp32", "4MB", 0x1000),
    "lora32v21": ("esp32", "4MB", 0x1000),
    "lora32v21_tcxo": ("esp32", "4MB", 0x1000),
    "heltec32v2": ("esp32", "8MB", 0x1000),
    "heltec32v3": ("esp32-s3", "8MB", 0x0),
    "heltec32v4pa": ("esp32-s3", "16MB", 0x0),
    "featheresp32": ("esp32", "4MB", 0x1000),
    "esp32_generic": ("esp32", "4MB", 0x1000),
    "ng20": ("esp32", "4MB", 0x1000),
    "ng21": ("esp32", "4MB", 0x1000),
    "t3s3": ("esp32-s3", "4MB", 0x0),
    "t3s3_sx127x": ("esp32-s3", "4MB", 0x0),
    "t3s3_sx1280_pa": ("esp32-s3", "4MB", 0x0),
    "tbeam_supreme": ("esp32-s3", "4MB", 0x0),
    "tdeck": ("esp32-s3", "4MB", 0x0),
    "xiao_esp32s3": ("esp32-s3", "8MB", 0x0),
}

# Fixed offsets for the other 3 segments in every board's release zip —
# real, uniform across every board entry in rnodeconf.py (only the
# bootloader's own offset varies, per BOARDS above).
PARTITIONS_OFFSET = 0x8000
BOOT_APP0_OFFSET = 0xE000
APP_OFFSET = 0x10000

# esptool's real FLASH_MODES/FLASH_FREQUENCY/FLASH_SIZES tables (see this
# module's own doc comment) — every board in BOARDS is flashed with
# dio/80m in rnodeconf.py, so those two are fixed; flash_size varies and
# is looked up from BOARDS.
_FLASH_MODE_DIO = 2
_FLASH_FREQ_80M = 0xF
_FLASH_SIZES = {"1MB": 0x00, "2MB": 0x10, "4MB": 0x20, "8MB": 0x30, "16MB": 0x40}
_ESP_IMAGE_MAGIC = 0xE9

# KISS-style command opcodes — real esptool ROM-loader opcodes (see this
# module's own doc comment), unrelated to rnode_interface.KISS's own
# opcode namespace despite superficially similar naming.
_SYNC = 0x08
_FLASH_BEGIN = 0x02
_FLASH_DATA = 0x03
_FLASH_END = 0x04

_FLASH_WRITE_SIZE = 0x400  # real ROM-loader (non-stub) block size
_CHECKSUM_MAGIC = 0xEF


def _slip_escape(data: bytes) -> bytes:
    out = bytearray()
    for b in data:
        if b == 0xC0:
            out += b"\xdb\xdc"
        elif b == 0xDB:
            out += b"\xdb\xdd"
        else:
            out.append(b)
    return bytes(out)


def _slip_unescape(data: bytes) -> bytes:
    out = bytearray()
    i = 0
    n = len(data)
    while i < n:
        b = data[i]
        if b == 0xDB and i + 1 < n:
            nxt = data[i + 1]
            if nxt == 0xDC:
                out.append(0xC0)
                i += 2
                continue
            elif nxt == 0xDD:
                out.append(0xDB)
                i += 2
                continue
        out.append(b)
        i += 1
    return bytes(out)


def _checksum(data: bytes) -> int:
    state = _CHECKSUM_MAGIC
    for b in data:
        state ^= b
    return state


def _read_packet(bridge, timeout_s: float):
    """Reads one SLIP-framed packet from `bridge` (a live
    `Esp32FlashBridge` Kotlin object exposing `read_byte(timeout_ms) ->
    int` — -1 on timeout, matching how `NomadRNodeInterface`'s own
    `bridge.read()` contract works, just byte-granular here since
    esptool's own real framing needs to find delimiters one byte at a
    time). Returns the raw (still SLIP-escaped) bytes between the two
    0xC0 delimiters, or None if no complete frame arrived within
    `timeout_s`. A literal 0xC0 never appears unescaped inside a real
    frame's payload (always sent as the 2-byte DB DC escape) — treating
    every 0xC0 byte as a delimiter is correct without needing
    escape-awareness at this stage, same as upstream esptool's own
    `slip_reader`."""
    deadline = time.time() + timeout_s
    buf = bytearray()
    started = False
    while time.time() < deadline:
        remaining_ms = max(1, int((deadline - time.time()) * 1000))
        b = bridge.read_byte(min(remaining_ms, 50))
        if b < 0:
            continue
        if b == 0xC0:
            if not started:
                started = True
                buf = bytearray()
                continue
            else:
                return bytes(buf)
        if started:
            buf.append(b)
    return None


def _command(bridge, op, data: bytes = b"", chk: int = 0, timeout_s: float = 3.0, wait_response: bool = True):
    """Direct port of upstream esptool's own `command()` — see this
    module's doc comment. Returns (val, response_data); raises IOError
    if no matching response arrives before `timeout_s`."""
    if op is not None:
        pkt = struct.pack("<BBHI", 0x00, op, len(data), chk) + data
        framed = b"\xc0" + _slip_escape(pkt) + b"\xc0"
        bridge.write(bytearray(framed))
    if not wait_response:
        return None, b""

    deadline = time.time() + timeout_s
    while time.time() < deadline:
        remaining = deadline - time.time()
        raw = _read_packet(bridge, remaining)
        if raw is None:
            break
        p = _slip_unescape(raw)
        if len(p) < 8:
            continue
        resp, op_ret, len_ret, val = struct.unpack("<BBHI", p[:8])
        if resp != 1:
            continue
        if op is None or op_ret == op:
            return val, p[8:]
    raise IOError("No valid response to command 0x%02x" % (op if op is not None else 0xFF))


def _reset_to_bootloader(bridge):
    """Classic esptool auto-reset sequence — see this module's doc
    comment. DTR=IO0 (active-low: True means IO0 pulled low), RTS=EN
    (active-low: True means chip held in reset)."""
    bridge.set_dtr(False)  # IO0 = HIGH
    bridge.set_rts(True)   # EN = LOW (reset)
    time.sleep(0.1)
    bridge.set_dtr(True)   # IO0 = LOW (download mode requested)
    bridge.set_rts(False)  # EN = HIGH (out of reset)
    time.sleep(0.05)
    bridge.set_dtr(False)  # IO0 = HIGH, done


def _hard_reset(bridge):
    """Boots normally into the newly-flashed app — real esptool
    `HardReset` sequence (USB variant timings)."""
    bridge.set_rts(True)
    time.sleep(0.2)
    bridge.set_rts(False)
    time.sleep(0.2)


def _sync(bridge, timeout_s: float = 0.3) -> bool:
    try:
        _command(bridge, _SYNC, b"\x07\x07\x12\x20" + b"\x55" * 32, timeout_s=timeout_s)
    except IOError:
        return False
    # Real esptool drains 7 more replies after a successful sync (the ROM
    # loader can echo the sync command multiple times) — best-effort,
    # failures here don't invalidate an already-successful sync.
    for _ in range(7):
        try:
            _command(bridge, None, timeout_s=0.05)
        except IOError:
            pass
    return True


def _connect(bridge, attempts: int = 4) -> bool:
    for _ in range(attempts):
        _reset_to_bootloader(bridge)
        if _sync(bridge):
            return True
    return False


def _patch_bootloader_header(data: bytes, flash_size: str) -> bytes:
    """Real esptool `_update_image_flash_params` behavior, restricted to
    exactly the one case this flasher needs (dio/80m, a real image with
    the 0xE9 magic) — see this module's doc comment for the source."""
    if len(data) < 4 or data[0] != _ESP_IMAGE_MAGIC:
        return data
    size_nibble = _FLASH_SIZES.get(flash_size, 0x20)
    patched = bytes([_FLASH_MODE_DIO, size_nibble | _FLASH_FREQ_80M])
    if patched == data[2:4]:
        return data
    return data[0:2] + patched + data[4:]


def _flash_segment(bridge, offset: int, data: bytes, on_progress):
    size = len(data)
    num_blocks = (size + _FLASH_WRITE_SIZE - 1) // _FLASH_WRITE_SIZE
    # ROM loader performs the erase synchronously inside FLASH_BEGIN
    # itself (real esptool comment: "ROM performs the erase up front") —
    # scale the timeout with size the same way, ~40s/MB is esptool's own
    # real ERASE_REGION_TIMEOUT_PER_MB default.
    begin_timeout = max(3.0, (size / (1024 * 1024)) * 40.0)
    _command(bridge, _FLASH_BEGIN, struct.pack("<IIII", size, num_blocks, _FLASH_WRITE_SIZE, offset), timeout_s=begin_timeout)

    for seq in range(num_blocks):
        block = data[seq * _FLASH_WRITE_SIZE: (seq + 1) * _FLASH_WRITE_SIZE]
        if len(block) < _FLASH_WRITE_SIZE:
            block = block + b"\xff" * (_FLASH_WRITE_SIZE - len(block))
        pkt = struct.pack("<IIII", len(block), seq, 0, 0) + block
        _command(bridge, _FLASH_DATA, pkt, chk=_checksum(block), timeout_s=6.0)
        if on_progress is not None:
            try:
                # Java method name, not a Python-style snake_case attribute
                # — Chaquopy's Python-calls-into-Kotlin attribute access
                # goes through the JVM's real method name as-is.
                on_progress.onProgress(min((seq + 1) * _FLASH_WRITE_SIZE, size), size)
            except Exception:
                pass  # a misbehaving UI callback must never abort a real flash in progress


def flash_board(bridge, board_key: str, files: dict, on_progress=None) -> str:
    """Flashes one official RNode firmware release onto the device
    already connected via `bridge` (a live `Esp32FlashBridge` Kotlin
    object — `write(data)`, `read_byte(timeout_ms)`, `set_dtr(bool)`,
    `set_rts(bool)`). `files` is `{"bootloader": bytes, "partitions":
    bytes, "boot_app0": bytes, "app": bytes}` — the 4 real files inside
    an official `rnode_firmware_<board>.zip` release asset (see
    `RnodeFirmwareRepository.kt`'s own doc comment for where these come
    from). `on_progress`, if given, is a Kotlin object exposing
    `onProgress(current: int, total: int)`, called after every 1KB
    block of every segment — best-effort, a callback failure never
    aborts the actual flash.

    Returns a JSON string `{"success": bool, "message": str}` — this
    module's own standing Chaquopy-boundary convention (see the
    nomadportal-android-conventions skill).
    """
    import json

    if board_key not in BOARDS:
        return json.dumps({"success": False, "message": f"Unsupported board: {board_key}"})
    _, flash_size, bootloader_offset = BOARDS[board_key]

    required = ("bootloader", "partitions", "boot_app0", "app")
    missing = [k for k in required if k not in files or not files[k]]
    if missing:
        return json.dumps({"success": False, "message": f"Firmware archive is missing: {', '.join(missing)}"})

    try:
        if not _connect(bridge):
            return json.dumps({
                "success": False,
                "message": "Could not sync with the device's bootloader. Hold the board's BOOT "
                            "button while connecting, or check the cable/port, then try again.",
            })

        bootloader_data = _patch_bootloader_header(files["bootloader"], flash_size)

        # Ascending offset order (matches rnodeconf.py's own real
        # ordering) — not a protocol requirement (each FLASH_BEGIN names
        # its own destination offset independently), just keeps behavior
        # aligned with the known-working reference implementation.
        segments = sorted([
            (bootloader_offset, bootloader_data),
            (PARTITIONS_OFFSET, files["partitions"]),
            (BOOT_APP0_OFFSET, files["boot_app0"]),
            (APP_OFFSET, files["app"]),
        ], key=lambda s: s[0])

        for offset, data in segments:
            _flash_segment(bridge, offset, data, on_progress)

        _command(bridge, _FLASH_END, struct.pack("<I", 0), timeout_s=3.0)  # reboot=True
        _hard_reset(bridge)
        return json.dumps({"success": True, "message": "Firmware flashed successfully"})
    except IOError as e:
        return json.dumps({"success": False, "message": f"Flash failed: {e}"})
    except Exception as e:
        return json.dumps({"success": False, "message": f"Unexpected error: {e}"})
