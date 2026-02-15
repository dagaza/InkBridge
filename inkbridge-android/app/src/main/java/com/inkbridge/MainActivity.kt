package com.inkbridge

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CheckCircle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                val rootView = LocalView.current
                InkBridgeDashboard(rootView)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        UsbStreamService.closeStream()
        NetworkStreamService.closeStream()
    }

    private fun startUsbConnection(view: View, onResult: (String) -> Unit) {
        lifecycleScope.launch {
            val accessories = usbManager.accessoryList
            if (accessories.isNullOrEmpty()) {
                onResult("No USB Accessory found.\nIs the Desktop App running?")
                return@launch
            }
            val accessory = accessories[0]
            withContext(Dispatchers.IO) {
                UsbStreamService.streamTouchInputToUsb(usbManager, accessory, view)
            }
            onResult("Connected to ${accessory.description ?: "Device"}")
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
                onResult("WiFi Stream Started to $ip:$portNum")
            }
        }
    }

    @Composable
    fun InkBridgeDashboard(rootView: View) {
        var statusMessage by remember { mutableStateOf("Ready to Connect") }
        var isConnected by remember { mutableStateOf(false) }
        var showWifiDialog by remember { mutableStateOf(false) }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "InkBridge",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                // FIX 1: Automatically pull version from build.gradle
                // If this is red, build the project once so BuildConfig is generated.
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(48.dp))

                // FIX 3: Status Card is now centered and wrapped
                StatusCard(status = statusMessage, isConnected = isConnected)

                Spacer(modifier = Modifier.height(48.dp))

                if (!isConnected) {
                    // FIX 2: Buttons use 'ConnectButton' which restricts width
                    ConnectButton(
                        text = "Connect via USB",
                        icon = Icons.Default.Usb,
                        onClick = {
                            statusMessage = "Connecting USB..."
                            startUsbConnection(rootView) { result ->
                                statusMessage = result
                                if (result.contains("Connected")) isConnected = true
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Wifi Button with matching width logic
                    OutlinedButton(
                        onClick = { showWifiDialog = true },
                        // Min width 250dp ensures it's not tiny, but doesn't fill screen
                        modifier = Modifier.widthIn(min = 250.dp).height(56.dp)
                    ) {
                        Icon(Icons.Default.Wifi, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Connect via WiFi", fontSize = 18.sp)
                    }
                } else {
                    Button(
                        onClick = {
                            UsbStreamService.closeStream()
                            NetworkStreamService.closeStream()
                            isConnected = false
                            statusMessage = "Disconnected"
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.widthIn(min = 250.dp).height(56.dp)
                    ) {
                        Text("Disconnect")
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "Screen is active. Touch to draw.", color = Color.Gray)
                }
            }
        }

        if (showWifiDialog) {
            WifiConnectDialog(
                onDismiss = { showWifiDialog = false },
                onConnect = { ip, port ->
                    showWifiDialog = false
                    startWifiConnection(ip, port, rootView) { res ->
                        statusMessage = res
                        if (res.contains("Started")) isConnected = true
                    }
                }
            )
        }
    }

    @Composable
    fun StatusCard(status: String, isConnected: Boolean) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isConnected) Color(0xFF1B5E20) else MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(16.dp),
            // Removed fillMaxWidth(), added padding for breathing room
            modifier = Modifier.wrapContentWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isConnected) Icons.Default.CheckCircle else Icons.Default.Cable,
                    contentDescription = null,
                    tint = if (isConnected) Color.Green else Color.Gray,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(text = "Status", style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = status,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    @Composable
    fun ConnectButton(text: String, icon: ImageVector, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            // Constrain width: Minimum 250dp, but let it grow slightly for long text
            modifier = Modifier.widthIn(min = 250.dp).height(60.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    // ... WifiConnectDialog remains unchanged ...
    @Composable
    fun WifiConnectDialog(onDismiss: () -> Unit, onConnect: (String, String) -> Unit) {
        var ip by remember { mutableStateOf("") }
        var port by remember { mutableStateOf("4545") }

        Dialog(onDismissRequest = onDismiss) {
            Card(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("WiFi Connection", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = ip,
                        onValueChange = { ip = it },
                        label = { Text("Host IP (e.g. 192.168.1.5)") },
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