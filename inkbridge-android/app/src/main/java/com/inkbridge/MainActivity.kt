package com.inkbridge

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager // <--- NEW
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var usbManager: UsbManager

    // Track connection state here to decide if we should intercept touches
    private var isDrawingModeActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFBB86FC),
                    secondary = Color(0xFF03DAC5),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E)
                )
            ) {
                var isConnected by remember { mutableStateOf(false) }
                var statusMessage by remember { mutableStateOf("Ready to Connect") }

                // Sync local state with class member for the interceptors
                LaunchedEffect(isConnected) {
                    isDrawingModeActive = isConnected
                }

                if (isConnected) {
                    DrawingPad(onDisconnect = {
                        UsbStreamService.closeStream()
                        NetworkStreamService.closeStream()
                        isConnected = false
                        statusMessage = "Disconnected"
                    })
                } else {
                    val rootView = LocalView.current
                    InkBridgeDashboard(
                        statusMessage = statusMessage,
                        onConnectRequested = { method, ip, port ->
                            if (method == "USB") {
                                statusMessage = "Connecting USB..."
                                startUsbConnection(rootView) { result ->
                                    statusMessage = result
                                    if (result.contains("Connected")) isConnected = true
                                }
                            } else {
                                startWifiConnection(ip, port, rootView) { result ->
                                    statusMessage = result
                                    if (result.contains("Started")) isConnected = true
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        UsbStreamService.closeStream()
        NetworkStreamService.closeStream()
    }

    // ==========================================
    // LAYER 1: KEY EVENT INTERCEPTION (BUTTONS)
    // ==========================================

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        val keyCode = event?.keyCode ?: return super.dispatchKeyEvent(event)

        // Block S-Pen Button codes (285 = Primary, 286 = Secondary/Tail)
        // Also block Camera (27) and Focus (80) which some S-Pens send via Bluetooth
        if (keyCode == 285 || keyCode == 284 || keyCode == 286 ||
            keyCode == KeyEvent.KEYCODE_CAMERA || keyCode == KeyEvent.KEYCODE_FOCUS) {

            // Log.d("InkBridge", "Blocked Restricted Key: $keyCode")
            return true // CONSUME EVENT - OS sees nothing
        }
        return super.dispatchKeyEvent(event)
    }

    // ==========================================
    // LAYER 2: GENERIC MOTION (HOVER GESTURES)
    // ==========================================

    // This catches "Air View" and hover gestures that trigger popups
    override fun dispatchGenericMotionEvent(ev: MotionEvent?): Boolean {
        if (isDrawingModeActive && ev != null) {
            // If it's a Stylus event, we want to ensure standard handling doesn't
            // trigger OS shortcuts. We let the view hierarchy handle it (our TouchListener)
            // but we might want to return 'true' if we detect specific Samsung flags.

            // For now, we rely on the View's OnGenericMotionListener which we set in UsbStreamService.
            // However, simply overriding this ensures we have the option to intercept global gestures.
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    // ==========================================
    // LAYER 3: WINDOW FOCUS & IMMERSIVE MODE
    // ==========================================

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                val typeMask = WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
                controller.hide(typeMask)
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    // ==========================================
    // CONNECTIONS & UI
    // ==========================================

    private fun startUsbConnection(view: View, onResult: (String) -> Unit) {
        lifecycleScope.launch {
            val accessories = usbManager.accessoryList
            if (accessories.isNullOrEmpty()) {
                onResult("No USB Accessory found.")
                return@launch
            }
            val accessory = accessories[0]
            
            // --- FIX: Ensure clean stream start ---
            // Move logic to IO thread and close previous connections first
            withContext(Dispatchers.IO) {
                // 1. Force close any existing stream
                UsbStreamService.closeStream()
                
                // 2. Brief pause to let OS release the file descriptor
                kotlinx.coroutines.delay(100)
                
                // 3. Start the new stream
                UsbStreamService.streamTouchInputToUsb(usbManager, accessory, view)
            }
            onResult("Connected to Device")
        }
    }

    private fun startWifiConnection(ip: String, port: String, view: View, onResult: (String) -> Unit) {
        if (ip.isBlank()) {
            onResult("Invalid IP Address")
            return
        }
        val portNum = port.toIntOrNull() ?: 4545
        lifecycleScope.launch(Dispatchers.IO) {
            NetworkStreamService.streamTouchInputToWifi(ip, portNum, view)
            withContext(Dispatchers.Main) {
                onResult("WiFi Started")
            }
        }
    }

    @Composable
    fun DrawingPad(onDisconnect: () -> Unit) {
        val padView = LocalView.current

        // Force system UI to stay hidden when entering drawing mode
        DisposableEffect(Unit) {
            hideSystemUI()
            UsbStreamService.updateView(padView)
            NetworkStreamService.updateView(padView)
            onDispose { }
        }

        BackHandler { onDisconnect() }

        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(contentAlignment = Alignment.Center) {
                Text("InkBridge Active", color = Color(0xFF1A1A1A), fontSize = 14.sp)
            }
        }
    }

    @Composable
    fun InkBridgeDashboard(
        statusMessage: String,
        onConnectRequested: (method: String, ip: String, port: String) -> Unit
    ) {
        var showWifiDialog by remember { mutableStateOf(false) }

        // --- NEW: Auto-Connect Polling ---
        // Checks for a USB accessory every 2 seconds.
        // If found, it automatically triggers the "USB" connection flow.
        LaunchedEffect(Unit) {
            while (true) {
                val accessories = usbManager.accessoryList
                if (!accessories.isNullOrEmpty()) {
                    // Accessory detected! Auto-click the connect button logic.
                    onConnectRequested("USB", "", "")
                    break // Stop polling once we trigger the request
                }
                kotlinx.coroutines.delay(2000) // Wait 2 seconds before next check
            }
        }
        // ----------------------------------

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(text = "InkBridge", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(text = "v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelLarge, color = Color.Gray)
                
                // Show "Scanning..." if we are waiting for the PC
                val displayStatus = if (statusMessage == "Ready to Connect") "Waiting for PC..." else statusMessage
                
                Spacer(modifier = Modifier.height(48.dp))
                StatusCard(status = displayStatus, isConnected = false)
                Spacer(modifier = Modifier.height(48.dp))
                
                // We keep the button as a fallback, but the loop above handles it mostly
                ConnectButton(text = "Connect via USB", icon = Icons.Default.Usb, onClick = { onConnectRequested("USB", "", "") })
                
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(onClick = { showWifiDialog = true }, modifier = Modifier.widthIn(min = 250.dp).height(56.dp)) {
                    Icon(Icons.Default.Wifi, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connect via WiFi", fontSize = 18.sp)
                }
            }
        }
        if (showWifiDialog) {
            WifiConnectDialog(onDismiss = { showWifiDialog = false }, onConnect = { ip, port ->
                showWifiDialog = false
                onConnectRequested("WIFI", ip, port)
            })
        }
    }

    @Composable
    fun StatusCard(status: String, isConnected: Boolean) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(16.dp), modifier = Modifier.wrapContentWidth()) {
            Row(modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.Cable, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(40.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(text = "Status", style = MaterialTheme.typography.labelMedium)
                    Text(text = status, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    @Composable
    fun ConnectButton(text: String, icon: ImageVector, onClick: () -> Unit) {
        Button(onClick = onClick, modifier = Modifier.widthIn(min = 250.dp).height(60.dp), shape = RoundedCornerShape(12.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    fun WifiConnectDialog(onDismiss: () -> Unit, onConnect: (String, String) -> Unit) {
        var ip by remember { mutableStateOf("") }
        var port by remember { mutableStateOf("4545") }
        Dialog(onDismissRequest = onDismiss) {
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("WiFi Connection", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text("Host IP") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { onConnect(ip, port) }) { Text("Connect") }
                    }
                }
            }
        }
    }
}