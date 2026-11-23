package com.adamryman.snapandprint

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.util.Size
import kotlin.math.roundToInt

@Composable
fun SnapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color.White,
            onPrimary = Color.Black,
            secondary = Color(0xFFBBBBBB),
            background = Color.Black,
            surface = Color(0xFF161616),
            onSurface = Color.White,
            surfaceVariant = Color(0xFF242424),
        ),
        content = content,
    )
}

@Composable
fun AppRoot(activity: MainActivity) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!activity.permissionsGranted) {
            PermissionScreen(activity)
        } else {
            CameraScreen(activity)
            if (activity.showSettings) SettingsScreen(activity)
            if (activity.showPicker) PrinterPicker(activity)
        }
    }
}

@Composable
private fun PermissionScreen(activity: MainActivity) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Snap & Print", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(20.dp))
        Text(
            "This app needs a few permissions to work:",
            color = Color.White, fontSize = 16.sp,
        )
        Spacer(Modifier.height(12.dp))
        val bt = if (Build.VERSION.SDK_INT >= 31) "Nearby devices (Bluetooth)" else "Location (Android needs it to scan for Bluetooth devices)"
        Bullet("Camera — to take the photo you're printing.")
        Bullet("$bt — to find and talk to your Phomemo printer.")
        Spacer(Modifier.height(12.dp))
        Text(
            "Nothing leaves your phone. The app has no internet access.",
            color = Color(0xFFAAAAAA), fontSize = 14.sp,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = { activity.requestPermissions() }, modifier = Modifier.fillMaxWidth()) {
            Text("Grant permissions")
        }
        if (activity.permissionsDenied) {
            Spacer(Modifier.height(8.dp))
            Text(
                "If Android won't ask again, open the app's settings and allow them there.",
                color = Color(0xFFAAAAAA), fontSize = 14.sp,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { activity.openAppSettings() }, modifier = Modifier.fillMaxWidth()) {
                Text("Open app settings")
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text("•  ", color = Color.White, fontSize = 16.sp)
        Text(text, color = Color.White, fontSize = 16.sp)
    }
}

@Composable
private fun CameraScreen(activity: MainActivity) {
    val mgr = activity.manager
    val status by mgr.status.collectAsStateWithLifecycle()
    val pending by mgr.pending.collectAsStateWithLifecycle()
    val thumb by mgr.thumbnail.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        CameraPreview(onCapture = { activity.imageCapture = it })

        // White flash on shutter.
        val flash = remember { Animatable(0f) }
        LaunchedEffect(activity.flashTick) {
            if (activity.flashTick > 0) {
                flash.snapTo(0.85f)
                flash.animateTo(0f, tween(220))
            }
        }
        if (flash.value > 0f) Box(Modifier.fillMaxSize().alpha(flash.value).background(Color.White))

        // Top bar: status pill + settings.
        Box(Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            StatusPill(status, Modifier.align(Alignment.Center), onTap = { activity.onStatusTapped() },
                onLongPress = { activity.showPicker = true })
            RoundButton("⚙", 44.dp, Modifier.align(Alignment.CenterEnd)) { activity.showSettings = true }
        }

        // Bottom controls.
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding()
                .padding(bottom = 28.dp, start = 24.dp, end = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton("🖼", 60.dp) { activity.pickFromGallery() }
            ShutterButton { activity.shutter() }
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                thumb?.let { bmp ->
                    val img = remember(bmp) { bmp.asImageBitmap() }
                    Image(
                        img, contentDescription = "Last print",
                        filterQuality = FilterQuality.None,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(60.dp).clip(RoundedCornerShape(6.dp))
                            .background(Color.White).border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(6.dp)),
                    )
                }
                if (pending > 0) {
                    Box(
                        Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp).size(24.dp)
                            .clip(CircleShape).background(Color(0xFFE53935)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$pending", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(onCapture: (ImageCapture?) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
    LaunchedEffect(Unit) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(1600, 1200), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                ).build()
            )
            .build()
        try {
            provider.unbindAll()
            // Bound to the activity lifecycle: CameraX releases the camera when backgrounded.
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            onCapture(capture)
        } catch (e: Exception) {
            android.util.Log.e("SnapPrint", "camera bind failed", e)
            onCapture(null)
        }
    }
    DisposableEffect(Unit) { onDispose { onCapture(null) } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatusPill(status: PrinterStatus, modifier: Modifier, onTap: () -> Unit, onLongPress: () -> Unit) {
    val (dot, text) = when (status) {
        is PrinterStatus.NoPrinter -> Color(0xFF888888) to "No printer · tap to pick"
        is PrinterStatus.Disconnected -> Color(0xFF888888) to "Not connected · tap to connect"
        is PrinterStatus.Connecting -> Color(0xFFFFC107) to "Connecting…"
        is PrinterStatus.Ready -> Color(0xFF4CAF50) to "Ready"
        is PrinterStatus.Printing -> Color(0xFF42A5F5) to "Printing ${(status.progress * 100).roundToInt()}%"
        is PrinterStatus.Error -> Color(0xFFE53935) to status.message
    }
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color(0xB0000000))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(50))
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
private fun RoundButton(label: String, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(size).clip(CircleShape).background(Color(0x80000000))
            .border(1.dp, Color(0x40FFFFFF), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = (size.value * 0.42f).sp, color = Color.White)
    }
}

@Composable
private fun ShutterButton(onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Box(
        Modifier.size(92.dp).clip(CircleShape).border(5.dp, Color.White, CircleShape)
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(74.dp).scale(if (pressed) 0.88f else 1f).clip(CircleShape).background(Color.White))
    }
}

// ---------------- Printer picker ----------------

@Composable
private fun PrinterPicker(activity: MainActivity) {
    val mgr = activity.manager
    val devices by mgr.devices.collectAsStateWithLifecycle()
    val scanning by mgr.scanning.collectAsStateWithLifecycle()
    val scanError by mgr.scanError.collectAsStateWithLifecycle()
    var showAll by remember { mutableStateOf(false) }
    val close = { mgr.stopScan(); activity.showPicker = false }

    DisposableEffect(Unit) {
        mgr.startScan()
        onDispose { mgr.stopScan() }
    }

    Dialog(onDismissRequest = close) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp)) {
                Text("Pick your printer", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Turn the M02 on. It shows up as “Mr.in_M02”.",
                    fontSize = 13.sp, color = Color(0xFFAAAAAA),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { showAll = !showAll }) {
                    Checkbox(checked = showAll, onCheckedChange = { showAll = it })
                    Text("Show all devices", fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    if (scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                val shown = devices
                    .filter { showAll || BlePrinter.looksLikePrinter(it.name) }
                    .sortedWith(compareByDescending<FoundDevice> { BlePrinter.looksLikePrinter(it.name) }.thenByDescending { it.rssi })
                Box(Modifier.heightIn(min = 80.dp, max = 340.dp)) {
                    if (shown.isEmpty()) {
                        Text(
                            scanError ?: if (scanning) "Scanning…" else "Nothing found. Is the printer on?",
                            color = Color(0xFFAAAAAA), modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                    LazyColumn {
                        items(shown, key = { it.address }) { d ->
                            Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .clickable { mgr.selectPrinter(d); activity.showPicker = false }
                                    .padding(vertical = 10.dp, horizontal = 8.dp)
                            ) {
                                Text(d.name ?: "(unnamed)", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Text("${d.address}  ·  ${d.rssi} dBm", fontSize = 12.sp, color = Color(0xFF999999))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { mgr.startScan() }) { Text("Rescan") }
                    TextButton(onClick = close) { Text("Close") }
                }
            }
        }
    }
}

// ---------------- Settings ----------------

@Composable
private fun SettingsScreen(activity: MainActivity) {
    val mgr = activity.manager
    val s = mgr.settings
    val pending by mgr.pending.collectAsStateWithLifecycle()
    var brightness by remember { mutableIntStateOf(s.brightness) }
    var contrast by remember { mutableIntStateOf(s.contrast) }
    var dither by remember { mutableStateOf(s.dither) }
    var rotateWide by remember { mutableStateOf(s.rotateWide) }
    var feed by remember { mutableIntStateOf(s.feed) }
    var packet by remember { mutableIntStateOf(s.packetSize) }
    var delayMs by remember { mutableIntStateOf(s.packetDelayMs) }
    var save by remember { mutableStateOf(s.saveToGallery) }
    var printerName by remember { mutableStateOf(s.printerName) }

    BackHandler { activity.showSettings = false }

    Surface(Modifier.fillMaxSize(), color = Color(0xFF0E0E0E)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { activity.showSettings = false }) { Text("Done") }
            }

            Section("Image")
            IntSlider("Brightness", brightness, -50..50) { brightness = it; s.brightness = it }
            IntSlider("Contrast", contrast, -50..50) { contrast = it; s.contrast = it }
            Text("Dither", fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
            DitherStyle.entries.forEach { d ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = dither == d) { dither = d; s.dither = d },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = dither == d, onClick = { dither = d; s.dither = d })
                    Text(d.label)
                }
            }
            SwitchRow("Rotate wide photos to fill the paper", rotateWide) { rotateWide = it; s.rotateWide = it }
            SwitchRow("Save each photo (color) to gallery", save) { save = it; activity.onSaveToGalleryToggled(it) }

            Section("Printer")
            Text("Printer: ${printerName ?: "none"}", color = Color(0xFFBBBBBB))
            IntSlider("Paper feed after print", feed, 0..255) { feed = it; s.feed = it }
            IntSlider("Packet size (bytes)", packet, 20..509) { packet = it; s.packetSize = it }
            IntSlider("Delay between packets (ms)", delayMs, 0..200) { delayMs = it; s.packetDelayMs = it }
            Text(
                "Streaky or cut-off prints? Lower the packet size or raise the delay.",
                fontSize = 12.sp, color = Color(0xFF888888),
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { mgr.testPrint() }, modifier = Modifier.fillMaxWidth()) { Text("Test print") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { activity.showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Pick printer")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { mgr.forgetPrinter(); printerName = null },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Forget printer") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { mgr.clearQueue() }, modifier = Modifier.fillMaxWidth()) {
                Text(if (pending > 0) "Clear queue ($pending waiting)" else "Clear queue")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                s.resetImageDefaults()
                brightness = s.brightness; contrast = s.contrast; dither = s.dither; rotateWide = s.rotateWide
                feed = s.feed; packet = s.packetSize; delayMs = s.packetDelayMs
            }) { Text("Reset to defaults") }
            Spacer(Modifier.height(24.dp))
        }
    }
    // Refresh printer name when the picker closes.
    LaunchedEffect(activity.showPicker) { printerName = s.printerName }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(18.dp))
    Text(title.uppercase(), fontSize = 12.sp, color = Color(0xFF888888), fontWeight = FontWeight.Bold)
    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Color(0xFF2A2A2A))
}

@Composable
private fun IntSlider(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column(Modifier.padding(top = 6.dp)) {
        Row {
            Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("$value", fontSize = 15.sp, color = Color(0xFFBBBBBB))
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
