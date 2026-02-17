package com.inkbridge

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.View
import kotlinx.coroutines.*
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

object UsbStreamService {

    private const val TAG = "InkBridgeUsbService"
    private var fileDescriptor: ParcelFileDescriptor? = null
    
    // Use our thread-safe wrapper
    private var outputStream: SafeOutputStream? = null

    private var currentListener: TouchListener? = null
    private var isStreamOpen = false
    
    // TRACKING FOR HEARTBEAT
    @Volatile private var lastActivityTime: Long = 0L
    private const val HEARTBEAT_PACKET_SIZE = 22 // As you specified

    fun updateView(view: View) {
        if (currentListener != null) {
            view.setOnTouchListener(currentListener)
            view.setOnGenericMotionListener(currentListener)
            Log.d(TAG, "TouchListener re-attached to new View.")
        }
    }

    fun streamTouchInputToUsb(
        usbManager: UsbManager,
        accessory: UsbAccessory,
        view: View
    ) {
        if (isStreamOpen) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Opening accessory: ${accessory.description}")
                fileDescriptor = usbManager.openAccessory(accessory)

                if (fileDescriptor == null) {
                    Log.e(TAG, "Failed to open accessory.")
                    return@launch
                }

                val fd: FileDescriptor = fileDescriptor!!.fileDescriptor
                val rawStream = FileOutputStream(fd)
                
                // Wrap in our synchronized, suicide-capable stream
                outputStream = SafeOutputStream(rawStream)
                isStreamOpen = true
                lastActivityTime = System.currentTimeMillis()

                withContext(Dispatchers.Main) {
                    val touchListener = TouchListener(outputStream!!)
                    currentListener = touchListener
                    view.setOnTouchListener(touchListener)
                    view.setOnGenericMotionListener(touchListener)
                }

                // --- START THE HEARTBEAT LOOP ---
                launchHeartbeat(this)

            } catch (e: Exception) {
                Log.e(TAG, "Error setting up stream", e)
                closeStream()
            }
        }
    }

    private fun launchHeartbeat(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            
            // FILL ENTIRE PACKET WITH 127
            // This guarantees C++ sees "Unknown Action" and ignores X/Y coordinates
            val heartbeatPacket = ByteArray(HEARTBEAT_PACKET_SIZE) 
            heartbeatPacket.fill(127.toByte()) 

            while (isStreamOpen) {
                delay(500) 
                val timeSinceLastTouch = System.currentTimeMillis() - lastActivityTime
                
                if (timeSinceLastTouch > 1000) {
                    try {
                        outputStream?.writeHeartbeat(heartbeatPacket)
                    } catch (e: Exception) {
                        break // Suicide logic triggers here if pipe is broken
                    }
                }
            }
        }
    }

    fun closeStream() {
        try {
            outputStream?.close()
            fileDescriptor?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing stream", e)
        } finally {
            outputStream = null
            fileDescriptor = null
            currentListener = null
            isStreamOpen = false
        }
    }

    // =========================================================================
    // THREAD-SAFE WRAPPER (Handles Synchronization & Suicide)
    // =========================================================================
    private class SafeOutputStream(private val wrapped: FileOutputStream) : OutputStream() {
        
        // Lock object to prevent Heartbeat and Touch from writing bytes at the exact same time
        private val lock = Any()

        override fun write(b: Int) {
            synchronized(lock) {
                try {
                    wrapped.write(b)
                    UsbStreamService.lastActivityTime = System.currentTimeMillis()
                } catch (e: IOException) {
                    handleBrokenPipe(e)
                }
            }
        }

        override fun write(b: ByteArray) {
            synchronized(lock) {
                try {
                    wrapped.write(b)
                    UsbStreamService.lastActivityTime = System.currentTimeMillis()
                } catch (e: IOException) {
                    handleBrokenPipe(e)
                }
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            synchronized(lock) {
                try {
                    wrapped.write(b, off, len)
                    UsbStreamService.lastActivityTime = System.currentTimeMillis()
                } catch (e: IOException) {
                    handleBrokenPipe(e)
                }
            }
        }

        // Special method for heartbeat that DOES NOT update 'lastActivityTime'
        // (We don't want the heartbeat to keep the heartbeat alive)
        fun writeHeartbeat(b: ByteArray) {
            synchronized(lock) {
                try {
                    wrapped.write(b)
                    // Do NOT update lastActivityTime here
                } catch (e: IOException) {
                    handleBrokenPipe(e)
                }
            }
        }

        override fun close() = synchronized(lock) { wrapped.close() }
        override fun flush() = synchronized(lock) { wrapped.flush() }

        private fun handleBrokenPipe(e: IOException) {
            Log.e("InkBridge", "BROKEN PIPE: Desktop disconnected. Killing App.", e)
            System.exit(0) 
        }
    }
}