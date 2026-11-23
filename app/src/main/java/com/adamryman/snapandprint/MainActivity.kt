package com.adamryman.snapandprint

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    val manager: PrintManager get() = (application as SnapApp).printManager

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    var imageCapture: ImageCapture? = null

    var permissionsGranted by mutableStateOf(false)
    var permissionsDenied by mutableStateOf(false)
    var showSettings by mutableStateOf(false)
    var showPicker by mutableStateOf(false)
    var flashTick by mutableIntStateOf(0)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val was = permissionsGranted
            permissionsGranted = hasAllPermissions()
            permissionsDenied = !permissionsGranted
            if (!was && permissionsGranted) onPermissionsGranted()
        }

    private val storageLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) manager.settings.saveToGallery = false
        }

    private val pickMedia =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
            if (uri != null) { feedback(); manager.submitUri(uri) }
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

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.CAMERA)
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
            Log.w("SnapPrint", "can't request bluetooth enable", e)
        }
    }

    fun pickFromGallery() {
        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    fun onSaveToGalleryToggled(on: Boolean) {
        manager.settings.saveToGallery = on
        if (on && Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    /** Shutter: capture and print. No preview, no confirm. */
    fun shutter() {
        val ic = imageCapture ?: return
        feedback()
        ic.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val bmp = image.toBitmap()
                    val rot = image.imageInfo.rotationDegrees
                    manager.submitBitmap(bmp, rot)
                } catch (t: Throwable) {
                    Log.e("SnapPrint", "capture convert failed", t)
                    manager.reportError("Camera error")
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e("SnapPrint", "capture failed", exception)
                manager.reportError("Camera error")
            }
        })
    }

    private fun feedback() {
        flashTick++
        try {
            val vib: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
            vib?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) { }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            permissionsGranted && !showSettings && !showPicker
        ) {
            if (event.repeatCount == 0) shutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            permissionsGranted && !showSettings && !showPicker
        ) return true
        return super.onKeyUp(keyCode, event)
    }
}
