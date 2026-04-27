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
import androidx.compose.foundation.Image
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ivlabs.batterymonitor.ui.theme.BatteryMonitorTheme

// ---------------------------------------------------------------------------
// Domain model
// ---------------------------------------------------------------------------

enum class DeviceType { BATTERY_MONITOR, CAMERA, STROBE }
enum class BatteryChemistry { LIPO, LIFEPO4, NIMH, ALKALINE }

data class BleDevice(
    val address: String,
    val name: String?,
    val batteryPercent: Int,
    val voltageMillivolts: Int,
    val rssi: Int,
    val lastSeen: Long,
    // Extended fields parsed from the 8-byte advertisement payload
    val isConfigured: Boolean = false,
    val deviceType: DeviceType = DeviceType.BATTERY_MONITOR,
    val batteryChemistry: BatteryChemistry = BatteryChemistry.LIPO,
    val cellCount: Int = 1,
    val groupId: Int = 0,        // 0 = no group
    val shutterCount: Int = 0    // camera only
)

// ---------------------------------------------------------------------------
// Scan list model
// ---------------------------------------------------------------------------

sealed class ScanListItem {
    data class UnconfiguredDevice(val device: BleDevice) : ScanListItem()
    data class Group(
        val groupId: Int,
        val groupName: String?,   // null in Phase 1 (name comes from GATT in Phase 2)
        val devices: List<BleDevice>
    ) : ScanListItem()
    data class IndividualDevice(val device: BleDevice) : ScanListItem()
}

fun buildScanList(devices: List<BleDevice>): List<ScanListItem> {
    val unconfigured = devices
        .filter { !it.isConfigured }
        .map { ScanListItem.UnconfiguredDevice(it) }

    val configured = devices.filter { it.isConfigured }

    val grouped = configured
        .filter { it.groupId != 0 }
        .groupBy { it.groupId }
        .map { (id, devs) ->
            ScanListItem.Group(
                groupId = id,
                groupName = null,   // Phase 2: read from GATT
                devices = devs.sortedBy { it.name ?: it.address }
            )
        }

    val individual = configured
        .filter { it.groupId == 0 }
        .sortedByDescending { it.rssi }
        .map { ScanListItem.IndividualDevice(it) }

    return unconfigured + grouped + individual
}

// ---------------------------------------------------------------------------
// Activity
// ---------------------------------------------------------------------------

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
                    scanItems = buildScanList(devices.values.toList()),
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

        val isConfigured: Boolean
        val deviceType: DeviceType
        val batteryChemistry: BatteryChemistry
        val cellCount: Int
        val groupId: Int
        val shutterCount: Int

        if (data.size >= 8) {
            val flags = data[3].toInt() and 0xFF
            isConfigured = (flags and 0x01) != 0
            deviceType = when ((flags shr 1) and 0x03) {
                1    -> DeviceType.CAMERA
                2    -> DeviceType.STROBE
                else -> DeviceType.BATTERY_MONITOR
            }
            batteryChemistry = when ((flags shr 3) and 0x03) {
                1    -> BatteryChemistry.LIFEPO4
                2    -> BatteryChemistry.NIMH
                3    -> BatteryChemistry.ALKALINE
                else -> BatteryChemistry.LIPO
            }
            groupId      = data[4].toInt() and 0xFF
            cellCount    = data[5].toInt() and 0xFF
            shutterCount = ((data[7].toInt() and 0xFF) shl 8) or (data[6].toInt() and 0xFF)
        } else {
            // Legacy 3-byte devices: treat as unconfigured battery monitors
            isConfigured    = false
            deviceType      = DeviceType.BATTERY_MONITOR
            batteryChemistry = BatteryChemistry.LIPO
            cellCount       = 1
            groupId         = 0
            shutterCount    = 0
        }

        val device = BleDevice(
            address          = result.device.address,
            name             = result.scanRecord?.deviceName,
            batteryPercent   = batteryPercent,
            voltageMillivolts = voltageMillivolts,
            rssi             = result.rssi,
            lastSeen         = System.currentTimeMillis(),
            isConfigured     = isConfigured,
            deviceType       = deviceType,
            batteryChemistry = batteryChemistry,
            cellCount        = cellCount,
            groupId          = groupId,
            shutterCount     = shutterCount
        )
        runOnUiThread { devices[device.address] = device }
    }

    private fun saveDeviceName(device: BleDevice, name: String) {
        // TODO Phase 2: connect via GATT and write device name characteristic
    }

    private fun resetDevice(device: BleDevice) {
        // TODO Phase 2: send factory reset command via GATT
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

// ---------------------------------------------------------------------------
// Screens
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleMonitorScreen(
    scanItems: List<ScanListItem>,
    isScanning: Boolean,
    onToggleScan: () -> Unit,
    onSaveName: (BleDevice, String) -> Unit,
    onReset: (BleDevice) -> Unit
) {
    // Phase 1: only individual configured devices open the detail sheet
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Image(
                painter = painterResource(id = R.drawable.loso_logo),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize(0.9f)
                    .align(Alignment.Center),
                contentScale = ContentScale.Fit,
                alpha = 0.08f
            )
            if (scanItems.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isScanning) "Scanning for devices…" else "Tap \u25B6 to start scanning",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = scanItems,
                        key = { item ->
                            when (item) {
                                is ScanListItem.UnconfiguredDevice -> "unc_${item.device.address}"
                                is ScanListItem.Group              -> "group_${item.groupId}"
                                is ScanListItem.IndividualDevice   -> "ind_${item.device.address}"
                            }
                        }
                    ) { item ->
                        when (item) {
                            is ScanListItem.UnconfiguredDevice ->
                                UnconfiguredDeviceCard(device = item.device)
                            is ScanListItem.Group ->
                                GroupCard(group = item)
                            is ScanListItem.IndividualDevice ->
                                IndividualDeviceCard(
                                    device = item.device,
                                    onClick = { selectedDevice = item.device }
                                )
                        }
                    }
                }
            }
        }
    }

    // Detail sheet for individually-configured devices (Phase 1 read-only view)
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

// ---------------------------------------------------------------------------
// Card composables
// ---------------------------------------------------------------------------

@Composable
fun UnconfiguredDeviceCard(device: BleDevice) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = "\u2699", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Unconfigured Device",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "MAC: ${device.address}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = "Signal: ${device.rssi} dBm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Text(
                text = "Setup \u2192",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@Composable
fun GroupCard(group: ScanListItem.Group) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = group.groupName ?: "Group ${group.groupId}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "\u2192",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            group.devices.forEachIndexed { index, device ->
                DeviceRow(device = device)
                if (index < group.devices.lastIndex) {
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
fun DeviceRow(device: BleDevice) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = when (device.deviceType) {
                        DeviceType.CAMERA -> "\uD83D\uDCF7"  // 📷
                        DeviceType.STROBE -> "\uD83D\uDCA1"  // 💡
                        else              -> "\uD83D\uDD0B"  // 🔋
                    },
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = device.name ?: device.address,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        LinearProgressIndicator(
            progress = { device.batteryPercent / 100f },
            modifier = Modifier.weight(2f),
            color = batteryColor(device.batteryPercent)
        )
        Text(
            text = "${device.batteryPercent}%",
            style = MaterialTheme.typography.bodySmall,
            color = batteryColor(device.batteryPercent)
        )
        if (device.batteryPercent < 20) {
            Text(text = "\u26A0", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun IndividualDeviceCard(device: BleDevice, onClick: () -> Unit) {
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = when (device.deviceType) {
                        DeviceType.CAMERA -> "\uD83D\uDCF7"  // 📷
                        DeviceType.STROBE -> "\uD83D\uDCA1"  // 💡
                        else              -> "\uD83D\uDD0B"  // 🔋
                    },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = device.name ?: device.address,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Text(
                    text = "${device.batteryPercent}%",
                    style = MaterialTheme.typography.titleLarge,
                    color = batteryColor(device.batteryPercent)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { device.batteryPercent / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = batteryColor(device.batteryPercent)
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

// ---------------------------------------------------------------------------
// Detail sheet (Phase 1 placeholder; replaced by DeviceScreen in Phase 2)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailSheet(
    device: BleDevice,
    onDismiss: () -> Unit,
    onSaveName: (String) -> Unit,
    onReset: () -> Unit
) {
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

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

@Composable
private fun batteryColor(percent: Int): Color = when {
    percent > 50 -> MaterialTheme.colorScheme.primary
    percent > 20 -> MaterialTheme.colorScheme.tertiary
    else         -> MaterialTheme.colorScheme.error
}
