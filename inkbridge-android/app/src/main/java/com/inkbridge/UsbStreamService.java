package com.inkbridge;

import static android.content.ContentValues.TAG;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.View;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

public class UsbStreamService {
    private static ParcelFileDescriptor fileDescriptor;
    private static FileOutputStream fileOutputStream;
    private static boolean isStreamOpen = false;

    public static void streamTouchInputToUsb(UsbManager usbManager, UsbAccessory usbAccessory, View view) {
        try {
            if(!isStreamOpen){
                fileOutputStream = getUsbFileOutputStream(usbManager, usbAccessory);
                fileOutputStream.flush();

                TouchListener touchListener = new TouchListener(fileOutputStream);

                // IMPORTANT: We must register BOTH listeners on the view
                view.setOnTouchListener(touchListener);
                view.setOnGenericMotionListener(touchListener);

                isStreamOpen = true;
            }
        } catch (IOException e) {
            Log.d(TAG, "USB Stream Error", e);
        }
    }

    public static void closeStream(){
        try {
            if (fileDescriptor != null) fileDescriptor.close();
            if (fileOutputStream != null) fileOutputStream.close();
        } catch (IOException e) {
            Log.e(TAG, "Error closing stream", e);
        } finally {
            isStreamOpen = false;
        }
    }

    private static FileOutputStream getUsbFileOutputStream(UsbManager usbManager, UsbAccessory usbAccessory) throws IOException {
        fileDescriptor = usbManager.openAccessory(usbAccessory);
        if (fileDescriptor != null) {
            return new FileOutputStream(fileDescriptor.getFileDescriptor());
        }
        throw new FileNotFoundException("File descriptor not found");
    }
}