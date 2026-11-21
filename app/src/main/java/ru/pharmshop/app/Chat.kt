package ru.pharmshop.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Неверный код администратора. */
class ChatAuthException(msg: String) : Exception(msg)

/** Клиент чата поддержки. Сервер: server/worker.js (Cloudflare Workers). */
object ChatApi {
    /**
     * Адрес вашего сервера чата, без слеша в конце, например:
     * "https://pharmshop-chat.ваш-аккаунт.workers.dev"
     */
    const val BASE_URL = "https://flat-violet-e617pharmshop-chat.soulszlord.workers.dev"

    /** Время, до которого собеседник прочитал наши сообщения (из последнего ответа сервера). */
    @Volatile var lastReadTs = 0L

    private fun post(path: String, body: JSONObject, adminToken: String = "", adminCode: String = ""): JSONObject {
        if (BASE_URL.isEmpty()) throw IllegalStateException("not_configured")
        val c = URL(BASE_URL + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            if (adminToken.isNotEmpty()) c.setRequestProperty("X-Admin-Token", adminToken)
            if (adminCode.isNotEmpty()) c.setRequestProperty("X-Admin-Code", adminCode)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val json = try { JSONObject(text) } catch (e: Exception) {
                throw Exception("Сервер чата ответил неожиданно (код $code)")
            }
            if (code == 401) throw ChatAuthException(json.optString("error", "Неверный код"))
            if (code !in 200..299) throw Exception(json.optString("error", "Ошибка $code"))
            return json
        } finally {
            c.disconnect()
        }
    }

    /** Модель, Android и версия приложения — уходит вместе с каждым обращением в поддержку. */
    fun deviceInfo(): String =
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} " +
            "(API ${android.os.Build.VERSION.SDK_INT}), приложение ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    // ----- клиент -----
    fun userSend(
        device: String, secret: String, nick: String, order: String, version: String,
        text: String, image: String = ""
    ) {
        val body = JSONObject().put("device", device).put("secret", secret).put("nick", nick)
            .put("order", order).put("version", version).put("text", text).put("info", deviceInfo())
        if (image.isNotEmpty()) body.put("image", image)
        post("/api/chat/send", body)
        Store.chatStarted = true
    }

    /** Фоновый опрос: {unread, last_id, text} — непрочитанные ответы поддержки. Ничего не помечает прочитанным. */
    fun userPoll(device: String, secret: String): JSONObject =
        post("/api/chat/poll", JSONObject().put("device", device).put("secret", secret))

    fun userImage(device: String, secret: String, id: Long): String =
        post("/api/chat/image", JSONObject().put("device", device).put("secret", secret).put("id", id))
            .optString("image")

    fun userMessages(device: String, secret: String, after: Long): JSONArray {
        val r = post("/api/chat/messages", JSONObject().put("device", device).put("secret", secret).put("after", after))
        lastReadTs = r.optLong("read_ts")
        return r.optJSONArray("messages") ?: JSONArray()
    }

    /** Объявление, настройки разделов и минимальная версия (публично, без кода). */
    fun appConfig(versionCode: Int): JSONObject =
        post("/api/app/config", JSONObject().put("version_code", versionCode))

    // ----- администратор -----
    /** Вход по коду: сервер возвращает токен сессии (30 дней). Сам код дальше нигде не хранится и не пересылается. */
    fun adminLogin(code: String): String =
        post("/api/admin/login", JSONObject(), adminCode = code).optString("token")
            .ifEmpty { throw Exception("Сервер не выдал токен") }

    /** Завершает сессию на сервере (ошибки сети не важны — токен всё равно стирается на устройстве). */
    fun adminLogout(token: String) {
        try { post("/api/admin/logout", JSONObject(), token) } catch (e: Exception) { /* не критично */ }
    }

    /** Ответ: {threads: [...], counts: {new, work, done, unread}}. status: "", "new", "work", "done". */
    fun adminThreads(code: String, status: String = "", unread: Boolean = false, query: String = ""): JSONObject {
        val body = JSONObject()
        if (status.isNotEmpty()) body.put("status", status)
        if (unread) body.put("unread", true)
        if (query.isNotEmpty()) body.put("q", query)
        return post("/api/admin/threads", body, code)
    }

    /** Фоновый опрос админа: {unread, last_ts, nick, text} — чаты с непрочитанными сообщениями. */
    fun adminPoll(token: String): JSONObject = post("/api/admin/poll", JSONObject(), token)

    fun adminMessages(code: String, device: String, after: Long): JSONArray {
        val r = post("/api/admin/messages", JSONObject().put("device", device).put("after", after), code)
        lastReadTs = r.optLong("read_ts")
        return r.optJSONArray("messages") ?: JSONArray()
    }

    fun adminReply(code: String, device: String, text: String, image: String = "") {
        val body = JSONObject().put("device", device).put("text", text)
        if (image.isNotEmpty()) body.put("image", image)
        post("/api/admin/reply", body, code)
    }

    fun adminImage(code: String, device: String, id: Long): String =
        post("/api/admin/image", JSONObject().put("device", device).put("id", id), code).optString("image")

    fun adminSetStatus(code: String, device: String, status: String) {
        post("/api/admin/status", JSONObject().put("device", device).put("status", status), code)
    }

    /** Другие устройства, писавшие с тех же IP: [{device, nick, ip, ts}]. */
    fun adminRelated(code: String, device: String): JSONArray =
        post("/api/admin/related", JSONObject().put("device", device), code).optJSONArray("related") ?: JSONArray()

    /** kind: "device" или "ip". */
    fun adminBlock(code: String, kind: String, value: String, reason: String = "") {
        post("/api/admin/block", JSONObject().put("kind", kind).put("value", value).put("reason", reason), code)
    }

    fun adminUnblock(code: String, kind: String, value: String) {
        post("/api/admin/unblock", JSONObject().put("kind", kind).put("value", value), code)
    }

    fun adminBlocks(code: String): JSONArray =
        post("/api/admin/blocks", JSONObject(), code).optJSONArray("blocks") ?: JSONArray()

    fun adminSettings(code: String): JSONObject = post("/api/admin/settings", JSONObject(), code)

    /** patch: любые из announcement, sections, min_version_code, update_message. Возвращает сохранённые настройки. */
    fun adminSaveSettings(code: String, patch: JSONObject): JSONObject =
        post("/api/admin/settings_save", patch, code)
}
