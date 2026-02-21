package com.inkbridge

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MainUiState(
    val isConnected: Boolean = false,
    val statusMessage: String = "Ready to Connect",
    val showTroubleshootingHint: Boolean = false,
    
    // Bluetooth State
    val showBluetoothPicker: Boolean = false,
    val pairedBtDevices: List<BluetoothDevice> = emptyList(),
    val nearbyBtDevices: List<BluetoothDevice> = emptyList(),
    val isBtScanning: Boolean = false,
    
    // WiFi Direct State
    val showWifiDirectDialog: Boolean = false,
    val wifiDirectSsid: String = "",
    val wifiDirectPassphrase: String = ""
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    fun updateStatus(message: String) {
        _uiState.update { it.copy(statusMessage = message) }
    }

    // --- USB Logic ---
    fun connectUsb(usbManager: UsbManager, accessory: UsbAccessory, context: Context) {
        _uiState.update { it.copy(showTroubleshootingHint = false, statusMessage = "Opening Connection...") }
        viewModelScope.launch(Dispatchers.IO) {
            UsbStreamService.closeStream()
            val success = UsbStreamService.connect(usbManager, accessory, context)
            withContext(Dispatchers.Main) {
                if (success) {
                    _uiState.update { it.copy(isConnected = true, statusMessage = "Connected via USB") }
                } else {
                    _uiState.update { it.copy(statusMessage = "Connection Failed. Check Driver.", showTroubleshootingHint = true) }
                }
            }
        }
    }

    // --- Bluetooth Logic ---
    @SuppressLint("MissingPermission")
    fun openBluetoothPicker(adapter: BluetoothAdapter) {
        _uiState.update { 
            it.copy(
                showBluetoothPicker = true,
                pairedBtDevices = BluetoothStreamService.getPairedDevices(adapter),
                nearbyBtDevices = emptyList(),
                isBtScanning = false
            )
        }
    }

    fun closeBluetoothPicker() {
        _uiState.update { it.copy(showBluetoothPicker = false, isBtScanning = false) }
    }

    fun addNearbyBluetoothDevice(device: BluetoothDevice) {
        _uiState.update { state ->
            if (state.nearbyBtDevices.none { it.address == device.address }) {
                state.copy(nearbyBtDevices = state.nearbyBtDevices + device)
            } else state
        }
    }

    fun setBluetoothScanning(isScanning: Boolean) {
        _uiState.update { it.copy(isBtScanning = isScanning) }
    }

    fun connectBluetooth(device: BluetoothDevice, context: Context) {
        closeBluetoothPicker()
        _uiState.update { it.copy(statusMessage = "Connecting via Bluetooth...") }
        viewModelScope.launch(Dispatchers.IO) {
            BluetoothStreamService.closeStream()
            val success = BluetoothStreamService.connect(device, context)
            withContext(Dispatchers.Main) {
                if (success) {
                    _uiState.update { it.copy(isConnected = true, statusMessage = "Connected via Bluetooth") }
                } else {
                    _uiState.update { it.copy(statusMessage = "Bluetooth connection failed. Is the desktop app running?") }
                }
            }
        }
    }

    // --- WiFi Direct Logic ---
    fun setWifiDirectCredentials(ssid: String, passphrase: String) {
        _uiState.update { 
            it.copy(
                wifiDirectSsid = ssid, 
                wifiDirectPassphrase = passphrase, 
                showWifiDirectDialog = true 
            ) 
        }
    }

    fun closeWifiDirectDialog() {
        _uiState.update { it.copy(showWifiDirectDialog = false) }
        WifiDirectService.closeStream()
    }

    fun confirmWifiDirectConnected() {
        _uiState.update { 
            it.copy(
                isConnected = true, 
                showWifiDirectDialog = false, 
                statusMessage = "Connected via WiFi Direct"
            ) 
        }
    }

    // --- General Disconnect ---
    fun disconnectAll() {
        viewModelScope.launch(Dispatchers.IO) {
            UsbStreamService.closeStream()
            WifiDirectService.closeStream()
            BluetoothStreamService.closeStream()
            withContext(Dispatchers.Main) {
                _uiState.update { 
                    it.copy(
                        isConnected = false, 
                        statusMessage = "Ready to Connect", 
                        showTroubleshootingHint = false,
                        showWifiDirectDialog = false
                    ) 
                }
            }
        }
    }
}