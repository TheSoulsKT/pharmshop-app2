package ru.pharmshop.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * Сцена питомца: кот в полный рост, его наряд, домик и миска. Всё рисуется на Canvas в условных координатах 320×240,
 * поэтому масштабируется под любой экран. Наряд, домик и миска выбираются по id, которые присылает сервер
 * (PET_LEVELS в server/worker.js): outfit — none, bandana, scarf, hoodie, vest, cape, bowtie, jacket, royal, crown;
 * house — box, box_soft, basket, bed, tent, wood, cottage, villa, tower, castle;
 * bowl — plastic, tin, ceramic, ceramic_fish, blue, silver, silver_gems, gold, gold_gems, diamond.
 *
 * Анимации: дыхание, виляние хвостом, моргание, еда (голова наклоняется к миске, пока VPN кормит кота), сердечки у сытого кота,
 * пузырь «хочу есть» у голодного. Нажатие на кота — прыжок и сердечки; celebrate() — конфетти (новый уровень).
 * Если анимации в приложении выключены, рисуется один неподвижный кадр.
 */
internal class PetStageView(ctx: Context, private val animOn: Boolean) : View(ctx) {
    private class Fur(val body: Int, val belly: Int, val ear: Int, val eye: Int, val mark: Int, val kind: Int)

    private fun hx(v: Long) = v.toInt()

    // шерсть по значку приложения: Классика, Рыжик, Снежок, Дымка, Сиам, Пятнашка
    private val furs = listOf(
        Fur(hx(0xFF2B2B33), hx(0xFF3C3C47), hx(0xFFF4A6B5), hx(0xFFF5C542), 0, 0),
        Fur(hx(0xFFF28C28), hx(0xFFFFE9CC), hx(0xFFF9B7A0), hx(0xFF6BCB3F), 0, 0),
        Fur(hx(0xFFF6F6FA), hx(0xFFE4E6F2), hx(0xFFF9B7C8), hx(0xFF3FA9F5), 0, 0),
        Fur(hx(0xFF8A93A3), hx(0xFFC7CCD6), hx(0xFFE8A7B2), hx(0xFF8BE05A), 0, 0),
        Fur(hx(0xFFF2E2C6), hx(0xFFFBF1DE), hx(0xFFC98F86), hx(0xFF4FA3FF), hx(0xFF6B4A3A), 1),
        Fur(hx(0xFFF6F6FA), hx(0xFFE4E6F2), hx(0xFFF9B7C8), hx(0xFFE0A030), hx(0xFFF29B38), 2)
    )

    private var level = 1
    private var outfit = "none"
    private var house = "box"
    private var bowl = "plastic"
    private var mood = "ok"          // full | ok | hungry | starving
    private var fill = 0.5f          // сколько еды в миске, 0..1
    private var eating = false
    private var accent = hx(0xFF4F8CFF)
    private var furIdx = 0

    /** Нажали на кота. */
    var onPet: (() -> Unit)? = null

    fun setup(level: Int, outfit: String, house: String, bowl: String, mood: String, fill: Float, eating: Boolean, accent: Int, fur: Int) {
        this.level = level; this.outfit = outfit; this.house = house; this.bowl = bowl
        this.mood = mood; this.fill = fill.coerceIn(0f, 1f); this.eating = eating
        this.accent = accent; this.furIdx = fur.coerceIn(0, furs.size - 1)
        invalidate()
    }

    // ---- время и частицы ----
    private var t = 0f
    private var lastNs = 0L
    private var bounceAt = -10f
    private var celebrateAt = -10f
    private var nextHeart = 1.5f
    private val hearts = ArrayList<FloatArray>()   // x, y, родился, снос по x

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val n = System.nanoTime()
            if (lastNs != 0L) t += ((n - lastNs) / 1e9f).coerceAtMost(0.1f)
            lastNs = n
            invalidate()
        }
    }

    private fun startTicker() { if (animOn && !ticker.isRunning) { lastNs = 0L; ticker.start() } }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); startTicker() }
    override fun onDetachedFromWindow() { ticker.cancel(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) startTicker() else ticker.cancel()
    }

    /** Конфетти: кот получил новый уровень. */
    fun celebrate() { celebrateAt = t; if (!animOn) invalidate() }

    private fun spawnHearts(n: Int) {
        for (i in 0 until n) hearts.add(floatArrayOf(150f + 30f * i + (i % 2) * 8f, 70f, t + i * 0.12f, if (i % 2 == 0) -10f else 12f))
    }

    // ---- кисть и помощники ----
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    private fun Canvas.oval(cx: Float, cy: Float, rx: Float, ry: Float, color: Int) {
        p.style = Paint.Style.FILL; p.color = color
        rect.set(cx - rx, cy - ry, cx + rx, cy + ry)
        drawOval(rect, p)
    }

    private fun Canvas.rrect(l: Float, tp: Float, r: Float, b: Float, rad: Float, color: Int) {
        p.style = Paint.Style.FILL; p.color = color
        rect.set(l, tp, r, b)
        drawRoundRect(rect, rad, rad, p)
    }

    private fun Canvas.poly(color: Int, vararg pts: Float) {
        path.reset()
        path.moveTo(pts[0], pts[1])
        var i = 2
        while (i + 1 < pts.size) { path.lineTo(pts[i], pts[i + 1]); i += 2 }
        path.close()
        p.style = Paint.Style.FILL; p.color = color
        drawPath(path, p)
    }

    private fun Canvas.line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, w: Float) {
        p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = w; p.strokeCap = Paint.Cap.ROUND
        drawLine(x1, y1, x2, y2, p)
        p.style = Paint.Style.FILL
    }

    private fun Canvas.arc(cx: Float, cy: Float, rx: Float, ry: Float, from: Float, sweep: Float, color: Int, w: Float) {
        p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = w; p.strokeCap = Paint.Cap.ROUND
        rect.set(cx - rx, cy - ry, cx + rx, cy + ry)
        drawArc(rect, from, sweep, false, p)
        p.style = Paint.Style.FILL
    }

    private fun Canvas.heart(cx: Float, cy: Float, s: Float, color: Int) {
        path.reset()
        path.moveTo(cx, cy + s * 0.9f)
        path.cubicTo(cx - s * 1.5f, cy - s * 0.1f, cx - s * 0.7f, cy - s * 1.2f, cx, cy - s * 0.35f)
        path.cubicTo(cx + s * 0.7f, cy - s * 1.2f, cx + s * 1.5f, cy - s * 0.1f, cx, cy + s * 0.9f)
        p.style = Paint.Style.FILL; p.color = color
        drawPath(path, p)
    }

    private fun Canvas.star(cx: Float, cy: Float, s: Float, color: Int) {
        poly(color, cx, cy - s, cx + s * 0.28f, cy - s * 0.28f, cx + s, cy, cx + s * 0.28f, cy + s * 0.28f,
            cx, cy + s, cx - s * 0.28f, cy + s * 0.28f, cx - s, cy, cx - s * 0.28f, cy - s * 0.28f)
    }

    private fun withAlpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)
    private fun fract(x: Float) = x - kotlin.math.floor(x)

    // ---- рисование ----
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val s = min(w / 320f, h / 240f)
        canvas.save()
        canvas.translate((w - 320f * s) / 2f, (h - 240f * s) / 2f)
        canvas.scale(s, s)

        // свечение за котом и пол
        p.shader = RadialGradient(165f, 140f, 170f, withAlpha(accent, 70), withAlpha(accent, 0), Shader.TileMode.CLAMP)
        canvas.drawRect(-40f, -20f, 360f, 260f, p)
        p.shader = null
        canvas.oval(160f, 201f, 156f, 13f, withAlpha(accent, 46))
        canvas.oval(160f, 201f, 120f, 8f, withAlpha(accent, 40))
        drawSparkles(canvas)

        drawHouse(canvas)
        drawCat(canvas)
        drawBowl(canvas)
        drawEffects(canvas)
        canvas.restore()
    }

    /** Чем выше уровень, тем больше мерцающих звёздочек вокруг. */
    private fun drawSparkles(c: Canvas) {
        val n = (level - 1) / 2 * 3
        for (i in 0 until n) {
            val x = 20f + fract(sin(i * 12.9898f) * 43758.547f) * 280f
            val y = 14f + fract(sin(i * 78.233f) * 12345.678f) * 120f
            val a = (0.5f + 0.5f * sin(t * 2f + i * 1.7f))
            c.star(x, y, 3f + 3f * a, withAlpha(accent, (60 + 150 * a).toInt()))
        }
    }

    // ================== кот ==================
    private fun drawCat(c: Canvas) {
        val f = furs[furIdx]
        val starving = mood == "starving"
        val hungry = mood == "hungry"
        val happy = mood == "full"
        val speed = if (starving) 0.5f else 1f

        val bp = ((t - bounceAt) / 0.6f)
        val jump = if (bp in 0f..1f) -22f * sin(bp * PI.toFloat()) else 0f
        val breathe = sin(t * 2.2f * speed) * 1.2f
        val sag = if (starving) 5f else 0f
        c.save()
        c.translate(0f, jump + sag * 0.5f)

        c.oval(168f, 199f, 62f, 6f, hx(0x33000000))   // тень

        // плащ и мантия за спиной
        drawBackWear(c)

        // хвост
        val sway = if (happy) sin(t * 5.5f) * 14f else sin(t * 2.2f * speed) * (if (starving) 3f else 10f)
        val tailEndY = if (starving) 176f else 134f + sway * 0.3f
        val tailEndX = if (starving) 246f else 248f + sway * 0.7f
        path.reset()
        path.moveTo(212f, 180f)
        path.cubicTo(240f, 188f, 258f, 166f + (if (starving) 8f else 0f), tailEndX, tailEndY)
        p.style = Paint.Style.STROKE; p.strokeWidth = 12f; p.strokeCap = Paint.Cap.ROUND
        p.color = if (f.kind == 1) f.mark else f.body
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
        if (f.kind == 2) c.oval(tailEndX, tailEndY, 6f, 6f, f.mark)

        // туловище
        c.oval(168f, 168f + breathe * 0.4f, 54f, 34f + breathe * 0.3f, f.body)
        c.oval(206f, 182f, 21f, 17f, f.body)                    // задняя лапа
        c.oval(214f, 196f, 12f, 6f, if (f.kind == 1) f.mark else f.body)
        c.oval(160f, 178f, 30f, 19f, f.belly)
        c.oval(150f, 184f, 10f, 16f, f.body)                    // передние лапы
        c.oval(182f, 184f, 10f, 16f, f.body)
        val paw = if (f.kind == 1) f.mark else f.belly
        c.oval(150f, 196f, 12f, 6f, paw)
        c.oval(182f, 196f, 12f, 6f, paw)

        drawBodyWear(c)

        // голова: при еде наклоняется к миске, у голодного опущена
        val tilt = when {
            eating -> 14f + 10f * (0.5f + 0.5f * sin(t * 6f))
            starving -> 10f
            hungry -> 4f
            else -> sin(t * 1.3f) * 2f
        }
        c.save()
        c.translate(if (eating) 6f else 0f, if (starving) 6f else 0f)
        c.rotate(tilt, 162f, 150f)
        drawHead(c, f, happy, hungry, starving)
        drawHeadWear(c)
        c.restore()

        drawNeckWear(c)
        c.restore()
    }

    private fun drawHead(c: Canvas, f: Fur, happy: Boolean, hungry: Boolean, starving: Boolean) {
        val earOuter = if (f.kind == 1) f.mark else f.body
        val earTwitch = if (sin(t * 0.9f) > 0.96f) 3f else 0f
        c.poly(if (f.kind == 2) f.mark else earOuter, 130f, 104f, 124f, 64f + earTwitch, 154f, 88f)
        c.poly(earOuter, 194f, 104f, 200f, 64f, 170f, 88f)
        c.poly(f.ear, 134f, 98f, 131f, 76f + earTwitch, 148f, 89f)
        c.poly(f.ear, 190f, 98f, 193f, 76f, 176f, 89f)

        c.oval(162f, 118f, 41f, 34f, f.body)
        if (f.kind == 1) c.oval(162f, 128f, 21f, 17f, withAlpha(f.mark, 210))
        if (f.kind == 2) c.oval(143f, 112f, 20f, 18f, f.mark)
        c.oval(162f, 131f, 15f, 10f, if (f.kind == 1) withAlpha(f.belly, 240) else f.belly)

        if (happy) {   // румянец
            c.oval(138f, 130f, 7f, 4f, withAlpha(hx(0xFFFF7A93), 110))
            c.oval(186f, 130f, 7f, 4f, withAlpha(hx(0xFFFF7A93), 110))
        }

        // глаза
        val blink = (t % 4.3f) > 4.12f
        val look = if (eating) 2.5f else 0f
        val ink = hx(0xFF15151A)
        for (k in 0..1) {
            val ex = if (k == 0) 146f else 178f
            val ey = 116f
            if (blink || (happy && !eating)) {
                c.arc(ex, ey + 2f, 7f, 6f, 200f, 140f, ink, 3f)
            } else if (eating) {
                c.arc(ex, ey + 1f, 7f, 5f, 20f, 140f, ink, 3f)   // «ам-ам»: глаза зажмурены
            } else {
                val ry = if (hungry) 9.5f else 8.5f
                c.oval(ex, ey, 7.5f, ry, f.eye)
                c.oval(ex + look, ey + 1f, 3.4f, ry - 2f, ink)
                c.oval(ex - 2f + look, ey - 3f, 2.2f, 2.2f, Color.WHITE)
                if (starving) c.oval(ex, ey - 4f, 9f, 6f, f.body)   // тяжёлые веки
            }
            if (hungry || starving) {   // грустные брови
                val dir = if (k == 0) 1f else -1f
                c.line(ex - 8f * dir, ey - 11f, ex + 6f * dir, ey - 16f, ink, 2.5f)
            }
        }
        // нос, рот, усы
        c.poly(hx(0xFFFF7A93), 158f, 126f, 166f, 126f, 162f, 131f)
        if (eating) {
            c.oval(162f, 139f, 5f, 4f + 2f * (0.5f + 0.5f * sin(t * 6f)), hx(0xFF8B2E3E))
        } else if (starving) {
            c.arc(156f, 141f, 6f, 5f, 200f, 120f, ink, 2f)
            c.arc(168f, 141f, 6f, 5f, 220f, 120f, ink, 2f)
        } else if (hungry) {
            c.oval(162f, 138f, 3f, 3.5f, hx(0xFF8B2E3E))
        } else {
            c.arc(156f, 133f, 6f, 5f, 20f, 120f, ink, 2f)
            c.arc(168f, 133f, 6f, 5f, 40f, 120f, ink, 2f)
        }
        val wc = withAlpha(hx(0xFF8A8FA0), 170)
        c.line(140f, 131f, 112f, 126f, wc, 1.6f); c.line(140f, 135f, 112f, 137f, wc, 1.6f)
        c.line(184f, 131f, 212f, 126f, wc, 1.6f); c.line(184f, 135f, 212f, 137f, wc, 1.6f)
    }

    // ---- наряды ----
    private fun drawBackWear(c: Canvas) {
        when (outfit) {
            "cape" -> {
                c.poly(hx(0xFF8B5CF6), 140f, 148f, 112f, 202f, 218f, 202f, 190f, 148f)
                c.poly(hx(0xFF6D3FE0), 150f, 160f, 132f, 202f, 150f, 202f)
            }
            "royal", "crown" -> {
                val col = if (outfit == "crown") hx(0xFF7A1FA2) else hx(0xFFC2263F)
                c.poly(col, 136f, 148f, 104f, 204f, 224f, 204f, 192f, 148f)
                c.rrect(104f, 198f, 224f, 205f, 3f, hx(0xFFF6F2EA))   // горностаевая кайма
                var x = 112f
                while (x < 220f) { c.oval(x, 201.5f, 2f, 1.6f, hx(0xFF15151A)); x += 18f }
                if (outfit == "crown") c.rrect(104f, 196f, 224f, 199f, 1.5f, hx(0xFFFFC83D))
            }
            "hoodie" -> c.oval(162f, 142f, 44f, 17f, hx(0xFF5B3FD9))   // капюшон за головой
        }
    }

    private fun drawBodyWear(c: Canvas) {
        when (outfit) {
            "hoodie" -> {
                c.oval(168f, 170f, 52f, 31f, hx(0xFF7C5CFF))
                c.rrect(138f, 176f, 194f, 196f, 8f, hx(0xFF6747E8))   // карман
            }
            "vest" -> {
                c.oval(168f, 172f, 42f, 27f, hx(0xFF2FBF8A))
                for (i in 0..2) c.oval(162f, 160f + i * 11f, 2.6f, 2.6f, hx(0xFFFFF3C4))
            }
            "jacket" -> {
                c.oval(168f, 170f, 52f, 31f, hx(0xFF5B6B8C))
                c.line(162f, 150f, 162f, 198f, hx(0xFFC9D1E3), 2f)
                c.rrect(176f, 178f, 198f, 190f, 4f, hx(0xFF4A5976))
            }
            "royal", "crown" -> {
                c.oval(168f, 172f, 36f, 22f, if (outfit == "crown") hx(0xFF8E2BB8) else hx(0xFFD6334C))
            }
        }
    }

    private fun drawNeckWear(c: Canvas) {
        when (outfit) {
            "bandana" -> {
                c.poly(hx(0xFFE5484D), 134f, 145f, 190f, 145f, 162f, 175f)
                for (i in 0..2) c.oval(150f + i * 12f, 152f + (i % 2) * 8f, 2.2f, 2.2f, Color.WHITE)
            }
            "scarf" -> {
                c.rrect(128f, 142f, 196f, 157f, 7f, hx(0xFF3FA9F5))
                for (i in 0..3) c.rrect(138f + i * 14f, 142f, 142f + i * 14f, 157f, 2f, Color.WHITE)
                c.rrect(176f, 150f, 190f, 184f, 5f, hx(0xFF3FA9F5))
                c.rrect(176f, 160f, 190f, 164f, 1f, Color.WHITE)
                c.rrect(176f, 172f, 190f, 176f, 1f, Color.WHITE)
            }
            "bowtie" -> {
                c.poly(hx(0xFFE5484D), 162f, 154f, 140f, 144f, 140f, 164f)
                c.poly(hx(0xFFE5484D), 162f, 154f, 184f, 144f, 184f, 164f)
                c.oval(162f, 154f, 5f, 5f, hx(0xFFB3262B))
            }
            "jacket" -> {
                for (i in 0..6) c.oval(130f + i * 10.5f, 150f, 9f, 8f, hx(0xFFF6F6FA))   // меховой воротник
                for (i in 0..5) c.oval(135f + i * 10.5f, 155f, 8f, 6f, hx(0xFFE4E6F2))
            }
            "royal", "crown" -> {
                for (i in 0..6) c.oval(130f + i * 10.5f, 151f, 8.5f, 7f, hx(0xFFF6F2EA))   // горностаевый воротник
                for (i in 0..2) c.oval(140f + i * 22f, 152f, 1.8f, 2.6f, hx(0xFF15151A))
                c.oval(162f, 160f, 4f, 4f, hx(0xFFFFC83D))
            }
            "hoodie" -> {
                c.line(154f, 152f, 152f, 170f, Color.WHITE, 2f)
                c.line(170f, 152f, 172f, 170f, Color.WHITE, 2f)
            }
            "cape" -> c.oval(162f, 150f, 5f, 5f, hx(0xFFFFC83D))
        }
    }

    private fun drawHeadWear(c: Canvas) {
        when (outfit) {
            "bowtie" -> {   // цилиндр
                c.oval(162f, 90f, 30f, 6f, hx(0xFF1B1B20))
                c.rrect(146f, 56f, 178f, 90f, 3f, hx(0xFF1B1B20))
                c.rrect(146f, 78f, 178f, 85f, 0f, hx(0xFFE5484D))
            }
            "royal" -> {   // маленький обруч
                c.rrect(138f, 84f, 186f, 91f, 3f, hx(0xFFFFC83D))
                c.oval(162f, 87.5f, 3f, 3f, hx(0xFFE5484D))
            }
            "crown" -> {
                c.poly(hx(0xFFFFC83D), 134f, 90f, 130f, 58f, 148f, 74f, 162f, 52f, 176f, 74f, 194f, 58f, 190f, 90f)
                c.rrect(134f, 84f, 190f, 92f, 2f, hx(0xFFE0A800))
                c.oval(162f, 79f, 3.6f, 3.6f, hx(0xFFE5484D))
                c.oval(146f, 80f, 2.8f, 2.8f, hx(0xFF3FA9F5))
                c.oval(178f, 80f, 2.8f, 2.8f, hx(0xFF3FA9F5))
                val a = (0.5f + 0.5f * sin(t * 3f))
                c.star(162f, 50f, 3f + 2f * a, withAlpha(Color.WHITE, (120 + 120 * a).toInt()))
            }
            "bandana" -> {}
        }
    }

    // ================== домик ==================
    private fun drawHouse(c: Canvas) {
        val g = 198f
        when (house) {
            "box" -> drawBox(c, false)
            "box_soft" -> drawBox(c, true)
            "basket" -> {
                c.oval(66f, 190f, 56f, 14f, hx(0xFFB8812E))
                c.rrect(14f, 152f, 118f, 198f, 20f, hx(0xFFD9A441))
                for (i in 0..3) c.arc(66f, 170f + i * 8f, 50f, 8f, 20f, 140f, hx(0xFFB8812E), 2f)
                for (i in 0..6) c.line(24f + i * 14f, 156f, 24f + i * 14f, 196f, withAlpha(hx(0xFFB8812E), 150), 2f)
                c.oval(66f, 153f, 52f, 8f, hx(0xFFFF8FA8))   // подушка
                c.oval(66f, 151f, 44f, 5f, hx(0xFFFFB3C4))
            }
            "bed" -> {
                c.oval(66f, 190f, 58f, 13f, hx(0xFF2F5DD1))
                c.oval(66f, 186f, 54f, 11f, hx(0xFF4F8CFF))
                c.oval(66f, 186f, 40f, 7f, hx(0xFFA9C4FF))
                c.rrect(96f, 172f, 120f, 190f, 9f, hx(0xFFFFB3C4))   // валик
            }
            "tent" -> {
                c.poly(hx(0xFFFF7A3D), 66f, 112f, 14f, g, 122f, g)
                c.poly(hx(0xFFFFB38A), 66f, 112f, 40f, g, 66f, g)
                c.poly(hx(0xFF3A2418), 66f, 148f, 48f, g, 84f, g)
                c.line(66f, 112f, 66f, 98f, hx(0xFF8B5A2B), 2f)
                c.poly(hx(0xFFE5484D), 66f, 98f, 82f, 103f, 66f, 108f)
            }
            "wood" -> {
                c.rrect(26f, 152f, 110f, g, 2f, hx(0xFF9A6B3F))
                for (i in 0..4) c.line(26f, 162f + i * 8f, 110f, 162f + i * 8f, hx(0xFF7D5430), 1.5f)
                c.poly(hx(0xFFB84A3A), 14f, 154f, 68f, 112f, 122f, 154f)
                c.oval(68f, 178f, 16f, 18f, hx(0xFF2A1A10))
                c.rrect(88f, 164f, 102f, 178f, 2f, hx(0xFFFFE9A8))
            }
            "cottage" -> {
                c.rrect(20f, 140f, 116f, g, 3f, hx(0xFFF1E2C6))
                c.rrect(88f, 112f, 104f, 138f, 2f, hx(0xFFB66A4C))   // труба
                val sm = fract(t * 0.4f)
                c.oval(96f + sm * 10f, 106f - sm * 22f, 4f + sm * 4f, 4f + sm * 4f, withAlpha(hx(0xFFFFFFFF), (180 * (1f - sm)).toInt()))
                c.poly(hx(0xFF7A3E2B), 10f, 144f, 68f, 104f, 126f, 144f)
                c.rrect(52f, 164f, 80f, g, 14f, hx(0xFF6B4226))
                c.rrect(28f, 156f, 46f, 174f, 3f, hx(0xFFFFE08A))
                c.rrect(90f, 156f, 108f, 174f, 3f, hx(0xFFFFE08A))
                c.oval(34f, 196f, 5f, 4f, hx(0xFFFF7A93)); c.oval(44f, 197f, 4f, 3f, hx(0xFFFFC83D))
            }
            "villa" -> {
                c.rrect(8f, 132f, 126f, g, 4f, hx(0xFFF5F5F7))
                c.rrect(4f, 124f, 130f, 134f, 3f, hx(0xFF3A3F55))
                c.rrect(16f, 142f, 42f, 166f, 3f, hx(0xFF8EC9F5)); c.rrect(94f, 142f, 120f, 166f, 3f, hx(0xFF8EC9F5))
                c.rrect(16f, 170f, 36f, 190f, 3f, hx(0xFF8EC9F5)); c.rrect(98f, 170f, 118f, 190f, 3f, hx(0xFF8EC9F5))
                c.rrect(52f, 156f, 82f, g, 4f, hx(0xFFFFC83D))
                c.rrect(44f, 142f, 90f, 148f, 2f, hx(0xFFD6DAE6))
                c.oval(20f, 120f, 10f, 8f, hx(0xFF2FBF8A)); c.oval(112f, 120f, 10f, 8f, hx(0xFF2FBF8A))
            }
            "tower" -> {
                c.rrect(36f, 76f, 100f, g, 2f, hx(0xFF9AA0B5))
                for (i in 0..5) c.line(36f, 90f + i * 18f, 100f, 90f + i * 18f, hx(0xFF7F86A0), 1.5f)
                c.poly(hx(0xFF4F46E5), 30f, 80f, 68f, 36f, 106f, 80f)
                val wv = sin(t * 3f) * 3f
                c.line(68f, 36f, 68f, 18f, hx(0xFF8B5A2B), 2f)
                c.poly(hx(0xFFE5484D), 68f, 18f, 86f, 23f + wv, 68f, 28f)
                c.rrect(60f, 98f, 76f, 120f, 8f, hx(0xFFFFE08A))
                c.rrect(52f, 164f, 84f, g, 16f, hx(0xFF4A2F1C))
            }
            "castle" -> {
                c.rrect(40f, 98f, 96f, g, 2f, hx(0xFFB8BED6))
                c.rrect(6f, 122f, 40f, g, 2f, hx(0xFFA6ADC8)); c.rrect(96f, 122f, 130f, g, 2f, hx(0xFFA6ADC8))
                c.poly(hx(0xFFE5484D), 2f, 126f, 23f, 90f, 44f, 126f)
                c.poly(hx(0xFFE5484D), 92f, 126f, 113f, 90f, 134f, 126f)
                var x = 42f
                while (x < 94f) { c.rrect(x, 90f, x + 8f, 100f, 1f, hx(0xFFB8BED6)); x += 13f }
                val wv = sin(t * 3f) * 3f
                c.line(68f, 90f, 68f, 62f, hx(0xFF8B5A2B), 2f)
                c.poly(hx(0xFFFFC83D), 68f, 62f, 90f, 68f + wv, 68f, 74f)
                c.rrect(54f, 150f, 82f, g, 14f, hx(0xFF3A2418))
                c.rrect(16f, 140f, 26f, 154f, 4f, hx(0xFFFFE08A)); c.rrect(110f, 140f, 120f, 154f, 4f, hx(0xFFFFE08A))
                c.rrect(62f, 114f, 74f, 130f, 6f, hx(0xFFFFE08A))
            }
        }
    }

    private fun drawBox(c: Canvas, soft: Boolean) {
        val g = 198f
        c.poly(hx(0xFFB88A55), 14f, 142f, 6f, 124f, 40f, 132f, 40f, 142f)   // открытые клапаны
        c.poly(hx(0xFFB88A55), 118f, 142f, 126f, 124f, 92f, 132f, 92f, 142f)
        c.rrect(14f, 140f, 118f, g, 3f, hx(0xFFC8A06B))
        c.rrect(14f, 140f, 118f, 150f, 2f, hx(0xFFD8B27F))
        c.rrect(60f, 140f, 72f, 168f, 0f, hx(0xFFE9D3A8))   // скотч
        c.oval(66f, 186f, 25f, 22f, hx(0xFF3A2A1A))          // вход
        c.rrect(41f, 186f, 91f, g, 0f, hx(0xFF3A2A1A))
        if (soft) {
            c.oval(66f, 194f, 28f, 7f, hx(0xFFFF8FA8))
            c.oval(66f, 192f, 22f, 4f, hx(0xFFFFB3C4))
            c.rrect(96f, 176f, 112f, 196f, 3f, hx(0xFF8EC9F5))   // одеяльце
        }
    }

    // ================== миска ==================
    private fun drawBowl(c: Canvas) {
        val cx = 268f
        val rimY = 172f
        val base = bowlColor()
        val dark = ColorBlend.dark(base)
        // тень и корпус
        c.oval(cx, 199f, 40f, 5f, hx(0x30000000))
        path.reset()
        path.moveTo(cx - 34f, rimY)
        path.lineTo(cx - 23f, 196f)
        path.quadTo(cx, 202f, cx + 23f, 196f)
        path.lineTo(cx + 34f, rimY)
        path.close()
        p.style = Paint.Style.FILL; p.color = base
        c.drawPath(path, p)
        c.oval(cx, rimY, 34f, 8f, dark)
        // еда
        val food = fill
        c.oval(cx, rimY, 30f, 6f, hx(0xFF3B2A1C))
        if (food > 0.02f) {
            val mound = 3f + 13f * food
            path.reset()
            path.moveTo(cx - 29f, rimY)
            path.quadTo(cx, rimY - mound * 2f, cx + 29f, rimY)
            path.close()
            p.color = hx(0xFF9B6A3A)
            c.drawPath(path, p)
            val n = 4 + (food * 8f).toInt()
            for (i in 0 until n) {
                val fx = cx - 22f + fract(i * 0.618f) * 44f
                val fy = rimY - 2f - fract(i * 0.37f + 0.1f) * mound * (1f - abs(fx - cx) / 36f)
                c.oval(fx, fy, 2.6f, 2f, if (i % 2 == 0) hx(0xFFB57D45) else hx(0xFF7A5128))
            }
            if (food > 0.35f && animOn) {   // запах вкусного
                for (i in 0..1) {
                    val ph = fract(t * 0.5f + i * 0.5f)
                    c.arc(cx - 8f + i * 16f, rimY - mound - 8f - ph * 16f, 4f, 5f, 90f, 200f, withAlpha(Color.WHITE, (150 * (1f - ph)).toInt()), 1.6f)
                }
            }
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = dark
        rect.set(cx - 34f, rimY - 8f, cx + 34f, rimY + 8f)
        c.drawArc(rect, 0f, 180f, false, p)          // передний борт поверх еды
        p.style = Paint.Style.FILL

        // украшения по виду миски
        when (bowl) {
            "tin", "silver", "silver_gems" -> c.arc(cx - 14f, 186f, 8f, 8f, 100f, 60f, withAlpha(Color.WHITE, 190), 2.5f)
            "ceramic" -> c.rrect(cx - 29f, 183f, cx + 29f, 187f, 2f, hx(0xFF4F8CFF))
            "ceramic_fish" -> {
                c.oval(cx - 3f, 186f, 8f, 4.5f, hx(0xFF3FA9F5))
                c.poly(hx(0xFF3FA9F5), cx + 4f, 186f, cx + 11f, 181f, cx + 11f, 191f)
                c.oval(cx - 7f, 185f, 1.2f, 1.2f, Color.WHITE)
            }
            "blue" -> c.arc(cx - 14f, 186f, 8f, 8f, 100f, 60f, withAlpha(Color.WHITE, 200), 2.5f)
            "gold" -> c.arc(cx - 14f, 186f, 8f, 8f, 100f, 60f, withAlpha(Color.WHITE, 200), 2.5f)
        }
        val gems = when (bowl) {
            "silver_gems" -> intArrayOf(hx(0xFF3FA9F5), hx(0xFF2FBF8A), hx(0xFFEC4899))
            "gold_gems" -> intArrayOf(hx(0xFFE5484D), hx(0xFFE5484D), hx(0xFFE5484D))
            "diamond" -> intArrayOf(Color.WHITE, hx(0xFFBFF3FF), Color.WHITE)
            else -> null
        }
        if (gems != null) {
            for (i in 0..2) {
                val gx = cx - 18f + i * 18f
                c.poly(gems[i], gx, 181f, gx + 5f, 186f, gx, 193f, gx - 5f, 186f)
            }
            if (bowl == "diamond") {
                val a = 0.5f + 0.5f * sin(t * 4f)
                c.star(cx - 18f, 183f, 3f + 3f * a, withAlpha(Color.WHITE, (100 + 150 * a).toInt()))
                c.star(cx + 20f, 190f, 3f + 3f * (1f - a), withAlpha(Color.WHITE, (100 + 150 * (1f - a)).toInt()))
            }
        }
    }

    private fun bowlColor(): Int = when (bowl) {
        "plastic" -> hx(0xFFE5484D)
        "tin" -> hx(0xFFB8BDC6)
        "ceramic", "ceramic_fish" -> hx(0xFFF1E9DD)
        "blue" -> hx(0xFF4F8CFF)
        "silver", "silver_gems" -> hx(0xFFD8DEE9)
        "gold", "gold_gems" -> hx(0xFFFFC83D)
        "diamond" -> hx(0xFF9FE8FF)
        else -> hx(0xFFE5484D)
    }

    // ================== эффекты ==================
    private fun drawEffects(c: Canvas) {
        // сердечки: сытый кот сам их пускает, нажатие — тоже
        if (mood == "full" && t >= nextHeart && animOn) { spawnHearts(1); nextHeart = t + 2.6f }
        val iter = hearts.iterator()
        while (iter.hasNext()) {
            val h = iter.next()
            val age = t - h[2]
            if (age > 1.7f) { iter.remove(); continue }
            if (age < 0f) continue
            val k = age / 1.7f
            c.heart(h[0] + h[3] * k, h[1] - 46f * k, 6f + 3f * k, withAlpha(hx(0xFFFF5C8A), (255 * (1f - k * k)).toInt()))
        }
        // пузырь «хочу есть»
        if (mood == "hungry" || mood == "starving") {
            val pulse = 1f + 0.06f * sin(t * 4f)
            c.save()
            c.translate(206f, 66f)
            c.scale(pulse, pulse)
            c.oval(0f, 0f, 20f, 15f, hx(0xFFFFFFFF))
            c.poly(hx(0xFFFFFFFF), -8f, 10f, -16f, 24f, 2f, 13f)
            c.oval(-2f, 0f, 8f, 4.5f, hx(0xFFFF8A3D))
            c.poly(hx(0xFFFF8A3D), 5f, 0f, 12f, -5f, 12f, 5f)
            c.oval(-6f, -1f, 1.2f, 1.2f, hx(0xFF15151A))
            c.restore()
        }
        // конфетти нового уровня
        val ct = t - celebrateAt
        if (ct in 0f..3.4f) {
            val cols = intArrayOf(hx(0xFFFFC83D), hx(0xFFFF5C8A), hx(0xFF3FA9F5), hx(0xFF34C759), hx(0xFF8B5CF6))
            for (i in 0 until 40) {
                val x = fract(sin(i * 12.9898f) * 43758.547f) * 320f
                val spd = 50f + fract(sin(i * 78.233f) * 12345.678f) * 70f
                val y = -10f + ct * spd - fract(sin(i * 3.1f) * 999f) * 30f
                if (y > 236f) continue
                p.color = withAlpha(cols[i % cols.size], (255 * (1f - ct / 3.4f)).toInt())
                p.style = Paint.Style.FILL
                c.save()
                c.rotate(ct * 200f + i * 30f, x + sin(ct * 3f + i) * 8f, y)
                c.drawRect(x + sin(ct * 3f + i) * 8f - 3f, y - 2f, x + sin(ct * 3f + i) * 8f + 3f, y + 2f, p)
                c.restore()
            }
        }
    }

    // ---- нажатие на кота ----
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) return true
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val s = min(width / 320f, height / 240f)
            val vx = (e.x - (width - 320f * s) / 2f) / s
            val vy = (e.y - (height - 240f * s) / 2f) / s
            if (vx in 112f..232f && vy in 56f..206f) {
                bounceAt = t
                spawnHearts(3)
                if (!animOn) invalidate()
                performClick()
                onPet?.invoke()
            }
            return true
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}

/** Затемнение цвета для бортов и теней. */
private object ColorBlend {
    fun dark(color: Int): Int = androidx.core.graphics.ColorUtils.blendARGB(color, Color.BLACK, 0.28f)
}