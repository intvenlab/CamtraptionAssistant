package com.ivlabs.batterymonitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class DeviceHistoryEntry(
    val timestamp: Long,
    val eventType: String,            // "Connect" | "Disconnect" | "Shutter" | "Battery" | "ExtBattRemoved" | "ExtBattAttached"
    val shutterCount: Int = 0,
    val batteryPercent: Int,          // ext battery % (–1 = absent)
    val voltageMillivolts: Int,       // ext voltage mV
    // Full telemetry (stored, shown on expand)
    val intBatteryPercent: Int = -1,
    val intVoltageMillivolts: Int = 0,
    val rssi: Int = 0,
    val cameraState: Int = 0,
    val cameraLiveFlags: Int = 0,
    val firmwareBuild: String = ""
)

class DeviceHistoryStore(private val context: Context) {

    companion object {
        private const val MAX_ENTRIES = 500
    }

    internal fun getFile(address: String): File {
        val safe = address.replace(":", "_")
        return File(context.filesDir, "device_history_$safe.json")
    }

    fun load(address: String): List<DeviceHistoryEntry> {
        val file = getFile(address)
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                DeviceHistoryEntry(
                    timestamp            = obj.getLong("ts"),
                    eventType            = obj.optString("evt", "Connect"),
                    shutterCount         = obj.optInt("shutter", 0),
                    batteryPercent       = obj.getInt("pct"),
                    voltageMillivolts    = obj.getInt("mv"),
                    intBatteryPercent    = obj.optInt("ipct", -1),
                    intVoltageMillivolts = obj.optInt("imv", 0),
                    rssi                 = obj.optInt("rssi", 0),
                    cameraState          = obj.optInt("camst", 0),
                    cameraLiveFlags      = obj.optInt("camlf", 0),
                    firmwareBuild        = obj.optString("fw", "")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun append(address: String, entry: DeviceHistoryEntry) {
        val existing = load(address).toMutableList()
        existing.add(0, entry)
        val trimmed = existing.take(MAX_ENTRIES)
        val array = JSONArray()
        trimmed.forEach { e ->
            array.put(JSONObject().apply {
                put("ts",      e.timestamp)
                put("evt",     e.eventType)
                put("shutter", e.shutterCount)
                put("pct",     e.batteryPercent)
                put("mv",      e.voltageMillivolts)
                put("ipct",    e.intBatteryPercent)
                put("imv",     e.intVoltageMillivolts)
                put("rssi",    e.rssi)
                put("camst",   e.cameraState)
                put("camlf",   e.cameraLiveFlags)
                put("fw",      e.firmwareBuild)
            })
        }
        try { getFile(address).writeText(array.toString()) } catch (e: Exception) {}
    }
}
