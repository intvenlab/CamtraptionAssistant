package com.ivlabs.batterymonitor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// Device dashboard
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    device: BleDevice,
    gattManager: BleGattManager,
    historyStore: DeviceHistoryStore,
    onOpenSettings: () -> Unit,
    onOpenCameraConfig: (() -> Unit)? = null,
    onBack: () -> Unit
) {
    BackHandler { gattManager.disconnect(); onBack() }

    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf<List<DeviceHistoryEntry>>(emptyList()) }

    // Load persisted history on first composition
    LaunchedEffect(Unit) {
        history = withContext(Dispatchers.IO) { historyStore.load(device.address) }
    }

    // Append a new entry whenever GATT connects (state → READY)
    LaunchedEffect(gattManager.state) {
        if (gattManager.state == GattState.READY) {
            val entry = DeviceHistoryEntry(
                timestamp         = System.currentTimeMillis(),
                batteryPercent    = device.batteryPercent,
                voltageMillivolts = device.voltageMillivolts,
                rssi              = device.rssi
            )
            val updated = withContext(Dispatchers.IO) {
                historyStore.append(device.address, entry)
                historyStore.load(device.address)
            }
            history = updated
        }
    }

    // External battery is the primary reading when present; internal is the fallback.
    val displayPercent   = if (device.extBatteryPercent >= 0) device.extBatteryPercent   else device.batteryPercent
    val displayVoltageMv = if (device.extBatteryPercent >= 0) device.extVoltageMillivolts else device.voltageMillivolts
    val displayIsExt     = device.extBatteryPercent >= 0
    val batteryColor     = batteryDisplayColor(displayPercent)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(device.name ?: device.address) },
                navigationIcon = {
                    IconButton(onClick = { gattManager.disconnect(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Battery gauge ──────────────────────────────────────────────
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (displayIsExt) "Device Battery" else "Internal Battery",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 40.dp, vertical = 8.dp)
                    ) {
                        BatteryGauge(
                            percent = displayPercent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${displayPercent}%",
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Bold,
                                color = batteryColor
                            )
                            Text(
                                text = "${"%.3f".format(displayVoltageMv / 1000f)} V",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // ── Stats card ─────────────────────────────────────────────────
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            StatItem("Type", device.deviceType.displayName())
                            StatItem("Chemistry", device.batteryChemistry.displayName())
                            StatItem("Cells", "${device.cellCount}")
                        }
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            StatItem(
                                label = "Group",
                                value = if (device.groupId == 0) "None" else "Group ${device.groupId}"
                            )
                            StatItem("Signal", "${device.rssi} dBm")
                            StatItem(
                                label = "Status",
                                value = when (gattManager.state) {
                                    GattState.READY      -> "Connected"
                                    GattState.CONNECTING -> "Connecting"
                                    GattState.ERROR      -> "Error"
                                    GattState.IDLE       -> "Idle"
                                }
                            )
                        }
                        // Internal battery always visible on device page
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            StatItem("Internal Battery", "${device.batteryPercent}%")
                            StatItem(
                                "Internal Voltage",
                                "${"%.3f".format(device.voltageMillivolts / 1000f)} V"
                            )
                        }
                    }
                }
            }

            // ── Camera (shutter count + reset + logic) ─────────────────────
            if (device.deviceType == DeviceType.CAMERA) {
                item {
                    CameraCard(
                        device = device,
                        gattManager = gattManager,
                        onOpenCameraConfig = onOpenCameraConfig,
                        onReset = {
                            scope.launch {
                                gattManager.writeCharacteristic(
                                    GattUuids.RESET_SHUTTER, byteArrayOf(0x01)
                                )
                            }
                        }
                    )
                }
            }

            // ── History ────────────────────────────────────────────────────
            item {
                Text(
                    text = "Connection History",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            item {
                if (history.isEmpty()) {
                    Text(
                        text = "No history yet. Stats are recorded each time you open this device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            history.forEachIndexed { idx, entry ->
                                HistoryEntryRow(entry)
                                if (idx < history.lastIndex) {
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Dashboard helpers
// ---------------------------------------------------------------------------

@Composable
private fun BatteryGauge(percent: Int, modifier: Modifier = Modifier) {
    val fillColor = batteryDisplayColor(percent)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier = modifier) {
        val strokePx = 30.dp.toPx()
        val inset = strokePx / 2f
        val arcTopLeft = Offset(inset, inset)
        val arcSize = Size(size.width - strokePx, size.height - strokePx)
        // Background track
        drawArc(
            color = trackColor,
            startAngle = 150f,
            sweepAngle = 240f,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = strokePx, cap = StrokeCap.Round)
        )
        // Filled portion
        val sweep = (240f * percent / 100f).coerceIn(0f, 240f)
        if (sweep > 0f) {
            drawArc(
                color = fillColor,
                startAngle = 150f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HistoryEntryRow(entry: DeviceHistoryEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = formatTimestamp(entry.timestamp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${entry.batteryPercent}%",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = batteryDisplayColor(entry.batteryPercent)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "${"%.3f".format(entry.voltageMillivolts / 1000f)}V",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "${entry.rssi} dBm",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatTimestamp(ts: Long): String {
    val today = Calendar.getInstance()
    val then  = Calendar.getInstance().also { it.timeInMillis = ts }
    return if (
        today.get(Calendar.YEAR)       == then.get(Calendar.YEAR) &&
        today.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    ) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
    } else {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ts))
    }
}

// ---------------------------------------------------------------------------
// Settings screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSettingsScreen(
    device: BleDevice,
    gattManager: BleGattManager,
    onBack: () -> Unit,
    onFactoryReset: () -> Unit
) {
    val scope = rememberCoroutineScope()
    BackHandler { onBack() }

    var nameInput  by remember { mutableStateOf(device.name ?: "") }
    var typeInput  by remember { mutableStateOf(device.deviceType) }
    var chemInput  by remember { mutableStateOf(device.batteryChemistry) }
    var cellInput  by remember { mutableStateOf(device.cellCount.coerceIn(1, 4)) }
    var groupInput by remember { mutableStateOf(device.groupId) }
    var saveStatus by remember { mutableStateOf<String?>(null) }

    // Read settings from device when GATT is ready
    LaunchedEffect(gattManager.state) {
        if (gattManager.state == GattState.READY) {
            gattManager.readCharacteristic(GattUuids.DEVICE_NAME)
                ?.let { nameInput = it.toString(Charsets.UTF_8) }
            gattManager.readCharacteristic(GattUuids.DEVICE_TYPE)
                ?.firstOrNull()?.toInt()
                ?.let { typeInput = DeviceType.values().getOrElse(it) { DeviceType.BATTERY_MONITOR } }
            gattManager.readCharacteristic(GattUuids.CHEMISTRY)
                ?.firstOrNull()?.toInt()
                ?.let { chemInput = BatteryChemistry.values().getOrElse(it) { BatteryChemistry.LIPO } }
            gattManager.readCharacteristic(GattUuids.CELL_COUNT)
                ?.firstOrNull()?.let { cellInput = (it.toInt() and 0xFF).coerceIn(1, 4) }
            gattManager.readCharacteristic(GattUuids.GROUP_ID)
                ?.firstOrNull()?.let { groupInput = it.toInt() and 0xFF }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { GattStatusBanner(gattManager) }

            item { BatteryInfoCard(device) }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Device Settings", style = MaterialTheme.typography.titleMedium)

                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { if (it.length <= 20) nameInput = it },
                            label = { Text("Device Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        EnumDropdown(
                            label = "Device Type",
                            selected = typeInput,
                            options = DeviceType.values().toList(),
                            onSelect = { typeInput = it },
                            displayName = DeviceType::displayName
                        )

                        EnumDropdown(
                            label = "Battery Chemistry",
                            selected = chemInput,
                            options = BatteryChemistry.values().toList(),
                            onSelect = { chemInput = it },
                            displayName = BatteryChemistry::displayName
                        )

                        CellCountDropdown(cellInput) { cellInput = it }

                        GroupDropdown(groupInput) { groupInput = it }
                    }
                }
            }

            item {
                saveStatus?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("Saved")) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Button(
                    onClick = {
                        scope.launch {
                            val ok = writeAllSettings(
                                gattManager, nameInput, typeInput, chemInput, cellInput, groupInput
                            )
                            saveStatus = if (ok) "Saved successfully" else "Save failed – not connected"
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save")
                }
            }

            item {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            gattManager.writeCharacteristic(GattUuids.FACTORY_RESET, byteArrayOf(0x01))
                            gattManager.disconnect()
                            onFactoryReset()
                        }
                    },
                    enabled = gattManager.state == GattState.READY,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Factory Reset")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared write logic
// ---------------------------------------------------------------------------

private suspend fun writeAllSettings(
    gattManager: BleGattManager,
    name: String,
    type: DeviceType,
    chemistry: BatteryChemistry,
    cellCount: Int,
    groupId: Int
): Boolean {
    if (gattManager.state != GattState.READY) return false
    var ok = gattManager.writeCharacteristic(GattUuids.DEVICE_NAME, name.toByteArray(Charsets.UTF_8))
    ok = ok && gattManager.writeCharacteristic(GattUuids.DEVICE_TYPE,  byteArrayOf(type.ordinal.toByte()))
    ok = ok && gattManager.writeCharacteristic(GattUuids.CHEMISTRY,    byteArrayOf(chemistry.ordinal.toByte()))
    ok = ok && gattManager.writeCharacteristic(GattUuids.CELL_COUNT,   byteArrayOf(cellCount.toByte()))
    ok = ok && gattManager.writeCharacteristic(GattUuids.GROUP_ID,     byteArrayOf(groupId.toByte()))
    return ok
}
