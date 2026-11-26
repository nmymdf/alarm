package com.archie.alarm

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.io.File

object Store {
    private var file: File? = null
    private var prefs: android.content.SharedPreferences? = null

    /** 刪除過的編號：永遠不再使用，也不會再響 */
    private val deleted = mutableSetOf<Long>()
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.applicationContext.filesDir, "items.json")
        prefs = ctx.applicationContext.getSharedPreferences("store", Context.MODE_PRIVATE)
        prefs?.getStringSet("deleted", emptySet())?.forEach { it.toLongOrNull()?.let(deleted::add) }
        try {
            val f = file!!
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                _items.value = (0 until arr.length()).map { Item.fromJson(arr.getJSONObject(it)) }
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun persist() {
        try {
            file?.writeText(JSONArray(_items.value.map { it.toJson() }).toString())
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun get(id: Long): Item? = if (id in deleted) null else _items.value.firstOrNull { it.id == id }

    @Synchronized
    fun isDeleted(id: Long): Boolean = id in deleted

    @Synchronized
    fun nextId(): Long {
        val last = prefs?.getLong("lastId", 0L) ?: 0L
        val maxDel = deleted.maxOrNull() ?: 0L
        val id = maxOf(last, maxDel, _items.value.maxOfOrNull { it.id } ?: 0L) + 1L
        prefs?.edit()?.putLong("lastId", id)?.apply()
        return id
    }

    @Synchronized
    fun upsert(item: Item) {
        if (item.id in deleted) return
        val cur = _items.value
        _items.value = if (cur.any { it.id == item.id }) cur.map { if (it.id == item.id) item else it } else cur + item
        persist()
    }

    @Synchronized
    fun delete(ids: Set<Long>) {
        _items.value = _items.value.filter { it.id !in ids }
        deleted.addAll(ids)
        prefs?.edit()?.putStringSet("deleted", deleted.map { it.toString() }.toSet())?.commit()
        persist()
    }
}
