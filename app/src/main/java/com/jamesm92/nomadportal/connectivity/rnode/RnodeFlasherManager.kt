package com.jamesm92.nomadportal.connectivity.rnode

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.chaquo.python.Python
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume

/** One official-firmware release, as reported by GitHub — see
 * `rnode_firmware.list_releases_json`'s own doc comment for exactly how
 * `boards` is derived. */
data class RnodeFirmwareRelease(
    val tag: String,
    val publishedAt: String,
    val boards: List<String>,
)

sealed class FlashState {
    data object Idle : FlashState()
    data object Connecting : FlashState()
    /** `current`/`total` are bytes within whichever segment
     * (bootloader/partitions/boot_app0/app) is currently being written —
     * see `esp32_flasher.flash_board`'s own doc comment; this is real,
     * per-block progress, not a fabricated/simulated ramp. */
    data class Flashing(val current: Int, val total: Int) : FlashState()
    data object Success : FlashState()
    data class Error(val message: String) : FlashState()
}

/** Chaquopy calls this by its real JVM method name (`onProgress`, not a
 * Python-style `on_progress` attribute) — see `esp32_flasher.py`'s own
 * matching doc comment on the call site. */
fun interface FlashProgressListener {
    fun onProgress(current: Int, total: Int)
}

/**
 * Owns the ESP32 official-firmware flasher's own device connect/flash
 * lifecycle — a distinct, exclusive-use USB session from
 * [RnodeUsbManager]'s own operational RNode connection (see
 * [Esp32FlashBridge]'s own doc comment for why they can't share a
 * bridge class, and why a caller must make sure RNode's own interface
 * isn't attached to the same device before flashing it).
 *
 * Release/board listing and the actual flash both go through
 * `orchestrator.py` (`list_rnode_firmware_releases_json`/
 * `list_supported_rnode_boards_json`/`flash_rnode_firmware`) — see those
 * functions' own doc comments, and `rnode_firmware.py`/`esp32_flasher.py`
 * for the real, source-verified GitHub-release-fetching and esptool-wire-
 * protocol logic underneath. Official firmware only, per explicit
 * direction — there is no "firmware source" picker here at all, unlike
 * Columba's own real flasher (`Official`/`MicroReticulum`/
 * `CommunityEdition`/`Custom`); this class only ever talks to
 * `markqvist/RNode_Firmware`'s real GitHub Releases API.
 */
class RnodeFlasherManager(
    private val context: Context,
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val orchestrator by lazy {
        Python.getInstance().getModule("nomadportal_core.orchestrator")
    }

    private val _flashState = MutableStateFlow<FlashState>(FlashState.Idle)
    val flashState: StateFlow<FlashState> = _flashState.asStateFlow()

    /** Currently-attached USB-serial candidate devices — a fresh
     * synchronous scan each call (this screen is short-lived/foreground-
     * only, unlike [RnodeUsbManager]'s own always-live attach/detach
     * tracking, so there's no need for a persisted StateFlow here). */
    fun availableDevices(): List<RnodeDeviceInfo> =
        UsbSerialDiscovery.findAllDrivers(usbManager).map { driver ->
            val d = driver.device
            RnodeDeviceInfo(
                deviceId = d.deviceId,
                vendorId = d.vendorId,
                productId = d.productId,
                deviceName = d.deviceName,
                productName = d.productName,
            )
        }

    /** Real GitHub Releases API listing, newest first. Throws on a fetch
     * failure (network down, rate-limited) — the caller (a Settings
     * screen) is expected to catch and show a real error rather than a
     * silently-empty list. */
    suspend fun fetchReleases(limit: Int = 5): List<RnodeFirmwareRelease> = withContext(Dispatchers.IO) {
        val raw = orchestrator.callAttr("list_rnode_firmware_releases_json", limit).toString()
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) {
            // {"error": "..."} shape — see list_releases_json's own doc comment.
            throw IOException(JSONObject(trimmed).optString("error", "Could not fetch firmware releases"))
        }
        val arr = JSONArray(trimmed)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            val boardsArr = obj.getJSONArray("boards")
            RnodeFirmwareRelease(
                tag = obj.optString("tag"),
                publishedAt = obj.optString("published_at"),
                boards = (0 until boardsArr.length()).map { boardsArr.getString(it) },
            )
        }
    }

    /** Board keys this flasher actually knows the flash layout for — see
     * `list_supported_rnode_boards_json`'s own doc comment for why this
     * is a real subset of what [fetchReleases] reports. */
    suspend fun fetchSupportedBoards(): Set<String> = withContext(Dispatchers.IO) {
        val arr = JSONArray(orchestrator.callAttr("list_supported_rnode_boards_json").toString())
        (0 until arr.length()).map { arr.getString(it) }.toSet()
    }

    /** Connects to `device`, downloads `boardKey`'s real official
     * firmware from release `tag`, and flashes it — updating
     * [flashState] throughout. Returns true on success. Never throws —
     * every failure (permission denial, port-open failure, download
     * failure, a real esptool protocol failure) lands in
     * [FlashState.Error] instead, matching this app's own
     * "connect/flash actions report failure via state, not exceptions"
     * convention (see [RnodeUsbManager.connect]). */
    suspend fun flash(device: RnodeDeviceInfo, tag: String, boardKey: String): Boolean = withContext(Dispatchers.IO) {
        _flashState.value = FlashState.Connecting

        val usbDevice = usbManager.deviceList.values.find { it.deviceId == device.deviceId }
        if (usbDevice == null) {
            _flashState.value = FlashState.Error("Device no longer attached")
            return@withContext false
        }

        if (!usbManager.hasPermission(usbDevice)) {
            val granted = requestPermission(usbDevice)
            if (!granted) {
                _flashState.value = FlashState.Error("USB permission denied")
                return@withContext false
            }
        }

        val driver = UsbSerialDiscovery.findAllDrivers(usbManager).find { it.device.deviceId == usbDevice.deviceId }
        if (driver == null || driver.ports.isEmpty()) {
            _flashState.value = FlashState.Error("No serial port found on this device")
            return@withContext false
        }

        val port: UsbSerialPort = driver.ports[0]
        try {
            val connection = usbManager.openDevice(usbDevice)
                ?: throw IOException("Could not open USB device connection")
            port.open(connection)
            // The real, fixed speed the ESP32 ROM bootloader's own UART
            // loader always starts at — not the operational RNode baud
            // rate (also 115200, coincidentally the same value, but a
            // conceptually different constant — see RnodeUsbManager's
            // own RNODE_BAUD_RATE comment).
            port.setParameters(115200, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        } catch (e: IOException) {
            _flashState.value = FlashState.Error("Could not open serial port: ${e.message}")
            return@withContext false
        }

        val bridge = Esp32FlashBridge(port)
        val progress = FlashProgressListener { current, total ->
            _flashState.value = FlashState.Flashing(current, total)
        }
        try {
            val resultJson = orchestrator.callAttr("flash_rnode_firmware", bridge, tag, boardKey, progress).toString()
            val obj = JSONObject(resultJson)
            val success = obj.optBoolean("success", false)
            _flashState.value = if (success) {
                FlashState.Success
            } else {
                FlashState.Error(obj.optString("message", "Flash failed"))
            }
            success
        } catch (e: Exception) {
            Log.w(TAG, "Flash failed: ${e.message}")
            _flashState.value = FlashState.Error(e.message ?: "Flash failed")
            false
        } finally {
            bridge.close()
        }
    }

    fun reset() {
        _flashState.value = FlashState.Idle
    }

    private suspend fun requestPermission(device: UsbDevice): Boolean = suspendCancellableCoroutine { cont ->
        val action = "com.jamesm92.nomadportal.ESP32_FLASH_USB_PERMISSION"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != action) return
                try {
                    context.unregisterReceiver(this)
                } catch (e: IllegalArgumentException) {
                    // Already unregistered (e.g. the coroutine was cancelled
                    // and invokeOnCancellation below beat this to it).
                }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (cont.isActive) cont.resume(granted)
            }
        }
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        // No SDK_INT gate needed — see RnodeUsbManager's matching comment
        // (real ObsoleteSdkInt lint finding: minSdk 31 already guarantees
        // FLAG_MUTABLE/API 31 is always available).
        val intent = PendingIntent.getBroadcast(
            context, 0, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_MUTABLE,
        )
        usbManager.requestPermission(device, intent)
        cont.invokeOnCancellation {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // Already unregistered.
            }
        }
    }

    private companion object {
        const val TAG = "RnodeFlasherManager"
    }
}
