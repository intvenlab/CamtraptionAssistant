package com.ivlabs.batterymonitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists all known BLE devices to a single JSON file so the scan list
 * can show remembered kits even when devices are out of range.
 *
 * Thread-safety: all public methods acquire [lock] and must be called on
 * Dispatchers.IO (blocking file I/O).
 */
class KnownDeviceStore(context: Context) {

    private val file = File(context.filesDir, "known_devices.json")
    private val lock = Any()

    /** Returns all persisted devices with isConnected = false. */
    fun loadAll(): List<BleDevice> {
        synchronized(lock) {
            if (!file.exists()) return emptyList()
            return try {
                val arr = JSONArray(file.readText())
                (0 until arr.length()).map { i -> arr.getJSONObject(i).toDevice() }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /** Upserts the device by MAC address. */
    fun save(device: BleDevice) {
        synchronized(lock) {
            val existing = readArray()
            var found = false
            for (i in 0 until existing.length()) {
                if (existing.getJSONObject(i).optString("addr") == device.address) {
                    existing.put(i, device.toJson())
                    found = true
                    break
                }
            }
            if (!found) existing.put(device.toJson())
            file.writeText(existing.toString())
        }
    }

    /** Removes all devices belonging to [groupId]. */
    fun forgetKit(groupId: Int) {
        synchronized(lock) {
            if (!file.exists()) return
            val existing = readArray()
            val updated = JSONArray()
            for (i in 0 until existing.length()) {
                val o = existing.getJSONObject(i)
                if (o.getInt("groupId") != groupId) updated.put(o)
            }
            file.writeText(updated.toString())
        }
    }

    // -------------------------------------------------------------------------

    private fun readArray(): JSONArray =
        if (file.exists()) {
            try { JSONArray(file.readText()) } catch (e: Exception) { JSONArray() }
        } else JSONArray()

    private fun BleDevice.toJson(): JSONObject = JSONObject().apply {
        put("addr",      address)
        put("name",      if (name != null) name else JSONObject.NULL)
        put("pct",       batteryPercent)
        put("mv",        voltageMillivolts)
        put("rssi",      rssi)
        put("lastSeen",  lastSeen)
        put("configured", isConfigured)
        put("type",      deviceType.ordinal)
        put("chem",      batteryChemistry.ordinal)
        put("cells",     cellCount)
        put("groupId",   groupId)
        put("shutter",   shutterCount)
        put("extPct",    extBatteryPercent)
        put("extMv",     extVoltageMillivolts)
        put("build",     firmwareBuild)
    }

    private fun JSONObject.toDevice(): BleDevice = BleDevice(
        address              = getString("addr"),
        name                 = if (isNull("name")) null else getString("name"),
        batteryPercent       = getInt("pct"),
        voltageMillivolts    = getInt("mv"),
        rssi                 = getInt("rssi"),
        lastSeen             = getLong("lastSeen"),
        isConfigured         = getBoolean("configured"),
        deviceType           = DeviceType.values().getOrElse(getInt("type")) { DeviceType.BATTERY_MONITOR },
        batteryChemistry     = BatteryChemistry.values().getOrElse(getInt("chem")) { BatteryChemistry.LIPO },
        cellCount            = getInt("cells"),
        groupId              = getInt("groupId"),
        shutterCount         = getInt("shutter"),
        extBatteryPercent    = getInt("extPct"),
        extVoltageMillivolts = getInt("extMv"),
        firmwareBuild        = optString("build", ""),
        isConnected          = false,
        cameraState          = 0,
        cameraLiveFlags      = 0
    )
}
