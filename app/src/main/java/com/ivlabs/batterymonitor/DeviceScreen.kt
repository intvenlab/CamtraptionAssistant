package com.ivlabs.batterymonitor

import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlin.math.abs
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "DeviceScreen"

// ---------------------------------------------------------------------------
// Device dashboard
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    device: BleDevice,
    bluetoothDevice: BluetoothDevice,
    gattManager: BleGattManager,
    historyStore: DeviceHistoryStore,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf<List<DeviceHistoryEntry>>(emptyList()) }
    var resetStatus by remember { mutableStateOf<String?>(null) }
    var expandedEntries by remember { mutableStateOf(emptySet<Int>()) }

    val currentDeviceState = rememberUpdatedState(device)

    suspend fun addHistoryEntry(eventType: String, dev: BleDevice) {
        val entry = DeviceHistoryEntry(
            timestamp            = System.currentTimeMillis(),
            eventType            = eventType,
            shutterCount         = dev.shutterCount,
            batteryPercent       = dev.extBatteryPercent,
            voltageMillivolts    = dev.extVoltageMillivolts,
            intBatteryPercent    = dev.batteryPercent,
            intVoltageMillivolts = dev.voltageMillivolts,
            rssi                 = dev.rssi,
            cameraState          = dev.cameraState,
            cameraLiveFlags      = dev.cameraLiveFlags,
            firmwareBuild        = dev.firmwareBuild
        )
        history = withContext(Dispatchers.IO) {
            historyStore.append(dev.address, entry)
            historyStore.load(dev.address)
        }
    }

    // Connection-aware event logger
    LaunchedEffect(device.address) {
        var prevConnected     = currentDeviceState.value.isConnected
        var lastLoggedVoltage = currentDeviceState.value.extVoltageMillivolts
        var prevShutter       = currentDeviceState.value.shutterCount
        var prevExtPresent    = currentDeviceState.value.extBatteryPercent >= 0

        // Always load existing history so logs are visible even when not connected
        history = withContext(Dispatchers.IO) { historyStore.load(device.address) }

        // Log Connect on entry only if already in range
        if (prevConnected) {
            addHistoryEntry("Connect", currentDeviceState.value)
        }

        snapshotFlow { currentDeviceState.value }
            .drop(1)
            .collect { dev ->
                val nowConnected = dev.isConnected

                when {
                    !prevConnected && nowConnected -> {
                        // Device came back into range
                        addHistoryEntry("Connect", dev)
                        lastLoggedVoltage = dev.extVoltageMillivolts
                        prevShutter       = dev.shutterCount
                        prevExtPresent    = dev.extBatteryPercent >= 0
                    }
                    prevConnected && !nowConnected -> {
                        // Device went out of range
                        addHistoryEntry("Disconnect", dev)
                    }
                }
                prevConnected = nowConnected

                if (nowConnected) {
                    if (dev.shutterCount != prevShutter) {
                        addHistoryEntry("Shutter", dev)
                        prevShutter = dev.shutterCount
                    }
                    val voltDelta = abs(dev.extVoltageMillivolts - lastLoggedVoltage)
                    if (voltDelta >= 1000 && dev.extVoltageMillivolts > 0) {
                        addHistoryEntry("Battery", dev)
                        lastLoggedVoltage = dev.extVoltageMillivolts
                    }
                    val nowPresent = dev.extBatteryPercent >= 0
                    if (prevExtPresent && !nowPresent)  addHistoryEntry("ExtBattRemoved", dev)
                    if (!prevExtPresent && nowPresent)  addHistoryEntry("ExtBattAttached", dev)
                    prevExtPresent = nowPresent
                }
            }
    }

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
            // ── Dual-battery card ───────────────────────────────────────────
            item {
                val hasExt   = device.extBatteryPercent >= 0
                val extPct   = device.extBatteryPercent
                val extMv    = device.extVoltageMillivolts
                val intPct   = device.batteryPercent
                val intMv    = device.voltageMillivolts
                val extColor = batteryDisplayColor(extPct.coerceAtLeast(0))
                val intColor = batteryDisplayColor(intPct)

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("External Battery", style = MaterialTheme.typography.bodyLarge)
                            if (hasExt) {
                                Text(
                                    "${extPct}%",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = extColor
                                )
                            }
                        }
                        if (hasExt) {
                            LinearProgressIndicator(
                                progress = { extPct / 100f },
                                modifier = Modifier.fillMaxWidth(),
                                color = extColor
                            )
                            Text(
                                "${"%.3f".format(extMv / 1000f)} V",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                "No Battery Connected",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Internal Battery", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${intPct}%",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = intColor
                            )
                        }
                        LinearProgressIndicator(
                            progress = { intPct / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = intColor
                        )
                        Text(
                            "${"%.3f".format(intMv / 1000f)} V",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                            StatItem("Kit", if (device.groupId == 0) "None" else "${device.groupId}")
                            StatItem(
                                label = if (device.isConnected) "Connection" else "Last Seen",
                                value = if (device.isConnected) "Connected"
                                        else if (device.lastSeen > 0L) formatLastSeen(device.lastSeen)
                                        else "--",
                                valueColor = if (device.isConnected) Color(0xFF4CAF50)
                                             else Color.Unspecified
                            )
                        }
                    }
                }
            }

            // ── Camera (shutter count + reset) ─────────────────────────────
            if (device.deviceType == DeviceType.CAMERA) {
                item {
                    CameraCard(
                        device = device,
                        resetStatus = resetStatus,
                        onReset = {
                            scope.launch {
                                resetStatus = "Connecting\u2026"
                                gattManager.connect(bluetoothDevice)
                                val result = withTimeoutOrNull(8_000) {
                                    snapshotFlow { gattManager.state }
                                        .first { s -> s == GattState.READY || s == GattState.ERROR }
                                }
                                if (result == GattState.READY) {
                                    val ok = gattManager.writeCharacteristic(
                                        GattUuids.RESET_SHUTTER, byteArrayOf(0x01)
                                    )
                                    resetStatus = if (ok) "Shutter count reset to 0"
                                                  else "Write failed"
                                } else {
                                    resetStatus = "Could not connect to camera"
                                }
                                gattManager.disconnect()
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
                        text = "No history yet. Events are logged automatically.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            history.forEachIndexed { idx, entry ->
                                HistoryEntryRow(
                                    entry = entry,
                                    deviceType = device.deviceType,
                                    isExpanded = idx in expandedEntries,
                                    onToggle = {
                                        expandedEntries = if (idx in expandedEntries)
                                            expandedEntries - idx
                                        else
                                            expandedEntries + idx
                                    }
                                )
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
private fun StatItem(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = valueColor
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HistoryEntryRow(
    entry: DeviceHistoryEntry,
    deviceType: DeviceType,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatTimestamp(entry.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = eventBadgeColor(entry.eventType)
            ) {
                Text(
                    text = eventBadgeLabel(entry.eventType),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            if (deviceType == DeviceType.CAMERA) {
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
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (entry.voltageMillivolts > 0) "${"%.3f".format(entry.voltageMillivolts / 1000f)} V" else "--",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Int: ${entry.intBatteryPercent}% / ${"%.3f".format(entry.intVoltageMillivolts / 1000f)} V",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "RSSI: ${entry.rssi} dBm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (deviceType == DeviceType.CAMERA) {
                    Text(
                        text = "Camera: state=0x%02X flags=0x%02X".format(entry.cameraState, entry.cameraLiveFlags),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (entry.firmwareBuild.isNotEmpty()) {
                    Text(
                        text = "FW: ${entry.firmwareBuild}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun formatTimestamp(ts: Long): String =
    SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()).format(Date(ts))

private fun eventBadgeLabel(eventType: String) = when (eventType) {
    "Connect"         -> "Connect"
    "Disconnect"      -> "Disconnect"
    "Shutter"         -> "Shutter"
    "Battery"         -> "Battery"
    "ExtBattRemoved"  -> "Batt Out"
    "ExtBattAttached" -> "Batt In"
    else              -> eventType
}

@Composable
private fun eventBadgeColor(eventType: String): Color = when (eventType) {
    "Connect"         -> MaterialTheme.colorScheme.primary
    "Disconnect"      -> MaterialTheme.colorScheme.onSurfaceVariant
    "Shutter"         -> MaterialTheme.colorScheme.secondary
    "Battery"         -> MaterialTheme.colorScheme.tertiary
    "ExtBattRemoved"  -> MaterialTheme.colorScheme.error
    "ExtBattAttached" -> Color(0xFF2E7D32)
    else              -> MaterialTheme.colorScheme.surfaceVariant
}

// ---------------------------------------------------------------------------
// Settings screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSettingsScreen(
    device: BleDevice,
    gattManager: BleGattManager,
    historyStore: DeviceHistoryStore,
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

    // Camera logic — only populated when deviceType == CAMERA
    var camSettingsLoaded            by remember { mutableStateOf(false) }
    var camEnabled                   by remember { mutableStateOf(false) }
    var wakeHalfPressHoldSec         by remember { mutableStateOf("10000") }
    var minHalfPressBeforeShutter    by remember { mutableStateOf("500") }
    var shutterPulseDuration         by remember { mutableStateOf("100") }
    var startFrameSpacingTicks       by remember { mutableStateOf("1000") }
    var postShutterHpHoldTenths      by remember { mutableStateOf("2000") }
    var hpDebounceMs                 by remember { mutableStateOf("35") }
    var fpDebounceMs                 by remember { mutableStateOf("20") }
    var fullPressIgnoreGapTenths     by remember { mutableStateOf("3100") }
    var frameCount                   by remember { mutableStateOf("4") }
    var maxSequenceCount             by remember { mutableStateOf("4") }
    var wakeHoldRefreshPolicy        by remember { mutableIntStateOf(0) }
    var fullPressWithoutHpPolicy     by remember { mutableIntStateOf(0) }
    var fpAfterMaxSeqCountPolicy     by remember { mutableIntStateOf(0) }
    var powerSaveIdleMode            by remember { mutableStateOf(false) }
    var camSaveStatus                by remember { mutableStateOf<String?>(null) }

    // Serialize camera settings to 22-byte v3 blob
    fun buildCamBytes(): ByteArray {
        val shutter = ((shutterPulseDuration.toIntOrNull() ?: 100) / 10).coerceIn(1, 3000)
        val spacing = ((startFrameSpacingTicks.toIntOrNull() ?: 1000) / 10).coerceIn(1, 3000)
        val b = ByteArray(22)
        b[0]  = 3                                                            // version
        b[1]  = if (camEnabled) 1 else 0
        b[2]  = ((wakeHalfPressHoldSec.toIntOrNull() ?: 10000) / 1000).coerceIn(1, 60).toByte()
        b[3]  = ((minHalfPressBeforeShutter.toIntOrNull() ?: 500) / 100).coerceIn(1, 100).toByte()
        b[4]  = (shutter and 0xFF).toByte()                                  // shutterPulseDuration low
        b[5]  = ((shutter shr 8) and 0xFF).toByte()                          // shutterPulseDuration high
        b[6]  = (spacing and 0xFF).toByte()                                  // startFrameSpacingTicks low
        b[7]  = ((spacing shr 8) and 0xFF).toByte()                          // startFrameSpacingTicks high
        b[8]  = ((postShutterHpHoldTenths.toIntOrNull() ?: 2000) / 100).coerceIn(1, 200).toByte()
        b[9]  = (hpDebounceMs.toIntOrNull() ?: 35).coerceIn(1, 250).toByte()
        b[10] = (fpDebounceMs.toIntOrNull() ?: 20).coerceIn(1, 250).toByte()
        b[11] = (frameCount.toIntOrNull() ?: 4).coerceIn(0, 64).toByte()
        b[12] = (maxSequenceCount.toIntOrNull() ?: 4).coerceIn(0, 64).toByte()
        b[13] = wakeHoldRefreshPolicy.coerceIn(0, 2).toByte()
        b[14] = 0                                                            // halfPressDuringBurstPolicy (forced)
        b[15] = fullPressWithoutHpPolicy.coerceIn(0, 1).toByte()
        b[16] = 0                                                            // activityHalfPressHoldPolicy (forced)
        b[17] = fpAfterMaxSeqCountPolicy.coerceIn(0, 1).toByte()
        b[18] = 0                                                            // inputActivePolarity (forced by fw)
        b[19] = 0                                                            // outputDriveMode (forced by fw)
        b[20] = if (powerSaveIdleMode) 1 else 0
        b[21] = ((fullPressIgnoreGapTenths.toIntOrNull() ?: 3100) / 100).coerceIn(5, 250).toByte()
        val hex = b.joinToString(" ") { "%02X".format(it) }
        Log.d(TAG, "Write ${b.size} bytes: $hex")
        return b
    }

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

            if (device.deviceType == DeviceType.CAMERA) {
                val camBytes = withTimeoutOrNull(5_000) {
                    gattManager.readCharacteristic(GattUuids.CAMERA_CONFIG)
                }
                gattManager.enableNotification(GattUuids.CAMERA_CONFIG_STATUS)
                if (camBytes != null && camBytes.size >= 22 && (camBytes[0].toInt() and 0xFF) == 3) {
                    camEnabled                = camBytes[1].toInt() != 0
                    wakeHalfPressHoldSec      = ((camBytes[2].toInt() and 0xFF) * 1000).toString()
                    minHalfPressBeforeShutter = ((camBytes[3].toInt() and 0xFF) * 100).toString()
                    shutterPulseDuration      = ((((camBytes[5].toInt() and 0xFF) shl 8) or
                                                 (camBytes[4].toInt() and 0xFF)) * 10).toString()
                    startFrameSpacingTicks    = ((((camBytes[7].toInt() and 0xFF) shl 8) or
                                                 (camBytes[6].toInt() and 0xFF)) * 10).toString()
                    postShutterHpHoldTenths   = ((camBytes[8].toInt() and 0xFF) * 100).toString()
                    hpDebounceMs              = (camBytes[9].toInt() and 0xFF).toString()
                    fpDebounceMs              = (camBytes[10].toInt() and 0xFF).toString()
                    frameCount                = (camBytes[11].toInt() and 0xFF).coerceIn(0, 64).toString()
                    maxSequenceCount          = (camBytes[12].toInt() and 0xFF).coerceIn(0, 64).toString()
                    wakeHoldRefreshPolicy     = (camBytes[13].toInt() and 0xFF).coerceIn(0, 2)
                    fullPressWithoutHpPolicy  = (camBytes[15].toInt() and 0xFF).coerceIn(0, 1)
                    fpAfterMaxSeqCountPolicy  = (camBytes[17].toInt() and 0xFF).coerceIn(0, 1)
                    powerSaveIdleMode         = camBytes[20].toInt() != 0
                    fullPressIgnoreGapTenths  = ((camBytes[21].toInt() and 0xFF) * 100).toString()
                    camSettingsLoaded = true
                } else if (camBytes != null) {
                    camSaveStatus = "Unexpected format – reflash firmware"
                }
            }
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

            // ── Camera logic (CAMERA devices only) ─────────────────────────
            if (device.deviceType == DeviceType.CAMERA) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Logic Bypass", style = MaterialTheme.typography.bodyLarge)
                            Switch(checked = !camEnabled, onCheckedChange = { camEnabled = !it })
                        }
                    }
                }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("Sequence", style = MaterialTheme.typography.titleMedium)
                            NumberField(
                                label = "Frame Count (0–64)",
                                value = frameCount,
                                onValueChange = { frameCount = it },
                                maxDigits = 2
                            )
                            NumberField(
                                label = "Max Sequence Count (0–64)",
                                value = maxSequenceCount,
                                onValueChange = { maxSequenceCount = it },
                                maxDigits = 2
                            )
                        }
                    }
                }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("Timing", style = MaterialTheme.typography.titleMedium)
                            NumberField(
                                label = "Wake HP Hold (ms)",
                                value = wakeHalfPressHoldSec,
                                onValueChange = { wakeHalfPressHoldSec = it },
                                maxDigits = 5
                            )
                            NumberField(
                                label = "Min HP Before Shutter (ms)",
                                value = minHalfPressBeforeShutter,
                                onValueChange = { minHalfPressBeforeShutter = it },
                                maxDigits = 5
                            )
                            NumberField(
                                label = "Shutter Pulse Duration (ms)",
                                value = shutterPulseDuration,
                                onValueChange = { shutterPulseDuration = it },
                                maxDigits = 5
                            )
                            NumberField(
                                label = "Frame Spacing (ms)",
                                value = startFrameSpacingTicks,
                                onValueChange = { startFrameSpacingTicks = it },
                                maxDigits = 5
                            )
                            NumberField(
                                label = "Post-Shutter HP Hold (ms)",
                                value = postShutterHpHoldTenths,
                                onValueChange = { postShutterHpHoldTenths = it },
                                maxDigits = 5
                            )
                            NumberField(
                                label = "HP Debounce (ms)",
                                value = hpDebounceMs,
                                onValueChange = { hpDebounceMs = it }
                            )
                            NumberField(
                                label = "FP Debounce (ms)",
                                value = fpDebounceMs,
                                onValueChange = { fpDebounceMs = it }
                            )
                            NumberField(
                                label = "FP Ignore Gap (ms)",
                                value = fullPressIgnoreGapTenths,
                                onValueChange = { fullPressIgnoreGapTenths = it },
                                maxDigits = 5
                            )
                        }
                    }
                }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("Policies", style = MaterialTheme.typography.titleMedium)
                            PolicyDropdown(
                                label = "Wake Hold Refresh",
                                options = listOf("Extend", "Restart", "Ignore While Active"),
                                selected = wakeHoldRefreshPolicy,
                                onSelect = { wakeHoldRefreshPolicy = it }
                            )
                            PolicyDropdown(
                                label = "FP Without Prior HP",
                                options = listOf("Assert HP Then Wait", "Ignore FP"),
                                selected = fullPressWithoutHpPolicy,
                                onSelect = { fullPressWithoutHpPolicy = it }
                            )
                            PolicyDropdown(
                                label = "FP After Max Sequences",
                                options = listOf("Ignore Until Activity End"),
                                selected = fpAfterMaxSeqCountPolicy,
                                onSelect = { fpAfterMaxSeqCountPolicy = it }
                            )
                        }
                    }
                }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Power Save Idle Mode", style = MaterialTheme.typography.bodyLarge)
                            Switch(
                                checked = powerSaveIdleMode,
                                onCheckedChange = { powerSaveIdleMode = it }
                            )
                        }
                    }
                }

                item {
                    camSaveStatus?.let {
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
                                val notifDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                                    gattManager.waitForNotification(
                                        GattUuids.CAMERA_CONFIG_STATUS, 3000
                                    )
                                }
                                val packet = buildCamBytes()
                                val written = gattManager.writeCharacteristic(
                                    GattUuids.CAMERA_CONFIG, packet
                                )
                                if (!written) {
                                    notifDeferred.cancel()
                                    camSaveStatus = "Save failed \u2013 not connected"
                                } else {
                                    val ack = notifDeferred.await()
                                    val ackByte = ack?.firstOrNull()?.toInt()?.and(0xFF)
                                    camSaveStatus = when (ackByte) {
                                        0x00 -> "Saved successfully"
                                        0xE1 -> "Rejected: bad packet format"
                                        0xE2 -> "Rejected: value out of range"
                                        0xE3 -> "Rejected: device busy \u2013 try again"
                                        null -> "Sent (no acknowledgment)"
                                        else -> "Unknown response (0x%02X)".format(ackByte)
                                    }
                                }
                            }
                        },
                        enabled = camSettingsLoaded,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save Camera Logic")
                    }
                }
            }

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

            // ── Device Info card ───────────────────────────────────────────
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Device Info", style = MaterialTheme.typography.titleMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            StatItem("Chemistry", device.batteryChemistry.displayName())
                            StatItem("Cells", "${device.cellCount}")
                            StatItem("Signal", "${device.rssi} dBm")
                        }
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

            item {
                val context = LocalContext.current
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            val file = historyStore.getFile(device.address)
                            if (!file.exists()) return@launch
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.provider",
                                file
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_SUBJECT, "Device Log: ${if (device.groupId != 0) "Kit ${device.groupId} - " else ""}${device.name ?: device.address}")
                                putExtra(Intent.EXTRA_TEXT, "MAC: ${device.address}\n\nSent from Camtraption Assistant App")
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Send Log"))
                        }
                    }
                ) {
                    Text("Send Log")
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

// ---------------------------------------------------------------------------
// Policy dropdown (reusable within this file)
// ---------------------------------------------------------------------------

@Composable
private fun PolicyDropdown(
    label: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    EnumDropdown(
        label = label,
        selected = selected,
        options = options.indices.toList(),
        onSelect = onSelect,
        displayName = { options.getOrElse(it) { "Unknown ($it)" } }
    )
}
