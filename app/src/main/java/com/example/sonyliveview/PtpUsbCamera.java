package com.example.sonyliveview;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal Sony PTP/USB prototype. The camera must be in PC Remote mode. */
public final class PtpUsbCamera implements AutoCloseable {
    private static final String TAG = "SonyLiveView";
    public interface Listener {
        void onState(String message);
        void onReady();
        void onFrame(byte[] jpeg);
        void onBattery(int percent);
        // Human-readable exposure line from the 0x9209 property block, e.g.
        // "1/5s · f/4.0 · ISO AUTO". Fired only when it changes.
        void onExposure(String label);
        void onClosed();
    }

    private static final int PTP_COMMAND = 1;
    private static final int PTP_DATA = 2;
    private static final int PTP_RESPONSE = 3;
    private static final int PTP_EVENT = 4;

    private static final int PTP_OC_GET_DEVICE_INFO = 0x1001;
    private static final int PTP_OC_OPEN_SESSION = 0x1002;
    private static final int PTP_OC_GET_STORAGE_IDS = 0x1004;
    private static final int PTP_OC_GET_OBJECT_INFO = 0x1008;
    private static final int PTP_OC_GET_OBJECT = 0x1009;
    private static final int USB_TRANSFER_TIMEOUT_MS = 15000;
    // Live-view frames arrive every ~90 ms, so a transaction that yields no
    // data for three seconds is broken. A short streaming timeout makes
    // transient camera hiccups surface in seconds instead of freezing the
    // UI for 45 s, and the recovery loop below then restarts the stream.
    private static final int STREAM_READ_TIMEOUT_MS = 3000;
    private static final int USB_READ_RETRIES = 3;
    // Automatic recovery attempts for a broken stream: the first retry just
    // re-synchronizes on the same session, later retries rebuild the whole
    // USB session. Beyond this the app gives up and asks for a power cycle.
    private static final int MAX_LIVEVIEW_RETRIES = 3;
    // Bulk IN reads must use buffers that are a multiple of the endpoint's
    // max packet size (512). A short header read (e.g. exactly 12 bytes)
    // against an incoming 512-byte packet causes a USB overflow on Android
    // and the rest of that packet is lost, which desynchronizes the PTP
    // stream. The capture shows Sony responses arrive as one USB transfer
    // made of several 512-byte packets plus a short packet (e.g. the
    // 0x9209 D221 container is 512 + 512 + 228 bytes), so always read a
    // large 512-aligned chunk and parse containers out of the buffered
    // stream, keeping the remainder for the next container. 64 KB is a
    // universally safe bulk-transfer size on Android; the two reads a ~132 KB
    // frame container needs cost microseconds compared to the camera's frame
    // generation time, so there is no reason to risk device URB-size limits.
    private static final int USB_READ_CHUNK = 64 * 1024;

    // Experimental: the Imaging Edge capture polls 0x9209 once between every
    // GetObject, so this defaults to keeping it. Setting it to true removes
    // that transaction from the frame loop (one third fewer round trips per
    // frame) for hardware testing; the camera may then serve stale frames.
    private static final boolean SKIP_INTERFRAME_STATUS_POLL = false;

    // Sony PTP extension commands found in the Monitor+ binary and public PTP traces.
    private static final int SONY_SDIO_CONNECT = 0x9201;
    private static final int SONY_SDIO_GET_EXT_DEVICE_INFO = 0x9202;
    private static final int SONY_SET_EXT_DEVICE_PROP_VALUE = 0x9205;
    private static final int SONY_GET_ALL_EXT_DEVICE_INFO = 0x9209;
    private static final int SONY_PRIORITY_MODE = 0xD25A;
    private static final int SONY_OPERATING_MODE = 0x5013;
    private static final int SONY_STANDBY_MODE = 0x00000001;
    private static final int SONY_LIVE_VIEW_STATUS = 0xD221;
    private static final int SONY_LIVE_VIEW_OBJECT = 0xFFFFC002;
    // Sony device properties returned inside the 0x9209 data container,
    // all confirmed against the Imaging Edge capture: 0xD218 = Battery
    // Level (INT8 percent, 0x0E = 14% in all 110 responses), 0xD20D =
    // Shutter Speed (UINT32 seconds as (numerator<<16)|denominator),
    // 0x5007 = F-Number (UINT16 f-number * 100), 0xD21E = ISO (UINT32
    // literal, 0xFFFFFF = AUTO) and 0x5010 = Exposure Compensation
    // (INT16 EV * 1000). The exposure values are 0 until live view
    // starts; the capture shows 0x00010005 = 1/5 s, 400 = f/4.0 and
    // 0xFFFFFF = AUTO once it is running.
    private static final int SONY_BATTERY_LEVEL = 0xD218;
    private static final int SONY_SHUTTER_SPEED = 0xD20D;
    private static final int SONY_FNUMBER = 0x5007;
    private static final int SONY_ISO = 0xD21E;
    private static final int SONY_EXPOSURE_COMP = 0x5010;
    // NOTE: Sony's SetExtDevicePropValue (0x9205) is NOT sent to the a6300.
    // A property write there (even a documented one such as LiveView mode
    // 0xD26A) can STALL the camera's bulk OUT pipe at the USB level (-1 on
    // OUT 0x02), which halts every later transaction until the camera is
    // power-cycled.

    private final UsbManager usbManager;
    private final UsbDevice device;
    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final JpegExtractor jpegExtractor = new JpegExtractor();

    private volatile boolean closed;
    private volatile boolean ready;
    private volatile boolean streaming;
    private volatile boolean liveViewEnabled;
    // When true, GetObjectInfo is sent once and then skipped while GetObject
    // keeps returning 0x2001 (one fewer PTP round trip per frame). If a
    // GetObject fails, GetObjectInfo is re-issued to re-sync.
    private volatile boolean skipObjectInfo = false;
    // Most recently seen battery percent (0..100) or -1 before the first
    // 0x9209 data container is parsed. The listener is only notified on
    // change, so the per-frame polls do not spam the UI thread.
    private volatile int lastBatteryPercent = -1;
    // Last exposure line sent to the listener, or null. Same change-only
    // rule as the battery: the ~10 Hz readiness polls carry the same
    // values (the camera only updates them when the exposure changes).
    private volatile String lastExposureLabel;
    private UsbDeviceConnection connection;
    private UsbEndpoint bulkIn;
    private UsbEndpoint bulkOut;
    private UsbEndpoint interruptIn;
    // Bytes received from the camera but not yet consumed by a container.
    // A single USB transfer can end with the tail of one container and the
    // head of the next, so these must survive between container parses.
    private byte[] incoming = new byte[0];
    private int incomingLength;
    private final byte[] readChunk = new byte[USB_READ_CHUNK];
    // PTP reserves transaction ID 0 for OpenSession; subsequent operations start at 1.
    private int transactionId = 0;

    public PtpUsbCamera(UsbManager usbManager, UsbDevice device, Listener listener) {
        this.usbManager = usbManager;
        this.device = device;
        this.listener = listener;
    }

    public void start() {
        executor.execute(() -> {
            try {
                openUsb();
                handshake();
                Thread.sleep(50L);
                state("Sony PTP/USB session is ready.");
                ready = true;
                listener.onReady();
                // Keep the PTP session open after onReady(). The LiveView
                // button uses this same executor for the next transaction.
                // Do not start a second bulk-IN reader here.
            } catch (Exception error) {
                Log.e(TAG, "USB/PTP connection failed", error);
                state("USB/PTP error: " + error.getMessage());
            } finally {
                // A successful handshake remains owned by this object until
                // close(). Only failed handshakes are cleaned up here.
                if (!ready) {
                    closed = true;
                    closeInternal();
                    listener.onClosed();
                }
            }
        });
    }

    /** Runs the capture-confirmed PTP session sequence (OpenSession through the SDIO connect). */
    private void handshake() throws IOException {
        state("Opening PTP session (transaction 0)...");
        sendCommand(PTP_OC_OPEN_SESSION, 1);
        expectOk("OpenSession", readUntilResponse("OpenSession"));
        sendCommand(PTP_OC_GET_DEVICE_INFO);
        expectOk("GetDeviceInfo", readUntilResponse("GetDeviceInfo"));
        // Imaging Edge performs GetStorageIDs before the Sony SDIO
        // handshake. The a6300 returns one storage ID (0x00010000)
        // in the data phase, so preserve this transaction instead of
        // entering the Sony extension sequence immediately.
        sendCommand(PTP_OC_GET_STORAGE_IDS);
        expectOk("GetStorageIDs", readUntilResponse("GetStorageIDs"));
        sendCommand(SONY_SDIO_CONNECT, 1, 0, 0);
        expectOk("Sony SDIO_Connect phase 1", readUntilResponse("Sony SDIO_Connect phase 1"));
        sendCommand(SONY_SDIO_CONNECT, 2, 0, 0);
        expectOk("Sony SDIO_Connect phase 2", readUntilResponse("Sony SDIO_Connect phase 2"));
        sendCommand(SONY_SDIO_GET_EXT_DEVICE_INFO, 0xC8);
        expectOk("Sony SDIO_GetExtDeviceInfo", readUntilResponse("Sony SDIO_GetExtDeviceInfo"));
        sendCommand(SONY_SDIO_CONNECT, 3, 0, 0);
        expectOk("Sony SDIO_Connect phase 3", readUntilResponse("Sony SDIO_Connect phase 3"));
        // Imaging Edge refreshes the extended-device information
        // after phase 3, immediately before its 0x9209 polling loop.
        sendCommand(SONY_SDIO_GET_EXT_DEVICE_INFO, 0xC8);
        expectOk("Sony SDIO_GetExtDeviceInfo refresh", readUntilResponse("Sony SDIO_GetExtDeviceInfo refresh"));
    }

    /**
     * Fails the connection attempt when a handshake response is not OK.
     * Every transaction in the Imaging Edge capture (OpenSession through the
     * second SDIO_GetExtDeviceInfo refresh) returns 0x2001. If the SDIO
     * connect is rejected on the phone, proceeding to 0x9209 would leave the
     * camera silent and the read would time out with a confusing -1, so fail
     * fast here instead.
     */
    private void expectOk(String operation, int responseCode) throws IOException {
        if (responseCode != 0x2001) {
            throw new IOException(String.format("%s failed with PTP response 0x%04X",
                    operation, responseCode));
        }
    }

    /** Requests Sony's LiveView-status operation after the SDIO session is open. */
    public void requestLiveView() {
        // Keep the command, its response, and the stream on the same executor.
        // Two concurrent bulk-IN readers can steal each other's PTP containers.
        executor.execute(() -> {
            if (closed || !ready) {
                state("LiveView request ignored: PTP session is not ready.");
                return;
            }
            if (streaming) {
                state("LiveView request ignored: a LiveView request is already active.");
                return;
            }
            liveViewEnabled = true;
            int retries = 0;
            boolean gaveUp = false;
            while (!closed && liveViewEnabled) {
                try {
                    streaming = true;
                    runLiveView();
                    break;
                } catch (Exception error) {
                    streaming = false;
                    Log.e(TAG, "LiveView stream error", error);
                    retries++;
                    if (retries > MAX_LIVEVIEW_RETRIES || closed || !liveViewEnabled) {
                        if (retries > MAX_LIVEVIEW_RETRIES && !closed) {
                            gaveUp = true;
                            state("LiveView request failed after " + retries +
                                    " attempts: " + error.getMessage());
                            state("Camera did not recover; power-cycle the camera (off and on) and reconnect.");
                            closed = true;
                            closeInternal();
                            listener.onClosed();
                        } else {
                            state("LiveView request failed: " + error.getMessage());
                        }
                        break;
                    }
                    state("LiveView error (" + retries + "/" + MAX_LIVEVIEW_RETRIES +
                            "), recovering: " + error.getMessage());
                    try {
                        Thread.sleep(500L * retries);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (!liveViewEnabled || closed) break; // user stopped meanwhile
                    // First retry re-synchronizes the stream on the same
                    // session; later retries rebuild the whole USB session.
                    resetStreamState();
                    if (retries >= 2) {
                        state("Restarting the USB session...");
                        try {
                            restartSession();
                        } catch (Exception restartError) {
                            Log.e(TAG, "Session restart failed", restartError);
                            gaveUp = true;
                            state("Session restart failed: " + restartError.getMessage());
                            state("Camera did not recover; power-cycle the camera (off and on) and reconnect.");
                            closed = true;
                            closeInternal();
                            listener.onClosed();
                            break;
                        }
                    }
                }
            }
            streaming = false;
            liveViewEnabled = false;
            if (!closed && !gaveUp) {
                state("Sony LiveView stopped.");
            }
        });
    }

    /** Polls readiness, then pulls LiveView JPEG frames until stopped or failed. */
    private void runLiveView() throws IOException {
        // The a6300/Imaging Edge trace uses 0x9209 as a short polling
        // operation. It has a data phase: the camera answers with a
        // 1252-byte D221 status container that spans three USB bulk
        // transfers (512 + 512 + 228) followed by response 0x2001.
        // readUntilResponse consumes the data container and returns
        // on the response. Imaging Edge polls ten times before the
        // first virtual-object request.
        for (int poll = 0; poll < 10 && !closed && liveViewEnabled; poll++) {
            sendCommand(SONY_GET_ALL_EXT_DEVICE_INFO);
            state("Sony LiveView readiness poll " + (poll + 1) + "/10 sent...");
            readUntilResponse("Sony LiveView readiness poll");
            try {
                Thread.sleep(50L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("LiveView interrupted", interrupted);
            }
        }
        state("Sony LiveView readiness polls complete; requesting virtual JPEG object.");
        boolean objectInfoNeeded = true;
        while (!closed && liveViewEnabled) {
            // Sony PTP2 exposes LiveView as a virtual object rather
            // than a UVC stream. GetObjectInfo returns the object
            // metadata, then GetObject carries the JPEG-containing
            // data container across multiple USB bulk transfers.
            // No sleep here: the camera answers each transaction as
            // fast as it can, so the frame rate is camera-governed
            // instead of capped at 20 fps by a 50 ms host sleep.
            if (objectInfoNeeded) {
                sendCommand(PTP_OC_GET_OBJECT_INFO, SONY_LIVE_VIEW_OBJECT);
                readUntilResponse("LiveView GetObjectInfo");
            }
            sendCommand(PTP_OC_GET_OBJECT, SONY_LIVE_VIEW_OBJECT);
            int getObjectResult = readUntilResponse("LiveView GetObject");
            objectInfoNeeded = !skipObjectInfo || getObjectResult != 0x2001;
            if (!SKIP_INTERFRAME_STATUS_POLL) {
                sendCommand(SONY_GET_ALL_EXT_DEVICE_INFO);
                readUntilResponse("Sony LiveView readiness poll");
            }
        }
    }

    /** Tears down and rebuilds the whole USB session after a hard stream failure. */
    private void restartSession() throws IOException {
        Log.i(TAG, "Restarting USB/PTP session");
        synchronized (this) {
            if (connection != null) {
                connection.close();
                connection = null;
            }
        }
        ready = false;
        resetStreamState();
        transactionId = 0;
        openUsb();
        handshake();
        ready = true;
    }

    /** Drops any partially received PTP bytes so the next attempt starts clean. */
    private void resetStreamState() {
        incoming = new byte[0];
        incomingLength = 0;
    }

    /**
     * Asks the LiveView loop to stop at the next transaction boundary. The
     * camera always answers the command already in flight, so the loop exits
     * cleanly without leaving a stuck bulk read.
     */
    public void stopLiveView() {
        liveViewEnabled = false;
    }

    /**
     * Enables skipping GetObjectInfo after the first successful frame.
     * Call before {@link #requestLiveView()}.
     */
    public void setSkipObjectInfo(boolean enabled) {
        skipObjectInfo = enabled;
    }

    private void openUsb() throws IOException {
        Log.i(TAG, "Opening USB device name=" + device.getDeviceName() +
                " vid=0x" + Integer.toHexString(device.getVendorId()) +
                " pid=0x" + Integer.toHexString(device.getProductId()) +
                " interfaces=" + device.getInterfaceCount());
        UsbInterface ptpInterface = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface candidate = device.getInterface(i);
            Log.d(TAG, "USB interface index=" + i + " id=" + candidate.getId() +
                    " class=" + candidate.getInterfaceClass() +
                    " subclass=" + candidate.getInterfaceSubclass() +
                    " protocol=" + candidate.getInterfaceProtocol() +
                    " endpoints=" + candidate.getEndpointCount());
            if (candidate.getInterfaceClass() == 6 || hasBulkEndpoints(candidate)) {
                ptpInterface = candidate;
                break;
            }
        }
        if (ptpInterface == null) throw new IOException("No PTP still-image USB interface found");

        connection = usbManager.openDevice(device);
        if (connection == null) throw new IOException("Android could not open the USB device");
        if (!connection.claimInterface(ptpInterface, true)) {
            throw new IOException("Could not claim the PTP USB interface");
        }

        for (int i = 0; i < ptpInterface.getEndpointCount(); i++) {
            UsbEndpoint endpoint = ptpInterface.getEndpoint(i);
            Log.d(TAG, "PTP endpoint index=" + i + " type=" + endpoint.getType() +
                    " direction=0x" + Integer.toHexString(endpoint.getDirection()) +
                    " address=" + endpointDescription(endpoint));
            if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) bulkIn = endpoint;
                else bulkOut = endpoint;
            } else if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_INT &&
                    endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                interruptIn = endpoint;
            }
        }
        if (bulkIn == null || bulkOut == null) throw new IOException("PTP bulk endpoints are missing");
        state("Claimed PTP USB interface " + ptpInterface.getId() +
                "; bulk IN=" + endpointDescription(bulkIn) +
                " OUT=" + endpointDescription(bulkOut));
        if (interruptIn != null) {
            state("PTP event endpoint detected: " + endpointDescription(interruptIn));
            startEventReader();
        }
    }

    /**
     * Drains Sony's interrupt event endpoint the same way Imaging Edge does.
     * The capture shows the host keeps a read pending on 0x83 from
     * OpenSession onward; once the SDIO connect completes the camera pushes a
     * 16-byte PTP Event container (0xC203, parameter 0xD21D) every few
     * hundred milliseconds. Events are informational here, but leaving the
     * endpoint unread can stall Sony cameras, so keep servicing it.
     */
    private void startEventReader() {
        Thread events = new Thread(() -> {
            // Bind to the connection that was current when this reader was
            // created, so a session restart closes the old reader and a new
            // reader starts for the new connection.
            final UsbDeviceConnection eventConnection = connection;
            byte[] buffer = new byte[64];
            int silentReads = 0;
            while (!closed && eventConnection != null) {
                try {
                    int count = eventConnection.bulkTransfer(interruptIn, buffer, buffer.length, 500);
                    if (count > 0) {
                        Log.d(TAG, "Sony PTP event (" + endpointDescription(interruptIn) +
                                "): " + hex(buffer, 0, count));
                        silentReads = 0;
                    } else if (++silentReads % 20 == 0) {
                        Log.d(TAG, "PTP event endpoint idle (no events pending)");
                    }
                } catch (Exception error) {
                    Log.d(TAG, "PTP event endpoint closed: " + error.getMessage());
                    return;
                }
            }
        }, "SonyLiveView-events");
        events.setDaemon(true);
        events.start();
    }

    private boolean hasBulkEndpoints(UsbInterface usbInterface) {
        boolean in = false;
        boolean out = false;
        for (int i = 0; i < usbInterface.getEndpointCount(); i++) {
            UsbEndpoint endpoint = usbInterface.getEndpoint(i);
            if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) in = true;
            if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT) out = true;
        }
        return in && out;
    }

    private synchronized void sendCommand(int code, int... params) throws IOException {
        int length = 12 + params.length * 4;
        ByteBuffer packet = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        packet.putInt(length);
        packet.put((byte) PTP_COMMAND);
        packet.put((byte) 0);
        packet.putShort((short) code);
        int currentTransaction = transactionId++;
        packet.putInt(currentTransaction);
        for (int param : params) packet.putInt(param);
        Log.i(TAG, String.format("PTP OUT command=0x%04X transaction=%d length=%d", code,
                currentTransaction, length));
        byte[] bytes = packet.array();
        int written = connection.bulkTransfer(bulkOut, bytes, bytes.length, USB_TRANSFER_TIMEOUT_MS);
        if (written != bytes.length) {
            throw new IOException("PTP command write failed: " + written +
                    " (OUT " + endpointDescription(bulkOut) + ")");
        }
        Log.i(TAG, String.format("PTP command 0x%04X sent", code));
    }

    private synchronized int sendDataCommand(int code, byte[] data, String operation,
            int... params) throws IOException {
        int commandLength = 12 + params.length * 4;
        ByteBuffer command = ByteBuffer.allocate(commandLength).order(ByteOrder.LITTLE_ENDIAN);
        command.putInt(commandLength);
        command.put((byte) PTP_COMMAND);
        command.put((byte) 0);
        command.putShort((short) code);
        int currentTransaction = transactionId++;
        command.putInt(currentTransaction);
        for (int param : params) command.putInt(param);
        Log.i(TAG, String.format("PTP OUT command=0x%04X transaction=%d length=%d",
                code, currentTransaction, commandLength));
        writeBulk(command.array(), "command");

        int dataLength = 12 + data.length;
        ByteBuffer dataPacket = ByteBuffer.allocate(dataLength).order(ByteOrder.LITTLE_ENDIAN);
        dataPacket.putInt(dataLength);
        dataPacket.put((byte) PTP_DATA);
        dataPacket.put((byte) 0);
        dataPacket.putShort((short) code);
        dataPacket.putInt(currentTransaction);
        dataPacket.put(data);
        Log.i(TAG, String.format("PTP OUT data operation=0x%04X transaction=%d length=%d",
                code, currentTransaction, dataLength));
        writeBulk(dataPacket.array(), "data");
        state(String.format("PTP data operation 0x%04X sent", code));
        return readUntilResponse(operation);
    }

    private void writeBulk(byte[] bytes, String phase) throws IOException {
        int written = connection.bulkTransfer(bulkOut, bytes, bytes.length, USB_TRANSFER_TIMEOUT_MS);
        if (written != bytes.length) {
            throw new IOException("PTP " + phase + " write failed: " + written +
                    " (OUT " + endpointDescription(bulkOut) +
                    "; the camera USB pipe is halted - power-cycle the camera (off and on) and reconnect)");
        }
    }

    private int readUntilResponse(String operation) throws IOException {
        while (!closed) {
            Container container = readContainer();
            jpegExtractor.accept(container.raw, listener);
            if (container.type == PTP_DATA) {
                logDataContainer(container);
                if (container.code == SONY_GET_ALL_EXT_DEVICE_INFO) {
                    parseProperties(container.raw);
                }
            }
            if (container.type == PTP_RESPONSE) {
                Log.i(TAG, String.format("PTP response operation=%s code=0x%04X transaction=%d",
                        operation, container.code, container.transaction));
                // Every stream frame produces several responses; only surface
                // them on screen outside streaming to avoid flooding the UI.
                if (!streaming) {
                    state(String.format("%s response: 0x%04X", operation, container.code));
                }
                return container.code;
            }
        }
        throw new IOException("No PTP response for " + operation);
    }

    private Container readContainer() throws IOException {
        ensureIncoming(12);
        ByteBuffer headerBuffer = ByteBuffer.wrap(incoming).order(ByteOrder.LITTLE_ENDIAN);
        int length = headerBuffer.getInt();
        int type = headerBuffer.get() & 0xff;
        headerBuffer.get();
        int code = headerBuffer.getShort() & 0xffff;
        int tx = headerBuffer.getInt();
        if (length < 12 || length > 8 * 1024 * 1024) {
            throw new IOException("Invalid PTP container length: " + length);
        }
        ensureIncoming(length);
        byte[] raw = new byte[length];
        System.arraycopy(incoming, 0, raw, 0, length);
        int leftover = incomingLength - length;
        byte[] rest = new byte[leftover];
        System.arraycopy(incoming, length, rest, 0, leftover);
        incoming = rest;
        incomingLength = leftover;
        if (!streaming || type != PTP_DATA || code != PTP_OC_GET_OBJECT) {
            Log.d(TAG, String.format("PTP IN container type=%d code=0x%04X transaction=%d length=%d",
                    type, code, tx, length));
        }
        return new Container(type, code, tx, raw);
    }

    /**
     * Makes sure at least {@code needed} bytes of the incoming PTP stream are
     * buffered, reading whole 512-aligned USB chunks. Unlike a 12-byte header
     * read, a chunk request never truncates an incoming 512-byte packet, so no
     * bytes are lost between containers.
     */
    private void ensureIncoming(int needed) throws IOException {
        int failures = 0;
        int timeoutMs = streaming ? STREAM_READ_TIMEOUT_MS : USB_TRANSFER_TIMEOUT_MS;
        while (incomingLength < needed && !closed) {
            int count = connection.bulkTransfer(bulkIn, readChunk, readChunk.length,
                    timeoutMs);
            if (count > 0) {
                byte[] grown = new byte[incomingLength + count];
                System.arraycopy(incoming, 0, grown, 0, incomingLength);
                System.arraycopy(readChunk, 0, grown, incomingLength, count);
                incoming = grown;
                incomingLength += count;
                failures = 0;
            } else if (count == 0) {
                // A zero-length packet is valid USB framing when a transfer
                // ends on a full 512-byte packet. Consume it and keep
                // reading; only give up after several zero-progress reads.
                failures++;
                if (failures >= USB_READ_RETRIES) {
                    throw new IOException("USB bulk read timed out (IN " +
                            endpointDescription(bulkIn) + ", after " + failures +
                            " attempts; camera may have left PC Remote mode)");
                }
            } else {
                failures++;
                Log.w(TAG, "USB bulk IN returned " + count + " on " +
                        endpointDescription(bulkIn) + " attempt=" + failures +
                        "/" + USB_READ_RETRIES);
                if (failures >= USB_READ_RETRIES) {
                    throw new IOException("USB bulk read failed: " + count +
                            " (IN " + endpointDescription(bulkIn) +
                            ", after " + failures + " attempts; camera may have left PC Remote mode, or its USB pipe is " +
                            "halted - power-cycle the camera (off and on) and reconnect)");
                }
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("USB read interrupted", interrupted);
                }
            }
        }
    }

    private void logDataContainer(Container container) {
        int payloadLength = container.raw.length - 12;
        if (payloadLength <= 128) {
            Log.d(TAG, String.format("PTP data operation=0x%04X transaction=%d length=%d payload=%s",
                    container.code, container.transaction, container.raw.length,
                    hex(container.raw, 12, payloadLength)));
            return;
        }
        int marker = findLittleEndianWord(container.raw, 0xD221);
        Log.i(TAG, String.format("PTP data operation=0x%04X transaction=%d length=%d D221_offset=%d",
                container.code, container.transaction, container.raw.length, marker));
        if (marker >= 0) {
            int dumpStart = Math.max(12, marker - 8);
            int dumpLength = Math.min(48, container.raw.length - dumpStart);
            Log.d(TAG, "D221 dataset bytes: " + hex(container.raw, dumpStart, dumpLength));
        }
    }

    /**
     * Parses the Sony device-property block inside a 0x9209 data container
     * (the block is the container payload after the 12-byte PTP header). The
     * block starts with a u64 record count, then one record per property;
     * the layout matches alpha-fairy's decoder and the Imaging Edge capture:
     *
     *     u16 code, u16 datatype, u8 get/set, u8 sony, u8 factory default,
     *     <current value>, u8 form, form data (0x01 range: 3*dsz bytes,
     *     0x02 enum: u16 count + count*dsz bytes, 0xFF/0x00: none)
     *
     * e.g. the shutter record 0D D2 06 00 00 02 05 00 01 00 ... -> current
     * value 0x00010005 = 1/5 s, and the battery record 18 D2 01 00 00 02
     * FF 0E 01 FF -> value 0x0E = 14%. Datatype sizes follow PTP (odd =
     * signed): 1/2 = 1 byte, 3/4 = 2, 5/6 = 4, 7/8 = 8, 9/10 = 16, and
     * 0xFFFF = UTF-16 string. All three exposure fields are 0 until the
     * camera starts live view, so the UI only shows the line once they
     * carry real values.
     */
    private void parseProperties(byte[] container) {
        if (container.length < 20) return;
        long count = u64(container, 12);
        int i = 20;
        Integer battery = null;
        Integer shutter = null;
        Integer aperture = null;
        Integer iso = null;
        for (int record = 0; record < count && i + 10 <= container.length; record++) {
            int code = u16(container, i);
            int datatype = u16(container, i + 2);
            i += 4;
            if (datatype == 0x0000 || code == 0x0000) {
                i += 4; // opaque filler record; alpha-fairy skips it the same way
                continue;
            }
            i += 2; // get/set visibility byte + Sony byte
            int dataSize = dataSize(datatype);
            if (datatype == 0xFFFF) {
                // UTF-16 string: u8 element count, then count * 2 bytes.
                if (i >= container.length) break;
                int elements = container[i++] & 0xff;
                i += elements * 2;
            } else if (dataSize > 0) {
                if (i + dataSize > container.length) break;
                i += dataSize; // factory default
                if (i + dataSize > container.length) break;
                long value = readValue(container, i, dataSize, datatype);
                i += dataSize;
                switch (code) {
                    case SONY_BATTERY_LEVEL:
                        if (value >= 0 && value <= 100) battery = (int) value;
                        break;
                    case SONY_SHUTTER_SPEED:
                        shutter = (int) value;
                        break;
                    case SONY_FNUMBER:
                        aperture = (int) value;
                        break;
                    case SONY_ISO:
                        iso = (int) value;
                        break;
                    default:
                        break;
                }
            } else {
                break; // unknown datatype: cannot walk further safely
            }
            if (i >= container.length) break;
            int form = container[i++] & 0xff;
            if (form == 0x01) {
                i += 3 * dataSize; // range: min, max, step
            } else if (form == 0x02) {
                if (i + 2 > container.length) break;
                int elements = u16(container, i);
                i += 2 + elements * dataSize; // enumeration
            }
            if (form != 0x01 && form != 0x02 && form != 0xFF && form != 0x00) {
                break; // unknown form: cannot walk further safely
            }
        }
        if (battery != null && battery != lastBatteryPercent) {
            lastBatteryPercent = battery;
            Log.i(TAG, "Sony battery level: " + battery + "% (property 0xD218)");
            listener.onBattery(battery);
        }
        String exposure = formatExposure(shutter, aperture, iso);
        if (exposure != null && !exposure.equals(lastExposureLabel)) {
            lastExposureLabel = exposure;
            Log.i(TAG, "Sony exposure: " + exposure);
            listener.onExposure(exposure);
        }
    }

    /**
     * Builds the exposure line from the raw property values. ShutterSpeed
     * is seconds as (numerator<<16)|denominator (alpha-fairy's disabled
     * cmd_ShutterSpeedSet sends int16[] {denominator, numerator}), so
     * 0x00010005 -> 1/5 s and 0x00020001 -> 2 s. FNumber is f-number *
     * 100 (400 -> f/4.0). ISO is literal with the 0xFFFFFF AUTO sentinel
     * (the capture's ISO enum list shows AUTO, AUTO 1/2 and AUTO 1/3 all
     * share that masked value). Returns null only while every field is
     * still unset/zero (before live view starts); otherwise a line with
     * whichever fields already carry real values.
     */
    private String formatExposure(Integer shutter, Integer aperture, Integer iso) {
        String shutterText = null;
        if (shutter != null && shutter != 0 && shutter != 0xFFFFFFFF) {
            int numerator = (shutter >>> 16) & 0xFFFF;
            int denominator = shutter & 0xFFFF;
            if (numerator > 0 && denominator > 0) {
                if (denominator == 1) shutterText = numerator + "s";
                else if (numerator == 1) shutterText = "1/" + denominator + "s";
                else shutterText = numerator + "/" + denominator + "s";
            }
        }
        String apertureText = null;
        if (aperture != null && aperture > 0) {
            apertureText = String.format(Locale.US, "f/%.1f", aperture / 100.0);
        }
        String isoText = null;
        if (iso != null && iso != 0) {
            isoText = (iso & 0xFFFFFF) == 0xFFFFFF ? "ISO AUTO" : "ISO " + (iso & 0xFFFFFF);
        }
        if (shutterText == null && apertureText == null && isoText == null) return null;
        StringBuilder label = new StringBuilder();
        if (shutterText != null) label.append(shutterText);
        if (apertureText != null) {
            if (label.length() > 0) label.append(" · ");
            label.append(apertureText);
        }
        if (isoText != null) {
            if (label.length() > 0) label.append(" · ");
            label.append(isoText);
        }
        return label.toString();
    }

    /** PTP data type width in bytes: 1/2 = 1, 3/4 = 2, 5/6 = 4, 7/8 = 8, 9/10 = 16. */
    private static int dataSize(int datatype) {
        switch (datatype & 0x0F) {
            case 1: case 2: return 1;
            case 3: case 4: return 2;
            case 5: case 6: return 4;
            case 7: case 8: return 8;
            case 9: case 10: return 16;
            default: return 0;
        }
    }

    /** Reads a little-endian value, sign-extended for PTP's odd (signed) types. */
    private static long readValue(byte[] bytes, int offset, int size, int datatype) {
        long value = 0;
        for (int k = size - 1; k >= 0; k--) {
            value = (value << 8) | (bytes[offset + k] & 0xff);
        }
        if ((datatype & 1) == 1) {
            int bits = size * 8;
            if (bits < 64 && (value & (1L << (bits - 1))) != 0) {
                value |= -1L << bits; // sign extend
            }
        }
        return value;
    }

    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static long u64(byte[] bytes, int offset) {
        long value = 0;
        for (int k = 7; k >= 0; k--) {
            value = (value << 8) | (bytes[offset + k] & 0xff);
        }
        return value;
    }

    private int findLittleEndianWord(byte[] bytes, int value) {
        int low = value & 0xff;
        int high = (value >>> 8) & 0xff;
        for (int i = 12; i + 1 < bytes.length; i++) {
            if ((bytes[i] & 0xff) == low && (bytes[i + 1] & 0xff) == high) {
                return i;
            }
        }
        return -1;
    }

    private String hex(byte[] bytes, int offset, int length) {
        StringBuilder result = new StringBuilder(length * 3);
        int end = Math.min(bytes.length, offset + length);
        for (int i = offset; i < end; i++) {
            if (result.length() > 0) result.append(' ');
            result.append(String.format("%02X", bytes[i] & 0xff));
        }
        return result.toString();
    }

    private void state(String message) {
        Log.i(TAG, message);
        listener.onState(message);
    }

    private String endpointDescription(UsbEndpoint endpoint) {
        if (endpoint == null) return "none";
        return String.format("0x%02X maxPacket=%d", endpoint.getAddress(), endpoint.getMaxPacketSize());
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        ready = false;
        executor.shutdownNow();
        closeInternal();
        listener.onClosed();
    }

    private synchronized void closeInternal() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    private static final class Container {
        final int type;
        final int code;
        final int transaction;
        final byte[] raw;

        Container(int type, int code, int transaction, byte[] raw) {
            this.type = type;
            this.code = code;
            this.transaction = transaction;
            this.raw = raw;
        }
    }

    private static final class JpegExtractor {
        private final ByteArrayOutputStream current = new ByteArrayOutputStream();
        private boolean inJpeg;
        private int previous = -1;

        void accept(byte[] bytes, Listener listener) {
            for (byte value : bytes) {
                int currentByte = value & 0xff;
                if (!inJpeg) {
                    if (previous == 0xff && currentByte == 0xd8) {
                        current.reset();
                        current.write(0xff);
                        current.write(0xd8);
                        inJpeg = true;
                    }
                } else {
                    current.write(currentByte);
                    if (previous == 0xff && currentByte == 0xd9) {
                        byte[] jpeg = current.toByteArray();
                        if (jpeg.length > 256) listener.onFrame(jpeg);
                        current.reset();
                        inJpeg = false;
                    } else if (current.size() > 8 * 1024 * 1024) {
                        current.reset();
                        inJpeg = false;
                    }
                }
                previous = currentByte;
            }
        }
    }
}
