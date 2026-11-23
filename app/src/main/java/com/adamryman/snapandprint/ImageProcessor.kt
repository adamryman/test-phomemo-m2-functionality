package com.adamryman.snapandprint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

/** Result of the image pipeline: packed 1-bit rows ready for the printer, plus a preview bitmap. */
class ProcessedImage(val packed: ByteArray, val height: Int, val preview: Bitmap)

object ImageProcessor {
    const val WIDTH = 384          // M02 print head: 384 dots @ 203 dpi
    const val BYTES_PER_ROW = 48   // 384 / 8

    /**
     * Applies orientation (rotation + optional mirror), optionally rotates landscape images 90°
     * so they fill the paper's length, and scales to exactly 384 px wide (aspect preserved).
     */
    fun prepare(src: Bitmap, rotationDegrees: Int, flipped: Boolean, rotateWide: Boolean): Bitmap {
        var bmp = src
        // Cheap progressive halving first so the final bilinear scale doesn't alias.
        while (min(bmp.width, bmp.height) / 2 >= WIDTH * 2) {
            val next = Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
            if (bmp !== src) bmp.recycle()
            bmp = next
        }
        val rot = ((rotationDegrees % 360) + 360) % 360
        var w = if (rot == 90 || rot == 270) bmp.height else bmp.width
        var h = if (rot == 90 || rot == 270) bmp.width else bmp.height
        var total = rot
        if (rotateWide && w > h) {
            total = (total + 90) % 360
            val t = w; w = h; h = t
        }
        val scale = WIDTH.toFloat() / w
        val m = Matrix()
        if (flipped) m.postScale(-1f, 1f)
        m.postRotate(total.toFloat())
        m.postScale(scale, scale)
        var out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out.width != WIDTH) {
            val outH = (h * scale).roundToInt().coerceAtLeast(1)
            val fixed = Bitmap.createScaledBitmap(out, WIDTH, outH, true)
            if (out !== bmp && out !== src) out.recycle()
            out = fixed
        }
        if (bmp !== src && bmp !== out) bmp.recycle()
        if (out.config != Bitmap.Config.ARGB_8888) {
            out = out.copy(Bitmap.Config.ARGB_8888, false)
        }
        return out
    }

    /** Grayscale → auto-levels → brightness/contrast → dither → pack. Input must be 384 px wide. */
    fun process(bmp: Bitmap, brightness: Int, contrast: Int, dither: DitherStyle): ProcessedImage {
        require(bmp.width == WIDTH) { "expected $WIDTH px wide, got ${bmp.width}" }
        val w = bmp.width
        val h = bmp.height
        val n = w * h
        val px = IntArray(n)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        // 1. Grayscale, compositing transparency onto white.
        val g = FloatArray(n)
        for (i in 0 until n) {
            val p = px[i]
            val a = (p ushr 24) and 0xFF
            val r = (p shr 16) and 0xFF
            val gr = (p shr 8) and 0xFF
            val b = p and 0xFF
            var y = 0.299f * r + 0.587f * gr + 0.114f * b
            if (a < 255) {
                val af = a / 255f
                y = y * af + 255f * (1f - af)
            }
            g[i] = y
        }

        // 2. Auto-levels: stretch 1st..99th percentile to 0..255 (skip if range < 16).
        val hist = IntArray(256)
        for (i in 0 until n) hist[g[i].roundToInt().coerceIn(0, 255)]++
        val loTarget = n * 0.01
        val hiTarget = n * 0.99
        var lo = 0
        var hi = 255
        var cum = 0L
        var foundLo = false
        for (v in 0..255) {
            cum += hist[v]
            if (!foundLo && cum >= loTarget) { lo = v; foundLo = true }
            if (cum >= hiTarget) { hi = v; break }
        }
        if (hi - lo >= 16) {
            val k = 255f / (hi - lo)
            for (i in 0 until n) g[i] = ((g[i] - lo) * k).coerceIn(0f, 255f)
        }

        // 3. Brightness / contrast.
        val c = contrast * 2.55f
        val f = (259f * (c + 255f)) / (255f * (259f - c))
        val bOff = brightness * 2.55f
        for (i in 0 until n) g[i] = ((g[i] - 128f) * f + 128f + bOff).coerceIn(0f, 255f)

        // 4. Dither to 1 bit. black[i] = true means a printed dot.
        val black = BooleanArray(n)
        when (dither) {
            DitherStyle.THRESHOLD -> for (i in 0 until n) black[i] = g[i] < 128f
            DitherStyle.FLOYD_STEINBERG -> {
                for (y in 0 until h) for (x in 0 until w) {
                    val i = y * w + x
                    val old = g[i]
                    val isBlack = old < 128f
                    black[i] = isBlack
                    val err = old - (if (isBlack) 0f else 255f)
                    if (x + 1 < w) g[i + 1] += err * 7f / 16f
                    if (y + 1 < h) {
                        if (x > 0) g[i + w - 1] += err * 3f / 16f
                        g[i + w] += err * 5f / 16f
                        if (x + 1 < w) g[i + w + 1] += err * 1f / 16f
                    }
                }
            }
            DitherStyle.ATKINSON -> {
                for (y in 0 until h) for (x in 0 until w) {
                    val i = y * w + x
                    val old = g[i]
                    val isBlack = old < 128f
                    black[i] = isBlack
                    val e = (old - (if (isBlack) 0f else 255f)) / 8f
                    if (x + 1 < w) g[i + 1] += e
                    if (x + 2 < w) g[i + 2] += e
                    if (y + 1 < h) {
                        if (x > 0) g[i + w - 1] += e
                        g[i + w] += e
                        if (x + 1 < w) g[i + w + 1] += e
                    }
                    if (y + 2 < h) g[i + 2 * w] += e
                }
            }
        }

        // 5. Pack: 48 bytes/row, MSB first, 1 = black. Also build the preview.
        val packed = ByteArray(BYTES_PER_ROW * h)
        val prev = IntArray(n)
        for (y in 0 until h) {
            val rowOff = y * BYTES_PER_ROW
            for (x in 0 until w) {
                val i = y * w + x
                if (black[i]) {
                    val bi = rowOff + (x shr 3)
                    packed[bi] = (packed[bi].toInt() or (0x80 ushr (x and 7))).toByte()
                    prev[i] = Color.BLACK
                } else {
                    prev[i] = Color.WHITE
                }
            }
        }
        val preview = Bitmap.createBitmap(prev, w, h, Bitmap.Config.ARGB_8888)
        return ProcessedImage(packed, h, preview)
    }

    /** ESC/POS raster stream for the Phomemo M02. */
    fun buildPrintStream(img: ProcessedImage, feed: Int): ByteArray {
        val out = ByteArrayOutputStream(img.packed.size + 64)
        fun put(vararg b: Int) = b.forEach { out.write(it and 0xFF) }
        put(0x1B, 0x40)             // ESC @  init
        put(0x1B, 0x61, 0x01)       // ESC a 1 center
        put(0x1F, 0x11, 0x02, 0x04) // Phomemo start
        var row = 0
        while (row < img.height) {
            val rows = min(255, img.height - row)
            put(0x1D, 0x76, 0x30, 0x00)                  // GS v 0, mode 0
            put(BYTES_PER_ROW and 0xFF, BYTES_PER_ROW shr 8) // width in bytes (LE)
            put(rows and 0xFF, rows shr 8)               // height in rows (LE)
            out.write(img.packed, row * BYTES_PER_ROW, rows * BYTES_PER_ROW)
            row += rows
        }
        put(0x1B, 0x64, feed.coerceIn(0, 255)) // ESC d n feed
        put(0x1B, 0x64, 0x02)
        put(0x1F, 0x11, 0x08)
        put(0x1F, 0x11, 0x0E)
        put(0x1F, 0x11, 0x07)
        put(0x1F, 0x11, 0x09)
        return out.toByteArray()
    }

    /** A 384-px-wide test card: border, text, gray ramp, and fine patterns. */
    fun testCard(): Bitmap {
        val w = WIDTH
        val h = 420
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        p.style = Paint.Style.STROKE; p.strokeWidth = 4f
        cv.drawRect(2f, 2f, w - 2f, h - 2f, p)
        p.style = Paint.Style.FILL
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        p.textSize = 44f; p.textAlign = Paint.Align.CENTER
        cv.drawText("Snap & Print", w / 2f, 62f, p)
        p.typeface = Typeface.DEFAULT; p.textSize = 22f
        cv.drawText("Phomemo M02 test print", w / 2f, 96f, p)
        // Gray ramp
        val rampTop = 120; val rampH = 60
        for (x in 12 until w - 12) {
            val v = ((x - 12) * 255f / (w - 25)).roundToInt().coerceIn(0, 255)
            p.color = Color.rgb(v, v, v)
            cv.drawRect(x.toFloat(), rampTop.toFloat(), x + 1f, (rampTop + rampH).toFloat(), p)
        }
        // Gray steps
        p.color = Color.BLACK
        val steps = 8; val sw = (w - 24) / steps
        for (s in 0 until steps) {
            val v = s * 255 / (steps - 1)
            p.color = Color.rgb(v, v, v)
            cv.drawRect(12f + s * sw, 196f, 12f + (s + 1) * sw, 246f, p)
        }
        // Fine lines (1px / 2px / 4px)
        p.color = Color.BLACK
        var x = 12
        for (spacing in intArrayOf(2, 4, 8)) {
            val end = x + 110
            while (x < end) { cv.drawRect(x.toFloat(), 262f, x + spacing / 2f, 322f, p); x += spacing }
            x += 6
        }
        // Circle + footer
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f
        cv.drawCircle(w / 2f, 362f, 26f, p)
        p.style = Paint.Style.FILL; p.textSize = 18f
        cv.drawText("If this looks crisp, you're good.", w / 2f, 408f, p)
        return bmp
    }
}
