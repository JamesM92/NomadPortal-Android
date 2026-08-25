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
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.jamesm92.nomadportal.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** One USB-attached candidate device — vendor/product ID is the part
 * that survives a replug (see [RnodeUsbManager]'s own doc comment for
 * why `deviceId` itself doesn't). */
data class RnodeDeviceInfo(
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val deviceName: String,
    val productName: String?,
)

sealed class RnodeConnectionState {
    data object Disconnected : RnodeConnectionState()
    data object Connecting : RnodeConnectionState()
    data class Connected(val device: RnodeDeviceInfo) : RnodeConnectionState()
    data class Error(val message: String) : RnodeConnectionState()
}

/**
 * Owns USB-serial device discovery, Android's own permission-grant flow,
 * and the connect/disconnect lifecycle for RNode-over-USB — the RNode
 * counterpart to [com.jamesm92.nomadportal.connectivity.BluetoothMeshManager],
 * same overall shape (discover -> obtain a live bridge object -> hand it
 * to `orchestrator.py` -> the Python side does the real protocol work).
 *
 * **Reconnect uses VID+PID, never the raw Android `deviceId`.** A USB
 * replug (or the device power-cycling, common for an RNode board
 * mid-configuration) gets assigned a new `deviceId` by Android's own USB
 * subsystem — persisting that raw ID and expecting it to still resolve
 * to the same physical device after a replug is a real, documented
 * mistake other RNode-over-USB implementations have had to work around;
 * see [SettingsRepository.rNodeVendorId]/[SettingsRepository.rNodeProductId].
 *
 * Device discovery itself (probe table + the honest CDC-ACM-class
 * fallback for native-USB boards) lives in [UsbSerialDiscovery], shared
 * with [RnodeFlasherManager] — see that object's own doc comment.
 */
class RnodeUsbManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val orchestrator by lazy {
        Python.getInstance().getModule("nomadportal_core.orchestrator")
    }

    private var bridge: RnodeUsbBridge? = null
    private var connectedVendorId: Int = -1
    private var connectedProductId: Int = -1

    private val _connectionState = MutableStateFlow<RnodeConnectionState>(RnodeConnectionState.Disconnected)
    val connectionState: StateFlow<RnodeConnectionState> = _connectionState.asStateFlow()

    private val _availableDevices = MutableStateFlow<List<RnodeDeviceInfo>>(emptyList())
    val availableDevices: StateFlow<List<RnodeDeviceInfo>> = _availableDevices.asStateFlow()

    private var pendingPermissionCallback: ((Boolean) -> Unit)? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    pendingPermissionCallback?.invoke(granted)
                    pendingPermissionCallback = null
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> refreshDevices()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = getDeviceExtra(intent)
                    refreshDevices()
                    if (device != null && device.vendorId == connectedVendorId && device.productId == connectedProductId) {
                        // The connected device itself was just unplugged —
                        // tear down the bridge/interface rather than
                        // leaving Python calling write()/read() on a dead
                        // port; RnodeUsbBridge.write() would eventually
                        // notice on its own, but this is faster and
                        // updates connectionState immediately for the UI.
                        scope.launch { disconnect() }
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(usbReceiver, filter)
        }
        refreshDevices()
    }

    /** Re-enumerates currently-attached USB-serial-capable devices. Safe
     * to call anytime (e.g. a Settings screen's own pull-to-refresh) —
     * cheap, synchronous, no I/O beyond `UsbManager`'s own device list. */
    fun refreshDevices() {
        val drivers = findAllDrivers()
        _availableDevices.value = drivers.map { driver ->
            val d = driver.device
            RnodeDeviceInfo(
                deviceId = d.deviceId,
                vendorId = d.vendorId,
                productId = d.productId,
                deviceName = d.deviceName,
                productName = d.productName,
            )
        }
    }

    private fun findAllDrivers(): List<UsbSerialDriver> = UsbSerialDiscovery.findAllDrivers(usbManager)

    /** Requests USB permission (if not already granted), opens the port,
     * runs `NomadRNodeInterface`'s real detect/configure sequence
     * (synchronously, on the IO dispatcher, since it blocks on real
     * device round-trips), and — on success — persists the device's
     * VID/PID + `configJson` for a future auto-reconnect. Returns false
     * (with [connectionState] set to [RnodeConnectionState.Error]) on
     * any failure — permission denial, a port that won't open, or a
     * device that fails `NomadRNodeInterface`'s own real firmware/radio
     * validation — rather than throwing, since this is meant to be
     * called directly from a Settings screen's own connect button. */
    suspend fun connect(deviceInfo: RnodeDeviceInfo, configJson: String): Boolean {
        _connectionState.value = RnodeConnectionState.Connecting
        val device = usbManager.deviceList.values.find { it.deviceId == deviceInfo.deviceId }
        if (device == null) {
            _connectionState.value = RnodeConnectionState.Error("Device no longer attached")
            return false
        }

        if (!usbManager.hasPermission(device)) {
            val granted = requestPermission(device)
            if (!granted) {
                _connectionState.value = RnodeConnectionState.Error("USB permission denied")
                return false
            }
        }

        val driver = findAllDrivers().find { it.device.deviceId == device.deviceId }
        if (driver == null || driver.ports.isEmpty()) {
            _connectionState.value = RnodeConnectionState.Error("No serial port found on this device")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val connection = usbManager.openDevice(device)
                    ?: throw IllegalStateException("Could not open USB device connection")
                val port: UsbSerialPort = driver.ports[0]
                port.open(connection)
                port.setParameters(
                    RNODE_BAUD_RATE,
                    UsbSerialPort.DATABITS_8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE,
                )

                val newBridge = RnodeUsbBridge(port)
                // NomadRNodeInterface's constructor runs the real
                // detect/configure sequence and raises on failure (bad
                // radio params, unresponsive device, firmware too old) —
                // let that surface here rather than reporting success for
                // an interface that never actually came online.
                orchestrator.callAttr("set_rnode_bridge", newBridge, configJson)

                disconnectBridgeOnly() // in case a previous session's bridge was still open
                bridge = newBridge
                connectedVendorId = deviceInfo.vendorId
                connectedProductId = deviceInfo.productId
                settings.setRNodeDevice(deviceInfo.vendorId, deviceInfo.productId)
                settings.setRNodeConfigJson(configJson)

                _connectionState.value = RnodeConnectionState.Connected(deviceInfo)
                true
            } catch (e: Exception) {
                Log.w(TAG, "RNode connect failed: ${e.message}")
                _connectionState.value = RnodeConnectionState.Error(e.message ?: "Connection failed")
                false
            }
        }
    }

    /** Attempts to reconnect to whatever device/config was last
     * persisted, only if a matching (by VID/PID) device is currently
     * attached — called from [com.jamesm92.nomadportal.connectivity.RealInterfaceController.setRNodeEnabled]
     * when the master toggle turns on. Returns false (without setting
     * [connectionState] to [RnodeConnectionState.Error]) if there's
     * nothing to reconnect to yet — that's a normal "never configured a
     * device" state, not a failure worth surfacing as an error. */
    suspend fun reconnectPersisted(): Boolean {
        val vendorId = settings.rNodeVendorId.first()
        val productId = settings.rNodeProductId.first()
        if (vendorId == -1 || productId == -1) return false

        refreshDevices()
        val match = _availableDevices.value.find { it.vendorId == vendorId && it.productId == productId }
            ?: return false // device just isn't attached right now — not an error

        val configJson = settings.rNodeConfigJson.first()
        return connect(match, configJson)
    }

    /** Detaches the Python interface and closes the USB port. */
    suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            try {
                orchestrator.callAttr("clear_rnode_bridge")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to detach RNode interface: ${e.message}")
            }
            disconnectBridgeOnly()
        }
        connectedVendorId = -1
        connectedProductId = -1
        _connectionState.value = RnodeConnectionState.Disconnected
    }

    private fun disconnectBridgeOnly() {
        bridge?.disconnect()
        bridge = null
    }

    private suspend fun requestPermission(device: UsbDevice): Boolean = suspendCancellableCoroutine { cont ->
        pendingPermissionCallback = { granted ->
            if (cont.isActive) cont.resume(granted)
        }
        // No SDK_INT gate needed — FLAG_MUTABLE (API 31/S) is always
        // available given this app's own minSdk 31 floor (real lint
        // finding: ObsoleteSdkInt flagged the check this used to have
        // here as dead code, correctly — minSdk already guarantees it).
        val intent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), PendingIntent.FLAG_MUTABLE,
        )
        usbManager.requestPermission(device, intent)
        cont.invokeOnCancellation { pendingPermissionCallback = null }
    }

    private fun getDeviceExtra(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private companion object {
        const val TAG = "RnodeUsbManager"
        const val ACTION_USB_PERMISSION = "com.jamesm92.nomadportal.USB_PERMISSION"
        // RNode's own KISS-over-serial firmware always runs at this rate
        // regardless of board — matches upstream RNodeInterface.py's own
        // hardcoded serial baud rate, not something the radio config
        // (frequency/bandwidth/etc.) affects.
        const val RNODE_BAUD_RATE = 115200
    }
}
