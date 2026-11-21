package ru.pharmshop.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** История заказов и последний ник — хранятся только на этом устройстве. */
object Store {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences("pharmshop", Context.MODE_PRIVATE)
        // старые версии хранили сам код админа открытым текстом — стираем; админ войдёт заново и получит токен
        if (sp.contains("admin_code")) sp.edit().remove("admin_code").apply()
    }

    // ----- шифрование секретов ключом из Android Keystore (ключ нельзя вынуть из устройства) -----
    private const val KS_ALIAS = "pharmshop_secrets"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KS_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KS_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    /** Пустая строка, если данных нет или их не удалось расшифровать (например, после сброса хранилища ключей). */
    private fun unseal(blob: String): String = try {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    } catch (e: Exception) { "" }

    var lastUser: String
        get() = sp.getString("last_user", "") ?: ""
        set(v) { sp.edit().putString("last_user", v).apply() }

    /** Токен сессии администратора (код сервер выдаёт один раз при входе). Хранится зашифрованным. */
    var adminToken: String
        get() = sp.getString("admin_token", "")?.takeIf { it.isNotEmpty() }?.let { unseal(it) } ?: ""
        set(v) {
            sp.edit().putString("admin_token", if (v.isEmpty()) "" else try { seal(v) } catch (e: Exception) { "" }).apply()
        }

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

    // ----- фоновые уведомления -----
    /** Пользователь включает/выключает push в профиле. */
    var pushEnabled: Boolean
        get() = sp.getBoolean("push_enabled", true)
        set(v) { sp.edit().putBoolean("push_enabled", v).apply() }

    /** Пользователь хоть раз писал в поддержку — только тогда есть смысл спрашивать сервер об ответах. */
    var chatStarted: Boolean
        get() = sp.getBoolean("chat_started", false)
        set(v) { sp.edit().putBoolean("chat_started", v).apply() }

    /** Последний ответ поддержки, о котором уже уведомили (id сообщения). */
    var notifiedSupportId: Long
        get() = sp.getLong("n_support", 0L)
        set(v) { sp.edit().putLong("n_support", v).apply() }

    /** Последнее сообщение пользователя, о котором уже уведомили админа (время). */
    var notifiedAdminTs: Long
        get() = sp.getLong("n_admin", 0L)
        set(v) { sp.edit().putLong("n_admin", v).apply() }

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
        o.put("seen_kind", statusKind(effStatus(o)))   // о первом статусе уведомлять не нужно
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

    /** Пользователь видел заказы на экране: до этих статусов фоновые уведомления не нужны. */
    @Synchronized
    fun markOrdersSeen(onlyMissing: Boolean = false) {
        val list = orders()
        var changed = false
        for (o in list) {
            val k = statusKind(effStatus(o))
            if (onlyMissing && o.has("seen_kind")) continue
            if (o.optInt("seen_kind", -1) != k) { o.put("seen_kind", k); changed = true }
        }
        if (changed) save(list)
    }

    @Synchronized
    fun clear() {
        sp.edit().remove("orders").apply()
    }
}
