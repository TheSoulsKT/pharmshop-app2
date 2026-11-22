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

    /** «Повторить заказ»: количество звёзд, которое нужно подставить при открытии формы (используется один раз). */
    @Volatile var repeatQty: Int = 0

    /** Тема оформления: 1 — тёмная (по умолчанию), 2 — светлая, 0 — как в системе. */
    var theme: Int
        get() = sp.getInt("theme", 1)
        set(v) { sp.edit().putInt("theme", v).apply() }

    /** Оформление: 0 — Стандарт, 1 — Осень, 2 — Новый год, 3 — Лето, 4 — Весна, 5 — Космос, 6 — Rap (см. Skin). */
    var skin: Int
        get() = sp.getInt("skin", 0)
        set(v) { sp.edit().putInt("skin", v).apply() }

    /** Шаги заказа: 0 — значок со статусом (как раньше), 1 — полоса «Создан → Оплачен → Выполнен». */
    var stepsStyle: Int
        get() = sp.getInt("steps_style", 0)
        set(v) { sp.edit().putInt("steps_style", v).apply() }

    // ----- защита входа -----
    /** 0 — выключена, 1 — PIN, 2 — отпечаток (PIN остаётся запасным входом). */
    var lockMode: Int
        get() = sp.getInt("lock_mode", 0)
        set(v) { sp.edit().putInt("lock_mode", v).apply() }

    /** Через сколько секунд после ухода из приложения снова просить вход. */
    var lockDelaySec: Int
        get() = sp.getInt("lock_delay", 60)
        set(v) { sp.edit().putInt("lock_delay", v).apply() }

    val hasPin: Boolean get() = !sp.getString("pin_hash", "").isNullOrEmpty()

    /** Хэш PIN с солью, дополнительно зашифрован ключом Android Keystore. Сам PIN нигде не хранится. */
    fun setPin(pin: String) {
        val b = ByteArray(16)
        java.security.SecureRandom().nextBytes(b)
        val salt = b.joinToString("") { "%02x".format(it) }
        val h = pinDigest(pin, salt)
        val stored = try { "s:" + seal(h) } catch (e: Exception) { "p:$h" }
        sp.edit().putString("pin_salt", salt).putString("pin_hash", stored)
            .putInt("pin_fails", 0).putLong("pin_lock_until", 0L).apply()
    }

    fun checkPin(pin: String): Boolean {
        val stored = sp.getString("pin_hash", "") ?: ""
        val salt = sp.getString("pin_salt", "") ?: ""
        if (stored.length < 3 || salt.isEmpty()) return false
        val want = if (stored.startsWith("s:")) unseal(stored.substring(2)) else stored.substring(2)
        if (want.isEmpty()) return false
        return java.security.MessageDigest.isEqual(want.toByteArray(), pinDigest(pin, salt).toByteArray())
    }

    fun clearPin() {
        sp.edit().remove("pin_hash").remove("pin_salt").remove("pin_fails").remove("pin_lock_until").apply()
    }

    private fun pinDigest(pin: String, salt: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val saltBytes = salt.toByteArray()
        var d = saltBytes + pin.toByteArray()
        repeat(20000) { d = md.digest(d + saltBytes) }
        return d.joinToString("") { "%02x".format(it) }
    }

    /** Неверный PIN: после каждых 5 ошибок подряд вход блокируется на 30 с, 60 с, 2 мин … до 10 минут. */
    fun pinFailed() {
        val n = sp.getInt("pin_fails", 0) + 1
        val e = sp.edit().putInt("pin_fails", n)
        if (n % 5 == 0) {
            val step = n / 5 - 1
            val sec = minOf(30L shl minOf(step, 5), 600L)
            e.putLong("pin_lock_until", System.currentTimeMillis() + sec * 1000L)
        }
        e.apply()
    }

    fun pinFailsReset() { sp.edit().putInt("pin_fails", 0).putLong("pin_lock_until", 0L).apply() }

    /** Сколько миллисекунд ещё действует блокировка после неверных попыток (0 — можно вводить). */
    fun pinLockLeftMs(): Long = (sp.getLong("pin_lock_until", 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    /** Полный сброс данных приложения (если забыли PIN). */
    fun wipeAll() { sp.edit().clear().commit() }

    // ----- обновления в фоне -----
    /** Скачивать новые версии в фоне, когда телефон на Wi-Fi. */
    var wifiUpdate: Boolean
        get() = sp.getBoolean("wifi_update", false)
        set(v) { sp.edit().putBoolean("wifi_update", v).apply() }

    /** Сборка, уже скачанная в фоне и ожидающая установки (0 — нет). */
    var pendingUpdateCode: Int
        get() = sp.getInt("pending_code", 0)
        set(v) { sp.edit().putInt("pending_code", v).apply() }

    var pendingUpdateName: String
        get() = sp.getString("pending_name", "") ?: ""
        set(v) { sp.edit().putString("pending_name", v).apply() }

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

    /** Токен FCM этого устройства (пусто, если Firebase не настроен). */
    var fcmToken: String
        get() = sp.getString("fcm_token", "") ?: ""
        set(v) { sp.edit().putString("fcm_token", v).apply() }

    /** Токен, который уже передан серверу как токен пользователя (чтобы не слать повторно). */
    var fcmUserSent: String
        get() = sp.getString("fcm_user_sent", "") ?: ""
        set(v) { sp.edit().putString("fcm_user_sent", v).apply() }

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
