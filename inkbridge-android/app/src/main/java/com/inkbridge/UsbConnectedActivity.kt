package com.inkbridge

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class UsbConnectedActivity : AppCompatActivity() {

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

        val accessory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
        }

        if (accessory != null) {
            UsbStreamService.streamTouchInputToUsb(usbManager, accessory, mainView)
        } else {
            Log.e(TAG, "No USB accessory found in intent")
            finish()
        }
    }

    companion object {
        private const val TAG = "UsbConnectedActivity"
    }
}