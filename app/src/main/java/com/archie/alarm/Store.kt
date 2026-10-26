package com.archie.alarm

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.io.File

object Store {
    private var file: File? = null
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.applicationContext.filesDir, "items.json")
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
    fun get(id: Long): Item? = _items.value.firstOrNull { it.id == id }

    @Synchronized
    fun nextId(): Long = (_items.value.maxOfOrNull { it.id } ?: 0L) + 1L

    @Synchronized
    fun upsert(item: Item) {
        val cur = _items.value
        _items.value = if (cur.any { it.id == item.id }) cur.map { if (it.id == item.id) item else it } else cur + item
        persist()
    }

    @Synchronized
    fun delete(ids: Set<Long>) {
        _items.value = _items.value.filter { it.id !in ids }
        persist()
    }
}
