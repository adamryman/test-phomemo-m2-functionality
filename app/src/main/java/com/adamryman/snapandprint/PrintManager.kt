package com.adamryman.snapandprint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import kotlin.math.roundToInt

private const val TAG = "SnapPrint"

sealed class PrinterStatus {
    data object NoPrinter : PrinterStatus()
    data object Disconnected : PrinterStatus()
    data object Connecting : PrinterStatus()
    data class Ready(val name: String) : PrinterStatus()
    data class Printing(val progress: Float) : PrinterStatus()
    data class Error(val message: String, val bluetoothOff: Boolean = false) : PrinterStatus()
}

/**
 * One unit of work for the printer. Either [data] is streamed, or [run] does custom GATT work
 * (e.g. the probe). Jobs are executed strictly one at a time, in order.
 */
private class PrintJob(
    val label: String,
    val data: ByteArray,
    val run: (suspend () -> Boolean)? = null,
)

enum class LogDir(val arrow: String) { TX("→"), RX("←"), INFO("•") }

data class LogEntry(val timeMs: Long, val dir: LogDir, val text: String)

data class JobResult(val label: String, val bytes: Int, val millis: Long, val ok: Boolean, val atMs: Long) {
    val kbPerSec: Float get() = if (millis > 0) bytes / 1024f / (millis / 1000f) else 0f
}

/** Owns the printer connection and the print queue. Lives for the whole process. */
class PrintManager(private val context: Context) {
    val settings = AppSettings(context)
    private val printer = BlePrinter(context)
    val protocol: PrinterProtocol = PhomemoM02
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val queue = ArrayDeque<PrintJob>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val connectMutex = Mutex()
    @Volatile private var printing = false

    private val _status = MutableStateFlow<PrinterStatus>(
        if (settings.printerAddress == null) PrinterStatus.NoPrinter else PrinterStatus.Disconnected
    )
    val status: StateFlow<PrinterStatus> = _status.asStateFlow()

    private val _pending = MutableStateFlow(0)
    /** Jobs waiting: queued + currently printing. */
    val pending: StateFlow<Int> = _pending.asStateFlow()

    private val _devices = MutableStateFlow<List<FoundDevice>>(emptyList())
    val devices: StateFlow<List<FoundDevice>> = _devices.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError.asStateFlow()
    private var scanTimeout: Job? = null

    val hasPrinter: Boolean get() = settings.printerAddress != null

    // ---- Diagnostics state (Test Lab) ----

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    private val _facts = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Parsed printer replies: battery, paper, firmware… */
    val facts: StateFlow<Map<String, String>> = _facts.asStateFlow()

    private val _results = MutableStateFlow<List<JobResult>>(emptyList())
    val results: StateFlow<List<JobResult>> = _results.asStateFlow()

    val connectionInfo: List<Pair<String, String>>
        get() = listOf(
            "Printer" to (settings.printerName ?: "none"),
            "Address" to (printer.address ?: settings.printerAddress ?: "—"),
            "Link" to if (printer.isReady) "connected" else "not connected",
            "MTU" to "${printer.mtu} (payload ${printer.mtu - 3})",
            "Write char" to (printer.writeUuid?.toString()?.substring(4, 8) ?: "—") +
                if (printer.writeNoResponse) " (no-response)" else " (with response)",
            "Notify chars" to printer.notifyUuids.joinToString { it.toString().substring(4, 8) }.ifEmpty { "—" },
            "Packet / delay" to "${settings.packetSize} B / ${settings.packetDelayMs} ms",
        )
    val gattTable: List<String> get() = printer.gattTable

    fun addLog(dir: LogDir, text: String) {
        val e = LogEntry(System.currentTimeMillis(), dir, text)
        _log.update { (it + e).takeLast(600) }
    }

    fun clearLog() { _log.value = emptyList() }

    init {
        printer.onDisconnected = {
            Log.i(TAG, "printer disconnected")
            if (!printing) setIdleStatus()
        }
        printer.onInfo = { addLog(LogDir.INFO, it) }
        printer.onReceive = { uuid, bytes ->
            val facts = protocol.parse(bytes)
            val meaning = if (facts.isEmpty()) "" else "  = " + facts.joinToString { "${it.first}: ${it.second}" }
            addLog(LogDir.RX, "[${uuid.toString().substring(4, 8)}] ${bytes.toHex(64)}$meaning")
            if (facts.isNotEmpty()) _facts.update { it + facts }
        }
        scope.launch { worker() }
    }

    private fun updatePending() {
        val q = synchronized(queue) { queue.size }
        _pending.value = q
    }

    private fun setIdleStatus() {
        _status.value = when {
            settings.printerAddress == null -> PrinterStatus.NoPrinter
            printer.isReady -> PrinterStatus.Ready(settings.printerName ?: "printer")
            else -> PrinterStatus.Disconnected
        }
    }

    // ---- Test Lab ----

    /** Queues a protocol command. Returns null if queued, or the reason it can't be sent. */
    fun submitCommand(label: String, cmd: Cmd): String? = when (cmd) {
        is Cmd.Unsupported -> {
            addLog(LogDir.INFO, "$label: not sent — ${cmd.reason}")
            cmd.reason
        }
        is Cmd.Bytes -> {
            synchronized(queue) { queue.addLast(PrintJob(label, cmd.data)) }
            updatePending()
            wake.trySend(Unit)
            null
        }
    }

    /** Sends each status query separately, collects notifications, then reads readable chars. */
    fun probe() {
        val queries = protocol.queries()
        val job = PrintJob("probe", ByteArray(0)) {
            for (q in queries) {
                addLog(LogDir.TX, "${q.label}: ${q.bytes.toHex()}")
                if (!printer.write(q.bytes, settings.packetSize, 0) {}) return@PrintJob false
                delay(450) // give the printer time to answer before the next query
            }
            for ((uuid, value) in printer.readAll()) {
                val short = uuid.toString().substring(4, 8)
                if (value == null) addLog(LogDir.RX, "read [$short] failed")
                else {
                    val ascii = String(value, Charsets.US_ASCII).filter { it.code in 32..126 }
                    addLog(LogDir.RX, "read [$short] ${value.toHex(48)}" + if (ascii.length >= 2) "  \"$ascii\"" else "")
                    val shown = if (short == "2a19" && value.isNotEmpty()) "${value[0].toInt() and 0xFF}%" else ascii
                    knownCharName(short)?.let { name -> if (shown.isNotEmpty()) _facts.update { it + (name to shown) } }
                }
            }
            true
        }
        synchronized(queue) { queue.addLast(job) }
        updatePending()
        wake.trySend(Unit)
    }

    private fun knownCharName(short: String) = when (short.lowercase()) {
        "2a00" -> "Device name"; "2a24" -> "Model"; "2a25" -> "Serial (DIS)"; "2a26" -> "Firmware (DIS)"
        "2a27" -> "Hardware"; "2a28" -> "Software"; "2a29" -> "Manufacturer"; "2a19" -> "Battery (BAS)"
        else -> null
    }

    fun disconnectNow() {
        scope.launch {
            connectMutex.withLock { printer.disconnect() }
            addLog(LogDir.INFO, "disconnected by user")
            setIdleStatus()
        }
    }

    /** Decodes a picked/captured photo to a 384-px-wide bitmap (off the main thread). */
    fun prepareUri(uri: Uri, rotateWide: Boolean): Bitmap {
        val (bmp, rot, flip) = decodeUri(uri)
        return ImageProcessor.prepare(bmp, rot, flip, rotateWide)
    }

    fun clearQueue() {
        synchronized(queue) {
            // Keep the job that's currently being sent; drop the rest.
            if (printing && queue.isNotEmpty()) {
                val cur = queue.first(); queue.clear(); queue.addLast(cur)
            } else queue.clear()
        }
        updatePending()
        if (!printing && _status.value is PrinterStatus.Error) setIdleStatus()
    }

    // ---- Worker ----

    private suspend fun worker() {
        while (true) {
            val job = synchronized(queue) { queue.firstOrNull() }
            if (job == null) { wake.receive(); continue }
            if (!ensureConnected()) {
                // Keep the queue; wait for a reconnect tap or a new photo.
                wake.receive(); continue
            }
            printing = true
            _status.value = PrinterStatus.Printing(0f)
            if (job.run == null) addLog(LogDir.TX, "${job.label}: ${job.data.toHex(48)}")
            val started = System.currentTimeMillis()
            var nextMark = 0.25f
            val ok = try {
                job.run?.invoke() ?: printer.write(job.data, settings.packetSize, settings.packetDelayMs) { p ->
                    _status.value = PrinterStatus.Printing(p)
                    if (job.data.size > 20_000 && p >= nextMark) {
                        nextMark += 0.25f
                        // Diagnostics only: must never abort the transfer.
                        runCatching {
                            val ms = System.currentTimeMillis() - started
                            val kbs = job.data.size * p / 1024f / (ms.coerceAtLeast(1) / 1000f)
                            val rate = String.format(Locale.US, "%.1f", kbs)
                            addLog(LogDir.INFO, "${job.label}: ${(p * 100).roundToInt()}% after $ms ms ($rate KB/s)")
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "print failed", t); false
            }
            val elapsed = System.currentTimeMillis() - started
            printing = false
            val result = JobResult(job.label, job.data.size, elapsed, ok, System.currentTimeMillis())
            _results.update { (listOf(result) + it).take(50) }
            addLog(LogDir.INFO, "${job.label}: ${if (ok) "sent" else "FAILED"} in $elapsed ms")
            // Always take the job off the queue. A failed job is dropped (and logged) rather than
            // retried, so one bad job can never block or be replayed in front of later ones.
            synchronized(queue) { if (queue.firstOrNull() === job) queue.removeFirst() }
            updatePending()
            if (ok) {
                setIdleStatus()
                // Let the print head catch up before streaming the next job.
                if (synchronized(queue) { queue.isNotEmpty() }) delay(1_000)
            } else {
                Log.w(TAG, "job '${job.label}' failed; dropped")
                printer.disconnect()
                _status.value = PrinterStatus.Error("${job.label} failed · tap to reconnect")
                // Remaining jobs wait for a reconnect tap or a new job.
                if (synchronized(queue) { queue.isNotEmpty() }) wake.receive()
            }
        }
    }

    /** Connects to the saved printer if needed. Sets status. Safe to call concurrently. */
    private suspend fun ensureConnected(): Boolean = connectMutex.withLock {
        if (printer.isReady) return@withLock true
        val addr = settings.printerAddress
        if (addr == null) { _status.value = PrinterStatus.NoPrinter; return@withLock false }
        if (!printer.isBluetoothOn) {
            _status.value = PrinterStatus.Error("Bluetooth is off · tap to turn on", bluetoothOff = true)
            return@withLock false
        }
        _status.value = PrinterStatus.Connecting
        var ok = false
        for (attempt in 0 until 2) {
            ok = try { printer.connect(addr) } catch (t: Throwable) { Log.e(TAG, "connect", t); false }
            if (ok) break
            delay(1_000)
        }
        if (ok) _status.value = PrinterStatus.Ready(settings.printerName ?: "printer")
        else _status.value = PrinterStatus.Error("Printer off? Tap to reconnect")
        ok
    }

    /** Reconnect (if needed) and resume the queue. */
    fun reconnect() {
        scope.launch {
            if (ensureConnected()) wake.trySend(Unit)
        }
    }

    // ---- Printer selection ----

    fun startScan() {
        _devices.value = emptyList()
        _scanError.value = null
        if (!printer.isBluetoothOn) { _scanError.value = "Bluetooth is off"; return }
        val err = printer.startScan { d ->
            _devices.update { list ->
                val i = list.indexOfFirst { it.address == d.address }
                if (i < 0) list + d
                else list.toMutableList().also { it[i] = d.copy(name = d.name ?: it[i].name) }
            }
        }
        if (err != null) { _scanError.value = err; return }
        _scanning.value = true
        scanTimeout?.cancel()
        scanTimeout = scope.launch { delay(20_000); stopScan() }
    }

    fun stopScan() {
        scanTimeout?.cancel(); scanTimeout = null
        printer.stopScan()
        _scanning.value = false
    }

    fun selectPrinter(d: FoundDevice) {
        stopScan()
        scope.launch {
            connectMutex.withLock {
                printer.disconnect()
                settings.printerAddress = d.address
                settings.printerName = d.name ?: d.address
            }
            if (ensureConnected()) wake.trySend(Unit)
        }
    }

    fun forgetPrinter() {
        scope.launch {
            connectMutex.withLock {
                printer.disconnect()
                settings.printerAddress = null
                settings.printerName = null
                _status.value = PrinterStatus.NoPrinter
            }
        }
    }

    fun reportError(msg: String) { _status.value = PrinterStatus.Error(msg) }

    // ---- Helpers ----

    private fun decodeUri(uri: Uri): Triple<Bitmap, Int, Boolean> {
        val cr = context.contentResolver
        var rot = 0
        var flip = false
        runCatching {
            cr.openInputStream(uri)?.use { s ->
                val exif = ExifInterface(s)
                rot = exif.rotationDegrees
                flip = exif.isFlipped
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val minSide = minOf(bounds.outWidth, bounds.outHeight)
        while (minSide / (sample * 2) >= ImageProcessor.WIDTH * 2) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("decode failed")
        return Triple(bmp, rot, flip)
    }
}
