package com.adamryman.snapandprint

import android.content.Context
import androidx.core.content.edit

enum class DitherStyle(val label: String) {
    ATKINSON("Atkinson"),
    FLOYD_STEINBERG("Floyd–Steinberg"),
    THRESHOLD("Plain threshold"),
}

/** All user settings, persisted in SharedPreferences. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("snap_and_print", Context.MODE_PRIVATE)

    var brightness: Int
        get() = prefs.getInt("brightness", 10)
        set(v) = prefs.edit { putInt("brightness", v.coerceIn(-50, 50)) }

    var contrast: Int
        get() = prefs.getInt("contrast", 15)
        set(v) = prefs.edit { putInt("contrast", v.coerceIn(-50, 50)) }

    var dither: DitherStyle
        get() = runCatching { DitherStyle.valueOf(prefs.getString("dither", null)!!) }
            .getOrDefault(DitherStyle.ATKINSON)
        set(v) = prefs.edit { putString("dither", v.name) }

    var rotateWide: Boolean
        get() = prefs.getBoolean("rotate_wide", true)
        set(v) = prefs.edit { putBoolean("rotate_wide", v) }

    var feed: Int
        get() = prefs.getInt("feed", 60)
        set(v) = prefs.edit { putInt("feed", v.coerceIn(0, 255)) }

    var packetSize: Int
        get() = prefs.getInt("packet_size", 128)
        set(v) = prefs.edit { putInt("packet_size", v.coerceIn(20, 509)) }

    var packetDelayMs: Int
        get() = prefs.getInt("packet_delay", 20)
        set(v) = prefs.edit { putInt("packet_delay", v.coerceIn(0, 200)) }

    var saveToGallery: Boolean
        get() = prefs.getBoolean("save_gallery", false)
        set(v) = prefs.edit { putBoolean("save_gallery", v) }

    var printerAddress: String?
        get() = prefs.getString("printer_address", null)
        set(v) = prefs.edit { putString("printer_address", v) }

    var printerName: String?
        get() = prefs.getString("printer_name", null)
        set(v) = prefs.edit { putString("printer_name", v) }

    fun resetImageDefaults() {
        brightness = 10; contrast = 15; dither = DitherStyle.ATKINSON; rotateWide = true
        feed = 60; packetSize = 128; packetDelayMs = 20
    }
}
