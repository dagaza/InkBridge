package com.inkbridge

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.ActionListener
import android.net.wifi.p2p.WifiP2pManager.GroupInfoListener
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.Arrays
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

object WifiDirectService {

    private const val TAG                = "InkBridgeP2P"
    private const val DATA_PORT          = 4545
    private const val BEACON_PORT        = 4547
    private const val BEACON_PREFIX      = "INKBRIDGE_P2P:"
    private const val P2P_SUBNET         = "192.168.49"
    private const val MAX_QUEUE_SIZE     = 12
    private const val PACKET_SIZE        = 22
    private const val OUTPUT_BUFFER_SIZE = PACKET_SIZE * 16

    private var socket: Socket? = null
    private var workerThread: Thread? = null
    private val queue = LinkedBlockingQueue<ByteArray>(MAX_QUEUE_SIZE)
    @Volatile private var isStreamOpen = false
    private var currentListener: TouchListener? = null
    private var p2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var beaconThread: Thread? = null

    // --- Callbacks ---
    var onStatusChanged: ((String) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    // Delivers SSID + passphrase to the UI for display to the user
    var onCredentialsReady: ((ssid: String, passphrase: String) -> Unit)? = null

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    fun init(context: Context) {
        p2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        p2pChannel = p2pManager?.initialize(context, context.mainLooper, null)
    }

    @SuppressLint("MissingPermission")
    fun createGroupAndConnect(context: Context) {
        val manager = p2pManager ?: run {
            onError?.invoke("WiFi Direct not available on this device.")
            return
        }
        val channel = p2pChannel ?: run {
            onError?.invoke("WiFi Direct channel could not be initialised.")
            return
        }

        onStatusChanged?.invoke("WiFi Direct: Creating P2P group...")

        manager.removeGroup(channel, object : ActionListener {
            override fun onSuccess()            { startGroup(manager, channel) }
            override fun onFailure(reason: Int) { startGroup(manager, channel) }
        })
    }

    /**
     * Call this when the user taps "Desktop is Connected" on Android.
     * Stops the beacon loop and immediately scans for the desktop TCP server.
     */
    fun userConfirmedDesktopConnected() {
        beaconThread?.interrupt()
        Thread {
            scanAndConnect()
        }.apply {
            name = "InkBridge-P2PScan"
            start()
        }
    }

    fun updateView(view: View) {
        currentListener?.let {
            view.setOnTouchListener(it)
            view.setOnGenericMotionListener(it)
            view.requestFocus()
        }
    }

    fun closeStream() {
        isStreamOpen = false
        beaconThread?.interrupt()
        beaconThread = null
        workerThread?.interrupt()
        try { workerThread?.join(1000) } catch (_: Exception) {}
        workerThread = null
        currentListener = null
        queue.clear()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        try {
            p2pManager?.removeGroup(p2pChannel, object : ActionListener {
                override fun onSuccess()       { Log.d(TAG, "P2P group removed.") }
                override fun onFailure(r: Int) { Log.w(TAG, "removeGroup failed: $r") }
            })
        } catch (_: Exception) {}
        Log.d(TAG, "WifiDirectService closed.")
    }

    // ---------------------------------------------------------------------------
    // Step 1: Create P2P group
    // ---------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startGroup(manager: WifiP2pManager, channel: WifiP2pManager.Channel) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val config = WifiP2pConfig.Builder()
                .setNetworkName("DIRECT-IB-InkBridge")
                .setPassphrase("inkbridge2024")
                .enablePersistentMode(true)
                .build()
            manager.createGroup(channel, config, object : ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "P2P group created (API 29+).")
                    fetchGroupInfoAndBeacon(manager, channel)
                }
                override fun onFailure(reason: Int) {
                    onError?.invoke("WiFi Direct: Failed to create group (error $reason). Is WiFi on?")
                }
            })
        } else {
            manager.createGroup(channel, object : ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "P2P group created (legacy).")
                    fetchGroupInfoAndBeacon(manager, channel)
                }
                override fun onFailure(reason: Int) {
                    onError?.invoke("WiFi Direct: Failed to create group (error $reason). Is WiFi on?")
                }
            })
        }
    }

    // ---------------------------------------------------------------------------
    // Step 2: Read credentials, display them, start beacon
    // ---------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun fetchGroupInfoAndBeacon(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel
    ) {
        var attempts = 0
        val maxAttempts = 5

        fun tryFetch() {
            manager.requestGroupInfo(channel, GroupInfoListener { group ->
                if (group == null || group.networkName.isNullOrEmpty()) {
                    attempts++
                    if (attempts < maxAttempts) {
                        Log.w(TAG, "Group info null, retrying ($attempts/$maxAttempts)...")
                        Handler(Looper.getMainLooper()).postDelayed({ tryFetch() }, 1000)
                    } else {
                        onError?.invoke("WiFi Direct: Could not read group info after " +
                                        "$maxAttempts attempts. Try toggling WiFi off and on.")
                    }
                    return@GroupInfoListener
                }

                val ssid       = group.networkName
                val passphrase = group.passphrase

                Log.d(TAG, "P2P group ready. SSID=$ssid")

                // Show credentials to user so they can connect the desktop manually
                onCredentialsReady?.invoke(ssid, passphrase)
                onStatusChanged?.invoke(
                    "Connect your PC WiFi to the network shown above, then tap 'Desktop is Connected'."
                )

                // Also send beacon so desktop can open TCP server and show instructions
                beaconThread = Thread {
                    sendBeacon(ssid, passphrase)
                }.apply {
                    name = "InkBridge-P2PBeacon"
                    start()
                }
            })
        }

        // 1 second delay before first attempt — group needs time to fully initialise
        Handler(Looper.getMainLooper()).postDelayed({ tryFetch() }, 1000)
    }

    // ---------------------------------------------------------------------------
    // Step 3: UDP beacon to desktop on regular WiFi broadcast
    // Resent every 2s for up to 60s. Desktop uses this to open its TCP server.
    // ---------------------------------------------------------------------------

    private fun sendBeacon(ssid: String, passphrase: String) {
        val message       = "$BEACON_PREFIX$ssid:$passphrase".toByteArray(Charsets.UTF_8)
        val broadcastAddr = InetAddress.getByName("255.255.255.255")
        var ds: DatagramSocket? = null
        try {
            ds = DatagramSocket()
            ds.broadcast = true
            val deadline = System.currentTimeMillis() + 60_000L
            while (System.currentTimeMillis() < deadline && !isStreamOpen) {
                try {
                    ds.send(DatagramPacket(message, message.size, broadcastAddr, BEACON_PORT))
                    Log.d(TAG, "Beacon sent. SSID=$ssid")
                } catch (e: IOException) {
                    Log.w(TAG, "Beacon send failed: ${e.message}")
                }
                Thread.sleep(2000)
            }
        } catch (e: InterruptedException) {
            Log.d(TAG, "Beacon interrupted — user confirmed or stream closed.")
        } catch (e: Exception) {
            Log.e(TAG, "Beacon error: ${e.message}")
        } finally {
            ds?.close()
        }
    }

    // ---------------------------------------------------------------------------
    // Step 4: Scan P2P subnet for desktop TCP server
    // ---------------------------------------------------------------------------

    private fun scanAndConnect() {
        onStatusChanged?.invoke("WiFi Direct: Scanning for desktop on P2P network...")
        val candidates = listOf("$P2P_SUBNET.2") + (3..20).map { "$P2P_SUBNET.$it" }
        for (ip in candidates) {
            if (isStreamOpen) break
            try {
                val testSocket = Socket()
                testSocket.connect(java.net.InetSocketAddress(ip, DATA_PORT), 300)
                Log.d(TAG, "Desktop found at $ip")
                openStream(testSocket)
                return
            } catch (_: Exception) {}
        }
        onError?.invoke(
            "WiFi Direct: Desktop not found. Make sure your PC WiFi is connected " +
            "to DIRECT-IB-InkBridge and InkBridge desktop is running."
        )
    }

    // ---------------------------------------------------------------------------
    // Step 5: Open data stream
    // ---------------------------------------------------------------------------

    private fun openStream(connectedSocket: Socket) {
        socket = connectedSocket
        socket?.tcpNoDelay = true
        val buffered = BufferedOutputStream(connectedSocket.outputStream, OUTPUT_BUFFER_SIZE)
        isStreamOpen = true
        queue.clear()
        workerThread = Thread { writeLoop(buffered) }.apply {
            name = "InkBridge-P2PWriter"
            priority = Thread.MAX_PRIORITY
            start()
        }
        val queueWrapper = object : OutputStream() {
            override fun write(b: Int) {}
            override fun write(b: ByteArray) { queue.offer(Arrays.copyOf(b, b.size)) }
            override fun write(b: ByteArray, off: Int, len: Int) {
                queue.offer(Arrays.copyOfRange(b, off, off + len))
            }
        }
        currentListener = TouchListener(queueWrapper)
        Log.d(TAG, "WiFi Direct stream open.")
        onConnected?.invoke()
    }

    // ---------------------------------------------------------------------------
    // Write loop — batched, identical to BluetoothStreamService
    // ---------------------------------------------------------------------------

    private fun writeLoop(stream: BufferedOutputStream) {
        val batchBuffer = ByteArrayOutputStream(OUTPUT_BUFFER_SIZE)
        val drainBatch  = ArrayList<ByteArray>(MAX_QUEUE_SIZE)
        while (isStreamOpen) {
            try {
                val first = queue.poll(100, TimeUnit.MILLISECONDS)
                if (first != null) {
                    drainBatch.clear()
                    drainBatch.add(first)
                    queue.drainTo(drainBatch)
                    batchBuffer.reset()
                    for (packet in drainBatch) batchBuffer.write(packet)
                    stream.write(batchBuffer.toByteArray())
                    stream.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "P2P write loop error: ${e.message}")
                isStreamOpen = false
                break
            }
        }
        try { stream.close() } catch (_: Exception) {}
    }
}