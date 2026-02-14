package com.inkbridge

import android.view.MotionEvent
import android.view.View
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TouchListener(private val outputStream: OutputStream) : View.OnTouchListener, View.OnGenericMotionListener {

    private val buffer: ByteBuffer = ByteBuffer.allocate(14).apply {
        order(ByteOrder.LITTLE_ENDIAN)
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        return processEvent(event)
    }

    override fun onGenericMotion(v: View, event: MotionEvent): Boolean {
        return processEvent(event)
    }

    private fun processEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val toolType = event.getToolType(0)

        // Filter: Only allow Stylus, Eraser, or Finger
        if (toolType != MotionEvent.TOOL_TYPE_STYLUS &&
            toolType != MotionEvent.TOOL_TYPE_ERASER &&
            toolType != MotionEvent.TOOL_TYPE_FINGER
        ) {
            return false
        }

        val rawX = event.x.toInt()
        val rawY = event.y.toInt()

        // Swap Logic
        val finalX = if (SWAP_XY_AXIS) rawY else rawX
        val finalY = if (SWAP_XY_AXIS) rawX else rawY

        val pressure = (event.pressure * 1000).toInt()

        synchronized(buffer) {
            buffer.clear()
            buffer.put(toolType.toByte())
            buffer.put(action.toByte())
            buffer.putInt(finalX)
            buffer.putInt(finalY)
            buffer.putInt(pressure)

            try {
                outputStream.write(buffer.array())
                outputStream.flush()
            } catch (e: IOException) {
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