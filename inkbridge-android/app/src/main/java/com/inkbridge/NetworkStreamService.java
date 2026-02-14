package com.inkbridge;

import android.util.Log;
import android.view.View;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

public class NetworkStreamService {
    private static Socket socket;
    private static OutputStream outputStream;

    public static void streamTouchInputToWifi(String host, int port, View view) {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 5000);
            outputStream = socket.getOutputStream();
            TouchListener touchListener = new TouchListener(outputStream);

            view.post(() -> {
                view.setOnTouchListener(touchListener);
                view.setOnGenericMotionListener(touchListener);
            });
        } catch (IOException e) {
            Log.e("InkBridge", "WiFi Stream Error", e);
        }
    }

    public static void closeStream() {
        try {
            if (outputStream != null) outputStream.close();
            if (socket != null) socket.close();
        } catch (IOException e) {
            Log.e("InkBridge", "Error closing WiFi stream", e);
        }
    }
}