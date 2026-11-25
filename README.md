# Debug Phomemo M2 Functionality

An Android app for finding out what a Phomemo M02 thermal printer can do. It talks
to the printer directly over Bluetooth Low Energy. The app has no internet permission.

The app ID is `com.adamryman.phomemodebug`. The project began as a camera app called
Snap & Print, so the Kotlin package is still `com.adamryman.snapandprint`.

## Features

The home screen shows the connection status, a few one-tap test prints, and a menu of pages:

| Page | What it does |
| --- | --- |
| Quick tests | Text line, icons, tiny prints, test patterns, ruler, status page |
| Text | Size, scale, styles, alignment, fonts, and experimental native printer text |
| Graphics | Icons and photos, four dithering modes, live 1-bit preview |
| Speed and darkness | Density, speed and heat commands, with the raw bytes sent |
| Paper control | Feed by lines or mm, and margins |
| Codes | QR, Data Matrix, Aztec, PDF417 and 1D barcodes |
| Batch and stress | Multiple copies, long prints, transfer timings |
| Diagnostics | Status probe, raw hex, capability list, log of bytes sent and received |
| Connection | Pick or forget a printer, packet size and delay |

Controls marked ⚗ are experimental: the printer may ignore them. ✕ means unsupported,
so the app explains why instead of sending anything (for example, the cut button).

## Printer notes

- BLE name `Mr.in_M02`; service `ff00`, write `ff02`, notify `ff03`.
- 384 dots wide, 203 dpi (about 8 dots per mm).
- Images use ESC/POS raster (`GS v 0`), plus Phomemo `1F 11 xx` commands.
- The meanings of `1F 11 xx` commands and the decoded replies (battery, paper, firmware)
  are guesses. Check them against the raw log.
- Timings measure the Bluetooth transfer, not when the paper stops moving.
- A failed job is logged and dropped, not retried.

## Build

You need:

- JDK 17 or newer (built with 21)
- Android SDK Platform 37.2, with `ANDROID_HOME` set or `sdk.dir` in `local.properties`
- An internet connection for the first build, to download Gradle and dependencies

```sh
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Install

You need an Android 8.0 (API 26) or newer phone with Bluetooth LE. Turn on USB debugging
in Developer options, connect the phone, accept the prompt, then run:

```sh
./gradlew installDebug
```

Or install an APK you have already built:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On first launch, grant the requested permission (Nearby devices, or Location on
Android 11 and older), turn the printer on, and pick it from the list.

## License

All code added after the initial commit (`8733b4e`) is dedicated to the public domain
under [CC0 1.0 Universal](LICENSE), including changes to files that existed before it.
This does not cover code that remains from the initial commit.

Third-party components keep their own licenses, including the Gradle wrapper, AndroidX
and ZXing (all Apache 2.0).
