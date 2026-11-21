package ru.pharmshop.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Запросы к витрине pharmshop.tgstars.tg (те же, что делает сам сайт). */
object Api {
    private const val BASE = "https://pharmshop.tgstars.tg"

    private fun call(method: String, path: String, body: JSONObject?): JSONObject {
        var url = URL(BASE + path)
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
                c.setRequestProperty("Origin", BASE)
                c.setRequestProperty("Referer", "$BASE/")
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

    fun quote(quantity: Int, methodId: String): JSONObject =
        call("POST", "/api/storefront/quote", JSONObject().put("quantity", quantity).put("method_id", methodId))

    fun createOrder(username: String, quantity: Int, methodId: String, captchaToken: String): JSONObject =
        call(
            "POST", "/api/storefront/orders",
            JSONObject().put("username", username).put("quantity", quantity)
                .put("method_id", methodId).put("captcha_token", captchaToken)
        )
}
