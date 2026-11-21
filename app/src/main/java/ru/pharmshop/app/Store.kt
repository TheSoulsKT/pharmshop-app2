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

    /** Код администратора (хранится только на этом устройстве). */
    var adminCode: String
        get() = sp.getString("admin_code", "") ?: ""
        set(v) { sp.edit().putString("admin_code", v).apply() }

    /** Анонимный идентификатор устройства для чата поддержки. */
    val deviceId: String
        get() {
            val cur = sp.getString("dev_id", "") ?: ""
            if (cur.isNotEmpty()) return cur
            val n = java.util.UUID.randomUUID().toString()
            sp.edit().putString("dev_id", n).apply()
            return n
        }

    /** Секрет устройства: по нему сервер понимает, что это ваш чат, а не чужой. */
    val deviceSecret: String
        get() {
            val cur = sp.getString("dev_secret", "") ?: ""
            if (cur.isNotEmpty()) return cur
            val b = ByteArray(24)
            java.security.SecureRandom().nextBytes(b)
            val n = b.joinToString("") { "%02x".format(it) }
            sp.edit().putString("dev_secret", n).apply()
            return n
        }

    /** Последние удалённые настройки (объявление, разделы, минимальная версия) — работают и без сети. */
    var remoteConfig: String
        get() = sp.getString("remote_config", "") ?: ""
        set(v) { sp.edit().putString("remote_config", v).apply() }

    /** Имена способов оплаты {id: имя} — как они показаны в форме (для подписи заказов). */
    var methodNames: String
        get() = sp.getString("method_names", "{}") ?: "{}"
        set(v) { sp.edit().putString("method_names", v).apply() }

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
