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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ivlabs.batterymonitor.ui.theme.BatteryMonitorTheme

// ---------------------------------------------------------------------------
// Domain model
// ---------------------------------------------------------------------------

enum class DeviceType { BATTERY_MONITOR, CAMERA, STROBE, FOCUS_LIGHT, FEEDER }
enum class BatteryChemistry { LIPO, LIFEPO4, NIMH, ALKALINE }

// Advertisement flags byte: bits1-2 hold the low 2 bits of device type, bit5 holds the MSB
// (widened from a 2-bit to a 3-bit field to make room for FEEDER; bits3-4 remain chemistry).
private fun deviceTypeFromFlags(flags: Int): DeviceType {
    val raw = ((flags shr 1) and 0x03) or (((flags shr 5) and 0x01) shl 2)
    return when (raw) {
        1    -> DeviceType.CAMERA
        2    -> DeviceType.STROBE
        3    -> DeviceType.FOCUS_LIGHT
        4    -> DeviceType.FEEDER
        else -> DeviceType.BATTERY_MONITOR
    }
}

data class BleDevice(
    val address: String,
    val name: String?,
    val batteryPercent: Int,
    val voltageMillivolts: Int,
    val rssi: Int,
    val lastSeen: Long,
    val isConfigured: Boolean = false,
    val deviceType: DeviceType = DeviceType.BATTERY_MONITOR,
    val batteryChemistry: BatteryChemistry = BatteryChemistry.LIPO,
    val cellCount: Int = 1,
    val groupId: Int = 0,
    val shutterCount: Int = 0,
    val extBatteryPercent: Int = -1,       // -1 = not present
    val extVoltageMillivolts: Int = 0,
    val isConnected: Boolean = true,
    val cameraState: Int = 0,             // adv byte [12]; only meaningful for CAMERA type
    val cameraLiveFlags: Int = 0,         // adv byte [13]; bit0=activityActive bit1=hpOutAsserted
    val firmwareBuild: String = ""        // formatted from bytes [14-20]; "" = not present
)

// ---------------------------------------------------------------------------
// Scan list model
// ---------------------------------------------------------------------------

sealed class ScanListItem {
    data class UnconfiguredDevice(val device: BleDevice) : ScanListItem()
    data class Group(
        val groupId: Int,
        val groupName: String?,
        val devices: List<BleDevice>
    ) : ScanListItem()
    data class IndividualDevice(val device: BleDevice) : ScanListItem()
}

fun buildScanList(
    devices: List<BleDevice>,
    groupNames: Map<Int, String> = emptyMap()
): List<ScanListItem> {
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
                groupName = groupNames[id],
                devices = devs.sortedWith(
                    compareBy({ it.deviceType.sortOrder() }, { it.name ?: it.address })
                )
            )
        }
        .sortedBy { it.groupId }

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
    private var isBluetoothEnabled by mutableStateOf(false)

    // Navigation back stack – starts on the Scan screen
    private val screenStack = mutableStateListOf<AppScreen>(AppScreen.Scan)

    private val gattManager by lazy { BleGattManager(applicationContext) }

    // Group names stored on the phone; key = groupId, value = user-assigned name
    private val groupNameStore by lazy { GroupNameStore(this) }
    private val groupNames = mutableStateMapOf<Int, String>()

    private val deviceHistoryStore by lazy { DeviceHistoryStore(this) }
    private val knownDeviceStore by lazy { KnownDeviceStore(this) }

    private val handler = Handler(Looper.getMainLooper())
    private val staleDeviceRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            devices.keys.toList()
                .filter { now - (devices[it]?.lastSeen ?: 0L) > STALE_TIMEOUT_MS }
                .forEach { addr ->
                    devices[addr]?.let { devices[addr] = it.copy(isConnected = false) }
                }
            handler.postDelayed(this, 1_000L)
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
        groupNames.putAll(groupNameStore.loadAll())
        lifecycleScope.launch(Dispatchers.IO) {
            val saved = knownDeviceStore.loadAll()
            withContext(Dispatchers.Main) {
                saved.forEach { d -> devices.getOrPut(d.address) { d } }
            }
        }
        setContent {
            BatteryMonitorTheme {
                val screen = screenStack.last()
                when (screen) {
                    is AppScreen.Scan -> BleMonitorScreen(
                        scanItems          = buildScanList(devices.values.toList(), groupNames),
                        isBluetoothEnabled = isBluetoothEnabled,
                        onNavigateToGroup  = { groupId, groupName ->
                            screenStack.add(AppScreen.Group(groupId, groupName))
                        },
                        onNavigateToDevice = { device ->
                            val btDevice = bluetoothAdapter.getRemoteDevice(device.address)
                            screenStack.add(AppScreen.Device(device, btDevice))
                        },
                        onNavigateToSetup = { device ->
                            val btDevice = bluetoothAdapter.getRemoteDevice(device.address)
                            screenStack.add(AppScreen.Setup(device, btDevice))
                            gattManager.connect(btDevice)
                        },
                        onForgetKit = { groupId ->
                            devices.keys
                                .filter { devices[it]?.groupId == groupId }
                                .forEach { devices.remove(it) }
                            lifecycleScope.launch(Dispatchers.IO) {
                                knownDeviceStore.forgetKit(groupId)
                            }
                        },
                        onInjectTestDevices = if (BuildConfig.DEBUG) ::injectTestDevices else null
                    )
                    is AppScreen.Group -> GroupScreen(
                        groupId = screen.groupId,
                        groupName = groupNames[screen.groupId],
                        allDevices = devices.values.toList(),
                        gattManager = gattManager,
                        cameraDevice = devices.values
                            .firstOrNull { it.groupId == screen.groupId && it.deviceType == DeviceType.CAMERA }
                            ?.let { bluetoothAdapter.getRemoteDevice(it.address) },
                        onGroupNameChange = { id, name ->
                            groupNames[id] = name
                            groupNameStore.save(id, name)
                        },
                        onNavigateToDevice = { device ->
                            val btDevice = bluetoothAdapter.getRemoteDevice(device.address)
                            screenStack.add(AppScreen.Device(device, btDevice))
                        },
                        onBack = { screenStack.removeLast() }
                    )
                    is AppScreen.Device -> {
                        // Always use the live advertised data — devices is mutableStateMapOf
                        // so reading it here re-triggers composition on every scan update.
                        val liveDevice = devices[screen.device.address] ?: screen.device
                        DeviceScreen(
                            device = liveDevice,
                            bluetoothDevice = screen.bluetoothDevice,
                            gattManager = gattManager,
                            historyStore = deviceHistoryStore,
                            onOpenSettings = {
                                gattManager.connect(screen.bluetoothDevice)
                                screenStack.add(
                                    AppScreen.DeviceSettings(liveDevice, screen.bluetoothDevice)
                                )
                            },
                            onBack = { screenStack.removeLast() }
                        )
                    }
                    is AppScreen.DeviceSettings -> DeviceSettingsScreen(
                        device       = screen.device,
                        gattManager  = gattManager,
                        historyStore = deviceHistoryStore,
                        onBack = { gattManager.disconnect(); screenStack.removeLast() },
                        onFactoryReset = {
                            repeat(2) { if (screenStack.size > 1) screenStack.removeLast() }
                        }
                    )
                    is AppScreen.Setup -> SetupScreen(
                        device = screen.device,
                        gattManager = gattManager,
                        onFinish = { screenStack.removeLast() }
                    )
                }
            }
        }
        if (hasPermissions()) startScanning()
        else permissionLauncher.launch(buildPermissionList().toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        isBluetoothEnabled = bluetoothAdapter?.isEnabled == true
        if (!isScanning && hasPermissions() && isBluetoothEnabled) startScanning()
    }

    override fun onPause() {
        super.onPause()
        if (isScanning) stopScanning()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(staleDeviceRunnable)
        gattManager.close()
    }

    private fun parseScanResult(result: ScanResult) {
        val data = result.scanRecord?.getManufacturerSpecificData(COMPANY_ID) ?: return
        if (data.size < 3) return

        val batteryPercent    = data[0].toInt() and 0xFF
        val voltageMillivolts = ((data[2].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)

        val isConfigured: Boolean
        val deviceType: DeviceType
        val batteryChemistry: BatteryChemistry
        val cellCount: Int
        val groupId: Int
        val shutterCount: Int
        var extBatteryPercent = -1
        var extVoltageMillivolts = 0
        var cameraState = 0
        var cameraLiveFlags = 0
        var firmwareBuild = ""

        if (data.size >= 11) {
            // New 13-byte packet (11 bytes after company ID strip):
            // [0]=intPct [1-2]=intMv [3]=extPct [4-5]=extMv [6]=flags [7]=group [8]=cells [9-10]=shutter
            val rawExtPct = data[3].toInt() and 0xFF
            extBatteryPercent = if (rawExtPct == 0xFF) -1 else rawExtPct
            val extMvLo = data[4].toInt() and 0xFF
            val extMvHi = data[5].toInt() and 0xFF
            val rawExtMv = (extMvHi shl 8) or extMvLo
            extVoltageMillivolts = if (extBatteryPercent < 0) 0 else rawExtMv
            val flags = data[6].toInt() and 0xFF
            isConfigured = (flags and 0x01) != 0
            deviceType = deviceTypeFromFlags(flags)
            batteryChemistry = when ((flags shr 3) and 0x03) {
                1    -> BatteryChemistry.LIFEPO4
                2    -> BatteryChemistry.NIMH
                3    -> BatteryChemistry.ALKALINE
                else -> BatteryChemistry.LIPO
            }
            groupId      = data[7].toInt() and 0xFF
            cellCount    = data[8].toInt() and 0xFF
            shutterCount = ((data[10].toInt() and 0xFF) shl 8) or (data[9].toInt() and 0xFF)
            if (data.size >= 21 && (data[11].toInt() and 0xFF) == 2) {
                cameraState     = data[12].toInt() and 0xFF
                cameraLiveFlags = data[13].toInt() and 0xFF
                val buildYear   = ((data[15].toInt() and 0xFF) shl 8) or (data[14].toInt() and 0xFF)
                val buildMonth  = data[16].toInt() and 0xFF
                val buildDay    = data[17].toInt() and 0xFF
                val buildHour   = data[18].toInt() and 0xFF
                val buildMin    = data[19].toInt() and 0xFF
                val buildSec    = data[20].toInt() and 0xFF
                firmwareBuild   = if (buildYear >= 2024)
                    "%04d-%02d-%02d %02d:%02d:%02d".format(
                        buildYear, buildMonth, buildDay, buildHour, buildMin, buildSec)
                else ""
            }
        } else if (data.size >= 8) {
            // Old 10-byte packet (8 bytes after company ID strip) – backward compat:
            // [0]=pct [1-2]=mv [3]=flags [4]=group [5]=cells [6-7]=shutter
            val flags = data[3].toInt() and 0xFF
            isConfigured = (flags and 0x01) != 0
            deviceType = deviceTypeFromFlags(flags)
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
            isConfigured     = false
            deviceType       = DeviceType.BATTERY_MONITOR
            batteryChemistry = BatteryChemistry.LIPO
            cellCount        = 1
            groupId          = 0
            shutterCount     = 0
        }

        val device = BleDevice(
            address              = result.device.address,
            name                 = result.scanRecord?.deviceName,
            batteryPercent       = batteryPercent,
            voltageMillivolts    = voltageMillivolts,
            rssi                 = result.rssi,
            lastSeen             = System.currentTimeMillis(),
            isConfigured         = isConfigured,
            deviceType           = deviceType,
            batteryChemistry     = batteryChemistry,
            cellCount            = cellCount,
            groupId              = groupId,
            shutterCount         = shutterCount,
            extBatteryPercent    = extBatteryPercent,
            extVoltageMillivolts = extVoltageMillivolts,
            cameraState          = cameraState,
            cameraLiveFlags      = cameraLiveFlags,
            firmwareBuild        = firmwareBuild
        )
        runOnUiThread { devices[device.address] = device }
        lifecycleScope.launch(Dispatchers.IO) { knownDeviceStore.save(device) }
    }

    private fun hasPermissions() = buildPermissionList().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startScanning() {
        isBluetoothEnabled = true
        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            isBluetoothEnabled = false
            return
        }
        scanner.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback
        )
        isScanning = true
        handler.removeCallbacks(staleDeviceRunnable)
        handler.post(staleDeviceRunnable)
    }

    private fun stopScanning() {
        bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
        isScanning = false
    }

    fun injectTestDevices() {
        val now = System.currentTimeMillis()
        listOf(
            // Unconfigured device (pinned at top)
            BleDevice("AA:BB:CC:DD:EE:01", null,            72, 3840, -61, now),
            // Group 1: camera → strobe → strobe → battery (sort order applied automatically)
            BleDevice("AA:BB:CC:DD:EE:02", "Hummingbird1",  85, 4100, -55, now,
                isConfigured = true, deviceType = DeviceType.CAMERA,          groupId = 1, shutterCount = 1_247),
            BleDevice("AA:BB:CC:DD:EE:03", "Strobe A",      54, 3900, -68, now,
                isConfigured = true, deviceType = DeviceType.STROBE,          groupId = 1),
            BleDevice("AA:BB:CC:DD:EE:04", "Strobe B",      18, 3620, -72, now,
                isConfigured = true, deviceType = DeviceType.STROBE,          groupId = 1),
            BleDevice("AA:BB:CC:DD:EE:05", "Base Station",  91, 4180, -48, now,
                isConfigured = true, deviceType = DeviceType.BATTERY_MONITOR, groupId = 1),
            BleDevice("AA:BB:CC:DD:EE:06", "Feeder A",      63, 3980, -59, now,
                isConfigured = true, deviceType = DeviceType.FEEDER,          groupId = 1),
        ).forEach { devices[it.address] = it }
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
// Scan screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleMonitorScreen(
    scanItems: List<ScanListItem>,
    isBluetoothEnabled: Boolean,
    onNavigateToGroup: (groupId: Int, groupName: String?) -> Unit,
    onNavigateToDevice: (BleDevice) -> Unit,
    onNavigateToSetup: (BleDevice) -> Unit,
    onForgetKit: (groupId: Int) -> Unit,
    onInjectTestDevices: (() -> Unit)? = null
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Camtraption Assistant") })
        },
        floatingActionButton = {
            onInjectTestDevices?.let {
                SmallFloatingActionButton(onClick = it) {
                    Text("T", style = MaterialTheme.typography.labelLarge)
                }
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
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isBluetoothEnabled) {
                        Text(
                            "\u26A0 Bluetooth is off",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(
                            "\u25CF",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Scanning",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (scanItems.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No devices found yet\u2026",
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
                                    UnconfiguredDeviceCard(
                                        device = item.device,
                                        onClick = { onNavigateToSetup(item.device) }
                                    )
                                is ScanListItem.Group ->
                                    GroupCard(
                                        group = item,
                                        onClick = { onNavigateToGroup(item.groupId, item.groupName) },
                                        onForgetKit = { onForgetKit(item.groupId) }
                                    )
                                is ScanListItem.IndividualDevice ->
                                    IndividualDeviceCard(
                                        device = item.device,
                                        onClick = { onNavigateToDevice(item.device) }
                                    )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Card composables
// ---------------------------------------------------------------------------

@Composable
fun UnconfiguredDeviceCard(device: BleDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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
fun GroupCard(
    group: ScanListItem.Group,
    onClick: () -> Unit,
    onForgetKit: () -> Unit
) {
    var showConfirm by remember { mutableStateOf(false) }

    val camera = group.devices.firstOrNull { it.deviceType == DeviceType.CAMERA }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                val ps = this
                coroutineScope {
                    while (true) {
                        // wait for any pointer to press down
                        ps.awaitPointerEventScope {
                            while (awaitPointerEvent().changes.none { it.pressed }) { }
                        }
                        var longPressed = false
                        val job = launch {
                            delay(2_000L)
                            longPressed = true
                            showConfirm = true
                        }
                        // wait for all pointers to lift
                        ps.awaitPointerEventScope {
                            while (awaitPointerEvent().changes.any { it.pressed }) { }
                        }
                        job.cancel()
                        if (!longPressed) onClick()
                    }
                }
            }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Kit ${group.groupId}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (!group.groupName.isNullOrBlank()) {
                    Text(
                        text = group.groupName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                camera?.let {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("\uD83D\uDCF7", style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = "Shutters: %,d".format(it.shutterCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Forget Kit ${group.groupId}?") },
            text = {
                Text(
                    "Removes ${group.devices.size} device(s) from memory. " +
                    "If they are still nearby they will reappear automatically."
                )
            },
            confirmButton = {
                TextButton(onClick = { onForgetKit(); showConfirm = false }) {
                    Text("Forget", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun DeviceRow(device: BleDevice) {
    val pct = device.extBatteryPercent
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = when (device.deviceType) {
                DeviceType.CAMERA      -> "\uD83D\uDCF7"  // 📷
                DeviceType.STROBE      -> "\uD83D\uDCA1"  // 💡
                DeviceType.FOCUS_LIGHT -> "\uD83D\uDD26"  // 🔦
                DeviceType.FEEDER      -> "💧"  // (droplet)
                else                   -> "\uD83D\uDD0B"  // 🔋
            },
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = device.name ?: device.address,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        if (device.isConnected) {
            LinearProgressIndicator(
                progress = { pct.coerceAtLeast(0) / 100f },
                modifier = Modifier.weight(2f),
                color = batteryDisplayColor(pct.coerceAtLeast(0))
            )
            Text(
                text = if (pct >= 0) "$pct%" else "--",
                style = MaterialTheme.typography.bodySmall,
                color = batteryDisplayColor(pct.coerceAtLeast(0))
            )
            if (pct in 0..19) {
                Text(text = "\u26A0", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Spacer(modifier = Modifier.weight(2f))
        }
        Text(
            text = if (device.isConnected) "Connected"
                   else if (device.lastSeen > 0L) formatLastSeen(device.lastSeen) else "",
            style = MaterialTheme.typography.labelSmall,
            color = if (device.isConnected) Color(0xFF2E7D32)
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun IndividualDeviceCard(device: BleDevice, onClick: () -> Unit) {
    val pct    = device.extBatteryPercent
    val voltMv = device.extVoltageMillivolts
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
                            DeviceType.CAMERA      -> "\uD83D\uDCF7"  // 📷
                            DeviceType.STROBE      -> "\uD83D\uDCA1"  // 💡
                            DeviceType.FOCUS_LIGHT -> "\uD83D\uDD26"  // 🔦
                            DeviceType.FEEDER      -> "💧"  // (droplet)
                            else                   -> "\uD83D\uDD0B"  // 🔋
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Column {
                        Text(
                            text = device.name ?: device.address,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (device.isConnected) "Connected"
                                   else if (device.lastSeen > 0L) formatLastSeen(device.lastSeen) else "Disconnected",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (device.isConnected) Color(0xFF2E7D32)
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = if (pct >= 0) "$pct%" else "--",
                    style = MaterialTheme.typography.titleLarge,
                    color = batteryDisplayColor(pct.coerceAtLeast(0))
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { pct.coerceAtLeast(0) / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = batteryDisplayColor(pct.coerceAtLeast(0))
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (voltMv > 0) "${"%.3f".format(voltMv / 1000f)}V" else "--",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "${device.rssi} dBm",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

internal fun formatLastSeen(ts: Long): String {
    val elapsed = System.currentTimeMillis() - ts
    return when {
        elapsed < 60_000L        -> "Just now"
        elapsed < 3_600_000L     -> "${elapsed / 60_000} min ago"
        elapsed < 86_400_000L    -> "${elapsed / 3_600_000} hr ago"
        elapsed < 172_800_000L   -> "Yesterday"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ts))
    }
}
