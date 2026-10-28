package com.archie.alarm

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 還沒按「知道了」的提前提醒 */
data class PendingAdv(val id: Long, val eventMillis: Long, val count: Int)

object Pending {
    private var file: File? = null
    private val _list = MutableStateFlow<List<PendingAdv>>(emptyList())
    val list: StateFlow<List<PendingAdv>> = _list

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.applicationContext.filesDir, "pending.json")
        try {
            val f = file!!
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                _list.value = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    PendingAdv(o.getLong("id"), o.getLong("at"), o.optInt("n", 1))
                }
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun persist() {
        try {
            val arr = JSONArray()
            _list.value.forEach {
                arr.put(JSONObject().put("id", it.id).put("at", it.eventMillis).put("n", it.count))
            }
            file?.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun get(id: Long): PendingAdv? = _list.value.firstOrNull { it.id == id }

    @Synchronized
    fun put(p: PendingAdv) {
        _list.value = _list.value.filter { it.id != p.id } + p
        persist()
    }

    @Synchronized
    fun remove(id: Long) {
        if (_list.value.none { it.id == id }) return
        _list.value = _list.value.filter { it.id != id }
        persist()
    }
}
