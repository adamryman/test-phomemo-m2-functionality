package com.adamryman.snapandprint

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
            AppScreen(activity)
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
        Text("Phomemo M2 Debug", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(20.dp))
        Text(
            "This app needs Bluetooth permission to work:",
            color = Color.White, fontSize = 16.sp,
        )
        Spacer(Modifier.height(12.dp))
        val bt = if (Build.VERSION.SDK_INT >= 31) "Nearby devices (Bluetooth)" else "Location (Android needs it to scan for Bluetooth devices)"
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

@Composable
internal fun Section(title: String) {
    Spacer(Modifier.height(18.dp))
    Text(title.uppercase(), fontSize = 12.sp, color = Color(0xFF888888), fontWeight = FontWeight.Bold)
    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Color(0xFF2A2A2A))
}

@Composable
internal fun IntSlider(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
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
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
