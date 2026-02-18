package com.inkbridge

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var usbManager: UsbManager
    private val ACTION_USB_PERMISSION = "com.inkbridge.USB_PERMISSION"
    
    // UI State
    private var isConnected by mutableStateOf(false)
    private var statusMessage by mutableStateOf("Ready to Connect")

    private var pendingAccessory: UsbAccessory? = null
    private var showTroubleshootingHint by mutableStateOf(false)
    
    // Internal State
    private var wakeLock: PowerManager.WakeLock? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val accessory: UsbAccessory? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY) as? UsbAccessory
                    }

                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

                    if (granted && accessory != null) {
                        showTroubleshootingHint = false
                        val rootView = window.decorView.findViewById<View>(android.R.id.content)
                        // Corrected: Launch coroutine to call the suspend function
                        lifecycleScope.launch {
                            runConnection(accessory, rootView)
                        }
                    } else { // This else now correctly follows the 'if'
                        statusMessage = "Permission Denied"
                        showTroubleshootingHint = true
                    }
                }
            } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED == intent.action) {
                disconnectAll()
                finishAffinity()
                System.exit(0)
            }
        }
    }

    // Permission Launcher for Android 13+ Wi-Fi scanning
    private val requestWifiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission granted! Start the scan.
            val rootView = window.decorView.findViewById<View>(android.R.id.content)
            runWifiDiscovery(rootView)
        } else {
            statusMessage = "Permission Denied. Cannot Scan."
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Re-apply immersive mode whenever the window regains focus
            // (e.g., after the user dismisses a permission dialog)
            hideSystemUI()
        }
    }

    // 3. NEW HELPER FUNCTION
    private fun triggerPendingConnection() {
        val accessory = pendingAccessory ?: return
        val rootView = window.decorView.findViewById<View>(android.R.id.content)
        
        // Clear it so we don't loop
        pendingAccessory = null 
        
        // Use the Retry Logic we built earlier
        connectWithRetry(accessory, rootView)
    }

    private fun connectWithRetry(accessory: UsbAccessory, view: View) {
        lifecycleScope.launch {
            statusMessage = "Verifying..."
            var connected = false
            
            // Try for 3 seconds (6 attempts)
            withContext(Dispatchers.IO) {
                for (i in 0..5) {
                    // Try to connect even if the OS says "No Permission" (It might be lying/lagging)
                    connected = UsbStreamService.connect(usbManager, accessory)
                    if (connected) break
                    
                    // Wait 500ms before next try
                    kotlinx.coroutines.delay(500)
                }
            }

            if (connected) {
                isConnected = true
                statusMessage = "Connected via USB"
            } else {
                statusMessage = "Permission Denied"
            }
        }
    }

    private fun exitApp() {
    // 1. Clean up hardware and network
    disconnectAll()
    
    // 2. Release wake lock if held
    if (wakeLock?.isHeld == true) {
        wakeLock?.release()
    }
    
    // 3. Close all activities and kill the process
    finishAffinity() 
}

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "InkBridge::ActiveSession")

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = androidx.compose.ui.graphics.Color(0xFFBB86FC),
                    secondary = androidx.compose.ui.graphics.Color(0xFF03DAC5),
                    background = androidx.compose.ui.graphics.Color(0xFF121212),
                    surface = androidx.compose.ui.graphics.Color(0xFF1E1E1E)
                )
            ) {
                LaunchedEffect(isConnected) {
                    if (isConnected) {
                        if (wakeLock?.isHeld == false) wakeLock?.acquire(10 * 60 * 1000L)
                        hideSystemUI()
                    } else {
                        if (wakeLock?.isHeld == true) wakeLock?.release()
                    }
                }

                if (isConnected) {
                    DrawingPad(onDisconnect = { disconnectAll() })
                } else {
                    // If user presses back on the home screen, exit cleanly
                    BackHandler { exitApp() }
                    val rootView = LocalView.current
                    InkBridgeDashboard(
                        status = statusMessage,
                        onConnectRequested = { method, ip, port ->
                            if (method == "USB") {
                                findAndConnectUsb(rootView)
                            /* Disabled for V0.2
                        else if (method == "WIFI_DISCOVER") {
                            attemptWifiDiscovery(rootView)
                        } else {
                            attemptWifiConnection(ip, port, rootView)
                        }
                        */
                            }
                        }
                    )
                }
            }
        }

        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (isConnected) hideSystemUI()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            val accessory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY) as? UsbAccessory
            }

            if (accessory != null) {
                lifecycleScope.launch {
                    statusMessage = "Auto-Connecting..."
                    kotlinx.coroutines.delay(500)
                    val rootView = window.decorView.findViewById<View>(android.R.id.content)
                    runConnection(accessory, rootView)
                }
            }
        }
    }

    private fun disconnectAll() {
        lifecycleScope.launch(Dispatchers.IO) {
            // 1. Heavy lifting: Close hardware and network handles (IO Thread)
            UsbStreamService.closeStream()
            NetworkStreamService.closeStream()

            // 2. UI Updates: Switch back to the Main thread to update the screen
            withContext(Dispatchers.Main) {
                isConnected = false
                statusMessage = "Ready to Connect"
                // Also hide the hint if it was visible
                showTroubleshootingHint = false
            }
        }
    }

    // ==========================================
    // LOGIC: USB CONNECTION
    // ==========================================

    private fun findAndConnectUsb(view: View) {
        // Reset hint state whenever a manual attempt starts
        showTroubleshootingHint = false 
        
        lifecycleScope.launch {
            val accessories = usbManager.accessoryList
            if (accessories.isNullOrEmpty()) {
                statusMessage = "No USB Device Found."
                withContext(Dispatchers.IO) {
                UsbStreamService.closeStream()
                }
                return@launch
            }
            val accessory = accessories[0]

            if (!usbManager.hasPermission(accessory)) {
                statusMessage = "Requesting Permission..."
                val intent = Intent(ACTION_USB_PERMISSION).apply { setPackage(packageName) }
                val permissionIntent = PendingIntent.getBroadcast(
                    this@MainActivity, 0, intent, PendingIntent.FLAG_IMMUTABLE
                )
                usbManager.requestPermission(accessory, permissionIntent)
            } else {
                runConnection(accessory, view)
            }
        }
    }

    private suspend fun runConnection(accessory: UsbAccessory, view: View) {
        // Run blocking connection on IO thread
        withContext(Dispatchers.IO) {
            // 1. Cleanup old
            withContext(Dispatchers.Main) { statusMessage = "Resetting..." }
            UsbStreamService.closeStream()
            
            // 2. Open new
            withContext(Dispatchers.Main) { statusMessage = "Opening Connection..." }
            val success = UsbStreamService.connect(usbManager, accessory)
            
            // 3. Update UI
            withContext(Dispatchers.Main) {
                if (success) {
                    isConnected = true
                    statusMessage = "Connected"
                } else {
                    statusMessage = "Connection Failed. Check Driver."
                }
            }
        }
    }

    // ==========================================
    // LOGIC: WIFI CONNECTION (Unchanged)
    // ==========================================

    // 1. THE GATEKEEPER: Checks permission first
    private fun attemptWifiDiscovery(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // On Android 13+, we must ask for permission explicitly
            val permission = Manifest.permission.NEARBY_WIFI_DEVICES
            
            if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
                runWifiDiscovery(view)
            } else {
                statusMessage = "Requesting Wi-Fi Permission..."
                requestWifiPermissionLauncher.launch(permission)
            }
        } else {
            // Older Android versions don't need runtime permission for this
            runWifiDiscovery(view)
        }
    }

    // 2. THE WORKER: Performs the actual scan (Renamed from old attemptWifiDiscovery)
    private fun runWifiDiscovery(view: View) {
        statusMessage = "Scanning for PC..."
        lifecycleScope.launch(Dispatchers.IO) {
            val ip = NetworkStreamService.discoverServerIP()
            if (ip != null) {
                withContext(Dispatchers.Main) {
                    statusMessage = "Found $ip. Connecting..."
                    attemptWifiConnection(ip, "4545", view)
                }
            } else {
                withContext(Dispatchers.Main) {
                    statusMessage = "Discovery Failed. Try Manual IP."
                }
            }
        }
    }

    private fun attemptWifiConnection(ip: String, port: String, view: View) {
        if (ip.isBlank()) return
        val portNum = port.toIntOrNull() ?: 4545
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                NetworkStreamService.streamTouchInputToWifi(ip, portNum, view)
                withContext(Dispatchers.Main) {
                    isConnected = true
                    statusMessage = "Connected via WiFi"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusMessage = "WiFi Error: ${e.message}"
                }
            }
        }
    }

    // ==========================================
    // UI COMPONENTS
    // ==========================================

    @Composable
    fun DrawingPad(onDisconnect: () -> Unit) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context: Context ->
                FrameLayout(context).apply {
                    setBackgroundColor(Color.BLACK)
                    
                    val textView = TextView(context).apply {
                        text = "InkBridge Active\n(Touch to Draw)"
                        setTextColor(Color.DKGRAY)
                        textSize = 20f
                        gravity = Gravity.CENTER
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER
                        )
                        // Make the label purely decorative — no touch, focus, or selection
                        isClickable = false
                        isLongClickable = false
                        isFocusable = false
                        setTextIsSelectable(false)
                    }
                    addView(textView)

                    isFocusable = true
                    isFocusableInTouchMode = true
                    keepScreenOn = true
                    
                    UsbStreamService.updateView(this)
                    NetworkStreamService.updateView(this)
                }
            },
            update = { view: View ->
                view.requestFocus()
                hideSystemUI()
            }
        )
        BackHandler { onDisconnect() }
    }

    @Composable
fun InkBridgeDashboard(
    status: String,
    onConnectRequested: (method: String, ip: String, port: String) -> Unit
) {
    var showWifiDialog by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "InkBridge", 
                style = MaterialTheme.typography.displayLarge, 
                color = MaterialTheme.colorScheme.primary, 
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            StatusCard(status = status)

            // --- TROUBLESHOOTING HINT ---
            if (showTroubleshootingHint) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = androidx.compose.ui.graphics.Color(0xFF332200)
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = "First time connecting? If you just checked 'Always allow', please press Connect again.",
                        color = androidx.compose.ui.graphics.Color(0xFFFFCC00),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
            
            ConnectButton("Connect via USB", Icons.Default.Usb) { 
                onConnectRequested("USB", "", "") 
            }
            
            // --- HIDE WIFI FOR V0.2 ---
            /*
            Spacer(modifier = Modifier.height(16.dp))
            
            ConnectButton("Auto-Discover WiFi", Icons.Default.Wifi) { 
                onConnectRequested("WIFI_DISCOVER", "", "") 
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            TextButton(onClick = { showWifiDialog = true }) {
                Text(
                    text = "Manual IP Input", 
                    color = androidx.compose.ui.graphics.Color.Gray
                )
            }
            */
            // --------------------------

            // --- THE NEW EXIT BUTTON ---
            Spacer(modifier = Modifier.weight(1f)) 
            
            OutlinedButton(
                onClick = { exitApp() }, 
                modifier = Modifier.widthIn(min = 200.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = androidx.compose.ui.graphics.Color(0xFFCF6679)
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, 
                    androidx.compose.ui.graphics.Color(0xFFCF6679).copy(alpha = 0.5f)
                )
            ) {
                Text("Exit Application")
            }
        } // End of Column
    } // End of Surface

    if (showWifiDialog) {
        WifiConnectDialog(
            onDismiss = { showWifiDialog = false }, 
            onConnect = { ip, port -> 
                showWifiDialog = false
                onConnectRequested("WIFI_MANUAL", ip, port)
            }
        )
    }
} // End of function

    @Composable
    fun StatusCard(status: String) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(16.dp)) {
            Row(modifier = Modifier.padding(32.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Cable, null, tint = androidx.compose.ui.graphics.Color.Gray, modifier = Modifier.size(40.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text("Status", style = MaterialTheme.typography.labelMedium)
                    Text(status, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    @Composable
    fun ConnectButton(text: String, icon: ImageVector, onClick: () -> Unit) {
        Button(onClick = onClick, modifier = Modifier.widthIn(min = 250.dp).height(60.dp), shape = RoundedCornerShape(12.dp)) {
            Icon(icon, null, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    fun WifiConnectDialog(onDismiss: () -> Unit, onConnect: (String, String) -> Unit) {
        var ip by remember { mutableStateOf("") }
        var port by remember { mutableStateOf("4545") }
        Dialog(onDismissRequest = onDismiss) {
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(16.dp)) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("Manual WiFi", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text("Host IP") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Button(onClick = { onConnect(ip, port) }) { Text("Connect") }
                    }
                }
            }
        }
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        val keyCode = event?.keyCode ?: return super.dispatchKeyEvent(event)
        if (keyCode == 285 || keyCode == 284 || keyCode == 286 ||
            keyCode == KeyEvent.KEYCODE_CAMERA || keyCode == KeyEvent.KEYCODE_FOCUS) {
            return true 
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent?): Boolean { return super.dispatchGenericMotionEvent(ev) }

        private fun hideSystemUI() {
        // Universal Legacy implementation (Works on API 24+ without compileSdk 30 requirements)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }
}