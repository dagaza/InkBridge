package com.inkbridge

import android.util.Log // Import Logging
import android.view.MotionEvent
import android.view.View
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

class TouchListener(private val outputStream: OutputStream) : View.OnTouchListener, View.OnGenericMotionListener {

    private val TAG = "InkBridgeTouch" // Log Tag

    private val buffer: ByteBuffer = ByteBuffer.allocate(22).apply {
        order(ByteOrder.LITTLE_ENDIAN)
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        return processEvent(v, event)
    }

    override fun onGenericMotion(v: View, event: MotionEvent): Boolean {
        return processEvent(v, event)
    }

    private fun processEvent(view: View, event: MotionEvent): Boolean {
        // 1. Log that we received an event
        // Log.d(TAG, "Event received: Action=${event.actionMasked}")

        val action = event.actionMasked
        val toolType = event.getToolType(0)

        // Filter: Only allow Stylus, Eraser, or Finger
        if (toolType != MotionEvent.TOOL_TYPE_STYLUS &&
            toolType != MotionEvent.TOOL_TYPE_ERASER &&
            toolType != MotionEvent.TOOL_TYPE_FINGER
        ) {
            return false
        }

        val width = view.width.toFloat()
        val height = view.height.toFloat()

        // 2. Safety Check Log
        if (width <= 0 || height <= 0) {
            Log.e(TAG, "ERROR: Invalid View Dimensions: ${width}x${height}")
            return false
        }

        // Clamp and Normalize
        val normX = ((event.x / width) * 32767).toInt().coerceIn(0, 32767)
        val normY = ((event.y / height) * 32767).toInt().coerceIn(0, 32767)

        // --- TILT LOGIC (Condensed for brevity, kept same as before) ---
        var tiltX = 0
        var tiltY = 0
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
            val tiltRad = event.getAxisValue(MotionEvent.AXIS_TILT)
            val orientationRad = event.getAxisValue(MotionEvent.AXIS_ORIENTATION)
            val tiltDeg = Math.toDegrees(tiltRad.toDouble())
            tiltX = (tiltDeg * sin(orientationRad.toDouble())).toInt()
            tiltY = (-tiltDeg * cos(orientationRad.toDouble())).toInt()
        }

        val finalX = if (SWAP_XY_AXIS) normY else normX
        val finalY = if (SWAP_XY_AXIS) normX else normY
        val finalTiltX = if (SWAP_XY_AXIS) tiltY else tiltX
        val finalTiltY = if (SWAP_XY_AXIS) tiltX else tiltY
        val pressure = (event.pressure * 1000).toInt()

        synchronized(buffer) {
            buffer.clear()
            buffer.put(toolType.toByte())
            buffer.put(action.toByte())
            buffer.putInt(finalX)
            buffer.putInt(finalY)
            buffer.putInt(pressure)
            buffer.putInt(finalTiltX)
            buffer.putInt(finalTiltY)

            try {
                // 3. Write Log
                outputStream.write(buffer.array())
                outputStream.flush()

                // Debug Log (Only print occasionally to avoid spamming)
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) {
                    Log.d(TAG, "Sent 22 bytes. X:$finalX Y:$finalY Tilt:$finalTiltX/$finalTiltY")
                }

            } catch (e: IOException) {
                Log.e(TAG, "Write Failed", e)
                return false
            }
        }
        return true
    }

    companion object {
        @Volatile
        var SWAP_XY_AXIS: Boolean = false
    }
}