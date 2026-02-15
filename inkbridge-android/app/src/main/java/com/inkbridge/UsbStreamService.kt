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

    // We keep a reference to the listener so we can re-attach it to new Views
    private var currentListener: TouchListener? = null
    private var isStreamOpen = false

    // --- NEW: Called by MainActivity to swap the drawing surface ---
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
        if (isStreamOpen) {
            Log.d(TAG, "Stream already open. Ignoring request.")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Attempting to open accessory: ${accessory.description}")
                fileDescriptor = usbManager.openAccessory(accessory)

                if (fileDescriptor == null) {
                    Log.e(TAG, "Failed to open accessory.")
                    return@launch
                }

                val fd: FileDescriptor = fileDescriptor!!.fileDescriptor
                outputStream = FileOutputStream(fd)
                isStreamOpen = true
                Log.d(TAG, "OutputStream created successfully.")

                withContext(Dispatchers.Main) {
                    // Create the listener and save it
                    val touchListener = TouchListener(outputStream!!)
                    currentListener = touchListener

                    // Attach to the initial view
                    view.setOnTouchListener(touchListener)
                    view.setOnGenericMotionListener(touchListener)
                    Log.i(TAG, "SUCCESS: TouchListener attached.")
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
            currentListener = null
            isStreamOpen = false
        }
    }
}