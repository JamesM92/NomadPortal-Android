package com.jamesm92.nomadportal.connectivity.rnode

import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The actual Python-facing half of RNode-over-USB support — this exact
 * object is what `orchestrator.set_rnode_bridge(bridge, config_json)`
 * hands to `nomadportal_core.rnode_interface.NomadRNodeInterface`'s
 * constructor. Called only via Chaquopy's plain-attribute-call convention
 * (`bridge.write(data)` / `bridge.read()` / `bridge.disconnect()`), the
 * same "Kotlin owns real device I/O, Python owns RNS protocol logic"
 * split [com.jamesm92.nomadportal.connectivity.BluetoothMeshManager]
 * already established for BLE mesh's `RnsBleBridge` — Chaquopy's embedded
 * CPython interpreter has no route to Android's own USB host APIs.
 *
 * Deliberately dumb: this class owns only an already-`open()`ed
 * [UsbSerialPort] and the read buffer draining it — device enumeration,
 * the USB permission-grant flow, and driver/port selection all happen one
 * layer up in [RnodeUsbManager], which constructs this only once a port
 * is actually open. Mirrors how [com.jamesm92.nomadportal.connectivity.BluetoothMeshManager]
 * keeps connection setup (service bind) and I/O (the bridge object) as
 * two separate concerns.
 *
 * `read()` is a bounded-blocking poll, not a push callback — matches the
 * shape `RnsBleBridge` already exposes for BLE, since there's no clean
 * way to call back *into* a Python thread from a Kotlin background thread
 * across the Chaquopy boundary; `NomadRNodeInterface`'s own read loop
 * just calls this in a tight loop instead; a short real-world empty
 * result (rather than blocking forever) is what lets that loop notice
 * `detach()`/[disconnect] promptly.
 */
class RnodeUsbBridge(private val port: UsbSerialPort) {

    // Bounded so a stalled Python-side reader can't let this grow forever
    // and pin memory — same backpressure reasoning any bounded queue
    // between a fast producer and a slower consumer needs. A full queue
    // drops the oldest chunk rather than blocking the USB read thread,
    // since blocking it would eventually stall SerialInputOutputManager's
    // own internal read loop.
    private val inbound = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    @Volatile private var closed = false

    private val ioManager = SerialInputOutputManager(
        port,
        object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                if (!inbound.offer(data)) {
                    // Queue full — drop the oldest chunk to make room
                    // rather than losing the newest one silently; either
                    // way this only happens if the Python-side reader has
                    // genuinely stalled, which is itself worth losing a
                    // little data over rather than blocking this thread.
                    inbound.poll()
                    inbound.offer(data)
                }
            }

            override fun onRunError(e: Exception) {
                Log.w(TAG, "USB I/O manager stopped: ${e.message}")
                closed = true
            }
        },
    )

    init {
        // v3.11's SerialInputOutputManager owns its own read/write
        // threads internally (a newer, simpler API than the older
        // submit-to-an-Executor pattern earlier library versions used) —
        // no Executor to manage/shut down on this class's own side.
        ioManager.start()
    }

    /** Called from Python's read thread in a tight loop — returns
     * whatever's queued within [READ_TIMEOUT_MS], or an empty array on
     * timeout (never blocks forever, so a caller looping on this can
     * still notice a cancellation flag promptly between calls). */
    fun read(): ByteArray {
        val chunk = inbound.poll(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return chunk ?: ByteArray(0)
    }

    /** Returns the number of bytes actually written — `NomadRNodeInterface
     * ._write` treats anything less than `data.size` as a real I/O
     * failure, matching how `port.write`'s own real Android USB failure
     * modes (device unplugged mid-write, permission revoked) surface. */
    fun write(data: ByteArray): Int {
        if (closed) return 0
        return try {
            port.write(data, WRITE_TIMEOUT_MS)
            data.size
        } catch (e: IOException) {
            Log.w(TAG, "USB write failed: ${e.message}")
            closed = true
            0
        }
    }

    fun isConnected(): Boolean = !closed

    fun disconnect() {
        if (closed) return
        closed = true
        try {
            ioManager.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping USB I/O manager: ${e.message}")
        }
        try {
            port.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing USB port: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "RnodeUsbBridge"
        const val QUEUE_CAPACITY = 256
        const val READ_TIMEOUT_MS = 200L
        const val WRITE_TIMEOUT_MS = 2000
    }
}
