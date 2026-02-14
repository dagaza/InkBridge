package com.virtual_pen;

import static android.content.ContentValues.TAG;

import android.app.AlertDialog;
import android.content.Context;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class MainActivity extends AppCompatActivity {

    private UsbManager usbManager;
    private View mainView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        mainView = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(mainView, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        showConnectionChoiceDialog();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        UsbStreamService.closeStream();
        NetworkStreamService.closeStream();
    }

    private void showConnectionChoiceDialog() {
        runOnUiThread(() -> {
            new AlertDialog.Builder(mainView.getContext())
                    .setTitle("Select Connection")
                    .setMessage("Choose how to connect to your PC host.")
                    .setPositiveButton("USB", (dialog, which) -> connectUsb())
                    .setNegativeButton("WiFi", (dialog, which) -> openWifiDialog())
                    .setCancelable(false)
                    .show();
        });
    }

    private void connectUsb() {
        CompletableFuture<Optional<UsbAccessory>> usbAccessoryCompletableFuture = CompletableFuture.supplyAsync(this::acquireUsbAccessory);
        usbAccessoryCompletableFuture.thenAcceptAsync(maybeUsbAccessory -> maybeUsbAccessory.ifPresent(usbAccessory -> UsbStreamService.streamTouchInputToUsb(usbManager, usbAccessory, mainView)));
    }

    private void openWifiDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        EditText hostInput = new EditText(this);
        hostInput.setHint("Host IP (e.g. 192.168.1.10)");
        layout.addView(hostInput);

        EditText portInput = new EditText(this);
        portInput.setHint("Port (e.g. 4545)");
        portInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        layout.addView(portInput);

        new AlertDialog.Builder(mainView.getContext())
                .setTitle("WiFi Connection")
                .setView(layout)
                .setPositiveButton("Connect", (dialog, which) -> connectWifi(hostInput.getText().toString(), portInput.getText().toString()))
                .setNegativeButton("Cancel", (dialog, which) -> showConnectionChoiceDialog())
                .setCancelable(false)
                .show();
    }

    private void connectWifi(String host, String portValue) {
        if (host == null || host.trim().isEmpty()) {
            openAlertDialog("Please enter a valid host IP.");
            openWifiDialog();
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portValue);
        } catch (NumberFormatException e) {
            openAlertDialog("Please enter a valid port.");
            openWifiDialog();
            return;
        }
        int finalPort = port;
        CompletableFuture.runAsync(() -> NetworkStreamService.streamTouchInputToWifi(host.trim(), finalPort, mainView));
    }

    private Optional<UsbAccessory> acquireUsbAccessory() {
        boolean isDisplayingUsbConnectionAlert = false;
        while (true) {
            UsbAccessory[] accessories = usbManager.getAccessoryList();
            if (accessories == null || accessories.length == 0) {
                if(!isDisplayingUsbConnectionAlert){
                    openAlertDialog("Usb link not established. Make sure your device is connected to the PC and launch the usb-host application.");
                    isDisplayingUsbConnectionAlert = true;
                }
                Log.d(TAG, "Empty accessories list, you should initialize the connected PC as accessory");
            } else {
                UsbAccessory accessory = accessories[0];
                if(usbManager.hasPermission(accessory)){
                    return Optional.of(accessory);
                }
            }
            try {
                //noinspection BusyWait
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return Optional.empty();
            }
        }
    }

    private void openAlertDialog(String message){
        runOnUiThread(() -> {
            new AlertDialog.Builder(mainView.getContext()).setTitle("Warning")
                    .setMessage(message)
                    .show();
        });
    }
}
