package com.inkbridge

import android.view.MotionEvent
import android.view.View
import android.util.Log
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

class TouchListener(
    private val outputStream: OutputStream,
    private val stylusOnly: Boolean = false
) : View.OnTouchListener, View.OnGenericMotionListener {

    private val buffer = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
    private val TAG = "InkBridgeTouch"

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

    private fun processEvent(view: View, event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)

        if (stylusOnly && toolType == MotionEvent.TOOL_TYPE_FINGER) {
            return true 
        }

        val action = event.actionMasked
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        val isButtonPressed = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        val actionWithButton = if (isButtonPressed) action or 32 else action

        val historySize = event.historySize
        for (i in 0 until historySize) {
            sendSample(
                toolType = toolType,
                actionWithButton = actionWithButton,
                x = event.getHistoricalX(i),
                y = event.getHistoricalY(i),
                pressure = event.getHistoricalPressure(i),
                tiltRad = event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i),
                orientationRad = event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, i),
                w = w,
                h = h
            )
        }

        sendSample(
            toolType = toolType,
            actionWithButton = actionWithButton,
            x = event.x,
            y = event.y,
            pressure = event.pressure,
            tiltRad = event.getAxisValue(MotionEvent.AXIS_TILT),
            orientationRad = event.getAxisValue(MotionEvent.AXIS_ORIENTATION),
            w = w,
            h = h
        )

        return true
    }

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
        val finalX = ((x / w) * 32767).toInt().coerceIn(0, 32767)
        val finalY = ((y / h) * 32767).toInt().coerceIn(0, 32767)
        val pressureInt = (pressure * 4096).toInt().coerceIn(0, 4096)

        // Native Kotlin Float math (No Double casting overhead)
        val sinTilt = sin(tiltRad)
        val tiltXDeg = (sinTilt * sin(orientationRad) * 90f).toInt()
        val tiltYDeg = (sinTilt * cos(orientationRad) * 90f).toInt()

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
                // EXPLICITLY pass length to prevent the stream from consuming the raw array reference blindly
                outputStream.write(buffer.array(), 0, 22)
            } catch (e: Exception) {
                Log.e(TAG, "Write failed", e)
            }
        }
    }
}