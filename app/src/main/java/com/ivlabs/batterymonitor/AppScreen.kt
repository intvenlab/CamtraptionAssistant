package com.ivlabs.batterymonitor

import android.bluetooth.BluetoothDevice
import java.util.UUID

// ---------------------------------------------------------------------------
// Navigation state machine
// ---------------------------------------------------------------------------

sealed class AppScreen {
    object Scan : AppScreen()

    data class Group(
        val groupId: Int,
        val groupName: String?
    ) : AppScreen()

    data class Device(
        val device: BleDevice,
        val bluetoothDevice: BluetoothDevice
    ) : AppScreen()

    data class Setup(
        val device: BleDevice,
        val bluetoothDevice: BluetoothDevice
    ) : AppScreen()

    data class DeviceSettings(
        val device: BleDevice,
        val bluetoothDevice: BluetoothDevice
    ) : AppScreen()

}

// ---------------------------------------------------------------------------
// GATT UUIDs
// TODO: Confirm all UUIDs with firmware before testing against real hardware.
// ---------------------------------------------------------------------------

object GattUuids {
    val SERVICE       = UUID.fromString("ca500000-0000-0000-0000-000000000000")!!
    val DEVICE_NAME   = UUID.fromString("ca500001-0000-0000-0000-000000000000")!!
    val GROUP_ID      = UUID.fromString("ca500002-0000-0000-0000-000000000000")!!
    val GROUP_NAME    = UUID.fromString("ca500003-0000-0000-0000-000000000000")!!
    val DEVICE_TYPE   = UUID.fromString("ca500004-0000-0000-0000-000000000000")!!
    val CHEMISTRY     = UUID.fromString("ca500005-0000-0000-0000-000000000000")!!
    val CELL_COUNT    = UUID.fromString("ca500006-0000-0000-0000-000000000000")!!
    val SHUTTER_COUNT = UUID.fromString("ca500007-0000-0000-0000-000000000000")!!
    val RESET_SHUTTER = UUID.fromString("ca500008-0000-0000-0000-000000000000")!!
    val FACTORY_RESET = UUID.fromString("ca500009-0000-0000-0000-000000000000")!!
    val CAMERA_CONFIG = UUID.fromString("ca50000a-0000-0000-0000-000000000000")!!
    val TELEMETRY     = UUID.fromString("ca50000b-0000-0000-0000-000000000000")!!
    val CAL_SET       = UUID.fromString("ca50000c-0000-0000-0000-000000000000")!!
    val INT_CAL_SET          = UUID.fromString("ca50000d-0000-0000-0000-000000000000")!!
    val CAMERA_CONFIG_STATUS = UUID.fromString("ca50000e-0000-0000-0000-000000000000")!!
    val FEEDER_CONFIG        = UUID.fromString("ca50000f-0000-0000-0000-000000000000")!!
    val FEEDER_CONFIG_STATUS = UUID.fromString("ca500010-0000-0000-0000-000000000000")!!
}

// ---------------------------------------------------------------------------
// Enum display names
// ---------------------------------------------------------------------------

// Sort order within a group: cameras first, then strobes, focus lights, battery monitors last
fun DeviceType.sortOrder() = when (this) {
    DeviceType.CAMERA          -> 0
    DeviceType.FEEDER          -> 1
    DeviceType.STROBE          -> 2
    DeviceType.FOCUS_LIGHT     -> 3
    DeviceType.BATTERY_MONITOR -> 4
}

fun DeviceType.displayName() = when (this) {
    DeviceType.BATTERY_MONITOR -> "Battery Monitor"
    DeviceType.CAMERA          -> "Camera"
    DeviceType.STROBE          -> "Strobe"
    DeviceType.FOCUS_LIGHT     -> "Focus Light"
    DeviceType.FEEDER          -> "Feeder"
}

fun BatteryChemistry.displayName() = when (this) {
    BatteryChemistry.LIPO     -> "LiPo"
    BatteryChemistry.LIFEPO4  -> "LiFePO4"
    BatteryChemistry.NIMH     -> "NiMH"
    BatteryChemistry.ALKALINE -> "Alkaline"
}

fun Int.cameraStateLabel() = when (this) {
    0    -> "Idle"
    1    -> "Wake AF"
    2    -> "Cold FP Wait"
    3    -> "Burst Active"
    4    -> "Post-Shutter"
    else -> "Unknown"
}
