package com.ivlabs.batterymonitor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Camera Config screen – reads/writes the 19-byte CAMERA_CONFIG characteristic
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraConfigScreen(
    device: BleDevice,
    gattManager: BleGattManager,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    BackHandler { gattManager.disconnect(); onBack() }

    // ── Per-field state ───────────────────────────────────────────────────────
    var enabled                    by remember { mutableStateOf(false) }
    var wakeHalfPressHoldSec       by remember { mutableStateOf("10") }
    var minHalfPressBeforeShutter  by remember { mutableStateOf("5") }
    var shutterPulseDuration       by remember { mutableStateOf("10") }
    var startFrameSpacingTenths    by remember { mutableStateOf("10") }
    var postShutterHpHoldTenths    by remember { mutableStateOf("20") }
    var hpDebounceMs               by remember { mutableStateOf("35") }
    var fpDebounceMs               by remember { mutableStateOf("20") }
    var frameCount                 by remember { mutableIntStateOf(4) }
    var maxSequenceCount           by remember { mutableIntStateOf(4) }
    var wakeHoldRefreshPolicy      by remember { mutableIntStateOf(0) }
    var fullPressWithoutHpPolicy   by remember { mutableIntStateOf(0) }
    var fpAfterMaxSeqCountPolicy   by remember { mutableIntStateOf(0) }
    var inputActivePolarity        by remember { mutableIntStateOf(0) }
    var outputDriveMode            by remember { mutableIntStateOf(0) }
    var powerSaveIdleMode          by remember { mutableStateOf(false) }
    var saveStatus                 by remember { mutableStateOf<String?>(null) }

    // ── Read from device when GATT is ready ───────────────────────────────────
    LaunchedEffect(gattManager.state) {
        if (gattManager.state == GattState.READY) {
            val bytes = gattManager.readCharacteristic(GattUuids.CAMERA_CONFIG)
            if (bytes != null && bytes.size >= 19) {
                enabled                   = bytes[1].toInt() != 0
                wakeHalfPressHoldSec      = (bytes[2].toInt() and 0xFF).toString()
                minHalfPressBeforeShutter = (bytes[3].toInt() and 0xFF).toString()
                shutterPulseDuration      = (bytes[4].toInt() and 0xFF).toString()
                startFrameSpacingTenths   = (bytes[5].toInt() and 0xFF).toString()
                postShutterHpHoldTenths   = (bytes[6].toInt() and 0xFF).toString()
                hpDebounceMs              = (bytes[7].toInt() and 0xFF).toString()
                fpDebounceMs              = (bytes[8].toInt() and 0xFF).toString()
                frameCount                = (bytes[9].toInt() and 0xFF).coerceIn(1, 8)
                maxSequenceCount          = (bytes[10].toInt() and 0xFF).coerceIn(1, 8)
                wakeHoldRefreshPolicy     = (bytes[11].toInt() and 0xFF).coerceIn(0, 2)
                fullPressWithoutHpPolicy  = (bytes[13].toInt() and 0xFF).coerceIn(0, 1)
                fpAfterMaxSeqCountPolicy  = (bytes[15].toInt() and 0xFF).coerceIn(0, 0)
                inputActivePolarity       = (bytes[16].toInt() and 0xFF).coerceIn(0, 1)
                outputDriveMode           = (bytes[17].toInt() and 0xFF).coerceIn(0, 1)
                powerSaveIdleMode         = bytes[18].toInt() != 0
            }
        }
    }

    // ── Serialize to 19-byte blob ─────────────────────────────────────────────
    fun buildBytes(): ByteArray {
        fun str(s: String, default: Int) = (s.toIntOrNull() ?: default).coerceIn(0, 255).toByte()
        val b = ByteArray(19)
        b[0]  = 1                                                    // version
        b[1]  = if (enabled) 1 else 0
        b[2]  = str(wakeHalfPressHoldSec, 10)
        b[3]  = str(minHalfPressBeforeShutter, 5)
        b[4]  = str(shutterPulseDuration, 10)
        b[5]  = str(startFrameSpacingTenths, 10)
        b[6]  = str(postShutterHpHoldTenths, 20)
        b[7]  = str(hpDebounceMs, 35)
        b[8]  = str(fpDebounceMs, 20)
        b[9]  = frameCount.toByte()
        b[10] = maxSequenceCount.toByte()
        b[11] = wakeHoldRefreshPolicy.toByte()
        b[12] = 0                                                    // halfPressDuringBurstPolicy
        b[13] = fullPressWithoutHpPolicy.toByte()
        b[14] = 0                                                    // activityHalfPressHoldPolicy
        b[15] = fpAfterMaxSeqCountPolicy.toByte()
        b[16] = inputActivePolarity.toByte()
        b[17] = outputDriveMode.toByte()
        b[18] = if (powerSaveIdleMode) 1 else 0
        return b
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Camera Logic") },
                navigationIcon = {
                    IconButton(onClick = { gattManager.disconnect(); onBack() }) {
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

            // ── Enable toggle ──────────────────────────────────────────────
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Camera Logic Enabled", style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                }
            }

            // ── Timing ────────────────────────────────────────────────────
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Timing", style = MaterialTheme.typography.titleMedium)
                        NumberField(
                            label = "Wake HP Hold (sec)",
                            value = wakeHalfPressHoldSec,
                            onValueChange = { wakeHalfPressHoldSec = it }
                        )
                        NumberField(
                            label = "Min HP Before Shutter (\u00d7100\u202fms)",
                            value = minHalfPressBeforeShutter,
                            onValueChange = { minHalfPressBeforeShutter = it }
                        )
                        NumberField(
                            label = "Shutter Pulse Duration (\u00d710\u202fms)",
                            value = shutterPulseDuration,
                            onValueChange = { shutterPulseDuration = it }
                        )
                        NumberField(
                            label = "Frame Spacing (\u00d7100\u202fms)",
                            value = startFrameSpacingTenths,
                            onValueChange = { startFrameSpacingTenths = it }
                        )
                        NumberField(
                            label = "Post-Shutter HP Hold (\u00d7100\u202fms)",
                            value = postShutterHpHoldTenths,
                            onValueChange = { postShutterHpHoldTenths = it }
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
                    }
                }
            }

            // ── Sequence ──────────────────────────────────────────────────
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Sequence", style = MaterialTheme.typography.titleMedium)

                        Text(
                            "Frame Count: $frameCount",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Slider(
                            value = frameCount.toFloat(),
                            onValueChange = { frameCount = it.toInt().coerceIn(1, 8) },
                            valueRange = 1f..8f,
                            steps = 6
                        )

                        Text(
                            "Max Sequence Count: $maxSequenceCount",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Slider(
                            value = maxSequenceCount.toFloat(),
                            onValueChange = { maxSequenceCount = it.toInt().coerceIn(1, 8) },
                            valueRange = 1f..8f,
                            steps = 6
                        )
                    }
                }
            }

            // ── Policies ──────────────────────────────────────────────────
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

            // ── I/O ───────────────────────────────────────────────────────
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("I/O", style = MaterialTheme.typography.titleMedium)

                        PolicyDropdown(
                            label = "Input Active Polarity",
                            options = listOf("Active Low", "Active High"),
                            selected = inputActivePolarity,
                            onSelect = { inputActivePolarity = it }
                        )
                        PolicyDropdown(
                            label = "Output Drive Mode",
                            options = listOf("Open Drain", "Push-Pull"),
                            selected = outputDriveMode,
                            onSelect = { outputDriveMode = it }
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
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
            }

            // ── Save ──────────────────────────────────────────────────────
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
                            val ok = gattManager.writeCharacteristic(
                                GattUuids.CAMERA_CONFIG, buildBytes()
                            )
                            saveStatus = if (ok) "Saved successfully"
                                         else "Save failed \u2013 not connected"
                        }
                    },
                    enabled = gattManager.state == GattState.READY,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save")
                }
            }
        }
    }
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
