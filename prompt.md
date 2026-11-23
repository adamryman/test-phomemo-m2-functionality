# Thermal Printer Test Lab – Agent Prompt

## Context

You are working in an existing Android app that connects to a thermal receipt/label printer and does an "instant print" from the camera button. Your job is to inspect it, figure out what the printer can actually do, and build a full-featured **Test Lab** screen for exploring every capability. Then give me one command to install the app on my phone.

## Step 1: Inspect before building

- Read the whole codebase. Find how it connects (Bluetooth Classic, BLE, USB or Wi-Fi), the printer model or name, the protocol (ESC/POS, TSPL, CPCL or a vendor-specific one such as the cat-printer style BLE protocols), and how images are currently turned into print data.
- Check the printer's print width in dots (for example 384 or 576), DPI, and which commands it supports. If you can't tell, add a "probe" feature that sends status/ID queries and logs the raw responses.
- Write a short summary of what you found and list any capabilities you're unsure about. Don't stop to ask me: make reasonable assumptions, note them, and build controls that let me test them on the device.

## Step 2: Design the Test Lab

Add a new screen, reachable from the main screen, and keep the existing instant-print flow working. Use a scrollable layout of sections with a grid of test buttons. Include whatever the printer supports, at least:

- **Quick tests:** one line of text; a thin strip with a single icon or a row of icons; the smallest possible print and feed; a test pattern (gradient, checkerboard, ruler marks every 10 mm); a self-test or status print.
- **Text:** text input box; font size and scale (width/height multipliers); bold, underline, inverted, upside-down; alignment; line spacing; character set or code page selection.
- **Graphics:** built-in icon picker (emoji or vector icons rendered to bitmap); image from gallery or camera; dithering mode selector (threshold, Floyd–Steinberg, Atkinson, ordered) with a threshold slider; contrast and brightness sliders; live black-and-white preview at true printer width.
- **Speed and darkness:** sliders for print speed, heat or density/energy, and feed speed, if the protocol supports them. Show the raw value sent.
- **Paper control:** feed N lines or N mm slider; top and bottom margin; cut command if supported; label/gap detection if it's a label printer.
- **Codes:** QR code and barcode from text input, with size slider and type selector.
- **Batch and stress:** print N copies; a long continuous print to test overheating or throttling; a timer showing how long each print took.
- **Diagnostics:** connection status, battery and paper-out status if available, and a live log of bytes sent and received with copy-to-clipboard.

Every control should show a preview where it makes sense, and every test button should be one tap.

## Step 3: Build it

- Match the existing code style and dependencies; add only what you need.
- Put printer commands behind a single clean interface so unsupported features fail gracefully with a clear message instead of crashing.
- Make sure it compiles with no errors. Fix any build problems yourself.

## Step 4: Deliverables

1. A summary of the printer capabilities you found, plus your assumptions.
2. The list of features added.
3. The exact commands to build and install on my Android phone over USB, e.g. `./gradlew installDebug`, or the `adb install -r` path to the APK. Include a one-line reminder to turn on USB debugging if that's needed.
