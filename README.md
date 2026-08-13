# Sony LiveView USB

A small native Android prototype for Sony a6300 USB/PTP LiveView.

## Camera setup

1. Set the camera USB Connection to **PC Remote** (not MTP/Mass Storage).
2. Turn the camera off and on after changing that setting, then connect it with a data-capable USB OTG cable.The a6300 reports VID `054c` / PID `079c` in PC Remote mode. The app accepts this Sony PTP interface and logs the endpoint and command exchange for troubleshooting.


3. Grant USB access to the app.
4. Press **Connect**. Once the PTP session is ready, LiveView starts
   automatically and a full-screen video page takes over; press **Stop**
   (or the back key) to return to the connection page with its live log.

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

### Battery level and exposure (`0x9209` property block)

Every `0x9209` data container carries the full Sony device-property block, and it includes property **`0xD218` (Battery Level, INT8 percent)**. The capture was checked: all **110** `0x9209` responses contain `18 D2 01 00 00 02 FF 0E 01 FF` → current value `0x0E` = **14%**, over the 9.44 s polling span. The app walks the whole block (u64 record count + one record per property, in alpha-fairy's layout) and shows `BATTERY n%` on the connection page and the video overlay; the listener fires only when the value changes. No extra transaction is needed — the readiness polls already fetch it.

The same block carries the live exposure settings, which the video overlay shows as a line like `1/5s · f/4.0 · ISO AUTO`:

| Property | Code | Type | Encoding (from the capture) |
| -------- | ---- | ---- | --------------------------- |
| Shutter Speed | `0xD20D` | UINT32 | seconds as `(numerator<<16)\|denominator` → `0x00010005` = **1/5 s** |
| F-Number | `0x5007` | UINT16 | f-number × 100 → `400` = **f/4.0** |
| ISO | `0xD21E` | UINT32 | literal; `0xFFFFFF` = **AUTO** |
| Exposure Comp | `0x5010` | INT16 | EV × 1000 → `0` = +0.0 EV (logged only) |

Value encodings were confirmed against the camera's own property enum list in the capture (ISO lists 25…409600 plus the `0xFFFFFF` AUTO sentinel) and against alpha-fairy, which sends shutter as `int16[] {denominator, numerator}` (i.e. low word = denominator, high word = numerator) and renders aperture as `value/100`. All three exposure fields read 0 until live view starts, so the overlay line stays hidden until the camera reports real values; in the capture they were constant while Imaging Edge streamed.

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

## Performance notes

Measured from the capture (SOF0 marker `FF C0 00 11 08 02 A8 04 00`):

- The a6300 delivers **1024×680 baseline JPEG** frames, 105–132 KB each, as the
  `GetObject(0xFFFFC002)` data container. The app renders them losslessly
  (`FIT_CENTER`, no `inSampleSize`), so delivered resolution equals displayed
  resolution; there is no app-side size limit.
- Imaging Edge paces roughly 100 ms per frame cycle (~10 fps) with host-side
  sleeps: ~25–30 ms between the `0x9209` response and `GetObjectInfo`, ~15 ms
  before `GetObject`, ~60 ms before the next poll. The USB transfers themselves
  only take 2–8 ms each.
- The prototype therefore removed the 50 ms per-frame sleep that capped the
  loop at 20 fps, moved JPEG decode off the camera's single read thread (a
  decode can take 10–30 ms and previously stalled the transaction loop), and
  enlarged the bulk read chunk to 256 KB so the largest captured frame
  container arrives in one read. The camera governs the real frame rate; the
  `SKIP_INTERFRAME_STATUS_POLL` flag in `PtpUsbCamera` is an experiment lever
  that removes one third of the per-frame round trips if the camera tolerates
  it.

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

## Reaching 30 fps (SDIO monitoring stream)

The PTP2 virtual-object pull loop is camera-paced and typically lands in the
10–15 fps range on the a6300; the supplied Imaging Edge capture itself only
achieves ~10 fps. Monitor+'s 30 fps comes from Sony's newer SDIO **monitoring
push stream**, confirmed by strings in `lib/arm64-v8a/libapp.so`:

- `SDIO_Start` returns a `deliveryId` (`START OK deliveryId=`, `START rejected
  deliveryId=`), then `SDIO_ControlMonitoring` runs the stream and the camera
  pushes `SDIE_MonitoringEvent` containers.
- The monitoring metadata block carries `Monitoring Binary Version`,
  `Monitoring Transport Protocol`, `Monitoring Cipher Type / Key / IV`,
  `Monitoring Delivery ID`, `Monitoring Delivering Status`, and supported
  streaming formats; the cipher enum includes `AES_128_CBC/CTR` through
  `AES_256_CBC/CTR`, and codecs `H264`, `MJPEG`, `JPEG`, `Mono` with frame
  rates up to `FrameRate_30_00` and resolutions up to 4K.
- On failure the app logs `), switching to STREAMING`, retries the start, and
  can fall back to HTTP (`SetLiveViewEnable`, `LiveViewUrl`).

The monitoring opcodes (`SDIO_Start`, `SDIO_ControlMonitoring`, the monitoring
metadata read) are **not published** in the public Camera Remote Command SDK or
any open-source project as of now, so replicating the 30 fps path requires
capturing Monitor+'s own USB traffic on the a6300 (USBPcap) and matching the
opcodes/parameters to this app. Until then the prototype keeps the certified
PTP2 path and adds experiment levers:

- **Skip GetObjectInfo** (checkbox, off by default): sends `GetObjectInfo`
  only once, then `GetObject` alone while it keeps returning `0x2001` (one
  fewer PTP round trip per frame). If a `GetObject` fails, `GetObjectInfo` is
  re-issued to re-sync. This is the first experiment worth running.
The video page is laid out as a filming stage: the live view runs edge-to-edge
in immersive fullscreen (system bars hidden; swipe to reveal), with a single
translucent readout panel in the top-right corner (FPS/dup/interval, exposure,
battery, frame info) and a compact tool row along the bottom-right:

- **Focus peaking**: a **Peak: Off → Red → Yellow → White** cycle button
  tints in-focus edges Sony-style. Computed on the decoder thread and never
  writes to the camera, so it cannot trigger the a6300's pipe stall. The
  active color lights up the button's text and border.
- **Zebra exposure aid**: a **Zebra: Off/100%** toggle overlays classic
  diagonal black/white stripes on every pixel at or above JPEG white
  (luma ≥ 254). Display-side only, same as peaking.
- **Thirds grid**: a **Grid: Off/Thirds** toggle draws rule-of-thirds lines
  plus a small center reticle, fitted to the actual image rectangle (the
  preview is `FIT_CENTER`, so the lines follow the frame, not the screen's
  letterbox bars). Purely a drawing overlay.
- **Anamorphic desqueeze**: a **DeSq: Off/1.33x/1.5x/2x** toggle stretches the
  decoded frame horizontally by the squeeze factor, so footage shot through an
  anamorphic lens displays un-squeezed. Display-side only (a `Matrix` scale on
  the decoder thread, applied after peaking/zebra); the grid and the readout
  panel track the widened image rectangle. The live view itself stays squeezed
  until you toggle it on.
- **Cinescope mask**: a **Scope: Off/2.39/1.85** toggle darkens everything
  outside the chosen cinematic frame and outlines it with a thin white border,
  like the aspect-ratio masks on Atomos/SmallHD monitors. The frame is the
  largest scope-aspect rectangle inside the fitted (and desqueezed) image, so
  it hugs the picture, not the screen letterbox bars. Display-side only.
- **Camera recording detection**: the camera's own movie recording state is
  read from the Sony `0xD21D` property (Movie Recording State) in the same
  `0x9209` block as the battery - the capture shows it present in every
  container (`0` = stopped). Start or stop movie recording on the camera and
  the stage reacts within one poll cycle (~0.1 s): a red frame borders the
  whole screen and a **● REC** label appears top-center while recording. No
  app-side record button - recording always starts/stops on the camera.
  Note: on the a6300, movie recording may be disabled while the USB LUN is set
  to PC Remote; if the camera cannot start recording, the property stays `0`
  and the frame stays off.
- **Live RGB histogram**: a **Hist: Off/On** toggle shows an Imaging
  Edge-style R/G/B histogram at the bottom-left, generated in-app from a
  128px-wide sample of the displayed frame (the camera sends no histogram
  data on the PTP live view path; Imaging Edge computes its overlay
  client-side the same way). The histogram is sampled before peaking so the
  tint never skews it, and it costs under ~0.1 ms per frame on the decoder
  thread.
- **Exposure readout**: the shutter speed, f-number and ISO decoded from
  the same `0x9209` property block as the battery (see the table above), e.g.
  `1/5s · f/4.0 · ISO AUTO`. It updates live as the exposure changes and is
  logged to the status panel once per change.
- **Frame diagnostics**: the readout panel shows received fps, consecutive
  duplicates and the average frame interval (`FPS 11 · dup 0 · 91ms`) plus
  the decoded resolution and frame counter. Stale identical JPEGs are not
  decoded or redrawn. If `dup` stays high, the camera is serving frames
  slower than the loop runs; if the interval stays ~85–90 ms with the
  checkbox on, that is the camera's own frame-generation floor and only the
  SDIO stream will raise it.

**Do not send `0x9205` (SetExtDevicePropValue) to the a6300.** A property
write there — even a documented property such as LiveView mode `0xD26A` — can
STALL the camera's bulk OUT pipe at the USB level (bulk transfer returns -1 on
OUT `0x02`), halting every later transaction. Android's public USB API cannot
clear a halted endpoint, so recovery requires power-cycling the camera.

For the cleanest measurement, keep the camera on JPEG-only (no RAW+JPEG),
manual focus/exposure, no DRO/HDR, no face/eye detection, no image review and
no picture effects, and compare the interval readout with the checkbox on/off.

## Automatic recovery

Transient live-view errors no longer require closing the app or power-cycling
the camera:

- **Short streaming timeouts**: during live view, a read that yields no data
  for 3 s (instead of 15 s) is treated as a broken frame and triggers
  recovery, so a camera hiccup surfaces in seconds, not a 45 s freeze.
- **Tiered retries**: the first retry re-synchronizes the stream on the same
  session; later retries tear down and rebuild the whole USB session
  (re-open, re-claim, full handshake). Up to three retries run with backoff
  before the app gives up.
- **Recovery is visible**: the video page shows an amber `RECOVERING:` overlay
  while retries run, and the stream stays up.
- **Clean failure**: if the camera cannot be recovered (e.g. a genuinely
  halted pipe), the app closes the session, returns to the connection page
  with the Connect button enabled, and prints the power-cycle hint — the app
  itself never needs to be killed. Power-cycling the camera is only required
  for hardware-level pipe halts.
- **Calmer per-frame logging**: per-frame responses are logged to logcat but
  no longer pushed to the UI log during streaming, removing UI-thread load
  that previously contributed to stutter.

Note that the a6300's live-view frame rate itself varies with scene and
exposure (a long exposure in a dark scene drops the frame rate), which is
camera-side and independent of these host-side changes.
