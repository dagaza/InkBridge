package com.inkbridge

import android.app.AlertDialog
import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbManager
    private lateinit var mainView: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        mainView = findViewById(R.id.main)

        ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        showConnectionChoiceDialog()
    }

    override fun onDestroy() {
        super.onDestroy()
        UsbStreamService.closeStream()
        NetworkStreamService.closeStream()
    }

    private fun showConnectionChoiceDialog() {
        AlertDialog.Builder(this)
            .setTitle("Select Connection")
            .setMessage("Choose how to connect to your PC host.")
            .setPositiveButton("USB") { _, _ -> connectUsb() }
            .setNegativeButton("WiFi") { _, _ -> openWifiDialog() }
            .setCancelable(false)
            .show()
    }

    private fun connectUsb() {
        // Launch a coroutine on the Main thread
        lifecycleScope.launch {
            // Switch to IO thread to poll for accessory
            val usbAccessory = withContext(Dispatchers.IO) {
                acquireUsbAccessory()
            }

            // Back on Main thread automatically
            usbAccessory?.let { accessory ->
                UsbStreamService.streamTouchInputToUsb(usbManager, accessory, mainView)
            }
        }
    }

    private fun openWifiDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val hostInput = EditText(this).apply {
            hint = "Host IP (e.g. 192.168.1.10)"
        }
        layout.addView(hostInput)

        val portInput = EditText(this).apply {
            hint = "Port (e.g. 4545)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(portInput)

        AlertDialog.Builder(this)
            .setTitle("WiFi Connection")
            .setView(layout)
            .setPositiveButton("Connect") { _, _ ->
                connectWifi(hostInput.text.toString(), portInput.text.toString())
            }
            .setNegativeButton("Cancel") { _, _ -> showConnectionChoiceDialog() }
            .setCancelable(false)
            .show()
    }

    private fun connectWifi(host: String, portValue: String) {
        if (host.trim().isEmpty()) {
            openAlertDialog("Please enter a valid host IP.")
            openWifiDialog()
            return
        }

        val port = try {
            portValue.toInt()
        } catch (e: NumberFormatException) {
            openAlertDialog("Please enter a valid port.")
            openWifiDialog()
            return
        }

        // Launch coroutine to handle network connection
        lifecycleScope.launch(Dispatchers.IO) {
            NetworkStreamService.streamTouchInputToWifi(host.trim(), port, mainView)
        }
    }

    // A suspend function that can be paused (non-blocking)
    private suspend fun acquireUsbAccessory(): UsbAccessory? {
        var isDisplayingUsbConnectionAlert = false

        while (true) {
            val accessories = usbManager.accessoryList

            if (accessories.isNullOrEmpty()) {
                if (!isDisplayingUsbConnectionAlert) {
                    // Switch to Main thread briefly to show UI
                    withContext(Dispatchers.Main) {
                        openAlertDialog("Usb link not established. Make sure your device is connected to the PC and launch the usb-host application.")
                    }
                    isDisplayingUsbConnectionAlert = true
                }
                Log.d(TAG, "Empty accessories list, you should initialize the connected PC as accessory")
            } else {
                val accessory = accessories[0]
                if (usbManager.hasPermission(accessory)) {
                    return accessory
                }
            }

            // Non-blocking delay instead of Thread.sleep
            delay(1000)
        }
    }

    private fun openAlertDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Warning")
            .setMessage(message)
            .show()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}