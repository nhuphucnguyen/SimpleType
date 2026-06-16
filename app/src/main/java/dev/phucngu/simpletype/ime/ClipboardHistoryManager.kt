package dev.phucngu.simpletype.ime

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class ClipboardItem(
    val id: String,
    val text: String,
    val timestamp: Long,
    val isPinned: Boolean = false
)

class ClipboardHistoryManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("clipboard_history", Context.MODE_PRIVATE)
    private val items = mutableListOf<ClipboardItem>()

    init {
        load()
        cleanUp()
    }

    private fun load() {
        val json = prefs.getString("items", "[]") ?: "[]"
        try {
            val array = JSONArray(json)
            items.clear()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                items.add(ClipboardItem(
                    obj.getString("id"),
                    obj.getString("text"),
                    obj.getLong("timestamp"),
                    obj.optBoolean("isPinned", false)
                ))
            }
        } catch (e: Exception) {
            items.clear()
        }
    }

    private fun save() {
        val array = JSONArray()
        for (item in items) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("text", item.text)
            obj.put("timestamp", item.timestamp)
            obj.put("isPinned", item.isPinned)
            array.put(obj)
        }
        prefs.edit().putString("items", array.toString()).apply()
    }

    fun addItem(text: String) {
        if (text.isBlank()) return
        // Remove duplicate if exists
        items.removeAll { it.text == text }
        val newItem = ClipboardItem(
            id = System.currentTimeMillis().toString(),
            text = text,
            timestamp = System.currentTimeMillis()
        )
        items.add(0, newItem)
        // Keep a maximum of say 50 items to avoid perf issues
        if (items.size > 50) {
            val toRemove = items.filter { !it.isPinned }.drop(50)
            items.removeAll(toRemove)
        }
        cleanUp()
        save()
    }

    fun deleteItem(id: String) {
        items.removeAll { it.id == id }
        save()
    }

    fun togglePin(id: String) {
        val index = items.indexOfFirst { it.id == id }
        if (index != -1) {
            val item = items[index]
            items[index] = item.copy(isPinned = !item.isPinned)
            save()
        }
    }

    fun getItems(): List<ClipboardItem> {
        cleanUp()
        // Pinned items always on top; within each group keep the existing
        // newest-first order (sortedByDescending is stable).
        return items.sortedByDescending { it.isPinned }
    }

    private fun cleanUp() {
        val now = System.currentTimeMillis()
        val fiveHoursAgo = now - 5 * 60 * 60 * 1000
        val removed = items.removeAll { !it.isPinned && it.timestamp < fiveHoursAgo }
        if (removed) save()
    }
}
