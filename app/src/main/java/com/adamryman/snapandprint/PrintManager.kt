package com.adamryman.snapandprint

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "SnapPrint"

sealed class PrinterStatus {
    data object NoPrinter : PrinterStatus()
    data object Disconnected : PrinterStatus()
    data object Connecting : PrinterStatus()
    data class Ready(val name: String) : PrinterStatus()
    data class Printing(val progress: Float) : PrinterStatus()
    data class Error(val message: String, val bluetoothOff: Boolean = false) : PrinterStatus()
}

private class PrintJob(val data: ByteArray)

/** Owns the printer connection and the print queue. Lives for the whole process. */
class PrintManager(private val context: Context) {
    val settings = AppSettings(context)
    private val printer = BlePrinter(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Single thread so photos are processed (and therefore printed) in shutter order. */
    private val processing = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val processingCount = AtomicInteger(0)

    private val queue = ArrayDeque<PrintJob>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val connectMutex = Mutex()
    @Volatile private var printing = false

    private val _status = MutableStateFlow<PrinterStatus>(
        if (settings.printerAddress == null) PrinterStatus.NoPrinter else PrinterStatus.Disconnected
    )
    val status: StateFlow<PrinterStatus> = _status.asStateFlow()

    private val _pending = MutableStateFlow(0)
    /** Photos waiting: being processed + queued + currently printing. */
    val pending: StateFlow<Int> = _pending.asStateFlow()

    private val _thumbnail = MutableStateFlow<Bitmap?>(null)
    val thumbnail: StateFlow<Bitmap?> = _thumbnail.asStateFlow()

    private val _devices = MutableStateFlow<List<FoundDevice>>(emptyList())
    val devices: StateFlow<List<FoundDevice>> = _devices.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError.asStateFlow()
    private var scanTimeout: Job? = null

    val hasPrinter: Boolean get() = settings.printerAddress != null

    init {
        printer.onDisconnected = {
            Log.i(TAG, "printer disconnected")
            if (!printing) setIdleStatus()
        }
        scope.launch { worker() }
    }

    private fun updatePending() {
        val q = synchronized(queue) { queue.size }
        _pending.value = processingCount.get() + q
    }

    private fun setIdleStatus() {
        _status.value = when {
            settings.printerAddress == null -> PrinterStatus.NoPrinter
            printer.isReady -> PrinterStatus.Ready(settings.printerName ?: "printer")
            else -> PrinterStatus.Disconnected
        }
    }

    // ---- Submitting photos ----

    /** A frame from the camera. [rotationDegrees] comes from the ImageProxy (EXIF-equivalent). */
    fun submitBitmap(src: Bitmap, rotationDegrees: Int) {
        processingCount.incrementAndGet(); updatePending()
        val s = settings
        scope.launch(processing) {
            try {
                if (s.saveToGallery) saveColorPhoto(src, rotationDegrees)
                val prepared = ImageProcessor.prepare(src, rotationDegrees, false, s.rotateWide)
                enqueueProcessed(prepared)
            } catch (t: Throwable) {
                Log.e(TAG, "processing failed", t)
                _status.value = PrinterStatus.Error("Couldn't process photo")
            } finally {
                processingCount.decrementAndGet(); updatePending()
            }
        }
    }

    /** A photo picked from the gallery. */
    fun submitUri(uri: Uri) {
        processingCount.incrementAndGet(); updatePending()
        scope.launch(processing) {
            try {
                val (bmp, rot, flip) = decodeUri(uri)
                val prepared = ImageProcessor.prepare(bmp, rot, flip, settings.rotateWide)
                enqueueProcessed(prepared)
            } catch (t: Throwable) {
                Log.e(TAG, "gallery decode failed", t)
                _status.value = PrinterStatus.Error("Couldn't open that photo")
            } finally {
                processingCount.decrementAndGet(); updatePending()
            }
        }
    }

    fun testPrint() {
        processingCount.incrementAndGet(); updatePending()
        scope.launch(processing) {
            try { enqueueProcessed(ImageProcessor.testCard()) }
            finally { processingCount.decrementAndGet(); updatePending() }
        }
    }

    private fun enqueueProcessed(prepared: Bitmap) {
        val s = settings
        val img = ImageProcessor.process(prepared, s.brightness, s.contrast, s.dither)
        _thumbnail.value = img.preview
        val data = ImageProcessor.buildPrintStream(img, s.feed)
        synchronized(queue) { queue.addLast(PrintJob(data)) }
        updatePending()
        wake.trySend(Unit)
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
            val ok = try {
                printer.write(job.data, settings.packetSize, settings.packetDelayMs) { p ->
                    _status.value = PrinterStatus.Printing(p)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "print failed", t); false
            }
            printing = false
            if (ok) {
                synchronized(queue) { if (queue.firstOrNull() === job) queue.removeFirst() }
                updatePending()
                setIdleStatus()
                // Let the print head catch up before streaming the next photo.
                if (synchronized(queue) { queue.isNotEmpty() }) delay(1_000)
            } else {
                Log.w(TAG, "print failed; keeping job in queue")
                printer.disconnect()
                _status.value = PrinterStatus.Error("Printer off? Tap to reconnect")
                wake.receive()
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

    private fun saveColorPhoto(src: Bitmap, rotationDegrees: Int) {
        try {
            val bmp = if (rotationDegrees % 360 != 0) {
                val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            } else src
            val name = "snap_${System.currentTimeMillis()}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SnapAndPrint")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val cr = context.contentResolver
            val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            cr.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            if (Build.VERSION.SDK_INT >= 29) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                cr.update(uri, values, null, null)
            }
            if (bmp !== src) bmp.recycle()
        } catch (t: Throwable) {
            Log.w(TAG, "save to gallery failed", t)
        }
    }
}
