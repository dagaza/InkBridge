package com.inkbridge

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

    // Heartbeat
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
    fun connect(usbManager: UsbManager, accessory: UsbAccessory): Boolean {
        if (isStreamOpen) {
            closeStream() // Force clean reset
        }

        return try {
            Log.d(TAG, "Attempting to open accessory: ${accessory.description}")
            fileDescriptor = usbManager.openAccessory(accessory)

            if (fileDescriptor == null) {
                Log.e(TAG, "openAccessory returned NULL")
                return false
            }

            val fd: FileDescriptor = fileDescriptor!!.fileDescriptor
            val rawOutputStream = FileOutputStream(fd)
            
            // Start the Writer Thread
            isStreamOpen = true
            queue.clear()
            
            workerThread = Thread {
                writeLoop(rawOutputStream)
            }.apply { 
                name = "InkBridge-UsbWriter"
                start() 
            }

            // Create wrapper that pushes to Queue
            val queueWrapper = object : OutputStream() {
                override fun write(b: Int) { /* Unused */ }
                override fun write(b: ByteArray) {
                    // Non-blocking offer. Drop if full to prevent lag.
                    if (!queue.offer(b)) {
                        // Queue full, packet dropped (Backpressure)
                    }
                }
            }

            // Initialize Listener
            currentListener = TouchListener(queueWrapper)
            
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
                // Poll: Wait up to 500ms for a touch packet
                val packet = queue.poll(500, TimeUnit.MILLISECONDS)
                
                if (packet != null) {
                    stream.write(packet)
                } else {
                    // Timeout = Send Heartbeat
                    stream.write(heartbeat)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Write Loop Error: ${e.message}")
                // If the stream dies, we close everything
                isStreamOpen = false
                break
            }
        }
        
        // Cleanup when loop exits
        try { stream.close() } catch (e: Exception) {}
    }

    fun closeStream() {
        Log.d(TAG, "Closing Stream...")
        isStreamOpen = false
        try {
            workerThread?.interrupt() // Wake up thread if sleeping
            workerThread?.join(1000)  // Wait for it to die
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