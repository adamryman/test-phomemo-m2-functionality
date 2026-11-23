package com.adamryman.snapandprint

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    val manager: PrintManager get() = (application as SnapApp).printManager

    var permissionsGranted by mutableStateOf(false)
    var permissionsDenied by mutableStateOf(false)
    var showPicker by mutableStateOf(false)
    /** Current debug page; null = home screen. */
    var page by mutableStateOf<LabPage?>(null)
    /** Test Lab controls; kept here so values survive switching pages. */
    val lab = LabState()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val was = permissionsGranted
            permissionsGranted = hasAllPermissions()
            permissionsDenied = !permissionsGranted
            if (!was && permissionsGranted) onPermissionsGranted()
        }

    private val labPickMedia =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
            if (uri != null) loadLabImage(uri)
        }

    private var labPhotoUri: Uri? = null
    private val labTakePhoto =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val uri = labPhotoUri
            if (ok && uri != null) loadLabImage(uri)
        }

    private val enableBluetooth =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            manager.reconnect()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        permissionsGranted = hasAllPermissions()
        setContent { SnapTheme { AppRoot(this) } }
        if (permissionsGranted) onPermissionsGranted() else requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        val was = permissionsGranted
        permissionsGranted = hasAllPermissions()
        if (!was && permissionsGranted) { permissionsDenied = false; onPermissionsGranted() }
    }

    fun requiredPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }.toTypedArray()

    private fun hasAllPermissions() = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    fun requestPermissions() = permissionLauncher.launch(requiredPermissions())

    fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun onPermissionsGranted() {
        if (manager.hasPrinter) manager.reconnect() else showPicker = true
    }

    fun onStatusTapped() {
        when (val s = manager.status.value) {
            is PrinterStatus.NoPrinter -> showPicker = true
            is PrinterStatus.Ready -> showPicker = true
            is PrinterStatus.Error -> if (s.bluetoothOff) requestBluetoothOn() else manager.reconnect()
            is PrinterStatus.Disconnected -> manager.reconnect()
            else -> Unit
        }
    }

    fun requestBluetoothOn() {
        try {
            enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (e: Exception) {
            Log.w("PhomemoDebug", "can't request bluetooth enable", e)
        }
    }

    fun labPickImage() =
        labPickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    /** Uses the system camera app, so this app needs no camera permission. */
    fun labCapturePhoto() {
        try {
            val dir = File(cacheDir, "lab").apply { mkdirs() }
            val file = File(dir, "capture.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.labfiles", file)
            labPhotoUri = uri
            labTakePhoto.launch(uri)
        } catch (e: Exception) {
            Log.w("PhomemoDebug", "camera intent failed", e)
            toast("No camera app available")
        }
    }

    private fun loadLabImage(uri: Uri) {
        val rotate = lab.rotateWide
        thread(name = "lab-decode") {
            try {
                val bmp = manager.prepareUri(uri, rotate)
                runOnUiThread { lab.image = bmp; lab.useImage = true }
            } catch (t: Throwable) {
                Log.w("PhomemoDebug", "lab decode failed", t)
                runOnUiThread { toast("Couldn't open that image") }
            }
        }
    }

    fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }
}
