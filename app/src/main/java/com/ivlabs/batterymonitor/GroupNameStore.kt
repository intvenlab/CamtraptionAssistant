package com.ivlabs.batterymonitor

import android.content.Context

/**
 * Stores group ID → name mappings in SharedPreferences on the phone.
 * Group IDs come from device advertisements; names are assigned by the user
 * and never written to the device hardware.
 */
class GroupNameStore(context: Context) {

    private val prefs = context.getSharedPreferences("group_names", Context.MODE_PRIVATE)

    fun loadAll(): Map<Int, String> =
        prefs.all.entries.mapNotNull { (key, value) ->
            if (key.startsWith("g") && value is String)
                key.drop(1).toIntOrNull()?.to(value)
            else null
        }.toMap()

    fun save(groupId: Int, name: String) {
        prefs.edit().putString("g$groupId", name).apply()
    }
}
