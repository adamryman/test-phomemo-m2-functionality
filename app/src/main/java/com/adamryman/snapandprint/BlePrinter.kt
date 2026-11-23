package com.adamryman.snapandprint

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.math.min

private const val TAG = "SnapPrint"

data class FoundDevice(val address: String, val name: String?, val rssi: Int)

/**
 * Minimal BLE GATT client for the Phomemo M02. All suspend functions must be called from one
 * coroutine at a time (PrintManager serialises them with a mutex).
 */
@SuppressLint("MissingPermission")
class BlePrinter(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
        val WRITE_UUID: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val NAME_PREFIXES = listOf("Mr.in", "M02", "M0", "T0", "Phomemo")
        fun looksLikePrinter(name: String?) =
            name != null && NAME_PREFIXES.any { name.startsWith(it, ignoreCase = true) }
    }

    private val manager = context.getSystemService(BluetoothManager::class.java)
    val adapter: BluetoothAdapter? get() = manager?.adapter
    val isBluetoothOn: Boolean get() = runCatching { adapter?.isEnabled == true }.getOrDefault(false)

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile var mtu: Int = 23; private set
    @Volatile var isReady: Boolean = false; private set

    /** Called (on a binder thread) whenever an established link drops. */
    var onDisconnected: (() -> Unit)? = null
    /** Notifications/indications from the printer (binder thread). */
    var onReceive: ((UUID, ByteArray) -> Unit)? = null
    /** Human-readable connection events for the diagnostics log. */
    var onInfo: ((String) -> Unit)? = null

    @Volatile var address: String? = null; private set
    @Volatile var writeUuid: UUID? = null; private set
    @Volatile var writeNoResponse: Boolean = false; private set
    @Volatile var notifyUuids: List<UUID> = emptyList(); private set
    @Volatile var gattTable: List<String> = emptyList(); private set

    @Volatile private var descWait: CompletableDeferred<Int>? = null
    @Volatile private var readWait: CompletableDeferred<ByteArray?>? = null

    private fun info(msg: String) { Log.i(TAG, msg); onInfo?.invoke(msg) }

    @Volatile private var connectWait: CompletableDeferred<Boolean>? = null
    @Volatile private var servicesWait: CompletableDeferred<Boolean>? = null
    @Volatile private var mtuWait: CompletableDeferred<Int>? = null
    @Volatile private var writeWait: CompletableDeferred<Int>? = null
    /** Some stacks never call back for write-without-response; we detect that and stop waiting. */
    @Volatile private var noResponseCallbacksWork = true

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "connection state: status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connectWait?.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val wasReady = isReady
                isReady = false
                connectWait?.complete(false)
                servicesWait?.complete(false)
                mtuWait?.complete(23)
                writeWait?.complete(-1)
                runCatching { g.close() }
                if (gatt === g) { gatt = null; writeChar = null }
                if (wasReady) { onInfo?.invoke("disconnected (status $status)"); onDisconnected?.invoke() }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesWait?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU changed: $mtu status=$status")
            mtuWait?.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeWait?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descWait?.complete(status)
        }

        // API 33+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onReceive?.invoke(c.uuid, value)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            onReceive?.invoke(c.uuid, c.value?.copyOf() ?: ByteArray(0))
        }

        // API 33+
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            readWait?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            readWait?.complete(if (status == BluetoothGatt.GATT_SUCCESS) c.value?.copyOf() else null)
        }
    }

    @Suppress("DEPRECATION")
    suspend fun connect(address: String): Boolean {
        disconnect()
        val ad = adapter ?: return false
        val device: BluetoothDevice = try {
            ad.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "bad address $address"); return false
        }
        Log.i(TAG, "connecting to $address")
        val cw = CompletableDeferred<Boolean>().also { connectWait = it }
        val g = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            Log.w(TAG, "connect: no permission", e); null
        } ?: return false
        gatt = g
        val connected = withTimeoutOrNull(12_000) { cw.await() } ?: false
        if (!connected) { Log.w(TAG, "connect failed/timeout"); closeGatt(g); return false }

        // Discover services.
        val sw = CompletableDeferred<Boolean>().also { servicesWait = it }
        delay(300) // some stacks want a breather before discovery
        if (!g.discoverServices()) { closeGatt(g); return false }
        val discovered = withTimeoutOrNull(10_000) { sw.await() } ?: false
        if (!discovered) { Log.w(TAG, "service discovery failed"); closeGatt(g); return false }

        var chosen: BluetoothGattCharacteristic? = null
        var fallback: BluetoothGattCharacteristic? = null
        val notifiable = mutableListOf<BluetoothGattCharacteristic>()
        val table = mutableListOf<String>()
        for (s in g.services) {
            Log.i(TAG, "service ${s.uuid}")
            table += "service ${s.uuid}"
            for (c in s.characteristics) {
                val p = c.properties
                val canWrite = p and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                if (p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                    notifiable += c
                }
                Log.i(TAG, "   char ${c.uuid} props=0x${p.toString(16)} writable=$canWrite")
                table += "  char ${c.uuid} [${propNames(p)}]"
                if (s.uuid == SERVICE_UUID && c.uuid == WRITE_UUID) chosen = c
                if (canWrite && fallback == null) fallback = c
            }
        }
        val wc = chosen ?: fallback
        if (wc == null) { Log.w(TAG, "no writable characteristic"); closeGatt(g); return false }
        info("using characteristic ${wc.uuid} (preferred=${chosen != null})")
        writeChar = wc
        writeUuid = wc.uuid
        writeNoResponse = wc.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        gattTable = table
        this.address = address

        // Bigger MTU.
        val mw = CompletableDeferred<Int>().also { mtuWait = it }
        mtu = if (g.requestMtu(512)) withTimeoutOrNull(4_000) { mw.await() } ?: 23 else 23
        info("using MTU $mtu")
        if (gatt !== g) return false // dropped meanwhile

        // Subscribe to every notify/indicate characteristic so status replies show up in the log.
        val subscribed = mutableListOf<UUID>()
        for (c in notifiable) {
            if (enableNotify(g, c)) subscribed += c.uuid
        }
        notifyUuids = subscribed
        info(if (subscribed.isEmpty()) "no notify characteristics" else "notifications on ${subscribed.joinToString()}")
        if (gatt !== g) return false
        noResponseCallbacksWork = true
        isReady = true
        return true
    }

    private fun propNames(p: Int): String = buildList {
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("writeNoResp")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
    }.joinToString(",")

    @Suppress("DEPRECATION")
    private suspend fun enableNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic): Boolean = try {
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD_UUID)
        if (d == null) true else {
            val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            val w = CompletableDeferred<Int>().also { descWait = it }
            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
            } else {
                d.value = value
                g.writeDescriptor(d)
            }
            started && withTimeoutOrNull(2_000) { w.await() } == BluetoothGatt.GATT_SUCCESS
        }
    } catch (e: SecurityException) { false }

    /** Reads every readable characteristic (device info, etc.). For the probe. */
    @Suppress("DEPRECATION")
    suspend fun readAll(): List<Pair<UUID, ByteArray?>> {
        val g = gatt ?: return emptyList()
        val out = mutableListOf<Pair<UUID, ByteArray?>>()
        for (s in g.services) for (c in s.characteristics) {
            if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) continue
            if (!isReady || out.size >= 16) break
            val w = CompletableDeferred<ByteArray?>().also { readWait = it }
            val started = try { g.readCharacteristic(c) } catch (e: SecurityException) { false }
            out += c.uuid to (if (started) withTimeoutOrNull(2_000) { w.await() } else null)
        }
        return out
    }

    fun disconnect() {
        val g = gatt ?: return
        isReady = false
        closeGatt(g)
    }

    private fun closeGatt(g: BluetoothGatt) {
        runCatching { g.disconnect() }
        runCatching { g.close() }
        if (gatt === g) { gatt = null; writeChar = null }
        isReady = false
    }

    /**
     * Writes [data] in chunks of min(MTU-3, packetSize). Waits for each write to be acknowledged
     * and then [delayMs] so the printer's buffer keeps up. On a failed write drops to 20 bytes.
     */
    suspend fun write(data: ByteArray, packetSize: Int, delayMs: Int, onProgress: (Float) -> Unit): Boolean {
        val c = writeChar ?: return false
        val noResp = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val type = if (noResp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                   else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        var chunk = min(mtu - 3, packetSize).coerceAtLeast(20)
        Log.i(TAG, "printing ${data.size} bytes, chunk=$chunk, noResponse=$noResp, delay=${delayMs}ms")
        var off = 0
        var failures = 0
        var lastReported = -1
        while (off < data.size) {
            if (!isReady) return false
            val end = min(off + chunk, data.size)
            val ok = writeChunk(c, data.copyOfRange(off, end), type, noResp)
            if (!ok) {
                failures++
                if (chunk > 20) {
                    Log.w(TAG, "write failed at $off, dropping to 20-byte chunks")
                    chunk = 20
                } else if (failures > 8) {
                    Log.w(TAG, "write failed repeatedly at $off, giving up")
                    return false
                }
                delay(100)
                continue
            }
            failures = 0
            off = end
            val pct = off * 100 / data.size
            if (pct != lastReported) { lastReported = pct; onProgress(off.toFloat() / data.size) }
            if (delayMs > 0) delay(delayMs.toLong())
        }
        return true
    }

    @Suppress("DEPRECATION")
    private suspend fun writeChunk(c: BluetoothGattCharacteristic, bytes: ByteArray, type: Int, noResp: Boolean): Boolean {
        val g = gatt ?: return false
        val w = CompletableDeferred<Int>().also { writeWait = it }
        // Start the write; the stack may be busy for a moment, so retry briefly.
        var started = false
        for (attempt in 0 until 40) {
            started = try {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(c, bytes, type) == BluetoothStatusCodes.SUCCESS
                } else {
                    c.writeType = type
                    c.value = bytes
                    g.writeCharacteristic(c)
                }
            } catch (e: SecurityException) { false }
            if (started || !isReady) break
            delay(10)
        }
        if (!started) return false
        if (noResp && !noResponseCallbacksWork) return true
        val status = withTimeoutOrNull(if (noResp) 1_000L else 3_000L) { w.await() }
        if (status == null) {
            if (noResp) {
                Log.w(TAG, "no write callback for write-without-response; relying on delay only")
                noResponseCallbacksWork = false
                return true
            }
            return false
        }
        return status == BluetoothGatt.GATT_SUCCESS
    }

    // ---- Scanning ----

    private var scanCallback: ScanCallback? = null

    /** Returns null on success, or an error message. */
    fun startScan(onFound: (FoundDevice) -> Unit): String? {
        stopScan()
        val scanner = adapter?.bluetoothLeScanner ?: return "Bluetooth is off"
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName
                    ?: runCatching { result.device.name }.getOrNull()
                onFound(FoundDevice(result.device.address, name, result.rssi))
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(0, it) }
            }
            override fun onScanFailed(errorCode: Int) { Log.w(TAG, "scan failed $errorCode") }
        }
        return try {
            scanner.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                cb,
            )
            scanCallback = cb
            null
        } catch (e: SecurityException) {
            "Bluetooth permission missing"
        } catch (e: Exception) {
            e.message ?: "Scan failed"
        }
    }

    fun stopScan() {
        val cb = scanCallback ?: return
        scanCallback = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
    }
}
