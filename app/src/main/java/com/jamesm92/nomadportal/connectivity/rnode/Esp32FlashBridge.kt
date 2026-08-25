package com.jamesm92.nomadportal.connectivity.rnode

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException

/**
 * The Python-facing half of the ESP32 firmware flasher — this exact
 * object is what `orchestrator.flash_rnode_firmware(bridge, ...)` hands
 * to `esp32_flasher.py`'s own SLIP/esptool-protocol client. Same
 * "Kotlin owns real device I/O, Python owns protocol logic" split
 * [RnodeUsbBridge] already uses for RNode's own operational KISS
 * traffic — but deliberately a **separate, simpler** class rather than
 * a shared one:
 *
 * - [RnodeUsbBridge] is built around `SerialInputOutputManager`'s own
 *   background read/write threads feeding a queue, tuned for RNode's
 *   continuous KISS traffic. The esptool protocol instead needs a
 *   classic *synchronous, blocking-with-timeout, byte-granular* read
 *   (matching real esptool's own `pyserial` usage directly against a
 *   port object) — a fundamentally different I/O shape, not a
 *   parameter to tune on the existing class.
 * - Flashing also needs direct DTR/RTS line control (to reset the chip
 *   into its ROM bootloader — see `esp32_flasher._reset_to_bootloader`'s
 *   own doc comment) — a real, separate capability [RnodeUsbBridge] has
 *   no reason to expose for RNode's own normal operation.
 *
 * Exclusive-use and short-lived: constructed fresh for one flash
 * operation (an already-`open()`ed [UsbSerialPort] at 115200 baud — the
 * real ESP32 ROM bootloader's own fixed UART-loader speed), and
 * [close]d once that operation finishes, success or failure. The caller
 * ([RnodeFlasherManager], not [RnodeUsbManager]) is responsible for
 * making sure RNode's own operational interface isn't attached to the
 * same device at the same time — a USB device can only serve one open
 * port from this app at once.
 */
class Esp32FlashBridge(private val port: UsbSerialPort) {

    /** Blocking write with a generous fixed timeout — flash-data packets
     * are small (≤ ~1KB) and a slow/stalled write is a real failure
     * worth surfacing, not something to silently retry here (the
     * Python-side `_command()` retry loop already owns retry policy at
     * the protocol level). */
    fun write(data: ByteArray) {
        port.write(data, WRITE_TIMEOUT_MS)
    }

    /** Reads exactly one byte, blocking up to `timeoutMs` — returns -1
     * on timeout (never throws for a plain timeout, since that's the
     * expected, common case while `esp32_flasher._read_packet` polls
     * for a SLIP frame boundary), or -1 on a real I/O error too (a
     * dropped/unplugged device looks the same to the Python-side
     * caller as "nothing arrived yet" — its own overall per-command
     * timeout is what actually surfaces a real failure to the user). */
    fun readByte(timeoutMs: Int): Int {
        val buf = ByteArray(1)
        return try {
            val n = port.read(buf, timeoutMs)
            if (n <= 0) -1 else buf[0].toInt() and 0xFF
        } catch (e: IOException) {
            -1
        }
    }

    /** IO0 — active-low: `true` pulls IO0 low (requests download/
     * bootloader mode on the next reset). See `esp32_flasher`'s own
     * doc comment for the real, source-verified reset sequence this is
     * a primitive for. */
    fun setDtr(value: Boolean) {
        try {
            // Called as a plain method, not Kotlin property syntax —
            // UsbSerialPort's interface declares `setDTR(boolean)` with
            // no matching getter, so it doesn't qualify as a synthetic
            // Kotlin property.
            port.setDTR(value)
        } catch (e: IOException) {
            // A board whose USB-serial chip doesn't wire DTR at all
            // (rare, but real) would throw here — let the overall
            // connect/sync attempt time out and report a real failure
            // rather than crashing the flash session on this one line.
        }
    }

    /** EN — active-low: `true` holds the chip in reset. */
    fun setRts(value: Boolean) {
        try {
            port.setRTS(value)
        } catch (e: IOException) {
            // See setDtr's own comment.
        }
    }

    fun close() {
        try {
            port.close()
        } catch (e: IOException) {
            // Already-closed/unplugged — nothing further to do.
        }
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 3000
    }
}
