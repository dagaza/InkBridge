package com.inkbridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
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
    private val HEARTBEAT = ByteArray(PACKET_SIZE) { 127.toByte() }
    private const val OUTPUT_BUFFER_SIZE = PACKET_SIZE * 16
    private const val MAX_QUEUE_SIZE = 12

    private var socket: BluetoothSocket? = null
    private var workerThread: Thread? = null
    private val queue = LinkedBlockingQueue<ByteArray>(MAX_QUEUE_SIZE)
    @Volatile private var isStreamOpen = false
    private var currentListener: TouchListener? = null

    fun updateView(view: View) {
        currentListener?.let {
            view.setOnTouchListener(it)
            view.setOnGenericMotionListener(it)
            view.requestFocus()
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, context: Context): Boolean {
        if (isStreamOpen) closeStream()

        Log.d(TAG, "Connecting to ${device.name} (${device.address})")

        val btSocket = openSocket(device) ?: return false

        return try {
            btSocket.connect()

            val bufferedStream = BufferedOutputStream(btSocket.outputStream, OUTPUT_BUFFER_SIZE)

            socket = btSocket
            isStreamOpen = true
            queue.clear()

            workerThread = Thread {
                writeLoop(bufferedStream)
            }.apply {
                name = "InkBridge-BtWriter"
                priority = Thread.MAX_PRIORITY
                start()
            }

            val queueWrapper = object : OutputStream() {
                override fun write(b: Int) { /* unused */ }
                override fun write(b: ByteArray) {
                    queue.offer(b)
                }
            }

            // Read preference once at connection time — not per-event
            val stylusOnly = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("stylus_only", false)
            currentListener = TouchListener(queueWrapper, stylusOnly)

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

    private fun writeLoop(stream: BufferedOutputStream) {
        val batchBuffer = ByteArrayOutputStream(OUTPUT_BUFFER_SIZE)
        val drainBatch = ArrayList<ByteArray>(MAX_QUEUE_SIZE)

        while (isStreamOpen) {
            try {
                val first = queue.poll(100, TimeUnit.MILLISECONDS)

                if (first != null) {
                    drainBatch.clear()
                    drainBatch.add(first)
                    queue.drainTo(drainBatch)

                    batchBuffer.reset()
                    for (packet in drainBatch) {
                        batchBuffer.write(packet)
                    }

                    stream.write(batchBuffer.toByteArray())
                    stream.flush()

                    if (DEBUG && drainBatch.size > 1) {
                        Log.d(TAG, "Batched ${drainBatch.size} packets in one write")
                    }
                } else {
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