package ru.pharmshop.app

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.hcaptcha.sdk.HCaptcha
import com.hcaptcha.sdk.HCaptchaConfig
import com.hcaptcha.sdk.HCaptchaException
import com.hcaptcha.sdk.HCaptchaTokenResponse
import org.json.JSONObject

// Покупка Premium и оплата.

// ---------- покупка Premium ----------
internal fun MainActivity.showPremium() {
    onHome = false
    Diag.feature("premium")
    val col = screen(bottomBar = true)
    val sid = screenId
    buyHeader(col, "Купить Premium")
    val status = tv("Загружаем данные…", 14f, MUTE)
    col.addView(status)
    riseIn(col)
    io.execute {
        try {
            val c = cfg ?: Api.config().also { cfg = it }
            ui { if (sid == screenId) { status.visibility = View.GONE; buildPremiumForm(col, c) } }
        } catch (e: Exception) {
            ui { if (sid == screenId) loadFailed(col, status, sid, e) { showPremium() } }
        }
    }
}

internal fun MainActivity.buildPremiumForm(col: LinearLayout, c: JSONObject) {
    val firstNew = col.childCount
    // доступные сроки: (месяцев, цена от)
    val plans = ArrayList<Pair<Int, String>>()
    val arr = c.optJSONArray("premium")
    if (arr != null) for (i in 0 until arr.length()) {
        val p = arr.optJSONObject(i) ?: continue
        if (p.optBoolean("available", true)) plans.add(Pair(p.optInt("months"), p.optString("total")))
    }
    val methods = shownMethods(c)
    if (plans.isEmpty() || methods.isEmpty()) {
        col.addView(tv("Premium сейчас недоступен", 14f, MUTE))
        return
    }
    c.optString("hcaptcha_sitekey").takeIf { it.isNotEmpty() }?.let { captcha(it) }

    // получатель
    col.addView(sectionLabel("Получатель", true))
    val (userBox, user) = userField()
    user.setText(Store.lastUser)
    col.addView(userBox)

    var months = plans[0].first
    var method = methods[0].first
    var methodName = methods[0].second
    var token = 0

    // закреплённая панель: сумма и кнопка
    var watch: ServerWatch? = null
    val bar = payBar { info ->
        pay(user.text.toString(), months, method, methodName, c, info, "premium", watch)
    }
    // плашка «Статус сервера» — над формой
    val w = ServerWatch(this, screenId, { st -> bar.setBlocked(st == SrvState.OFFLINE) })
    watch = w
    col.addView(w.root, firstNew)

    fun requestQuote() {
        token++
        val my = token
        bar.cap.text = "К оплате · $months мес."
        popText(bar.total, "…")
        val m = months
        val mt = method
        io.execute {
            val t0 = System.currentTimeMillis()
            try {
                val r = Api.quote(m, mt, "premium")
                val ms = System.currentTimeMillis() - t0
                ui { if (my == token) { popText(bar.total, fmtMoney(r)); watch?.reportOk(ms) } }
            } catch (e: Exception) {
                ui {
                    if (my == token) {
                        popText(bar.total, if (Api.isServerDown(e)) "Сайт не работает" else "Не удалось рассчитать цену")
                        watch?.reportError(e)
                    }
                }
            }
        }
    }

    // срок подписки: широкие карточки со скидкой за длинный срок
    col.addView(sectionLabel("Срок подписки"))
    val planBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(4) }
    col.addView(planBox)
    planRows(planBox, plans, months) { m -> months = m; requestQuote() }

    // способ оплаты
    col.addView(sectionLabel("Способ оплаты"))
    val methodBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(4) }
    col.addView(methodBox)
    methodPicker(methodBox, methods, method) { id, name -> method = id; methodName = name; requestQuote() }

    attachBar(col, bar.root)
    riseIn(col, firstNew)
    requestQuote()
}

/** Готовим hCaptcha заранее, чтобы по кнопке «Оплатить» проверка открывалась сразу. */
internal fun MainActivity.captcha(siteKey: String): HCaptcha {
    hc?.let { return it }
    val h = HCaptcha.getClient(this)
    h.addOnSuccessListener { r: HCaptchaTokenResponse -> hcOk?.invoke(r.tokenResult) }
    h.addOnFailureListener { e: HCaptchaException -> hcFail?.invoke(e.message ?: "") }
    // Сервер сайта проверяет домен токена, поэтому подписываемся его доменом, а не hcaptcha.com
    h.setup(HCaptchaConfig.builder().siteKey(siteKey).host("pharmshop.tgstars.tg").build())
    hc = h
    return h
}

/**
 * Ключ идемпотентности для «Оплатить». Если прошлая попытка закончилась неясно (ответ потерян, сайт ответил не JSON-ом),
 * заказ мог создаться, и повторное нажатие с тем же ключом вернёт тот же заказ, а не создаст второй.
 * После успеха или чёткого отказа сайта ключ сбрасывается. Живёт 10 минут.
 */
private class PendingKey(val sig: String, val key: String, val ts: Long)
@Volatile private var pendingKey: PendingKey? = null

private fun keyFor(sig: String): String {
    val p = pendingKey
    if (p != null && p.sig == sig && System.currentTimeMillis() - p.ts < 10 * 60_000L) return p.key
    val k = Api.newIdempotencyKey()
    pendingKey = PendingKey(sig, k, System.currentTimeMillis())
    return k
}

/**
 * Оформление заказа. Заказ создаётся, только если одновременно выполнено:
 *  1) сайт работает (свежий запрос к нему делается прямо перед капчей);
 *  2) капча пройдена (пустой токен или отказ/закрытие капчи — заказа нет).
 */
internal fun MainActivity.pay(
    rawUser: String, q: Int, method: String, methodName: String, c: JSONObject, info: TextView,
    product: String = "", watch: ServerWatch? = null
) {
    if (busy) return
    val username = rawUser.trim().removePrefix("@")
    val min = c.optInt("min", 50)
    val max = c.optInt("max", 10000)
    if (username.length < 4) { toast("Введите имя пользователя"); return }
    if (product.isEmpty() && (q < min || q > max)) { toast("Количество: от $min до $max"); return }
    val siteKey = c.optString("hcaptcha_sitekey")
    if (siteKey.isEmpty()) { toast("Нет ключа проверки"); return }

    busy = true
    info.text = "Проверяем сайт…"
    io.execute {
        // свежая проверка прямо перед капчей: токен капчи живёт недолго, тратить его на лежащий сайт незачем
        val r = Api.ping()
        ui {
            if (watch != null) watch.applyPing(r) else ServerHealth.fromPing(r)
            if (!r.ok) {
                busy = false
                info.text = "Сайт не работает (${r.detail}) — заказ не создан. Попробуйте позже."
                return@ui
            }
            verifyCaptchaAndOrder(username, q, method, methodName, siteKey, product, info, watch)
        }
    }
}

/** Капча → заказ. Без успешно пройденной капчи (непустой токен) заказ не создаётся. */
private fun MainActivity.verifyCaptchaAndOrder(
    username: String, q: Int, method: String, methodName: String, siteKey: String,
    product: String, info: TextView, watch: ServerWatch?
) {
    info.text = "Пройдите проверку…"
    val h = captcha(siteKey)
    var finished = false   // капча срабатывает один раз: повторный/запоздалый колбэк заказ не создаёт
    hcOk = ok@{ token ->
        if (finished) return@ok
        finished = true
        if (token.isBlank()) {
            busy = false
            hc = null
            info.text = "Проверка не пройдена — заказ не создан"
            return@ok
        }
        info.text = "Проверка пройдена ✓ Создаём заказ…"
        io.execute {
            try {
                val key = keyFor("$product|$username|$q|$method")
                val o = Api.createOrder(username, q, method, token, product, key)
                pendingKey = null
                o.put("method_name", methodName)
                Store.add(o)
                Store.lastUser = username
                OrderSync.syncAll()
                val url = o.optString("payment_url")
                val num = o.optString("order_number")
                ui {
                    busy = false
                    info.text = "Заказ $num создан. Переходим к оплате…"
                    openPay(url, info)
                }
            } catch (e: Exception) {
                // чёткий отказ сайта — заказа нет, ключ не нужен; неясный исход (не JSON, обрыв сети) — ключ оставляем
                if (e !is NotJsonException && e !is java.io.IOException) pendingKey = null
                val unsure = if (e is NotJsonException || e is java.io.IOException)
                    "\n\nЗаказ мог создаться. Проверьте Профиль → Заказы, прежде чем платить ещё раз." else ""
                ui {
                    busy = false
                    info.text = "Ошибка: ${e.message}$unsure"
                    if (Api.isServerDown(e)) watch?.reportError(e)
                }
            }
        }
    }
    hcFail = { msg ->
        finished = true
        busy = false
        hc = null   // в следующий раз подготовим проверку заново
        info.text = "Проверка не пройдена — заказ не создан" + if (msg.isNotEmpty()) " ($msg)" else ""
    }
    h.verifyWithHCaptcha()
}

internal fun MainActivity.openPay(url: String, info: TextView) {
    if (!url.startsWith("https://")) {
        info.text = "Сайт не вернул ссылку на оплату"
        return
    }
    try {
        returnFromPay = true
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        returnFromPay = false
        info.text = "Откройте ссылку вручную: $url"
    }
}

/**
 * Экран не смог загрузить конфигурацию — значит, сайт, скорее всего, не работает.
 * Показываем плашку «Статус сервера» с причиной; как только сайт оживёт, экран откроется сам.
 */
internal fun MainActivity.loadFailed(col: LinearLayout, status: TextView, sid: Int, e: Exception, retry: () -> Unit) {
    status.text = "Не удалось загрузить данные: ${e.message}"
    val w = ServerWatch(this, sid, {}, { if (sid == screenId) retry() })
    if (Api.isServerDown(e)) w.reportError(e)
    col.addView(w.root, col.indexOfChild(status))
}
