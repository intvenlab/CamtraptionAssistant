package com.ivlabs.batterymonitor

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ivlabs.batterymonitor.ui.theme.BatteryMonitorTheme

data class BleDevice(
    val address: String,
    val name: String?,
    val batteryPercent: Int,
    val voltageMillivolts: Int,
    val rssi: Int,
    val lastSeen: Long
)

class MainActivity : ComponentActivity() {

    companion object {
        private const val COMPANY_ID = 0xFFFF
        private const val STALE_TIMEOUT_MS = 10_000L
    }

    private val bluetoothAdapter by lazy {
        getSystemService(BluetoothManager::class.java).adapter
    }

    private val devices = mutableStateMapOf<String, BleDevice>()
    private var isScanning by mutableStateOf(false)

    private val handler = Handler(Looper.getMainLooper())
    private val staleDeviceRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            devices.keys.toList()
                .filter { now - (devices[it]?.lastSeen ?: 0L) > STALE_TIMEOUT_MS }
                .forEach { devices.remove(it) }
            if (isScanning) handler.postDelayed(this, 1_000L)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            parseScanResult(result)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach { parseScanResult(it) }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) startScanning()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BatteryMonitorTheme {
                BleMonitorScreen(
                    devices = devices.values.sortedByDescending { it.rssi },
                    isScanning = isScanning,
                    onToggleScan = ::toggleScanning,
                    onSaveName = ::saveDeviceName,
                    onReset = ::resetDevice
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isScanning) stopScanning()
    }

    private fun parseScanResult(result: ScanResult) {
        val data = result.scanRecord?.getManufacturerSpecificData(COMPANY_ID) ?: return
        if (data.size < 3) return

        val batteryPercent = data[0].toInt() and 0xFF
        val voltageMillivolts = ((data[2].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)

        val device = BleDevice(
            address = result.device.address,
            name = result.scanRecord?.deviceName,
            batteryPercent = batteryPercent,
            voltageMillivolts = voltageMillivolts,
            rssi = result.rssi,
            lastSeen = System.currentTimeMillis()
        )
        runOnUiThread { devices[device.address] = device }
    }

    private fun saveDeviceName(device: BleDevice, name: String) {
        // TODO: Connect to device via BLE GATT and write the new name to a writable characteristic.
        // The firmware needs a custom GATT service with a characteristic that persists the name
        // to EEPROM/flash. UUIDs to be defined once firmware specs are confirmed.
    }

    private fun resetDevice(device: BleDevice) {
        // TODO: Send reset command to device via BLE GATT or UART.
        // Implementation pending firmware specs (pin signal or UART message - TBD).
    }

    private fun hasPermissions(): Boolean {
        return buildPermissionList().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun toggleScanning() {
        if (isScanning) {
            stopScanning()
        } else if (hasPermissions()) {
            startScanning()
        } else {
            permissionLauncher.launch(buildPermissionList().toTypedArray())
        }
    }

    private fun startScanning() {
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, scanCallback)
        isScanning = true
        handler.post(staleDeviceRunnable)
    }

    private fun stopScanning() {
        bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
        isScanning = false
        handler.removeCallbacks(staleDeviceRunnable)
    }

    private fun buildPermissionList(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleMonitorScreen(
    devices: List<BleDevice>,
    isScanning: Boolean,
    onToggleScan: () -> Unit,
    onSaveName: (BleDevice, String) -> Unit,
    onReset: (BleDevice) -> Unit
) {
    var selectedDevice by remember { mutableStateOf<BleDevice?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Camtraptions Battery Monitor") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onToggleScan) {
                Icon(
                    imageVector = if (isScanning) Icons.Default.Close else Icons.Default.PlayArrow,
                    contentDescription = if (isScanning) "Stop scanning" else "Start scanning"
                )
            }
        }
    ) { innerPadding ->
        if (devices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isScanning) "Scanning for devices..." else "Tap \u25B6 to start scanning",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(devices, key = { it.address }) { device ->
                    DeviceCard(
                        device = device,
                        onClick = { selectedDevice = device }
                    )
                }
            }
        }
    }

    selectedDevice?.let { device ->
        DeviceDetailSheet(
            device = device,
            onDismiss = { selectedDevice = null },
            onSaveName = { name ->
                onSaveName(device, name)
                selectedDevice = null
            },
            onReset = {
                onReset(device)
                selectedDevice = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailSheet(
    device: BleDevice,
    onDismiss: () -> Unit,
    onSaveName: (String) -> Unit,
    onReset: () -> Unit
) {
    // remember keyed on address so the field resets when a different device is opened
    var nameInput by remember(device.address) { mutableStateOf(device.name ?: "") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("Device Settings", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = device.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                label = { Text("Device Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { onSaveName(nameInput) },
                enabled = nameInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save to Device")
            }

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Reset")
            }
        }
    }
}

@Composable
fun DeviceCard(device: BleDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = device.name ?: device.address,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "${device.batteryPercent}%",
                    style = MaterialTheme.typography.titleLarge,
                    color = batteryColor(device.batteryPercent)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { device.batteryPercent / 100f },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${"%.3f".format(device.voltageMillivolts / 1000f)}V",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "${device.rssi} dBm",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (device.name != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun batteryColor(percent: Int): Color = when {
    percent > 50 -> MaterialTheme.colorScheme.primary
    percent > 20 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}
