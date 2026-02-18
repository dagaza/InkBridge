package com.inkbridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import android.view.View
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

object BluetoothStreamService {

    private const val TAG = "InkBridgeBtService"

    private const val DEBUG = false

    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private const val PACKET_SIZE = 22

    // Heartbeat — all 127s, same as UsbStreamService
    private val HEARTBEAT = ByteArray(PACKET_SIZE) { 127.toByte() }

    // Buffer the output stream at 16 packets worth of bytes.
    // This lets the JVM coalesce small writes at the OS level before
    // they hit the Bluetooth stack, reducing L2CAP frame overhead.
    private const val OUTPUT_BUFFER_SIZE = PACKET_SIZE * 16

    // Queue cap: at 120 packets/sec a cap of 12 gives ~100ms of buffering.
    // Older events beyond this are dropped rather than transmitted late,
    // which eliminates phantom strokes after lifting the pen.
    // USB can afford a larger queue because it's faster; Bluetooth needs
    // tighter control to avoid building up a backlog.
    private const val MAX_QUEUE_SIZE = 12

    private var socket: BluetoothSocket? = null
    private var workerThread: Thread? = null

    // Bounded queue — offer() drops silently if full, preventing backlog.
    private val queue = LinkedBlockingQueue<ByteArray>(MAX_QUEUE_SIZE)

    @Volatile private var isStreamOpen = false
    private var currentListener: TouchListener? = null

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    fun updateView(view: View) {
        currentListener?.let {
            view.setOnTouchListener(it)
            view.setOnGenericMotionListener(it)
            view.requestFocus()
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice): Boolean {
        if (isStreamOpen) closeStream()

        Log.d(TAG, "Connecting to ${device.name} (${device.address})")

        val btSocket = openSocket(device) ?: return false

        return try {
            btSocket.connect()

            // Wrap the raw socket stream in a BufferedOutputStream.
            // Writes to the buffer are fast; the buffer flushes to the
            // Bluetooth stack in larger, more efficient chunks.
            val bufferedStream = BufferedOutputStream(btSocket.outputStream, OUTPUT_BUFFER_SIZE)

            socket = btSocket
            isStreamOpen = true
            queue.clear()

            workerThread = Thread {
                writeLoop(bufferedStream)
            }.apply {
                name = "InkBridge-BtWriter"
                priority = Thread.MAX_PRIORITY // Highest priority for minimum latency
                start()
            }

            val queueWrapper = object : OutputStream() {
                override fun write(b: Int) { /* unused */ }
                override fun write(b: ByteArray) {
                    // offer() is non-blocking and drops the packet if the
                    // queue is full — intentional backpressure behaviour.
                    queue.offer(b)
                }
            }

            currentListener = TouchListener(queueWrapper)

            Log.d(TAG, "Bluetooth stream opened successfully")
            true

        } catch (e: IOException) {
            Log.e(TAG, "RFCOMM connect() failed: ${e.message}")
            try { btSocket.close() } catch (_: Exception) {}
            false
        }
    }

    fun closeStream() {
        Log.d(TAG, "Closing Bluetooth stream...")
        isStreamOpen = false

        try {
            workerThread?.interrupt()
            workerThread?.join(1000)
        } catch (_: Exception) {}

        workerThread = null
        currentListener = null

        try { socket?.close() } catch (_: Exception) {}
        socket = null
        queue.clear()

        Log.d(TAG, "Bluetooth stream closed.")
    }

    // ------------------------------------------------------------------
    // Discovery helpers
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun getPairedDevices(adapter: BluetoothAdapter): List<BluetoothDevice> =
        adapter.bondedDevices.toList()

    @SuppressLint("MissingPermission")
    fun startDiscovery(adapter: BluetoothAdapter) {
        if (adapter.isDiscovering) adapter.cancelDiscovery()
        adapter.startDiscovery()
    }

    @SuppressLint("MissingPermission")
    fun cancelDiscovery(adapter: BluetoothAdapter) {
        if (adapter.isDiscovering) adapter.cancelDiscovery()
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun openSocket(device: BluetoothDevice): BluetoothSocket? {
        return try {
            device.createRfcommSocketToServiceRecord(SPP_UUID)
        } catch (e: IOException) {
            Log.w(TAG, "Secure socket failed, falling back to insecure: ${e.message}")
            try {
                device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            } catch (e2: IOException) {
                Log.e(TAG, "Insecure socket also failed: ${e2.message}")
                null
            }
        }
    }

    /**
     * Write loop — optimised for low latency over Bluetooth.
     *
     * Key difference from UsbStreamService: instead of writing one packet
     * per iteration, we drain the entire queue in a single pass and
     * batch all pending packets into one write() call. This means one
     * L2CAP radio transaction per event burst instead of one per packet,
     * which is the single biggest latency win available in software.
     *
     * The batch buffer is reused across iterations to avoid GC pressure
     * during fast stylus movement.
     */
    private fun writeLoop(stream: BufferedOutputStream) {
        // Reusable batch buffer — pre-allocated to avoid per-frame allocation.
        val batchBuffer = ByteArrayOutputStream(OUTPUT_BUFFER_SIZE)
        // Drain target — collect up to this many packets per flush.
        val drainBatch = ArrayList<ByteArray>(MAX_QUEUE_SIZE)

        while (isStreamOpen) {
            try {
                // Block for up to 100ms waiting for the first packet.
                // 100ms is short enough that the heartbeat interval stays
                // reasonable while not burning CPU on an empty queue.
                val first = queue.poll(100, TimeUnit.MILLISECONDS)

                if (first != null) {
                    // Got at least one packet. Drain everything else that
                    // arrived while we were waiting or processing — this is
                    // the key batching step. drainTo() is non-blocking and
                    // atomic on LinkedBlockingQueue.
                    drainBatch.clear()
                    drainBatch.add(first)
                    queue.drainTo(drainBatch)

                    // Assemble all drained packets into a single byte array.
                    batchBuffer.reset()
                    for (packet in drainBatch) {
                        batchBuffer.write(packet)
                    }

                    // One write() call for the entire batch — one L2CAP frame.
                    stream.write(batchBuffer.toByteArray())
                    stream.flush()

                    if (DEBUG && drainBatch.size > 1) {
                        Log.d(TAG, "Batched ${drainBatch.size} packets in one write")
                    }
                } else {
                    // Queue empty after timeout — send heartbeat to keep
                    // the connection alive and let the desktop detect drops.
                    stream.write(HEARTBEAT)
                    stream.flush()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Bluetooth write loop error: ${e.message}")
                isStreamOpen = false
                break
            }
        }

        try { stream.close() } catch (_: Exception) {}
    }
}