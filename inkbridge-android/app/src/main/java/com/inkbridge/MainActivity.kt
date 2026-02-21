package com.inkbridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var usbManager: UsbManager
    private val ACTION_USB_PERMISSION = "com.inkbridge.USB_PERMISSION"

    private var wakeLock: PowerManager.WakeLock? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var currentNightMode by mutableStateOf(AppCompatDelegate.MODE_NIGHT_YES)

    // --- Broadcast Receivers ---
    private val bluetoothScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    device?.let { viewModel.addNearbyBluetoothDevice(it) }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    viewModel.setBluetoothScanning(false)
                }
            }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val accessory: UsbAccessory? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY) as? UsbAccessory
                        }
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (granted && accessory != null) {
                            viewModel.connectUsb(usbManager, accessory, this@MainActivity)
                        } else {
                            viewModel.updateStatus("Permission Denied")
                        }
                    }
                }
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    viewModel.disconnectAll()
                    finishAffinity()
                    System.exit(0)
                }
            }
        }
    }

    // --- Permission Launchers ---
    private val requestBluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            bluetoothAdapter?.let { viewModel.openBluetoothPicker(it) }
        } else {
            viewModel.updateStatus("Bluetooth permission denied.")
        }
    }

    private val requestWifiDirectPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            WifiDirectService.createGroupAndConnect(this)
        } else {
            viewModel.updateStatus("WiFi Direct permission denied.")
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Hardware Services Initialization
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "InkBridge::ActiveSession")
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        bluetoothAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter

        // Register Receivers
        val filter = IntentFilter(ACTION_USB_PERMISSION).apply { addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        val btFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(bluetoothScanReceiver, btFilter)

        // Initialize WiFi Direct Callbacks
        WifiDirectService.init(this)
        WifiDirectService.onStatusChanged = { msg -> runOnUiThread { viewModel.updateStatus(msg) } }
        WifiDirectService.onError = { msg -> runOnUiThread { viewModel.updateStatus(msg) } }
        WifiDirectService.onCredentialsReady = { ssid, passphrase -> 
            runOnUiThread { viewModel.setWifiDirectCredentials(ssid, passphrase) } 
        }
        WifiDirectService.onConnected = {
            runOnUiThread {
                val rootView = window.decorView.findViewById<View>(android.R.id.content)
                WifiDirectService.updateView(rootView)
                viewModel.confirmWifiDirectConnected()
            }
        }

        // Apply Theme
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val savedMode = prefs.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_YES)
        AppCompatDelegate.setDefaultNightMode(savedMode)
        currentNightMode = savedMode

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            val isDark = currentNightMode != AppCompatDelegate.MODE_NIGHT_NO
            val colorScheme = if (isDark) {
                darkColorScheme(primary = Color(0xFFBB86FC), secondary = Color(0xFF03DAC5), background = Color(0xFF121212), surface = Color(0xFF1E1E1E))
            } else {
                lightColorScheme(primary = Color(0xFF6200EE), secondary = Color(0xFF03DAC5), background = Color(0xFFF5F5F5), surface = Color(0xFFFFFFFF))
            }

            MaterialTheme(colorScheme = colorScheme) {
                LaunchedEffect(uiState.isConnected) {
                    if (uiState.isConnected) {
                        if (wakeLock?.isHeld == false) wakeLock?.acquire(10 * 60 * 1000L)
                        hideSystemUI()
                    } else {
                        if (wakeLock?.isHeld == true) wakeLock?.release()
                    }
                }

                val rootView = LocalView.current
                if (uiState.isConnected) {
                    DrawingPadScreen(onDisconnect = { viewModel.disconnectAll() })
                } else {
                    DashboardScreen(
                        uiState = uiState,
                        viewModel = viewModel,
                        isDarkTheme = isDark,
                        onThemeChanged = { newMode ->
                            AppCompatDelegate.setDefaultNightMode(newMode)
                            prefs.edit().putInt("night_mode", newMode).apply()
                            currentNightMode = newMode
                        },
                        onConnectUsb = { findAndConnectUsb() },
                        onConnectWifiDirect = { findAndConnectWifiDirect() },
                        onConnectBluetooth = { findAndConnectBluetooth() },
                        onStartBtScan = { startBluetoothScan() },
                        onExit = { exitApp() }
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
        if (viewModel.uiState.value.isConnected) hideSystemUI()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && viewModel.uiState.value.isConnected) hideSystemUI()
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
                viewModel.connectUsb(usbManager, accessory, this)
            }
        }
    }

    private fun exitApp() {
        viewModel.disconnectAll()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        finishAffinity()
    }

    private fun hideSystemUI() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    // --- Hardware Connection Launchers ---
    private fun findAndConnectUsb() {
        val accessories = usbManager.accessoryList
        if (accessories.isNullOrEmpty()) {
            viewModel.updateStatus("No USB Device Found.")
            UsbStreamService.closeStream()
            return
        }
        val accessory = accessories[0]
        if (!usbManager.hasPermission(accessory)) {
            viewModel.updateStatus("Requesting Permission...")
            val intent = Intent(ACTION_USB_PERMISSION).apply { setPackage(packageName) }
            val permissionIntent = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            usbManager.requestPermission(accessory, permissionIntent)
        } else {
            viewModel.connectUsb(usbManager, accessory, this)
        }
    }

    private fun findAndConnectWifiDirect() {
        val permissionsNeeded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (permissionsNeeded.isNotEmpty()) {
            requestWifiDirectPermissionLauncher.launch(permissionsNeeded.toTypedArray())
        } else {
            WifiDirectService.createGroupAndConnect(this)
        }
    }

    private fun findAndConnectBluetooth() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            viewModel.updateStatus("Bluetooth is not enabled.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
                .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (needed.isNotEmpty()) {
                requestBluetoothPermissionLauncher.launch(needed.toTypedArray())
                return
            }
        }
        viewModel.openBluetoothPicker(adapter)
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothScan() {
        val adapter = bluetoothAdapter ?: return
        viewModel.setBluetoothScanning(true)
        BluetoothStreamService.startDiscovery(adapter)
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