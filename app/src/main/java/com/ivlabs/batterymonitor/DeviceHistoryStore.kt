package com.ivlabs.batterymonitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class DeviceHistoryEntry(
    val timestamp: Long,
    val batteryPercent: Int,
    val voltageMillivolts: Int,
    val rssi: Int
)

class DeviceHistoryStore(private val context: Context) {

    companion object {
        private const val MAX_ENTRIES = 100
    }

    private fun fileFor(address: String): File {
        val safe = address.replace(":", "_")
        return File(context.filesDir, "device_history_$safe.json")
    }

    fun load(address: String): List<DeviceHistoryEntry> {
        val file = fileFor(address)
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                DeviceHistoryEntry(
                    timestamp         = obj.getLong("ts"),
                    batteryPercent    = obj.getInt("pct"),
                    voltageMillivolts = obj.getInt("mv"),
                    rssi              = obj.getInt("rssi")
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
                put("ts",   e.timestamp)
                put("pct",  e.batteryPercent)
                put("mv",   e.voltageMillivolts)
                put("rssi", e.rssi)
            })
        }
        try { fileFor(address).writeText(array.toString()) } catch (e: Exception) {}
    }
}
