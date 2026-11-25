// SPDX-License-Identifier: CC0-1.0
package com.adamryman.snapandprint

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/** How sure we are that the printer understands a command. */
enum class Support(val badge: String) {
    YES("✓"),             // confirmed working on the M02
    EXPERIMENTAL("⚗"),    // plausible (ESC/POS or Phomemo app captures); test on the device
    NO("✕"),              // not available on this hardware / no known command
}

enum class Feature(val label: String) {
    RASTER("Raster image (GS v 0)"),
    FEED_LINES("Feed n lines (ESC d)"),
    FEED_DOTS("Feed n dots (ESC J)"),
    DENSITY("Density / heat level (1F 11 02 n)"),
    SPEED("Print speed (ESC N 0D n)"),
    HEAT_PARAMS("Heating dots/time/interval (ESC 7)"),
    FEED_SPEED("Feed speed"),
    NATIVE_TEXT("Built-in font text (ESC ! / GS !)"),
    CODE_PAGE("Code page (ESC t n)"),
    NATIVE_QR("Built-in QR (GS ( k)"),
    NATIVE_BARCODE("Built-in barcode (GS k)"),
    SELF_TEST("Self-test (GS ( A)"),
    STATUS_QUERY("Status queries (1F 11 xx, DLE EOT, GS I)"),
    CUT("Paper cut (GS V)"),
    LABEL_GAP("Label / gap detection"),
}

/** Either bytes to send, or a clear reason why we won't. Never throws. */
sealed interface Cmd {
    class Bytes(val data: ByteArray, val label: String) : Cmd
    class Unsupported(val reason: String) : Cmd
}

data class Query(val label: String, val bytes: ByteArray)

data class HeatParams(val maxDots: Int, val heatTime: Int, val interval: Int)

/** Everything a Test Lab job can tweak around a raster image. Null = don't send that command. */
data class JobOptions(
    val density: Int = 4,
    val speed: Int? = null,
    val heat: HeatParams? = null,
    val topMarginDots: Int = 0,
    val bottomMarginDots: Int = 0,
    val feedLines: Int = 60,
    val trailer: Boolean = true,
)

data class NativeTextStyle(
    val bold: Boolean = false,
    val underline: Boolean = false,
    val inverted: Boolean = false,
    val upsideDown: Boolean = false,
    val scaleW: Int = 1,
    val scaleH: Int = 1,
    val align: Int = 0,          // 0 left, 1 center, 2 right
    val lineSpacing: Int? = null,  // dots; null = printer default
    val codePage: CodePage = CodePage.PC437,
)

enum class CodePage(val n: Int, val charset: String, val label: String) {
    PC437(0, "IBM437", "0 · PC437 (US)"),
    KATAKANA(1, "ISO-8859-1", "1 · Katakana"),
    PC850(2, "IBM850", "2 · PC850 (Multilingual)"),
    PC860(3, "IBM860", "3 · PC860 (Portuguese)"),
    PC863(4, "IBM863", "4 · PC863 (Canadian-French)"),
    PC865(5, "IBM865", "5 · PC865 (Nordic)"),
    WPC1252(16, "windows-1252", "16 · WPC1252 (Latin 1)"),
    PC866(17, "IBM866", "17 · PC866 (Cyrillic)"),
    PC852(18, "IBM852", "18 · PC852 (Latin 2)"),
    PC858(19, "IBM00858", "19 · PC858 (Euro)"),
}

/**
 * The single interface for everything we send to a printer. UI code never builds bytes itself;
 * unsupported features return [Cmd.Unsupported] with a human-readable reason.
 */
interface PrinterProtocol {
    val name: String
    val widthDots: Int
    val dpi: Int
    val dotsPerMm: Float get() = dpi / 25.4f

    fun support(f: Feature): Support
    fun note(f: Feature): String

    fun imageJob(img: ProcessedImage, o: JobOptions): Cmd
    fun feedLines(n: Int): Cmd
    fun feedDots(n: Int): Cmd
    fun cut(): Cmd
    fun labelGapDetect(): Cmd
    fun selfTest(): Cmd
    fun nativeText(text: String, style: NativeTextStyle, o: JobOptions): Cmd
    fun nativeQr(text: String, moduleSize: Int, ecc: Int, o: JobOptions): Cmd
    fun nativeBarcode128(text: String, height: Int, moduleWidth: Int, o: JobOptions): Cmd
    fun queries(): List<Query>

    /** Human-readable meaning of a notification; also returns key/value facts for the status panel. */
    fun parse(bytes: ByteArray): List<Pair<String, String>>

    /** Preview of the bytes sent before the raster for these options, for the "raw value" labels. */
    fun headerHex(o: JobOptions): String
}

fun ByteArray.toHex(max: Int = Int.MAX_VALUE): String {
    val n = minOf(size, max)
    val sb = StringBuilder(n * 3 + 16)
    for (i in 0 until n) {
        if (i > 0) sb.append(' ')
        sb.append(String.format("%02X", this[i].toInt() and 0xFF))
    }
    if (size > max) sb.append(" … (").append(size).append(" bytes)")
    return sb.toString()
}

/** Parses "1b 40", "1B40", "0x1b,0x40" etc. Returns null if it isn't valid hex. */
fun parseHex(s: String): ByteArray? {
    val clean = s.replace("0x", "", ignoreCase = true).filter { !it.isWhitespace() && it != ',' && it != ':' }
    if (clean.isEmpty() || clean.length % 2 != 0) return null
    return runCatching { ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
        .getOrNull()
}

/** Phomemo M02 / M02S family: ESC/POS-ish raster over BLE plus Phomemo 1F 11 xx extensions. */
object PhomemoM02 : PrinterProtocol {
    override val name = "Phomemo M02"
    override val widthDots = ImageProcessor.WIDTH
    override val dpi = 203

    override fun support(f: Feature): Support = when (f) {
        Feature.RASTER, Feature.FEED_LINES -> Support.YES
        Feature.FEED_DOTS, Feature.DENSITY, Feature.SPEED, Feature.HEAT_PARAMS,
        Feature.NATIVE_TEXT, Feature.CODE_PAGE, Feature.NATIVE_QR, Feature.NATIVE_BARCODE,
        Feature.SELF_TEST, Feature.STATUS_QUERY -> Support.EXPERIMENTAL
        Feature.FEED_SPEED, Feature.CUT, Feature.LABEL_GAP -> Support.NO
    }

    override fun note(f: Feature): String = when (f) {
        Feature.RASTER -> "Confirmed: the instant-print flow uses it."
        Feature.FEED_LINES -> "Confirmed: used after every photo."
        Feature.FEED_DOTS -> "Standard ESC/POS. Blank raster rows always work as a fallback."
        Feature.DENSITY -> "The ‘start’ command 1F 11 02 04 is believed to carry density (4 = app default)."
        Feature.SPEED -> "Used by other Phomemo models (M110). May be ignored."
        Feature.HEAT_PARAMS -> "Classic ESC/POS heat control. May be ignored."
        Feature.FEED_SPEED -> "No known command on this model."
        Feature.NATIVE_TEXT -> "Mini Phomemos usually have no font ROM; the bitmap text mode always works."
        Feature.CODE_PAGE -> "Only matters if built-in text works."
        Feature.NATIVE_QR, Feature.NATIVE_BARCODE -> "Likely unsupported; bitmap codes always work."
        Feature.SELF_TEST -> "No documented self-test; the status page is app-generated."
        Feature.STATUS_QUERY -> "Replies arrive as BLE notifications; see the log."
        Feature.CUT -> "The M02 has no cutter. Tear against the serrated edge."
        Feature.LABEL_GAP -> "The M02 takes continuous rolls (incl. continuous sticker paper); no gap sensor."
    }

    private class Out {
        val b = ByteArrayOutputStream()
        fun put(vararg v: Int) = v.forEach { b.write(it and 0xFF) }
        fun put(a: ByteArray) = b.write(a)
        fun bytes(): ByteArray = b.toByteArray()
    }

    private fun Out.header(o: JobOptions, align: Int = 1) {
        put(0x1B, 0x40)                         // ESC @ init
        put(0x1B, 0x61, align.coerceIn(0, 2))   // ESC a n
        put(0x1F, 0x11, 0x02, o.density.coerceIn(0, 255)) // Phomemo start / density
        o.speed?.let { put(0x1B, 0x4E, 0x0D, it.coerceIn(0, 255)) }
        o.heat?.let { put(0x1B, 0x37, it.maxDots.coerceIn(0, 255), it.heatTime.coerceIn(0, 255), it.interval.coerceIn(0, 255)) }
    }

    private fun Out.trailer(o: JobOptions) {
        put(0x1B, 0x64, o.feedLines.coerceIn(0, 255))
        if (o.trailer) {
            put(0x1B, 0x64, 0x02)
            put(0x1F, 0x11, 0x08)
            put(0x1F, 0x11, 0x0E)
            put(0x1F, 0x11, 0x07)
            put(0x1F, 0x11, 0x09)
        }
    }

    private fun Out.raster(packed: ByteArray, height: Int) {
        val bpr = widthDots / 8
        var row = 0
        while (row < height) {
            val rows = minOf(255, height - row)
            put(0x1D, 0x76, 0x30, 0x00, bpr and 0xFF, bpr shr 8, rows and 0xFF, rows shr 8)
            b.write(packed, row * bpr, rows * bpr)
            row += rows
        }
    }

    override fun headerHex(o: JobOptions): String = Out().apply { header(o) }.bytes().toHex()

    override fun imageJob(img: ProcessedImage, o: JobOptions): Cmd {
        val bpr = widthDots / 8
        if (img.packed.size != img.height * bpr) return Cmd.Unsupported("Image must be $widthDots dots wide")
        val top = o.topMarginDots.coerceIn(0, 4000)
        val bottom = o.bottomMarginDots.coerceIn(0, 4000)
        val h = top + img.height + bottom
        if (h <= 0) return Cmd.Unsupported("Nothing to print")
        val packed = ByteArray(h * bpr)  // margins are blank raster rows: works on any raster printer
        System.arraycopy(img.packed, 0, packed, top * bpr, img.packed.size)
        val out = Out()
        out.header(o)
        out.raster(packed, h)
        out.trailer(o)
        return Cmd.Bytes(out.bytes(), "image ${widthDots}×${img.height}")
    }

    override fun feedLines(n: Int): Cmd =
        Cmd.Bytes(byteArrayOf(0x1B, 0x40, 0x1B, 0x64, n.coerceIn(0, 255).toByte()), "ESC d $n")

    override fun feedDots(n: Int): Cmd {
        val out = Out()
        out.put(0x1B, 0x40)
        var left = n.coerceIn(0, 4000)
        while (left > 0) { val k = minOf(255, left); out.put(0x1B, 0x4A, k); left -= k }
        return Cmd.Bytes(out.bytes(), "ESC J $n dots")
    }

    override fun cut(): Cmd = Cmd.Unsupported(note(Feature.CUT))
    override fun labelGapDetect(): Cmd = Cmd.Unsupported(note(Feature.LABEL_GAP))

    override fun selfTest(): Cmd =
        Cmd.Bytes(byteArrayOf(0x1B, 0x40, 0x1D, 0x28, 0x41, 0x02, 0x00, 0x00, 0x02), "GS ( A self-test")

    override fun nativeText(text: String, style: NativeTextStyle, o: JobOptions): Cmd {
        val cs = runCatching { Charset.forName(style.codePage.charset) }.getOrDefault(Charsets.ISO_8859_1)
        val out = Out()
        out.header(o, style.align)
        out.put(0x1B, 0x74, style.codePage.n)                       // ESC t n code page
        var mode = 0
        if (style.bold) mode = mode or 0x08
        if (style.underline) mode = mode or 0x80
        out.put(0x1B, 0x21, mode)                                    // ESC ! n
        out.put(0x1B, 0x45, if (style.bold) 1 else 0)                // ESC E n
        out.put(0x1B, 0x2D, if (style.underline) 1 else 0)           // ESC - n
        out.put(0x1D, 0x42, if (style.inverted) 1 else 0)            // GS B n
        out.put(0x1B, 0x7B, if (style.upsideDown) 1 else 0)          // ESC { n
        out.put(0x1D, 0x21, ((style.scaleW.coerceIn(1, 8) - 1) shl 4) or (style.scaleH.coerceIn(1, 8) - 1))
        if (style.lineSpacing != null) out.put(0x1B, 0x33, style.lineSpacing.coerceIn(0, 255)) else out.put(0x1B, 0x32)
        out.put(text.replace("\r", "").toByteArray(cs))
        out.put(0x0A)
        out.trailer(o)
        return Cmd.Bytes(out.bytes(), "native text")
    }

    override fun nativeQr(text: String, moduleSize: Int, ecc: Int, o: JobOptions): Cmd {
        val data = text.toByteArray(Charsets.UTF_8)
        if (data.size > 700) return Cmd.Unsupported("Too long for a native QR test (max 700 bytes)")
        val out = Out()
        out.header(o)
        out.put(0x1D, 0x28, 0x6B, 0x04, 0x00, 0x31, 0x41, 0x32, 0x00)              // model 2
        out.put(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x43, moduleSize.coerceIn(1, 16)) // module size
        out.put(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x45, 0x30 + ecc.coerceIn(0, 3))  // ECC
        val len = data.size + 3
        out.put(0x1D, 0x28, 0x6B, len and 0xFF, len shr 8, 0x31, 0x50, 0x30)
        out.put(data)
        out.put(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x51, 0x30)                    // print
        out.put(0x0A)
        out.trailer(o)
        return Cmd.Bytes(out.bytes(), "native QR")
    }

    override fun nativeBarcode128(text: String, height: Int, moduleWidth: Int, o: JobOptions): Cmd {
        val data = text.toByteArray(Charsets.US_ASCII)
        if (data.isEmpty() || data.size > 250) return Cmd.Unsupported("Code 128 needs 1–250 ASCII characters")
        val out = Out()
        out.header(o)
        out.put(0x1D, 0x68, height.coerceIn(1, 255))      // GS h height
        out.put(0x1D, 0x77, moduleWidth.coerceIn(2, 6))   // GS w width
        out.put(0x1D, 0x48, 0x02)                         // HRI below
        out.put(0x1D, 0x6B, 73, data.size + 2, '{'.code, 'B'.code)
        out.put(data)
        out.put(0x0A)
        out.trailer(o)
        return Cmd.Bytes(out.bytes(), "native Code 128")
    }

    override fun queries(): List<Query> = listOf(
        Query("Phomemo 1F 11 08 (battery?)", byteArrayOf(0x1F, 0x11, 0x08)),
        Query("Phomemo 1F 11 0E (auto-off timer?)", byteArrayOf(0x1F, 0x11, 0x0E)),
        Query("Phomemo 1F 11 07 (firmware?)", byteArrayOf(0x1F, 0x11, 0x07)),
        Query("Phomemo 1F 11 09 (serial?)", byteArrayOf(0x1F, 0x11, 0x09)),
        Query("Phomemo 1F 11 11 (paper?)", byteArrayOf(0x1F, 0x11, 0x11)),
        Query("Phomemo 1F 11 12", byteArrayOf(0x1F, 0x11, 0x12)),
        Query("Phomemo 1F 11 13", byteArrayOf(0x1F, 0x11, 0x13)),
        Query("ESC/POS DLE EOT 1 (printer status)", byteArrayOf(0x10, 0x04, 0x01)),
        Query("ESC/POS DLE EOT 4 (paper sensor)", byteArrayOf(0x10, 0x04, 0x04)),
        Query("ESC/POS GS I 1 (model ID)", byteArrayOf(0x1D, 0x49, 0x01)),
        Query("ESC/POS GS I 67 (model name)", byteArrayOf(0x1D, 0x49, 0x43)),
        Query("ESC/POS GS r 1 (paper status)", byteArrayOf(0x1D, 0x72, 0x01)),
    )

    override fun parse(bytes: ByteArray): List<Pair<String, String>> {
        val facts = mutableListOf<Pair<String, String>>()
        var i = 0
        // Phomemo replies are usually 3-byte packets: 1A <kind> <value>, sometimes longer for strings.
        while (i + 2 < bytes.size && bytes[i] == 0x1A.toByte()) {
            val kind = bytes[i + 1].toInt() and 0xFF
            val v = bytes[i + 2].toInt() and 0xFF
            when (kind) {
                0x04 -> facts += "Battery" to "$v%"
                0x06 -> facts += "Paper" to when (v) { 0x88 -> "OK"; 0x89 -> "OUT"; else -> "0x%02X".format(v) }
                0x03 -> facts += "Cover/head" to "0x%02X".format(v)
                0x05 -> facts += "Cover" to "0x%02X".format(v)
                0x07 -> {
                    val rest = bytes.copyOfRange(i + 2, bytes.size)
                    facts += "Firmware" to rest.joinToString(".") { (it.toInt() and 0xFF).toString() }
                    return facts
                }
                0x08 -> {
                    val rest = bytes.copyOfRange(i + 2, bytes.size)
                    facts += "Serial" to String(rest, Charsets.US_ASCII).filter { it.code in 32..126 }.ifEmpty { rest.toHex() }
                    return facts
                }
                0x0E -> facts += "Auto-off" to "$v"
                0x0F -> facts += "Job" to if (v == 0x0C) "print complete" else "0x%02X".format(v)
                else -> facts += "1A %02X".format(kind) to "0x%02X".format(v)
            }
            i += 3
        }
        if (facts.isEmpty() && bytes.isNotEmpty()) {
            val ascii = String(bytes, Charsets.US_ASCII)
            if (ascii.all { it.code in 32..126 }) facts += "Text" to ascii
        }
        return facts
    }
}
