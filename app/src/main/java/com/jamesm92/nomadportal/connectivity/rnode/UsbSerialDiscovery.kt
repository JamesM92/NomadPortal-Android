package com.jamesm92.nomadportal.connectivity.rnode

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber

/**
 * Shared USB-serial device enumeration for both [RnodeUsbManager] (RNode's
 * own operational connection) and [RnodeFlasherManager] (the ESP32
 * firmware flasher) — promoted here once a real second caller needed the
 * identical logic, per this project's own "promote after the second real
 * call site, not preemptively" convention (see the
 * nomadportal-android-conventions skill).
 *
 * Uses `usb-serial-for-android`'s own default probe table (covers
 * CP210x/CH340/FTDI/PL2303/generic CDC-ACM — the USB-serial bridge chips
 * on most RNode-compatible boards, e.g. Heltec/LilyGo/TTGO's CP2102 or
 * CH9102), plus one honest fallback: a device that isn't in that table
 * but *identifies itself* as USB class 0x02 (Communications/CDC-ACM) —
 * covering native-USB ESP32-S2/S3 boards (Espressif's own registered VID
 * 0x303A, confirmed via `espressif/usb-pids` — but this project could not
 * independently verify one single stable PID for every board's own USB
 * Serial/JTAG descriptor, so this matches by *class*, not by a
 * possibly-wrong hardcoded PID).
 */
internal object UsbSerialDiscovery {
    private val prober: UsbSerialProber by lazy {
        UsbSerialProber(UsbSerialProber.getDefaultProbeTable())
    }

    fun findAllDrivers(usbManager: UsbManager): List<UsbSerialDriver> {
        val fromTable = prober.findAllDrivers(usbManager)
        val matchedDeviceIds = fromTable.map { it.device.deviceId }.toSet()
        val fallback = usbManager.deviceList.values
            .filter { it.deviceId !in matchedDeviceIds && isCdcAcmClassDevice(it) }
            .map { CdcAcmSerialDriver(it) as UsbSerialDriver }
        return fromTable + fallback
    }

    private fun isCdcAcmClassDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_COMM) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_COMM) return true
        }
        return false
    }
}
