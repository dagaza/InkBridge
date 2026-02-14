package com.inkbridge

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.View
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

object UsbStreamService {
    private const val TAG = "UsbStreamService"

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var fileOutputStream: FileOutputStream? = null
    private var isStreamOpen = false

    fun streamTouchInputToUsb(usbManager: UsbManager, usbAccessory: UsbAccessory, view: View) {
        try {
            if (!isStreamOpen) {
                fileOutputStream = getUsbFileOutputStream(usbManager, usbAccessory)
                fileOutputStream?.flush()

                val touchListener = TouchListener(fileOutputStream!!)

                view.post {
                    view.setOnTouchListener(touchListener)
                    view.setOnGenericMotionListener(touchListener)
                }

                isStreamOpen = true
            }
        } catch (e: IOException) {
            Log.d(TAG, "USB Stream Error", e)
        }
    }

    fun closeStream() {
        try {
            fileDescriptor?.close()
            fileOutputStream?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing stream", e)
        } finally {
            fileDescriptor = null
            fileOutputStream = null
            isStreamOpen = false
        }
    }

    private fun getUsbFileOutputStream(usbManager: UsbManager, usbAccessory: UsbAccessory): FileOutputStream {
        fileDescriptor = usbManager.openAccessory(usbAccessory)
            ?: throw FileNotFoundException("File descriptor not found")

        return FileOutputStream(fileDescriptor!!.fileDescriptor)
    }
}