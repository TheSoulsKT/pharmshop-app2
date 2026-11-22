package ru.pharmshop.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import org.json.JSONObject

// Покупка звёзд.

// ---------- покупка звёзд ----------
internal fun MainActivity.showStars() {
    onHome = false
    val col = screen()
    val sid = screenId
    backRow(col, "Купить звёзды")
    val status = tv("Загружаем данные…", 14f, MUTE)
    col.addView(status)
    riseIn(col)
    io.execute {
        try {
            val c = cfg ?: Api.config().also { cfg = it }
            ui { if (sid == screenId) { status.visibility = View.GONE; buildForm(col, c) } }
        } catch (e: Exception) {
            ui { if (sid == screenId) status.text = "Не удалось загрузить данные: ${e.message}" }
        }
    }
}

internal fun MainActivity.buildForm(col: LinearLayout, c: JSONObject) {
    val firstNew = col.childCount
    val min = c.optInt("min", 50)
    val max = c.optInt("max", 10000)
    c.optString("hcaptcha_sitekey").takeIf { it.isNotEmpty() }?.let { captcha(it) }
    col.addView(banner(Ic.STAR, "Telegram Stars", "Доставка в течение 15 секунд", GOLD_A, GOLD_B, DARK_ON))
    col.addView(tv("${c.optString("stars_rate")} ₽ за звезду · от $min до $max шт.", 14f, MUTE))

    val user = input("Имя пользователя в Telegram, без @", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
    user.setText(Store.lastUser)
    val qty = input("Сколько звёзд", InputType.TYPE_CLASS_NUMBER)
    user.background = shape(CARD, 14, edge(GOLD))
    qty.background = shape(CARD, 14, edge(ORANGE))
    col.addView(user)
    col.addView(qty)

    val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val presets = c.optJSONArray("presets")
    if (presets != null) {
        for (i in 0 until presets.length()) {
            val v = presets.getInt(i)
            chips.addView(chipTint(v.toString(), listOf(GOLD, ORANGE, PINK, VIOLET)[i % 4]) { qty.setText(v.toString()) })
        }
    }
    col.addView(HorizontalScrollView(this).apply {
        addView(chips)
        isHorizontalScrollBarEnabled = false
        layoutParams = lp(16)
    })

    col.addView(tv("Способ оплаты", 15f, GOLD, true))
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = lp(10)
    }
    col.addView(box)

    var method = c.optString("default_method")
    var methodName = ""
    val rows = ArrayList<MethodRow>()
    val total = tv("К оплате: —", 18f, GOLD, true)
    var token = 0

    fun paint() {
        for (r in rows) {
            val sel = r.id == method
            r.row.background = shape(if (sel) mix(r.tint, 0.2f) else CARD, 14, if (sel) r.tint else LINE)
            val dot = GradientDrawable()
            dot.setShape(GradientDrawable.OVAL)
            dot.setColor(if (sel) r.tint else Color.TRANSPARENT)
            dot.setStroke(dp(2), if (sel) r.tint else MUTE)
            r.dot.background = dot
            dotState(r.dot, sel)
        }
    }

    fun requestQuote() {
        val q = qty.text.toString().toIntOrNull() ?: 0
        token++
        val my = token
        if (q < min || q > max) { popText(total, "К оплате: —"); return }
        main.postDelayed({
            if (my != token) return@postDelayed
            io.execute {
                try {
                    val r = Api.quote(q, method)
                    ui { if (my == token) popText(total, "К оплате: ${r.optString("total")} ${r.optString("currency", "RUB")}") }
                } catch (e: Exception) {
                    ui { if (my == token) popText(total, "Не удалось рассчитать цену") }
                }
            }
        }, 400L)
    }

    val methods = c.optJSONArray("methods")
    if (methods != null) {
        for ((mid, mname) in shownMethods(c)) {
            val dotView = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(14) }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = lp(8)
                isClickable = true
                setOnClickListener {
                    method = mid
                    methodName = mname
                    paint()
                    requestQuote()
                }
                pressable(this)
            }
            row.addView(dotView)
            row.addView(txt(mname, 16f, TEXT))
            box.addView(row)
            rows.add(MethodRow(mid, mname, row, dotView, listOf(GOLD, ORANGE, PINK, VIOLET)[rows.size % 4]))
        }
    }
    // по умолчанию выбран первый способ — «СБП 1»
    if (rows.isNotEmpty()) method = rows[0].id
    methodName = rows.firstOrNull { it.id == method }?.name ?: ""
    paint()

    qty.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) { requestQuote() }
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
    })
    if (Store.repeatQty > 0) { qty.setText(Store.repeatQty.toString()); Store.repeatQty = 0 }   // «Повторить заказ»

    val info = tv("", 14f, MUTE)
    val payBtn = gradBtn("Оплатить", GOLD_A, GOLD_B, DARK_ON) {
        pay(user.text.toString(), qty.text.toString().toIntOrNull() ?: 0, method, methodName, c, info)
    }
    col.addView(total)
    col.addView(payBtn)
    col.addView(info)
    col.addView(btn("Мои заказы") { showProfile() })
    riseIn(col, firstNew)
}

/**
 * Способы оплаты для формы: первый и второй меняются местами, подписи («СБП #1», «СБП #2»)
 * остаются по позициям, способы через сеть TON скрыты. Возвращает (id, подпись).
 */
internal fun MainActivity.shownMethods(c: JSONObject): List<Pair<String, String>> {
    val methods = c.optJSONArray("methods") ?: return emptyList()
    val all = (0 until methods.length()).toMutableList()
    if (all.size >= 2) { val t = all[0]; all[0] = all[1]; all[1] = t }
    val tonRe = Regex("(^|[^a-z])ton([^a-z]|$)|тон", RegexOption.IGNORE_CASE)
    val out = ArrayList<Pair<String, String>>()
    for (pos in all.indices) {
        val m = methods.getJSONObject(all[pos])
        if (tonRe.containsMatchIn(m.optString("name")) || tonRe.containsMatchIn(m.optString("id"))) continue
        out.add(Pair(m.getString("id"), methods.getJSONObject(pos).getString("name")))
    }
    // запоминаем подписи, чтобы заказы в профиле подписывались так же
    val names = JSONObject()
    for ((id, nm) in out) names.put(id, nm)
    Store.methodNames = names.toString()
    return out
}

/** Список с радио-точками: один выбранный пункт. */
internal fun MainActivity.selectList(
    box: LinearLayout, items: List<Pair<String, String>>, initial: String,
    palette: List<Int> = listOf(BLUE),
    onSelect: (String, String) -> Unit
) {
    val rows = ArrayList<MethodRow>()
    var cur = initial
    fun paint() {
        for (r in rows) {
            val sel = r.id == cur
            r.row.background = shape(if (sel) mix(r.tint, 0.2f) else CARD, 14, if (sel) r.tint else LINE)
            val dot = GradientDrawable()
            dot.setShape(GradientDrawable.OVAL)
            dot.setColor(if (sel) r.tint else Color.TRANSPARENT)
            dot.setStroke(dp(2), if (sel) r.tint else MUTE)
            r.dot.background = dot
            dotState(r.dot, sel)
        }
    }
    for ((id, name) in items) {
        val dotView = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(14) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = lp(8)
            isClickable = true
            setOnClickListener { cur = id; paint(); onSelect(id, name) }
            pressable(this)
        }
        row.addView(dotView)
        row.addView(txt(name, 16f, TEXT))
        box.addView(row)
        rows.add(MethodRow(id, name, row, dotView, palette[rows.size % palette.size]))
    }
    paint()
}
