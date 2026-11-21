package ru.pharmshop.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Запросы к витрине pharmshop.tgstars.tg (те же, что делает сам сайт). */
class AuthExpired : Exception("Сессия истекла, войдите заново")

object Api {
    private const val BASE = "https://pharmshop.tgstars.tg"

    /**
     * Адрес ВАШЕГО сервера, который связан с ботом (см. папку server/).
     * Замените на свой домен, например "https://api.example.com".
     */
    const val AUTH_BASE = "https://YOUR_DOMAIN"
    val authConfigured: Boolean get() = !AUTH_BASE.contains("YOUR_DOMAIN")

    private fun call(
        method: String, path: String, body: JSONObject?,
        headers: Map<String, String> = emptyMap(),
        base: String = BASE
    ): JSONObject {
        var url = URL(base + path)
        var redirects = 0
        while (true) {
            val c = url.openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 15000
                c.readTimeout = 20000
                // Сами обрабатываем редиректы: HttpURLConnection при 301/302 превращает POST в GET.
                c.instanceFollowRedirects = false
                c.setRequestProperty("Accept", "application/json")
                c.setRequestProperty("User-Agent", "PharmShopApp/1.0")
                c.setRequestProperty("Origin", base)
                c.setRequestProperty("Referer", "$base/")
                for ((k, v) in headers) c.setRequestProperty(k, v)
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                if (code in listOf(301, 302, 303, 307, 308) && redirects < 3) {
                    val loc = c.getHeaderField("Location")
                    if (loc != null) {
                        url = URL(url, loc)   // сохраняем метод и тело
                        redirects++
                        continue
                    }
                }
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                val where = "$method ${url.path}"
                if (!text.trimStart().startsWith("{")) {
                    throw Exception("Сервер вернул не JSON (код $code, $where). Возможно, сайт не пустил приложение.")
                }
                val json = JSONObject(text)
                if (code == 401 && base == AUTH_BASE) throw AuthExpired()
                if (code !in 200..299) {
                    // JSONObject сам декодирует \uXXXX, так что сообщение читается по-русски
                    val msg = json.optString("error").ifEmpty { text.take(300) }
                    throw Exception("HTTP $code ($where): $msg")
                }
                return json
            } finally {
                c.disconnect()
            }
        }
    }

    fun config(): JSONObject = call("GET", "/api/storefront/config", null)

    /** Текущий статус заказа (так же делает сам сайт). */
    fun order(id: String): JSONObject = call("GET", "/api/storefront/orders/$id", null)

    fun quote(quantity: Int, methodId: String): JSONObject =
        call("POST", "/api/storefront/quote", JSONObject().put("quantity", quantity).put("method_id", methodId))

    fun createOrder(username: String, quantity: Int, methodId: String, captchaToken: String): JSONObject =
        call(
            "POST", "/api/storefront/orders",
            JSONObject().put("username", username).put("quantity", quantity)
                .put("method_id", methodId).put("captcha_token", captchaToken),
            // сайт шлёт этот заголовок с UUID — без него сервер отвечает 422
            mapOf("Idempotency-Key" to UUID.randomUUID().toString())
        )

    // ---------- вход через Telegram-бота (ваш сервер) ----------

    private fun bearer(session: String) = mapOf("Authorization" to "Bearer $session")

    /** Создаёт одноразовый токен входа. Ответ: {token, bot_url, expires_in}. */
    fun authStart(): JSONObject = call("POST", "/app/auth/start", JSONObject(), base = AUTH_BASE)

    /** Ответ: {status: "pending"|"expired"} или {status: "ok", session, user:{id, username, first_name}}. */
    fun authPoll(token: String): JSONObject =
        call("GET", "/app/auth/poll?token=$token", null, base = AUTH_BASE)

    fun me(session: String): JSONObject =
        call("GET", "/app/me", null, bearer(session), AUTH_BASE)

    fun logout(session: String): JSONObject =
        call("POST", "/app/auth/logout", JSONObject(), bearer(session), AUTH_BASE)

    /** Заказы пользователя из бота. Ответ: {orders: [...]} в том же формате, что и у витрины. */
    fun myOrders(session: String): JSONObject =
        call("GET", "/app/orders", null, bearer(session), AUTH_BASE)

    /** Привязывает заказ, созданный в приложении, к аккаунту. */
    fun linkOrder(session: String, orderId: String): JSONObject =
        call("POST", "/app/orders/link", JSONObject().put("id", orderId), bearer(session), AUTH_BASE)
}
