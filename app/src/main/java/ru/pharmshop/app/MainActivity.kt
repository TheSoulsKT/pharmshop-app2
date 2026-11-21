package ru.pharmshop.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import com.hcaptcha.sdk.HCaptcha
import com.hcaptcha.sdk.HCaptchaConfig
import com.hcaptcha.sdk.HCaptchaException
import com.hcaptcha.sdk.HCaptchaTokenResponse
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

private class MethodRow(val id: String, val name: String, val row: LinearLayout, val dot: View, val tint: Int = 0)

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

    // палитра экранов покупки (главный экран остаётся синим)
    private val GOLD = Color.parseColor("#FFB020")
    private val GOLD_A = Color.parseColor("#FFC83D")
    private val GOLD_B = Color.parseColor("#FF8A3D")
    private val ORANGE = Color.parseColor("#FF7A3D")
    private val PINK = Color.parseColor("#EC4899")
    private val VIOLET = Color.parseColor("#8B5CF6")
    private val VIOLET_A = Color.parseColor("#7C3AED")
    private val VIOLET_L = Color.parseColor("#B794F6")
    private val CYAN = Color.parseColor("#22C1DC")
    private val DARK_ON = Color.parseColor("#231400")

    private val BOT_URL = "https://t.me/pharmaishop_bot"
    /** Единая высота всех карточек главного меню */
    private val CARD_H get() = dp(96)

    private val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private var cfg: JSONObject? = null
    private var busy = false
    private var onHome = true
    private var screenId = 0
    private var backAction: (() -> Unit)? = null   // особое действие кнопки «назад» (чаты)
    private val net = Executors.newFixedThreadPool(4)   // параллельные запросы статусов
    private var refreshing = false
    private var lastRefreshError = ""
    private var returnFromPay = false
    private var hc: HCaptcha? = null
    private var hcOk: ((String) -> Unit)? = null
    private var hcFail: ((String) -> Unit)? = null
    private var navBack = false        // возврат назад: экран выезжает слева
    private var firstScreen = true     // первый экран после запуска — только плавное появление
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var onImagePicked: ((Uri) -> Unit)? = null
    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onImagePicked?.invoke(uri)
    }
    private var updateChecked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val ba = backAction
                if (ba != null) { ba(); return }
                if (onHome) finish() else { navBack = true; showHome() }
            }
        })
        // напоминания: разрешение спрашиваем один раз, будильник ставим при каждом запуске
        if (Reminders.shouldAskPermission(this)) {
            Reminders.markAsked(this)
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        Reminders.ensureScheduled(this)
        if (!openFromIntent(intent)) showHome()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFromIntent(intent)
    }

    /** Нажатие на уведомление открывает экран покупки звёзд или Premium. */
    private fun openFromIntent(i: Intent?): Boolean {
        val target = i?.getStringExtra(Reminders.EXTRA_OPEN) ?: return false
        i.removeExtra(Reminders.EXTRA_OPEN)
        return when (target) {
            "stars" -> { showStars(); true }
            "premium" -> { showPremium(); true }
            else -> false
        }
    }

    override fun onResume() {
        super.onResume()
        if (returnFromPay) {
            returnFromPay = false
            showProfile()
        }
    }

    // ---------- анимации ----------
    /** Лёгкое «вдавливание» при касании. Клики не перехватывает (возвращает false). */
    private fun pressable(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.97f).scaleY(0.97f)
                    .setStartDelay(0).setDuration(110).setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f)
                    .setStartDelay(0).setDuration(240).setInterpolator(OvershootInterpolator(2.5f)).start()
            }
            false
        }
    }

    /** Поочерёдное появление дочерних элементов (снизу вверх с проявлением), начиная с индекса from. */
    private fun riseIn(col: LinearLayout, from: Int = 0) {
        var n = 0
        for (i in from until col.childCount) {
            val v = col.getChildAt(i)
            v.alpha = 0f
            v.translationY = dp(14).toFloat()
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(minOf(n, 10) * 55L).setDuration(420)
                .setInterpolator(DecelerateInterpolator(2f)).start()
            n++
        }
    }

    /** Бесконечная анимация, которая живёт, пока вид на экране, и останавливается при его удалении. */
    private fun loopWhileAttached(v: View, a: ObjectAnimator) {
        a.repeatMode = ValueAnimator.REVERSE
        a.repeatCount = ValueAnimator.INFINITE
        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(x: View) { if (!a.isStarted) a.start() }
            override fun onViewDetachedFromWindow(x: View) { a.cancel() }
        })
    }

    /** Логотип слегка «парит». */
    private fun floatForever(v: View) {
        loopWhileAttached(v, ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, 0f, -dp(3).toFloat()).apply {
            duration = 2200
            interpolator = AccelerateDecelerateInterpolator()
        })
    }

    /** Мягкое мигание бейджа заказа, который ещё не завершён. */
    private fun pulse(v: View) {
        loopWhileAttached(v, ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0.55f).apply {
            duration = 900
            interpolator = AccelerateDecelerateInterpolator()
        })
    }

    /** Смена текста с небольшим «всплеском» (для суммы к оплате). */
    private fun popText(t: TextView, s: String) {
        if (t.text.toString() == s) return
        t.text = s
        t.pivotX = 0f
        t.pivotY = t.height / 2f
        t.scaleX = 0.94f; t.scaleY = 0.94f; t.alpha = 0.4f
        t.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setStartDelay(0).setDuration(280).setInterpolator(OvershootInterpolator(2f)).start()
    }

    /** Радио-точка «выскакивает» в момент выбора. */
    private fun dotState(dot: View, selected: Boolean) {
        val was = dot.tag == "on"
        dot.tag = if (selected) "on" else null
        if (selected && !was) {
            dot.scaleX = 0.5f; dot.scaleY = 0.5f
            dot.animate().scaleX(1f).scaleY(1f)
                .setStartDelay(0).setDuration(320).setInterpolator(OvershootInterpolator(3f)).start()
        }
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
            pressable(this)
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
            pressable(this)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8) }
        }

    /** Цвет, подмешанный к фону карточки (для выбранных строк). */
    private fun mix(c: Int, amount: Float) = ColorUtils.blendARGB(CARD, c, amount)

    private fun edge(accent: Int) = ColorUtils.blendARGB(LINE, accent, 0.55f)

    private fun gradient(c1: Int, c2: Int, radius: Int) =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(c1, c2)).apply {
            cornerRadius = dp(radius).toFloat()
        }

    /** Цветная плашка-заголовок экрана покупки. */
    private fun banner(ic: Ic, title: String, sub: String, c1: Int, c2: Int, on: Int): LinearLayout {
        val iconBox = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = shape(ColorUtils.setAlphaComponent(on, 40), 14)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(14) }
            addView(IconView(this@MainActivity, ic, on).apply {
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
            })
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(txt(title, 20f, on, true))
            addView(txt(sub, 13f, ColorUtils.setAlphaComponent(on, 205)))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = gradient(c1, c2, 20)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = lp(16)
            addView(iconBox)
            addView(texts)
        }
    }

    /** Кнопка «Оплатить» с градиентом. */
    private fun gradBtn(t: String, c1: Int, c2: Int, textColor: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            text = t
            setAllCaps(false)
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(textColor)
            background = RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF), gradient(c1, c2, 18), shape(Color.WHITE, 18)
            )
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = lp()
            setOnClickListener { onClick() }
            pressable(this)
        }

    /** Чип с цветной обводкой и тонировкой. */
    private fun chipTint(t: String, color: Int, onClick: () -> Unit): TextView =
        chip(t, false, onClick).apply {
            setTextColor(color)
            background = ripple(mix(color, 0.16f), 12, color)
            setPadding(dp(14), dp(9), dp(14), dp(9))
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
        backAction = null
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
        // переход между экранами: вперёд — выезд справа, назад — слева
        sv.alpha = 0f
        sv.translationX = if (firstScreen) 0f else dp(28).toFloat() * (if (navBack) -1 else 1)
        sv.animate().alpha(1f).translationX(0f).setDuration(320)
            .setInterpolator(DecelerateInterpolator(2f)).start()
        firstScreen = false
        navBack = false
        return col
    }

    private fun backRow(col: LinearLayout, title: String, onBack: () -> Unit = { navBack = true; showHome() }) {
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
            setOnClickListener { onBack() }
            pressable(this)
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
            pressable(this)
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
            pressable(this)
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
            setOnLongClickListener { showAdminEntry(); true }
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
            pressable(this)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            addView(userIcon)
        }
        val helpBtn = TextView(this).apply {
            text = "?"
            textSize = 20f
            setTextColor(TEXT)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            contentDescription = "Поддержка"
            background = ripple(CARD, 22, LINE)
            isClickable = true
            setOnClickListener { showSupport() }
            pressable(this)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(8) }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(22)
            addView(logo)
            addView(titles)
            addView(helpBtn)
            addView(profileBtn)
        }
        col.addView(head)
        col.addView(tv("Выберите, что вам нужно", 15f, MUTE))

        val soon = { toast("Скоро будет доступно") }
        val (starsCard, starsSub) = menuWide(Ic.STAR, "Звёзды", rateText()) { showStars() }
        col.addView(starsCard)
        col.addView(menuWide(Ic.GIFT, "Обычные подарки", "Покупка за рубли") { soon() }.first)
        col.addView(menuWide(Ic.CROWN, "Купить Premium", "Покупка подписки") { showPremium() }.first)
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
            pressable(this)
        }
        bot.addView(botIcon)
        bot.addView(txt("Заказать в боте", 13f, Color.BLACK))
        bot.addView(txt("@pharmaishop_bot", 20f, Color.BLACK, true))
        col.addView(bot)

        col.addView(txt("Цены на услуги могут меняться в зависимости от курса", 12f, MUTE).apply {
            gravity = Gravity.CENTER
            layoutParams = lp(0)
        })
        col.addView(txt("Приложение в активной разработке\nВерсия ${BuildConfig.VERSION_NAME}", 12f, MUTE).apply {
            gravity = Gravity.CENTER
            layoutParams = lp(0).apply { topMargin = dp(14) }
        })
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            layoutParams = lp(0).apply { topMargin = dp(10) }
            addView(chip("Проверить обновления", false) { checkForUpdate(false) }.apply {
                (layoutParams as LinearLayout.LayoutParams).rightMargin = 0
            })
        })

        floatForever(logo)
        riseIn(col)

        // один раз за запуск тихо проверяем обновления
        if (!updateChecked) {
            updateChecked = true
            checkForUpdate(true)
        }

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

    // ---------- обновление приложения ----------

    /** silent = проверка при запуске: сообщаем только если есть новая версия. */
    private fun checkForUpdate(silent: Boolean) {
        if (!Updater.configured) {
            if (!silent) toast("Обновления работают в версии, собранной на GitHub")
            return
        }
        if (!silent) toast("Проверяем обновления…")
        io.execute {
            try {
                val info = Updater.check()
                ui {
                    if (isFinishing || isDestroyed) return@ui
                    if (info == null) { if (!silent) toast("У вас последняя версия") }
                    else offerUpdate(info)
                }
            } catch (e: Exception) {
                ui { if (!silent) toast(e.message ?: "Не удалось проверить обновления") }
            }
        }
    }

    private fun offerUpdate(info: Updater.Info) {
        val mb = if (info.size > 0) " (%.1f МБ)".format(info.size / 1048576.0) else ""
        val notes = if (info.notes.isNotEmpty()) "\n\n${info.notes.take(600)}" else ""
        AlertDialog.Builder(this)
            .setTitle("Доступно обновление")
            .setMessage("${info.versionName}$mb$notes")
            .setPositiveButton("Обновить") { _, _ -> startUpdate(info) }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun startUpdate(info: Updater.Info) {
        if (!Updater.canInstall(this)) {
            AlertDialog.Builder(this)
                .setTitle("Нужно разрешение")
                .setMessage("Разрешите приложению устанавливать обновления, затем вернитесь и нажмите «Проверить обновления» ещё раз.")
                .setPositiveButton("Открыть настройки") { _, _ -> Updater.openInstallPermissionSettings(this) }
                .setNegativeButton("Отмена", null)
                .show()
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Скачиваем обновление")
            .setMessage("0%")
            .setCancelable(false)
            .show()
        io.execute {
            try {
                val apk = Updater.download(this, info) { pct -> ui { dialog.setMessage("$pct%") } }
                ui {
                    dialog.dismiss()
                    try { Updater.install(this, apk) } catch (e: Exception) { toast("Не удалось запустить установку") }
                }
            } catch (e: Exception) {
                ui { dialog.dismiss(); toast(e.message ?: "Не удалось скачать обновление") }
            }
        }
    }

    // ---------- частые вопросы ----------
    private fun faqCard(title: String, body: String, color: Int): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(CARD, 18, LINE)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = lp(10)
        }
        card.addView(txt(title, 16f, color, true).apply { layoutParams = lp(6) })
        card.addView(txt(body, 14f, TEXT))
        return card
    }

    // ---------- поддержка: чат ----------

    /** Экран с фиксированной шапкой и полем ввода внизу (для чатов). */
    private fun chatScreen(): LinearLayout {
        screenId++
        backAction = null
        navBack = false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(18), dp(28), dp(18), dp(12))
        }
        setContentView(root)
        return root
    }

    private fun errText(e: Exception) =
        if (e is IllegalStateException) "Чат пока не настроен" else (e.message ?: "Нет связи")

    /** Сжимает фото: учитывает поворот, сторона до 1280 px, JPEG ≤ ~700 КБ. Возвращает base64. */
    private fun prepareImage(uri: Uri): String? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2560) sample *= 2
            var bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
            val rotation = try {
                contentResolver.openInputStream(uri)?.use {
                    when (android.media.ExifInterface(it).getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL
                    )) {
                        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            } catch (e: Exception) { 0f }
            val scale = 1280f / maxOf(bmp.width, bmp.height)
            val m = Matrix()
            if (scale < 1f) m.postScale(scale, scale)
            if (rotation != 0f) m.postRotate(rotation)
            if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            var q = 82
            var bytes: ByteArray
            do {
                val o = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, q, o)
                bytes = o.toByteArray()
                q -= 12
            } while (bytes.size > 700_000 && q > 30)
            if (bytes.size > 1_000_000) null
            else android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    private fun showFullImage(bmp: Bitmap) {
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        AlertDialog.Builder(this).setView(iv).setPositiveButton("Закрыть", null).show()
    }

    /**
     * Лента сообщений + поле ввода. mine — чьи сообщения показывать справа ("user" или "admin").
     * fetch(after) возвращает новые сообщения, send(text, imageBase64) отправляет сообщение (фото — по желанию),
     * fetchImage(id) загружает фото сообщения. showMeta — показывать под сообщениями клиента IP и устройство (админ).
     */
    private fun chatPane(
        sid: Int, mine: String, hint: String,
        fetch: (Long) -> JSONArray, send: (String, String) -> Unit,
        fetchImage: (Long) -> String, showMeta: Boolean = false
    ): LinearLayout {
        val pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }
        val emptyHint = tv(hint, 14f, MUTE)
        list.addView(emptyHint)
        val scroll = ScrollView(this).apply {
            addView(list)
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val status = txt("", 12f, MUTE).apply { layoutParams = lp(4) }
        val field = input(
            "Сообщение",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        ).apply {
            maxLines = 4
            filters = arrayOf(InputFilter.LengthFilter(1000))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) }
        }

        var lastId = 0L
        var pendingImage = ""   // прикреплённое фото (base64), уходит со следующим сообщением
        val timeFmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

        // строка «Фото прикреплено» с кнопкой убрать
        val attachInfo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = lp(6)
        }

        fun add(m: JSONObject) {
            val id = m.optLong("id")
            if (id <= lastId) return
            lastId = id
            if (emptyHint.parent != null) list.removeView(emptyHint)
            val own = m.optString("from") == mine
            val wrap = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (own) Gravity.END else Gravity.START
                layoutParams = lp(8)
            }
            val body = m.optString("text")
            if (body.isNotEmpty()) {
                wrap.addView(TextView(this).apply {
                    text = body
                    textSize = 15f
                    setTextColor(if (own) Color.BLACK else TEXT)
                    background = shape(if (own) BLUE else CARD, 16, if (own) BLUE else LINE)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
                })
            }
            if (m.optInt("has_image") == 1) {
                val size = (resources.displayMetrics.widthPixels * 0.6f).toInt()
                val iv = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = shape(CARD, 16, LINE)
                    clipToOutline = true
                    layoutParams = LinearLayout.LayoutParams(size, size * 3 / 4).apply { topMargin = dp(4) }
                }
                var full: Bitmap? = null
                iv.setOnClickListener { full?.let { showFullImage(it) } }
                wrap.addView(iv)
                io.execute {
                    try {
                        val bytes = android.util.Base64.decode(fetchImage(id), android.util.Base64.DEFAULT)
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) ui { full = bmp; iv.setImageBitmap(bmp) }
                    } catch (e: Exception) {
                        // оставляем пустую рамку; при следующем открытии чата загрузится снова
                    }
                }
            }
            wrap.addView(txt(timeFmt.format(java.util.Date(m.optLong("ts"))), 10f, MUTE))
            // для администратора: откуда пришло сообщение
            if (showMeta && m.optString("from") == "user") {
                val ip = m.optString("ip")
                val dev = m.optString("dev")
                val geo = m.optString("geo")
                if (ip.isNotEmpty() || dev.isNotEmpty()) {
                    val line = buildString {
                        if (ip.isNotEmpty()) append("IP ").append(ip)
                        if (geo.isNotEmpty()) append(" • ").append(geo)
                        if (dev.isNotEmpty()) append("\n").append(dev)
                    }
                    wrap.addView(txt(line, 10f, MUTE).apply { setTextIsSelectable(true) })
                }
            }
            list.addView(wrap)
        }

        fun fetchOnce() {
            io.execute {
                try {
                    val arr = fetch(lastId)
                    ui {
                        if (sid == screenId) {
                            val before = lastId
                            for (i in 0 until arr.length()) add(arr.getJSONObject(i))
                            status.text = ""
                            if (lastId != before) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                        }
                    }
                } catch (e: Exception) {
                    ui { if (sid == screenId) status.text = errText(e) }
                }
            }
        }

        fun loop() {
            if (sid != screenId) return
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) fetchOnce()
            main.postDelayed({ loop() }, 4000L)
        }

        fun setAttachment(b64: String) {
            pendingImage = b64
            attachInfo.removeAllViews()
            if (b64.isEmpty()) { attachInfo.visibility = View.GONE; return }
            attachInfo.visibility = View.VISIBLE
            attachInfo.addView(txt("📷 Фото прикреплено", 13f, TEXT).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            attachInfo.addView(chip("Убрать", false) { setAttachment("") }.apply {
                (layoutParams as LinearLayout.LayoutParams).rightMargin = 0
            })
        }

        fun sendNow() {
            val text = field.text.toString().trim()
            val image = pendingImage
            if (text.isEmpty() && image.isEmpty()) return
            field.setText("")
            setAttachment("")
            io.execute {
                try {
                    send(text, image)
                    ui { fetchOnce() }
                } catch (e: Exception) {
                    ui {
                        if (sid == screenId) {
                            field.setText(text)
                            setAttachment(image)
                            toast(errText(e))
                        }
                    }
                }
            }
        }

        val attachBtn = TextView(this).apply {
            text = "📎"
            textSize = 20f
            gravity = Gravity.CENTER
            contentDescription = "Прикрепить фото"
            background = ripple(CARD, 12, LINE)
            isClickable = true
            setOnClickListener {
                onImagePicked = { uri ->
                    toast("Обрабатываем фото…")
                    io.execute {
                        val b64 = prepareImage(uri)
                        ui {
                            if (sid != screenId) return@ui
                            if (b64 == null) toast("Не удалось подготовить фото") else setAttachment(b64)
                        }
                    }
                }
                try { imagePicker.launch("image/*") } catch (e: Exception) { toast("Не удалось открыть галерею") }
            }
            pressable(this)
            layoutParams = LinearLayout.LayoutParams(dp(50), dp(50)).apply { rightMargin = dp(8) }
        }
        val sendBtn = chip("Отправить", true) { sendNow() }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(50))
        }
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(attachBtn)
            addView(field)
            addView(sendBtn)
        }
        pane.addView(scroll)
        pane.addView(status)
        pane.addView(attachInfo)
        pane.addView(composer)
        loop()
        return pane
    }

    private fun faqView(): ScrollView {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(tv("Часто задаваемые вопросы:", 15f, MUTE))
        c.addView(faqCard(
            "Как быстро приходят звезды?",
            "Заказы отправляются автоматически и, приходят в течение 15 секунд.", BLUE
        ))
        c.addView(faqCard(
            "Могу ли я покупать звезды только для себя?",
            "Нет, вы можете отправлять подарки любым пользователям, у которых есть @username.", CYAN
        ))
        c.addView(faqCard(
            "Есть ли риск блокировки моего аккаунта или рефаунда звезд?",
            "Нет, риск отсутствует, так как мы используем официальную платформу Telegram для покупки звёзд. " +
                "Блокировка или потеря звёзд невозможны", GREEN
        ))
        c.addView(faqCard(
            "Продажа звезд",
            "Для продажи звезд требуется подтвердить их, или ждать 21 день. После оплаты заявки бот выдаст вам " +
                "всю информацию. Необходимо скинуть номер заявки и транзакции по звездам, все просто", AMBER
        ))
        return ScrollView(this).apply {
            addView(c)
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
    }

    /** Единственная точка связи с поддержкой: кнопка «?» на главном экране. */
    private fun showSupport() {
        onHome = false
        val root = chatScreen()
        val sid = screenId
        backRow(root, "Поддержка")
        val nick = Store.lastUser
        val order = Store.orders().firstOrNull()?.optString("order_number").orEmpty()
        val chat = chatPane(
            sid, "user",
            "Напишите нам, ответ придёт сюда, в чат. Если вопрос по заказу, укажите его номер. " +
                "Можно прикрепить фото (скрин ошибки, чек) кнопкой 📎.\n\n" +
                "Вместе с обращением мы получаем ваш IP-адрес и модель устройства — это помогает разобраться с проблемой.",
            { after -> ChatApi.userMessages(Store.deviceId, Store.deviceSecret, after) },
            { text, image -> ChatApi.userSend(Store.deviceId, Store.deviceSecret, nick, order, BuildConfig.VERSION_NAME, text, image) },
            { id -> ChatApi.userImage(Store.deviceId, Store.deviceSecret, id) }
        )
        val faq = faqView()
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(12)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        fun select(isChat: Boolean) {
            tabs.removeAllViews()
            tabs.addView(chip("Чат", isChat) { select(true) })
            tabs.addView(chip("Частые вопросы", !isChat) { select(false) })
            body.removeAllViews()
            body.addView(if (isChat) chat else faq)
        }
        root.addView(tabs)
        root.addView(body)
        select(true)
    }

    // ---------- админ-панель (вход: долгое нажатие на логотип) ----------

    private fun showAdminEntry() {
        if (Store.adminCode.isNotEmpty()) showAdminThreads() else showAdminLogin()
    }

    private fun showAdminLogin() {
        onHome = false
        val col = screen()
        val sid = screenId
        backRow(col, "Админ-панель")
        col.addView(tv("Введите код доступа.", 14f, MUTE))
        val code = input("Код", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val info = tv("", 13f, RED)
        var working = false
        col.addView(code)
        col.addView(btn("Войти", true) {
            if (working) return@btn
            val c = code.text.toString().trim()
            if (c.isEmpty()) return@btn
            working = true
            info.text = ""
            io.execute {
                try {
                    ChatApi.adminThreads(c)
                    Store.adminCode = c
                    ui { working = false; if (sid == screenId) showAdminThreads() }
                } catch (e: Exception) {
                    ui { working = false; if (sid == screenId) info.text = errText(e) }
                }
            }
        })
        col.addView(info)
    }

    private fun showAdminThreads() {
        onHome = false
        val col = screen()
        val sid = screenId
        backRow(col, "Чаты поддержки")
        val status = tv("Загружаем…", 13f, MUTE)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(status)
        col.addView(list)
        col.addView(btn("Выйти из админ-панели") {
            Store.adminCode = ""
            navBack = true
            showHome()
        })
        val timeFmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

        fun render(arr: JSONArray) {
            list.removeAllViews()
            status.text = if (arr.length() == 0) "Пока нет обращений" else ""
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                val unread = t.optInt("unread")
                val name = t.optString("nick").let { if (it.isEmpty()) "Гость" else "@$it" }
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = ripple(CARD, 18, if (unread > 0) BLUE else LINE)
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    layoutParams = lp(10)
                    isClickable = true
                    setOnClickListener { showAdminChat(t) }
                    pressable(this)
                }
                val top = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                top.addView(txt(name, 16f, TEXT, true).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (unread > 0) {
                    top.addView(txt("$unread", 12f, Color.BLACK, true).apply {
                        background = shape(BLUE, 10)
                        setPadding(dp(8), dp(2), dp(8), dp(2))
                    })
                }
                card.addView(top)
                card.addView(txt(t.optString("last_text").ifEmpty { "…" }, 14f, MUTE).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
                val order = t.optString("last_order")
                val ip = t.optString("last_ip")
                val meta = timeFmt.format(java.util.Date(t.optLong("last_ts"))) +
                    (if (order.isNotEmpty()) "  •  заказ $order" else "") +
                    (if (ip.isNotEmpty()) "  •  IP $ip" else "")
                card.addView(txt(meta, 11f, MUTE))
                list.addView(card)
            }
        }

        fun load() {
            if (sid != screenId) return
            io.execute {
                try {
                    val arr = ChatApi.adminThreads(Store.adminCode)
                    ui { if (sid == screenId) render(arr) }
                } catch (e: ChatAuthException) {
                    Store.adminCode = ""
                    ui { if (sid == screenId) showAdminLogin() }
                    return@execute
                } catch (e: Exception) {
                    ui { if (sid == screenId) status.text = errText(e) }
                }
                main.postDelayed({ load() }, 8000L)
            }
        }
        load()
    }

    private fun showAdminChat(t: JSONObject) {
        onHome = false
        val root = chatScreen()
        val sid = screenId
        backAction = { showAdminThreads() }
        val device = t.optString("device")
        val title = t.optString("nick").let { if (it.isEmpty()) "Гость" else "@$it" }
        backRow(root, title) { showAdminThreads() }
        val order = t.optString("last_order")
        if (order.isNotEmpty()) root.addView(tv("Последний заказ: $order", 12f, MUTE))
        val lastIp = t.optString("last_ip")
        val lastDev = t.optString("last_dev")
        if (lastIp.isNotEmpty() || lastDev.isNotEmpty()) {
            root.addView(tv("IP: ${lastIp.ifEmpty { "—" }}\n${lastDev.ifEmpty { "устройство неизвестно" }}", 12f, MUTE).apply {
                setTextIsSelectable(true)
            })
        }
        root.addView(chatPane(
            sid, "admin", "Сообщений пока нет.",
            { after -> ChatApi.adminMessages(Store.adminCode, device, after) },
            { text, image -> ChatApi.adminReply(Store.adminCode, device, text, image) },
            { id -> ChatApi.adminImage(Store.adminCode, device, id) },
            showMeta = true
        ))
    }

    // ---------- покупка звёзд ----------
    private fun showStars() {
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

    private fun buildForm(col: LinearLayout, c: JSONObject) {
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
    private fun shownMethods(c: JSONObject): List<Pair<String, String>> {
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
    private fun selectList(
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

    // ---------- покупка Premium ----------
    private fun showPremium() {
        onHome = false
        val col = screen()
        val sid = screenId
        backRow(col, "Купить Premium")
        val status = tv("Загружаем данные…", 14f, MUTE)
        col.addView(status)
        riseIn(col)
        io.execute {
            try {
                val c = cfg ?: Api.config().also { cfg = it }
                ui { if (sid == screenId) { status.visibility = View.GONE; buildPremiumForm(col, c) } }
            } catch (e: Exception) {
                ui { if (sid == screenId) status.text = "Не удалось загрузить данные: ${e.message}" }
            }
        }
    }

    private fun buildPremiumForm(col: LinearLayout, c: JSONObject) {
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

        col.addView(banner(Ic.CROWN, "Telegram Premium", "Подписка для любого @username", VIOLET_A, PINK, Color.WHITE))
        col.addView(tv("Подписка Telegram Premium для указанного аккаунта", 14f, MUTE))
        val user = input("Имя пользователя в Telegram, без @", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        user.setText(Store.lastUser)
        user.background = shape(CARD, 14, edge(VIOLET))
        col.addView(user)

        var months = plans[0].first
        var method = methods[0].first
        var methodName = methods[0].second
        val total = tv("К оплате: —", 18f, VIOLET_L, true)
        var token = 0

        fun requestQuote() {
            token++
            val my = token
            popText(total, "К оплате: …")
            val m = months
            val mt = method
            io.execute {
                try {
                    val r = Api.quote(m, mt, "premium")
                    ui { if (my == token) popText(total, "К оплате: ${r.optString("total")} ${r.optString("currency", "RUB")}") }
                } catch (e: Exception) {
                    ui { if (my == token) popText(total, "Не удалось рассчитать цену") }
                }
            }
        }

        col.addView(tv("Срок подписки", 15f, CYAN, true))
        val planBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(10) }
        col.addView(planBox)
        selectList(
            planBox, plans.map { Pair(it.first.toString(), "${it.first} мес. · от ${it.second} ₽") },
            months.toString(), listOf(CYAN, VIOLET, PINK, GOLD)
        ) { id, _ -> months = id.toInt(); requestQuote() }

        col.addView(tv("Способ оплаты", 15f, VIOLET_L, true))
        val methodBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(10) }
        col.addView(methodBox)
        selectList(methodBox, methods, method, listOf(VIOLET, PINK)) { id, name -> method = id; methodName = name; requestQuote() }

        val info = tv("", 14f, MUTE)
        val payBtn = gradBtn("Оплатить", VIOLET_A, PINK, Color.WHITE) {
            pay(user.text.toString(), months, method, methodName, c, info, "premium")
        }
        col.addView(total)
        col.addView(payBtn)
        col.addView(info)
        col.addView(btn("Мои заказы") { showProfile() })
        riseIn(col, firstNew)
        requestQuote()
    }

    /** Готовим hCaptcha заранее, чтобы по кнопке «Оплатить» проверка открывалась сразу. */
    private fun captcha(siteKey: String): HCaptcha {
        hc?.let { return it }
        val h = HCaptcha.getClient(this)
        h.addOnSuccessListener { r: HCaptchaTokenResponse -> hcOk?.invoke(r.tokenResult) }
        h.addOnFailureListener { e: HCaptchaException -> hcFail?.invoke(e.message ?: "") }
        // Сервер сайта проверяет домен токена, поэтому подписываемся его доменом, а не hcaptcha.com
        h.setup(HCaptchaConfig.builder().siteKey(siteKey).host("pharmshop.tgstars.tg").build())
        hc = h
        return h
    }

    private fun pay(rawUser: String, q: Int, method: String, methodName: String, c: JSONObject, info: TextView, product: String = "") {
        if (busy) return
        val username = rawUser.trim().removePrefix("@")
        val min = c.optInt("min", 50)
        val max = c.optInt("max", 10000)
        if (username.length < 4) { toast("Введите имя пользователя"); return }
        if (product.isEmpty() && (q < min || q > max)) { toast("Количество: от $min до $max"); return }
        val siteKey = c.optString("hcaptcha_sitekey")
        if (siteKey.isEmpty()) { toast("Нет ключа проверки"); return }

        busy = true
        info.text = "Пройдите проверку…"
        val h = captcha(siteKey)
        hcOk = { token ->
            info.text = "Создаём заказ…"
            io.execute {
                try {
                    val o = Api.createOrder(username, q, method, token, product)
                    o.put("method_name", methodName)
                    Store.add(o)
                    Store.lastUser = username
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
        }
        hcFail = { msg ->
            busy = false
            hc = null   // в следующий раз подготовим проверку заново
            info.text = "Проверка не пройдена: $msg"
        }
        h.verifyWithHCaptcha()
    }

    private fun openPay(url: String, info: TextView) {
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

    // ---------- профиль и заказы ----------
    // 0 — ждёт оплаты, 1 — оплачен/в работе, 2 — выполнен, 3 — отменён, 4 — неизвестный статус
    /**
     * Итоговый статус заказа. Сайт может отдавать статус в разных полях
     * (status / payment_status / delivery_status …) или флагами (paid, paid_at …),
     * поэтому берём самый «продвинутый» из всех найденных.
     */
    private fun effStatus(o: JSONObject): String {
        val fields = listOf(
            "status", "order_status", "state", "payment_status",
            "delivery_status", "fulfillment_status", "payout_status"
        )
        var best = ""
        var bestRank = -1
        fun rank(k: Int) = when (k) { 2 -> 4; 3 -> 3; 1 -> 2; 0 -> 1; else -> 0 }
        for (f in fields) {
            val v = o.optString(f)
            if (v.isEmpty() || v == "null") continue
            val r = rank(statusKind(v))
            if (r > bestRank) { bestRank = r; best = v }
        }
        // признаки выполнения/оплаты, если строкового статуса мало
        fun has(key: String) = o.has(key) && !o.isNull(key) && o.optString(key).isNotEmpty() && o.optString(key) != "false"
        if (bestRank < 4 && (has("completed_at") || has("delivered_at") || has("fulfilled_at"))) return "completed"
        if (bestRank < 2 && (has("paid") && o.optString("paid") != "0" || has("is_paid") && o.optString("is_paid") != "0" || has("paid_at"))) return "paid"
        return best
    }

    private fun statusKind(raw: String): Int {
        val s = raw.lowercase().trim()
        return when {
            s.isEmpty() -> 4
            s in setOf("completed", "complete", "done", "delivered", "success", "succeeded", "fulfilled", "finished", "sent") -> 2
            listOf("cancel", "fail", "expire", "refund", "reject", "error").any { s.contains(it) } -> 3
            listOf("complet", "success", "fulfil", "finish").any { s.contains(it) } -> 2
            listOf("paid", "process", "progress", "deliver", "confirm", "send", "queue", "work").any { s.contains(it) } -> 1
            listOf("pend", "wait", "new", "creat", "unpaid", "await").any { s.contains(it) } -> 0
            else -> 4
        }
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
        renderProfile(body, sid, true)
        refreshOrders(body, sid, false)
        startAutoRefresh(body, sid)
    }

    /** Пока открыт профиль и есть незавершённые заказы — обновляем статусы каждые 5 секунд. */
    private fun startAutoRefresh(body: LinearLayout, sid: Int) {
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

    private fun renderProfile(body: LinearLayout, sid: Int, animate: Boolean = false) {
        body.removeAllViews()
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

    /** Показывает всё, что сайт вернул по заказу (для диагностики статуса). */
    private fun showRaw(o: JSONObject) {
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

    private fun orderCard(o: JSONObject, body: LinearLayout, sid: Int): LinearLayout {
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
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(8)
            addView(numView)
            addView(badge)
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
        card.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(actions)
        })
        return card
    }

    private fun ordersSnapshot(): String =
        Store.orders().joinToString("|") { it.optString("id") + ":" + effStatus(it) }

    /** Подтягивает свежие статусы заказов с сервера. force — обновить и завершённые. */
    private fun refreshOrders(body: LinearLayout, sid: Int, force: Boolean) {
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
}
