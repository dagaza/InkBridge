package com.inkbridge

import android.util.Log
import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.Arrays

object NetworkStreamService {

    private const val TAG = "InkBridgeWifi"
    
    private var socket: Socket? = null
    private var rawOutputStream: OutputStream? = null
    private var currentListener: TouchListener? = null
    private var isStreamOpen = false

    // Threading & Queue
    private val queue = LinkedBlockingQueue<ByteArray>(100) 
    private var workerThread: Thread? = null

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

    suspend fun discoverServerIP(): String? = withContext(Dispatchers.IO) {
        var ds: DatagramSocket? = null
        try {
            ds = DatagramSocket()
            ds.broadcast = true
            ds.soTimeout = 3000 // Increased to 3s for safety

            Log.d(TAG, "Attempting discovery on Port $DISCOVERY_PORT...")

            // 1. Send Broadcast
            val sendData = DISCOVERY_MSG.toByteArray()
            val broadcastAddr = InetAddress.getByName("255.255.255.255")
            val sendPacket = DatagramPacket(sendData, sendData.size, broadcastAddr, DISCOVERY_PORT)
            ds.send(sendPacket)
            Log.d(TAG, "Broadcast sent. Waiting for reply...")

            // 2. Receive Response
            val buffer = ByteArray(1024)
            val receivePacket = DatagramPacket(buffer, buffer.size)

            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 3000) {
                try {
                    ds.receive(receivePacket)
                    
                    // DEBUG: Log exactly what we got
                    val rawMessage = String(receivePacket.data, 0, receivePacket.length)
                    val cleanMessage = rawMessage.trim()
                    val remoteIp = receivePacket.address.hostAddress
                    
                    Log.d(TAG, "Received UDP Packet from $remoteIp")
                    Log.d(TAG, "Raw Data: '$rawMessage'")
                    
                    if (cleanMessage == DISCOVERY_RESPONSE) {
                        Log.d(TAG, "Handshake Success! Found Server.")
                        return@withContext remoteIp
                    } else {
                        Log.w(TAG, "Mismatch! Expected '$DISCOVERY_RESPONSE' but got '$cleanMessage'")
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    Log.d(TAG, "Socket timed out (No reply yet...)")
                } catch (e: Exception) {
                    Log.e(TAG, "Socket Error during receive", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Critical Discovery Failure", e)
        } finally {
            ds?.close()
        }
        Log.d(TAG, "Discovery gave up.")
        return@withContext null
    }

    suspend fun streamTouchInputToWifi(ip: String, port: Int, view: View) = withContext(Dispatchers.IO) {
        if (isStreamOpen) {
            closeStream()
        }

        try {
            Log.d(TAG, "Connecting to $ip:$port")
            socket = Socket(ip, port)
            socket?.tcpNoDelay = true 
            
            rawOutputStream = socket?.getOutputStream() ?: throw Exception("Stream is null")
            isStreamOpen = true

            queue.clear()
            workerThread = Thread {
                writeLoop(rawOutputStream!!)
            }.apply {
                name = "InkBridge-WifiWriter"
                start()
            }

            // --- THE FIX IS HERE ---
            val queueWrapper = object : OutputStream() {
                // 1. We MUST override this specific method because TouchListener uses it
                override fun write(b: ByteArray, off: Int, len: Int) {
                    // 2. We MUST copy the data. 
                    // Arrays.copyOfRange creates a fresh copy so the background thread 
                    // has safe data even if the UI thread reuses the buffer.
                    val packetCopy = Arrays.copyOfRange(b, off, off + len)
                    
                    if (!queue.offer(packetCopy)) {
                        // Queue full, drop packet (backpressure)
                    }
                }

                override fun write(b: ByteArray) {
                    write(b, 0, b.size)
                }

                override fun write(b: Int) { 
                    // Fallback for single byte writes (rarely used by TouchListener)
                    queue.offer(byteArrayOf(b.toByte()))
                }
            }
            // -----------------------

            val touchListener = TouchListener(queueWrapper)
            currentListener = touchListener

            Log.d(TAG, "WiFi Stream Started Successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error opening WiFi stream", e)
            closeStream()
            throw e
        }
    }

    private fun writeLoop(stream: OutputStream) {
        try {
            while (isStreamOpen) {
                val data = queue.take() 
                stream.write(data)
                // stream.flush() is usually automatic for TCP, but good to have if Nagle's algo fights us
            }
        } catch (e: InterruptedException) {
            // Thread stopped cleanly
        } catch (e: Exception) {
            Log.e(TAG, "Write loop error", e)
        }
    }

    fun closeStream() {
        try {
            isStreamOpen = false
            workerThread?.interrupt()
            workerThread = null
            queue.clear()

            currentListener = null
            rawOutputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing stream", e)
        } finally {
            rawOutputStream = null
            socket = null
        }
    }
}