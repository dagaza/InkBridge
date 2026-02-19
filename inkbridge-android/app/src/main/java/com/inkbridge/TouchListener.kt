package com.inkbridge

import android.view.MotionEvent
import android.view.View
import android.util.Log
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TouchListener(
    private val outputStream: OutputStream,
    private val stylusOnly: Boolean = false  // read once at construction, not per-event
) : View.OnTouchListener, View.OnGenericMotionListener {

    // Pre-allocated buffer for a single packet — reused every event to
    // avoid per-frame heap allocation during fast stylus movement.
    private val buffer = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)

    private val TAG = "InkBridgeTouch"

    // --- 1. BLOCK INTERCEPTS (Fixes Samsung Gestures) ---
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            v.parent?.requestDisallowInterceptTouchEvent(true)
        }
        return processEvent(v, event)
    }

    override fun onGenericMotion(v: View, event: MotionEvent): Boolean {
        v.parent?.requestDisallowInterceptTouchEvent(true)
        return processEvent(v, event)
    }

    // --- 2. PROCESS EVENT ---
    private fun processEvent(view: View, event: MotionEvent): Boolean {
        val toolType   = event.getToolType(0)

        // Stylus-only gatekeeper — checked against a cached boolean, not
        // a live SharedPreferences read, so cost is a single branch per event.
        if (stylusOnly && toolType == MotionEvent.TOOL_TYPE_FINGER) {
            return true // Consume the event to block OS gestures, but send nothing
        }

        val action     = event.actionMasked
        val w          = view.width.toFloat()
        val h          = view.height.toFloat()
        val isButtonPressed = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        val actionWithButton = if (isButtonPressed) action or 32 else action

        // --- HISTORICAL EVENTS ---
        // Android batches intermediate positions between frame callbacks into
        // each MotionEvent as "historical" samples. Ignoring them drops data
        // during fast strokes, causing jagged lines. We drain them first,
        // then send the current sample last so ordering is preserved.
        //
        // Historical events only exist for MOVE/HOVER_MOVE — for all other
        // actions (DOWN, UP, etc.) historySize is 0 so the loop is a no-op.
        val historySize = event.historySize
        for (i in 0 until historySize) {
            sendSample(
                toolType         = toolType,
                actionWithButton = actionWithButton,
                x                = event.getHistoricalX(i),
                y                = event.getHistoricalY(i),
                pressure         = event.getHistoricalPressure(i),
                tiltRad          = event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i),
                orientationRad   = event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, i),
                w                = w,
                h                = h
            )
        }

        // Current (most recent) sample
        sendSample(
            toolType         = toolType,
            actionWithButton = actionWithButton,
            x                = event.x,
            y                = event.y,
            pressure         = event.pressure,
            tiltRad          = event.getAxisValue(MotionEvent.AXIS_TILT),
            orientationRad   = event.getAxisValue(MotionEvent.AXIS_ORIENTATION),
            w                = w,
            h                = h
        )

        return true
    }

    // --- 3. ENCODE AND SEND A SINGLE SAMPLE ---
    private fun sendSample(
        toolType: Int,
        actionWithButton: Int,
        x: Float,
        y: Float,
        pressure: Float,
        tiltRad: Float,
        orientationRad: Float,
        w: Float,
        h: Float
    ) {
        val finalX    = ((x / w) * 32767).toInt().coerceIn(0, 32767)
        val finalY    = ((y / h) * 32767).toInt().coerceIn(0, 32767)

        // Encode pressure as 0–4096 integer.
        // accessory.cpp must divide by 4096.0f (not 1000.0f) to decode correctly.
        val pressureInt = (pressure * 4096).toInt().coerceIn(0, 4096)

        // Convert spherical tilt (Android) to Cartesian degrees (Linux stylus)
        val sinTilt    = Math.sin(tiltRad.toDouble())
        val tiltXDeg   = (sinTilt * Math.sin(orientationRad.toDouble()) * 90).toInt()
        val tiltYDeg   = (sinTilt * Math.cos(orientationRad.toDouble()) * 90).toInt()

        synchronized(buffer) {
            buffer.clear()
            buffer.put(toolType.toByte())
            buffer.put(actionWithButton.toByte())
            buffer.putInt(finalX)
            buffer.putInt(finalY)
            buffer.putInt(pressureInt)
            buffer.putInt(tiltXDeg)
            buffer.putInt(tiltYDeg)

            try {
                // Write only — NO flush() here.
                // For Bluetooth, flushing is done by the write loop in
                // BluetoothStreamService after batching multiple packets,
                // so calling flush() here would bypass the batching entirely.
                // For USB, the underlying stream handles buffering correctly
                // without an explicit flush per packet.
                outputStream.write(buffer.array())
            } catch (e: Exception) {
                Log.e(TAG, "Write failed", e)
            }
        }
    }
}