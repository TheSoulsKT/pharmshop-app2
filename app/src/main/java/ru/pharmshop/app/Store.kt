package ru.pharmshop.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** История заказов и последний ник — хранятся только на этом устройстве. */
object Store {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences("pharmshop", Context.MODE_PRIVATE)
    }

    var lastUser: String
        get() = sp.getString("last_user", "") ?: ""
        set(v) { sp.edit().putString("last_user", v).apply() }

    @Synchronized
    fun orders(): List<JSONObject> {
        val arr = try { JSONArray(sp.getString("orders", "[]")) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o != null) out.add(o)
        }
        return out
    }

    private fun save(list: List<JSONObject>) {
        val arr = JSONArray()
        for (o in list) arr.put(o)
        sp.edit().putString("orders", arr.toString()).apply()
    }

    @Synchronized
    fun add(o: JSONObject) {
        val id = o.optString("id")
        val list = orders().filter { it.optString("id") != id }.toMutableList()
        list.add(0, o)
        save(list.take(100))
    }

    /** Обновляет сохранённый заказ свежими данными с сервера (остальные поля не трогает). */
    @Synchronized
    fun update(id: String, fresh: JSONObject) {
        val list = orders()
        for (o in list) {
            if (o.optString("id") == id) {
                val keys = fresh.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    o.put(k, fresh.get(k))
                }
            }
        }
        save(list)
    }

    @Synchronized
    fun clear() {
        sp.edit().remove("orders").apply()
    }
}
