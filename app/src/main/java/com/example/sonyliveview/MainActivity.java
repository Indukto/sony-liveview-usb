package com.example.sonyliveview;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Map;

public final class MainActivity extends Activity implements PtpUsbCamera.Listener {
    private static final String TAG = "SonyLiveView";
    private static final int SONY_VENDOR_ID = 0x054c;
    private static final int A6300_PC_REMOTE_PRODUCT_ID = 0x079c;
    private static final String ACTION_USB_PERMISSION = "com.example.sonyliveview.USB_PERMISSION";

    private UsbManager usbManager;
    private TextView statusView;
    private ImageView previewView;
    private Button connectButton;
    private Button liveViewButton;
    private PtpUsbCamera camera;
    private BroadcastReceiver usbReceiver;
    private int frameCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        buildUi();
        registerUsbReceiver();
        appendStatus("Connect a Sony a6300 in PC Remote mode.");
        appendStatus("USB host ready: " + (usbManager != null));
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff101418);
        root.setPadding(20, 16, 20, 16);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);

        connectButton = new Button(this);
        connectButton.setText("Connect");
        connectButton.setOnClickListener(v -> requestCameraPermission());
        toolbar.addView(connectButton, new LinearLayout.LayoutParams(-2, -2));

        liveViewButton = new Button(this);
        liveViewButton.setText("Start LiveView");
        liveViewButton.setEnabled(false);
        liveViewButton.setOnClickListener(v -> {
            liveViewButton.setEnabled(false);
            if (camera != null) camera.requestLiveView();
        });
        toolbar.addView(liveViewButton, new LinearLayout.LayoutParams(-2, -2));

        statusView = new TextView(this);
        statusView.setTextColor(0xffb8c4cc);
        statusView.setTextSize(12);
        statusView.setPadding(16, 0, 0, 0);
        toolbar.addView(statusView, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));

        previewView = new ImageView(this);
        previewView.setBackgroundColor(0xff050607);
        previewView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(previewView, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView help = new TextView(this);
        help.setTextColor(0xff81909a);
        help.setTextSize(13);
        help.setPadding(4, 8, 4, 0);
        help.setText("The preview decodes JPEG frames from the Sony PTP USB bulk stream. " +
                "Enable PC Remote on the camera before connecting.");
        root.addView(help, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
    }

    private void registerUsbReceiver() {
        usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                Log.i(TAG, "USB permission result granted=" + granted +
                        " device=" + (device == null ? "none" : device.getDeviceName()));
                if (granted && device != null) {
                    connectTo(device);
                } else {
                    Log.w(TAG, "USB permission denied or device missing");
                    appendStatus("USB permission denied.");
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
    }

    private void requestCameraPermission() {
        if (usbManager == null) {
            appendStatus("USB host is unavailable on this phone.");
            return;
        }
        UsbDevice device = findPtpDevice();
        if (device == null) {
            appendStatus("No PTP camera found. Set USB Connection to PC Remote and reconnect.");
            return;
        }
        String deviceSummary = "Found USB device: " + device.getDeviceName() +
                " VID=" + Integer.toHexString(device.getVendorId()) +
                " PID=" + Integer.toHexString(device.getProductId());
        Log.i(TAG, deviceSummary + " interfaces=" + device.getInterfaceCount());
        appendStatus(deviceSummary);
        if (device.getVendorId() == SONY_VENDOR_ID &&
                device.getProductId() == A6300_PC_REMOTE_PRODUCT_ID) {
            Log.i(TAG, "Sony a6300 PC Remote USB identity recognized (054c:079c)");
            appendStatus("Sony a6300 PC Remote identity recognized.");
        }
        if (usbManager.hasPermission(device)) {
            Log.i(TAG, "USB permission already granted; opening camera");
            connectTo(device);
            return;
        }
        // Android 14+ rejects mutable PendingIntents that wrap implicit intents.
        // Restrict the broadcast to this app while keeping it mutable so UsbManager
        // can attach EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED.
        Intent permissionIntent = new Intent(ACTION_USB_PERMISSION)
                .setPackage(getPackageName());
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this, 0, permissionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        Log.i(TAG, "Requesting Android USB permission");
        usbManager.requestPermission(device, pendingIntent);
        appendStatus("Waiting for Android USB permission...");
    }

    private UsbDevice findPtpDevice() {
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                if (device.getInterface(i).getInterfaceClass() == 6) return device;
            }
        }
        return null;
    }

    private void connectTo(UsbDevice device) {
        Log.i(TAG, "Starting PTP camera connection for " + device.getDeviceName());
        if (camera != null) camera.close();
        camera = new PtpUsbCamera(usbManager, device, this);
        connectButton.setEnabled(false);
        camera.start();
    }

    @Override
    public void onState(String message) {
        runOnUiThread(() -> {
            appendStatus(message);
            if (message.startsWith("LiveView request failed") ||
                    message.startsWith("Sony LiveView request failed")) {
                liveViewButton.setEnabled(true);
            }
        });
    }

    @Override
    public void onReady() {
        runOnUiThread(() -> {
            liveViewButton.setEnabled(true);
            appendStatus("PTP ready. Press Start LiveView.");
        });
    }

    @Override
    public void onFrame(byte[] jpeg) {
        int currentFrame = ++frameCount;
        if (currentFrame == 1 || currentFrame % 30 == 0) {
            Log.d(TAG, "JPEG frames received=" + currentFrame + " latestBytes=" + jpeg.length);
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bitmap == null) {
            Log.w(TAG, "JPEG decode failed bytes=" + jpeg.length);
            return;
        }
        runOnUiThread(() -> previewView.setImageBitmap(bitmap));
    }

    @Override
    public void onClosed() {
        runOnUiThread(() -> {
            connectButton.setEnabled(true);
            liveViewButton.setEnabled(false);
        });
    }

    private void appendStatus(String message) {
        Log.d(TAG, "UI status: " + message);
        if (statusView == null) return;
        String old = statusView.getText().toString();
        String[] lines = (old + "\n" + message).split("\\n");
        int start = Math.max(0, lines.length - 4);
        StringBuilder visible = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            if (visible.length() > 0) visible.append('\n');
            visible.append(lines[i]);
        }
        statusView.setText(visible.toString());
    }

    @Override
    protected void onDestroy() {
        if (camera != null) camera.close();
        if (usbReceiver != null) unregisterReceiver(usbReceiver);
        super.onDestroy();
    }
}
