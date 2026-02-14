package com.inkbridge;

import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class TouchListener implements View.OnTouchListener, View.OnGenericMotionListener {

    // --- NEW SETTING ---
    // A static flag that MainActivity can toggle directly.
    public static volatile boolean SWAP_XY_AXIS = false;
    // -------------------

    private final OutputStream outputStream;
    private final ByteBuffer buffer = ByteBuffer.allocate(14);

    public TouchListener(OutputStream outputStream) {
        this.outputStream = outputStream;
        this.buffer.order(ByteOrder.LITTLE_ENDIAN);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        return processEvent(event);
    }

    @Override
    public boolean onGenericMotion(View v, MotionEvent event) {
        return processEvent(event);
    }

    private boolean processEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int toolType = event.getToolType(0);

        if (toolType != MotionEvent.TOOL_TYPE_STYLUS &&
                toolType != MotionEvent.TOOL_TYPE_ERASER &&
                toolType != MotionEvent.TOOL_TYPE_FINGER) {
            return false;
        }

        int rawX = (int) event.getX();
        int rawY = (int) event.getY();

        // --- THE FIX ---
        // If the toggle is ON, we swap X and Y before sending.
        int finalX = SWAP_XY_AXIS ? rawY : rawX;
        int finalY = SWAP_XY_AXIS ? rawX : rawY;
        // ---------------

        int pressure = (int) (event.getPressure() * 1000);

        synchronized (buffer) {
            buffer.clear();
            buffer.put((byte) toolType);
            buffer.put((byte) action);
            buffer.putInt(finalX);
            buffer.putInt(finalY);
            buffer.putInt(pressure);

            try {
                if (outputStream != null) {
                    outputStream.write(buffer.array());
                    outputStream.flush();
                }
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }
}