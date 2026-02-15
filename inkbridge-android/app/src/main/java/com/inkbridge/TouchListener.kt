package com.inkbridge

import android.view.MotionEvent
import android.view.View
import android.util.Log
import java.io.OutputStream // Change import
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TouchListener(private val outputStream: OutputStream) : View.OnTouchListener, View.OnGenericMotionListener {

    private val buffer = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN)
    private val TAG = "InkBridgeTouch"

    // --- 1. BLOCK INTERCEPTS (Fixes Samsung Gestures) ---
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        // Tell the parent (Samsung System UI) to NOT intercept our touches
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            v.parent?.requestDisallowInterceptTouchEvent(true)
        }
        return processEvent(v, event)
    }

    override fun onGenericMotion(v: View, event: MotionEvent): Boolean {
        // Also block during hover to prevent "Air Actions"
        v.parent?.requestDisallowInterceptTouchEvent(true)
        return processEvent(v, event)
    }

    // --- 2. PROCESS DATA (Sends to Desktop) ---
    private fun processEvent(view: View, event: MotionEvent): Boolean {
        val action = event.actionMasked
        val toolType = event.getToolType(0)

        // --- DETECT BUTTON FOR ERASER ---
        // Check if the primary stylus button is held down
        val isButtonPressed = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0

        // Pack the button state into Bit 5 (add 32 to the action code)
        // This tells the desktop: "The button is pressed!"
        val actionWithButton = if (isButtonPressed) action or 32 else action

        // --- COORDINATES ---
        val x = event.x
        val y = event.y
        val w = view.width.toFloat()
        val h = view.height.toFloat()

        // Normalize coordinates to 0..32767 range (Standard for USB HID)
        val finalX = ((x / w) * 32767).toInt().coerceIn(0, 32767)
        val finalY = ((y / h) * 32767).toInt().coerceIn(0, 32767)

        // Pressure (0..4096 is standard, Android gives 0.0..1.0)
        val pressure = (event.pressure * 4096).toInt()

        // Tilt (Standard Android Tilt is in degrees, we send as-is for now)
        // Note: Some styluses might need scaling here depending on your C++ backend
        val tiltX = event.getAxisValue(MotionEvent.AXIS_TILT).toInt()
        val tiltY = 0 // Android usually provides a combined tilt or requires complex calculation for Y

        synchronized(buffer) {
            buffer.clear()
            // Protocol:
            // [ToolType: 1] [Action: 1] [X: 4] [Y: 4] [Pressure: 4] [TiltX: 4] [TiltY: 4] ...
            buffer.put(toolType.toByte())
            buffer.put(actionWithButton.toByte()) // Send the modified action
            buffer.putInt(finalX)
            buffer.putInt(finalY)
            buffer.putInt(pressure)
            buffer.putInt(tiltX)
            buffer.putInt(tiltY)

            try {
                outputStream.write(buffer.array())
                outputStream.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Write Failed", e)
                return false
            }
        }
        return true
    }
}