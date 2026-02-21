package com.inkbridge

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun DrawingPadScreen(onDisconnect: () -> Unit) {
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
                
                // Attach the active listeners from whichever service is currently connected
                UsbStreamService.updateView(this)
                WifiDirectService.updateView(this)
                BluetoothStreamService.updateView(this)
            }
        },
        update = { view: View ->
            view.requestFocus()
            
            // Re-apply the immersive mode flags just in case an update pass clears them
            @Suppress("DEPRECATION")
            view.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    )
    
    // Handle the physical back gesture to stop the connection
    BackHandler { onDisconnect() }
}