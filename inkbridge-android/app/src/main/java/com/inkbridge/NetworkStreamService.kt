package com.inkbridge

import android.util.Log
import android.view.View
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

object NetworkStreamService {
    private const val TAG = "InkBridge"
    private const val CONNECTION_TIMEOUT = 5000

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null

    fun streamTouchInputToWifi(host: String, port: Int, view: View) {
        try {
            socket = Socket().apply {
                connect(InetSocketAddress(host, port), CONNECTION_TIMEOUT)
            }
            outputStream = socket?.getOutputStream()

            val touchListener = TouchListener(outputStream!!)

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
        }
    }
}