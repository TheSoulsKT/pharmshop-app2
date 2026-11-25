package ru.pharmshop.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Рамки аватара — те же, что в PWA: десять рамок с узорами (листья, лучи, огонь, кристаллы, лавры…), каждая открывается на своём уровне профиля
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

    /** Значки уровней — те же, что в PWA. */
    val ICONS = arrayOf("🌱", "👋", "🙂", "🏠", "⚡", "🔥", "🧠", "🛡️", "💎", "🏆")

    /** Сколько дней человек «с нами»: от регистрации в чате, а если сервер её не прислал — от первого просмотра уровня (как в PWA). */
    fun days(): Int {
        val t0 = Store.chatCreated.takeIf { it > 0 } ?: Store.levelSince()
        return ((System.currentTimeMillis() - t0) / 86_400_000L).toInt().coerceAtLeast(0)
    }

    /** Номер уровня 0..9 по дням «с нами». */
    fun level(): Int {
        val days = days()
        var i = 0
        DAYS.forEachIndexed { n, d -> if (days >= d) i = n }
        return i
    }

    /** Сколько процентов пути до следующего уровня пройдено (на последнем уровне — 100). */
    fun progress(): Int {
        val next = DAYS.getOrNull(level() + 1) ?: return 100
        return Math.min(100, Math.round(days() * 100f / next))
    }
}

/**
 * Место под рамку вокруг фото диаметром [sizeDp]. Рамка рисуется ВНУТРИ собственного квадрата аватарки
 * (а не вылезает наружу), поэтому лента чата её не обрезает и она не налезает на пузырь сообщения.
 */
internal fun framePadDp(sizeDp: Int): Int = max(4, (sizeDp * 0.18f).roundToInt())

/** Полный размер аватарки вместе с местом под рамку. */
internal fun frameOuterDp(sizeDp: Int): Int = sizeDp + 2 * framePadDp(sizeDp)

/**
 * Рамка аватара уровня [level] (1..10). Занимает весь свой квадрат; фото лежит по центру с радиусом
 * (сторона / 2 − [pad]). У каждой рамки свой узор: листья, пузырьки, лучи, кружево, зубцы, огонь, искры, клёпки, кристаллы, лавры с короной.
 */
internal class FrameView(ctx: Context, private val level: Int, private val pad: Float) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private var cx = 0f
    private var cy = 0f
    private var a = 0f      // радиус фото
    private var b = 0f      // ширина полосы под узор
    private val base = Frames.color(level)
    private val light = ColorUtils.blendARGB(base, Color.WHITE, 0.55f)
    private val dark = ColorUtils.blendARGB(base, Color.BLACK, 0.35f)

    override fun onDraw(c: Canvas) {
        val side = Math.min(width, height).toFloat()
        if (side <= 0f) return
        cx = width / 2f; cy = height / 2f
        b = pad
        a = side / 2f - pad
        // мягкое свечение под узором
        ring(c, a + b * 0.55f, b * 0.9f, ColorUtils.setAlphaComponent(base, 0x26))
        when (level) {
            1 -> leaves(c)
            2 -> bubbles(c)
            3 -> rays(c)
            4 -> lace(c)
            5 -> cog(c)
            6 -> flames(c)
            7 -> sparkles(c)
            8 -> steel(c)
            9 -> crystals(c)
            else -> legend(c)
        }
    }

    // ---------- помощники ----------

    private fun ring(c: Canvas, r: Float, w: Float, color: Int, shader: Shader? = null) {
        line.pathEffect = null; line.shader = shader; line.color = color; line.strokeWidth = w
        c.drawCircle(cx, cy, r, line)
        line.shader = null
    }

    private fun stroke(w: Float, color: Int) {
        line.pathEffect = null; line.shader = null; line.color = color; line.strokeWidth = w
    }

    private fun solid(color: Int) { fill.shader = null; fill.color = color }

    private fun sweep(vararg colors: Int): Shader = SweepGradient(cx, cy, colors, null)

    /** Повторяет рисунок [n] раз по кругу; сам рисунок рисуется «наверху» (в точке над центром). */
    private fun spin(c: Canvas, n: Int, offset: Float = 0f, block: (Int) -> Unit) {
        for (i in 0 until n) {
            c.save()
            c.rotate(offset + 360f * i / n, cx, cy)
            block(i)
            c.restore()
        }
    }

    private fun leaf(x: Float, y0: Float, len: Float, wd: Float) {
        path.reset()
        path.moveTo(x, y0)
        path.quadTo(x + wd, y0 - len * 0.55f, x, y0 - len)
        path.quadTo(x - wd, y0 - len * 0.55f, x, y0)
        path.close()
    }

    private fun sparkle(x: Float, y: Float, r: Float) {
        val k = r * 0.16f
        path.reset()
        path.moveTo(x, y - r)
        path.quadTo(x + k, y - k, x + r, y)
        path.quadTo(x + k, y + k, x, y + r)
        path.quadTo(x - k, y + k, x - r, y)
        path.quadTo(x - k, y - k, x, y - r)
        path.close()
    }

    private fun rhombus(x: Float, yBottom: Float, len: Float, wd: Float) {
        path.reset()
        path.moveTo(x, yBottom - len)
        path.lineTo(x + wd, yBottom - len * 0.45f)
        path.lineTo(x, yBottom)
        path.lineTo(x - wd, yBottom - len * 0.45f)
        path.close()
    }

    // ---------- 1. Новичок: росток с листьями ----------
    private fun leaves(c: Canvas) {
        val r0 = a + b * 0.2f
        ring(c, r0, b * 0.14f, dark)
        spin(c, 8, 22.5f) {
            leaf(cx, cy - r0, b * 0.5f, b * 0.14f); solid(light); c.drawPath(path, fill)
        }
        spin(c, 8) {
            c.save(); c.rotate(16f, cx, cy - r0)
            leaf(cx, cy - r0, b * 0.74f, b * 0.27f); solid(base); c.drawPath(path, fill)
            stroke(b * 0.05f, dark)    // прожилка
            c.drawLine(cx, cy - r0 - b * 0.06f, cx, cy - r0 - b * 0.62f, line)
            c.restore()
        }
    }

    // ---------- 2. Знакомый: пузырьки и пунктир ----------
    private fun bubbles(c: Canvas) {
        ring(c, a + b * 0.22f, b * 0.16f, base)
        spin(c, 12) { i ->
            solid(if (i % 2 == 0) light else base)
            c.drawCircle(cx, cy - (a + b * 0.62f), b * 0.2f, fill)
        }
        solid(ColorUtils.setAlphaComponent(light, 0xCC.toInt()))
        spin(c, 12, 15f) { c.drawCircle(cx, cy - (a + b * 0.8f), b * 0.1f, fill) }
        line.shader = null; line.color = ColorUtils.setAlphaComponent(base, 0x88); line.strokeWidth = b * 0.1f
        line.pathEffect = DashPathEffect(floatArrayOf(b * 0.12f, b * 0.3f), 0f)
        c.drawCircle(cx, cy, a + b * 0.94f, line)
        line.pathEffect = null
    }

    // ---------- 3. Свой: солнечные лучи ----------
    private fun rays(c: Canvas) {
        ring(c, a + b * 0.24f, b * 0.18f, base)
        stroke(b * 0.13f, base)
        spin(c, 16) { i ->
            line.color = if (i % 2 == 0) base else light
            val r2 = a + b * (if (i % 2 == 0) 0.94f else 0.72f)
            c.drawLine(cx, cy - (a + b * 0.42f), cx, cy - r2, line)
        }
    }

    // ---------- 4. Постоялец: кружево из дуг ----------
    private fun lace(c: Canvas) {
        ring(c, a + b * 0.14f, b * 0.12f, dark)
        stroke(b * 0.11f, base)
        spin(c, 14) { c.drawCircle(cx, cy - (a + b * 0.55f), b * 0.4f, line) }
        solid(light)
        spin(c, 14) { c.drawCircle(cx, cy - (a + b * 0.55f), b * 0.09f, fill) }
    }

    // ---------- 5. Бывалый: золотая шестерёнка ----------
    private fun cog(c: Canvas) {
        val n = 16
        val r1 = a + b * 0.32f
        val r2 = a + b * 0.98f
        val d = 2.0 * PI / n
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        for (i in 0 until n) {
            val t = d * i - PI / 2
            fun pt(r: Float, k: Double, first: Boolean) {
                val x = cx + (r * cos(t + d * k)).toFloat()
                val y = cy + (r * sin(t + d * k)).toFloat()
                if (first) path.moveTo(x, y) else path.lineTo(x, y)
            }
            pt(r1, 0.0, i == 0); pt(r2, 0.2, false); pt(r2, 0.5, false); pt(r1, 0.7, false)
        }
        path.close()
        path.addCircle(cx, cy, r1 * 0.97f, Path.Direction.CW)   // дырка под фото
        fill.shader = sweep(base, dark, light, base, dark, light, base)
        c.drawPath(path, fill)
        fill.shader = null
        path.fillType = Path.FillType.WINDING
        ring(c, a + b * 0.12f, b * 0.1f, base)
        ring(c, r1 + b * 0.12f, b * 0.07f, ColorUtils.setAlphaComponent(Color.WHITE, 0x90))
    }

    // ---------- 6. Опытный: пламя ----------
    private fun flames(c: Canvas) {
        val r0 = a + b * 0.18f
        ring(c, r0, b * 0.16f, dark)
        fun flame(len: Float, w: Float) {
            val y0 = cy - r0
            path.reset()
            path.moveTo(cx - w, y0)
            path.cubicTo(cx - w, y0 - len * 0.5f, cx - w * 0.2f, y0 - len * 0.6f, cx, y0 - len)
            path.cubicTo(cx + w * 0.2f, y0 - len * 0.6f, cx + w, y0 - len * 0.5f, cx + w, y0)
            path.close()
        }
        spin(c, 12) {
            c.save(); c.rotate(10f, cx, cy - r0)
            flame(b * 0.8f, b * 0.25f); solid(base); c.drawPath(path, fill)
            flame(b * 0.5f, b * 0.13f); solid(0xFFFFB74D.toInt()); c.drawPath(path, fill)
            c.restore()
        }
    }

    // ---------- 7. Знаток: переливы и искры ----------
    private fun sparkles(c: Canvas) {
        val rm = a + b * 0.5f
        ring(c, rm, b * 0.28f, base, sweep(base, 0xFFF48FB1.toInt(), light, 0xFF7E57C2.toInt(), base))
        val yBig = cy - rm
        spin(c, 4) { sparkle(cx, yBig, b * 0.58f); solid(Color.WHITE); c.drawPath(path, fill) }
        spin(c, 4, 45f) { sparkle(cx, yBig, b * 0.34f); solid(light); c.drawPath(path, fill) }
        solid(ColorUtils.setAlphaComponent(Color.WHITE, 0xC0))
        spin(c, 8, 22.5f) { c.drawCircle(cx, cy - rm, b * 0.07f, fill) }
    }

    // ---------- 8. Ветеран: стальная рамка с клёпками ----------
    private fun steel(c: Canvas) {
        val rm = a + b * 0.5f
        ring(c, rm, b * 0.34f, base, sweep(light, base, dark, base, light))
        ring(c, rm - b * 0.13f, b * 0.05f, ColorUtils.setAlphaComponent(Color.WHITE, 0xA0))
        solid(ColorUtils.setAlphaComponent(Color.WHITE, 0xE0))
        spin(c, 12, 15f) { c.drawCircle(cx, cy - rm, b * 0.07f, fill) }
        spin(c, 4) {
            val y = cy - rm
            path.reset()
            path.moveTo(cx, y - b * 0.46f); path.lineTo(cx + b * 0.3f, y)
            path.lineTo(cx, y + b * 0.46f); path.lineTo(cx - b * 0.3f, y); path.close()
            solid(light); c.drawPath(path, fill)
            stroke(b * 0.06f, dark); c.drawPath(path, line)
        }
    }

    // ---------- 9. Мастер: кристаллы ----------
    private fun crystals(c: Canvas) {
        val yb = cy - (a + b * 0.1f)
        spin(c, 12) { i ->
            rhombus(cx, yb, b * 0.9f, b * 0.3f)
            solid(if (i % 2 == 0) base else light); c.drawPath(path, fill)
            stroke(b * 0.04f, ColorUtils.setAlphaComponent(Color.WHITE, 0xB0))
            c.drawLine(cx, yb - b * 0.9f, cx, yb, line)           // грань посередине
        }
        ring(c, a + b * 0.1f, b * 0.1f, ColorUtils.setAlphaComponent(Color.WHITE, 0xA0))
    }

    // ---------- 10. Легенда: золото, лавры и корона ----------
    private fun legend(c: Canvas) {
        val rm = a + b * 0.3f
        val gold = 0xFFFFD700.toInt()
        ring(c, rm, b * 0.26f, gold, sweep(gold, 0xFFFFF59D.toInt(), 0xFFFFB300.toInt(), 0xFFFFF59D.toInt(), gold))
        for (s in intArrayOf(1, -1)) {
            for (k in 0..5) {
                c.save()
                c.rotate(s * (54f + k * 21f), cx, cy)
                val y0 = cy - (a + b * 0.3f)
                c.rotate(-s * 28f, cx, y0)
                leaf(cx, y0, b * 0.62f, b * 0.2f)
                solid(if (k % 2 == 0) 0xFFFFE082.toInt() else 0xFFFFB300.toInt())
                c.drawPath(path, fill)
                c.restore()
            }
        }
        // корона сверху
        val yb = cy - (a + b * 0.3f)
        val w = b * 0.6f
        val h = b * 0.6f
        path.reset()
        path.moveTo(cx - w, yb); path.lineTo(cx - w, yb - h * 0.55f); path.lineTo(cx - w * 0.5f, yb - h * 0.25f)
        path.lineTo(cx, yb - h); path.lineTo(cx + w * 0.5f, yb - h * 0.25f); path.lineTo(cx + w, yb - h * 0.55f)
        path.lineTo(cx + w, yb); path.close()
        solid(gold); c.drawPath(path, fill)
        stroke(b * 0.05f, 0xFFB26A00.toInt()); c.drawPath(path, line)
        solid(0xFFFF5252.toInt()); c.drawCircle(cx, yb - h * 0.38f, b * 0.09f, fill)
        solid(Color.WHITE)
        c.drawCircle(cx - w, yb - h * 0.55f, b * 0.08f, fill)
        c.drawCircle(cx, yb - h, b * 0.09f, fill)
        c.drawCircle(cx + w, yb - h * 0.55f, b * 0.08f, fill)
    }
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
