package com.inkbridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import android.view.View
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * BluetoothStreamService
 *
 * Mirrors UsbStreamService exactly: singleton object, queue-backed write loop,
 * blocking connect() designed to be called from Dispatchers.IO, and a
 * heartbeat to keep the connection alive during idle periods.
 *
 * Uses Classic Bluetooth SPP (RFCOMM) over the well-known SPP UUID.
 * This gives a reliable, low-latency serial stream — the right choice for
 * continuous stylus event data. BLE's MTU cap and connection-interval
 * constraints make it unsuitable for this use case.
 *
 * The Android device acts as the CLIENT. The desktop companion app must
 * open an RFCOMM server socket listening on the same SPP UUID before
 * the user attempts to connect.
 */
object BluetoothStreamService {

    private const val TAG = "InkBridgeBtService"

    // Standard SPP UUID — recognised by every Bluetooth stack.
    // The desktop companion must advertise this same UUID.
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // Heartbeat size matches UsbStreamService so the desktop parser
    // can treat both transports identically.
    private const val HEARTBEAT_PACKET_SIZE = 22

    // How long to wait for the RFCOMM connection to be established.
    private const val CONNECT_TIMEOUT_MS = 8000L

    private var socket: BluetoothSocket? = null
    private var workerThread: Thread? = null
    private val queue = LinkedBlockingQueue<ByteArray>()

    @Volatile private var isStreamOpen = false
    private var currentListener: TouchListener? = null

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Registers the touch/motion listeners on a view, identical to
     * UsbStreamService.updateView(). Call this inside DrawingPad's factory.
     */
    fun updateView(view: View) {
        currentListener?.let {
            view.setOnTouchListener(it)
            view.setOnGenericMotionListener(it)
            view.requestFocus()
        }
    }

    /**
     * BLOCKING. Call from Dispatchers.IO.
     *
     * Connects to [device] via RFCOMM and starts the write loop.
     * Returns true on success, false on any failure.
     *
     * We try the secure channel first (encrypted + authenticated).
     * If the device doesn't support it (older or unpaired hardware),
     * we fall back to the insecure channel (still encrypted on modern
     * stacks, but without MITM protection). This gives maximum
     * device compatibility while preferring security when available.
     */
    @SuppressLint("MissingPermission") // Permissions are checked in MainActivity before this call
    fun connect(device: BluetoothDevice): Boolean {
        if (isStreamOpen) closeStream()

        Log.d(TAG, "Connecting to ${device.name} (${device.address})")

        val btSocket = openSocket(device) ?: return false

        return try {
            // BluetoothSocket.connect() is blocking — must be on IO thread.
            btSocket.connect()

            val outputStream = btSocket.outputStream
            socket = btSocket
            isStreamOpen = true
            queue.clear()

            workerThread = Thread {
                writeLoop(outputStream)
            }.apply {
                name = "InkBridge-BtWriter"
                start()
            }

            // Wrap the output stream in the same queue-backed wrapper
            // used by UsbStreamService so TouchListener is transport-agnostic.
            val queueWrapper = object : OutputStream() {
                override fun write(b: Int) { /* unused */ }
                override fun write(b: ByteArray) {
                    // Non-blocking offer — drop packet on backpressure,
                    // same behaviour as USB to prevent stroke lag.
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

    /**
     * Closes the stream cleanly. Safe to call from any thread.
     * Mirrors UsbStreamService.closeStream() exactly.
     */
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
    // Device discovery helpers (called from MainActivity)
    // ------------------------------------------------------------------

    /**
     * Returns all devices the system has already paired with.
     * No scan needed — instant result. Shown as the primary list
     * in the picker dialog.
     */
    @SuppressLint("MissingPermission")
    fun getPairedDevices(adapter: BluetoothAdapter): List<BluetoothDevice> {
        return adapter.bondedDevices.toList()
    }

    /**
     * Starts a Classic Bluetooth discovery scan.
     * Results arrive via BluetoothDevice.ACTION_FOUND broadcast —
     * register a receiver in MainActivity and collect devices there.
     *
     * Note: discovery is a heavy operation that temporarily degrades
     * connection throughput. We only run it on explicit user request
     * (the "Scan for new devices" action in the picker).
     */
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

    /**
     * Tries to open a secure RFCOMM socket, falls back to insecure
     * if that fails. Insecure sockets are still encrypted on Android 10+
     * but skip MITM pairing — acceptable for LAN/local use.
     */
    @SuppressLint("MissingPermission")
    private fun openSocket(device: BluetoothDevice): BluetoothSocket? {
        return try {
            Log.d(TAG, "Trying secure RFCOMM socket...")
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
     * Write loop — identical structure to UsbStreamService.writeLoop().
     * Polls the queue with a 500 ms timeout; sends a heartbeat on idle
     * so the desktop can detect a dropped connection quickly.
     */
    private fun writeLoop(stream: OutputStream) {
        val heartbeat = ByteArray(HEARTBEAT_PACKET_SIZE) { 127.toByte() }

        while (isStreamOpen) {
            try {
                val packet = queue.poll(500, TimeUnit.MILLISECONDS)
                if (packet != null) {
                    stream.write(packet)
                } else {
                    stream.write(heartbeat)
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
