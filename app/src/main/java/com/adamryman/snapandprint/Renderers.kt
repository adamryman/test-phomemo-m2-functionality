package com.adamryman.snapandprint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.roundToInt

enum class FontFamily(val label: String, val typeface: Typeface) {
    SANS("Sans", Typeface.SANS_SERIF),
    SERIF("Serif", Typeface.SERIF),
    MONO("Mono", Typeface.MONOSPACE),
}

data class BitmapTextStyle(
    val sizePx: Int = 28,
    val scaleW: Int = 1,
    val scaleH: Int = 1,
    val bold: Boolean = false,
    val underline: Boolean = false,
    val inverted: Boolean = false,
    val upsideDown: Boolean = false,
    val align: Int = 0,         // 0 left, 1 center, 2 right
    val lineSpacingPx: Int = 0,
    val family: FontFamily = FontFamily.SANS,
)

enum class CodeType(val label: String, val format: BarcodeFormat, val twoD: Boolean) {
    QR("QR", BarcodeFormat.QR_CODE, true),
    DATA_MATRIX("Data Matrix", BarcodeFormat.DATA_MATRIX, true),
    AZTEC("Aztec", BarcodeFormat.AZTEC, true),
    PDF417("PDF417", BarcodeFormat.PDF_417, true),
    CODE128("Code 128", BarcodeFormat.CODE_128, false),
    CODE39("Code 39", BarcodeFormat.CODE_39, false),
    EAN13("EAN-13", BarcodeFormat.EAN_13, false),
    EAN8("EAN-8", BarcodeFormat.EAN_8, false),
    UPCA("UPC-A", BarcodeFormat.UPC_A, false),
    ITF("ITF", BarcodeFormat.ITF, false),
    CODABAR("Codabar", BarcodeFormat.CODABAR, false),
}

/** Draws things onto 384-px-wide white bitmaps for the raster pipeline. */
object Renderers {
    private const val W = ImageProcessor.WIDTH
    const val DOTS_PER_MM = 8 // 203 dpi ≈ 7.99 dots/mm

    private fun blank(h: Int): Pair<Bitmap, Canvas> {
        val b = Bitmap.createBitmap(W, h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.WHITE)
        return b to c
    }

    fun text(text: String, st: BitmapTextStyle): Bitmap {
        val sw = st.scaleW.coerceIn(1, 8)
        val sh = st.scaleH.coerceIn(1, 8)
        val layoutWidth = (W / sw).coerceAtLeast(8)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = st.sizePx.toFloat()
            typeface = Typeface.create(st.family.typeface, if (st.bold) Typeface.BOLD else Typeface.NORMAL)
            isFakeBoldText = st.bold && st.family == FontFamily.MONO
            isUnderlineText = st.underline
        }
        val alignment = when (st.align) {
            1 -> Layout.Alignment.ALIGN_CENTER
            2 -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        val src = text.ifEmpty { " " }
        val layout = StaticLayout.Builder.obtain(src, 0, src.length, paint, layoutWidth)
            .setAlignment(alignment)
            .setLineSpacing(st.lineSpacingPx.toFloat(), 1f)
            .setIncludePad(true)
            .build()
        val (raw, c) = Bitmap.createBitmap(layoutWidth, layout.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            .let { it to Canvas(it) }
        c.drawColor(Color.WHITE)
        layout.draw(c)
        // Integer width/height multipliers, like GS ! on a real ESC/POS printer.
        val m = Matrix().apply { postScale(sw.toFloat(), sh.toFloat()) }
        if (st.upsideDown) m.postRotate(180f)
        val scaled = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, false)
        val (out, oc) = blank(scaled.height)
        val x = when (st.align) { 1 -> (W - scaled.width) / 2f; 2 -> (W - scaled.width).toFloat(); else -> 0f }
        oc.drawBitmap(scaled, x, 0f, null)
        if (st.inverted) invert(out)
        return out
    }

    private fun invert(b: Bitmap) {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        for (i in px.indices) px[i] = px[i] xor 0x00FFFFFF
        b.setPixels(px, 0, b.width, 0, 0, b.width, b.height)
    }

    /** One or more icons (emoji/symbols) in a row, centred. */
    fun icons(icons: List<String>, sizePx: Int, gapPx: Int = 8): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sizePx.toFloat(); color = Color.BLACK }
        val fm = paint.fontMetrics
        val h = (fm.descent - fm.ascent).roundToInt() + 4
        val widths = icons.map { paint.measureText(it) }
        val total = widths.sum() + gapPx * (icons.size - 1).coerceAtLeast(0)
        val (b, c) = blank(h)
        var x = ((W - total) / 2f).coerceAtLeast(0f)
        icons.forEachIndexed { i, s ->
            c.drawText(s, x, -fm.ascent + 2, paint)
            x += widths[i] + gapPx
        }
        return b
    }

    fun solid(heightDots: Int): Bitmap {
        val (b, c) = blank(heightDots)
        c.drawColor(Color.BLACK)
        return b
    }

    fun gradient(heightDots: Int = 96): Bitmap {
        val (b, c) = blank(heightDots)
        val p = Paint()
        for (x in 0 until W) {
            val v = (x * 255f / (W - 1)).roundToInt()
            p.color = Color.rgb(v, v, v)
            c.drawRect(x.toFloat(), 0f, x + 1f, heightDots.toFloat(), p)
        }
        return b
    }

    /** Checkerboards with 1, 2, 4, 8 and 16 dot squares. */
    fun checkerboard(): Bitmap {
        val sizes = intArrayOf(1, 2, 4, 8, 16)
        val band = 48
        val (b, c) = blank(sizes.size * (band + 6))
        val p = Paint().apply { color = Color.BLACK }
        sizes.forEachIndexed { i, s ->
            val y0 = i * (band + 6)
            var y = 0
            while (y < band) {
                var x = 0
                while (x < W) {
                    if (((x / s) + (y / s)) % 2 == 0) c.drawRect(x.toFloat(), (y0 + y).toFloat(), (x + s).toFloat(), (y0 + y + s).coerceAtMost(y0 + band).toFloat(), p)
                    x += s
                }
                y += s
            }
        }
        return b
    }

    /** Horizontal ruler across the head plus a vertical ruler [lengthMm] long; ticks every 1 mm, long every 10 mm. */
    fun ruler(lengthMm: Int = 60): Bitmap {
        val mm = DOTS_PER_MM
        val h = lengthMm * mm + 40
        val (b, c) = blank(h)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 16f }
        val line = Paint().apply { color = Color.BLACK }
        // Across the width (48 mm head).
        for (i in 0..(W / mm)) {
            val x = (i * mm).toFloat().coerceAtMost(W - 1f)
            val len = if (i % 10 == 0) 28f else if (i % 5 == 0) 18f else 10f
            c.drawRect(x, 0f, x + 1f, len, line)
            if (i % 10 == 0 && i > 0) c.drawText("${i / 10}cm", x + 3, 30f, p)
        }
        // Down the length.
        val top = 40
        for (i in 0..lengthMm) {
            val y = (top + i * mm).toFloat().coerceAtMost(h - 1f)
            val len = if (i % 10 == 0) 60f else if (i % 5 == 0) 40f else 20f
            c.drawRect(0f, y, len, y + 1f, line)
            if (i % 10 == 0) c.drawText("$i mm", 66f, y + 6, p)
        }
        c.drawText("1 mm = $mm dots @ 203 dpi", 140f, top + 30f, p)
        return b
    }

    /** Gradient + checkerboard + ruler on one strip. */
    fun testPattern(): Bitmap {
        val parts = listOf(
            text("TEST PATTERN  384 dots / 48 mm", BitmapTextStyle(sizePx = 22, bold = true, align = 1)),
            gradient(64), spacer(8), stepWedge(), spacer(8), checkerboard(), spacer(8), ruler(30),
        )
        return stack(parts)
    }

    fun stepWedge(steps: Int = 8, heightDots: Int = 40): Bitmap {
        val (b, c) = blank(heightDots)
        val p = Paint()
        val sw = W / steps
        for (s in 0 until steps) {
            val v = s * 255 / (steps - 1)
            p.color = Color.rgb(v, v, v)
            c.drawRect((s * sw).toFloat(), 0f, ((s + 1) * sw).toFloat(), heightDots.toFloat(), p)
        }
        return b
    }

    fun spacer(h: Int) = blank(h).first

    fun stack(parts: List<Bitmap>): Bitmap {
        val (b, c) = blank(parts.sumOf { it.height })
        var y = 0f
        for (p in parts) { c.drawBitmap(p, 0f, y, null); y += p.height }
        return b
    }

    fun statusPage(lines: List<Pair<String, String>>): Bitmap {
        val body = lines.joinToString("\n") { (k, v) -> "$k: $v" }
        return stack(listOf(
            text("STATUS", BitmapTextStyle(sizePx = 40, bold = true, align = 1)),
            text(body, BitmapTextStyle(sizePx = 20, family = FontFamily.MONO)),
        ))
    }

    /**
     * QR/barcode via ZXing. [module] = dots per module (bar width for 1D). [heightDots] for 1D codes.
     * Throws IllegalArgumentException with a readable message for invalid content.
     */
    fun code(type: CodeType, content: String, module: Int, heightDots: Int, ecc: Int = 1): Bitmap {
        require(content.isNotEmpty()) { "Enter some text first" }
        val hints = mutableMapOf<EncodeHintType, Any>(EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to "UTF-8")
        if (type == CodeType.QR) hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.entries[ecc.coerceIn(0, 3)]
        val matrix = try {
            // Size 1 gives us the minimal matrix (one cell per module); we scale ourselves for crisp dots.
            MultiFormatWriter().encode(content, type.format, 1, 1, hints)
        } catch (e: Exception) {
            throw IllegalArgumentException("${type.label}: ${e.message ?: "can't encode this text"}")
        }
        val m = module.coerceIn(1, 16)
        val cw = matrix.width * m
        require(cw <= W) { "${type.label} is ${cw} dots wide at size $m — max $W. Lower the size or shorten the text." }
        val ch = if (type.twoD) matrix.height * m else heightDots.coerceIn(8, 800)
        val label = !type.twoD
        val (b, c) = blank(ch + if (label) 26 else 0)
        val p = Paint().apply { color = Color.BLACK }
        val x0 = (W - cw) / 2
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (!matrix[x, y]) continue
            val top = if (type.twoD) y * m else 0
            val bottom = if (type.twoD) top + m else ch
            c.drawRect((x0 + x * m).toFloat(), top.toFloat(), (x0 + (x + 1) * m).toFloat(), bottom.toFloat(), p)
        }
        if (label) {
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 20f; textAlign = Paint.Align.CENTER; typeface = Typeface.MONOSPACE }
            c.drawText(content, W / 2f, ch + 21f, tp)
        }
        return b
    }

    /** Built-in icons for the picker (symbols + emoji, rendered by the system font). */
    val ICONS = listOf(
        "★", "♥", "☀", "☂", "☕", "✈", "⚡", "✔", "✖", "☺", "♪", "☎", "✉", "⚙", "⌂", "♻",
        "🐱", "🐶", "🌲", "🍕", "🎉", "🔥", "💡", "📦", "🚲", "🌙", "🍀", "🎂", "👍", "😀", "🦆", "🚀",
    )
}
