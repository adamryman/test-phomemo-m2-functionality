// SPDX-License-Identifier: CC0-1.0
package com.adamryman.snapandprint

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily as ComposeFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

enum class BatchItem(val label: String) { PATTERN("Test pattern"), LINE("One line"), TEXT("Text"), GRAPHIC("Graphic"), CODE("Code") }
enum class LongPattern(val label: String) { CHECKER("50% checker"), STRIPES("10 mm stripes"), GRADIENT("Gradient"), SOLID("Solid black ⚠") }

/** All Test Lab controls. Compose state, so the UI and previews update live. */
class LabState {
    // Text
    var text by mutableStateOf("Hello from the Test Lab!\nThe quick brown fox jumps over the lazy dog. 0123456789")
    var textSize by mutableIntStateOf(28)
    var scaleW by mutableIntStateOf(1)
    var scaleH by mutableIntStateOf(1)
    var bold by mutableStateOf(false)
    var underline by mutableStateOf(false)
    var inverted by mutableStateOf(false)
    var upsideDown by mutableStateOf(false)
    var align by mutableIntStateOf(0)
    var lineSpacing by mutableIntStateOf(0)
    var family by mutableStateOf(FontFamily.SANS)
    var nativeText by mutableStateOf(false)
    var codePage by mutableStateOf(CodePage.PC437)

    // Graphics
    var icon by mutableStateOf("★")
    var iconSize by mutableIntStateOf(96)
    var iconCount by mutableIntStateOf(1)
    var image by mutableStateOf<Bitmap?>(null)
    var useImage by mutableStateOf(false)
    var dither by mutableStateOf(DitherStyle.ATKINSON)
    var threshold by mutableIntStateOf(128)
    var brightness by mutableIntStateOf(0)
    var contrast by mutableIntStateOf(0)
    var autoLevels by mutableStateOf(true)
    var rotateWide by mutableStateOf(true)

    // Speed & darkness
    var density by mutableIntStateOf(4)
    var speedOn by mutableStateOf(false)
    var speed by mutableIntStateOf(3)
    var heatOn by mutableStateOf(false)
    var heatDots by mutableIntStateOf(7)
    var heatTime by mutableIntStateOf(80)
    var heatInterval by mutableIntStateOf(2)

    // Paper
    var postFeed by mutableIntStateOf(60)
    var feedLines by mutableIntStateOf(10)
    var feedMm by mutableIntStateOf(10)
    var topMm by mutableIntStateOf(0)
    var bottomMm by mutableIntStateOf(0)
    var trailer by mutableStateOf(true)

    // Codes
    var codeText by mutableStateOf("https://example.com/test-lab")
    var codeType by mutableStateOf(CodeType.QR)
    var codeSize by mutableIntStateOf(6)
    var codeHeight by mutableIntStateOf(80)
    var ecc by mutableIntStateOf(1)

    // Batch & stress
    var copies by mutableIntStateOf(3)
    var batchItem by mutableStateOf(BatchItem.PATTERN)
    var longMm by mutableIntStateOf(200)
    var longPattern by mutableStateOf(LongPattern.CHECKER)

    var rawHex by mutableStateOf("1B 40 1B 64 05")

    fun jobOptions() = JobOptions(
        density = density,
        speed = if (speedOn) speed else null,
        heat = if (heatOn) HeatParams(heatDots, heatTime, heatInterval) else null,
        topMarginDots = topMm * Renderers.DOTS_PER_MM,
        bottomMarginDots = bottomMm * Renderers.DOTS_PER_MM,
        feedLines = postFeed,
        trailer = trailer,
    )

    fun bitmapTextStyle() = BitmapTextStyle(textSize, scaleW, scaleH, bold, underline, inverted, upsideDown, align, lineSpacing, family)
    fun nativeTextStyle() = NativeTextStyle(bold, underline, inverted, upsideDown, scaleW, scaleH, align,
        if (lineSpacing > 0) 30 + lineSpacing else null, codePage)

    fun renderText(): Bitmap = Renderers.text(text, bitmapTextStyle())
    fun renderLine(): Bitmap {
        val first = text.lineSequence().firstOrNull { it.isNotBlank() } ?: "Hello, printer!"
        return Renderers.text(first, BitmapTextStyle(sizePx = 24, align = 1))
    }
    fun renderGraphic(): Bitmap? =
        if (useImage) image else Renderers.icons(List(iconCount) { icon }, iconSize)
    fun renderCode(): Bitmap = Renderers.code(codeType, codeText, codeSize, codeHeight, ecc)

    fun processGraphic(b: Bitmap) = ImageProcessor.process(b, brightness, contrast, dither, threshold, autoLevels)
}

/** Crisp 1-bit conversion for text, codes and test patterns (no auto-levels, plain threshold). */
private fun crisp(b: Bitmap) = ImageProcessor.process(b, 0, 0, DitherStyle.THRESHOLD, 128, autoLevels = false)

/** Glue between buttons and the print manager. Rendering happens off the main thread. */
private class LabActions(val activity: MainActivity, val scope: CoroutineScope) {
    val mgr = activity.manager
    val lab = activity.lab
    val proto = mgr.protocol

    fun send(label: String, cmd: Cmd) {
        mgr.submitCommand(label, cmd)?.let { activity.toast("$label: $it") }
    }

    /** Renders on a background thread, converts to 1-bit and queues it [copies] times. */
    fun print(label: String, copies: Int = 1, opts: (JobOptions) -> JobOptions = { it },
              convert: (Bitmap) -> ProcessedImage = ::crisp, make: () -> Bitmap?) {
        val o = opts(lab.jobOptions())
        scope.launch(Dispatchers.Default) {
            try {
                val bmp = make() ?: run { activity.toast("Nothing to print"); return@launch }
                val cmd = proto.imageJob(convert(bmp), o)
                for (i in 1..copies) {
                    send(if (copies > 1) "$label ($i/$copies)" else label, cmd)
                }
            } catch (e: IllegalArgumentException) {
                activity.toast(e.message ?: "Can't render that")
            } catch (t: Throwable) {
                activity.toast("Render failed: ${t.message}")
            }
        }
    }

    fun printProcessed(label: String, img: ProcessedImage, o: JobOptions = lab.jobOptions()) =
        send(label, proto.imageJob(img, o))

    fun statusLines(): List<Pair<String, String>> {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return listOf("Time" to now, "Protocol" to proto.name, "Width" to "${proto.widthDots} dots @ ${proto.dpi} dpi") +
            mgr.connectionInfo + mgr.facts.value.toList() +
            listOf("Density" to "${lab.density}", "Feed" to "${lab.postFeed}")
    }

    fun longPrint() {
        val rows = lab.longMm * Renderers.DOTS_PER_MM
        val pattern = lab.longPattern
        scope.launch(Dispatchers.Default) {
            val bpr = ImageProcessor.BYTES_PER_ROW
            val packed = ByteArray(rows * bpr)
            for (y in 0 until rows) {
                val off = y * bpr
                when (pattern) {
                    LongPattern.SOLID -> packed.fill(0xFF.toByte(), off, off + bpr)
                    LongPattern.CHECKER -> packed.fill((if (y % 2 == 0) 0xAA else 0x55).toByte(), off, off + bpr)
                    LongPattern.STRIPES -> if ((y / (10 * Renderers.DOTS_PER_MM)) % 2 == 0) packed.fill(0xFF.toByte(), off, off + bpr)
                    LongPattern.GRADIENT -> {
                        // Darkness ramps 0→100% every 50 mm, ordered-dithered.
                        val level = (y % (50 * Renderers.DOTS_PER_MM)) * 64 / (50 * Renderers.DOTS_PER_MM)
                        for (x in 0 until ImageProcessor.WIDTH) {
                            if (BAYER[(y and 7) * 8 + (x and 7)] < level) {
                                packed[off + (x shr 3)] = (packed[off + (x shr 3)].toInt() or (0x80 ushr (x and 7))).toByte()
                            }
                        }
                    }
                }
            }
            val preview = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            printProcessed("long ${lab.longMm} mm ${pattern.label}", ProcessedImage(packed, rows, preview))
        }
    }

    fun densitySweep() {
        val base = lab.jobOptions()
        scope.launch(Dispatchers.Default) {
            for (d in 1..8) {
                val strip = Renderers.stack(listOf(
                    Renderers.text("density $d  (1F 11 02 %02X)".format(d), BitmapTextStyle(sizePx = 22, bold = true)),
                    Renderers.stepWedge(8, 32),
                ))
                val img = ImageProcessor.process(strip, 0, 0, DitherStyle.ORDERED, 128, autoLevels = false)
                printProcessed("density sweep $d", img, base.copy(density = d, feedLines = if (d == 8) base.feedLines else 0, trailer = d == 8))
            }
        }
    }

    companion object {
        val BAYER = intArrayOf(
            0, 32, 8, 40, 2, 34, 10, 42, 48, 16, 56, 24, 50, 18, 58, 26,
            12, 44, 4, 36, 14, 46, 6, 38, 60, 28, 52, 20, 62, 30, 54, 22,
            3, 35, 11, 43, 1, 33, 9, 41, 51, 19, 59, 27, 49, 17, 57, 25,
            15, 47, 7, 39, 13, 45, 5, 37, 63, 31, 55, 23, 61, 29, 53, 21,
        )
    }
}

private class LabButton(val label: String, val support: Support? = null, val onClick: () -> Unit)

/** Each debug area gets its own page, opened from the home screen. */
enum class LabPage(val title: String, val subtitle: String) {
    QUICK("Quick tests", "Line, icons, tiny prints, patterns, ruler, status page"),
    TEXT("Text", "Size, scale, bold/underline/inverted, alignment, code pages"),
    GRAPHICS("Graphics", "Icons, gallery/camera image, dithering, live preview"),
    DARKNESS("Speed and darkness", "Density, speed and heat commands with raw bytes"),
    PAPER("Paper control", "Feed lines/mm, margins, cut, label gap"),
    CODES("Codes", "QR, Data Matrix, Aztec, PDF417 and 1D barcodes"),
    BATCH("Batch and stress", "N copies, long prints, timings"),
    DIAGNOSTICS("Diagnostics", "Probe, battery/paper, raw hex, live byte log"),
    CONNECTION("Connection", "Pick/forget printer, packet size and delay"),
}

@Composable
fun AppScreen(activity: MainActivity) {
    val scope = rememberCoroutineScope()
    val act = remember(activity, scope) { LabActions(activity, scope) }
    val page = activity.page
    if (page != null) BackHandler { activity.page = null }
    val scroll = key(page) { rememberScrollState() }

    Surface(Modifier.fillMaxSize(), color = Color(0xFF0E0E0E)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(scroll)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (page != null) TextButton(onClick = { activity.page = null }) { Text("‹ Back") }
                Text(page?.title ?: "Phomemo M2 Debug", fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
            }
            LabStatusBar(activity)
            Spacer(Modifier.height(4.dp))
            when (page) {
                null -> HomePage(act)
                LabPage.QUICK -> QuickSection(act)
                LabPage.TEXT -> TextSection(act)
                LabPage.GRAPHICS -> GraphicsSection(act)
                LabPage.DARKNESS -> DarknessSection(act)
                LabPage.PAPER -> PaperSection(act)
                LabPage.CODES -> CodesSection(act)
                LabPage.BATCH -> BatchSection(act)
                LabPage.DIAGNOSTICS -> DiagnosticsSection(act)
                LabPage.CONNECTION -> ConnectionSection(act)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun HomePage(a: LabActions) {
    val lab = a.lab
    val proto = a.proto
    Text(
        "${proto.name} · ${proto.widthDots} dots (48 mm) · ${proto.dpi} dpi.  ${Support.YES.badge} confirmed  ${Support.EXPERIMENTAL.badge} experimental  ${Support.NO.badge} not supported",
        fontSize = 12.sp, color = Color(0xFF888888),
    )
    Caption("Test prints")
    ButtonGrid(listOf(
        LabButton("One line") { a.print("one line") { lab.renderLine() } },
        LabButton("Test pattern") { a.print("test pattern", convert = { ImageProcessor.process(it, 0, 0, DitherStyle.ORDERED, 128, false) }) { Renderers.testPattern() } },
        LabButton("Status page") { a.print("status page") { Renderers.statusPage(a.statusLines()) } },
        LabButton("Ruler 10 mm") { a.print("ruler") { Renderers.ruler(100) } },
        LabButton("Test card") { a.print("test card", convert = { ImageProcessor.process(it, 0, 0, DitherStyle.ATKINSON) }) { ImageProcessor.testCard() } },
        LabButton("Probe printer", proto.support(Feature.STATUS_QUERY)) { a.mgr.probe() },
    ))
    Caption("Debug pages")
    LabPage.entries.forEach { p ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1A1A1A)).clickable { a.activity.page = p }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(p.title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(p.subtitle, fontSize = 12.sp, color = Color(0xFF999999))
            }
            Text("›", fontSize = 22.sp, color = Color(0xFF888888))
        }
    }
}

@Composable
private fun ConnectionSection(a: LabActions) {
    val mgr = a.mgr
    val s = mgr.settings
    val pending by mgr.pending.collectAsStateWithLifecycle()
    val status by mgr.status.collectAsStateWithLifecycle()
    var packet by remember { mutableIntStateOf(s.packetSize) }
    var delayMs by remember { mutableIntStateOf(s.packetDelayMs) }
    val printerName = remember(status, a.activity.showPicker) { s.printerName }
    Text("Printer: ${printerName ?: "none"}", color = Color(0xFFBBBBBB))
    IntSlider("Packet size (bytes)", packet, 20..509) { packet = it; s.packetSize = it }
    IntSlider("Delay between packets (ms)", delayMs, 0..200) { delayMs = it; s.packetDelayMs = it }
    Text("Streaky or cut-off prints? Lower the packet size or raise the delay.", fontSize = 12.sp, color = Color(0xFF888888))
    ButtonGrid(listOf(
        LabButton("Pick printer") { a.activity.showPicker = true },
        LabButton("Reconnect") { mgr.reconnect() },
        LabButton("Disconnect") { mgr.disconnectNow() },
        LabButton("Forget printer") { mgr.forgetPrinter() },
        LabButton(if (pending > 0) "Clear queue ($pending)" else "Clear queue") { mgr.clearQueue() },
        LabButton("Reset packet defaults") { s.resetConnectionDefaults(); packet = s.packetSize; delayMs = s.packetDelayMs },
    ))
}

@Composable
private fun LabStatusBar(activity: MainActivity) {
    val mgr = activity.manager
    val status by mgr.status.collectAsStateWithLifecycle()
    val pending by mgr.pending.collectAsStateWithLifecycle()
    val results by mgr.results.collectAsStateWithLifecycle()
    val facts by mgr.facts.collectAsStateWithLifecycle()
    val (dot, text) = when (val s = status) {
        is PrinterStatus.NoPrinter -> Color(0xFF888888) to "No printer"
        is PrinterStatus.Disconnected -> Color(0xFF888888) to "Not connected"
        is PrinterStatus.Connecting -> Color(0xFFFFC107) to "Connecting…"
        is PrinterStatus.Ready -> Color(0xFF4CAF50) to "Ready · ${s.name}"
        is PrinterStatus.Printing -> Color(0xFF42A5F5) to "Sending ${(s.progress * 100).roundToInt()}%"
        is PrinterStatus.Error -> Color(0xFFE53935) to s.message
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF1A1A1A))
            .clickable { activity.onStatusTapped() }.padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            Text(text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
            if (pending > 0) Text("$pending queued", fontSize = 13.sp, color = Color(0xFF42A5F5))
        }
        val extra = listOfNotNull(
            facts["Battery"]?.let { "🔋 $it" } ?: facts["Battery (BAS)"]?.let { "🔋 $it" },
            facts["Paper"]?.let { "Paper: $it" },
        )
        if (extra.isNotEmpty()) Text(extra.joinToString("   "), fontSize = 12.sp, color = Color(0xFFBBBBBB))
        results.firstOrNull()?.let { r ->
            Text(
                "Last: ${r.label} · ${r.bytes} B · ${r.millis} ms · ${"%.1f".format(r.kbPerSec)} KB/s${if (r.ok) "" else " · FAILED"}",
                fontSize = 12.sp, color = if (r.ok) Color(0xFFBBBBBB) else Color(0xFFE57373), maxLines = 1,
            )
        }
    }
}

// ---------------- Sections ----------------

@Composable
private fun QuickSection(a: LabActions) {
    val lab = a.lab
    ButtonGrid(listOf(
        LabButton("One line") { a.print("one line") { lab.renderLine() } },
        LabButton("Icon strip") { a.print("icon strip", convert = { lab.processGraphic(it) }) { Renderers.icons(Renderers.ICONS.take(8), 36, 6) } },
        LabButton("Single icon") { a.print("icon", convert = { lab.processGraphic(it) }) { Renderers.icons(listOf(lab.icon), 64) } },
        LabButton("Tiny: 1 dot row") { a.print("1 row", opts = { it.copy(feedLines = 0, trailer = false, topMarginDots = 0, bottomMarginDots = 0) }) { Renderers.solid(1) } },
        LabButton("Tiny: 1 mm bar") { a.print("1 mm bar", opts = { it.copy(feedLines = 0, trailer = false, topMarginDots = 0, bottomMarginDots = 0) }) { Renderers.solid(8) } },
        LabButton("Tiny feed", Support.YES) { a.send("feed 1", a.proto.feedLines(1)) },
        LabButton("Test pattern") { a.print("test pattern", convert = { ImageProcessor.process(it, 0, 0, DitherStyle.ORDERED, 128, false) }) { Renderers.testPattern() } },
        LabButton("Gradient") { a.print("gradient", convert = { ImageProcessor.process(it, 0, 0, lab.dither, lab.threshold, false) }) { Renderers.gradient(96) } },
        LabButton("Checkerboard") { a.print("checkerboard") { Renderers.checkerboard() } },
        LabButton("Ruler 10 mm") { a.print("ruler") { Renderers.ruler(100) } },
        LabButton("Status page") { a.print("status page") { Renderers.statusPage(a.statusLines()) } },
        LabButton("Self-test", a.proto.support(Feature.SELF_TEST)) { a.send("self-test", a.proto.selfTest()) },
        LabButton("Test card") { a.print("test card", convert = { ImageProcessor.process(it, 0, 0, DitherStyle.ATKINSON) }) { ImageProcessor.testCard() } },
    ))
    val preview by produceState<Bitmap?>(null) {
        value = withContext(Dispatchers.Default) { ImageProcessor.process(Renderers.testPattern(), 0, 0, DitherStyle.ORDERED, 128, false).preview }
    }
    Caption("Test pattern preview")
    DotPreview(preview, maxHeight = 260)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextSection(a: LabActions) {
    val lab = a.lab
    OutlinedTextField(lab.text, { lab.text = it }, Modifier.fillMaxWidth(), label = { Text("Text") }, minLines = 2)
    IntSlider("Font size (px)", lab.textSize, 8..120) { lab.textSize = it }
    IntSlider("Width ×", lab.scaleW, 1..4) { lab.scaleW = it }
    IntSlider("Height ×", lab.scaleH, 1..4) { lab.scaleH = it }
    IntSlider("Extra line spacing (px)", lab.lineSpacing, 0..40) { lab.lineSpacing = it }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("Bold", lab.bold) { lab.bold = !lab.bold }
        Chip("Underline", lab.underline) { lab.underline = !lab.underline }
        Chip("Inverted", lab.inverted) { lab.inverted = !lab.inverted }
        Chip("Upside-down", lab.upsideDown) { lab.upsideDown = !lab.upsideDown }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Left", "Center", "Right").forEachIndexed { i, s -> Chip(s, lab.align == i) { lab.align = i } }
        FontFamily.entries.forEach { f -> Chip(f.label, lab.family == f) { lab.family = f } }
    }
    SwitchRow("Use printer's built-in font ${Support.EXPERIMENTAL.badge}", lab.nativeText) { lab.nativeText = it }
    if (lab.nativeText) {
        Text("Code page (ESC t n)", fontSize = 13.sp, color = Color(0xFFBBBBBB))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CodePage.entries.forEach { c -> Chip(c.label, lab.codePage == c) { lab.codePage = c } }
        }
        Text(a.proto.note(Feature.NATIVE_TEXT), fontSize = 12.sp, color = Color(0xFF888888))
    }
    val style = lab.bitmapTextStyle()
    val preview by produceState<Bitmap?>(null, lab.text, style) {
        value = withContext(Dispatchers.Default) { runCatching { crisp(Renderers.text(lab.text, style)).preview }.getOrNull() }
    }
    if (!lab.nativeText) DotPreview(preview, maxHeight = 320)
    Spacer(Modifier.height(6.dp))
    ButtonGrid(listOf(
        LabButton(if (lab.nativeText) "Print native text" else "Print text", if (lab.nativeText) Support.EXPERIMENTAL else null) {
            if (lab.nativeText) a.send("native text", a.proto.nativeText(lab.text, lab.nativeTextStyle(), lab.jobOptions()))
            else a.print("text") { lab.renderText() }
        },
        LabButton("Size ladder") {
            a.print("size ladder") {
                Renderers.stack(listOf(12, 16, 20, 24, 32, 40, 56, 72).map { sz ->
                    Renderers.text("${sz}px Aa Bb 123", lab.bitmapTextStyle().copy(sizePx = sz, scaleW = 1, scaleH = 1))
                })
            }
        },
        LabButton("All styles") {
            a.print("style sheet") {
                val base = BitmapTextStyle(sizePx = 24)
                Renderers.stack(listOf(
                    Renderers.text("Regular", base), Renderers.text("Bold", base.copy(bold = true)),
                    Renderers.text("Underline", base.copy(underline = true)), Renderers.text("Inverted", base.copy(inverted = true)),
                    Renderers.text("Upside-down", base.copy(upsideDown = true)), Renderers.text("Wide ×2", base.copy(scaleW = 2)),
                    Renderers.text("Tall ×2", base.copy(scaleH = 2)), Renderers.text("Serif", base.copy(family = FontFamily.SERIF)),
                    Renderers.text("Mono 0O1lI", base.copy(family = FontFamily.MONO)), Renderers.text("Right", base.copy(align = 2)),
                ))
            }
        },
    ))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GraphicsSection(a: LabActions) {
    val lab = a.lab
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Renderers.ICONS.forEach { ic ->
            val sel = !lab.useImage && lab.icon == ic
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (sel) Color.White else Color(0xFF242424))
                    .clickable { lab.icon = ic; lab.useImage = false },
                contentAlignment = Alignment.Center,
            ) { Text(ic, fontSize = 20.sp, color = if (sel) Color.Black else Color.White) }
        }
    }
    IntSlider("Icon size (px)", lab.iconSize, 16..300) { lab.iconSize = it }
    IntSlider("Icons in a row", lab.iconCount, 1..10) { lab.iconCount = it }
    ButtonGrid(listOf(
        LabButton("Pick from gallery") { a.activity.labPickImage() },
        LabButton("Take photo") { a.activity.labCapturePhoto() },
        LabButton(if (lab.useImage) "Use icon instead" else "Use last image") {
            if (lab.useImage) lab.useImage = false
            else if (lab.image != null) lab.useImage = true
            else a.activity.toast("Pick or take a photo first")
        },
    ))
    SwitchRow("Rotate wide photos to fill the paper", lab.rotateWide) { lab.rotateWide = it }
    Text("Dither", fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DitherStyle.entries.forEach { d -> Chip(d.label, lab.dither == d) { lab.dither = d } }
    }
    IntSlider("Threshold", lab.threshold, 1..254) { lab.threshold = it }
    IntSlider("Brightness", lab.brightness, -50..50) { lab.brightness = it }
    IntSlider("Contrast", lab.contrast, -50..50) { lab.contrast = it }
    SwitchRow("Auto-levels (1st–99th percentile stretch)", lab.autoLevels) { lab.autoLevels = it }

    val src = if (lab.useImage) lab.image else null
    val preview by produceState<Bitmap?>(null, lab.useImage, src, lab.icon, lab.iconSize, lab.iconCount,
        lab.dither, lab.threshold, lab.brightness, lab.contrast, lab.autoLevels) {
        value = withContext(Dispatchers.Default) { lab.renderGraphic()?.let { lab.processGraphic(it).preview } }
    }
    Caption("Live 1-bit preview at true printer width")
    DotPreview(preview, maxHeight = 420)
    Spacer(Modifier.height(6.dp))
    ButtonGrid(listOf(
        LabButton("Print graphic") { a.print(if (lab.useImage) "image" else "icon ${lab.icon}", convert = { lab.processGraphic(it) }) { lab.renderGraphic() } },
        LabButton("All dithers") {
            a.print("dither compare") {
                val g = lab.renderGraphic() ?: return@print null
                val small = Bitmap.createScaledBitmap(g, ImageProcessor.WIDTH, g.height.coerceAtMost(240), true)
                Renderers.stack(DitherStyle.entries.flatMap { d ->
                    val img = ImageProcessor.process(small, lab.brightness, lab.contrast, d, lab.threshold, lab.autoLevels).preview
                    listOf(Renderers.text(d.label, BitmapTextStyle(sizePx = 20, bold = true)), img, Renderers.spacer(8))
                })
            }
        },
    ))
}

@Composable
private fun DarknessSection(a: LabActions) {
    val lab = a.lab
    val p = a.proto
    Text("These apply to every Test Lab print.", fontSize = 12.sp, color = Color(0xFF888888))
    IntSlider("Density ${p.support(Feature.DENSITY).badge}  raw: 1F 11 02 %02X".format(lab.density), lab.density, 0..15) { lab.density = it }
    SwitchRow("Send print speed ${p.support(Feature.SPEED).badge}", lab.speedOn) { lab.speedOn = it }
    if (lab.speedOn) IntSlider("Speed  raw: 1B 4E 0D %02X".format(lab.speed), lab.speed, 0..15) { lab.speed = it }
    SwitchRow("Send heat parameters (ESC 7) ${p.support(Feature.HEAT_PARAMS).badge}", lab.heatOn) { lab.heatOn = it }
    if (lab.heatOn) {
        IntSlider("Max heating dots (n1)", lab.heatDots, 0..255) { lab.heatDots = it }
        IntSlider("Heating time (n2, ×10 µs)", lab.heatTime, 3..255) { lab.heatTime = it }
        IntSlider("Heating interval (n3, ×10 µs)", lab.heatInterval, 0..255) { lab.heatInterval = it }
    }
    Text("Feed speed ${p.support(Feature.FEED_SPEED).badge}: ${p.note(Feature.FEED_SPEED)}", fontSize = 13.sp, color = Color(0xFFBBBBBB))
    Text("Header sent: ${p.headerHex(lab.jobOptions())}", fontSize = 12.sp, fontFamily = ComposeFontFamily.Monospace,
        color = Color(0xFF9CCC65), modifier = Modifier.padding(vertical = 6.dp))
    ButtonGrid(listOf(
        LabButton("Gradient @ settings") { a.print("gradient d${lab.density}", convert = { ImageProcessor.process(it, 0, 0, DitherStyle.ORDERED, 128, false) }) { Renderers.stack(listOf(Renderers.gradient(48), Renderers.stepWedge())) } },
        LabButton("Density sweep 1–8") { a.densitySweep() },
        LabButton("Solid 5 mm") { a.print("solid 5 mm") { Renderers.solid(40) } },
    ))
}

@Composable
private fun PaperSection(a: LabActions) {
    val lab = a.lab
    val p = a.proto
    IntSlider("Feed after each print (ESC d n)", lab.postFeed, 0..255) { lab.postFeed = it }
    SwitchRow("Send Phomemo trailer (ESC d 2 + 1F 11 08/0E/07/09)", lab.trailer) { lab.trailer = it }
    IntSlider("Top margin (mm, blank rows)", lab.topMm, 0..30) { lab.topMm = it }
    IntSlider("Bottom margin (mm, blank rows)", lab.bottomMm, 0..30) { lab.bottomMm = it }
    IntSlider("Feed lines  raw: 1B 64 %02X".format(lab.feedLines), lab.feedLines, 0..255) { lab.feedLines = it }
    IntSlider("Feed mm  = ${lab.feedMm * Renderers.DOTS_PER_MM} dots", lab.feedMm, 1..100) { lab.feedMm = it }
    ButtonGrid(listOf(
        LabButton("Feed ${lab.feedLines} lines", p.support(Feature.FEED_LINES)) { a.send("feed ${lab.feedLines} lines", p.feedLines(lab.feedLines)) },
        LabButton("Feed ${lab.feedMm} mm (ESC J)", p.support(Feature.FEED_DOTS)) { a.send("feed ${lab.feedMm} mm", p.feedDots(lab.feedMm * Renderers.DOTS_PER_MM)) },
        LabButton("Feed ${lab.feedMm} mm (blank)", Support.YES) {
            a.print("blank ${lab.feedMm} mm", opts = { it.copy(feedLines = 0, trailer = false, topMarginDots = 0, bottomMarginDots = 0) }) { Renderers.spacer(lab.feedMm * Renderers.DOTS_PER_MM) }
        },
        LabButton("Mark + margins") {
            a.print("margin test") { Renderers.text("▲ top margin ${lab.topMm} mm\n▼ bottom margin ${lab.bottomMm} mm", BitmapTextStyle(sizePx = 22, align = 1)) }
        },
        LabButton("Cut", p.support(Feature.CUT)) { a.send("cut", p.cut()) },
        LabButton("Label gap detect", p.support(Feature.LABEL_GAP)) { a.send("label gap", p.labelGapDetect()) },
    ))
    Text("ESC d units are printer ‘lines’; compare against the ruler to calibrate.", fontSize = 12.sp, color = Color(0xFF888888))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CodesSection(a: LabActions) {
    val lab = a.lab
    OutlinedTextField(lab.codeText, { lab.codeText = it }, Modifier.fillMaxWidth(), label = { Text("Content") }, singleLine = true)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CodeType.entries.forEach { t -> Chip(t.label, lab.codeType == t) { lab.codeType = t } }
    }
    IntSlider(if (lab.codeType.twoD) "Module size (dots)" else "Bar width (dots)", lab.codeSize, 1..16) { lab.codeSize = it }
    if (!lab.codeType.twoD) IntSlider("Bar height (dots)", lab.codeHeight, 16..400) { lab.codeHeight = it }
    if (lab.codeType == CodeType.QR) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("L", "M", "Q", "H").forEachIndexed { i, s -> Chip("ECC $s", lab.ecc == i) { lab.ecc = i } }
        }
    }
    val state by produceState<Pair<Bitmap?, String?>>(null to null, lab.codeText, lab.codeType, lab.codeSize, lab.codeHeight, lab.ecc) {
        value = withContext(Dispatchers.Default) {
            try { crisp(lab.renderCode()).preview to null } catch (e: IllegalArgumentException) { null to e.message }
        }
    }
    state.second?.let { Text(it, color = Color(0xFFE57373), fontSize = 13.sp) }
    DotPreview(state.first, maxHeight = 320)
    Spacer(Modifier.height(6.dp))
    ButtonGrid(listOf(
        LabButton("Print ${lab.codeType.label}") { a.print(lab.codeType.label) { lab.renderCode() } },
        LabButton("Native QR", a.proto.support(Feature.NATIVE_QR)) { a.send("native QR", a.proto.nativeQr(lab.codeText, lab.codeSize, lab.ecc, lab.jobOptions())) },
        LabButton("Native Code 128", a.proto.support(Feature.NATIVE_BARCODE)) {
            a.send("native Code 128", a.proto.nativeBarcode128(lab.codeText, lab.codeHeight, lab.codeSize.coerceIn(2, 6), lab.jobOptions()))
        },
        LabButton("QR size ladder") {
            a.print("QR ladder") {
                Renderers.stack((2..8 step 2).flatMap { m ->
                    listOf(Renderers.text("module $m", BitmapTextStyle(sizePx = 18)), Renderers.code(CodeType.QR, lab.codeText, m, 0, lab.ecc), Renderers.spacer(8))
                })
            }
        },
    ))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BatchSection(a: LabActions) {
    val lab = a.lab
    val results by a.mgr.results.collectAsStateWithLifecycle()
    IntSlider("Copies", lab.copies, 1..20) { lab.copies = it }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BatchItem.entries.forEach { b -> Chip(b.label, lab.batchItem == b) { lab.batchItem = b } }
    }
    IntSlider("Long print length (mm)", lab.longMm, 20..1000) { lab.longMm = it }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LongPattern.entries.forEach { p -> Chip(p.label, lab.longPattern == p) { lab.longPattern = p } }
    }
    ButtonGrid(listOf(
        LabButton("Print ${lab.copies} × ${lab.batchItem.label}") {
            val item = lab.batchItem
            val convert: (Bitmap) -> ProcessedImage = if (item == BatchItem.GRAPHIC) lab::processGraphic else ::crisp
            a.print("batch ${item.label}", copies = lab.copies, convert = convert) {
                when (item) {
                    BatchItem.PATTERN -> Renderers.testPattern()
                    BatchItem.LINE -> lab.renderLine()
                    BatchItem.TEXT -> lab.renderText()
                    BatchItem.GRAPHIC -> lab.renderGraphic()
                    BatchItem.CODE -> lab.renderCode()
                }
            }
        },
        LabButton("Long print ${lab.longMm} mm") { a.longPrint() },
        LabButton("Clear queue") { a.mgr.clearQueue() },
    ))
    Text(
        "Timing = time to stream the job over BLE (the printer buffers little, so for long jobs it tracks print speed). " +
            "Throughput is logged every 25% for big jobs — a falling KB/s suggests thermal throttling.",
        fontSize = 12.sp, color = Color(0xFF888888),
    )
    if (results.isNotEmpty()) {
        Column(Modifier.padding(top = 6.dp)) {
            results.take(12).forEach { r ->
                Text(
                    "%-22s %7d B %6d ms %6.1f KB/s %s".format(r.label.take(22), r.bytes, r.millis, r.kbPerSec, if (r.ok) "" else "FAIL"),
                    fontSize = 11.sp, fontFamily = ComposeFontFamily.Monospace,
                    color = if (r.ok) Color(0xFFCCCCCC) else Color(0xFFE57373),
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsSection(a: LabActions) {
    val mgr = a.mgr
    val lab = a.lab
    val log by mgr.log.collectAsStateWithLifecycle()
    val facts by mgr.facts.collectAsStateWithLifecycle()
    val status by mgr.status.collectAsStateWithLifecycle()
    // connectionInfo isn't a flow; re-read it whenever status or the log changes.
    val info = remember(status, log.size) { mgr.connectionInfo }
    KeyValues(info + facts.toList())
    ButtonGrid(listOf(
        LabButton("Probe printer", a.proto.support(Feature.STATUS_QUERY)) { mgr.probe() },
        LabButton("Reconnect") { mgr.reconnect() },
        LabButton("Disconnect") { mgr.disconnectNow() },
        LabButton("Pick printer") { a.activity.showPicker = true },
        LabButton("Copy log") {
            val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
            val textLog = buildString {
                (info + facts.toList()).forEach { (k, v) -> append(k).append(": ").append(v).append('\n') }
                append("GATT:\n"); mgr.gattTable.forEach { append(it).append('\n') }
                append('\n')
                log.forEach { append(fmt.format(Date(it.timeMs))).append(' ').append(it.dir.arrow).append(' ').append(it.text).append('\n') }
            }
            val cm = a.activity.getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("Printer log", textLog))
            a.activity.toast("Log copied (${log.size} lines)")
        },
        LabButton("Clear log") { mgr.clearLog() },
    ))
    OutlinedTextField(
        lab.rawHex, { lab.rawHex = it }, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text("Raw hex to send") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        textStyle = LocalTextStyle.current.copy(fontFamily = ComposeFontFamily.Monospace),
    )
    ButtonGrid(listOf(
        LabButton("Send raw bytes") {
            val bytes = parseHex(lab.rawHex)
            if (bytes == null) a.activity.toast("Not valid hex") else a.send("raw", Cmd.Bytes(bytes, "raw"))
        },
    ))

    Caption("Capabilities")
    Feature.entries.forEach { f ->
        val s = a.proto.support(f)
        Row(Modifier.padding(vertical = 2.dp)) {
            Text(s.badge, fontSize = 13.sp, modifier = Modifier.width(22.dp), color = when (s) {
                Support.YES -> Color(0xFF81C784); Support.EXPERIMENTAL -> Color(0xFFFFD54F); Support.NO -> Color(0xFFE57373)
            })
            Column {
                Text(f.label, fontSize = 13.sp)
                Text(a.proto.note(f), fontSize = 11.sp, color = Color(0xFF888888))
            }
        }
    }

    Caption("Live log (${log.size})")
    val fmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    val logScroll = rememberScrollState()
    LaunchedEffect(log.size) { logScroll.scrollTo(logScroll.maxValue) }
    Column(
        Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black)
            .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(8.dp)).verticalScroll(logScroll).padding(8.dp)
    ) {
        if (log.isEmpty()) Text("No traffic yet. Tap “Probe printer”.", fontSize = 12.sp, color = Color(0xFF888888))
        log.takeLast(200).forEach { e ->
            Text(
                "${fmt.format(Date(e.timeMs))} ${e.dir.arrow} ${e.text}",
                fontSize = 11.sp, fontFamily = ComposeFontFamily.Monospace,
                color = when (e.dir) { LogDir.TX -> Color(0xFF90CAF9); LogDir.RX -> Color(0xFFA5D6A7); LogDir.INFO -> Color(0xFFAAAAAA) },
            )
        }
    }
}

// ---------------- Building blocks ----------------

@Composable
private fun ButtonGrid(buttons: List<LabButton>, columns: Int = 3) {
    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        buttons.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { b ->
                    OutlinedButton(
                        onClick = b.onClick,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        val badge = b.support?.takeIf { it != Support.YES }?.badge?.let { "$it " } ?: ""
                        Text(badge + b.label, fontSize = 13.sp, maxLines = 2, lineHeight = 15.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = if (b.support == Support.NO) Color(0xFF888888) else Color.White)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, fontSize = 13.sp) })
}

@Composable
private fun Caption(text: String) {
    Text(text, fontSize = 12.sp, color = Color(0xFF888888), modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
private fun KeyValues(rows: List<Pair<String, String>>) {
    Column(Modifier.padding(vertical = 4.dp)) {
        rows.forEach { (k, v) ->
            Row {
                Text(k, fontSize = 13.sp, color = Color(0xFF999999), modifier = Modifier.width(120.dp))
                Text(v, fontSize = 13.sp)
            }
        }
    }
}

/** Shows a 1-bit bitmap pixel-exact on white, scaled to the screen width, plus its physical size. */
@Composable
private fun DotPreview(bmp: Bitmap?, maxHeight: Int) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 40.dp, max = maxHeight.dp).clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF242424)),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (bmp != null) {
            val img = remember(bmp) { bmp.asImageBitmap() }
            Image(
                img, contentDescription = "Print preview",
                filterQuality = FilterQuality.None, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height.coerceAtLeast(1), matchHeightConstraintsFirst = true)
                    .background(Color.White),
            )
        } else {
            Text("…", color = Color(0xFF888888), modifier = Modifier.padding(12.dp))
        }
    }
    if (bmp != null) {
        Text("${bmp.width} × ${bmp.height} dots · %.1f mm long".format(bmp.height / Renderers.DOTS_PER_MM.toFloat()),
            fontSize = 11.sp, color = Color(0xFF888888))
    }
}
