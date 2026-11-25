package ru.pharmshop.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils

/**
 * Рамки аватара — те же, что в PWA: десять цветных рамок, каждая открывается на своём уровне профиля
 * (уровень зависит от числа дней с регистрации в чате). Выбранную рамку видят в общем чате все.
 */
object Frames {
    val COLORS = intArrayOf(
        0, Color.parseColor("#7BC96F"), Color.parseColor("#4FC3F7"), Color.parseColor("#FFD54F"), Color.parseColor("#FF8A65"),
        Color.parseColor("#FFB300"), Color.parseColor("#FF5252"), Color.parseColor("#BA68C8"), Color.parseColor("#5C6BC0"),
        Color.parseColor("#26C6DA"), Color.parseColor("#FFD700")
    )
    val DAYS = intArrayOf(0, 1, 3, 7, 14, 30, 60, 100, 180, 365)
    val NAMES = arrayOf("Новичок", "Знакомый", "Свой", "Постоялец", "Бывалый", "Опытный", "Знаток", "Ветеран", "Мастер", "Легенда")

    /** Цвет рамки по номеру; у профиля без рамки — золотой, как в PWA. */
    fun color(n: Int): Int = if (n in 1..10) COLORS[n] else Color.parseColor("#FFD54F")

    /** Номер уровня 0..9 по дням с регистрации в чате. */
    fun level(): Int {
        val t0 = Store.chatCreated.takeIf { it > 0 } ?: System.currentTimeMillis()
        val days = ((System.currentTimeMillis() - t0) / 86_400_000L).toInt().coerceAtLeast(0)
        var i = 0
        DAYS.forEachIndexed { n, d -> if (days >= d) i = n }
        return i
    }
}

/** Рисует рамку вокруг круглой аватарки [box]: кольцо 2dp с зазором и мягкое свечение вокруг. Выходит за границы на 4–6dp. */
internal fun MainActivity.addFrameRing(box: FrameLayout, sizeDp: Int, color: Int) {
    box.clipChildren = false
    box.clipToPadding = false
    box.addView(View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(3), ColorUtils.setAlphaComponent(color, 0x44))
        }
        layoutParams = FrameLayout.LayoutParams(dp(sizeDp + 12), dp(sizeDp + 12), Gravity.CENTER)
    }, 0)
    box.addView(View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(2), color)
        }
        layoutParams = FrameLayout.LayoutParams(dp(sizeDp + 8), dp(sizeDp + 8), Gravity.CENTER)
    }, 1)
    // чтобы кольцо не обрезалось родителями
    box.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            var p = v.parent
            var n = 0
            while (p is ViewGroup && n < 3) { p.clipChildren = false; p = p.parent; n++ }
        }
        override fun onViewDetachedFromWindow(v: View) {}
    })
}

/** Окно «Рамка аватара»: сетка из «Без рамки» и десяти рамок; закрытые помечены замком. */
internal fun MainActivity.frameSheet(body: LinearLayout, sid: Int, user: String) {
    if (Store.chatNick.isEmpty()) { toast("Сначала выберите ник в чате: рамку видят в нём"); return }
    io.execute {
        try { ChatApi.chatMe().optLong("uid").let { if (it > 0) CommunityState.myUid = it } } catch (e: Exception) { /* покажем по сохранённым данным */ }
        ui { openFrameSheet(body, sid) }
    }
}

private fun MainActivity.openFrameSheet(body: LinearLayout, sid: Int) {
    val level = Frames.level()
    val cur = Store.chatFrame
    sheet("Рамка аватара", scroll = true) { box, close ->
        box.addView(txt("Каждый уровень открывает новую рамку. Её видят в общем чате все.", 13f, MUTE).apply { layoutParams = lp(12) })
        val grid = GridLayout(this).apply { columnCount = 3; layoutParams = lp(8) }
        for (n in 0..10) {
            val locked = n > 0 && level < n - 1
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = ripple(CARD, 16, if (n == cur) BLUE else LINE)
                setPadding(dp(4), dp(14), dp(4), dp(10))
                alpha = if (locked) 0.5f else 1f
                isClickable = true
                setOnClickListener {
                    if (locked) { toast("Откроется на уровне $n «${Frames.NAMES[n - 1]}»"); return@setOnClickListener }
                    io.execute {
                        try {
                            Store.chatFrame = ChatApi.frameSet(n)
                            ui { close(); toast(if (n > 0) "Рамка применена" else "Рамка снята"); if (sid == screenId) renderProfile(body, sid) }
                        } catch (e: Exception) { ui { toast(e.message ?: "Не удалось сменить рамку") } }
                    }
                }
                pressable(this)
                layoutParams = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                    width = 0; setMargins(dp(4), dp(4), dp(4), dp(4))
                }
            }
            card.addView(chatAvatar(CommunityState.myUid, Store.chatNick, 0L, 46, n).apply {
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(8)
            })
            card.addView(txt(if (n > 0) Frames.NAMES[n - 1] else "Без рамки", 13f, TEXT, true).apply { gravity = Gravity.CENTER })
            card.addView(txt(if (n == 0) "—" else if (locked) "🔒 ур. $n" else "Ур. $n", 11f, MUTE).apply { gravity = Gravity.CENTER })
            grid.addView(card)
        }
        box.addView(grid)
    }
}
