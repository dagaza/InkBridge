package com.inkbridge

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.View
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

object UsbStreamService {

    private const val TAG = "InkBridgeUsbService"
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var workerThread: Thread? = null
    private var queue = LinkedBlockingQueue<ByteArray>()

    @Volatile private var isStreamOpen = false
    private var currentListener: TouchListener? = null

    private const val HEARTBEAT_PACKET_SIZE = 22

    fun updateView(view: View) {
        if (currentListener != null) {
            view.setOnTouchListener(currentListener)
            view.setOnGenericMotionListener(currentListener)
            view.requestFocus()
        }
    }

    /**
     * BLOCKING call to open the stream.
     * Call this from a background thread (Dispatchers.IO).
     */
    fun connect(usbManager: UsbManager, accessory: UsbAccessory, context: Context): Boolean {
        if (isStreamOpen) {
            closeStream()
        }

        return try {
            fileDescriptor = usbManager.openAccessory(accessory)

            if (fileDescriptor == null) {
                Log.e(TAG, "OS refused to open accessory. PC app likely not running.")
                return false
            }

            val fd: FileDescriptor = fileDescriptor!!.fileDescriptor
            val rawOutputStream = FileOutputStream(fd)

            isStreamOpen = true
            queue.clear()

            workerThread = Thread {
                writeLoop(rawOutputStream)
            }.apply {
                name = "InkBridge-UsbWriter"
                start()
            }

            val queueWrapper = object : OutputStream() {
                override fun write(b: Int) { /* Unused */ }
                override fun write(b: ByteArray) {
                    if (!queue.offer(b)) {
                        // Queue full, packet dropped (backpressure)
                    }
                }
            }

            // Read preference once at connection time — not per-event
            val stylusOnly = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("stylus_only", false)
            currentListener = TouchListener(queueWrapper, stylusOnly)

            Log.d(TAG, "USB Stream Opened Successfully")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Error in connect()", e)
            closeStream()
            false
        }
    }

    private fun writeLoop(stream: FileOutputStream) {
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
                Log.e(TAG, "Write Loop Error: ${e.message}")
                isStreamOpen = false
                break
            }
        }

        try { stream.close() } catch (e: Exception) {}
    }

    fun closeStream() {
        Log.d(TAG, "Closing Stream...")
        isStreamOpen = false
        try {
            workerThread?.interrupt()
            workerThread?.join(1000)
        } catch (e: Exception) {}

        workerThread = null
        currentListener = null

        try {
            fileDescriptor?.close()
        } catch (e: Exception) {}
        fileDescriptor = null
        queue.clear()
        Log.d(TAG, "Stream Closed.")
    }
}