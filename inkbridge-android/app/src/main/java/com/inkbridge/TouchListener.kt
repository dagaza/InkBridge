package com.inkbridge

import android.view.MotionEvent
import android.view.View
import android.util.Log
import java.io.OutputStream // Change import
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TouchListener(private val outputStream: OutputStream) : View.OnTouchListener, View.OnGenericMotionListener {

    private val buffer = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
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

        // --- BUTTON LOGIC ---
        val isButtonPressed = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        val actionWithButton = if (isButtonPressed) action or 32 else action

        // --- COORDINATES ---
        val x = event.x
        val y = event.y
        val w = view.width.toFloat()
        val h = view.height.toFloat()

        val finalX = ((x / w) * 32767).toInt().coerceIn(0, 32767)
        val finalY = ((y / h) * 32767).toInt().coerceIn(0, 32767)
        val pressure = (event.pressure * 4096).toInt()

        // --- FIX: TILT CALCULATION ---
        // 1. Get raw values (Radians)
        // AXIS_TILT: Angle between screen vertical and stylus (0 to 1.57)
        // AXIS_ORIENTATION: Direction stylus is pointing (-PI to PI)
        val tiltRad = event.getAxisValue(MotionEvent.AXIS_TILT)
        val orientationRad = event.getAxisValue(MotionEvent.AXIS_ORIENTATION)

        // 2. Convert Spherical (Tilt/Orient) to Cartesian (TiltX/TiltY)
        // Math: We project the tilt magnitude onto the X and Y axes.
        // We multiply by 57.295 (180/PI) to convert Radians to Degrees.
        // Range: -90 to +90 degrees (Standard for Linux Stylus)
        val sinTilt = Math.sin(tiltRad.toDouble())
        
        // Note: Android Orientation is usually: 0=Up, -PI/2=Left. 
        // We might need to adjust signs depending on your specific Linux driver expectation,
        // but this is the standard geometric projection.
        val tiltXDeg = (sinTilt * Math.sin(orientationRad.toDouble()) * 90).toInt()
        val tiltYDeg = (sinTilt * Math.cos(orientationRad.toDouble()) * 90).toInt()
        
        synchronized(buffer) {
            buffer.clear()
            buffer.put(toolType.toByte())
            buffer.put(actionWithButton.toByte())
            buffer.putInt(finalX)
            buffer.putInt(finalY)
            buffer.putInt(pressure)
            
            // Send calculated DEGREES, not truncated radians
            buffer.putInt(tiltXDeg) 
            buffer.putInt(tiltYDeg) 

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