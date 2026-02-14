package com.virtual_pen;

import static android.view.MotionEvent.TOOL_TYPE_FINGER;
import static android.view.MotionEvent.TOOL_TYPE_STYLUS;

import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class TouchListener {
    private final OutputStream outputStream;
    private final ByteBuffer buffer = ByteBuffer.allocate(14);

    public TouchListener(OutputStream outputStream) {
        this.outputStream = outputStream;
        this.buffer.order(ByteOrder.LITTLE_ENDIAN);
    }

    public View.OnTouchListener handleTouch = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if(TOOL_TYPE_STYLUS != event.getToolType(0) &&
               MotionEvent.TOOL_TYPE_ERASER != event.getToolType(0) &&
               TOOL_TYPE_FINGER != event.getToolType(0)){
               return false;
            }
            int x = (int) event.getX();
            int y = (int) event.getY();
            int pressure = (int) (event.getPressure() * 1000); // Scale pressure to integer

            buffer.clear();
            buffer.put((byte) event.getToolType(0));
            buffer.put((byte) event.getAction());
            buffer.putInt(x);
            buffer.putInt(y);
            buffer.putInt(pressure);

            try {
                outputStream.write(buffer.array());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    Log.i("TAG", "touched down");
                    break;
                case MotionEvent.ACTION_MOVE:
                    Log.i("TAG", "moving: (" + x + ", " + y + ")");
                    break;
                case MotionEvent.ACTION_UP:
                    Log.i("TAG", "touched up");
                    break;
            }

            return true;
        }
    };
}
