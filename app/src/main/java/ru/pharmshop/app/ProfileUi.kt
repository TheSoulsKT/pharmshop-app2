package ru.pharmshop.app

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import java.util.Locale

// Оформление профиля: карточка с обложкой, фото и ником, достижения, плитки статистики и аккуратные окна редактирования.

/** Обложки карточки профиля. Номер хранится в Store.profileCover, 0 — цвет берётся из темы. */
internal class ProfileCover(val id: Int, val title: String, val c1: Int, val c2: Int)

internal val PROFILE_COVERS = listOf(
    ProfileCover(1, "Закат", Color.parseColor("#FF7E5F"), Color.parseColor("#FEB47B")),
    ProfileCover(2, "Океан", Color.parseColor("#2193B0"), Color.parseColor("#6DD5ED")),
    ProfileCover(3, "Лес", Color.parseColor("#11998E"), Color.parseColor("#38EF7D")),
    ProfileCover(4, "Ночь", Color.parseColor("#0F2027"), Color.parseColor("#2C5364")),
    ProfileCover(5, "Сирень", Color.parseColor("#8E2DE2"), Color.parseColor("#4A00E0")),
    ProfileCover(6, "Малина", Color.parseColor("#F953C6"), Color.parseColor("#B91D73"))
)

/** Пара цветов выбранной обложки (по умолчанию — акцент темы, уходящий в цвет фона). */
private fun MainActivity.coverColors(): Pair<Int, Int> {
    val c = PROFILE_COVERS.firstOrNull { it.id == Store.profileCover }
    return if (c != null) c.c1 to c.c2 else BLUE to ColorUtils.blendARGB(BLUE, BG, 0.5f)
}

private fun MainActivity.coverDrawable(radius: Int = 0): GradientDrawable {
    val (a, b) = coverColors()
    return GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(a, b)).apply { cornerRadius = dp(radius).toFloat() }
}

private fun ovalRipple(fill: Int, ring: Int, ringPx: Int, rippleColor: Int): RippleDrawable {
    val base = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        if (ringPx > 0) setStroke(ringPx, ring)
    }
    val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
    return RippleDrawable(ColorStateList.valueOf(rippleColor), base, mask)
}

/** Круглая кнопка с иконкой-эмодзи (значок на фото, карандаш у ника). */
private fun MainActivity.roundBtn(icon: String, sizeDp: Int, fill: Int, ring: Int, desc: String, onClick: () -> Unit): TextView =
    TextView(this).apply {
        text = icon
        textSize = (sizeDp * 0.42f).coerceIn(11f, 18f)
        gravity = Gravity.CENTER
        includeFontPadding = false
        background = ovalRipple(fill, ring, dp(2), Theme.rippleColor)
        contentDescription = desc
        isClickable = true
        setOnClickListener { onClick() }
        pressable(this)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

private fun MainActivity.ovalOutline(v: View) {
    v.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, o: Outline) { o.setOval(0, 0, view.width, view.height) }
    }
    v.clipToOutline = true
}

/** Фото или кружок с первой буквой ника. sizeDp — диаметр. */
internal fun MainActivity.avatarView(sizeDp: Int, user: String, letterSize: Float): View {
    val photo = if (hiddenData()) null else Avatar.bitmap()
    return if (photo != null) ImageView(this).apply {
        setImageBitmap(photo)
        scaleType = ImageView.ScaleType.CENTER_CROP
        ovalOutline(this)
        contentDescription = "Аватарка"
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    } else TextView(this).apply {
        text = if (hiddenData()) "•" else user.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        textSize = letterSize
        setTextColor(Color.BLACK)
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(BLUE) }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }
}

/**
 * Главная карточка профиля: обложка, фото с кнопкой камеры, ник с карандашом, описание.
 * Если включено «Скрывать суммы и ники» — нажатие на карточку показывает и прячет данные.
 */
internal fun MainActivity.profileHero(body: LinearLayout, sid: Int, user: String): LinearLayout {
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(CARD, 22, LINE)
        clipToOutline = true
        layoutParams = lp(14)
    }

    // обложка + кнопка смены обложки
    val cover = FrameLayout(this).apply {
        background = coverDrawable()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(104))
    }
    cover.addView(View(this).apply {   // лёгкий узор: большой полупрозрачный круг
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ColorUtils.setAlphaComponent(Color.WHITE, 36)) }
        layoutParams = FrameLayout.LayoutParams(dp(150), dp(150), Gravity.END or Gravity.TOP).apply {
            topMargin = -dp(60); rightMargin = -dp(30)
        }
    })
    cover.addView(View(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ColorUtils.setAlphaComponent(Color.WHITE, 24)) }
        layoutParams = FrameLayout.LayoutParams(dp(90), dp(90), Gravity.START or Gravity.BOTTOM).apply {
            bottomMargin = -dp(46); leftMargin = dp(20)
        }
    })
    cover.addView(roundBtn("🎨", 36, ColorUtils.setAlphaComponent(Color.BLACK, 110), Color.TRANSPARENT, "Сменить обложку") {
        coverSheet(body, sid)
    }.apply {
        layoutParams = FrameLayout.LayoutParams(dp(36), dp(36), Gravity.END or Gravity.TOP).apply { topMargin = dp(12); rightMargin = dp(12) }
    })
    card.addView(cover)

    // фото поверх обложки, на нём значок камеры
    val ring = FrameLayout(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(CARD) }
        setPadding(dp(4), dp(4), dp(4), dp(4))
        layoutParams = FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER_HORIZONTAL)
        isClickable = true
        setOnClickListener { photoSheet(body, sid, user) }
        pressable(this)
    }
    ring.addView(avatarView(88, user, 34f).apply {
        layoutParams = FrameLayout.LayoutParams(dp(88), dp(88))
    })
    val photoWrap = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(112), dp(96)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = -dp(48) }
        addView(ring)
        addView(roundBtn("📷", 32, BLUE, CARD, "Изменить фото") { photoSheet(body, sid, user) }.apply {
            layoutParams = FrameLayout.LayoutParams(dp(32), dp(32), Gravity.END or Gravity.BOTTOM).apply { bottomMargin = dp(2) }
        })
    }
    card.addView(photoWrap)

    // ник и карандаш
    val nameRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10); leftMargin = dp(16); rightMargin = dp(16)
        }
    }
    nameRow.addView(txt(if (user.isEmpty()) "Telegram не указан" else hiddenNick(user), 20f, TEXT, true).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
    })
    nameRow.addView(roundBtn("✏️", 30, mix(BLUE, 0.22f), edge(BLUE), "Изменить ник") { editUsername(body, sid) })
    card.addView(nameRow)

    card.addView(txt(
        if (user.isEmpty()) "Укажите ник: он подставится в заказы" else "Ник указан вручную, ботом не подтверждён",
        12f, MUTE
    ).apply {
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2); leftMargin = dp(16); rightMargin = dp(16)
        }
    })

    // описание: текст или приглашение его добавить
    val hasBio = Store.bio.isNotEmpty()
    card.addView(txt(
        if (hasBio) (if (hiddenData()) "••••••" else Store.bio) else "＋ Добавить пару слов о себе",
        14f, if (hasBio) TEXT else BLUE, !hasBio
    ).apply {
        gravity = Gravity.CENTER
        background = if (hasBio) ripple(Color.TRANSPARENT, 12) else ripple(mix(BLUE, 0.14f), 12, edge(BLUE))
        setPadding(dp(14), dp(8), dp(14), dp(8))
        isClickable = true
        setOnClickListener { editBio(body, sid) }
        pressable(this)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10); bottomMargin = dp(18)
            leftMargin = dp(16); rightMargin = dp(16)
        }
    })

    if (Store.hideSensitive) {
        card.isClickable = true
        card.setOnClickListener { AppState.revealed = !AppState.revealed; renderProfile(body, sid) }
    }
    return card
}

/** Плитка статистики: цветной значок, число и подпись. */
internal fun MainActivity.statCard(emoji: String, value: String, label: String, color: Int): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = shape(mix(color, 0.12f), 18, edge(color))
        setPadding(dp(6), dp(14), dp(6), dp(12))
        addView(txt(emoji, 22f).apply { gravity = Gravity.CENTER; layoutParams = lp(4) })
        addView(txt(value, 17f, TEXT, true).apply { gravity = Gravity.CENTER; maxLines = 1 })
        addView(txt(label, 11f, MUTE).apply { gravity = Gravity.CENTER })
    }

private class BadgeInfo(val emoji: String, val title: String, val hint: String, val got: Boolean)

/** Достижения: открываются сами по тому, что уже есть в приложении. Нажатие подсказывает, как получить. */
internal fun MainActivity.achievementsRow(orders: List<org.json.JSONObject>, stars: Int): View {
    val premiumBought = orders.any { it.optString("product") == "premium" && statusKind(effStatus(it)).let { k -> k == 1 || k == 2 } }
    val list = listOf(
        BadgeInfo("🛒", "Первый заказ", "Оформите любой заказ", orders.isNotEmpty()),
        BadgeInfo("⭐", "500 звёзд", "Купите звёзд на 500 и больше", stars >= 500),
        BadgeInfo("👑", "Premium", "Оплатите подписку Premium", premiumBought),
        BadgeInfo("🛡", "VPN", "Войдите в личный кабинет VPN", VpnApi.loggedIn()),
        BadgeInfo("🔐", "Под защитой", "Включите PIN или отпечаток", lockEnabled()),
        BadgeInfo("📸", "С фото", "Добавьте фото профиля", Avatar.has())
    )
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    for (b in list) {
        val col = if (b.got) GREEN else MUTE
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = ripple(if (b.got) mix(GREEN, 0.12f) else Color.TRANSPARENT, 16, if (b.got) edge(GREEN) else LINE)
            setPadding(dp(8), dp(12), dp(8), dp(10))
            alpha = if (b.got) 1f else 0.55f
            isClickable = true
            setOnClickListener { toast(if (b.got) "${b.title}: получено" else "${b.title}: ${b.hint}") }
            pressable(this)
            layoutParams = LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
            addView(txt(b.emoji, 24f).apply { gravity = Gravity.CENTER; layoutParams = lp(4) })
            addView(txt(b.title, 12f, if (b.got) TEXT else MUTE, b.got).apply { gravity = Gravity.CENTER; maxLines = 2 })
            addView(txt(if (b.got) "✓ есть" else "закрыто", 10f, col).apply { gravity = Gravity.CENTER })
        })
    }
    return HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        layoutParams = lp(18)
        addView(row)
    }
}

/** Крупная строка-ссылка: значок, заголовок и пояснение. */
internal fun MainActivity.linkTile(emoji: String, title: String, sub: String, onClick: () -> Unit): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = ripple(CARD, 18, LINE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = lp(18)
        isClickable = true
        setOnClickListener { onClick() }
        pressable(this)
        addView(txt(emoji, 20f).apply {
            gravity = Gravity.CENTER
            background = shape(mix(BLUE, 0.16f), 12)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(12) }
        })
        addView(LinearLayout(this@linkTile).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(txt(title, 15f, TEXT, true))
            addView(txt(sub, 12f, MUTE))
        })
        addView(txt("›", 22f, MUTE))
    }

// ---------- окна редактирования ----------

/** Ник Telegram: 5–32 символа, латиница, цифры и «_». Подсказка только предупреждает, сохранить можно любой. */
private val TG_NICK = Regex("^[A-Za-z0-9_]{5,32}$")

/** Приводит ввод к нику: убирает @, пробелы и ссылку t.me/. */
internal fun cleanNick(s: String): String =
    s.trim().substringAfterLast('/').removePrefix("@").replace(" ", "").take(32)

internal fun MainActivity.editUsername(body: LinearLayout, sid: Int) {
    sheet("Ваш Telegram", focusInput = true) { box, close ->
        box.addView(txt("Ник подставится в заказы. Можно вставить @ник или ссылку t.me, лишнее уберём сами.", 13f, MUTE).apply {
            layoutParams = lp(12)
        })
        val field = EditText(this).apply {
            hint = "username"
            setText(Store.lastUser)
            setSelection(text.length)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(64))
            setTextColor(TEXT)
            setHintTextColor(MUTE)
            textSize = 17f * Theme.textScale
            background = null
            setPadding(0, dp(14), 0, dp(14))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val hintLine = txt("", 12f, MUTE).apply { layoutParams = lp(14) }
        fun check() {
            val n = cleanNick(field.text.toString())
            when {
                n.isEmpty() -> { hintLine.text = "Оставьте пустым, чтобы убрать ник"; hintLine.setTextColor(MUTE) }
                TG_NICK.matches(n) -> { hintLine.text = "✓ Ник выглядит правильно"; hintLine.setTextColor(GREEN) }
                else -> { hintLine.text = "У ников Telegram обычно 5–32 символа: латиница, цифры и «_»"; hintLine.setTextColor(AMBER) }
            }
        }
        val clear = TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(MUTE)
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { field.setText("") }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(CARD, 14, edge(BLUE))
            setPadding(dp(14), 0, dp(8), 0)
            layoutParams = lp(8)
            addView(txt("@", 18f, BLUE, true).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) } })
            addView(field)
            addView(clear)
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { clear.visibility = if (s.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE; check() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        clear.visibility = if (field.text.isEmpty()) View.INVISIBLE else View.VISIBLE
        check()
        box.addView(inputRow)
        box.addView(hintLine)
        box.addView(btnGrid(listOf(
            gbtn("Отмена", false) { close() },
            gbtn("Сохранить", true) {
                Store.lastUser = cleanNick(field.text.toString())
                close()
                if (sid == screenId) renderProfile(body, sid)
            }
        ), 0))
        field.requestFocus()
    }
}

/** Окно фото профиля: большой предпросмотр, выбор из галереи и удаление. */
internal fun MainActivity.photoSheet(body: LinearLayout, sid: Int, user: String) {
    sheet("Фото профиля") { box, close ->
        box.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            layoutParams = lp(12)
            background = coverDrawable(18)
            setPadding(dp(0), dp(22), dp(0), dp(22))
            addView(FrameLayout(this@photoSheet).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(BG) }
                setPadding(dp(4), dp(4), dp(4), dp(4))
                addView(avatarView(104, user, 40f))
            })
        })
        box.addView(txt("Фото обрезается по центру до квадрата и остаётся только на этом телефоне: никуда не отправляется.", 13f, MUTE).apply {
            layoutParams = lp(14)
        })
        val btns = ArrayList<TextView>()
        btns.add(gbtn(if (Avatar.has()) "Выбрать другое" else "Выбрать из галереи", true) { close(); pickAvatar(body, sid) })
        if (Avatar.has()) btns.add(gbtn("Убрать фото", false) {
            close()
            Avatar.remove()
            if (sid == screenId) renderProfile(body, sid)
        })
        box.addView(btnGrid(btns, 0))
    }
}

/** Выбор обложки: образцы в два ряда, выбранная отмечена галочкой. */
internal fun MainActivity.coverSheet(body: LinearLayout, sid: Int) {
    sheet("Обложка профиля") { box, close ->
        box.addView(txt("Цвет верхней части карточки профиля. «По теме» подстраивается под выбранное оформление.", 13f, MUTE).apply { layoutParams = lp(12) })
        val options = listOf(ProfileCover(0, "По теме", BLUE, ColorUtils.blendARGB(BLUE, BG, 0.5f))) + PROFILE_COVERS
        var i = 0
        while (i < options.size) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = lp(10) }
            for (k in 0 until 2) {
                val c = options.getOrNull(i + k)
                if (c == null) { row.addView(View(this), LinearLayout.LayoutParams(0, dp(1), 1f)); continue }
                val sel = Store.profileCover == c.id
                row.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.BOTTOM
                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c.c1, c.c2)).apply {
                        cornerRadius = corner(16)
                        setStroke(dp(if (sel) 3 else 1), if (sel) TEXT else LINE)
                    }
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    isClickable = true
                    setOnClickListener {
                        Store.profileCover = c.id
                        close()
                        if (sid == screenId) renderProfile(body, sid)
                    }
                    pressable(this)
                    layoutParams = LinearLayout.LayoutParams(0, dp(64), 1f).apply {
                        if (k == 0) rightMargin = dp(5) else leftMargin = dp(5)
                    }
                    addView(txt(c.title + if (sel) "  ✓" else "", 13f, Color.WHITE, true))
                })
            }
            box.addView(row)
            i += 2
        }
    }
}

/** Описание профиля: поле с живым счётчиком символов. */
internal fun MainActivity.editBio(body: LinearLayout, sid: Int) {
    sheet("О себе", focusInput = true) { box, close ->
        box.addView(txt("Пара слов в карточке профиля. Видно только вам, хранится на этом телефоне.", 13f, MUTE).apply { layoutParams = lp(12) })
        val field = input("Например: беру звёзды для друзей", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            setText(Store.bio)
            setSelection(text.length)
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
            filters = arrayOf(InputFilter.LengthFilter(120))
            layoutParams = lp(6)
        }
        val counter = txt("", 12f, MUTE).apply { gravity = Gravity.END; layoutParams = lp(14) }
        fun count() { counter.text = "${field.text.length} / 120" }
        field.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { count() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        count()
        box.addView(field)
        box.addView(counter)
        val btns = arrayListOf(gbtn("Отмена", false) { close() },
            gbtn("Сохранить", true) {
                Store.bio = field.text.toString().trim()
                close()
                if (sid == screenId) renderProfile(body, sid)
            })
        box.addView(btnGrid(btns, 0))
        field.requestFocus()
    }
}

/** Заголовок блока профиля с необязательной подписью справа. */
internal fun MainActivity.profileTitle(t: String, right: String = ""): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = lp(10)
        addView(txt(t, 18f, TEXT, true).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        if (right.isNotEmpty()) addView(txt(right, 12f, MUTE))
    }

internal fun starsLabel(n: Int): String = String.format(Locale.US, "%,d", n).replace(',', ' ')
