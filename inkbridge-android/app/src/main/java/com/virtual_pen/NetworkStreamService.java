package com.virtual_pen;

import android.util.Log;
import android.view.View;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

public class NetworkStreamService {
    private static final String TAG = "NetworkStreamService";
    private static Socket socket;
    private static OutputStream outputStream;

    public static void streamTouchInputToWifi(String host, int port, View view) {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 5000);
            outputStream = socket.getOutputStream();
            TouchListener touchListener = new TouchListener(outputStream);
            view.post(() -> view.setOnTouchListener(touchListener.handleTouch));
        } catch (IOException e) {
            String message = "Unexpected IO exception while attempting to open socket stream.";
            Log.e(TAG, message, e);
            throw new RuntimeException(message, e);
        }
    }

    public static void closeStream() {
        if (outputStream != null) {
            try {
                outputStream.close();
            } catch (IOException e) {
                Log.e(TAG, "Failed to close output stream.", e);
            } finally {
                outputStream = null;
            }
        }
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                Log.e(TAG, "Failed to close socket.", e);
            } finally {
                socket = null;
            }
        }
    }
}
