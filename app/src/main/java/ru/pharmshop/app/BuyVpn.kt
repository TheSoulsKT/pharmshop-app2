package ru.pharmshop.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

// Раздел «VPN»: вход через Telegram или по почте, покупка подписки и выдача ключей.
// Устроен как экраны звёзд и Premium: форма, панель оплаты, оплата на странице платёжки.
// Сайт: pvpn.vitrinka.net (запросы — в VpnApi). Подписки всегда берём с сервера, в «Мои заказы» они не попадают.

private const val V_WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Номер проверки платежа: новая проверка гасит старую, чтобы не крутились две сразу. */
private var vpnPollGen = 0

// ---------- подписи ----------

/** Копейки → «118 ₽» (с округлением вверх, как на сайте). */
private fun vpnRub(minor: Long): String = "${(minor + 99) / 100} ₽"

private fun vpnDays(n: Int): String {
    val m100 = n % 100
    val m10 = n % 10
    val w = if (m100 in 11..14) "дней" else when (m10) { 1 -> "день"; 2, 3, 4 -> "дня"; else -> "дней" }
    return "$n $w"
}

private fun vpnDevices(n: Int): String {
    val m100 = n % 100
    val m10 = n % 10
    val w = if (m100 in 11..14) "устройств" else when (m10) { 1 -> "устройство"; 2, 3, 4 -> "устройства"; else -> "устройств" }
    return "$n $w"
}

/** «2026-12-05T18:27:16Z» → «05.12.2026». */
private fun vpnDate(s: String): String {
    val d = s.take(10).split("-")
    return if (d.size == 3) "${d[2]}.${d[1]}.${d[0]}" else s
}

/** Название тарифа для экрана: с большой буквы, как на сайте. */
private fun vpnPlanName(name: String): String = name.take(1).uppercase() + name.drop(1)

private fun vpnProviderName(id: String): String = when (id) {
    "platega" -> "СБП"
    "aurapay" -> "СБП 2"
    "antilopay" -> "СБП 3"
    "cryptobot" -> "CryptoBot (через Telegram)"
    "xrocket" -> "xRocket (через Telegram)"
    "lzt" -> "LZT Market"
    else -> id
}

// ---------- экран ----------

internal fun MainActivity.showVpn() {
    onHome = false
    val col = screen(4, bottomBar = true)
    val sid = screenId
    titleRow(col, "VPN")
    if (!VpnApi.loggedIn()) {
        buildVpnLogin(col, sid)
        riseIn(col)
        return
    }
    val status = tv("Загружаем данные…", 14f, MUTE)
    col.addView(status)
    riseIn(col)
    io.execute {
        try {
            val subs = VpnApi.subscriptions()
            val plans = VpnApi.plans()
            val prov = VpnApi.providers()
            ui { if (sid == screenId) { status.visibility = View.GONE; buildVpnForm(col, sid, subs, plans, prov) } }
        } catch (e: VpnApi.AuthException) {
            ui { if (sid == screenId) showVpn() }   // сессия кончилась — откроется экран входа
        } catch (e: Exception) {
            ui { if (sid == screenId) status.text = "Не удалось загрузить данные: ${e.message}" }
        }
    }
}

// ---------- вход через Telegram ----------

private fun MainActivity.buildVpnLogin(col: LinearLayout, sid: Int) {
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(CARD, 18, LINE)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = lp(12)
    }
    card.addView(txt("Вход через Telegram", 18f, TEXT, true).apply { layoutParams = lp(6) })
    card.addView(txt(
        "Откроется бот Pharm VPN. Нажмите в нём Start и вернитесь в приложение — вход подтвердится сам.",
        14f, MUTE
    ).apply { layoutParams = lp(12) })
    val info = txt("", 13f, MUTE).apply {
        visibility = View.GONE
        layoutParams = lp(10)
    }
    card.addView(btn("Войти через Telegram", true) {
        if (busy) return@btn
        busy = true
        info.visibility = View.VISIBLE
        info.text = "Создаём вход…"
        io.execute {
            try {
                val r = VpnApi.startLogin()
                val pollId = r.getString("poll_id")
                val url = r.getString("bot_url")
                val num = r.optInt("number", 0)
                val until = System.currentTimeMillis() + r.optLong("expires_in", 300L) * 1000L
                ui {
                    busy = false
                    if (sid == screenId) {
                        info.text = if (num > 0) "Ждём подтверждение в Telegram… Номер входа: $num" else "Ждём подтверждение в Telegram…"
                        openUrl(url)
                        pollVpnLogin(sid, pollId, until, info)
                    }
                }
            } catch (e: Exception) {
                ui { busy = false; info.text = "Ошибка: ${e.message}" }
            }
        }
    })
    card.addView(info)
    col.addView(card)
    buildVpnEmailAuth(col, sid)   // вход, регистрация и сброс пароля по почте (VpnAuthUi.kt)
}

private fun MainActivity.pollVpnLogin(sid: Int, pollId: String, until: Long, info: TextView) {
    if (sid != screenId) return
    if (System.currentTimeMillis() > until) {
        info.text = "Время ожидания вышло. Нажмите кнопку ещё раз."
        return
    }
    io.execute {
        val res = try { VpnApi.pollLogin(pollId) } catch (e: Exception) { "error" }   // сеть моргнула — пробуем дальше
        ui {
            if (sid != screenId) return@ui
            when (res) {
                "confirmed" -> showVpn()
                "pending", "error" -> main.postDelayed({ pollVpnLogin(sid, pollId, until, info) }, 2000L)
                else -> { info.text = "Вход не подтверждён ($res). Нажмите кнопку ещё раз." }
            }
        }
    }
}

// ---------- подписки и покупка ----------

private fun MainActivity.buildVpnForm(col: LinearLayout, sid: Int, subs: JSONArray, plans: JSONArray, prov: JSONObject) {
    val firstNew = col.childCount

    // платёж создан, но ещё не подтверждён
    val pending = Store.vpnPay
    val waitInfo = txt("⏳ Ждём подтверждение оплаты…", 14f, TEXT)
    if (pending.isNotEmpty()) {
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(0).apply { topMargin = dp(10) }
        }
        actions.addView(chip("Проверить сейчас", true) {
            vpnPollGen++
            waitInfo.text = "⏳ Проверяем оплату…"
            pollVpnPayment(sid, pending, 0, vpnPollGen, waitInfo)
        })
        actions.addView(chip("Не жду", false) { Store.vpnPay = ""; vpnPollGen++; showVpn() })
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(mix(BLUE, 0.14f), 18, BLUE)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = lp(12)
            addView(waitInfo)
            addView(actions)
        })
    }

    val methods = ArrayList<Pair<String, String>>()
    prov.optJSONArray("providers")?.let { arr ->
        for (i in 0 until arr.length()) {
            val id = arr.optString(i)
            if (id.isNotEmpty()) methods.add(Pair(id, vpnProviderName(id)))
        }
    }

    // мои подписки с ключами
    if (subs.length() > 0) {
        col.addView(sectionLabel("Мои подписки", pending.isEmpty()))
        for (i in 0 until subs.length()) {
            val s = subs.optJSONObject(i) ?: continue
            col.addView(vpnSubCard(s, methods))
        }
    }

    // тарифы (бессрочные не берём): по сроку, потом по цене
    val list = ArrayList<JSONObject>()
    for (i in 0 until plans.length()) {
        val p = plans.optJSONObject(i) ?: continue
        if (!p.optBoolean("is_lifetime")) list.add(p)
    }
    list.sortWith(compareBy<JSONObject>({ it.optInt("days") }, { it.optLong("total_price_display") }))

    if (list.isEmpty() || methods.isEmpty()) {
        col.addView(tv("Покупка VPN сейчас недоступна", 14f, MUTE))
        riseIn(col, firstNew)
        if (pending.isNotEmpty()) { vpnPollGen++; pollVpnPayment(sid, pending, 0, vpnPollGen, waitInfo) }
        return
    }

    var plan: JSONObject = list.firstOrNull { it.optBoolean("is_popular") } ?: list[0]
    var method = methods[0].first
    var quote: JSONObject? = null
    var qTok = 0

    // закреплённая панель: сумма и кнопка
    val bar = payBar { info -> payVpn(plan.optString("uuid"), method, info) }

    fun paintTotal() {
        val q = quote
        val byProvider = q?.optJSONObject("provider_totals")?.optLong(method, -1L) ?: -1L
        val minor = if (byProvider >= 0) byProvider else (q?.optLong("total_display_minor", -1L) ?: -1L)
        popText(bar.total, if (minor >= 0) vpnRub(minor) else "—")
    }

    fun requestQuote() {
        qTok++
        val my = qTok
        val uuid = plan.optString("uuid")
        bar.cap.text = "К оплате · ${vpnDays(plan.optInt("days"))}"
        quote = null
        popText(bar.total, "…")
        io.execute {
            try {
                val r = VpnApi.quote(uuid)
                ui { if (my == qTok && sid == screenId) { quote = r; paintTotal() } }
            } catch (e: Exception) {
                ui { if (my == qTok && sid == screenId) popText(bar.total, "—") }
            }
        }
    }

    // тарифы: карточки, сгруппированные по сроку
    col.addView(sectionLabel(if (subs.length() > 0) "Купить новую подписку" else "Тариф", subs.length() == 0 && pending.isEmpty()))
    val planBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(4) }
    col.addView(planBox)
    val rows = ArrayList<Pair<String, LinearLayout>>()
    fun paintRows() {
        for ((id, r) in rows) {
            val sel = id == plan.optString("uuid")
            r.background = ripple(if (sel) mix(BLUE, 0.16f) else CARD, 16, if (sel) BLUE else LINE)
        }
    }
    var lastDays = -1
    for (p in list) {
        val d = p.optInt("days")
        if (d != lastDays) {
            lastDays = d
            planBox.addView(txt(vpnDays(d), 13f, MUTE).apply { layoutParams = lp(6).apply { topMargin = dp(6) } })
        }
        val name = p.optString("name")
        val title = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(txt(vpnPlanName(name), 16f, TEXT, true))
            if (p.optBoolean("is_special")) addView(vpnPill("Особый"))
            else if (p.optBoolean("is_popular")) addView(vpnPill("Популярный"))
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, V_WRAP, 1f)
            addView(title)
            addView(txt(vpnDevices(p.optInt("devices")), 13f, MUTE))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = lp(8)
            isClickable = true
            setOnClickListener { plan = p; paintRows(); requestQuote() }
            pressable(this)
            addView(left)
            addView(txt(vpnRub(p.optLong("total_price_display")), 16f, TEXT, true))
        }
        planBox.addView(row)
        rows.add(Pair(p.optString("uuid"), row))
    }

    // способ оплаты (комиссия уже учтена в сумме внизу)
    col.addView(sectionLabel("Способ оплаты"))
    val methodBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(4) }
    col.addView(methodBox)
    methodPicker(methodBox, methods, method) { id, _ -> method = id; paintTotal() }

    val topNote = txt("", 13f, MUTE).apply { visibility = View.GONE; layoutParams = lp(4) }
    col.addView(txt("Пополнить баланс", 13f, MUTE).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(14), 0, dp(6))
        layoutParams = lp(0)
        isClickable = true
        setOnClickListener { askTopup(methods, topNote) }
    })
    col.addView(topNote)
    col.addView(txt("Выйти из аккаунта VPN", 13f, MUTE).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(14), 0, dp(14))
        layoutParams = lp(4)
        isClickable = true
        setOnClickListener { VpnApi.logout(); vpnPollGen++; showVpn() }
    })

    attachBar(col, bar.root)
    riseIn(col, firstNew)
    paintRows()
    requestQuote()
    if (pending.isNotEmpty()) { vpnPollGen++; pollVpnPayment(sid, pending, 0, vpnPollGen, waitInfo) }
}

private fun MainActivity.vpnPill(t: String): TextView =
    txt(t, 11f, android.graphics.Color.BLACK, true).apply {
        background = shape(BLUE, 8)
        setPadding(dp(7), dp(2), dp(7), dp(2))
        layoutParams = LinearLayout.LayoutParams(V_WRAP, V_WRAP).apply { leftMargin = dp(8) }
    }

// ---------- карточка подписки с ключом ----------

private fun MainActivity.vpnSubCard(s: JSONObject, methods: List<Pair<String, String>>): LinearLayout {
    val active = s.optBoolean("is_active")
    val url = s.optString("subscription_url")
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(CARD, 18, if (active) edge(BLUE) else LINE)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = lp(10)
    }
    val name = s.optString("plan_name").ifEmpty { "Подписка" }
    card.addView(txt("${vpnPlanName(name)} · ${vpnDevices(s.optInt("devices"))}", 16f, TEXT, true))
    val end = vpnDate(s.optString("end_date"))
    val line = when {
        s.optBoolean("is_frozen") -> "Заморожена · до $end"
        active -> "Активна · осталось ${vpnDays(s.optInt("days_left"))} · до $end"
        else -> "Не активна · до $end"
    }
    card.addView(txt(line, 13f, if (active) GREEN else MUTE).apply { layoutParams = lp(10) })
    val limBytes = s.optLong("traffic_limit_bytes")
    if (limBytes > 0) {
        card.addView(txt("Трафик: ${vpnGb(s.optLong("used_traffic_bytes"))} из ${vpnGb(limBytes)}", 13f, MUTE).apply { layoutParams = lp(10) })
    }
    if (url.startsWith("https://")) {
        card.addView(txt(url, 12f, MUTE).apply {
            setTextIsSelectable(true)
            layoutParams = lp(10)
        })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(0)
        }
        actions.addView(chip("Скопировать ссылку", true) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("vpn", url))
            toast("Ссылка подписки скопирована")
        })
        actions.addView(chip("Открыть в клиенте", false) { openInVpnClient(url) })
        card.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(actions)
        })
    }
    val subId = VpnApi.subId(s)
    if (subId.isNotEmpty()) {
        val note = txt("", 13f, MUTE).apply { visibility = View.GONE; layoutParams = lp(8) }
        val acts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = lp(8) }
        acts.addView(chip("Продлить", true) {
            if (busy) return@chip
            pickRenewDays(subId, methods, note)
        })
        val frozen = s.optBoolean("is_frozen")
        if (!s.optBoolean("is_trial") && !s.optBoolean("is_lifetime")) acts.addView(chip("Больше устройств", false) {
            if (busy) return@chip
            startUpgrade(s, subId, methods, note)
        })
        acts.addView(chip(if (frozen) "Разморозить" else "Заморозить", false) {
            if (busy) return@chip
            val go = {
                busy = true
                io.execute {
                    try {
                        if (frozen) VpnApi.unfreeze(subId) else VpnApi.freeze(subId)
                        ui { busy = false; toast(if (frozen) "Подписка разморожена" else "Подписка заморожена"); showVpn() }
                    } catch (e: VpnApi.AuthException) {
                        ui { busy = false; showVpn() }
                    } catch (e: Exception) {
                        ui { busy = false; toast("Ошибка: ${e.message}") }
                    }
                }
            }
            if (frozen) go() else androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Заморозить подписку?")
                .setMessage("Подписка будет приостановлена. Разморозить её можно в любой момент.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Заморозить") { _, _ -> go() }
                .show()
        })
        acts.addView(chip("Обновить ссылку", false) {
            if (busy) return@chip
            val avail = s.optString("link_rotation_available_at")
            val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
            if (avail.length >= 19 && avail != "null" && avail.take(19) > now) {
                toast("Ссылку можно обновить с ${vpnDateTime(avail)}")
                return@chip
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Обновить ссылку?")
                .setMessage("Старая ссылка перестанет работать, и по ней больше никто не подключится. Кто уже подключился по ней, останется до конца срока подписки.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Обновить") { _, _ ->
                    busy = true
                    io.execute {
                        try {
                            VpnApi.rotateLink(subId)
                            ui { busy = false; toast("Ссылка обновлена"); showVpn() }
                        } catch (e: VpnApi.AuthException) {
                            ui { busy = false; showVpn() }
                        } catch (e: Exception) {
                            ui { busy = false; toast("Ошибка: ${e.message}") }
                        }
                    }
                }.show()
        })
        if (limBytes > 0) acts.addView(chip("Докупить трафик", false) {
            if (busy) return@chip
            note.visibility = View.VISIBLE
            note.text = "Загружаем пакеты…"
            io.execute {
                try {
                    val packs = VpnApi.trafficPacks(subId)
                    ui { note.visibility = View.GONE; pickTrafficPack(subId, methods, packs, note) }
                } catch (e: VpnApi.AuthException) {
                    ui { showVpn() }
                } catch (e: Exception) {
                    ui { note.text = "Не удалось загрузить пакеты: ${e.message}" }
                }
            }
        })
        card.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(acts)
        })
        card.addView(note)
        vpnDevicesBlock(card, subId)
    }
    return card
}

/** Срок продления: как на сайте — 1 месяц, 7/14/60/90 дней или свой (от 3 до 365). null = «1 месяц». */
private fun MainActivity.pickRenewDays(subId: String, methods: List<Pair<String, String>>, note: TextView) {
    val opts = arrayOf("1 месяц", "7 дней", "14 дней", "60 дней", "90 дней", "Свой срок…")
    val vals = arrayOf<Int?>(null, 7, 14, 60, 90, -1)
    androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle("На сколько продлить?")
        .setItems(opts) { _, i ->
            if (vals[i] == -1) askCustomDays(subId, methods, note) else quoteRenew(subId, methods, note, vals[i])
        }
        .show()
}

private fun MainActivity.askCustomDays(subId: String, methods: List<Pair<String, String>>, note: TextView) {
    val input = android.widget.EditText(this).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        hint = "от 3 до 365"
        setPadding(dp(20), dp(14), dp(20), dp(14))
    }
    androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle("Свой срок, дней")
        .setView(input)
        .setNegativeButton("Отмена", null)
        .setPositiveButton("Дальше") { _, _ ->
            val n = input.text.toString().trim().toIntOrNull()
            if (n == null || n < 3 || n > 365) toast("Введите число от 3 до 365") else quoteRenew(subId, methods, note, n)
        }
        .show()
}

private fun MainActivity.quoteRenew(subId: String, methods: List<Pair<String, String>>, note: TextView, days: Int?) {
    note.visibility = View.VISIBLE
    note.text = "Считаем цену…"
    io.execute {
        var q: JSONObject? = null
        try {
            q = VpnApi.renewQuote(subId, days)
        } catch (e: VpnApi.AuthException) {
            ui { showVpn() }
            return@execute
        } catch (e: Exception) {
            // цену узнать не вышло — продлить всё равно можно, суммы в списке не будет
        }
        ui { note.visibility = View.GONE; pickRenewMethod(subId, methods, q, note, days) }
    }
}

/** Выбор способа оплаты для продления (с итоговой суммой, если сайт её отдал). */
private fun MainActivity.pickRenewMethod(subId: String, methods: List<Pair<String, String>>, q: JSONObject?, note: TextView, days: Int?) {
    val totals = q?.optJSONObject("provider_totals")
    val items = methods.map { (id, name) ->
        val minor = totals?.optLong(id, -1L) ?: -1L
        if (minor >= 0) "$name — ${vpnRub(minor)}" else name
    }.toTypedArray()
    val end = q?.optString("new_end_date").orEmpty()
    val title = if (end.length >= 10) "Продлить до ${vpnDate(end)}" else "Продлить подписку"
    androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle(title)
        .setItems(items) { _, i ->
            note.visibility = View.VISIBLE
            payVpnWith(note) { VpnApi.renew(subId, methods[i].first, days) }
        }
        .show()
}

// ---------- трафик и баланс ----------

/** Байты → «7,1 ГБ». */
private fun vpnGb(bytes: Long): String =
    String.format(java.util.Locale("ru"), "%.1f ГБ", bytes / 1073741824.0)

private fun MainActivity.pickMethod(title: String, methods: List<Pair<String, String>>, onPick: (String) -> Unit) {
    androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle(title)
        .setItems(methods.map { it.second }.toTypedArray()) { _, i -> onPick(methods[i].first) }
        .show()
}

private fun MainActivity.pickTrafficPack(subId: String, methods: List<Pair<String, String>>, packs: JSONArray, note: TextView) {
    if (packs.length() == 0) { toast("Пакетов трафика сейчас нет"); return }
    val gbs = ArrayList<Int>()
    val items = ArrayList<String>()
    for (i in 0 until packs.length()) {
        val p = packs.optJSONObject(i) ?: continue
        gbs.add(p.optInt("gb"))
        items.add("${p.optInt("gb")} ГБ — ${vpnRub(p.optLong("total_display"))}")
    }
    androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle("Докупить трафик")
        .setItems(items.toTypedArray()) { _, i ->
            pickMethod("Способ оплаты", methods) { prov ->
                note.visibility = View.VISIBLE
                payVpnWith(note) { VpnApi.trafficIntent(subId, gbs[i], prov) }
            }
        }
        .show()
}

private fun MainActivity.askTopup(methods: List<Pair<String, String>>, note: TextView, prefillMinor: Long = 0L) {
    val input = android.widget.EditText(this).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        hint = "сумма, ₽"
        if (prefillMinor > 0) setText(((prefillMinor + 99) / 100).toString())
        setPadding(dp(20), dp(14), dp(20), dp(14))
    }
    androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle("Пополнить баланс")
        .setView(input)
        .setNegativeButton("Отмена", null)
        .setPositiveButton("Дальше") { _, _ ->
            val rub = input.text.toString().trim().toLongOrNull()
            if (rub == null || rub < 1) {
                toast("Введите сумму в рублях")
            } else {
                pickMethod("Способ оплаты", methods) { prov ->
                    note.visibility = View.VISIBLE
                    payVpnWith(note) { VpnApi.topup(rub * 100, prov) }
                }
            }
        }
        .show()
}

// ---------- смена тарифа («Больше устройств») ----------

/** Ищет тарифы с тем же сроком и бОльшим числом устройств, считает доплату по каждому и даёт выбрать. */
private fun MainActivity.startUpgrade(s: JSONObject, subId: String, methods: List<Pair<String, String>>, note: TextView) {
    note.visibility = View.VISIBLE
    note.text = "Считаем доплату…"
    io.execute {
        try {
            val days = s.optInt("days")
            val devices = s.optInt("devices")
            val curPlan = s.optString("plan_uuid")
            val plans = VpnApi.plans()
            val cands = ArrayList<JSONObject>()
            for (i in 0 until plans.length()) {
                val p = plans.optJSONObject(i) ?: continue
                if (p.optBoolean("is_lifetime")) continue
                if (p.optInt("days") == days && p.optInt("devices") > devices && p.optString("uuid") != curPlan) cands.add(p)
            }
            cands.sortBy { it.optInt("devices") }
            val ok = ArrayList<Pair<JSONObject, JSONObject>>()
            var lastErr: Exception? = null
            for (p in cands) {
                try {
                    ok.add(Pair(p, VpnApi.upgradeQuote(subId, p.optString("uuid"))))
                } catch (e: VpnApi.AuthException) {
                    throw e
                } catch (e: Exception) {
                    lastErr = e
                }
            }
            val balance = try { VpnApi.profile().optLong("balance_display_minor", -1L) } catch (e: VpnApi.AuthException) { throw e } catch (e: Exception) { -1L }
            ui {
                note.visibility = View.GONE
                when {
                    cands.isEmpty() -> toast("Тарифов с большим числом устройств нет")
                    ok.isEmpty() -> { note.visibility = View.VISIBLE; note.text = "Не удалось посчитать доплату: ${lastErr?.message}" }
                    else -> pickUpgrade(subId, ok, balance, methods, note)
                }
            }
        } catch (e: VpnApi.AuthException) {
            ui { showVpn() }
        } catch (e: Exception) {
            ui { note.text = "Не удалось загрузить тарифы: ${e.message}" }
        }
    }
}

private fun MainActivity.pickUpgrade(subId: String, opts: List<Pair<JSONObject, JSONObject>>, balance: Long, methods: List<Pair<String, String>>, note: TextView) {
    val items = opts.map { (p, q) ->
        val extra = q.optLong("charged_display_minor")
        "${vpnPlanName(p.optString("name"))} · ${vpnDevices(p.optInt("devices"))} — доплата ${vpnRub(extra)}"
    }.toTypedArray()
    androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle("Больше устройств")
        .setItems(items) { _, i ->
            val (p, q) = opts[i]
            val charge = q.optLong("charged_display_minor")
            val enough = q.optBoolean("enough_balance", true)
            val name = "${vpnPlanName(p.optString("name"))} · ${vpnDevices(p.optInt("devices"))}"
            if (!enough) {
                val have = if (balance >= 0) "На балансе ${vpnRub(balance)}." else ""
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Не хватает баланса")
                    .setMessage("Нужно ${vpnRub(charge)}. $have Пополните баланс и повторите.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Пополнить") { _, _ ->
                        val need = if (balance >= 0) charge - balance else charge
                        askTopup(methods, note, if (need > 0) need else charge)
                    }.show()
            } else {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Перейти на «$name»?")
                    .setMessage("С баланса спишется ${vpnRub(charge)}. Срок подписки не изменится.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Перейти") { _, _ ->
                        if (busy) return@setPositiveButton
                        busy = true
                        io.execute {
                            try {
                                VpnApi.upgrade(subId, p.optString("uuid"))
                                ui { busy = false; toast("Тариф изменён"); showVpn() }
                            } catch (e: VpnApi.AuthException) {
                                ui { busy = false; showVpn() }
                            } catch (e: Exception) {
                                ui { busy = false; toast("Ошибка: ${e.message}") }
                            }
                        }
                    }.show()
            }
        }
        .show()
}

// ---------- устройства ----------

/** «2026-11-22T14:08:52…» → «22.11 14:08». */
private fun vpnDateTime(s: String): String {
    if (s.length < 16) return s
    val d = s.take(10).split("-")
    return if (d.size == 3) "${d[2]}.${d[1]} ${s.substring(11, 16)}" else s
}

private fun MainActivity.vpnDevicesBlock(card: LinearLayout, subId: String) {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        layoutParams = lp(0)
    }
    var loaded = false

    fun load() {
        box.removeAllViews()
        box.addView(txt("Загружаем устройства…", 13f, MUTE))
        io.execute {
            try {
                val devs = VpnApi.devices(subId)
                val blocks = try { VpnApi.deviceBlocks(subId) } catch (e: VpnApi.AuthException) { throw e } catch (e: Exception) { JSONArray() }
                ui { paintDevices(box, subId, devs, blocks) { load() } }
            } catch (e: VpnApi.AuthException) {
                ui { showVpn() }
            } catch (e: Exception) {
                ui { box.removeAllViews(); box.addView(txt("Не удалось загрузить: ${e.message}", 13f, MUTE)) }
            }
        }
    }
    card.addView(chip("Устройства", false) {
        if (box.visibility == View.VISIBLE) { box.visibility = View.GONE; return@chip }
        box.visibility = View.VISIBLE
        if (!loaded) { loaded = true; load() }
    }.apply { (layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = dp(8) })
    card.addView(box)
}

private fun MainActivity.paintDevices(box: LinearLayout, subId: String, devs: JSONArray, blocks: JSONArray, reload: () -> Unit) {
    box.removeAllViews()
    box.addView(txt("Устройства: ${devs.length()}", 14f, TEXT, true).apply { layoutParams = lp(6) })
    if (devs.length() == 0) box.addView(txt("Пока ни одно устройство не подключено", 13f, MUTE))
    for (i in 0 until devs.length()) {
        val d = devs.optJSONObject(i) ?: continue
        val name = d.optString("name").ifEmpty { d.optString("model") }.ifEmpty { "Устройство" }
        val sub = listOf(d.optString("os"), d.optString("ip"), "был ${vpnDateTime(d.optString("last_seen"))}")
            .filter { it.isNotEmpty() && it != "null" }.joinToString(" · ")
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, V_WRAP, 1f)
            addView(txt(name, 14f, TEXT, true))
            addView(txt(sub, 12f, MUTE))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(6)
            addView(left)
            addView(chip("Отключить", false) {
                androidx.appcompat.app.AlertDialog.Builder(this@paintDevices)
                    .setTitle("Отключить «$name»?")
                    .setMessage("Устройство потеряет доступ. Вернуть его можно в списке отключённых.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Отключить") { _, _ ->
                        io.execute {
                            try {
                                VpnApi.disconnectDevice(subId, d.optLong("id"))
                                ui { toast("Устройство отключено"); reload() }
                            } catch (e: VpnApi.AuthException) {
                                ui { showVpn() }
                            } catch (e: Exception) {
                                ui { toast("Ошибка: ${e.message}") }
                            }
                        }
                    }.show()
            })
        }
        box.addView(row)
    }
    if (blocks.length() > 0) {
        box.addView(txt("Отключённые", 13f, MUTE).apply { layoutParams = lp(6).apply { topMargin = dp(8) } })
        for (i in 0 until blocks.length()) {
            val b = blocks.optJSONObject(i) ?: continue
            val hwid = b.optString("hwid")
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = lp(6)
                addView(LinearLayout(this@paintDevices).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, V_WRAP, 1f)
                    addView(txt(b.optString("name").ifEmpty { "Устройство" }, 14f, TEXT))
                    addView(txt("отключено ${vpnDateTime(b.optString("blocked_at"))}", 12f, MUTE))
                })
                addView(chip("Вернуть", false) {
                    io.execute {
                        try {
                            VpnApi.unblockDevice(subId, hwid)
                            ui { toast("Устройство возвращено"); reload() }
                        } catch (e: VpnApi.AuthException) {
                            ui { showVpn() }
                        } catch (e: Exception) {
                            ui { toast("Ошибка: ${e.message}") }
                        }
                    }
                })
            }
            box.addView(row)
        }
    }
}

/** Добавляет подписку в установленный VPN-клиент по его ссылке-схеме. Если клиента нет — просим скопировать ссылку. */
private fun MainActivity.openInVpnClient(url: String) {
    val apps = listOf(
        Pair("Happ", "happ://add/$url"),
        Pair("v2rayTun", "v2raytun://import/$url"),
        Pair("Hiddify", "hiddify://import/$url")
    )
    androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle("Открыть в приложении")
        .setItems(apps.map { it.first }.toTypedArray()) { _, i ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apps[i].second)))
            } catch (e: Exception) {
                toast("${apps[i].first} не установлен. Скопируйте ссылку и добавьте подписку вручную.")
            }
        }
        .show()
}

// ---------- оплата ----------

private fun MainActivity.payVpn(planUuid: String, provider: String, info: TextView) =
    payVpnWith(info) { VpnApi.intent(planUuid, provider) }

/** Создаёт платёж (покупка или продление) и открывает страницу оплаты. */
private fun MainActivity.payVpnWith(info: TextView, create: () -> JSONObject) {
    if (busy) return
    busy = true
    info.text = "Создаём платёж…"
    io.execute {
        try {
            val r = create()
            val id = r.optString("payment_id")
            val url = r.optString("invoice_url")
            if (id.isNotEmpty()) Store.vpnPay = id
            ui {
                busy = false
                if (!url.startsWith("https://")) {
                    info.text = "Сайт не вернул ссылку на оплату"
                    return@ui
                }
                info.text = "Платёж создан. Переходим к оплате…"
                try {
                    vpnReturn = true
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    vpnReturn = false
                    info.text = "Откройте ссылку вручную: $url"
                }
            }
        } catch (e: VpnApi.AuthException) {
            ui { busy = false; showVpn() }
        } catch (e: Exception) {
            ui { busy = false; info.text = "Ошибка: ${e.message}" }
        }
    }
}

/** Раз в 4 секунды спрашивает статус платежа (до 4 минут). Оплачен и выдан — открываем экран заново: ключ уже в «Мои подписки». */
private fun MainActivity.pollVpnPayment(sid: Int, id: String, tries: Int, gen: Int, info: TextView) {
    if (sid != screenId || gen != vpnPollGen) return
    io.execute {
        var res = "wait"
        try {
            val j = VpnApi.paymentStatus(id)
            val st = j.optString("status")
            res = when {
                j.optString("fulfillment") == "done" || statusKind(st) == 2 -> "done"
                statusKind(st) == 3 -> "failed"
                else -> "wait"
            }
        } catch (e: VpnApi.AuthException) {
            res = "auth"
        } catch (e: Exception) {
            res = "wait"
        }
        ui {
            if (sid != screenId || gen != vpnPollGen) return@ui
            when (res) {
                "done" -> { Store.vpnPay = ""; toast("Оплата прошла — подписка готова"); showVpn() }
                "failed" -> { Store.vpnPay = ""; toast("Платёж отменён или истёк"); showVpn() }
                "auth" -> showVpn()
                else -> if (tries >= 60) {
                    info.text = "Оплата пока не подтвердилась. Нажмите «Проверить сейчас» после оплаты."
                } else {
                    info.text = "⏳ Ждём подтверждение оплаты…"
                    main.postDelayed({ pollVpnPayment(sid, id, tries + 1, gen, info) }, 4000L)
                }
            }
        }
    }
}
