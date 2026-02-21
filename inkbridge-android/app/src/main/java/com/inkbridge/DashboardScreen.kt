package com.inkbridge

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
fun DashboardScreen(
    uiState: MainUiState,
    viewModel: MainViewModel,
    isDarkTheme: Boolean,
    onThemeChanged: (Int) -> Unit,
    onConnectUsb: () -> Unit,
    onConnectWifiDirect: () -> Unit,
    onConnectBluetooth: () -> Unit,
    onStartBtScan: () -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var showWifiDialog by remember { mutableStateOf(false) }
    var showTutorial by remember { mutableStateOf(prefs.getBoolean("first_launch", true)) }
    var isStylusOnly by remember { mutableStateOf(prefs.getBoolean("stylus_only", false)) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {

            // ---- TOP RIGHT CORNER BUTTONS ----
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Stylus Only Toggle Button
                IconButton(
                    onClick = {
                        isStylusOnly = !isStylusOnly
                        prefs.edit().putBoolean("stylus_only", isStylusOnly).apply()
                    }
                ) {
                    Icon(
                        imageVector = if (isStylusOnly) Icons.Default.Edit else Icons.Default.TouchApp,
                        contentDescription = if (isStylusOnly) "Stylus Only Mode" else "Touch & Stylus Mode",
                        tint = if (isStylusOnly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        modifier = Modifier.size(32.dp)
                    )
                }

                // Help / Tutorial Button
                IconButton(onClick = { showTutorial = true }) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Show Tutorial",
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        modifier = Modifier.size(32.dp)
                    )
                }

                // Theme Toggle Button
                IconButton(
                    onClick = {
                        val newMode = if (isDarkTheme) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
                        onThemeChanged(newMode)
                    }
                ) {
                    Icon(
                        imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                        contentDescription = if (isDarkTheme) "Switch to light mode" else "Switch to dark mode",
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            // ---- MAIN DASHBOARD CONTENT ----
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Spacer(modifier = Modifier.weight(2f))

                Image(
                    painter = painterResource(id = R.drawable.ic_wordmark),
                    contentDescription = "InkBridge Logo",
                    modifier = Modifier
                        .fillMaxWidth(0.4f)
                        .padding(vertical = 24.dp),
                    contentScale = ContentScale.FillWidth
                )

                Spacer(modifier = Modifier.height(150.dp))
                StatusCard(status = uiState.statusMessage)

                if (uiState.showTroubleshootingHint) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF332200)),
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

                GradientConnectButton(
                    text = "Connect via USB",
                    icon = Icons.Default.Usb,
                    onClick = { onConnectUsb() }
                )

                Spacer(modifier = Modifier.height(16.dp))
                GradientConnectButton(
                    text = "Connect via WiFi Direct",
                    icon = Icons.Default.Wifi,
                    onClick = { onConnectWifiDirect() }
                )

                Spacer(modifier = Modifier.height(16.dp))
                GradientConnectButton(
                    text = "Connect via Bluetooth",
                    icon = Icons.Default.Bluetooth,
                    onClick = { onConnectBluetooth() }
                )

                Spacer(modifier = Modifier.weight(1f))

                OutlinedButton(
                    onClick = { onExit() },
                    modifier = Modifier.widthIn(min = 200.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCF6679)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFCF6679).copy(alpha = 0.5f))
                ) {
                    Text("Exit Application")
                }
            }
        }
    }

    // --- DIALOGS ---

    if (showTutorial) {
        OnboardingTutorialDialog(
            onDismiss = {
                showTutorial = false
                prefs.edit().putBoolean("first_launch", false).apply()
            }
        )
    }

    if (uiState.showBluetoothPicker) {
        val rootView = LocalView.current
        BluetoothDevicePickerDialog(
            pairedDevices = uiState.pairedBtDevices,
            nearbyDevices = uiState.nearbyBtDevices,
            isScanning = uiState.isBtScanning,
            onScanRequested = { onStartBtScan() },
            onDeviceSelected = { device -> viewModel.connectBluetooth(device, context) },
            onDismiss = { viewModel.closeBluetoothPicker() }
        )
    }

    if (uiState.showWifiDirectDialog) {
        WifiDirectCredentialsDialog(
            ssid = uiState.wifiDirectSsid,
            passphrase = uiState.wifiDirectPassphrase,
            onConfirm = { WifiDirectService.userConfirmedDesktopConnected() },
            onDismiss = { viewModel.closeWifiDirectDialog() }
        )
    }

    if (showWifiDialog) {
        WifiConnectDialog(
            onDismiss = { showWifiDialog = false },
            onConnect = { ip, port ->
                showWifiDialog = false
                // Original app triggered an ignored intent here, we leave it as a no-op or handle if needed
            }
        )
    }
}

@Composable
fun WifiDirectCredentialsDialog(
    ssid: String,
    passphrase: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = isSystemInDarkTheme()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = if (isDark) Color(0xFF1A2332) else Color(0xFFF5F9FF),
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = "Connect to WiFi Direct",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color(0xFF0082C8)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "Connect your PC's WiFi to this network, then tap the button below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isDark) Color(0xFFB0BEC5) else Color(0xFF546E7A)
                )

                // SSID row
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isDark) Color(0xFF0D1B2A) else Color(0xFFFFFFFF),
                            RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Network",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isDark) Color(0xFF78909C) else Color(0xFF90A4AE)
                    )
                    Text(
                        text = ssid,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = if (isDark) Color.White else Color(0xFF1A1A2E)
                    )
                }

                // Password row
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isDark) Color(0xFF0D1B2A) else Color(0xFFFFFFFF),
                            RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Password",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isDark) Color(0xFF78909C) else Color(0xFF90A4AE)
                    )
                    Text(
                        text = passphrase,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (isDark) Color.White else Color(0xFF1A1A2E)
                    )
                }

                Text(
                    text = "The dialog will close automatically once the connection is established.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDark) Color(0xFF546E7A) else Color(0xFF90A4AE)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853))
            ) {
                Text(
                    text = "Desktop is Connected",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Cancel",
                    color = if (isDark) Color(0xFF78909C) else Color(0xFF90A4AE)
                )
            }
        }
    )
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
        radius = 800f
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