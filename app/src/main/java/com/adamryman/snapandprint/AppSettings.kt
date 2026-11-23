package com.adamryman.snapandprint

import android.content.Context
import androidx.core.content.edit

enum class DitherStyle(val label: String) {
    ATKINSON("Atkinson"),
    FLOYD_STEINBERG("Floyd–Steinberg"),
    THRESHOLD("Plain threshold"),
    ORDERED("Ordered (Bayer)"),
}

/** All user settings, persisted in SharedPreferences. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("phomemo_debug", Context.MODE_PRIVATE)

    var packetSize: Int
        get() = prefs.getInt("packet_size", 128)
        set(v) = prefs.edit { putInt("packet_size", v.coerceIn(20, 509)) }

    var packetDelayMs: Int
        get() = prefs.getInt("packet_delay", 20)
        set(v) = prefs.edit { putInt("packet_delay", v.coerceIn(0, 200)) }

    var printerAddress: String?
        get() = prefs.getString("printer_address", null)
        set(v) = prefs.edit { putString("printer_address", v) }

    var printerName: String?
        get() = prefs.getString("printer_name", null)
        set(v) = prefs.edit { putString("printer_name", v) }

    fun resetConnectionDefaults() {
        packetSize = 128; packetDelayMs = 20
    }
}
