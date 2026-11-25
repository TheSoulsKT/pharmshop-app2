package ru.pharmshop.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import java.io.File

// Уровень профиля, как в PWA: растёт с числом дней в чате, каждый уровень открывает рамку аватара.
// Свою обложку из галереи можно поставить сразу, без уровня.

/** «1 день», «3 дня», «7 дней». */
internal fun ruDays(n: Int): String {
    val m = Math.abs(n) % 100
    val d = m % 10
    val w = if (m in 11..19) "дней" else when (d) { 1 -> "день"; 2, 3, 4 -> "дня"; else -> "дней" }
    return "$n $w"
}

/**
 * Своё фото на обложке профиля. Лежит в закрытой папке приложения, никуда не отправляется:
 * хранится как JPEG 800×400, а на экране подгоняется под размер карточки.
 */
object CoverPhoto {
    /** Значение Store.profileCover, когда выбрано своё фото. */
    const val ID = 100
    private const val W = 800
    private const val H = 400
    private var dir: File? = null
    private var cache: Bitmap? = null

    fun init(ctx: Context) { dir = ctx.filesDir }

    private fun file(): File? = dir?.let { File(it, "cover.jpg") }

    fun has(): Boolean = file()?.exists() == true

    fun bitmap(): Bitmap? {
        cache?.let { return it }
        val f = file() ?: return null
        if (!f.exists()) return null
        val b = try { BitmapFactory.decodeFile(f.path) } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
        cache = b
        return b
    }

    /** Берёт картинку из галереи, обрезает по центру до пропорции 2:1 и сохраняет. Не вызывать из главного потока. */
    fun save(ctx: Context, uri: Uri): Boolean {
        val f = file() ?: return false
        try {
            val cr = ctx.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= W && bounds.outHeight / (sample * 2) >= H) sample *= 2
            val src = cr.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return false

            // поворот по данным камеры, чтобы фото не оказалось на боку
            val orient = try {
                cr.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }
            val m = Matrix()
            when (orient) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            }
            val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)

            // центральная область с пропорцией W:H
            var cw = rotated.width
            var ch = cw * H / W
            if (ch > rotated.height) { ch = rotated.height; cw = ch * W / H }
            val crop = Bitmap.createBitmap(rotated, (rotated.width - cw) / 2, (rotated.height - ch) / 2, cw, ch)
            val out = if (cw > W) Bitmap.createScaledBitmap(crop, W, H, true) else crop

            val tmp = File(f.parentFile, "cover.tmp")
            tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) return false
            }
            cache = out
            return true
        } catch (e: Exception) {
            AppLog.e("cover", e)
            return false
        } catch (e: OutOfMemoryError) {
            return false
        }
    }

    fun remove() {
        cache = null
        try { file()?.delete() } catch (e: Exception) { }
    }
}

/** Фото обложки: заполняет всю область по центру (как background-size: cover), скруглённые углы, лёгкое затемнение для белого текста. */
internal class CoverPhotoDrawable(private val bmp: Bitmap, private val radius: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
    private val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val rect = RectF()
    private val matrix = Matrix()

    init { paint.shader = shader }

    override fun onBoundsChange(b: android.graphics.Rect) {
        rect.set(b)
        if (b.width() <= 0 || b.height() <= 0) return
        val s = Math.max(b.width().toFloat() / bmp.width, b.height().toFloat() / bmp.height)
        matrix.reset()
        matrix.setScale(s, s)
        matrix.postTranslate(b.left + (b.width() - bmp.width * s) / 2f, b.top + (b.height() - bmp.height * s) / 2f)
        shader.setLocalMatrix(matrix)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRoundRect(rect, radius, radius, paint)
        canvas.drawRoundRect(rect, radius, radius, shade)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Выбор фото для обложки: после выбора оно обрезается, сохраняется и сразу ставится на обложку. */
internal fun MainActivity.pickCoverPhoto(body: LinearLayout, sid: Int) {
    onImagePicked = { uri ->
        toast("Загружаем фото…")
        io.execute {
            val ok = CoverPhoto.save(this, uri)
            ui {
                if (!ok) toast("Не удалось загрузить фото")
                else {
                    Store.profileCover = CoverPhoto.ID
                    toast("Обложка обновлена")
                    if (sid == screenId) renderProfile(body, sid)
                }
            }
        }
    }
    try { imagePicker.launch("image/*") } catch (e: Exception) { toast("Не удалось открыть галерею") }
}

/**
 * Блок уровня в карточке профиля: плашка «🌱 Новичок · уровень 1», полоса пути до следующего уровня
 * и подпись «С нами N дней · до «…» ещё M». Нажатие открывает список уровней.
 */
internal fun MainActivity.levelBlock(): LinearLayout {
    val li = Frames.level()
    val days = Frames.days()
    val gold = if (Theme.light) Color.parseColor("#B7791F") else Color.parseColor("#FFD54F")
    val orange = Color.parseColor("#FF783C")
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12); leftMargin = dp(18); rightMargin = dp(18)
        }
        isClickable = true
        setOnClickListener { levelsSheet() }
        pressable(this)
    }

    // плашка с уровнем
    box.addView(txt(Frames.ICONS[li] + "  " + Frames.NAMES[li] + " · уровень " + (li + 1), 15f, gold, true).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
            ColorUtils.setAlphaComponent(gold, 0x33), ColorUtils.setAlphaComponent(orange, 0x24)
        )).apply {
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), ColorUtils.setAlphaComponent(gold, 0x80))
        }
        setPadding(dp(18), dp(7), dp(18), dp(7))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    })

    // полоса прогресса до следующего уровня
    val pc = Frames.progress()
    val track = FrameLayout(this).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(5).toFloat()
            setColor(ColorUtils.setAlphaComponent(MUTE, 0x33))
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(9)).apply { topMargin = dp(14) }
    }
    val fill = View(this).apply {
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(GREEN, BLUE)).apply {
            cornerRadius = dp(5).toFloat()
        }
        layoutParams = FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    track.addView(fill)
    track.post {
        val target = track.width * pc / 100
        if (Store.uiAnim && target > 0) {
            ValueAnimator.ofInt(0, target).apply {
                duration = 1100
                interpolator = DecelerateInterpolator()
                addUpdateListener { a ->
                    val lp = fill.layoutParams
                    lp.width = a.animatedValue as Int
                    fill.layoutParams = lp
                }
                start()
            }
        } else {
            val lp = fill.layoutParams
            lp.width = target
            fill.layoutParams = lp
        }
    }
    box.addView(track)

    val next = Frames.DAYS.getOrNull(li + 1)
    val caption = "С нами " + ruDays(days) + " · " +
        if (next != null) "до «" + Frames.NAMES[li + 1] + "» ещё " + (next - days) else "максимальный уровень"
    box.addView(txt(caption, 13f, MUTE).apply {
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    })
    return box
}

/** Окно «Уровни»: все десять ступеней, достигнутые отмечены, текущая подсвечена. */
internal fun MainActivity.levelsSheet() {
    val cur = Frames.level()
    sheet("Уровни", scroll = true) { box, _ ->
        box.addView(txt(
            "Уровень растёт с числом дней в чате. Каждый открывает новую рамку аватара.",
            13f, MUTE
        ).apply { layoutParams = lp(12) })
        for (n in 0..9) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ripple(if (n == cur) mix(BLUE, 0.12f) else CARD, 14, if (n == cur) BLUE else LINE)
                setPadding(dp(14), dp(11), dp(14), dp(11))
                layoutParams = lp(8)
                alpha = if (n <= cur) 1f else 0.55f
            }
            row.addView(txt(Frames.ICONS[n], 22f).apply {
                layoutParams = LinearLayout.LayoutParams(dp(38), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(txt("Уровень " + (n + 1) + " · " + Frames.NAMES[n], 15f, TEXT, true))
            info.addView(txt(if (Frames.DAYS[n] == 0) "с первого дня" else "от " + ruDays(Frames.DAYS[n]) + " в чате", 12f, MUTE))
            row.addView(info)
            row.addView(txt(if (n < cur) "✓" else if (n == cur) "сейчас" else "🔒", 13f, if (n <= cur) GREEN else MUTE, true))
            box.addView(row)
        }
    }
}
