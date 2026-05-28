package com.ivlabs.batterymonitor

import android.bluetooth.BluetoothDevice
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreen(
    groupId: Int,
    groupName: String?,
    allDevices: List<BleDevice>,
    gattManager: BleGattManager,
    cameraDevice: BluetoothDevice?,
    onGroupNameChange: (groupId: Int, name: String) -> Unit,
    onNavigateToDevice: (BleDevice) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val scope = rememberCoroutineScope()
    val devices = allDevices
        .filter { it.groupId == groupId }
        .sortedWith(compareBy({ it.deviceType.sortOrder() }, { it.name ?: it.address }))

    var nameInput       by remember(groupName) { mutableStateOf(groupName ?: "") }
    val camera          = devices.firstOrNull { it.deviceType == DeviceType.CAMERA }
    var showConfirm     by remember { mutableStateOf(false) }
    var resetStatus     by remember { mutableStateOf<String?>(null) }
    var resetInProgress by remember { mutableStateOf(false) }

    // Confirmation dialog
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Reset Shutter Count?") },
            text  = {
                Text(
                    "This will reset the shutter count to 0 on " +
                    "${camera?.name ?: camera?.address ?: "the camera"}. " +
                    "This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        if (cameraDevice != null) {
                            resetInProgress = true
                            resetStatus = null
                            scope.launch {
                                resetStatus = "Connecting…"
                                gattManager.connect(cameraDevice)
                                val result = withTimeoutOrNull(8_000) {
                                    snapshotFlow { gattManager.state }
                                        .first { s -> s == GattState.READY || s == GattState.ERROR }
                                }
                                if (result == GattState.READY) {
                                    val ok = gattManager.writeCharacteristic(
                                        GattUuids.RESET_SHUTTER, byteArrayOf(0x01)
                                    )
                                    resetStatus = if (ok) "Shutter count reset to 0" else "Write failed"
                                } else {
                                    resetStatus = "Could not connect to camera"
                                }
                                gattManager.disconnect()
                                resetInProgress = false
                            }
                        }
                    }
                ) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(nameInput.ifBlank { "Kit $groupId" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (devices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text("No devices in this group", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { input ->
                            if (input.length <= 20) {
                                nameInput = input
                                onGroupNameChange(groupId, input)
                            }
                        },
                        label = { Text("Kit Description - (Optional)") },
                        placeholder = { Text("Kit $groupId") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                camera?.let { cam ->
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("\uD83D\uDCF7", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "Shutter Count:",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "%,d".format(cam.shutterCount),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(
                                onClick = { showConfirm = true },
                                enabled = !resetInProgress,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text(if (resetInProgress) "Resetting…" else "Reset")
                            }
                        }
                    }

                    resetStatus?.let { status ->
                        item {
                            Text(
                                text = status,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (status.startsWith("Shutter"))
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                }

                items(devices, key = { it.address }) { device ->
                    IndividualDeviceCard(
                        device = device,
                        onClick = { onNavigateToDevice(device) }
                    )
                }
            }
        }
    }
}
