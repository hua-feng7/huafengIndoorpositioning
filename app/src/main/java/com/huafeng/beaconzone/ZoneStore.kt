package com.huafeng.beaconzone

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

class ZoneStore(ctx: Context) {

    private val sp = ctx.getSharedPreferences("zones_store", Context.MODE_PRIVATE)

    private val _zones = MutableStateFlow(load())
    val zones: StateFlow<List<Zone>> = _zones

    fun upsert(z: Zone) {
        val list = _zones.value.toMutableList()
        val idx = list.indexOfFirst { it.id == z.id }
        if (idx >= 0) list[idx] = z else list.add(z)
        save(list)
        _zones.value = list
    }

    fun delete(id: String) {
        val list = _zones.value.filterNot { it.id == id }
        save(list)
        _zones.value = list
    }

    private fun save(list: List<Zone>) {
        val arr = JSONArray()
        list.forEach { z ->
            val o = JSONObject()
            o.put("id", z.id)
            o.put("name", z.name)
            o.put("uuid", z.uuid)
            o.put("major", z.major)
            o.put("minor", z.minor)
            arr.put(o)
        }
        sp.edit().putString("zones_json", arr.toString()).apply()
    }

    private fun load(): List<Zone> {
        val s = sp.getString("zones_json", null) ?: return emptyList()
        return try {
            val arr = JSONArray(s)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        Zone(
                            id = o.getString("id"),
                            name = o.getString("name"),
                            uuid = o.getString("uuid"),
                            major = o.getInt("major"),
                            minor = o.getInt("minor")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
