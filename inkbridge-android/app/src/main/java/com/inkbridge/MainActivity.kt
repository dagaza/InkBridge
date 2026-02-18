package com.inkbridge

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

    // --- Bluetooth state ---
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var showBluetoothPicker by mutableStateOf(false)
    private var pairedBtDevices by mutableStateOf<List<BluetoothDevice>>(emptyList())
    private var nearbyBtDevices by mutableStateOf<List<BluetoothDevice>>(emptyList())
    private var isBtScanning by mutableStateOf(false)

    // Receives ACTION_FOUND broadcasts during a discovery scan.
    private val bluetoothScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let { d ->
                        if (nearbyBtDevices.none { it.address == d.address }) {
                            nearbyBtDevices = nearbyBtDevices + d
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    isBtScanning = false
                }
            }
        }
    }

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
                        lifecycleScope.launch {
                            runConnection(accessory, rootView)
                        }
                    } else {
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

    // Permission launcher for Android 13+ Wi-Fi scanning
    private val requestWifiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            val rootView = window.decorView.findViewById<View>(android.R.id.content)
            runWifiDiscovery(rootView)
        } else {
            statusMessage = "Permission Denied. Cannot Scan."
        }
    }

    // Permission launcher for Android 12+ Bluetooth
    private val requestBluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            openBluetoothPicker()
        } else {
            statusMessage = "Bluetooth permission denied."
        }
    }

    // ==========================================
    // LIFECYCLE
    // ==========================================

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "InkBridge::ActiveSession")

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        // Register USB receiver
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        // Register Bluetooth scan receiver
        val btFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(bluetoothScanReceiver, btFilter)

        // Initialise Bluetooth adapter
        bluetoothAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter

        // Apply saved theme preference
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val savedMode = prefs.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_YES)
        AppCompatDelegate.setDefaultNightMode(savedMode)

        setContent {
            val nightMode = AppCompatDelegate.getDefaultNightMode()
            val isDark = nightMode != AppCompatDelegate.MODE_NIGHT_NO

            val colorScheme = if (isDark) {
                darkColorScheme(
                    primary     = Color(0xFFBB86FC),
                    secondary   = Color(0xFF03DAC5),
                    background  = Color(0xFF121212),
                    surface     = Color(0xFF1E1E1E)
                )
            } else {
                lightColorScheme(
                    primary     = Color(0xFF6200EE),
                    secondary   = Color(0xFF03DAC5),
                    background  = Color(0xFFF5F5F5),
                    surface     = Color(0xFFFFFFFF)
                )
            }

            MaterialTheme(colorScheme = colorScheme) {
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
                    BackHandler { exitApp() }
                    val rootView = LocalView.current
                    InkBridgeDashboard(
                        status = statusMessage,
                        onConnectRequested = { method, ip, port ->
                            if (method == "USB") {
                                findAndConnectUsb(rootView)
                            }
                        }
                    )
                }
            }
        }

        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(bluetoothScanReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        bluetoothAdapter?.let { BluetoothStreamService.cancelDiscovery(it) }
    }

    override fun onResume() {
        super.onResume()
        if (isConnected) hideSystemUI()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // ==========================================
    // INTENT HANDLING
    // ==========================================

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

    // ==========================================
    // HELPERS
    // ==========================================

    private fun exitApp() {
        disconnectAll()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        finishAffinity()
    }

    private fun triggerPendingConnection() {
        val accessory = pendingAccessory ?: return
        val rootView = window.decorView.findViewById<View>(android.R.id.content)
        pendingAccessory = null
        connectWithRetry(accessory, rootView)
    }

    private fun connectWithRetry(accessory: UsbAccessory, view: View) {
        lifecycleScope.launch {
            statusMessage = "Verifying..."
            var connected = false
            withContext(Dispatchers.IO) {
                for (i in 0..5) {
                    connected = UsbStreamService.connect(usbManager, accessory)
                    if (connected) break
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

    private fun disconnectAll() {
        lifecycleScope.launch(Dispatchers.IO) {
            UsbStreamService.closeStream()
            NetworkStreamService.closeStream()
            BluetoothStreamService.closeStream()
            withContext(Dispatchers.Main) {
                isConnected = false
                statusMessage = "Ready to Connect"
                showTroubleshootingHint = false
            }
        }
    }

    private fun hideSystemUI() {
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

    // ==========================================
    // LOGIC: USB CONNECTION
    // ==========================================

    private fun findAndConnectUsb(view: View) {
        showTroubleshootingHint = false
        lifecycleScope.launch {
            val accessories = usbManager.accessoryList
            if (accessories.isNullOrEmpty()) {
                statusMessage = "No USB Device Found."
                withContext(Dispatchers.IO) { UsbStreamService.closeStream() }
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
        withContext(Dispatchers.IO) {
            withContext(Dispatchers.Main) { statusMessage = "Resetting..." }
            UsbStreamService.closeStream()
            withContext(Dispatchers.Main) { statusMessage = "Opening Connection..." }
            val success = UsbStreamService.connect(usbManager, accessory)
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
    // LOGIC: WIFI CONNECTION
    // ==========================================

    private fun attemptWifiDiscovery(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.NEARBY_WIFI_DEVICES
            if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
                runWifiDiscovery(view)
            } else {
                statusMessage = "Requesting Wi-Fi Permission..."
                requestWifiPermissionLauncher.launch(permission)
            }
        } else {
            runWifiDiscovery(view)
        }
    }

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
    // LOGIC: BLUETOOTH CONNECTION
    // ==========================================

    private fun findAndConnectBluetooth() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            statusMessage = "Bluetooth is not enabled."
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ).filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                requestBluetoothPermissionLauncher.launch(needed.toTypedArray())
                return
            }
        }
        openBluetoothPicker()
    }

    @SuppressLint("MissingPermission")
    private fun openBluetoothPicker() {
        val adapter = bluetoothAdapter ?: return
        pairedBtDevices = BluetoothStreamService.getPairedDevices(adapter)
        nearbyBtDevices = emptyList()
        isBtScanning = false
        showBluetoothPicker = true
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothScan() {
        val adapter = bluetoothAdapter ?: return
        nearbyBtDevices = emptyList()
        isBtScanning = true
        BluetoothStreamService.startDiscovery(adapter)
    }

    @SuppressLint("MissingPermission")
    private fun connectBluetooth(device: BluetoothDevice, view: View) {
        showBluetoothPicker = false
        bluetoothAdapter?.let { BluetoothStreamService.cancelDiscovery(it) }
        isBtScanning = false
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { statusMessage = "Connecting via Bluetooth..." }
            BluetoothStreamService.closeStream()
            val success = BluetoothStreamService.connect(device)
            withContext(Dispatchers.Main) {
                if (success) {
                    BluetoothStreamService.updateView(view)
                    isConnected = true
                    statusMessage = "Connected via Bluetooth"
                } else {
                    statusMessage = "Bluetooth connection failed. Is the desktop app running?"
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
                    setBackgroundColor(android.graphics.Color.BLACK)
                    val textView = TextView(context).apply {
                        text = "InkBridge Active\n(Touch to Draw)"
                        setTextColor(android.graphics.Color.DKGRAY)
                        textSize = 20f
                        gravity = Gravity.CENTER
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER
                        )
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
                    BluetoothStreamService.updateView(this)
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

        val context = LocalContext.current
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        var isDarkTheme by remember {
            mutableStateOf(
                prefs.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_YES) == AppCompatDelegate.MODE_NIGHT_YES
            )
        }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize()) {

                // ---- THEME TOGGLE (top-right corner) ----
                IconButton(
                    onClick = {
                        val newMode = if (isDarkTheme)
                            AppCompatDelegate.MODE_NIGHT_NO
                        else
                            AppCompatDelegate.MODE_NIGHT_YES
                        isDarkTheme = !isDarkTheme
                        AppCompatDelegate.setDefaultNightMode(newMode)
                        prefs.edit().putInt("night_mode", newMode).apply()
                        (context as? Activity)?.recreate()
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    Icon(
                        imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                        contentDescription = if (isDarkTheme) "Switch to light mode" else "Switch to dark mode",
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )
                }
                // -----------------------------------------

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top
                ) {
                    Spacer(modifier = Modifier.weight(2f))

                    // Logo / wordmark
                    Image(
                        painter = painterResource(id = R.drawable.ic_wordmark),
                        contentDescription = "InkBridge Logo",
                        modifier = Modifier
                            .fillMaxWidth(0.4f)
                            .padding(vertical = 24.dp),
                        contentScale = ContentScale.FillWidth
                    )

                    Spacer(modifier = Modifier.height(150.dp))
                    StatusCard(status = status)

                    if (showTroubleshootingHint) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFF332200)
                            ),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            Text(
                                text = "First time connecting? If you just checked 'Always allow', please press Connect again.",
                                color = Color(0xFFFFCC00),
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(48.dp))

                    // USB button — gradient style
                    GradientConnectButton(
                        text = "Connect via USB",
                        icon = Icons.Default.Usb,
                        onClick = { onConnectRequested("USB", "", "") }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Bluetooth button — same gradient style
                    GradientConnectButton(
                        text = "Connect via Bluetooth",
                        icon = Icons.Default.Bluetooth,
                        onClick = { findAndConnectBluetooth() }
                    )

                    // --- HIDE WIFI FOR V0.2 ---
                    /*
                    Spacer(modifier = Modifier.height(16.dp))
                    GradientConnectButton("Auto-Discover WiFi", Icons.Default.Wifi) {
                        onConnectRequested("WIFI_DISCOVER", "", "")
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    TextButton(onClick = { showWifiDialog = true }) {
                        Text(text = "Manual IP Input", color = Color.Gray)
                    }
                    */
                    // --------------------------

                    Spacer(modifier = Modifier.weight(1f))

                    OutlinedButton(
                        onClick = { exitApp() },
                        modifier = Modifier.widthIn(min = 200.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFCF6679)
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            Color(0xFFCF6679).copy(alpha = 0.5f)
                        )
                    ) {
                        Text("Exit Application")
                    }
                }
            }
        }

        // Bluetooth picker dialog — outside the Surface but inside the composable
        if (showBluetoothPicker) {
            val rootView = LocalView.current
            BluetoothDevicePickerDialog(
                pairedDevices    = pairedBtDevices,
                nearbyDevices    = nearbyBtDevices,
                isScanning       = isBtScanning,
                onScanRequested  = { startBluetoothScan() },
                onDeviceSelected = { device -> connectBluetooth(device, rootView) },
                onDismiss        = {
                    showBluetoothPicker = false
                    bluetoothAdapter?.let { BluetoothStreamService.cancelDiscovery(it) }
                    isBtScanning = false
                }
            )
        }

        if (showWifiDialog) {
            WifiConnectDialog(
                onDismiss = { showWifiDialog = false },
                onConnect = { ip, port ->
                    showWifiDialog = false
                    onConnectRequested("WIFI_MANUAL", ip, port)
                }
            )
        }
    }

    @Composable
    fun GradientConnectButton(
        text: String,
        icon: ImageVector,
        onClick: () -> Unit
    ) {
        val gradientBrush = Brush.radialGradient(
            colors = listOf(
                Color(0xFF02FAFF), // Cyan
                Color(0xFF6801FF)  // Purple
            ),
            radius = 600f
        )

        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
            contentPadding = PaddingValues(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .width(280.dp)
                .height(56.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(gradientBrush),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = text,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }

    @Composable
    fun StatusCard(status: String) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Cable, null, tint = Color.Gray, modifier = Modifier.size(40.dp))
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
        Button(
            onClick = onClick,
            modifier = Modifier.widthIn(min = 250.dp).height(60.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
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
                    OutlinedTextField(
                        value = ip,
                        onValueChange = { ip = it },
                        label = { Text("Host IP") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("Port") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth()
                    ) {
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

    override fun dispatchGenericMotionEvent(ev: MotionEvent?): Boolean {
        return super.dispatchGenericMotionEvent(ev)
    }
}