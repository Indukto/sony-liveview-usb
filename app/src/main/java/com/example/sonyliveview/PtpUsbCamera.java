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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal Sony PTP/USB prototype. The camera must be in PC Remote mode. */
public final class PtpUsbCamera implements AutoCloseable {
    private static final String TAG = "SonyLiveView";
    public interface Listener {
        void onState(String message);
        void onReady();
        void onFrame(byte[] jpeg);
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
    private static final int USB_READ_RETRIES = 3;
    // Bulk IN reads must use buffers that are a multiple of the endpoint's
    // max packet size (512). A short header read (e.g. exactly 12 bytes)
    // against an incoming 512-byte packet causes a USB overflow on Android
    // and the rest of that packet is lost, which desynchronizes the PTP
    // stream. The capture shows Sony responses arrive as one USB transfer
    // made of several 512-byte packets plus a short packet (e.g. the
    // 0x9209 D221 container is 512 + 512 + 228 bytes), so always read a
    // large 512-aligned chunk and parse containers out of the buffered
    // stream, keeping the remainder for the next container.
    private static final int USB_READ_CHUNK = 64 * 1024;

    // Sony PTP extension commands found in the Monitor+ binary and public PTP traces.
    private static final int SONY_SDIO_CONNECT = 0x9201;
    private static final int SONY_SDIO_GET_EXT_DEVICE_INFO = 0x9202;
    private static final int SONY_SET_CONTROL_DEVICE_A = 0x9205;
    private static final int SONY_GET_ALL_EXT_DEVICE_INFO = 0x9209;
    private static final int SONY_PRIORITY_MODE = 0xD25A;
    private static final int SONY_OPERATING_MODE = 0x5013;
    private static final int SONY_STANDBY_MODE = 0x00000001;
    private static final int SONY_LIVE_VIEW_STATUS = 0xD221;
    private static final int SONY_LIVE_VIEW_OBJECT = 0xFFFFC002;

    private final UsbManager usbManager;
    private final UsbDevice device;
    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final JpegExtractor jpegExtractor = new JpegExtractor();

    private volatile boolean closed;
    private volatile boolean ready;
    private volatile boolean streaming;
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
            try {
                if (closed || !ready) {
                    state("LiveView request ignored: PTP session is not ready.");
                    return;
                }
                if (streaming) {
                    state("LiveView request ignored: a LiveView request is already active.");
                    return;
                }
                streaming = true;
                // The a6300/Imaging Edge trace uses 0x9209 as a short polling
                // operation. It has a data phase: the camera answers with a
                // 1252-byte D221 status container that spans three USB bulk
                // transfers (512 + 512 + 228) followed by response 0x2001.
                // readUntilResponse consumes the data container and returns
                // on the response. Imaging Edge polls ten times before the
                // first virtual-object request.
                for (int poll = 0; poll < 10 && !closed; poll++) {
                    sendCommand(SONY_GET_ALL_EXT_DEVICE_INFO);
                    state("Sony LiveView readiness poll " + (poll + 1) + "/10 sent...");
                    readUntilResponse("Sony LiveView readiness poll");
                    Thread.sleep(50L);
                }
                state("Sony LiveView readiness polls complete; requesting virtual JPEG object.");
                while (!closed) {
                    // Sony PTP2 exposes LiveView as a virtual object rather
                    // than a UVC stream. GetObjectInfo returns the object
                    // metadata, then GetObject carries the JPEG-containing
                    // data container across multiple USB bulk transfers.
                    sendCommand(PTP_OC_GET_OBJECT_INFO, SONY_LIVE_VIEW_OBJECT);
                    readUntilResponse("LiveView GetObjectInfo");
                    sendCommand(PTP_OC_GET_OBJECT, SONY_LIVE_VIEW_OBJECT);
                    readUntilResponse("LiveView GetObject");
                    sendCommand(SONY_GET_ALL_EXT_DEVICE_INFO);
                    readUntilResponse("Sony LiveView readiness poll");
                    Thread.sleep(50L);
                }
            } catch (Exception error) {
                Log.e(TAG, "LiveView request failed", error);
                state("LiveView request failed: " + error.getMessage());
            } finally {
                streaming = false;
            }
        });
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
            byte[] buffer = new byte[64];
            int silentReads = 0;
            while (!closed && connection != null) {
                try {
                    int count = connection.bulkTransfer(interruptIn, buffer, buffer.length, 500);
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
        state(String.format("PTP command 0x%04X sent", code));
    }

    private synchronized void sendDataCommand(int code, byte[] data, String operation,
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
        readUntilResponse(operation);
    }

    private void writeBulk(byte[] bytes, String phase) throws IOException {
        int written = connection.bulkTransfer(bulkOut, bytes, bytes.length, USB_TRANSFER_TIMEOUT_MS);
        if (written != bytes.length) {
            throw new IOException("PTP " + phase + " write failed: " + written +
                    " (OUT " + endpointDescription(bulkOut) + ")");
        }
    }

    private int readUntilResponse(String operation) throws IOException {
        while (!closed) {
            Container container = readContainer();
            jpegExtractor.accept(container.raw, listener);
            if (container.type == PTP_DATA) {
                logDataContainer(container);
            }
            if (container.type == PTP_RESPONSE) {
                Log.i(TAG, String.format("PTP response operation=%s code=0x%04X transaction=%d",
                        operation, container.code, container.transaction));
                state(String.format("%s response: 0x%04X", operation, container.code));
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
        while (incomingLength < needed && !closed) {
            int count = connection.bulkTransfer(bulkIn, readChunk, readChunk.length,
                    USB_TRANSFER_TIMEOUT_MS);
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
                            ", after " + failures + " attempts; camera may have left PC Remote mode)");
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
