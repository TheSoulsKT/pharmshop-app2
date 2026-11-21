package ru.pharmshop.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

// Поддержка: частые вопросы и чат.

// ---------- частые вопросы ----------
internal fun MainActivity.faqCard(title: String, body: String, color: Int): LinearLayout {
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
internal fun MainActivity.chatScreen(): LinearLayout {
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

internal fun MainActivity.errText(e: Exception) =
    if (e is IllegalStateException) "Чат пока не настроен" else (e.message ?: "Нет связи")

/** Сжимает фото: учитывает поворот, сторона до 1280 px, JPEG ≤ ~700 КБ. Возвращает base64. */
internal fun MainActivity.prepareImage(uri: Uri): String? {
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

internal fun MainActivity.showFullImage(bmp: Bitmap) {
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
internal fun MainActivity.chatPane(
    sid: Int, mine: String, hint: String,
    fetch: (Long) -> JSONArray, send: (String, String) -> Unit,
    fetchImage: (Long) -> String, showMeta: Boolean = false,
    readTs: () -> Long = { 0L }
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

    // отметки «Доставлено / Прочитано» под своими сообщениями
    val receipts = ArrayList<Triple<Long, TextView, String>>()
    fun updateReceipts() {
        val rt = readTs()
        for ((ts, v, base) in receipts) {
            val read = rt >= ts
            v.text = base + if (read) "  ✓✓ Прочитано" else "  ✓ Доставлено"
            v.setTextColor(if (read) BLUE else MUTE)
        }
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
        val msgTs = m.optLong("ts")
        val timeStr = timeFmt.format(java.util.Date(msgTs))
        val timeView = txt(timeStr, 10f, MUTE)
        wrap.addView(timeView)
        if (own) receipts.add(Triple(msgTs, timeView, timeStr))
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
                        updateReceipts()
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

internal fun MainActivity.faqView(): ScrollView {
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
internal fun MainActivity.showSupport() {
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
        { id -> ChatApi.userImage(Store.deviceId, Store.deviceSecret, id) },
        readTs = { ChatApi.lastReadTs }
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
