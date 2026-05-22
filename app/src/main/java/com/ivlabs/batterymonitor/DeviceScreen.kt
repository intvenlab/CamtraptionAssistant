package com.ivlabs.batterymonitor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
    BackHandler { onBack() }

    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf<List<DeviceHistoryEntry>>(emptyList()) }

    // On page entry: log a history snapshot from the current advertisement data
    // and load the full history list. GATT is not connected on this screen.
    LaunchedEffect(device.address) {
        val entry = DeviceHistoryEntry(
            timestamp         = System.currentTimeMillis(),
            batteryPercent    = device.extBatteryPercent,
            voltageMillivolts = device.extVoltageMillivolts,
            rssi              = device.rssi,
            shutterCount      = device.shutterCount
        )
        history = withContext(Dispatchers.IO) {
            historyStore.append(device.address, entry)
            historyStore.load(device.address)
        }
    }

    // Gauge defaults to external battery when present; swipe left/right to toggle.
    val hasExt = device.extBatteryPercent >= 0
    var showExternal by remember(hasExt) { mutableStateOf(hasExt) }

    val displayPercent   = if (showExternal) device.extBatteryPercent    else device.batteryPercent
    val displayVoltageMv = if (showExternal) device.extVoltageMillivolts else device.voltageMillivolts
    val displayLabel     = if (showExternal) "Device Battery"            else "Internal Battery"
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
                var dragAccumulated by remember { mutableStateOf(0f) }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = displayLabel,
                        style = MaterialTheme.typography.titleMedium
                    )
                    // Page-indicator dots shown when both batteries are available
                    if (hasExt) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (showExternal) "\u25CF" else "\u25CB",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (showExternal) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (!showExternal) "\u25CF" else "\u25CB",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (!showExternal) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 40.dp, vertical = 8.dp)
                            .pointerInput(hasExt) {
                                if (!hasExt) return@pointerInput
                                detectHorizontalDragGestures(
                                    onDragStart  = { dragAccumulated = 0f },
                                    onDragCancel = { dragAccumulated = 0f },
                                    onDragEnd    = {
                                        if (dragAccumulated > 40.dp.toPx() ||
                                            dragAccumulated < -40.dp.toPx()) {
                                            showExternal = !showExternal
                                        }
                                        dragAccumulated = 0f
                                    },
                                    onHorizontalDrag = { change, amount ->
                                        change.consume()
                                        dragAccumulated += amount
                                    }
                                )
                            }
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
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            StatItem("MAC Address", device.address)
                        }
                        if (device.firmwareBuild.isNotEmpty()) {
                            HorizontalDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                StatItem("Built", device.firmwareBuild)
                            }
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
                                HistoryEntryRow(entry, device.deviceType)
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
private fun HistoryEntryRow(entry: DeviceHistoryEntry, deviceType: DeviceType) {
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
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (deviceType == DeviceType.CAMERA) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "S: ${entry.shutterCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = if (entry.batteryPercent >= 0) "${entry.batteryPercent}%" else "--",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = batteryDisplayColor(entry.batteryPercent.coerceAtLeast(0))
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = if (entry.voltageMillivolts > 0) "${"%.3f".format(entry.voltageMillivolts / 1000f)}V" else "--",
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
    var saveStatus      by remember { mutableStateOf<String?>(null) }
    var calVoltageInput by remember { mutableStateOf("") }
    var calStatus       by remember { mutableStateOf<String?>(null) }
    var intCalVoltageInput by remember { mutableStateOf("") }
    var intCalStatus       by remember { mutableStateOf<String?>(null) }

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
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("External Battery Calibration", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Connect battery and enter the actual pack voltage " +
                            "measured with a multimeter, then tap Set Cal.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = calVoltageInput,
                            onValueChange = { calVoltageInput = it },
                            label = { Text("Actual Voltage (V)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        val mv = (calVoltageInput.toFloatOrNull() ?: 0f) * 1000f
                                        val mvi = mv.toInt().coerceIn(100, 30000)
                                        val ok = gattManager.writeCharacteristic(
                                            GattUuids.CAL_SET,
                                            byteArrayOf((mvi and 0xFF).toByte(), ((mvi shr 8) and 0xFF).toByte())
                                        )
                                        calStatus = if (ok) "Calibration set" else "Write failed"
                                    }
                                },
                                enabled = gattManager.state == GattState.READY &&
                                          (calVoltageInput.toFloatOrNull() ?: 0f) > 0f,
                                modifier = Modifier.weight(1f)
                            ) { Text("Set Cal") }

                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val ok = gattManager.writeCharacteristic(
                                            GattUuids.CAL_SET, byteArrayOf(0x00, 0x00)
                                        )
                                        calStatus = if (ok) "Reset to default" else "Write failed"
                                    }
                                },
                                enabled = gattManager.state == GattState.READY,
                                modifier = Modifier.weight(1f)
                            ) { Text("Reset Cal") }
                        }
                        calStatus?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (it.contains("failed")) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Internal CR2032 Calibration", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Enter the actual CR2032 coin cell voltage measured with a multimeter, " +
                            "then tap Set Cal.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = intCalVoltageInput,
                            onValueChange = { intCalVoltageInput = it },
                            label = { Text("Actual Voltage (V)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        val mv = (intCalVoltageInput.toFloatOrNull() ?: 0f) * 1000f
                                        val mvi = mv.toInt().coerceIn(100, 5000)
                                        val ok = gattManager.writeCharacteristic(
                                            GattUuids.INT_CAL_SET,
                                            byteArrayOf((mvi and 0xFF).toByte(), ((mvi shr 8) and 0xFF).toByte())
                                        )
                                        intCalStatus = if (ok) "Calibration set" else "Write failed"
                                    }
                                },
                                enabled = gattManager.state == GattState.READY &&
                                          (intCalVoltageInput.toFloatOrNull() ?: 0f) > 0f,
                                modifier = Modifier.weight(1f)
                            ) { Text("Set Cal") }

                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val ok = gattManager.writeCharacteristic(
                                            GattUuids.INT_CAL_SET, byteArrayOf(0x00, 0x00)
                                        )
                                        intCalStatus = if (ok) "Reset to default" else "Write failed"
                                    }
                                },
                                enabled = gattManager.state == GattState.READY,
                                modifier = Modifier.weight(1f)
                            ) { Text("Reset Cal") }
                        }
                        intCalStatus?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (it.contains("failed")) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
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
