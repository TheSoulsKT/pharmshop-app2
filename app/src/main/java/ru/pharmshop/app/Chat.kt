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

    private fun post(path: String, body: JSONObject, adminCode: String = ""): JSONObject {
        if (BASE_URL.isEmpty()) throw IllegalStateException("not_configured")
        val c = URL(BASE_URL + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
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

    // ----- клиент -----
    fun userSend(device: String, secret: String, nick: String, order: String, version: String, text: String) {
        post(
            "/api/chat/send",
            JSONObject().put("device", device).put("secret", secret).put("nick", nick)
                .put("order", order).put("version", version).put("text", text)
        )
    }

    fun userMessages(device: String, secret: String, after: Long): JSONArray =
        post("/api/chat/messages", JSONObject().put("device", device).put("secret", secret).put("after", after))
            .optJSONArray("messages") ?: JSONArray()

    // ----- администратор -----
    fun adminThreads(code: String): JSONArray =
        post("/api/admin/threads", JSONObject(), code).optJSONArray("threads") ?: JSONArray()

    fun adminMessages(code: String, device: String, after: Long): JSONArray =
        post("/api/admin/messages", JSONObject().put("device", device).put("after", after), code)
            .optJSONArray("messages") ?: JSONArray()

    fun adminReply(code: String, device: String, text: String) {
        post("/api/admin/reply", JSONObject().put("device", device).put("text", text), code)
    }
}
