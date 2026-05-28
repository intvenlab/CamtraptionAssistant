package com.ivlabs.batterymonitor

import android.util.Log
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "CameraConfig"

// ---------------------------------------------------------------------------
// Camera Config screen – reads/writes the 22-byte v3 CAMERA_CONFIG characteristic
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
    var settingsLoaded             by remember { mutableStateOf(false) }
    var enabled                    by remember { mutableStateOf(false) }
    var wakeHalfPressHoldSec       by remember { mutableStateOf("10") }
    var minHalfPressBeforeShutter  by remember { mutableStateOf("5") }
    var shutterPulseDuration       by remember { mutableStateOf("10") }
    var startFrameSpacingTicks     by remember { mutableStateOf("100") }
    var postShutterHpHoldTenths    by remember { mutableStateOf("20") }
    var hpDebounceMs               by remember { mutableStateOf("35") }
    var fpDebounceMs               by remember { mutableStateOf("20") }
    var fullPressIgnoreGapTenths   by remember { mutableStateOf("31") }
    var frameCount                 by remember { mutableIntStateOf(4) }
    var maxSequenceCount           by remember { mutableIntStateOf(4) }
    var wakeHoldRefreshPolicy      by remember { mutableIntStateOf(0) }
    var fullPressWithoutHpPolicy   by remember { mutableIntStateOf(0) }
    var fpAfterMaxSeqCountPolicy   by remember { mutableIntStateOf(0) }
    var powerSaveIdleMode          by remember { mutableStateOf(false) }
    var saveStatus                 by remember { mutableStateOf<String?>(null) }
    // Diagnostic: shows MTU + raw read bytes so we can verify the packet without adb
    var diagInfo                   by remember { mutableStateOf("") }

    // ── Read from device when GATT is ready ───────────────────────────────────
    LaunchedEffect(gattManager.state) {
        if (gattManager.state == GattState.READY) {
            val bytes = withTimeoutOrNull(5_000) {
                gattManager.readCharacteristic(GattUuids.CAMERA_CONFIG)
            }
            // Enable the ack notification AFTER the read so the CCCD descriptor write
            // doesn't interfere with the pending read operation.
            gattManager.enableNotification(GattUuids.CAMERA_CONFIG_STATUS)

            if (bytes == null) {
                saveStatus = "Read timed out – reconnect and try again"
                Log.e(TAG, "readCharacteristic timed out")
                diagInfo = "MTU:${gattManager.negotiatedMtu}  READ TIMEOUT"
                return@LaunchedEffect
            }

            val hex = bytes.joinToString(" ") { "%02X".format(it) }
            diagInfo = "MTU:${gattManager.negotiatedMtu}  Read(${bytes.size}): $hex"
            Log.d(TAG, "Read ${bytes.size} bytes: $hex")

            if (bytes.size < 22 || (bytes[0].toInt() and 0xFF) != 3) {
                saveStatus = "Unexpected format (size=${bytes.size}, ver=${bytes.getOrNull(0)?.toInt()?.and(0xFF)})"
                Log.e(TAG, "Bad format — size=${bytes.size}, ver=${bytes.getOrNull(0)?.toInt()?.and(0xFF)}")
                return@LaunchedEffect
            }

            // v3 parse — byte offsets match CameraConfig struct in config.h
            enabled                   = bytes[1].toInt() != 0
            wakeHalfPressHoldSec      = (bytes[2].toInt() and 0xFF).toString()
            minHalfPressBeforeShutter = (bytes[3].toInt() and 0xFF).toString()
            shutterPulseDuration      = (((bytes[5].toInt() and 0xFF) shl 8) or
                                         (bytes[4].toInt() and 0xFF)).toString()
            startFrameSpacingTicks    = (((bytes[7].toInt() and 0xFF) shl 8) or
                                         (bytes[6].toInt() and 0xFF)).toString()
            postShutterHpHoldTenths   = (bytes[8].toInt() and 0xFF).toString()
            hpDebounceMs              = (bytes[9].toInt() and 0xFF).toString()
            fpDebounceMs              = (bytes[10].toInt() and 0xFF).toString()
            frameCount                = (bytes[11].toInt() and 0xFF).coerceIn(1, 8)
            maxSequenceCount          = (bytes[12].toInt() and 0xFF).coerceIn(1, 64)
            wakeHoldRefreshPolicy     = (bytes[13].toInt() and 0xFF).coerceIn(0, 2)
            fullPressWithoutHpPolicy  = (bytes[15].toInt() and 0xFF).coerceIn(0, 1)
            fpAfterMaxSeqCountPolicy  = (bytes[17].toInt() and 0xFF).coerceIn(0, 1)
            powerSaveIdleMode         = bytes[20].toInt() != 0
            fullPressIgnoreGapTenths  = (bytes[21].toInt() and 0xFF).toString()
            settingsLoaded = true
        }
    }

    // ── Serialize to 22-byte v3 blob ──────────────────────────────────────────
    fun buildBytes(): ByteArray {
        val shutter = (shutterPulseDuration.toIntOrNull() ?: 10).coerceIn(1, 3000)
        val spacing = (startFrameSpacingTicks.toIntOrNull() ?: 100).coerceIn(1, 3000)
        val b = ByteArray(22)
        b[0]  = 3                                                            // version
        b[1]  = if (enabled) 1 else 0
        b[2]  = (wakeHalfPressHoldSec.toIntOrNull() ?: 10).coerceIn(1, 60).toByte()
        b[3]  = (minHalfPressBeforeShutter.toIntOrNull() ?: 5).coerceIn(1, 100).toByte()
        b[4]  = (shutter and 0xFF).toByte()                                  // shutterPulseDuration low
        b[5]  = ((shutter shr 8) and 0xFF).toByte()                          // shutterPulseDuration high
        b[6]  = (spacing and 0xFF).toByte()                                  // startFrameSpacingTicks low
        b[7]  = ((spacing shr 8) and 0xFF).toByte()                          // startFrameSpacingTicks high
        b[8]  = (postShutterHpHoldTenths.toIntOrNull() ?: 20).coerceIn(1, 200).toByte()
        b[9]  = (hpDebounceMs.toIntOrNull() ?: 35).coerceIn(1, 250).toByte()
        b[10] = (fpDebounceMs.toIntOrNull() ?: 20).coerceIn(1, 250).toByte()
        b[11] = frameCount.coerceIn(1, 8).toByte()
        b[12] = maxSequenceCount.coerceIn(1, 64).toByte()
        b[13] = wakeHoldRefreshPolicy.coerceIn(0, 2).toByte()
        b[14] = 0                                                            // halfPressDuringBurstPolicy (forced)
        b[15] = fullPressWithoutHpPolicy.coerceIn(0, 1).toByte()
        b[16] = 0                                                            // activityHalfPressHoldPolicy (forced)
        b[17] = fpAfterMaxSeqCountPolicy.coerceIn(0, 1).toByte()
        b[18] = 0                                                            // inputActivePolarity (forced by fw)
        b[19] = 0                                                            // outputDriveMode (forced by fw)
        b[20] = if (powerSaveIdleMode) 1 else 0
        b[21] = (fullPressIgnoreGapTenths.toIntOrNull() ?: 31).coerceIn(5, 250).toByte()
        val hex = b.joinToString(" ") { "%02X".format(it) }
        Log.d(TAG, "Write ${b.size} bytes: $hex")
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

            // ── Diagnostics (MTU + raw read bytes) ─────────────────────────
            if (diagInfo.isNotEmpty()) {
                item {
                    Text(
                        text = diagInfo,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

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
                            onValueChange = { shutterPulseDuration = it },
                            maxDigits = 4
                        )
                        NumberField(
                            label = "Frame Spacing (\u00d710\u202fms)",
                            value = startFrameSpacingTicks,
                            onValueChange = { startFrameSpacingTicks = it },
                            maxDigits = 4
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
                        NumberField(
                            label = "FP Ignore Gap (\u00d7100\u202fms)",
                            value = fullPressIgnoreGapTenths,
                            onValueChange = { fullPressIgnoreGapTenths = it }
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
                            onValueChange = { maxSequenceCount = it.toInt().coerceIn(1, 64) },
                            valueRange = 1f..64f,
                            steps = 62
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

            // ── Power Save ────────────────────────────────────────────────
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

            // ── Save ──────────────────────────────────────────────────────
            item {
                if (!settingsLoaded && gattManager.state == GattState.READY) {
                    Text(
                        "Reading settings from device\u2026",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                }
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
                            // UNDISPATCHED runs the child coroutine inline up to its first
                            // suspension point, guaranteeing pendingNotifyCont is set before
                            // gatt.writeCharacteristic() fires (eliminates the race window).
                            val notifDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                                gattManager.waitForNotification(
                                    GattUuids.CAMERA_CONFIG_STATUS, 3000
                                )
                            }
                            val packet = buildBytes()
                            val written = gattManager.writeCharacteristic(
                                GattUuids.CAMERA_CONFIG, packet
                            )
                            if (!written) {
                                notifDeferred.cancel()
                                saveStatus = "Save failed \u2013 not connected"
                            } else {
                                val ack = notifDeferred.await()
                                val ackByte = ack?.firstOrNull()?.toInt()?.and(0xFF)
                                Log.d(TAG, "Ack: ${ackByte?.let { "0x%02X".format(it) } ?: "null (timeout)"}")
                                saveStatus = when (ackByte) {
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
                    enabled = settingsLoaded,
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
