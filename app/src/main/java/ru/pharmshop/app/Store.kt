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

    // ---------- сессия Telegram ----------
    var session: String
        get() = sp.getString("session", "") ?: ""
        set(v) { sp.edit().putString("session", v).apply() }
    var tgId: Long
        get() = sp.getLong("tg_id", 0L)
        set(v) { sp.edit().putLong("tg_id", v).apply() }
    var tgUsername: String
        get() = sp.getString("tg_username", "") ?: ""
        set(v) { sp.edit().putString("tg_username", v).apply() }
    var tgName: String
        get() = sp.getString("tg_name", "") ?: ""
        set(v) { sp.edit().putString("tg_name", v).apply() }
    /** Незавершённая попытка входа (чтобы продолжить после возврата из Telegram). */
    var pendingToken: String
        get() = sp.getString("pending_token", "") ?: ""
        set(v) { sp.edit().putString("pending_token", v).apply() }

    val loggedIn: Boolean get() = session.isNotEmpty()

    fun login(session: String, id: Long, username: String, name: String) {
        sp.edit().putString("session", session).putLong("tg_id", id)
            .putString("tg_username", username).putString("tg_name", name)
            .remove("pending_token").apply()
    }

    /** Выход: чистим сессию и заказы, чтобы они не достались другому аккаунту. */
    fun logout() {
        sp.edit().remove("session").remove("tg_id").remove("tg_username")
            .remove("tg_name").remove("pending_token").remove("orders").apply()
    }

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

    /** Добавляет/обновляет заказы с сервера, сохраняя локальные поля (method_name). */
    @Synchronized
    fun merge(remote: List<JSONObject>) {
        val byId = LinkedHashMap<String, JSONObject>()
        for (o in orders()) byId[o.optString("id")] = o
        for (r in remote) {
            val id = r.optString("id")
            if (id.isEmpty()) continue
            val cur = byId[id]
            if (cur == null) byId[id] = r else {
                val keys = r.keys()
                while (keys.hasNext()) { val k = keys.next(); cur.put(k, r.get(k)) }
            }
        }
        save(byId.values.sortedByDescending { it.optString("created_at") }.take(100))
    }
}
