package ru.pharmshop.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// Профиль и заказы.

// ---------- профиль и заказы ----------
internal fun MainActivity.statusLabel(s: String): String = when (statusKind(s)) {
    0 -> "Ожидает оплаты"
    1 -> "Оплачен · выдаём"
    2 -> "Выполнен"
    3 -> "Отменён"
    else -> if (s.isEmpty()) "—" else s
}

internal fun MainActivity.statusColor(s: String): Int = when (statusKind(s)) {
    0 -> AMBER
    1 -> BLUE
    2 -> GREEN
    3 -> RED
    else -> MUTE
}

internal fun MainActivity.fmtDate(iso: String): String = try {
    val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    f.timeZone = TimeZone.getTimeZone("UTC")
    val d = f.parse(iso.take(19))
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(d!!)
} catch (e: Exception) {
    iso.take(16).replace('T', ' ')
}

internal fun MainActivity.money(total: String, cur: String): String =
    "$total ${if (cur.isEmpty() || cur == "RUB") "₽" else cur}"

internal fun MainActivity.showProfile() {
    onHome = false
    val col = screen()
    val sid = screenId
    backRow(col, "Профиль")
    // кнопка настроек — справа сверху (вставляем в только что созданную строку-шапку)
    (col.getChildAt(col.childCount - 1) as LinearLayout).apply {
        addView(android.view.View(this@showProfile).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        addView(LinearLayout(this@showProfile).apply {
            gravity = Gravity.CENTER
            background = ripple(CARD, 22, LINE)
            isClickable = true
            contentDescription = "Настройки"
            setOnClickListener { showSettings() }
            pressable(this)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            addView(IconView(this@showProfile, Ic.GEAR, TEXT).apply {
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            })
        })
    }
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(body)
    renderProfile(body, sid, true)
    refreshOrders(body, sid, false)
    startAutoRefresh(body, sid)
}

/** Пока открыт профиль и есть незавершённые заказы — обновляем статусы каждые 5 секунд. */
internal fun MainActivity.startAutoRefresh(body: LinearLayout, sid: Int) {
    val r = object : Runnable {
        override fun run() {
            if (sid != screenId) return
            val active = Store.orders().any { statusKind(effStatus(it)) !in listOf(2, 3) }
            if (active) refreshOrders(body, sid, false)
            main.postDelayed(this, 5000L)
        }
    }
    main.postDelayed(r, 5000L)
}

internal fun MainActivity.statTile(value: String, label: String): LinearLayout {
    val t = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = shape(CARD, 16, LINE)
        setPadding(dp(8), dp(14), dp(8), dp(14))
    }
    t.addView(txt(value, 18f, TEXT, true).apply { gravity = Gravity.CENTER })
    t.addView(txt(label, 11f, MUTE).apply { gravity = Gravity.CENTER })
    return t
}

internal fun MainActivity.renderProfile(body: LinearLayout, sid: Int, animate: Boolean = false) {
    body.removeAllViews()
    Store.markOrdersSeen()   // раз заказы на экране — уведомлять о них уже не нужно
    val orders = Store.orders()
    val user = Store.lastUser

    // карточка пользователя
    val first = user.firstOrNull()
    val avatarDrawable = GradientDrawable()
    avatarDrawable.setShape(GradientDrawable.OVAL)
    avatarDrawable.setColor(BLUE)
    val avatar = TextView(this).apply {
        text = if (first != null) first.uppercaseChar().toString() else "?"
        textSize = 22f
        setTextColor(Color.BLACK)
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        background = avatarDrawable
        layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(14) }
    }
    val who = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    who.addView(txt(if (user.isEmpty()) "Telegram не указан" else "@$user", 18f, TEXT, true))
    who.addView(txt(if (user.isEmpty()) "Укажите ник — он подставится в заказы" else "Ник указан вручную, ботом не подтверждён", 12f, MUTE))
    val userCard = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = shape(CARD, 18, LINE)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = lp(12)
    }
    userCard.addView(avatar)
    userCard.addView(who)
    body.addView(userCard)

    val tgActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    tgActions.addView(chip(if (user.isEmpty()) "Указать Telegram" else "Изменить ник", true) { editUsername(body, sid) })
    tgActions.addView(chip("Открыть @pharmaishop_bot", false) { openUrl("$BOT_URL?start=app") })
    body.addView(HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        layoutParams = lp(16)
        addView(tgActions)
    })

    // статистика
    val paid = orders.filter { val k = statusKind(effStatus(it)); k == 1 || k == 2 }
    val spent = paid.sumOf { it.optString("total").toDoubleOrNull() ?: 0.0 }
    val stars = paid.filter { it.optString("product") != "premium" }.sumOf { it.optInt("quantity") }
    val t1 = statTile(orders.size.toString(), "Заказов")
    val t2 = statTile(String.format(Locale.US, "%.2f ₽", spent), "Оплачено")
    val t3 = statTile(stars.toString(), "Звёзд куплено")
    t1.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(5) }
    t2.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(5); rightMargin = dp(5) }
    t3.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(5) }
    val stats = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = lp(18)
        addView(t1)
        addView(t2)
        addView(t3)
    }
    body.addView(stats)

    // заголовок списка
    val listTitle = txt("Мои заказы", 18f, TEXT, true).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    val refreshChip = chip("Обновить", false) { refreshOrders(body, sid, true) }
    val listHead = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = lp(10)
        addView(listTitle)
        addView(refreshChip)
    }
    body.addView(listHead)
    if (lastRefreshError.isNotEmpty()) {
        body.addView(tv("Не удалось обновить статус: $lastRefreshError", 12f, RED))
    }

    if (orders.isEmpty()) {
        body.addView(tv("Пока нет заказов. Здесь появятся заказы, оформленные в этом приложении.", 14f, MUTE))
    } else {
        for (o in orders) body.addView(orderCard(o, body, sid))
    }

    body.addView(tv("История хранится только на этом устройстве: заказы с сайта и из бота здесь не видны.", 12f, MUTE))
    if (orders.isNotEmpty()) {
        body.addView(btn("Очистить историю") {
            AlertDialog.Builder(this)
                .setTitle("Очистить историю?")
                .setMessage("Заказы на сервере не удаляются, пропадёт только список в приложении.")
                .setPositiveButton("Очистить") { _, _ ->
                    Store.clear()
                    renderProfile(body, sid)
                }
                .setNegativeButton("Отмена", null)
                .show()
        })
    }
    if (animate) riseIn(body)
}

internal fun MainActivity.editUsername(body: LinearLayout, sid: Int) {
    val et = EditText(this)
    et.setText(Store.lastUser)
    et.hint = "Ник без @"
    et.setSingleLine()
    AlertDialog.Builder(this)
        .setTitle("Ваш Telegram")
        .setView(et)
        .setPositiveButton("Сохранить") { _, _ ->
            Store.lastUser = et.text.toString().trim().removePrefix("@")
            if (sid == screenId) renderProfile(body, sid)
        }
        .setNegativeButton("Отмена", null)
        .show()
}

/** Показывает всё, что сайт вернул по заказу (для диагностики статуса). */
internal fun MainActivity.showRaw(o: JSONObject) {
    val t = TextView(this).apply {
        text = o.toString(2)
        textSize = 12f
        setTextIsSelectable(true)
        setPadding(dp(20), dp(12), dp(20), dp(12))
    }
    AlertDialog.Builder(this)
        .setTitle("Данные заказа")
        .setView(ScrollView(this).apply { addView(t) })
        .setPositiveButton("Закрыть", null)
        .show()
}

internal fun MainActivity.orderCard(o: JSONObject, body: LinearLayout, sid: Int): LinearLayout {
    val status = effStatus(o)
    val num = o.optString("order_number")
    val color = statusColor(status)

    val badge = txt(statusLabel(status), 12f, color, true).apply {
        background = shape((color and 0x00FFFFFF) or 0x33000000, 10)
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }
    if (statusKind(status) == 0 || statusKind(status) == 1) pulse(badge)
    val numView = txt("Заказ $num", 16f, TEXT, true).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    // стиль шагов заказа (Настройки → Заказы): значок со статусом или полоса «Создан → Оплачен → Выполнен»
    val kind = statusKind(status)
    val asBar = Store.stepsStyle == 1 && kind in 0..3
    val top = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = lp(8)
        addView(numView)
        if (!asBar) addView(badge)
    }

    val savedNames = try { JSONObject(Store.methodNames) } catch (e: Exception) { JSONObject() }
    val method = savedNames.optString(o.optString("method_id"))
        .ifEmpty { o.optString("method_name") }
        .ifEmpty { o.optString("method_id") }
    val isPremium = o.optString("product") == "premium"
    val line1 = tv(
        if (isPremium) "Premium · ${o.optInt("months", o.optInt("quantity"))} мес.  →  @${o.optString("username")}"
        else "${o.optInt("quantity")} ★  →  @${o.optString("username")}",
        15f, TEXT
    )
    val line2 = tv(
        listOf(money(o.optString("total"), o.optString("currency")), method, fmtDate(o.optString("created_at")))
            .filter { it.isNotBlank() }.joinToString("  •  "),
        12f, MUTE
    )

    val actions = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = lp(0)
    }
    val url = o.optString("payment_url")
    if (statusKind(status) == 0 && url.startsWith("https://")) {
        actions.addView(chip("Оплатить", true) { returnFromPay = true; openUrl(url) })
    }
    if (statusKind(status) == 2 || statusKind(status) == 3) {
        actions.addView(chip("Повторить", true) {
            Store.lastUser = o.optString("username")
            if (isPremium) showPremium() else { Store.repeatQty = o.optInt("quantity"); showStars() }
        })
    }
    actions.addView(chip("Обновить статус", false) { refreshOrders(body, sid, true) })
    actions.addView(chip("Данные", false) { showRaw(o) })
    actions.addView(chip("Скопировать номер", false) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("order", num))
        toast("Номер скопирован")
    })

    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(CARD, 18, LINE)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = lp(10)
    }
    card.addView(top)
    card.addView(line1)
    card.addView(line2)
    if (asBar) card.addView(stepsView(kind).apply { layoutParams = lp(4).apply { topMargin = dp(6) } })
    card.addView(HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(actions)
    })
    return card
}

internal fun MainActivity.ordersSnapshot(): String =
    Store.orders().joinToString("|") { it.optString("id") + ":" + effStatus(it) }

/** Подтягивает свежие статусы заказов с сервера. force — обновить и завершённые. */
internal fun MainActivity.refreshOrders(body: LinearLayout, sid: Int, force: Boolean) {
    if (refreshing) {
        if (force) toast("Уже обновляем…")
        return
    }
    val targets = Store.orders()
        .filter { o ->
            val k = statusKind(effStatus(o))
            force || (k != 2 && k != 3)
        }
        .filter { it.optString("id").isNotEmpty() }
        .take(10)
    if (targets.isEmpty()) {
        if (force) toast("Нет заказов для обновления")
        return
    }
    refreshing = true
    io.execute {
        val before = ordersSnapshot()
        // запрашиваем все заказы одновременно, а не по очереди
        val jobs = targets.map { o ->
            val id = o.optString("id")
            net.submit<String> {
                try { Store.update(id, Api.order(id, o.optString("access_token"))); "" } catch (e: Exception) { e.message ?: "ошибка" }
            }
        }
        val results = jobs.map { try { it.get() } catch (e: Exception) { e.message ?: "ошибка" } }
        val ok = results.count { it.isEmpty() }
        lastRefreshError = results.firstOrNull { it.isNotEmpty() } ?: ""
        OrderSync.syncAll()
        val changed = ordersSnapshot() != before
        ui {
            refreshing = false
            if (sid == screenId) {
                if (changed || force) renderProfile(body, sid)
                if (force) toast(if (ok > 0) "Статусы обновлены" else "Не удалось обновить")
            }
        }
    }
}
