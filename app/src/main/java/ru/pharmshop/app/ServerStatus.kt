package ru.pharmshop.app

import android.animation.ObjectAnimator
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils

// Плашка «Статус сервера» на экранах «Купить звёзды» и «Купить Premium».
// Пока сайт не работает, оформить заказ нельзя (см. pay() в BuyPremium.kt).

internal enum class SrvState { CHECKING, ONLINE, SLOW, OFFLINE }

/** Последнее известное состояние сайта. Общее для обоих экранов покупки. */
internal object ServerHealth {
    @Volatile var state: SrvState = SrvState.CHECKING
    @Volatile var ms: Long = 0
    @Volatile var detail: String = ""

    /** Сайт не работает — заказ создавать нельзя. */
    val down: Boolean get() = state == SrvState.OFFLINE

    /** Ответ дольше этого времени считаем «медленно» (но заказ создать можно). */
    const val SLOW_MS = 3000L

    fun update(s: SrvState, ms: Long, detail: String) {
        state = s
        this.ms = ms
        this.detail = detail
    }

    fun fromPing(r: PingResult) {
        if (r.ok) update(if (r.ms > SLOW_MS) SrvState.SLOW else SrvState.ONLINE, r.ms, "")
        else update(SrvState.OFFLINE, r.ms, r.detail)
    }
}

/**
 * Плашка со статусом сайта: цветная точка, статус, время ответа и кнопка «Проверить».
 * Сама перепроверяет сайт раз в 20 секунд, пока экран открыт, а результаты расчёта цены
 * (report*) тоже обновляют статус, так что при выборе суммы или срока сайт проверяется заново.
 *
 * onChange — вызывается при каждом обновлении (форма гасит/включает кнопку «Оплатить»).
 * onRecovered — вызывается, когда сайт снова заработал после сбоя.
 */
internal class ServerWatch(
    private val a: MainActivity,
    private val sid: Int,
    private val onChange: (SrvState) -> Unit = {},
    private val onRecovered: (() -> Unit)? = null
) {
    private val dot = View(a)
    private val ring = LinearLayout(a)
    private val status = a.txt("Проверяем…", 17f, a.TEXT, true)
    private val sub = a.txt("", 12f, a.MUTE)
    private val action = TextView(a)
    val root = LinearLayout(a)
    private var checking = false

    init {
        ring.apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(a.dp(40), a.dp(40)).apply { rightMargin = a.dp(14) }
            addView(dot, LinearLayout.LayoutParams(a.dp(14), a.dp(14)))
        }
        a.loopWhileAttached(dot, ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.35f).apply {
            duration = 900
            interpolator = AccelerateDecelerateInterpolator()
        })
        val mid = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(a.txt("Статус сервера", 12f, a.MUTE))
            addView(status)
            addView(sub)
        }
        action.apply {
            text = "Проверить"
            textSize = 13f * Theme.textScale
            gravity = Gravity.CENTER
            setTextColor(ACC_TEXT)
            background = a.ripple(Color.TRANSPARENT, 12, a.LINE)
            setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8))
            isClickable = true
            contentDescription = "Проверить статус сервера"
            setOnClickListener { check() }
            a.pressable(this)
        }
        root.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(14), a.dp(12), a.dp(12), a.dp(12))
            layoutParams = a.lp(12)
            addView(ring)
            addView(mid)
            addView(action)
        }
        // показываем то, что знали раньше, и сразу перепроверяем
        render(ServerHealth.state, ServerHealth.ms, ServerHealth.detail)
        check(silent = true)
        schedule()
    }

    // ---------- проверка ----------

    /** Запрос к сайту. silent = true — без «Проверяем…» (фоновая перепроверка). */
    fun check(silent: Boolean = false) {
        if (checking) return
        checking = true
        if (!silent) {
            render(SrvState.CHECKING, 0, "")
            action.text = "…"
        }
        a.io.execute {
            val r = Api.ping()
            a.ui {
                checking = false
                action.text = "Проверить"
                if (sid == a.screenId) applyPing(r) else ServerHealth.fromPing(r)
            }
        }
    }

    private fun schedule() {
        a.main.postDelayed({
            if (sid != a.screenId) return@postDelayed
            if (!a.busy) check(silent = true)
            schedule()
        }, 20_000L)
    }

    fun applyPing(r: PingResult) {
        if (r.ok) set(if (r.ms > ServerHealth.SLOW_MS) SrvState.SLOW else SrvState.ONLINE, r.ms, "")
        else set(SrvState.OFFLINE, r.ms, r.detail)
    }

    /** Цена рассчитана — значит, сайт отвечает. */
    fun reportOk(ms: Long) =
        set(if (ms > ServerHealth.SLOW_MS) SrvState.SLOW else SrvState.ONLINE, ms, "")

    /** Расчёт цены не удался: сайт лежит (5xx, не JSON, нет сети) или просто отклонил неверные данные. */
    fun reportError(e: Exception) {
        if (Api.isServerDown(e)) set(SrvState.OFFLINE, 0, Api.downReason(e))
        else set(SrvState.ONLINE, ServerHealth.ms, "")   // сайт ответил JSON-ом — он работает
    }

    private fun set(s: SrvState, ms: Long, detail: String) {
        val was = ServerHealth.state
        ServerHealth.update(s, ms, detail)
        render(s, ms, detail)
        onChange(s)
        if (was == SrvState.OFFLINE && s != SrvState.OFFLINE) onRecovered?.invoke()
    }

    // ---------- вид ----------

    private fun fmtMs(ms: Long) = if (ms >= 1000) "${ms / 100 / 10.0} с" else "$ms мс"

    private fun render(s: SrvState, ms: Long, detail: String) {
        val c = when (s) {
            SrvState.ONLINE -> a.GREEN
            SrvState.SLOW -> a.AMBER
            SrvState.OFFLINE -> a.RED
            SrvState.CHECKING -> a.MUTE
        }
        root.background = a.shape(a.mix(c, 0.12f), 18, a.edge(c))
        ring.background = a.shape(ColorUtils.setAlphaComponent(c, 48), 30)
        dot.background = a.shape(c, 30)
        status.setTextColor(if (s == SrvState.CHECKING) a.TEXT else c)
        a.popText(status, when (s) {
            SrvState.ONLINE -> "Работает"
            SrvState.SLOW -> "Работает медленно"
            SrvState.OFFLINE -> "Не работает"
            SrvState.CHECKING -> "Проверяем…"
        })
        sub.text = when (s) {
            SrvState.ONLINE ->
                if (ms > 0) "Ответ за ${fmtMs(ms)} · заказ можно оформлять" else "Сайт отвечает · заказ можно оформлять"
            SrvState.SLOW -> "Ответ за ${fmtMs(ms)} · заказ можно оформлять, но придётся подождать"
            SrvState.OFFLINE ->
                (detail.ifEmpty { "нет ответа" }.replaceFirstChar { it.uppercase() }) + " · заказ сейчас создать нельзя"
            SrvState.CHECKING -> "Связываемся с сайтом"
        }
    }
}

/** Прячет кнопку «Оплатить» «под стекло», пока сайт не работает. */
internal fun PayBar.setBlocked(blocked: Boolean) {
    button.alpha = if (blocked) 0.45f else 1f
}
