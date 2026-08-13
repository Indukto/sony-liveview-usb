package com.example.sonyliveview;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.ViewFlipper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Two-page UI: a connection page with status, controls and a live log, and a
 * full-screen video page that takes over as soon as the PTP session is ready
 * and LiveView is streaming. Stopping LiveView returns to the connection page.
 */
public final class MainActivity extends Activity implements PtpUsbCamera.Listener {
    private static final String TAG = "SonyLiveView";
    private static final int SONY_VENDOR_ID = 0x054c;
    private static final int A6300_PC_REMOTE_PRODUCT_ID = 0x079c;
    private static final String ACTION_USB_PERMISSION = "com.example.sonyliveview.USB_PERMISSION";
    private static final int MAX_LOG_LINES = 200;

    private static final int COLOR_BG = 0xFF0C0F12;
    private static final int COLOR_PANEL = 0xFF141A20;
    private static final int COLOR_PREVIEW = 0xFF050607;
    private static final int COLOR_TEXT = 0xFFB8C4CC;
    private static final int COLOR_DIM = 0xFF6B7A85;
    private static final int COLOR_ACCENT = 0xFF80CBC4;
    private static final int COLOR_OK = 0xFF69DB7C;
    private static final int COLOR_BUSY = 0xFFFFD43B;
    private static final int COLOR_ERR = 0xFFFF6B6B;

    private static final int PAGE_CONNECTION = 0;
    private static final int PAGE_VIDEO = 1;

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private UsbManager usbManager;
    private PtpUsbCamera camera;
    private BroadcastReceiver usbReceiver;

    private ViewFlipper flipper;
    private boolean connected;

    // Connection page
    private TextView statusLabel;
    private View statusDot;
    private Button connectButton;
    private Button liveViewButton;
    private Button disconnectButton;
    private TextView logView;
    private ScrollView logScroll;

    // Video page
    private ImageView previewView;
    private TextView startingView;
    private TextView videoFpsLabel;
    private TextView videoInfoLabel;

    private int frameCount;
    private int fpsCount;
    private long fpsWindowStart;
    private String lastFrameInfo = "No frames yet";

    // JPEG decode runs off the camera's read thread so decoding (10-30 ms per
    // frame) never stalls the PTP transaction loop. Only the newest frame is
    // decoded; stale frames are dropped instead of queued up.
    private final ExecutorService decoderExecutor = Executors.newSingleThreadExecutor();
    private final AtomicReference<byte[]> latestJpeg = new AtomicReference<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        buildUi();
        registerUsbReceiver();
        showConnectionPage(COLOR_ERR, "Disconnected");
        appendStatus("Connect a Sony a6300 in PC Remote mode.");
        appendStatus("USB host ready: " + (usbManager != null));
    }

    // ------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------

    private void buildUi() {
        flipper = new ViewFlipper(this);
        flipper.addView(buildConnectionPage(), lp(-1, -1, 0, 0, 0, 0, 0));
        flipper.addView(buildVideoPage(), lp(-1, -1, 0, 0, 0, 0, 0));
        flipper.setDisplayedChild(PAGE_CONNECTION);
        setContentView(flipper);
    }

    private LinearLayout buildConnectionPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(COLOR_BG);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(16), dp(12), dp(16), dp(8));

        TextView title = new TextView(this);
        title.setText("SONY LIVEVIEW");
        title.setTextColor(COLOR_ACCENT);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.15f);
        toolbar.addView(title, lp(-2, -2, 0, 0, 0, dp(20), 0));

        statusDot = new View(this);
        toolbar.addView(statusDot, new LinearLayout.LayoutParams(dp(12), dp(12)));

        statusLabel = new TextView(this);
        statusLabel.setTextSize(13);
        statusLabel.setPadding(dp(8), 0, 0, 0);
        toolbar.addView(statusLabel, lp(-2, -2, 0, 0, 0, dp(16), 0));

        page.addView(toolbar, lp(-1, -2, 0, 0, 0, 0, 0));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(16), 0, dp(16), dp(12));

        connectButton = makeButton("Connect", COLOR_ACCENT);
        connectButton.setOnClickListener(v -> requestCameraPermission());
        controls.addView(connectButton, lp(-2, -2, 0, 0, 0, dp(8), 0));

        liveViewButton = makeButton("Start LiveView", COLOR_OK);
        setButtonEnabled(liveViewButton, false);
        liveViewButton.setOnClickListener(v -> startLiveViewFromConnectionPage());
        controls.addView(liveViewButton, lp(-2, -2, 0, dp(10), 0, 0, 0));

        controls.addView(spacer(), lp(0, 0, 1, 0, 0, 0, 0));

        disconnectButton = makeButton("Disconnect", COLOR_DIM);
        setButtonEnabled(disconnectButton, false);
        disconnectButton.setOnClickListener(v -> {
            if (camera != null) camera.close();
            appendStatus("Disconnecting…");
        });
        controls.addView(disconnectButton, lp(-2, -2, 0, 0, 0, 0, 0));

        page.addView(controls, lp(-1, -2, 0, 0, 0, 0, 0));

        TextView hint = new TextView(this);
        hint.setText("Connect the a6300 with USB Connection set to PC Remote.\n" +
                "Once the PTP session is ready, LiveView starts automatically and the\n" +
                "video takes over the screen. Stop returns here.");
        hint.setTextColor(COLOR_DIM);
        hint.setTextSize(13);
        hint.setLineSpacing(0, 1.3f);
        hint.setPadding(dp(16), 0, dp(16), dp(12));
        page.addView(hint, lp(-1, -2, 0, 0, 0, 0, 0));

        logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(COLOR_PANEL);

        logView = new TextView(this);
        logView.setTextColor(COLOR_TEXT);
        logView.setTextSize(11);
        logView.setPadding(dp(16), dp(8), dp(16), dp(8));
        logView.setTypeface(Typeface.MONOSPACE);
        logScroll.addView(logView, new ScrollView.LayoutParams(-1, -2));
        logScroll.setVerticalScrollBarEnabled(true);

        page.addView(logScroll, lp(-1, 0, 1, 0, 0, 0, 0));
        return page;
    }

    private FrameLayout buildVideoPage() {
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(COLOR_PREVIEW);

        previewView = new ImageView(this);
        previewView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        page.addView(previewView, new FrameLayout.LayoutParams(-1, -1));

        startingView = new TextView(this);
        startingView.setText("Starting LiveView…");
        startingView.setTextColor(COLOR_DIM);
        startingView.setTextSize(14);
        startingView.setGravity(Gravity.CENTER);
        page.addView(startingView, new FrameLayout.LayoutParams(-1, -1));

        Button backButton = makeOverlayButton("◀ Stop");
        backButton.setOnClickListener(v -> {
            if (camera != null) camera.stopLiveView();
            showConnectionPage(COLOR_BUSY, "Stopping…");
        });
        FrameLayout.LayoutParams backParams = new FrameLayout.LayoutParams(-2, -2);
        backParams.gravity = Gravity.TOP | Gravity.START;
        backParams.setMargins(dp(12), dp(12), 0, 0);
        page.addView(backButton, backParams);

        videoFpsLabel = new TextView(this);
        videoFpsLabel.setText("FPS 0");
        videoFpsLabel.setTextColor(COLOR_TEXT);
        videoFpsLabel.setTextSize(13);
        FrameLayout.LayoutParams fpsParams = new FrameLayout.LayoutParams(-2, -2);
        fpsParams.gravity = Gravity.TOP | Gravity.END;
        fpsParams.setMargins(0, dp(14), dp(14), 0);
        page.addView(videoFpsLabel, fpsParams);

        videoInfoLabel = new TextView(this);
        videoInfoLabel.setText(lastFrameInfo);
        videoInfoLabel.setTextColor(COLOR_DIM);
        videoInfoLabel.setTextSize(12);
        FrameLayout.LayoutParams infoParams = new FrameLayout.LayoutParams(-2, -2);
        infoParams.gravity = Gravity.TOP | Gravity.END;
        infoParams.setMargins(0, dp(40), dp(14), 0);
        page.addView(videoInfoLabel, infoParams);

        return page;
    }

    private Button makeOverlayButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(COLOR_TEXT);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setMinHeight(dp(40));
        button.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xB3000000);
        background.setStroke(dp(1), COLOR_TEXT);
        background.setCornerRadius(dp(10));
        button.setBackground(background);
        return button;
    }

    private LinearLayout spacer() {
        LinearLayout spacer = new LinearLayout(this);
        spacer.setOrientation(LinearLayout.HORIZONTAL);
        return spacer;
    }

    private Button makeButton(String text, int accent) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(accent);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setMinHeight(dp(42));
        button.setPadding(dp(18), 0, dp(18), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(COLOR_PANEL);
        background.setStroke(dp(1), accent);
        background.setCornerRadius(dp(10));
        button.setBackground(background);
        return button;
    }

    private void setButtonEnabled(Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.35f);
    }

    private void showConnectionPage(int color, String label) {
        flipper.setDisplayedChild(PAGE_CONNECTION);
        setStatus(color, label);
    }

    private void showVideoPage() {
        startingView.setVisibility(View.VISIBLE);
        flipper.setDisplayedChild(PAGE_VIDEO);
    }

    private void setStatus(int color, String label) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        statusDot.setBackground(dot);
        statusLabel.setText(label);
        statusLabel.setTextColor(color);
    }

    private LinearLayout.LayoutParams lp(int width, int height, int weight,
            int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        if (weight > 0) params.weight = weight;
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------
    // USB permission / connection
    // ------------------------------------------------------------------

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
                    setButtonEnabled(connectButton, true);
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
            setButtonEnabled(connectButton, true);
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
        setButtonEnabled(connectButton, false);
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
        connected = false;
        setButtonEnabled(connectButton, false);
        setButtonEnabled(liveViewButton, false);
        setButtonEnabled(disconnectButton, false);
        setStatus(COLOR_BUSY, "Connecting…");
        camera.start();
    }

    private void startLiveViewFromConnectionPage() {
        if (camera == null || !connected) return;
        setButtonEnabled(liveViewButton, false);
        showVideoPage();
        camera.requestLiveView();
    }

    // ------------------------------------------------------------------
    // PtpUsbCamera.Listener callbacks
    // ------------------------------------------------------------------

    @Override
    public void onState(String message) {
        runOnUiThread(() -> {
            appendStatus(message);
            if (message.startsWith("Claimed PTP USB interface") ||
                    message.startsWith("Opening PTP session")) {
                setStatus(COLOR_BUSY, "Connecting…");
            } else if (message.startsWith("Sony LiveView stopped")) {
                connected = true;
                showConnectionPage(COLOR_BUSY, "Ready");
                setButtonEnabled(connectButton, false);
                setButtonEnabled(disconnectButton, true);
                setButtonEnabled(liveViewButton, true);
            } else if (message.startsWith("LiveView request failed")) {
                connected = true;
                showConnectionPage(COLOR_ERR, "Error");
                setButtonEnabled(connectButton, false);
                setButtonEnabled(disconnectButton, true);
                setButtonEnabled(liveViewButton, true);
            } else if (message.startsWith("USB/PTP error")) {
                connected = false;
                showConnectionPage(COLOR_ERR, "Error");
            }
        });
    }

    @Override
    public void onReady() {
        runOnUiThread(() -> {
            connected = true;
            appendStatus("PTP ready. Starting LiveView…");
            setStatus(COLOR_BUSY, "Ready");
            setButtonEnabled(connectButton, false);
            setButtonEnabled(disconnectButton, true);
            setButtonEnabled(liveViewButton, true);
            // Take over the screen with the video stream.
            showVideoPage();
            camera.requestLiveView();
        });
    }

    @Override
    public void onFrame(byte[] jpeg) {
        // Received-frame accounting runs here on the camera thread; it only
        // does cheap counters plus a one-line log, never a JPEG decode.
        int currentFrame = ++frameCount;
        fpsCount++;
        long now = SystemClock.elapsedRealtime();
        if (fpsWindowStart == 0) fpsWindowStart = now;
        long elapsed = now - fpsWindowStart;
        if (elapsed >= 1000) {
            final int fps = (int) Math.round(fpsCount * 1000.0 / elapsed);
            fpsCount = 0;
            fpsWindowStart = now;
            runOnUiThread(() -> videoFpsLabel.setText("FPS " + fps));
        }
        if (currentFrame == 1 || currentFrame % 30 == 0) {
            Log.d(TAG, "JPEG frames received=" + currentFrame + " latestBytes=" + jpeg.length);
        }
        latestJpeg.set(jpeg);
        decoderExecutor.execute(this::decodeAndShow);
    }

    /** Runs on the decoder thread; decodes only the newest pending frame. */
    private void decodeAndShow() {
        byte[] jpeg = latestJpeg.getAndSet(null);
        if (jpeg == null) return; // superseded by a newer decode task
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bitmap == null) {
            Log.w(TAG, "JPEG decode failed bytes=" + jpeg.length);
            return;
        }
        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();
        lastFrameInfo = "JPEG " + width + "×" + height + " · frame #" + frameCount;
        runOnUiThread(() -> {
            previewView.setImageBitmap(bitmap);
            startingView.setVisibility(View.GONE);
            videoInfoLabel.setText(lastFrameInfo);
        });
    }

    @Override
    public void onClosed() {
        runOnUiThread(() -> {
            connected = false;
            showConnectionPage(COLOR_ERR, "Disconnected");
            appendStatus("Camera connection closed.");
            setButtonEnabled(connectButton, true);
            setButtonEnabled(disconnectButton, false);
            setButtonEnabled(liveViewButton, false);
            videoFpsLabel.setText("FPS 0");
            videoInfoLabel.setText("No frames yet");
        });
    }

    // ------------------------------------------------------------------
    // Log panel
    // ------------------------------------------------------------------

    private void appendStatus(String message) {
        Log.d(TAG, "UI status: " + message);
        if (logView == null) return;
        String line = timeFormat.format(new Date()) + "  " + message;
        String current = logView.getText().toString();
        String[] lines = (current + "\n" + line).split("\\n");
        int start = Math.max(0, lines.length - MAX_LOG_LINES);
        StringBuilder visible = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            if (visible.length() > 0) visible.append('\n');
            visible.append(lines[i]);
        }
        logView.setText(visible.toString());
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    public void onBackPressed() {
        if (flipper.getDisplayedChild() == PAGE_VIDEO) {
            if (camera != null) camera.stopLiveView();
            showConnectionPage(COLOR_BUSY, "Stopping…");
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (camera != null) camera.close();
        if (usbReceiver != null) unregisterReceiver(usbReceiver);
        decoderExecutor.shutdownNow();
        super.onDestroy();
    }
}
