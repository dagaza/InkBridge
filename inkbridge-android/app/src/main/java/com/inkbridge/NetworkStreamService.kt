package com.inkbridge

import android.util.Log
import android.view.View
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

object NetworkStreamService {
    private const val TAG = "InkBridgeNetwork"
    private const val CONNECTION_TIMEOUT = 5000

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var currentListener: TouchListener? = null

    // --- NEW: Called by MainActivity to swap the drawing surface ---
    fun updateView(view: View) {
        if (currentListener != null) {
            view.setOnTouchListener(currentListener)
            view.setOnGenericMotionListener(currentListener)
        }
    }

    fun streamTouchInputToWifi(host: String, port: Int, view: View) {
        try {
            socket = Socket().apply {
                connect(InetSocketAddress(host, port), CONNECTION_TIMEOUT)
            }
            outputStream = socket?.getOutputStream()

            val touchListener = TouchListener(outputStream!!)
            currentListener = touchListener

            view.post {
                view.setOnTouchListener(touchListener)
                view.setOnGenericMotionListener(touchListener)
            }
        } catch (e: IOException) {
            Log.e(TAG, "WiFi Stream Error", e)
        }
    }

    fun closeStream() {
        try {
            outputStream?.close()
            socket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing WiFi stream", e)
        } finally {
            outputStream = null
            socket = null
            currentListener = null
        }
    }
}