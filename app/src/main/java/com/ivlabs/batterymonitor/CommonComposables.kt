package com.ivlabs.batterymonitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// GATT status banner
// ---------------------------------------------------------------------------

@Composable
fun GattStatusBanner(gattManager: BleGattManager) {
    val (text, color) = when (gattManager.state) {
        GattState.IDLE        -> "Not connected" to MaterialTheme.colorScheme.onSurfaceVariant
        GattState.CONNECTING  -> "Connecting…"   to MaterialTheme.colorScheme.tertiary
        GattState.READY       -> "Connected"      to MaterialTheme.colorScheme.primary
        GattState.ERROR       -> (gattManager.errorMessage ?: "Connection error") to MaterialTheme.colorScheme.error
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color
    )
}

// ---------------------------------------------------------------------------
// Battery info card (read-only, sourced from advertisement)
// ---------------------------------------------------------------------------

@Composable
fun BatteryInfoCard(device: BleDevice) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Battery", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${"%.3f".format(device.voltageMillivolts / 1000f)} V",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "${device.batteryPercent}%",
                    style = MaterialTheme.typography.titleLarge,
                    color = batteryDisplayColor(device.batteryPercent)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { device.batteryPercent / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = batteryDisplayColor(device.batteryPercent)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${device.rssi} dBm  •  ${device.cellCount} cell  •  ${device.batteryChemistry.displayName()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Camera card (shutter count + reset)
// ---------------------------------------------------------------------------

@Composable
fun CameraCard(
    device: BleDevice,
    onReset: () -> Unit,
    resetStatus: String? = null,
    resetEnabled: Boolean = true
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Camera", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Shutter Count", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = device.shutterCount.toString(),
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
                Button(
                    onClick = onReset,
                    enabled = resetEnabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text("Reset")
                }
            }
            resetStatus?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("Shutter")) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                )
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "State",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    device.cameraState.cameraStateLabel(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (device.cameraState == 3) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                )
            }
            val activityActive = (device.cameraLiveFlags and 0x01) != 0
            val hpAsserted     = (device.cameraLiveFlags and 0x02) != 0
            if (activityActive || hpAsserted) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (activityActive) Text(
                        "● Activity",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (hpAsserted) Text(
                        "● HP Out",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Generic enum dropdown
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> EnumDropdown(
    label: String,
    selected: T,
    options: List<T>,
    onSelect: (T) -> Unit,
    displayName: (T) -> String
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = displayName(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(displayName(option)) },
                    onClick = { onSelect(option); expanded = false },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared number field
// ---------------------------------------------------------------------------

@Composable
fun NumberField(label: String, value: String, onValueChange: (String) -> Unit, maxDigits: Int = 3) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= maxDigits && it.all { c -> c.isDigit() }) onValueChange(it) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

// ---------------------------------------------------------------------------
// Cell count dropdown (1–4)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CellCountDropdown(selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = "$selected cell${if (selected > 1) "s" else ""}",
            onValueChange = {}, readOnly = true,
            label = { Text("Cell Count") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (1..4).forEach { count ->
                DropdownMenuItem(
                    text = { Text("$count cell${if (count > 1) "s" else ""}") },
                    onClick = { onSelect(count); expanded = false },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Group ID dropdown (0 = No Group, 1–15)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDropdown(selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = if (selected == 0) "No Group" else "Group $selected"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label, onValueChange = {}, readOnly = true,
            label = { Text("Group") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (0..15).forEach { id ->
                DropdownMenuItem(
                    text = { Text(if (id == 0) "No Group (0)" else "Group $id") },
                    onClick = { onSelect(id); expanded = false },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Color helper (internal to this module)
// ---------------------------------------------------------------------------

@Composable
internal fun batteryDisplayColor(percent: Int): Color = when {
    percent > 50 -> MaterialTheme.colorScheme.primary
    percent > 20 -> MaterialTheme.colorScheme.tertiary
    else         -> MaterialTheme.colorScheme.error
}
