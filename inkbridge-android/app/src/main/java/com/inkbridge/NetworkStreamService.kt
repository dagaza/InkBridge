package com.inkbridge

import android.util.Log
import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.io.OutputStream

object NetworkStreamService {

    private const val TAG = "InkBridgeWifi"
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var currentListener: TouchListener? = null
    private var isStreamOpen = false

    // Discovery Constants
    private const val DISCOVERY_PORT = 4546
    private const val DISCOVERY_MSG = "INKBRIDGE_DISCOVER"
    private const val DISCOVERY_RESPONSE = "I_AM_INKBRIDGE"

    fun updateView(view: View) {
        if (currentListener != null) {
            view.setOnTouchListener(currentListener)
            view.setOnGenericMotionListener(currentListener)
        }
    }

    /**
     * Broadcasts a UDP packet and waits for the Linux Desktop to reply.
     * Returns the IP address string of the Desktop, or null if failed.
     */
    suspend fun discoverServerIP(): String? = withContext(Dispatchers.IO) {
        var ds: DatagramSocket? = null
        try {
            ds = DatagramSocket()
            ds.broadcast = true
            ds.soTimeout = 2000 // 2 second timeout

            // 1. Send Broadcast
            val sendData = DISCOVERY_MSG.toByteArray()
            val broadcastAddr = InetAddress.getByName("255.255.255.255")
            val sendPacket = DatagramPacket(sendData, sendData.size, broadcastAddr, DISCOVERY_PORT)
            ds.send(sendPacket)
            Log.d(TAG, "Discovery packet sent...")

            // 2. Receive Response
            val buffer = ByteArray(1024)
            val receivePacket = DatagramPacket(buffer, buffer.size)

            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 2000) {
                try {
                    ds.receive(receivePacket)
                    val message = String(receivePacket.data, 0, receivePacket.length).trim()
                    
                    // Basic sanity check to ensure we aren't hearing ourselves
                    if (message == DISCOVERY_RESPONSE) {
                        val remoteIp = receivePacket.address.hostAddress
                        Log.d(TAG, "Received UDP from $remoteIp: $message")
                        return@withContext remoteIp
                    }
                } catch (e: Exception) {
                    // SocketTimeoutException is expected if no one replies
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Discovery failed", e)
        } finally {
            ds?.close()
        }
        return@withContext null
    }

    // Note: We use Dispatchers.IO for network operations
    suspend fun streamTouchInputToWifi(ip: String, port: Int, view: View) = withContext(Dispatchers.IO) {
        if (isStreamOpen) return@withContext

        try {
            Log.d(TAG, "Connecting to $ip:$port")
            socket = Socket(ip, port)
            socket?.tcpNoDelay = true 

            outputStream = socket?.getOutputStream() ?: throw Exception("Stream is null")
            isStreamOpen = true

            // Initialize Touch Listener
            // Note: We create the listener here, but attaching it to the View 
            // must happen on the Main thread.
            val touchListener = TouchListener(outputStream!!)
            currentListener = touchListener

            // We return to the caller (MainActivity) which will handle the UI update
        } catch (e: Exception) {
            Log.e(TAG, "Error opening WiFi stream", e)
            closeStream()
            throw e
        }
    }

    fun closeStream() {
        try {
            isStreamOpen = false
            currentListener = null
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing stream", e)
        } finally {
            outputStream = null
            socket = null
        }
    }
}