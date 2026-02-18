package com.inkbridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * BluetoothDevicePickerDialog
 *
 * Shows two sections:
 *   1. Paired devices — available instantly, no scan needed
 *   2. Nearby devices — populated as the BT scan finds them
 *
 * The host (MainActivity) drives the scan lifecycle and passes results
 * in via state so this composable stays purely presentational.
 *
 * @param pairedDevices     Devices already bonded with the system
 * @param nearbyDevices     Devices found during an active scan
 * @param isScanning        Whether a discovery scan is currently running
 * @param onScanRequested   Called when the user taps "Scan for new devices"
 * @param onDeviceSelected  Called with the chosen BluetoothDevice
 * @param onDismiss         Called when the user dismisses without selecting
 */
@SuppressLint("MissingPermission")
@Composable
fun BluetoothDevicePickerDialog(
    pairedDevices: List<BluetoothDevice>,
    nearbyDevices: List<BluetoothDevice>,
    isScanning: Boolean,
    onScanRequested: () -> Unit,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {

                // ----- Header -----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Bluetooth,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Connect via Bluetooth",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "Make sure the InkBridge desktop app is running and Bluetooth is enabled on your PC.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 16.dp)
                )

                HorizontalDivider()

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    // ----- Section: Paired devices -----
                    item {
                        SectionHeader(title = "Paired Devices")
                    }

                    if (pairedDevices.isEmpty()) {
                        item {
                            EmptyHint("No paired devices found. Pair your PC in Android Bluetooth settings first.")
                        }
                    } else {
                        items(pairedDevices, key = { it.address }) { device ->
                            DeviceRow(
                                device = device,
                                isPaired = true,
                                onClick = { onDeviceSelected(device) }
                            )
                        }
                    }

                    // ----- Section: Nearby devices -----
                    item {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        SectionHeader(title = "Nearby Devices")
                    }

                    if (nearbyDevices.isEmpty() && !isScanning) {
                        item {
                            EmptyHint("Tap \"Scan\" below to discover nearby devices.")
                        }
                    }

                    if (nearbyDevices.isEmpty() && isScanning) {
                        item {
                            EmptyHint("Scanning... make sure the PC is discoverable.")
                        }
                    }

                    items(nearbyDevices, key = { it.address }) { device ->
                        DeviceRow(
                            device = device,
                            isPaired = false,
                            onClick = { onDeviceSelected(device) }
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

                // ----- Footer: scan button + dismiss -----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Scan button with spinning icon while active
                    val rotation by rememberInfiniteTransition(label = "scan_spin")
                        .animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1200, easing = LinearEasing)
                            ),
                            label = "scan_rotation"
                        )

                    OutlinedButton(
                        onClick = onScanRequested,
                        enabled = !isScanning
                    ) {
                        Icon(
                            imageVector = Icons.Default.BluetoothSearching,
                            contentDescription = null,
                            modifier = Modifier
                                .size(18.dp)
                                .then(if (isScanning) Modifier.rotate(rotation) else Modifier)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isScanning) "Scanning..." else "Scan")
                    }

                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Internal sub-composables
// ---------------------------------------------------------------------------

@SuppressLint("MissingPermission")
@Composable
private fun DeviceRow(
    device: BluetoothDevice,
    isPaired: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Use a computer icon for desktop-class devices, phone for others
        val icon = when (device.bluetoothClass?.majorDeviceClass) {
            android.bluetooth.BluetoothClass.Device.Major.COMPUTER -> Icons.Default.Computer
            android.bluetooth.BluetoothClass.Device.Major.PHONE    -> Icons.Default.PhoneAndroid
            else                                                    -> Icons.Default.Bluetooth
        }

        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name ?: "Unknown Device",
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
            Text(
                text = device.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (isPaired) {
            Text(
                text = "Paired",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
    )
}
