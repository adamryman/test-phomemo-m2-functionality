# Build "Snap & Print": an Android camera app that instantly prints to a Phomemo M02

Build a native Android app (Kotlin) and produce an installable APK. The tools (JDK, Android SDK, Gradle) are already installed. Work until `./gradlew assembleDebug` succeeds, then tell me the path to the APK and the `adb install` command.

A working web version of this app exists (`index.html`, attached if available). Use it as the reference for behavior and the image/printing code. The Android app should do the same thing, but with the camera built in so it works without hosting a website.

## What the app does

- It opens straight to a full-screen live camera viewfinder (CameraX, back camera).
- **One big shutter button. Tapping it takes the photo and immediately prints it.** There's no preview, no confirm step, and no editing. If the photo is bad, that's fine.
- Also map the **volume buttons** to the shutter.
- After the shutter: a short white flash, a short vibration, and a small thumbnail of the black-and-white print in the corner. This is feedback only, not a preview gate.
- Prints go into a queue. If I snap 3 photos quickly, all 3 print in order. Show a small badge with the number waiting.
- A status pill at the top shows: not connected / connecting / ready / printing (with progress) / error. Tapping it reconnects or picks the printer.
- First launch: ask for the permissions, scan for the printer, and let me pick it. Remember it, and reconnect automatically on later launches, and again before each print if it has disconnected.
- A gallery button prints an existing photo (system photo picker), using the same pipeline.
- Settings screen (saved in SharedPreferences): brightness (-50..50, default 10), contrast (-50..50, default 15), dither style (Atkinson default / Floyd–Steinberg / plain threshold), rotate wide photos to fill the paper (default on), paper feed after print (default 60), packet size (default 128), delay between packets in ms (default 20), a "Test print" button, "Forget printer", and "Clear queue".
- Optionally save each photo (in color) to the gallery. Make this a setting, off by default.
- Keep the screen on while the app is open. Release the camera when the app is backgrounded.
- Dark UI, minimal, portrait-only.

## Printer facts (already confirmed with my printer)

- The printer is a **Phomemo M02**. It advertises over Bluetooth Low Energy with the name **`Mr.in_M02`**. The web version already printed to it successfully over BLE (Web Bluetooth) using the protocol below, so BLE works.
- Scan for BLE devices whose name starts with `Mr.in`, `M02`, `M0`, `T0`, or `Phomemo`. Also offer a "show all devices" option.
- After connecting, discover services. Use service **`0000ff00-0000-1000-8000-00805f9b34fb`** and write characteristic **`0000ff02-0000-1000-8000-00805f9b34fb`**. Fall back to the first characteristic in any service that supports write or write-without-response. Log every service and characteristic UUID to Logcat so I can debug.
- Request a larger MTU (`requestMtu(512)`) after connecting. Send the data in chunks of (MTU − 3), capped at the packet-size setting. If a write fails, drop to 20-byte chunks and retry. Wait for `onCharacteristicWrite` (or use write-without-response with the delay setting) so the printer's buffer doesn't overflow. Too-fast writes cause streaky or cut-off prints.

## Image pipeline (match the web version exactly)

1. Take the captured image, apply EXIF rotation, and scale it to **384 pixels wide** (the M02 print head is 384 dots, 203 dpi). Keep the aspect ratio. If "rotate wide photos" is on and the image is landscape, rotate it 90° first so it fills the paper's length.
2. Convert to grayscale: `0.299R + 0.587G + 0.114B`, compositing transparency onto white.
3. Auto-levels: find the 1st and 99th percentile, then stretch to 0–255. Skip this if the range is under 16.
4. Brightness/contrast: `c = contrast*2.55; f = (259*(c+255))/(255*(259-c)); v = (v-128)*f + 128 + brightness*2.55`, clamped to 0–255.
5. Dither to 1-bit (black when the value is under 128):
   - Atkinson: spread error/8 to (x+1,y), (x+2,y), (x−1,y+1), (x,y+1), (x+1,y+1), (x,y+2)
   - Floyd–Steinberg: 7/16 to (x+1,y), 3/16 to (x−1,y+1), 5/16 to (x,y+1), 1/16 to (x+1,y+1)
6. Pack each row into 48 bytes, MSB first, with bit = 1 meaning black.

Do the image processing off the main thread. The shutter must feel instant.

## Print command stream (ESC/POS raster, M02)

```
1B 40                     ESC @        init
1B 61 01                  ESC a 1      center
1F 11 02 04               Phomemo start
-- for each block of up to 255 rows:
1D 76 30 00  30 00  hh 00  GS v 0, width = 0x0030 (48 bytes), height = rows (little-endian 16-bit)
<48 * rows bytes of bitmap data>
-- after all blocks:
1B 64 <feed>              ESC d n      feed (setting, default 60)
1B 64 02
1F 11 08
1F 11 0E
1F 11 07
1F 11 09
```

## Android details

- minSdk 26, targetSdk 34 (or the newest the installed SDK supports). Kotlin and a single Activity are fine. Jetpack Compose or Views, whichever builds most reliably here.
- Permissions: `CAMERA`; on Android 12+ `BLUETOOTH_SCAN` (with `neverForLocation`) and `BLUETOOTH_CONNECT`; on Android 11 and below `BLUETOOTH`, `BLUETOOTH_ADMIN`, and `ACCESS_FINE_LOCATION`; plus `VIBRATE`. Request them at runtime, with a clear screen explaining why if they're denied.
- Use CameraX `ImageCapture` with `CAPTURE_MODE_MINIMIZE_LATENCY`.
- Handle disconnects gracefully. Never crash if the printer is off; show "Printer off? Tap to reconnect" and keep the queue.
- No internet permission. Nothing leaves the phone.

## Done means

- `./gradlew assembleDebug` succeeds with no errors.
- Tell me the APK path (`app/build/outputs/apk/debug/app-debug.apk`) and the exact `adb install -r` command.
- Give me a short list of what to test first: test print, then one snapped photo, then three quick snaps in a row.
