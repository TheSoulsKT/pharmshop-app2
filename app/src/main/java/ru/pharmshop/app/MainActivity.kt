package ru.pharmshop.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.hcaptcha.sdk.HCaptcha
import com.hcaptcha.sdk.HCaptchaConfig
import com.hcaptcha.sdk.HCaptchaException
import com.hcaptcha.sdk.HCaptchaTokenResponse
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

private class MethodRow(val id: String, val name: String, val row: LinearLayout, val dot: View)

class MainActivity : AppCompatActivity() {
    private val BG = Color.parseColor("#121214")
    private val CARD = Color.parseColor("#1D1C21")
    private val LINE = Color.parseColor("#2B2A31")
    private val TEXT = Color.parseColor("#F2F2F4")
    private val MUTE = Color.parseColor("#9A9DA3")
    private val BLUE = Color.parseColor("#3B8EF3")
    private val GREEN = Color.parseColor("#34C759")
    private val AMBER = Color.parseColor("#E0A030")
    private val RED = Color.parseColor("#FF5A52")

    private val BOT_URL = "https://t.me/pharmaishop_bot"
    /** Единая высота всех карточек главного меню */
    private val CARD_H get() = dp(96)

    private val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private var cfg: JSONObject? = null
    private var busy = false
    private var onHome = true
    private var screenId = 0
    private var polling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (onHome) finish() else showHome()
            }
        })
        showHome()
    }

    // ---------- helpers ----------
    private fun ui(r: () -> Unit) { main.post { r() } }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun shape(fill: Int, radius: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun ripple(fill: Int, radius: Int, stroke: Int = 0) = RippleDrawable(
        ColorStateList.valueOf(0x33FFFFFF),
        shape(fill, radius, stroke),
        shape(Color.WHITE, radius)
    )

    private fun lp(bottom: Int = 10) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, 0, 0, dp(bottom)) }

    private fun txt(t: String, size: Float, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun tv(t: String, size: Float, color: Int = TEXT, bold: Boolean = false): TextView =
        txt(t, size, color, bold).apply { layoutParams = lp(8) }

    private fun btn(t: String, primary: Boolean = false, onClick: () -> Unit): Button =
        Button(this).apply {
            text = t
            setAllCaps(false)
            textSize = 16f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(if (primary) Color.BLACK else TEXT)
            background = ripple(if (primary) BLUE else CARD, 18, if (primary) BLUE else LINE)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = lp()
            setOnClickListener { onClick() }
        }

    private fun chip(t: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = t
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.BLACK else TEXT)
            background = ripple(if (primary) BLUE else Color.TRANSPARENT, 12, if (primary) BLUE else LINE)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8) }
        }

    private fun input(hintText: String, type: Int): EditText = EditText(this).apply {
        hint = hintText
        inputType = type
        setTextColor(TEXT)
        setHintTextColor(MUTE)
        textSize = 16f
        background = shape(CARD, 14, LINE)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        layoutParams = lp()
    }

    private fun screen(): LinearLayout {
        screenId++
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(24))
        }
        val sv = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(col)
        }
        setContentView(sv)
        return col
    }

    private fun backRow(col: LinearLayout, title: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(16)
        }
        val icon = IconView(this, Ic.BACK, TEXT).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        }
        val b = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = ripple(CARD, 22, LINE)
            isClickable = true
            setOnClickListener { showHome() }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(14) }
            addView(icon)
        }
        row.addView(b)
        row.addView(txt(title, 24f, TEXT, true))
        col.addView(row)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("Не удалось открыть ссылку")
        }
    }

    // ---------- карточки главного меню ----------
    private fun menuWide(icon: Ic, title: String, sub: String, onClick: () -> Unit): Pair<LinearLayout, TextView> {
        val subView = txt(sub, 13f, MUTE)
        val ic = IconView(this, icon, BLUE).apply {
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(14) }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(txt(title, 17f, TEXT, true))
        texts.addView(subView)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(CARD, 18, LINE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = lp(10).apply { height = CARD_H }
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(ic)
        row.addView(texts)
        return Pair(row, subView)
    }

    private fun tile(icon: Ic, title: String, sub: String, onClick: () -> Unit): LinearLayout {
        val ic = IconView(this, icon, BLUE).apply {
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        }
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ripple(CARD, 18, LINE)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            setOnClickListener { onClick() }
        }
        t.addView(ic)
        t.addView(spacer)
        t.addView(txt(title, 16f, TEXT, true))
        if (sub.isNotEmpty()) t.addView(txt(sub, 12f, MUTE))
        return t
    }

    private fun gridRow(a: View, b: View): LinearLayout {
        a.layoutParams = LinearLayout.LayoutParams(0, CARD_H, 1f)
            .apply { rightMargin = dp(5) }
        b.layoutParams = LinearLayout.LayoutParams(0, CARD_H, 1f)
            .apply { leftMargin = dp(5) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(10)
            addView(a)
            addView(b)
        }
    }

    // ---------- главный экран ----------
    private fun rateText(): String {
        val r = cfg?.optString("stars_rate") ?: ""
        return if (r.isNotEmpty()) "$r ₽/шт" else "Покупка за рубли"
    }

    private fun showHome() {
        onHome = true
        val col = screen()
        val sid = screenId

        // шапка: логотип, название, профиль
        val logo = LogoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(14) }
        }
        val title = TextView(this).apply {
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            val sb = SpannableStringBuilder("Pharm")
            sb.setSpan(ForegroundColorSpan(TEXT), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("Shop")
            sb.setSpan(ForegroundColorSpan(BLUE), 5, 9, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = sb
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titles.addView(title)
        titles.addView(txt("Telegram магазин • Stars • NFT • Подарки", 12f, MUTE))

        val userIcon = IconView(this, Ic.USER, TEXT).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        }
        val profileBtn = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = ripple(CARD, 22, LINE)
            isClickable = true
            setOnClickListener { showProfile() }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            addView(userIcon)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(22)
            addView(logo)
            addView(titles)
            addView(profileBtn)
        }
        col.addView(head)
        col.addView(tv("Выберите, что вам нужно", 15f, MUTE))

        val soon = { toast("Скоро будет доступно") }
        val (starsCard, starsSub) = menuWide(Ic.STAR, "Звёзды", rateText()) { showStars() }
        col.addView(starsCard)
        col.addView(menuWide(Ic.GIFT, "Обычные подарки", "Покупка за рубли") { soon() }.first)
        col.addView(menuWide(Ic.CROWN, "Купить Premium", "Покупка подписки") { soon() }.first)
        col.addView(gridRow(
            tile(Ic.BOX, "Купить NFT", "") { soon() },
            tile(Ic.KEY, "Аренда NFT", "") { soon() }
        ))
        col.addView(gridRow(
            tile(Ic.DB, "Продать звёзды", "Скоро") { soon() },
            tile(Ic.AT, "Скупка NFT", "") { soon() }
        ))

        // кнопка бота
        val botIcon = IconView(this, Ic.SEND, Color.BLACK).apply {
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { bottomMargin = dp(8) }
        }
        val bot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ripple(BLUE, 20, BLUE)
            setPadding(dp(20), dp(18), dp(20), dp(18))
            layoutParams = lp(14)
            isClickable = true
            setOnClickListener { openUrl(BOT_URL) }
        }
        bot.addView(botIcon)
        bot.addView(txt("Заказать в боте", 13f, Color.BLACK))
        bot.addView(txt("@pharmaishop_bot", 20f, Color.BLACK, true))
        col.addView(bot)

        col.addView(txt("Цены на услуги могут меняться в зависимости от курса", 12f, MUTE).apply {
            gravity = Gravity.CENTER
            layoutParams = lp(0)
        })

        // подтягиваем курс для карточки «Звёзды»
        if (cfg == null) {
            io.execute {
                try {
                    val c = Api.config()
                    cfg = c
                    ui { if (sid == screenId) starsSub.text = rateText() }
                } catch (e: Exception) {
                    // оставляем текст по умолчанию
                }
            }
        }
    }

    // ---------- покупка звёзд ----------
    private fun showStars() {
        onHome = false
        val col = screen()
        val sid = screenId
        backRow(col, "Купить звёзды")
        val status = tv("Загружаем данные…", 14f, MUTE)
        col.addView(status)
        io.execute {
            try {
                val c = cfg ?: Api.config().also { cfg = it }
                ui { if (sid == screenId) { status.visibility = View.GONE; buildForm(col, c) } }
            } catch (e: Exception) {
                ui { if (sid == screenId) status.text = "Не удалось загрузить данные: ${e.message}" }
            }
        }
    }

    private fun buildForm(col: LinearLayout, c: JSONObject) {
        val min = c.optInt("min", 50)
        val max = c.optInt("max", 10000)
        col.addView(tv("${c.optString("stars_rate")} ₽ за звезду · от $min до $max шт.", 14f, MUTE))

        val user = input("Имя пользователя в Telegram, без @", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        user.setText(Store.lastUser.ifEmpty { Store.tgUsername })
        val qty = input("Сколько звёзд", InputType.TYPE_CLASS_NUMBER)
        col.addView(user)
        col.addView(qty)

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val presets = c.optJSONArray("presets")
        if (presets != null) {
            for (i in 0 until presets.length()) {
                val v = presets.getInt(i)
                chips.addView(chip(v.toString(), false) { qty.setText(v.toString()) })
            }
        }
        col.addView(HorizontalScrollView(this).apply {
            addView(chips)
            isHorizontalScrollBarEnabled = false
            layoutParams = lp(16)
        })

        col.addView(tv("Способ оплаты", 15f, TEXT, true))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp(10)
        }
        col.addView(box)

        var method = c.optString("default_method")
        var methodName = ""
        val rows = ArrayList<MethodRow>()
        val total = tv("К оплате: —", 18f, TEXT, true)
        var token = 0

        fun paint() {
            for (r in rows) {
                val sel = r.id == method
                r.row.background = shape(if (sel) Color.parseColor("#1A2A42") else CARD, 14, if (sel) BLUE else LINE)
                val dot = GradientDrawable()
                dot.setShape(GradientDrawable.OVAL)
                dot.setColor(if (sel) BLUE else Color.TRANSPARENT)
                dot.setStroke(dp(2), if (sel) BLUE else MUTE)
                r.dot.background = dot
            }
        }

        fun requestQuote() {
            val q = qty.text.toString().toIntOrNull() ?: 0
            token++
            val my = token
            if (q < min || q > max) { total.text = "К оплате: —"; return }
            main.postDelayed({
                if (my != token) return@postDelayed
                io.execute {
                    try {
                        val r = Api.quote(q, method)
                        ui { if (my == token) total.text = "К оплате: ${r.optString("total")} ${r.optString("currency", "RUB")}" }
                    } catch (e: Exception) {
                        ui { if (my == token) total.text = "Не удалось рассчитать цену" }
                    }
                }
            }, 400L)
        }

        val methods = c.optJSONArray("methods")
        if (methods != null) {
            for (i in 0 until methods.length()) {
                val m = methods.getJSONObject(i)
                val mid = m.getString("id")
                val mname = m.getString("name")
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
                }
                row.addView(dotView)
                row.addView(txt(mname, 16f, TEXT))
                box.addView(row)
                rows.add(MethodRow(mid, mname, row, dotView))
            }
        }
        if (rows.isNotEmpty() && rows.none { it.id == method }) method = rows[0].id
        methodName = rows.firstOrNull { it.id == method }?.name ?: ""
        paint()

        qty.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { requestQuote() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        })

        val info = tv("", 14f, MUTE)
        val payBtn = btn("Оплатить", true) {
            pay(user.text.toString(), qty.text.toString().toIntOrNull() ?: 0, method, methodName, c, info)
        }
        payBtn.gravity = Gravity.CENTER
        col.addView(total)
        col.addView(payBtn)
        col.addView(info)
        col.addView(btn("Мои заказы") { showProfile() })
    }

    private fun pay(rawUser: String, q: Int, method: String, methodName: String, c: JSONObject, info: TextView) {
        if (busy) return
        val username = rawUser.trim().removePrefix("@")
        val min = c.optInt("min", 50)
        val max = c.optInt("max", 10000)
        if (username.length < 4) { toast("Введите имя пользователя"); return }
        if (q < min || q > max) { toast("Количество: от $min до $max"); return }
        val siteKey = c.optString("hcaptcha_sitekey")
        if (siteKey.isEmpty()) { toast("Нет ключа проверки"); return }

        busy = true
        info.text = "Пройдите проверку…"
        val h = HCaptcha.getClient(this)
        h.addOnSuccessListener { r: HCaptchaTokenResponse ->
            info.text = "Создаём заказ…"
            io.execute {
                try {
                    val o = Api.createOrder(username, q, method, r.tokenResult)
                    o.put("method_name", methodName)
                    Store.add(o)
                    Store.lastUser = username
                    if (Store.loggedIn && Api.authConfigured) {
                        try { Api.linkOrder(Store.session, o.optString("id")) } catch (e: Exception) { /* не критично */ }
                    }
                    val url = o.optString("payment_url")
                    val num = o.optString("order_number")
                    ui {
                        busy = false
                        info.text = "Заказ $num создан. Переходим к оплате…"
                        openPay(url, info)
                    }
                } catch (e: Exception) {
                    ui { busy = false; info.text = "Ошибка: ${e.message}" }
                }
            }
        }.addOnFailureListener { e: HCaptchaException ->
            busy = false
            info.text = "Проверка не пройдена: ${e.message}"
        }
        // Сервер сайта проверяет домен токена, поэтому подписываемся его доменом, а не hcaptcha.com
        val config = HCaptchaConfig.builder()
            .siteKey(siteKey)
            .host("pharmshop.tgstars.tg")
            .build()
        h.setup(config).verifyWithHCaptcha()
    }

    private fun openPay(url: String, info: TextView) {
        if (!url.startsWith("https://")) {
            info.text = "Сайт не вернул ссылку на оплату"
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            info.text = "Откройте ссылку вручную: $url"
        }
    }

    // ---------- профиль и заказы ----------
    // 0 — ждёт оплаты, 1 — оплачен/в работе, 2 — выполнен, 3 — отменён, 4 — неизвестный статус
    private fun statusKind(s: String): Int = when (s.lowercase()) {
        "pending", "new", "created", "awaiting_payment", "waiting" -> 0
        "paid", "processing", "in_progress" -> 1
        "completed", "complete", "done", "delivered", "success", "succeeded", "fulfilled" -> 2
        "cancelled", "canceled", "expired", "failed", "refunded", "rejected" -> 3
        else -> 4
    }

    private fun statusLabel(s: String): String = when (statusKind(s)) {
        0 -> "Ожидает оплаты"
        1 -> "Оплачен · выдаём"
        2 -> "Выполнен"
        3 -> "Отменён"
        else -> if (s.isEmpty()) "—" else s
    }

    private fun statusColor(s: String): Int = when (statusKind(s)) {
        0 -> AMBER
        1 -> BLUE
        2 -> GREEN
        3 -> RED
        else -> MUTE
    }

    private fun fmtDate(iso: String): String = try {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        val d = f.parse(iso.take(19))
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(d!!)
    } catch (e: Exception) {
        iso.take(16).replace('T', ' ')
    }

    private fun money(total: String, cur: String): String =
        "$total ${if (cur.isEmpty() || cur == "RUB") "₽" else cur}"

    private fun showProfile() {
        onHome = false
        val col = screen()
        val sid = screenId
        backRow(col, "Профиль")
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(body)
        renderProfile(body, sid)
        refreshOrders(body, sid, false)
        val pending = Store.pendingToken
        if (pending.isNotEmpty() && !Store.loggedIn) pollLogin(body, sid, pending)
    }

    private fun statTile(value: String, label: String): LinearLayout {
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

    private fun renderProfile(body: LinearLayout, sid: Int) {
        body.removeAllViews()
        val orders = Store.orders()

        // карточка пользователя
        val loggedIn = Store.loggedIn
        val waiting = !loggedIn && Store.pendingToken.isNotEmpty()
        val shownName = when {
            loggedIn -> Store.tgName.ifEmpty { if (Store.tgUsername.isNotEmpty()) "@${Store.tgUsername}" else "Пользователь" }
            else -> ""
        }
        val first = (shownName.ifEmpty { Store.tgUsername }).trimStart('@').firstOrNull()
        val avatarDrawable = GradientDrawable()
        avatarDrawable.setShape(GradientDrawable.OVAL)
        avatarDrawable.setColor(if (loggedIn) BLUE else LINE)
        val avatar = TextView(this).apply {
            text = if (first != null) first.uppercaseChar().toString() else "?"
            textSize = 22f
            setTextColor(if (loggedIn) Color.BLACK else MUTE)
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = avatarDrawable
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(14) }
        }
        val who = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        when {
            loggedIn -> {
                who.addView(txt(shownName, 18f, TEXT, true))
                val sub = if (Store.tgUsername.isNotEmpty()) "@${Store.tgUsername} · вход через бота" else "Вход через бота"
                who.addView(txt(sub, 12f, GREEN))
            }
            waiting -> {
                who.addView(txt("Ждём подтверждение…", 18f, TEXT, true))
                who.addView(txt("Откройте бота и нажмите «Старт»", 12f, MUTE))
            }
            else -> {
                who.addView(txt("Вы не вошли", 18f, TEXT, true))
                who.addView(txt("Войдите через бота, чтобы видеть свои заказы", 12f, MUTE))
            }
        }
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
        when {
            loggedIn -> {
                tgActions.addView(chip("Открыть @pharmaishop_bot", false) { openUrl(BOT_URL) })
            }
            waiting -> {
                tgActions.addView(chip("Открыть бота снова", true) { startLogin(body, sid) })
                tgActions.addView(chip("Отмена", false) {
                    Store.pendingToken = ""
                    polling = false
                    renderProfile(body, sid)
                })
            }
            else -> {
                tgActions.addView(chip("Войти через Telegram", true) { startLogin(body, sid) })
                tgActions.addView(chip("Указать ник", false) { editUsername(body, sid) })
            }
        }
        body.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = lp(16)
            addView(tgActions)
        })

        // статистика
        val paid = orders.filter { val k = statusKind(it.optString("status")); k == 1 || k == 2 }
        val spent = paid.sumOf { it.optString("total").toDoubleOrNull() ?: 0.0 }
        val stars = paid.sumOf { it.optInt("quantity") }
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

        if (orders.isEmpty()) {
            body.addView(tv("Пока нет заказов. Здесь появятся заказы, оформленные в этом приложении.", 14f, MUTE))
        } else {
            for (o in orders) body.addView(orderCard(o, body, sid))
        }

        body.addView(tv(
            if (loggedIn) "Заказы синхронизируются с вашим аккаунтом в боте."
            else "Войдите через Telegram, чтобы видеть заказы из бота. Без входа история хранится только на этом устройстве.",
            12f, MUTE
        ))
        body.addView(btn("Поддержка в боте  @pharmaishop_bot") { openUrl(BOT_URL) })
        if (loggedIn) {
            body.addView(btn("Выйти из аккаунта") {
                AlertDialog.Builder(this)
                    .setTitle("Выйти из аккаунта?")
                    .setMessage("Список заказов на этом устройстве будет очищен. Сами заказы останутся на сервере.")
                    .setPositiveButton("Выйти") { _, _ ->
                        val ses = Store.session
                        Store.logout()
                        polling = false
                        if (Api.authConfigured) io.execute { try { Api.logout(ses) } catch (e: Exception) {} }
                        if (sid == screenId) renderProfile(body, sid)
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            })
        }
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
    }

    // ---------- вход через Telegram-бота ----------
    private fun startLogin(body: LinearLayout, sid: Int) {
        if (!Api.authConfigured) {
            toast("Сервер входа не настроен (Api.AUTH_BASE)")
            return
        }
        // если вход уже начат — просто открываем бота ещё раз
        val existing = Store.pendingToken
        if (existing.isNotEmpty()) {
            openUrl("$BOT_URL?start=login_$existing")
            if (!polling) pollLogin(body, sid, existing)
            return
        }
        io.execute {
            try {
                val r = Api.authStart()
                val token = r.getString("token")
                var link = r.optString("bot_url")
                if (!link.startsWith("https://t.me/")) link = "$BOT_URL?start=login_$token"
                Store.pendingToken = token
                ui {
                    if (sid == screenId) renderProfile(body, sid)
                    openUrl(link)
                    pollLogin(body, sid, token)
                }
            } catch (e: Exception) {
                ui { toast("Не удалось начать вход: ${e.message}") }
            }
        }
    }

    private fun pollLogin(body: LinearLayout, sid: Int, token: String) {
        if (polling) return
        polling = true
        val startedAt = System.currentTimeMillis()
        val step = object : Runnable {
            override fun run() {
                // ушли с экрана или отменили — прекращаем
                if (sid != screenId || Store.pendingToken != token) { polling = false; return }
                if (System.currentTimeMillis() - startedAt > 5 * 60 * 1000L) {
                    polling = false
                    Store.pendingToken = ""
                    renderProfile(body, sid)
                    toast("Время ожидания вышло. Попробуйте ещё раз")
                    return
                }
                io.execute {
                    try {
                        val r = Api.authPoll(token)
                        when (r.optString("status")) {
                            "ok" -> {
                                val u = r.getJSONObject("user")
                                Store.login(
                                    r.getString("session"), u.optLong("id"),
                                    u.optString("username"), u.optString("first_name")
                                )
                                ui {
                                    polling = false
                                    if (sid == screenId) {
                                        renderProfile(body, sid)
                                        refreshOrders(body, sid, true)
                                    }
                                    toast("Вы вошли через Telegram")
                                }
                            }
                            "expired" -> ui {
                                polling = false
                                Store.pendingToken = ""
                                if (sid == screenId) renderProfile(body, sid)
                                toast("Ссылка входа устарела. Попробуйте ещё раз")
                            }
                            else -> ui { main.postDelayed(this, 2000L) }
                        }
                    } catch (e: Exception) {
                        ui { main.postDelayed(this, 3000L) }
                    }
                }
            }
        }
        main.postDelayed(step, 1500L)
    }

    private fun editUsername(body: LinearLayout, sid: Int) {
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

    private fun orderCard(o: JSONObject, body: LinearLayout, sid: Int): LinearLayout {
        val status = o.optString("status")
        val num = o.optString("order_number")
        val color = statusColor(status)

        val badge = txt(statusLabel(status), 12f, color, true).apply {
            background = shape((color and 0x00FFFFFF) or 0x33000000, 10)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        val numView = txt("Заказ $num", 16f, TEXT, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(8)
            addView(numView)
            addView(badge)
        }

        val method = o.optString("method_name").ifEmpty { o.optString("method_id") }
        val line1 = tv("${o.optInt("quantity")} ★  →  @${o.optString("username")}", 15f, TEXT)
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
            actions.addView(chip("Оплатить", true) { openUrl(url) })
        }
        actions.addView(chip("Обновить статус", false) { refreshOrders(body, sid, true) })
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
        card.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(actions)
        })
        return card
    }

    /** Подтягивает заказы и статусы. force — обновить и завершённые. */
    private fun refreshOrders(body: LinearLayout, sid: Int, force: Boolean) {
        val loggedIn = Store.loggedIn && Api.authConfigured
        if (!loggedIn) {
            val has = Store.orders().any { it.optString("id").isNotEmpty() }
            if (!has) {
                if (force) toast("Нет заказов для обновления")
                return
            }
        }
        io.execute {
            var ok = 0
            var expired = false
            var syncedFromBot = false
            if (loggedIn) {
                try {
                    val arr = Api.myOrders(Store.session).optJSONArray("orders")
                    val list = ArrayList<JSONObject>()
                    if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(it) }
                    Store.merge(list)
                    syncedFromBot = true
                    ok = list.size
                } catch (e: AuthExpired) {
                    expired = true
                    Store.logout()
                } catch (e: Exception) {
                    // нет связи с сервером — ниже обновим статусы через витрину
                }
            }
            if (!syncedFromBot && !expired) {
                val targets = Store.orders()
                    .filter { o ->
                        val k = statusKind(o.optString("status"))
                        force || (k != 2 && k != 3)
                    }
                    .filter { it.optString("id").isNotEmpty() }
                    .take(10)
                for (o in targets) {
                    val id = o.optString("id")
                    try {
                        Store.update(id, Api.order(id))
                        ok++
                    } catch (e: Exception) {
                        // пропускаем этот заказ
                    }
                }
            }
            ui {
                if (sid == screenId) {
                    renderProfile(body, sid)
                    if (expired) toast("Сессия истекла, войдите заново")
                    else if (force) toast(if (ok > 0) "Заказы обновлены" else "Не удалось обновить")
                }
            }
        }
    }
}
