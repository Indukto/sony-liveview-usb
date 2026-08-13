# Sony LiveView USB

A small native Android prototype for Sony a6300 USB/PTP LiveView.

## Camera setup

1. Set the camera USB Connection to **PC Remote** (not MTP/Mass Storage).
2. Turn the camera off and on after changing that setting, then connect it with a data-capable USB OTG cable.The a6300 reports VID `054c` / PID `079c` in PC Remote mode. The app accepts this Sony PTP interface and logs the endpoint and command exchange for troubleshooting.


3. Grant USB access to the app.
4. Press **Connect**, then **Start LiveView**.

The app opens the Sony PTP USB interface, performs the a6300 sequence confirmed from the supplied Sony Imaging Edge USBPcap capture (`GetStorageIDs`, Sony SDIO negotiation, and the second `0x9202` refresh), polls `0x9209`, then requests Sony's virtual LiveView object (`0xFFFFC002`) with standard `GetObjectInfo`/`GetObject`. The `GetObject` data container can span multiple USB bulk transfers and contains the JPEG preview bytes, which the app extracts for display.

Build from this folder with the Android Studio Gradle wrapper or import the folder into Android Studio.

## Logcat diagnostics

The app writes connection details under the `SonyLiveView` tag, including USB interfaces, endpoint addresses, PTP commands, responses, read failures, and JPEG frame progress. Filter Android logs with:

```bash
adb logcat -v time -s SonyLiveView:D *:S
```

To capture the connection attempt to a file:

```bash
adb logcat -c
adb logcat -v time -s SonyLiveView:D *:S > sony-liveview.log
```

Stop the second command after reproducing the problem. The most useful lines are the first `PTP OUT command`, each `PTP response`, and any `USB bulk IN returned` or `USB/PTP connection failed` entry.

## Protocol references and current plan

Useful independent references:

- [Sony Camera Remote Command](https://support.d-imaging.sony.co.jp/app/cameraremotecommand/en/index.html) — official PTP command reference. The current supported-device list does not include the ILCE-6300, so its commands must be validated on hardware.
- [pysonycam](https://github.com/olkham/pysonycam) — Python SDIO/PTP implementation with LiveView, mode preparation, virtual-object polling, and v2/v3 notes. Its v2 path is not hardware-certified for the a6300.
- [expo-sony-camera protocol findings](https://github.com/jongan69/expo-sony-camera/blob/main/docs/PROTOCOL_FINDINGS.md) — documents the PTP2 LiveView model: poll `D221`, then use virtual object `0xFFFFC002` with `GetObjectInfo` and `GetObject`.
- [alpha-fairy reverse-engineering notes](https://github.com/frank26080115/alpha-fairy/blob/main/doc/Camera-Reverse-Engineering.md) — explains why Sony properties and packet captures vary by model and firmware.
- [gPhoto a6300 timeout report](https://sourceforge.net/p/gphoto/bugs/1038/) — documents PC Remote PTP timeouts on the a6300 even though Sony Imaging Edge works.

The supplied capture has now replaced the earlier speculative `0x9251`/D221 plan. Its confirmed PTP sequence is:

1. `OpenSession` transaction 0.
2. `GetDeviceInfo` transaction 1.
3. `GetStorageIDs` (`0x1004`) transaction 2; the camera returns storage ID `0x00010000` in a data container.
4. `SDIO_Connect` (`0x9201`) phases 1 and 2.
5. `SDIO_GetExtDeviceInfo` (`0x9202`, parameter `0xC8`).
6. `SDIO_Connect` phase 3.
7. A second `SDIO_GetExtDeviceInfo` refresh (`0x9202`, parameter `0xC8`).
8. Ten `0x9209` readiness polls. Each poll has a **data phase**: the camera
   answers with a 1252-byte D221 status container that spans three USB bulk
   transfers (`512 + 512 + 228`) followed by response `0x2001`. Wireshark
   labels the container as `0x9209`; the multi-transfer tail is what the
   parser's stray `0x0200`/`0x01ffffff` rows are (tshark re-parsing the
   middle of the D221 payload as a new container).
9. Repeated `GetObjectInfo(0xFFFFC002)` followed by `GetObject(0xFFFFC002)`.
10. The `GetObject` data container is multi-transfer and contains JPEG bytes beginning with `FF D8`; the capture contains 99 JPEG bulk transfers.

The capture uses bulk OUT `0x02`, bulk IN `0x81`, and interrupt IN `0x83`. It does not contain the previously added `0x9205` priority or `0x5013` standby transactions, so the Android prototype now follows the observed Imaging Edge order instead of sending those unconfirmed commands. The host keeps a read pending on interrupt IN `0x83` from OpenSession onward; once the SDIO connect completes, the camera pushes a 16-byte PTP Event container (`0xC203`, parameter `0xD21D`) every few hundred milliseconds, so the app drains that endpoint too.

## Why the first `0x9209` read used to fail on Android

The original code read the 12-byte PTP header as its own USB bulk transfer and
only then read the body. A 12-byte bulk IN request against the camera's
incoming 512-byte packet overflows the transfer (libusb: "you will never see
an overflow if your transfer buffer size is a multiple of the endpoint's
packet size"), the rest of that packet is lost, and the stream desynchronizes.
The failure shows up exactly at `0x9209` because that is the first response
large enough to span multiple USB packets (`0x9201`/`0x9202` containers fit in
one). The fix is the same one expo-sony-camera documents for this symptom:
read complete 512-aligned USB packets, buffer the remainder, and parse
containers out of that buffer instead of issuing short header reads.

## Monitor+ binary findings

The extracted Monitor+ AOT binary contains a different, more complete LiveView path than this prototype. These identifiers and log messages are directly present in `lib/arm64-v8a/libapp.so`:

- `PtpInitializer` performs USB DID and initial `GetDeviceInfo` setup.
- It can auto-upgrade function mode from `RC` to `RCWT`, then downgrade to `RC` when the camera returns `Parameter_Not_Supported`.
- `LiveViewPTP` tries `SetLiveViewEnable(Down=Enable)` but continues when that operation fails.
- The main adaptive USB stream path calls `SDIO_Start`, retries `SDIO Start NG_Invalid_Args (detailResult=6)`, then uses `SDIO_ControlMonitoring`.
- The stream path handles `monitoringStart`, `monitoringMeta`, `monitoringSetDecodeEnable`, `monitoringStop`, and `SDIE_MonitoringEvent`.
- Monitoring metadata includes `MonitoringBinaryVersion`, `MonitoringTransportProtocol`, `MonitoringCipherType`, `MonitoringCipherKey`, `MonitoringCipherIV`, `MonitoringDeliveryID`, `MonitoringDeliveringStatus`, and supported verification formats.
- The receiver parses live-view data and reports `STREAMING frame received`; it can fall back to HTTP using `SetLiveViewEnable` and `LiveViewUrl` when SDIO streaming fails.

This is confirmed evidence that Monitor+ has a separate adaptive stream layer, but the supplied Imaging Edge capture proves that the a6300 also exposes a working PTP2 virtual-object path. The Android prototype now prioritizes the directly captured Imaging Edge sequence. The Monitor+ `SDIO_Start`/`SDIO_ControlMonitoring` path remains a separate fallback if the captured virtual-object path does not produce frames on Android.
