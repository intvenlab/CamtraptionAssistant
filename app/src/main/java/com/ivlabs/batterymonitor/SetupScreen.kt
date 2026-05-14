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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
fun SetupScreen(
    device: BleDevice,
    gattManager: BleGattManager,
    onFinish: () -> Unit
) {
    val scope = rememberCoroutineScope()

    fun disconnect() { gattManager.disconnect() }

    BackHandler { disconnect(); onFinish() }

    var nameInput  by remember { mutableStateOf("") }
    var typeInput  by remember { mutableStateOf(DeviceType.BATTERY_MONITOR) }
    var chemInput  by remember { mutableStateOf(BatteryChemistry.LIPO) }
    var cellInput  by remember { mutableStateOf(1) }
    var groupInput by remember { mutableStateOf(0) }
    var saveStatus by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setup Device") },
                navigationIcon = {
                    IconButton(onClick = { disconnect(); onFinish() }) {
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
            item {
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                GattStatusBanner(gattManager)
            }

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
                            val ok = writeSetupSettings(
                                gattManager, nameInput, typeInput, chemInput,
                                cellInput, groupInput
                            )
                            if (ok) {
                                disconnect()
                                onFinish()
                            } else {
                                saveStatus = "Save failed – not connected"
                            }
                        }
                    },
                    enabled = gattManager.state == GattState.READY && nameInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save & Finish")
                }
            }
        }
    }
}

private suspend fun writeSetupSettings(
    gattManager: BleGattManager,
    name: String,
    type: DeviceType,
    chemistry: BatteryChemistry,
    cellCount: Int,
    groupId: Int
): Boolean {
    if (gattManager.state != GattState.READY) return false
    var ok = gattManager.writeCharacteristic(GattUuids.DEVICE_NAME, name.toByteArray(Charsets.UTF_8))
    ok = ok && gattManager.writeCharacteristic(GattUuids.DEVICE_TYPE, byteArrayOf(type.ordinal.toByte()))
    ok = ok && gattManager.writeCharacteristic(GattUuids.CHEMISTRY,   byteArrayOf(chemistry.ordinal.toByte()))
    ok = ok && gattManager.writeCharacteristic(GattUuids.CELL_COUNT,  byteArrayOf(cellCount.toByte()))
    ok = ok && gattManager.writeCharacteristic(GattUuids.GROUP_ID,    byteArrayOf(groupId.toByte()))
    return ok
}
