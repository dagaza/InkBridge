package com.inkbridge

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException

object UsbStreamService {

    private const val TAG = "InkBridgeUsbService"
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var outputStream: FileOutputStream? = null

    // We use a flag to prevent double-opening
    private var isStreamOpen = false

    fun streamTouchInputToUsb(
        usbManager: UsbManager,
        accessory: UsbAccessory,
        view: View
    ) {
        if (isStreamOpen) {
            Log.d(TAG, "Stream already open. Ignoring request.")
            return
        }

        // Use a CoroutineScope to move File I/O off the UI thread
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Attempting to open accessory: ${accessory.description}")

                fileDescriptor = usbManager.openAccessory(accessory)

                if (fileDescriptor == null) {
                    Log.e(TAG, "Failed to open accessory. FileDescriptor is null.")
                    return@launch
                }

                val fd: FileDescriptor = fileDescriptor!!.fileDescriptor
                outputStream = FileOutputStream(fd)
                isStreamOpen = true
                Log.d(TAG, "OutputStream created successfully.")

                // Switch back to Main thread to touch UI elements
                withContext(Dispatchers.Main) {
                    Log.d(TAG, "Attaching TouchListener to View...")

                    // Create the listener with the stream
                    val touchListener = TouchListener(outputStream!!)

                    // CRITICAL: Attach to the view
                    view.setOnTouchListener(touchListener)
                    view.setOnGenericMotionListener(touchListener)

                    Log.i(TAG, "SUCCESS: TouchListener attached. Please touch the screen now.")
                }

            } catch (e: Exception) {
                Log.e(TAG, "CRITICAL ERROR setting up USB stream", e)
                closeStream()
            }
        }
    }

    fun closeStream() {
        try {
            Log.d(TAG, "Closing USB stream.")
            outputStream?.close()
            fileDescriptor?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing stream", e)
        } finally {
            outputStream = null
            fileDescriptor = null
            isStreamOpen = false
        }
    }
}