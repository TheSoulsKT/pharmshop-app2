package ru.pharmshop.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils

// Общие помощники интерфейса: анимации, виджеты, каркас экрана.

/** Раздел главного меню с учётом удалённых настроек. */
internal class SecView(val title: String, val sub: String, val enabled: Boolean, val url: String, val message: String)

internal class MethodRow(val id: String, val name: String, val row: LinearLayout, val dot: View, val tint: Int = 0)

// ---------- анимации ----------
/** Лёгкое «вдавливание» при касании. Клики не перехватывает (возвращает false). */
internal fun MainActivity.pressable(v: View) {
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
internal fun MainActivity.riseIn(col: LinearLayout, from: Int = 0) {
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
internal fun MainActivity.loopWhileAttached(v: View, a: ObjectAnimator) {
    a.repeatMode = ValueAnimator.REVERSE
    a.repeatCount = ValueAnimator.INFINITE
    v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(x: View) { if (!a.isStarted) a.start() }
        override fun onViewDetachedFromWindow(x: View) { a.cancel() }
    })
}

/** Логотип слегка «парит». */
internal fun MainActivity.floatForever(v: View) {
    loopWhileAttached(v, ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, 0f, -dp(3).toFloat()).apply {
        duration = 2200
        interpolator = AccelerateDecelerateInterpolator()
    })
}

/** Мягкое мигание бейджа заказа, который ещё не завершён. */
internal fun MainActivity.pulse(v: View) {
    loopWhileAttached(v, ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0.55f).apply {
        duration = 900
        interpolator = AccelerateDecelerateInterpolator()
    })
}

/** Смена текста с небольшим «всплеском» (для суммы к оплате). */
internal fun MainActivity.popText(t: TextView, s: String) {
    if (t.text.toString() == s) return
    t.text = s
    t.pivotX = 0f
    t.pivotY = t.height / 2f
    t.scaleX = 0.94f; t.scaleY = 0.94f; t.alpha = 0.4f
    t.animate().scaleX(1f).scaleY(1f).alpha(1f)
        .setStartDelay(0).setDuration(280).setInterpolator(OvershootInterpolator(2f)).start()
}

/** Радио-точка «выскакивает» в момент выбора. */
internal fun MainActivity.dotState(dot: View, selected: Boolean) {
    val was = dot.tag == "on"
    dot.tag = if (selected) "on" else null
    if (selected && !was) {
        dot.scaleX = 0.5f; dot.scaleY = 0.5f
        dot.animate().scaleX(1f).scaleY(1f)
            .setStartDelay(0).setDuration(320).setInterpolator(OvershootInterpolator(3f)).start()
    }
}

// ---------- helpers ----------
internal fun MainActivity.ui(r: () -> Unit) { main.post { r() } }
internal fun MainActivity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
internal fun MainActivity.toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

internal fun MainActivity.shape(fill: Int, radius: Int, stroke: Int = 0) = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = dp(radius).toFloat()
    if (stroke != 0) setStroke(dp(1), stroke)
}

internal fun MainActivity.ripple(fill: Int, radius: Int, stroke: Int = 0) = RippleDrawable(
    ColorStateList.valueOf(Theme.rippleColor),
    shape(fill, radius, stroke),
    shape(Color.WHITE, radius)
)

internal fun MainActivity.lp(bottom: Int = 10) = LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
).apply { setMargins(0, 0, 0, dp(bottom)) }

internal fun MainActivity.txt(t: String, size: Float, color: Int = TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

internal fun MainActivity.tv(t: String, size: Float, color: Int = TEXT, bold: Boolean = false): TextView =
    txt(t, size, color, bold).apply { layoutParams = lp(8) }

internal fun MainActivity.btn(t: String, primary: Boolean = false, onClick: () -> Unit): Button =
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

internal fun MainActivity.chip(t: String, primary: Boolean, onClick: () -> Unit): TextView =
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
internal fun MainActivity.mix(c: Int, amount: Float) = ColorUtils.blendARGB(CARD, c, amount)

internal fun MainActivity.edge(accent: Int) = ColorUtils.blendARGB(LINE, accent, 0.55f)

internal fun MainActivity.gradient(c1: Int, c2: Int, radius: Int) =
    GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(c1, c2)).apply {
        cornerRadius = dp(radius).toFloat()
    }

/** Цветная плашка-заголовок экрана покупки. */
internal fun MainActivity.banner(ic: Ic, title: String, sub: String, c1: Int, c2: Int, on: Int): LinearLayout {
    val iconBox = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        background = shape(ColorUtils.setAlphaComponent(on, 40), 14)
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(14) }
        addView(IconView(this@banner, ic, on).apply {
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
internal fun MainActivity.gradBtn(t: String, c1: Int, c2: Int, textColor: Int, onClick: () -> Unit): Button =
    Button(this).apply {
        text = t
        setAllCaps(false)
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(textColor)
        background = RippleDrawable(
            ColorStateList.valueOf(Theme.rippleColor), gradient(c1, c2, 18), shape(Color.WHITE, 18)
        )
        setPadding(dp(18), dp(16), dp(18), dp(16))
        layoutParams = lp()
        setOnClickListener { onClick() }
        pressable(this)
    }

/** Чип с цветной обводкой и тонировкой. */
internal fun MainActivity.chipTint(t: String, color: Int, onClick: () -> Unit): TextView =
    chip(t, false, onClick).apply {
        setTextColor(color)
        background = ripple(mix(color, 0.16f), 12, color)
        setPadding(dp(14), dp(9), dp(14), dp(9))
    }

internal fun MainActivity.input(hintText: String, type: Int): EditText = EditText(this).apply {
    hint = hintText
    inputType = type
    setTextColor(TEXT)
    setHintTextColor(MUTE)
    textSize = 16f
    background = shape(CARD, 14, LINE)
    setPadding(dp(14), dp(14), dp(14), dp(14))
    layoutParams = lp()
}

internal fun MainActivity.screen(): LinearLayout {
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

internal fun MainActivity.backRow(col: LinearLayout, title: String, onBack: () -> Unit = { navBack = true; showHome() }) {
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

internal fun MainActivity.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        toast("Не удалось открыть ссылку")
    }
}
