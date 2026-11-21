package ru.pharmshop.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.hcaptcha.sdk.HCaptcha
import com.hcaptcha.sdk.HCaptchaConfig
import com.hcaptcha.sdk.HCaptchaException
import com.hcaptcha.sdk.HCaptchaTokenResponse
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val BG = Color.parseColor("#121214")
    private val CARD = Color.parseColor("#1D1C21")
    private val LINE = Color.parseColor("#2B2A31")
    private val TEXT = Color.parseColor("#F2F2F4")
    private val MUTE = Color.parseColor("#9A9DA3")
    private val BLUE = Color.parseColor("#3B8EF3")

    private val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private var cfg: JSONObject? = null
    private var busy = false
    private var onHome = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    private fun lp(bottom: Int = 10) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, 0, 0, dp(bottom)) }

    private fun tv(t: String, size: Float, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            layoutParams = lp(8)
        }

    private fun btn(t: String, primary: Boolean = false, onClick: () -> Unit): Button =
        Button(this).apply {
            text = t
            setAllCaps(false)
            textSize = 16f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(if (primary) Color.BLACK else TEXT)
            background = shape(if (primary) BLUE else CARD, 18, if (primary) BLUE else LINE)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = lp()
            setOnClickListener { onClick() }
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

    // ---------- главный экран ----------
    private fun showHome() {
        onHome = true
        val col = screen()
        col.addView(tv("PharmShop", 32f, TEXT, true))
        col.addView(tv("Telegram магазин • Stars • NFT • Подарки", 13f, MUTE).also {
            (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(20)
        })
        col.addView(btn("⭐  Звёзды") { showStars() })
        val soon = listOf(
            "🎁  Обычные подарки", "👑  Купить Premium", "🖼  Купить NFT",
            "🔑  Аренда NFT", "💰  Продать звёзды", "📦  Скупка NFT"
        )
        for (n in soon) col.addView(btn(n) { toast("Скоро будет доступно") })
    }

    // ---------- покупка звёзд ----------
    private fun showStars() {
        onHome = false
        val col = screen()
        col.addView(btn("←  Назад") { showHome() })
        col.addView(tv("Купить звёзды", 26f, TEXT, true))
        val status = tv("Загружаем данные…", 14f, MUTE)
        col.addView(status)
        io.execute {
            try {
                val c = cfg ?: Api.config().also { cfg = it }
                ui { status.visibility = View.GONE; buildForm(col, c) }
            } catch (e: Exception) {
                ui { status.text = "Не удалось загрузить данные: ${e.message}" }
            }
        }
    }

    private fun buildForm(col: LinearLayout, c: JSONObject) {
        val min = c.optInt("min", 50)
        val max = c.optInt("max", 10000)
        col.addView(tv("${c.optString("stars_rate")} ₽ за звезду. От $min до $max шт.", 14f, MUTE))

        val user = input("Имя пользователя в Telegram, без @", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val qty = input("Сколько звёзд", InputType.TYPE_CLASS_NUMBER)
        col.addView(user)
        col.addView(qty)

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val presets = c.optJSONArray("presets")
        if (presets != null) {
            for (i in 0 until presets.length()) {
                val v = presets.getInt(i)
                chips.addView(TextView(this).apply {
                    text = v.toString()
                    setTextColor(TEXT)
                    textSize = 14f
                    background = shape(CARD, 12, LINE)
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, dp(8), 0) }
                    setOnClickListener { qty.setText(v.toString()) }
                })
            }
        }
        col.addView(HorizontalScrollView(this).apply {
            addView(chips)
            isHorizontalScrollBarEnabled = false
            layoutParams = lp(14)
        })

        col.addView(tv("Способ оплаты", 15f, TEXT, true))
        var method = c.optString("default_method")
        val group = RadioGroup(this).apply { layoutParams = lp(10) }
        val methods = c.optJSONArray("methods")
        if (methods != null) {
            for (i in 0 until methods.length()) {
                val m = methods.getJSONObject(i)
                val mid = m.getString("id")
                group.addView(RadioButton(this).apply {
                    text = m.getString("name")
                    setTextColor(TEXT)
                    this.id = View.generateViewId()
                    tag = mid
                    isChecked = (mid == method)
                })
            }
            if (group.childCount > 0 && group.checkedRadioButtonId == -1) {
                val first = group.getChildAt(0) as RadioButton
                first.isChecked = true
                method = first.tag as String
            }
        }
        col.addView(group)

        val total = tv("К оплате: —", 18f, TEXT, true)
        var token = 0
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

        group.setOnCheckedChangeListener { g, checked ->
            method = g.findViewById<RadioButton>(checked).tag as String
            requestQuote()
        }
        qty.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { requestQuote() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        })

        val info = tv("", 14f, MUTE)
        val payBtn = btn("Оплатить", true) {
            pay(user.text.toString(), qty.text.toString().toIntOrNull() ?: 0, method, c, info)
        }
        payBtn.gravity = Gravity.CENTER
        col.addView(total)
        col.addView(payBtn)
        col.addView(info)
    }

    private fun pay(rawUser: String, q: Int, method: String, c: JSONObject, info: TextView) {
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
}
