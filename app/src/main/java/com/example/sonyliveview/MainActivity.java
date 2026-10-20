package com.example.sonyliveview;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.ViewFlipper;

import java.text.SimpleDateFormat;
import java.util.Arrays;
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

    // Palette and shared view factories live in Ui; these aliases keep the
    // rest of the class readable.
    private static final int COLOR_BG = Ui.COLOR_BG;
    private static final int COLOR_PANEL = Ui.COLOR_PANEL;
    private static final int COLOR_PANEL_SOFT = Ui.COLOR_PANEL_SOFT;
    private static final int COLOR_PREVIEW = Ui.COLOR_PREVIEW;
    private static final int COLOR_TEXT = Ui.COLOR_TEXT;
    private static final int COLOR_DIM = Ui.COLOR_DIM;
    private static final int COLOR_ACCENT = Ui.COLOR_ACCENT;
    private static final int COLOR_OK = Ui.COLOR_OK;
    private static final int COLOR_BUSY = Ui.COLOR_WARN;
    private static final int COLOR_ERR = Ui.COLOR_ERR;
    private static final int COLOR_SCOPE = Ui.COLOR_SCOPE;
    private static final int COLOR_REC = Ui.COLOR_REC;

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
    private TextView connectionBatteryLabel;
    private View statusDot;
    private Button connectButton;
    private Button liveViewButton;
    private Button disconnectButton;
    private CheckBox skipInfoCheck;
    private TextView logView;
    private ScrollView logScroll;
    private TextView deviceNameLabel;
    private TextView deviceDetailLabel;
    private View deviceAccent;
    private TextView logCountLabel;

    // Video page (the filming stage)
    private ImageView previewView;
    private ProgressBar startingSpinner;
    private LinearLayout startingPanel;
    private TextView videoFpsLabel;
    private TextView videoBatteryLabel;
    private TextView videoExposureLabel;
    private TextView videoStatusLabel;
    private TextView videoCameraLabel;
    private GridOverlayView gridView;
    private Button gridButton;

    // Stage chrome that has to stay clear of the display cutout in landscape.
    private Button stageStopButton;
    private LinearLayout stageInfoPanel;
    private LinearLayout stageTools;
    private LinearLayout appBar;
    private int insetLeft;
    private int insetRight;
    private int insetTop;
    private int insetBottom;

    // Last battery percent reported by the camera via the Sony 0xD218
    // property (from the 0x9209 GetAllDevicePropData container), or -1
    // until the first read. -1 means the label stays hidden.
    private volatile int batteryPercent = -1;

    private int frameCount;
    private int fpsCount;
    private long fpsWindowStart;
    private byte[] lastJpeg;
    private boolean aspectTipShown;

    // Focus peaking: 0 = off, 1 = red, 2 = yellow, 3 = white. Display-side
    // only (FocusPeaking), so no camera writes and no stall risk.
    private int peakingIndex;
    private Button peakingButton;

    // Live RGB histogram, computed in-app from the displayed frame (Imaging
    // Edge does the same host-side; the camera sends no histogram data on the
    // PTP live view path). Computed on the decoder thread when enabled.
    private boolean histogramEnabled;
    private Button histogramButton;
    private HistogramView histogramView;

    // Zebra exposure aid: on = 100% level (JPEG white). Display-side only,
    // like peaking and the histogram.
    private boolean zebraEnabled;
    private Button zebraButton;

    // Rule-of-thirds grid overlay (display-side only).
    private boolean gridEnabled;

    // Anamorphic desqueeze: 0 = off, otherwise an index into DESQUEEZE_FACTORS.
    // Display-side only: the decoded frame is stretched horizontally by the
    // squeeze factor so an anamorphic lens's squeezed image displays un-squeezed.
    private int desqueezeIndex;
    private Button desqueezeButton;
    private static final float[] DESQUEEZE_FACTORS = {1.33f, 1.5f, 2f};
    private static final String[] DESQUEEZE_LABELS = {"1.33x", "1.5x", "2x"};

    // Camera recording state: the a6300 reports movie recording via the Sony
    // 0xD21D property (Movie Recording State) in the same 0x9209 block as the
    // battery. While the camera records, a red frame borders the whole stage
    // and a red REC label shows top-center. Display-side only - recording
    // starts/stops on the camera itself.
    private TextView recLabel;
    private View recBorder;

    // Cinescope framing mask: 0 = off, otherwise an index into SCOPE_ASPECTS.
    // Display-side only: darkens everything outside the cinematic frame and
    // outlines it, like the aspect-ratio masks on Atomos/SmallHD monitors.
    private int scopeIndex;
    private Button scopeButton;
    private ScopeOverlayView scopeView;
    private static final float[] SCOPE_ASPECTS = {2.39f, 1.85f};
    private static final String[] SCOPE_LABELS = {"2.39", "1.85"};

    private final int[][] histogramBins = new int[3][HistogramView.BINS];
    private static final int HISTOGRAM_SAMPLE_WIDTH = 128;

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
        updateDeviceCard();
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
        applyWindowInsets();
    }

    /**
     * Keeps the stage chrome clear of the display cutout. On a phone in
     * landscape the camera cutout sits on one of the long edges, right where
     * the Stop button and the readout panel want to be.
     */
    private void applyWindowInsets() {
        getWindow().getDecorView().setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                insetLeft = bars.left;
                insetRight = bars.right;
                insetTop = bars.top;
                insetBottom = bars.bottom;
            } else {
                insetLeft = insets.getSystemWindowInsetLeft();
                insetRight = insets.getSystemWindowInsetRight();
                insetTop = insets.getSystemWindowInsetTop();
                insetBottom = insets.getSystemWindowInsetBottom();
            }
            applyStageInsets();
            return insets;
        });
    }

    /** Re-applies the safe-area margins to the stage chrome. */
    private void applyStageInsets() {
        if (appBar != null) {
            // The app draws edge-to-edge, so the bar has to make room for the
            // status bar itself.
            appBar.setPadding(dp(18), dp(14) + insetTop, dp(18), dp(14));
        }
        if (logView != null) {
            logView.setPadding(dp(18), dp(8), dp(18), dp(10) + insetBottom);
        }
        if (stageStopButton != null) {
            FrameLayout.LayoutParams params =
                    (FrameLayout.LayoutParams) stageStopButton.getLayoutParams();
            params.setMargins(dp(12) + insetLeft, dp(12) + insetTop, 0, 0);
            stageStopButton.setLayoutParams(params);
        }
        if (stageInfoPanel != null) {
            FrameLayout.LayoutParams params =
                    (FrameLayout.LayoutParams) stageInfoPanel.getLayoutParams();
            params.setMargins(0, dp(12) + insetTop, dp(12) + insetRight, 0);
            stageInfoPanel.setLayoutParams(params);
        }
        if (stageTools != null) {
            FrameLayout.LayoutParams params =
                    (FrameLayout.LayoutParams) stageTools.getLayoutParams();
            params.setMargins(0, 0, dp(12) + insetRight, dp(12) + insetBottom);
            stageTools.setLayoutParams(params);
        }
        if (histogramView != null) {
            FrameLayout.LayoutParams params =
                    (FrameLayout.LayoutParams) histogramView.getLayoutParams();
            params.setMargins(dp(12) + insetLeft, 0, 0, dp(12) + insetBottom);
            histogramView.setLayoutParams(params);
        }
        if (recLabel != null) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) recLabel.getLayoutParams();
            params.setMargins(0, dp(12) + insetTop, 0, 0);
            recLabel.setLayoutParams(params);
        }
        if (videoStatusLabel != null) {
            FrameLayout.LayoutParams params =
                    (FrameLayout.LayoutParams) videoStatusLabel.getLayoutParams();
            params.setMargins(0, 0, 0, dp(16) + insetBottom);
            videoStatusLabel.setLayoutParams(params);
        }
    }

    private LinearLayout buildConnectionPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(COLOR_BG);

        page.addView(buildAppBar(), lp(-1, -2, 0, 0, 0, 0, 0));
        // The rule needs an explicit height: a plain View with wrap_content
        // expands to fill whatever space is left in a vertical LinearLayout.
        page.addView(Ui.divider(this, Ui.COLOR_HAIRLINE), lp(-1, dp(1), 0, 0, 0, 0, 0));

        // The page is landscape-only, so the controls sit in a left column and
        // the session log gets a column of its own instead of a thin strip
        // under everything else.
        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setPadding(dp(20), dp(20), dp(20), dp(14));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);

        controls.addView(buildDeviceCard(), lp(-1, -2, 0, 0, 0, 0, 0));
        controls.addView(buildActions(), lp(-1, -2, 0, 0, dp(16), 0, 0));
        controls.addView(buildSetup(), lp(-1, -2, 0, 0, dp(22), 0, 0));

        LinearLayout logColumn = buildLogPanel();

        columns.addView(controls, lp(-1, -1, 0.46f, 0, 0, dp(24), 0));
        columns.addView(logColumn, lp(-1, -1, 0.54f, 0, 0, 0, 0));

        page.addView(columns, lp(-1, 0, 1, 0, 0, 0, 0));
        return page;
    }

    /** Top bar: wordmark, live status and the camera's battery level. */
    private LinearLayout buildAppBar() {
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(20), dp(14), dp(20), dp(14));
        toolbar.setBackgroundColor(COLOR_PANEL_SOFT);
        appBar = toolbar;

        TextView title = Ui.text(this, "SONY LIVEVIEW", Ui.SIZE_TITLE, COLOR_ACCENT, true);
        title.setLetterSpacing(0.16f);
        toolbar.addView(title, lp(-2, -2, 0, 0, 0, dp(20), 0));

        statusDot = new View(this);
        toolbar.addView(statusDot, new LinearLayout.LayoutParams(dp(8), dp(8)));

        statusLabel = Ui.text(this, "Disconnected", Ui.SIZE_LABEL, COLOR_ERR, false);
        statusLabel.setPadding(dp(8), 0, 0, 0);
        toolbar.addView(statusLabel, lp(-2, -2, 0, 0, 0, dp(16), 0));

        toolbar.addView(spacer(), lp(0, 0, 1, 0, 0, 0, 0));

        connectionBatteryLabel = Ui.text(this, "", Ui.SIZE_LABEL, COLOR_OK, false);
        connectionBatteryLabel.setPadding(dp(10), 0, dp(10), 0);
        connectionBatteryLabel.setBackground(Ui.surface(this, 0x1A6FDB94, 0x406FDB94, 9));
        connectionBatteryLabel.setVisibility(View.GONE);
        toolbar.addView(connectionBatteryLabel, lp(-2, -2, 0, 0, 0, 0, 0));

        return toolbar;
    }

    /** Card describing the attached USB device. */
    private View buildDeviceCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(Ui.surface(this, COLOR_PANEL, Ui.COLOR_HAIRLINE, 12));

        deviceAccent = new View(this);
        deviceAccent.setBackground(Ui.surface(this, COLOR_DIM, android.graphics.Color.TRANSPARENT, 2));
        LinearLayout.LayoutParams accentParams =
                new LinearLayout.LayoutParams(dp(3), dp(36));
        accentParams.setMargins(0, 0, dp(14), 0);
        card.addView(deviceAccent, accentParams);

        LinearLayout cardText = new LinearLayout(this);
        cardText.setOrientation(LinearLayout.VERTICAL);

        deviceNameLabel = Ui.text(this, "No camera attached", Ui.SIZE_BODY, COLOR_TEXT, true);
        cardText.addView(deviceNameLabel, lp(-1, -2, 0, 0, 0, 0, 0));

        deviceDetailLabel = Ui.text(this,
                "Set USB Connection to PC Remote, then plug in the OTG cable.",
                Ui.SIZE_LABEL, COLOR_DIM, false);
        deviceDetailLabel.setMaxLines(2);
        LinearLayout.LayoutParams detailParams = lp(-1, -2, 0, 0, dp(4), 0, 0);
        cardText.addView(deviceDetailLabel, detailParams);

        card.addView(cardText, lp(-1, -2, 0, 0, 0, 0, 0));
        return card;
    }

    /** Connect / LiveView / Disconnect plus the experimental toggle. */
    private View buildActions() {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        connectButton = Ui.primaryButton(this, "Connect", COLOR_ACCENT);
        connectButton.setOnClickListener(v -> requestCameraPermission());
        row.addView(connectButton, lp(-2, dp(44), 0, 0, 0, dp(10), 0));

        liveViewButton = Ui.secondaryButton(this, "Start LiveView", COLOR_OK);
        setButtonEnabled(liveViewButton, false);
        liveViewButton.setOnClickListener(v -> startLiveViewFromConnectionPage());
        row.addView(liveViewButton, lp(-2, dp(44), 0, 0, 0, dp(10), 0));

        disconnectButton = Ui.ghostButton(this, "Disconnect", COLOR_DIM);
        setButtonEnabled(disconnectButton, false);
        disconnectButton.setOnClickListener(v -> {
            if (camera != null) camera.close();
            appendStatus("Disconnecting…");
        });
        row.addView(disconnectButton, lp(-2, dp(44), 0, 0, 0, 0, 0));

        group.addView(row, lp(-1, -2, 0, 0, 0, 0, 0));

        // The experiment toggle gets its own line so the label is never
        // squeezed by the buttons.
        LinearLayout experiment = new LinearLayout(this);
        experiment.setOrientation(LinearLayout.VERTICAL);

        LinearLayout toggle = new LinearLayout(this);
        toggle.setOrientation(LinearLayout.HORIZONTAL);
        toggle.setGravity(Gravity.CENTER_VERTICAL);

        skipInfoCheck = new CheckBox(this);
        skipInfoCheck.setText("Skip GetObjectInfo");
        skipInfoCheck.setTextColor(COLOR_DIM);
        skipInfoCheck.setTextSize(Ui.SIZE_LABEL);
        skipInfoCheck.setChecked(false);
        skipInfoCheck.setButtonTintList(android.content.res.ColorStateList.valueOf(COLOR_DIM));
        toggle.addView(skipInfoCheck, lp(-2, -2, 0, 0, 0, 0, 0));

        experiment.addView(toggle, lp(-1, -2, 0, 0, 0, 0, 0));

        TextView hint = Ui.text(this, "experimental: one PTP round trip less per frame",
                Ui.SIZE_LABEL, 0xFF56646E, false);
        experiment.addView(hint, lp(-1, -2, 0, dp(30), dp(2), 0, 0));

        group.addView(experiment, lp(-1, -2, 0, 0, dp(10), 0, 0));
        return group;
    }

    /** The three things to do before the camera shows up. */
    private View buildSetup() {
        LinearLayout setup = new LinearLayout(this);
        setup.setOrientation(LinearLayout.VERTICAL);

        setup.addView(Ui.sectionLabel(this, "Setup"), lp(-1, -2, 0, 0, 0, dp(10), 0));
        setup.addView(stepRow("1", "Set USB Connection to PC Remote on the camera"),
                lp(-1, -2, 0, 0, 0, dp(6), 0));
        setup.addView(stepRow("2", "Connect the OTG cable and allow USB access"),
                lp(-1, -2, 0, 0, 0, dp(6), 0));
        setup.addView(stepRow("3", "Press Connect - LiveView starts on its own"),
                lp(-1, -2, 0, 0, 0, dp(6), 0));

        return setup;
    }

    /** Session log column: header, rule and the scrolling log itself. */
    private LinearLayout buildLogPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.surface(this, COLOR_PANEL_SOFT, Ui.COLOR_HAIRLINE, 12));

        LinearLayout logHeader = new LinearLayout(this);
        logHeader.setOrientation(LinearLayout.HORIZONTAL);
        logHeader.setGravity(Gravity.CENTER_VERTICAL);
        logHeader.setPadding(dp(18), dp(12), dp(12), dp(12));
        logHeader.addView(Ui.sectionLabel(this, "Session log"), lp(-2, -2, 0, 0, 0, 0, 0));
        logHeader.addView(spacer(), lp(0, 0, 1, 0, 0, 0, 0));

        logCountLabel = Ui.text(this, "0 lines", Ui.SIZE_LABEL, COLOR_DIM, false);
        logHeader.addView(logCountLabel, lp(-2, -2, 0, 0, 0, dp(12), 0));

        Button clearLogButton = Ui.ghostButton(this, "Clear", COLOR_DIM);
        clearLogButton.setTextSize(11);
        clearLogButton.setPadding(dp(10), 0, dp(10), 0);
        clearLogButton.setOnClickListener(v -> {
            logView.setText("");
            logCountLabel.setText("0 lines");
        });
        logHeader.addView(clearLogButton, lp(-2, dp(28), 0, 0, 0, 0, 0));

        panel.addView(logHeader, lp(-1, -2, 0, 0, 0, 0, 0));
        panel.addView(Ui.divider(this, Ui.COLOR_HAIRLINE), lp(-1, dp(1), 0, 0, 0, 0, 0));

        logScroll = new ScrollView(this);
        logScroll.setFillViewport(true);

        logView = new TextView(this);
        logView.setTextColor(COLOR_TEXT);
        logView.setTextSize(Ui.SIZE_MONO);
        logView.setPadding(dp(18), dp(12), dp(18), dp(12));
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setLineSpacing(dp(2), 1f);
        logScroll.addView(logView, new ScrollView.LayoutParams(-1, -2));
        logScroll.setVerticalScrollBarEnabled(false);

        panel.addView(logScroll, lp(-1, 0, 1, 0, 0, 0, 0));
        return panel;
    }

    /** One numbered line of the setup checklist. */
    private View stepRow(String number, String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView index = Ui.text(this, number, 10, COLOR_ACCENT, true);
        index.setGravity(Gravity.CENTER);
        index.setBackground(Ui.surface(this, 0x1A80CBC4, 0x4080CBC4, 7));
        LinearLayout.LayoutParams indexParams = new LinearLayout.LayoutParams(dp(18), dp(18));
        indexParams.setMargins(0, 0, dp(10), 0);
        row.addView(index, indexParams);

        TextView label = Ui.text(this, text, Ui.SIZE_LABEL, COLOR_DIM, false);
        row.addView(label, lp(-1, -2, 0, 0, 0, 0, 0));
        return row;
    }

    private FrameLayout buildVideoPage() {
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(COLOR_PREVIEW);

        previewView = new ImageView(this);
        previewView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        page.addView(previewView, new FrameLayout.LayoutParams(-1, -1));

        // Starting state: a quiet spinner instead of a bare line of text.
        LinearLayout starting = new LinearLayout(this);
        starting.setOrientation(LinearLayout.VERTICAL);
        starting.setGravity(Gravity.CENTER);

        startingSpinner = new ProgressBar(this);
        startingSpinner.setIndeterminate(true);
        startingSpinner.setIndeterminateTintList(
                android.content.res.ColorStateList.valueOf(COLOR_ACCENT));
        starting.addView(startingSpinner, new LinearLayout.LayoutParams(dp(28), dp(28)));

        TextView startingLabel = Ui.text(this, "Starting LiveView…", Ui.SIZE_BODY, COLOR_DIM, false);
        startingLabel.setGravity(Gravity.CENTER);
        starting.addView(startingLabel, lp(-2, -2, 0, 0, dp(12), 0, 0));
        startingPanel = starting;

        FrameLayout.LayoutParams startingParams = new FrameLayout.LayoutParams(-2, -2);
        startingParams.gravity = Gravity.CENTER;
        page.addView(starting, startingParams);

        // Rule-of-thirds grid over the frame; hidden until the Grid tool is on.
        gridView = new GridOverlayView(this);
        gridView.setVisibility(View.GONE);
        page.addView(gridView, new FrameLayout.LayoutParams(-1, -1));

        // Cinescope mask over the frame; hidden until the Scope tool is on.
        scopeView = new ScopeOverlayView(this);
        scopeView.setVisibility(View.GONE);
        page.addView(scopeView, new FrameLayout.LayoutParams(-1, -1));

        // Top-left: the only chrome up here is Stop (back to the connection
        // page). Everything else stays out of the frame.
        Button backButton = Ui.ghostButton(this, "Stop", COLOR_TEXT);
        backButton.setTextSize(Ui.SIZE_LABEL);
        backButton.setPadding(dp(14), 0, dp(14), 0);
        backButton.setBackground(Ui.surface(this, 0xB3000000, Ui.COLOR_HAIRLINE, 9));
        stageStopButton = backButton;
        backButton.setOnClickListener(v -> {
            if (camera != null) camera.stopLiveView();
            showConnectionPage(COLOR_BUSY, "Stopping…");
        });
        FrameLayout.LayoutParams backParams = new FrameLayout.LayoutParams(-2, dp(34));
        backParams.gravity = Gravity.TOP | Gravity.START;
        backParams.setMargins(dp(12), dp(12), 0, 0);
        page.addView(backButton, backParams);

        // Top-center: the camera-recording indicator. A label, not a button -
        // movie recording starts/stops on the camera (Sony 0xD21D).
        recLabel = Ui.text(this, "● REC", Ui.SIZE_LABEL, COLOR_REC, true);
        recLabel.setLetterSpacing(0.12f);
        recLabel.setGravity(Gravity.CENTER);
        recLabel.setPadding(dp(12), dp(5), dp(12), dp(5));
        recLabel.setVisibility(View.GONE);
        recLabel.setBackground(Ui.surface(this, 0xE61A0505, COLOR_REC, 9));
        FrameLayout.LayoutParams recParams = new FrameLayout.LayoutParams(-2, -2);
        recParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        recParams.setMargins(0, dp(12), 0, 0);
        page.addView(recLabel, recParams);

        // Top-right: one translucent panel with the filming readouts instead of
        // four loose labels floating over the frame.
        LinearLayout infoPanel = new LinearLayout(this);
        infoPanel.setOrientation(LinearLayout.VERTICAL);
        infoPanel.setPadding(dp(12), dp(8), dp(12), dp(9));
        infoPanel.setBackground(Ui.surface(this, 0xCC0A0D10, 0x3380CBC4, 10));

        videoCameraLabel = Ui.text(this, "a6300 · PC REMOTE", 10, COLOR_DIM, true);
        videoCameraLabel.setLetterSpacing(0.12f);
        infoPanel.addView(videoCameraLabel, new LinearLayout.LayoutParams(-2, -2));

        videoFpsLabel = Ui.text(this, "FPS 0", Ui.SIZE_BODY, COLOR_TEXT, true);
        infoPanel.addView(videoFpsLabel, lp(-2, -2, 0, 0, dp(5), 0, 0));

        videoExposureLabel = Ui.text(this, "", Ui.SIZE_LABEL, COLOR_TEXT, false);
        videoExposureLabel.setVisibility(View.GONE);
        infoPanel.addView(videoExposureLabel, lp(-2, -2, 0, 0, dp(3), 0, 0));

        videoBatteryLabel = Ui.text(this, "", Ui.SIZE_LABEL, COLOR_OK, false);
        videoBatteryLabel.setVisibility(View.GONE);
        infoPanel.addView(videoBatteryLabel, lp(-2, -2, 0, 0, dp(2), 0, 0));

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(-2, -2);
        panelParams.gravity = Gravity.TOP | Gravity.END;
        panelParams.setMargins(0, dp(12), dp(12), 0);
        stageInfoPanel = infoPanel;
        page.addView(infoPanel, panelParams);

        // Bottom-right: the filming tools, grouped in a single toolbar so they
        // read as one control surface instead of six floating chips.
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setPadding(dp(6), dp(4), dp(6), dp(4));
        tools.setBackground(Ui.surface(this, 0xB3000000, Ui.COLOR_HAIRLINE, 12));

        peakingButton = Ui.toolChip(this, "Peak: Off");
        peakingButton.setOnClickListener(v -> cyclePeaking());
        tools.addView(peakingButton, lp(-2, dp(34), 0, 0, 0, dp(4), 0));

        zebraButton = Ui.toolChip(this, "Zebra: Off");
        zebraButton.setOnClickListener(v -> toggleZebra());
        tools.addView(zebraButton, lp(-2, dp(34), 0, 0, 0, dp(4), 0));

        tools.addView(toolDivider(), lp(dp(1), dp(16), 0, 0, 0, dp(6), 0));

        gridButton = Ui.toolChip(this, "Grid: Off");
        gridButton.setOnClickListener(v -> toggleGrid());
        tools.addView(gridButton, lp(-2, dp(34), 0, 0, 0, dp(4), 0));

        desqueezeButton = Ui.toolChip(this, "DeSq: Off");
        desqueezeButton.setOnClickListener(v -> cycleDesqueeze());
        tools.addView(desqueezeButton, lp(-2, dp(34), 0, 0, 0, dp(4), 0));

        scopeButton = Ui.toolChip(this, "Scope: Off");
        scopeButton.setOnClickListener(v -> cycleScope());
        tools.addView(scopeButton, lp(-2, dp(34), 0, 0, 0, dp(4), 0));

        tools.addView(toolDivider(), lp(dp(1), dp(16), 0, 0, 0, dp(6), 0));

        histogramButton = Ui.toolChip(this, "Hist: Off");
        histogramButton.setOnClickListener(v -> toggleHistogram());
        tools.addView(histogramButton, lp(-2, dp(34), 0, 0, 0, 0, 0));

        FrameLayout.LayoutParams toolsParams = new FrameLayout.LayoutParams(-2, -2);
        toolsParams.gravity = Gravity.BOTTOM | Gravity.END;
        toolsParams.setMargins(0, 0, dp(12), dp(12));
        stageTools = tools;
        page.addView(tools, toolsParams);

        histogramView = new HistogramView(this);
        histogramView.setVisibility(View.GONE);
        FrameLayout.LayoutParams histViewParams = new FrameLayout.LayoutParams(dp(158), dp(70));
        histViewParams.gravity = Gravity.BOTTOM | Gravity.START;
        histViewParams.setMargins(dp(12), 0, 0, dp(12));
        page.addView(histogramView, histViewParams);

        // Added last so it draws on top of everything: the recording frame. A
        // transparent view with a thick red stroke around the screen edges.
        recBorder = new View(this);
        recBorder.setVisibility(View.GONE);
        GradientDrawable recBorderBackground = new GradientDrawable();
        recBorderBackground.setColor(0x00000000);
        recBorderBackground.setStroke(dp(4), COLOR_REC);
        recBorder.setBackground(recBorderBackground);
        page.addView(recBorder, new FrameLayout.LayoutParams(-1, -1));

        videoStatusLabel = Ui.text(this, "", Ui.SIZE_LABEL, COLOR_BUSY, true);
        videoStatusLabel.setGravity(Gravity.CENTER);
        videoStatusLabel.setPadding(dp(12), dp(7), dp(12), dp(7));
        videoStatusLabel.setVisibility(View.GONE);
        videoStatusLabel.setBackground(Ui.surface(this, 0xE6141A20, COLOR_BUSY, 8));
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(-2, -2);
        statusParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        statusParams.setMargins(0, 0, 0, dp(16));
        page.addView(videoStatusLabel, statusParams);

        return page;
    }

    /** Thin vertical rule that separates groups of tool chips. */
    private View toolDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(1), dp(16));
        divider.setLayoutParams(params);
        return divider;
    }

    /** Colors a tool chip to show its active state. */
    private void styleToggle(Button button, boolean active, int activeColor) {
        Ui.styleChip(button, active, activeColor);
    }

    private LinearLayout spacer() {
        LinearLayout spacer = new LinearLayout(this);
        spacer.setOrientation(LinearLayout.HORIZONTAL);
        return spacer;
    }

    private void setButtonEnabled(Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.35f);
    }

    private void cyclePeaking() {
        peakingIndex = (peakingIndex + 1) % (FocusPeaking.COLORS.length + 1);
        String label;
        int color;
        if (peakingIndex == 0) {
            label = "Peak: Off";
            color = COLOR_TEXT;
        } else {
            color = FocusPeaking.COLORS[peakingIndex - 1];
            label = "Peak: " + (peakingIndex == 1 ? "Red" : peakingIndex == 2 ? "Yellow" : "White");
        }
        peakingButton.setText(label);
        styleToggle(peakingButton, peakingIndex != 0, color);
        appendStatus("Focus peaking " + (peakingIndex == 0 ? "off" : "on (" + label.substring(6) + ")"));
    }

    private void toggleHistogram() {
        histogramEnabled = !histogramEnabled;
        histogramButton.setText(histogramEnabled ? "Hist: On" : "Hist: Off");
        styleToggle(histogramButton, histogramEnabled, COLOR_ACCENT);
        histogramView.setVisibility(histogramEnabled ? View.VISIBLE : View.GONE);
        appendStatus("Histogram " + (histogramEnabled ? "on" : "off"));
    }

    private void toggleZebra() {
        zebraEnabled = !zebraEnabled;
        zebraButton.setText(zebraEnabled ? "Zebra: 100%" : "Zebra: Off");
        styleToggle(zebraButton, zebraEnabled, COLOR_BUSY);
        appendStatus("Zebra " + (zebraEnabled ? "on (100%)" : "off"));
    }

    private void toggleGrid() {
        gridEnabled = !gridEnabled;
        gridButton.setText(gridEnabled ? "Grid: Thirds" : "Grid: Off");
        styleToggle(gridButton, gridEnabled, COLOR_ACCENT);
        gridView.setThirds(gridEnabled);
        appendStatus("Thirds grid " + (gridEnabled ? "on" : "off"));
    }

    private void cycleDesqueeze() {
        desqueezeIndex = (desqueezeIndex + 1) % (DESQUEEZE_FACTORS.length + 1);
        String label = desqueezeIndex == 0
                ? "DeSq: Off"
                : "DeSq: " + DESQUEEZE_LABELS[desqueezeIndex - 1];
        desqueezeButton.setText(label);
        styleToggle(desqueezeButton, desqueezeIndex != 0, COLOR_OK);
        appendStatus("Anamorphic desqueeze " +
                (desqueezeIndex == 0 ? "off" : "on (" + DESQUEEZE_LABELS[desqueezeIndex - 1] + ")"));
    }

    private void cycleScope() {
        scopeIndex = (scopeIndex + 1) % (SCOPE_ASPECTS.length + 1);
        String label = scopeIndex == 0 ? "Scope: Off" : "Scope: " + SCOPE_LABELS[scopeIndex - 1];
        scopeButton.setText(label);
        styleToggle(scopeButton, scopeIndex != 0, COLOR_SCOPE);
        scopeView.setScopeAspect(scopeIndex > 0 ? SCOPE_ASPECTS[scopeIndex - 1] : 0f);
        appendStatus("Cinescope " + (scopeIndex == 0
                ? "off"
                : "on (" + SCOPE_LABELS[scopeIndex - 1] + ":1)"));
    }

    private void showConnectionPage(int color, String label) {
        flipper.setDisplayedChild(PAGE_CONNECTION);
        applyStageImmersive(false);
        // The connection page is not a shooting surface: let the screen sleep
        // on its normal timeout again.
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setStatus(color, label);
    }

    private void showVideoPage() {
        startingPanel.setVisibility(View.VISIBLE);
        flipper.setDisplayedChild(PAGE_VIDEO);
        // The filming stage goes edge-to-edge: hide the system bars so the
        // frame fills the screen; a swipe reveals them transiently.
        applyStageImmersive(true);
        // Never let the display sleep while the stage is up - a monitor that
        // dims mid-shot is useless. The flag lives only while the video page
        // is shown; leaving the stage clears it.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** Hides (true) or restores (false) the system bars for the video stage. */
    private void applyStageImmersive(boolean immersive) {
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller == null) return;
            if (immersive) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                controller.show(WindowInsets.Type.systemBars());
            }
        } else {
            decor.setSystemUiVisibility(immersive
                    ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                    : View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    private void setStatus(int color, String label) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        statusDot.setBackground(dot);
        statusLabel.setText(label);
        statusLabel.setTextColor(color);
    }

    private LinearLayout.LayoutParams lp(int width, int height, float weight,
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
                String action = intent.getAction();
                if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action) ||
                        UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                    // A camera was plugged in or pulled out: refresh the card
                    // so the page always shows the current USB state.
                    updateDeviceCard();
                    return;
                }
                if (!ACTION_USB_PERMISSION.equals(action)) return;
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
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
    }

    /**
     * Shows the attached USB camera in the device card: product name, USB
     * identity and whether it is the a6300 in PC Remote mode.
     */
    private void updateDeviceCard() {
        if (deviceNameLabel == null) return;
        UsbDevice device = findPtpDevice();
        if (device == null) {
            deviceNameLabel.setText("No camera attached");
            deviceDetailLabel.setText(
                    "Set USB Connection to PC Remote, then plug in the OTG cable.");
            setCardAccent(COLOR_DIM);
            return;
        }
        String identity = String.format(Locale.US, "%04x:%04x",
                device.getVendorId(), device.getProductId());
        boolean sony = device.getVendorId() == SONY_VENDOR_ID &&
                device.getProductId() == A6300_PC_REMOTE_PRODUCT_ID;
        deviceNameLabel.setText(sony ? "Sony a6300" : "USB device " + device.getDeviceName());
        deviceDetailLabel.setText(identity + "  ·  " +
                device.getInterfaceCount() + " interface(s)" +
                (sony ? "  ·  PC Remote" : ""));
        setCardAccent(sony ? COLOR_ACCENT : COLOR_BUSY);
    }

    private void setCardAccent(int color) {
        if (deviceAccent == null) return;
        deviceAccent.setBackground(Ui.surface(this, color,
                android.graphics.Color.TRANSPARENT, 2));
    }

    private void requestCameraPermission() {
        if (usbManager == null) {
            appendStatus("USB host is unavailable on this phone.");
            return;
        }
        UsbDevice device = findPtpDevice();
        if (device == null) {
            updateDeviceCard();
            appendStatus("No PTP camera found. Set USB Connection to PC Remote and reconnect.");
            setButtonEnabled(connectButton, true);
            return;
        }
        updateDeviceCard();
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
        if (usbManager == null) return null;
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
        camera.setSkipObjectInfo(skipInfoCheck.isChecked());
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
            } else if (message.startsWith("LiveView error") ||
                    message.startsWith("Restarting the USB session")) {
                // Keep the video page up during automatic recovery and show
                // the progress on an overlay instead of hiding the stream.
                videoStatusLabel.setVisibility(View.VISIBLE);
                videoStatusLabel.setText("RECOVERING: " + message);
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
            camera.setSkipObjectInfo(skipInfoCheck.isChecked());
            camera.requestLiveView();
        });
    }

    @Override
    public void onBattery(int percent) {
        batteryPercent = percent;
        runOnUiThread(() -> {
            String label = "BATTERY " + percent + "%";
            connectionBatteryLabel.setText(label);
            connectionBatteryLabel.setVisibility(View.VISIBLE);
            videoBatteryLabel.setText(label);
            videoBatteryLabel.setVisibility(View.VISIBLE);
        });
    }

    @Override
    public void onExposure(String label) {
        runOnUiThread(() -> {
            videoExposureLabel.setText(label);
            videoExposureLabel.setVisibility(View.VISIBLE);
            appendStatus("Exposure: " + label);
        });
    }

    @Override
    public void onFrame(byte[] jpeg) {
        // Received-frame accounting runs here on the camera thread; it only
        // does cheap counters plus a one-line log, never a JPEG decode.
        int currentFrame = ++frameCount;
        fpsCount++;
        // Consecutive identical JPEGs mean the camera served a stale frame
        // (it had not produced a new one yet); they are skipped, not decoded.
        if (!Arrays.equals(jpeg, lastJpeg)) {
            lastJpeg = jpeg;
            latestJpeg.set(jpeg);
            decoderExecutor.execute(this::decodeAndShow);
        }
        long now = SystemClock.elapsedRealtime();
        if (fpsWindowStart == 0) fpsWindowStart = now;
        long elapsed = now - fpsWindowStart;
        if (elapsed >= 1000) {
            final int fps = (int) Math.round(fpsCount * 1000.0 / elapsed);
            fpsCount = 0;
            fpsWindowStart = now;
            final String interval = fps > 0 ? String.format(Locale.US, "%dms", 1000 / fps) : "-";
            runOnUiThread(() -> videoFpsLabel.setText("FPS " + fps + " · " + interval));
        }
        if (currentFrame == 1 || currentFrame % 30 == 0) {
            Log.d(TAG, "JPEG frames received=" + currentFrame + " latestBytes=" + jpeg.length);
        }
    }

    /** Runs on the decoder thread; decodes only the newest pending frame. */
    private void computeHistogram(Bitmap bitmap, int[][] bins) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int sampleWidth = Math.min(HISTOGRAM_SAMPLE_WIDTH, width);
        int sampleHeight = Math.max(1, Math.round(height * sampleWidth / (float) width));
        Bitmap small = Bitmap.createScaledBitmap(bitmap, sampleWidth, sampleHeight, true);
        int[] pixels = new int[sampleWidth * sampleHeight];
        small.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight);
        if (small != bitmap) small.recycle();
        for (int i = 0; i < 3; i++) Arrays.fill(bins[i], 0);
        for (int p : pixels) {
            bins[0][((p >>> 16) & 0xFF) * HistogramView.BINS / 256]++;
            bins[1][((p >>> 8) & 0xFF) * HistogramView.BINS / 256]++;
            bins[2][(p & 0xFF) * HistogramView.BINS / 256]++;
        }
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
        final boolean showAspectTip = !aspectTipShown && width == 1024 && height == 574;
        if (showAspectTip) aspectTipShown = true;
        final int desqueeze = desqueezeIndex;
        final float desqueezeFactor = desqueeze > 0 ? DESQUEEZE_FACTORS[desqueeze - 1] : 1f;
        // Histogram comes from the untouched frame (peaking would skew it).
        // Sampling a 128px-wide copy keeps it under ~0.1 ms per frame.
        if (histogramEnabled) {
            computeHistogram(bitmap, histogramBins);
            histogramView.setHistogram(histogramBins[0], histogramBins[1], histogramBins[2]);
        }
        if (peakingIndex > 0) {
            // Runs on the decoder thread, which is also the only thread that
            // touches FocusPeaking's reused buffers - safe without locks.
            bitmap = FocusPeaking.apply(bitmap, FocusPeaking.COLORS[peakingIndex - 1]);
        }
        if (zebraEnabled) {
            // Applied after peaking so the stripes stay visible on top of the
            // edge tint. Same single-threaded buffer rules as FocusPeaking.
            bitmap = Zebra.apply(bitmap);
        }
        if (desqueezeFactor > 1f) {
            // Last, because it is a geometric stretch rather than a pixel
            // filter: widens the squeezed anamorphic frame back to its true
            // aspect (1.33x / 1.5x / 2x). The histogram above stays untouched.
            Matrix matrix = new Matrix();
            matrix.postScale(desqueezeFactor, 1f);
            Bitmap desqueezed = Bitmap.createBitmap(bitmap, 0, 0,
                    bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (desqueezed != bitmap) bitmap.recycle();
            bitmap = desqueezed;
        }
        final Bitmap shownBitmap = bitmap;
        runOnUiThread(() -> {
            previewView.setImageBitmap(shownBitmap);
            startingPanel.setVisibility(View.GONE);
            videoStatusLabel.setVisibility(View.GONE);
            // Grid and scope follow the displayed (desqueezed) image rectangle.
            int shownWidth = Math.round(width * desqueezeFactor);
            gridView.setImageAspect(shownWidth, height);
            scopeView.setImageAspect(shownWidth, height);
            if (showAspectTip) {
                appendStatus("Tip: camera Aspect Ratio is 16:9 (1024×574). " +
                        "Set it to 3:2 on the camera for a taller 1024×680 live view.");
            }
        });
    }

    @Override
    public void onRecording(boolean recording) {
        runOnUiThread(() -> {
            recBorder.setVisibility(recording ? View.VISIBLE : View.GONE);
            recLabel.setVisibility(recording ? View.VISIBLE : View.GONE);
            appendStatus(recording ? "Camera recording started" : "Camera recording stopped");
        });
    }

    @Override
    public void onClosed() {
        runOnUiThread(() -> {
            connected = false;
            showConnectionPage(COLOR_ERR, "Disconnected");
            applyStageImmersive(false);
            appendStatus("Camera connection closed.");
            setButtonEnabled(connectButton, true);
            setButtonEnabled(disconnectButton, false);
            setButtonEnabled(liveViewButton, false);
            videoFpsLabel.setText("FPS 0");
            batteryPercent = -1;
            connectionBatteryLabel.setText("");
            connectionBatteryLabel.setVisibility(View.GONE);
            videoBatteryLabel.setText("");
            videoBatteryLabel.setVisibility(View.GONE);
            videoExposureLabel.setText("");
            videoExposureLabel.setVisibility(View.GONE);
            recBorder.setVisibility(View.GONE);
            recLabel.setVisibility(View.GONE);
        });
    }

    // ------------------------------------------------------------------
    // Log panel
    // ------------------------------------------------------------------

    private void appendStatus(String message) {
        Log.d(TAG, "UI status: " + message);
        if (logView == null) return;
        String stamp = timeFormat.format(new Date());
        String current = logView.getText().toString();
        String[] lines = (current + "\n" + stamp + "  " + message).split("\\n");
        int start = Math.max(0, lines.length - MAX_LOG_LINES);
        StringBuilder visible = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            if (visible.length() > 0) visible.append('\n');
            visible.append(lines[i]);
        }
        // Timestamps are dimmed so the messages carry the eye.
        SpannableStringBuilder styled = new SpannableStringBuilder(visible);
        String plain = visible.toString();
        int lineStart = 0;
        while (lineStart < plain.length()) {
            int lineEnd = plain.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = plain.length();
            styled.setSpan(new ForegroundColorSpan(0xFF55636D), lineStart,
                    Math.min(lineStart + stamp.length(), lineEnd),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            lineStart = lineEnd + 1;
        }
        logView.setText(styled);
        if (logCountLabel != null) {
            int count = lines.length - start;
            logCountLabel.setText(count + (count == 1 ? " line" : " lines"));
        }
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
