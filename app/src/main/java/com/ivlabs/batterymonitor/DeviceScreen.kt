package com.ivlabs.batterymonitor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    device: BleDevice,
    gattManager: BleGattManager,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    fun disconnect() { gattManager.disconnect() }

    BackHandler { disconnect(); onBack() }

    // Editable fields – seeded from advertisement data, overwritten by GATT reads when ready
    var nameInput  by remember { mutableStateOf(device.name ?: "") }
    var typeInput  by remember { mutableStateOf(device.deviceType) }
    var chemInput  by remember { mutableStateOf(device.batteryChemistry) }
    var cellInput  by remember { mutableStateOf(device.cellCount.toString()) }
    var groupInput by remember { mutableStateOf(device.groupId.toString()) }
    var saveStatus by remember { mutableStateOf<String?>(null) }

    // When GATT connects, read all settings characteristics to populate fields
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
                ?.firstOrNull()?.let { cellInput = (it.toInt() and 0xFF).toString() }
            gattManager.readCharacteristic(GattUuids.GROUP_ID)
                ?.firstOrNull()?.let { groupInput = (it.toInt() and 0xFF).toString() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(device.name ?: device.address) },
                navigationIcon = {
                    IconButton(onClick = { disconnect(); onBack() }) {
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
                        Text("Settings", style = MaterialTheme.typography.titleMedium)

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

                        NumberField(
                            label = "Cell Count (1–8)",
                            value = cellInput,
                            onValueChange = { cellInput = it },
                            maxDigits = 1
                        )

                        NumberField(
                            label = "Group ID (0 = no group)",
                            value = groupInput,
                            onValueChange = { groupInput = it },
                            maxDigits = 3
                        )
                    }
                }
            }

            if (device.deviceType == DeviceType.CAMERA) {
                item {
                    CameraCard(device, gattManager) {
                        scope.launch {
                            gattManager.writeCharacteristic(GattUuids.RESET_SHUTTER, byteArrayOf(0x01))
                        }
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
                            val ok = writeAllSettings(gattManager, nameInput, typeInput, chemInput, cellInput, groupInput)
                            saveStatus = if (ok) "Saved successfully" else "Save failed – not connected"
                        }
                    },
                    enabled = gattManager.state == GattState.READY,
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
                            disconnect()
                            onBack()
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

private suspend fun writeAllSettings(
    gattManager: BleGattManager,
    name: String,
    type: DeviceType,
    chemistry: BatteryChemistry,
    cellCount: String,
    groupId: String
): Boolean {
    if (gattManager.state != GattState.READY) return false
    var ok = gattManager.writeCharacteristic(GattUuids.DEVICE_NAME, name.toByteArray(Charsets.UTF_8))
    ok = ok && gattManager.writeCharacteristic(GattUuids.DEVICE_TYPE,  byteArrayOf(type.ordinal.toByte()))
    ok = ok && gattManager.writeCharacteristic(GattUuids.CHEMISTRY,    byteArrayOf(chemistry.ordinal.toByte()))
    ok = ok && gattManager.writeCharacteristic(GattUuids.CELL_COUNT,   byteArrayOf((cellCount.toIntOrNull() ?: 1).toByte()))
    ok = ok && gattManager.writeCharacteristic(GattUuids.GROUP_ID,     byteArrayOf((groupId.toIntOrNull() ?: 0).toByte()))
    return ok
}
