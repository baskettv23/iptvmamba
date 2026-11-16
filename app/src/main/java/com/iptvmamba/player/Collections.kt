package com.iptvmamba.player

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

data class UserCollection(val id: String, val name: String, val ids: List<String>)

object CollectionsStore {
    val list = mutableStateListOf<UserCollection>()
    private fun prefs(c: Context) = Profiles.sp(c, "ipvio_collections")

    fun init(c: Context) {
        if (list.isNotEmpty()) return
        val s = prefs(c).getString("c", null) ?: return
        try {
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val ids = ArrayList<String>()
                val arr = o.optJSONArray("ids") ?: JSONArray()
                for (j in 0 until arr.length()) ids.add(arr.getString(j))
                list.add(UserCollection(o.getString("id"), o.getString("name"), ids))
            }
        } catch (_: Exception) {
        }
    }

    private fun persist(c: Context) {
        val a = JSONArray()
        for (col in list) {
            val ids = JSONArray()
            for (id in col.ids) ids.put(id)
            a.put(JSONObject().put("id", col.id).put("name", col.name).put("ids", ids))
        }
        prefs(c).edit().putString("c", a.toString()).apply()
    }

    fun create(c: Context, name: String): UserCollection {
        init(c)
        val col = UserCollection(System.currentTimeMillis().toString(36), name.trim().ifBlank { "Colección" }, emptyList())
        list.add(0, col)
        persist(c)
        return col
    }

    fun rename(c: Context, id: String, name: String) {
        init(c)
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        list[i] = list[i].copy(name = name.trim().ifBlank { list[i].name })
        persist(c)
    }

    fun remove(c: Context, id: String) {
        init(c)
        if (list.removeAll { it.id == id }) persist(c)
    }

    fun has(colId: String, itemId: String): Boolean = list.firstOrNull { it.id == colId }?.ids?.contains(itemId) == true

    fun toggle(c: Context, colId: String, itemId: String) {
        init(c)
        val i = list.indexOfFirst { it.id == colId }
        if (i < 0) return
        val col = list[i]
        val ids = col.ids.toMutableList()
        if (!ids.remove(itemId)) ids.add(0, itemId)
        list[i] = col.copy(ids = ids)
        persist(c)
    }

    fun removeItem(c: Context, colId: String, itemId: String) {
        init(c)
        val i = list.indexOfFirst { it.id == colId }
        if (i < 0) return
        val ids = list[i].ids.filter { it != itemId }
        if (ids.size != list[i].ids.size) { list[i] = list[i].copy(ids = ids); persist(c) }
    }

    fun of(id: String): UserCollection? = list.firstOrNull { it.id == id }
}
