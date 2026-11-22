package ru.pharmshop.app

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils

// Настройки: оформление, шаги заказа, уведомления, защита входа, обновления.

internal fun MainActivity.showSettings() {
    onHome = false
    val col = screen(3)
    val sid = screenId
    titleRow(col, "Настройки")
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(body)
    renderSettings(body, sid)
    riseIn(body)
}

/** Перерисовать экран целиком (после смены темы: поменялись все цвета). Только плавное появление, без выезда. */
internal fun MainActivity.reopenSettings() {
    firstScreen = true
    showSettings()
}

internal fun MainActivity.renderSettings(body: LinearLayout, sid: Int) {
    body.removeAllViews()
    val again: () -> Unit = { if (sid == screenId) renderSettings(body, sid) }
    appearanceBlock(body)
    ordersBlock(body, again)
    notificationsBlock(body)
    securityBlock(body, again)
    updatesBlock(body, again)
    body.addView(txt("Версия ${BuildConfig.VERSION_NAME} (сборка ${BuildConfig.VERSION_CODE})", 12f, MUTE).apply {
        gravity = Gravity.CENTER
        layoutParams = lp(0).apply { topMargin = dp(6) }
    })
}

// ---------- общие элементы ----------

private fun MainActivity.sectionLabel(body: LinearLayout, t: String) {
    body.addView(txt(t, 20f, TEXT, true).apply { layoutParams = lp(10).apply { topMargin = dp(10) } })
}

private fun MainActivity.groupCard(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = shape(CARD, 18, LINE)
    setPadding(dp(16), dp(14), dp(16), dp(14))
    layoutParams = lp(12)
}

private fun MainActivity.caption(t: String): TextView = txt(t, 13f, MUTE).apply { layoutParams = lp(8) }

/** Переключатель из нескольких вариантов («таблетка»). */
internal fun MainActivity.segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = shape(BG, 14, LINE)
        setPadding(dp(3), dp(3), dp(3), dp(3))
        layoutParams = lp(12)
    }
    options.forEachIndexed { i, label ->
        val on = i == selected
        box.addView(TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(if (on) Color.BLACK else TEXT)
            if (on) setTypeface(typeface, Typeface.BOLD)
            background = if (on) shape(BLUE, 11) else ripple(Color.TRANSPARENT, 11)
            setPadding(dp(6), dp(10), dp(6), dp(10))
            isClickable = true
            setOnClickListener { if (!on) onSelect(i) }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
    }
    return box
}

/** Строка с выключателем. */
internal fun MainActivity.switchRow(
    title: String, sub: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit
): LinearLayout {
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    val sw = SwitchCompat(this).apply {
        isChecked = checked
        isEnabled = enabled
        thumbTintList = ColorStateList(states, intArrayOf(BLUE, MUTE))
        trackTintList = ColorStateList(states, intArrayOf(ColorUtils.setAlphaComponent(BLUE, 110), LINE))
    }
    val texts = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(12) }
    }
    texts.addView(txt(title, 16f, if (enabled) TEXT else MUTE, true))
    if (sub.isNotEmpty()) texts.addView(txt(sub, 12f, MUTE))
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = lp(8)
        setPadding(0, dp(4), 0, dp(4))
    }
    row.addView(texts)
    row.addView(sw)
    sw.setOnCheckedChangeListener { _, v -> onChange(v) }
    if (enabled) {
        row.isClickable = true
        row.setOnClickListener { sw.toggle() }
    }
    return row
}

// ---------- оформление ----------

private fun MainActivity.appearanceBlock(body: LinearLayout) {
    sectionLabel(body, "Оформление")
    val c = groupCard()

    c.addView(caption("Режим"))
    val modes = listOf(1, 2, 0)   // значения Store.theme: тёмная, светлая, как в системе
    c.addView(segmented(listOf("🌙 Тёмная", "☀️ Светлая", "🌓 Система"), modes.indexOf(Store.theme).coerceAtLeast(0)) { i ->
        Store.theme = modes[i]
        applyAppearance()
    })

    c.addView(caption("Тема"))
    val all = Skin.values().toList()
    var i = 0
    while (i < all.size) {
        val a = skinCard(all[i])
        val b: View = if (i + 1 < all.size) skinCard(all[i + 1]) else View(this).apply { visibility = View.INVISIBLE }
        a.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(5) }
        b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(5) }
        c.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(10)
            addView(a)
            addView(b)
        })
        i += 2
    }
    if (!FxView.allowed(this)) {
        c.addView(txt("Анимации отключены в системе телефона, поэтому фон темы неподвижен.", 12f, MUTE))
    }
    body.addView(c)
}

/** Применяет выбранные режим и тему сразу, без перезапуска приложения. */
private fun MainActivity.applyAppearance() {
    Theme.update(this)
    applyWindowTheme()
    // смена тёмного/светлого режима может пересоздать экран — тогда вернёмся в настройки
    AppState.reopen = "settings"
    main.postDelayed({ AppState.reopen = null }, 2000)   // флаг нужен только для мгновенного пересоздания
    androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(Theme.nightMode())
    reopenSettings()
}

private fun MainActivity.swatch(color: Int, ring: Int): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(5) }
    background = GradientDrawable().apply {
        setShape(GradientDrawable.OVAL)
        setColor(color)
        setStroke(dp(1), ring)
    }
}

private fun MainActivity.skinCard(s: Skin): LinearLayout {
    val sel = Theme.skin == s
    val p = if (Theme.light) s.light else s.dark
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = RippleDrawable(
            ColorStateList.valueOf(Theme.rippleColor),
            GradientDrawable().apply {
                setColor(p.bg)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(if (sel) 2 else 1), if (sel) p.accent else LINE)
            },
            shape(Color.WHITE, 16)
        )
        setPadding(dp(12), dp(10), dp(12), dp(10))
        isClickable = true
        setOnClickListener {
            if (!sel) {
                Store.skin = s.id
                applyAppearance()
            }
        }
        pressable(this)
    }
    val top = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = lp(4)
    }
    top.addView(txt(s.emoji, 22f).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
    if (sel) top.addView(txt("✓", 16f, p.accent, true))
    box.addView(top)
    box.addView(txt(s.title, 14f, TEXT, true).apply { layoutParams = lp(6) })
    box.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(swatch(p.card, p.line))
        addView(swatch(p.line, p.line))
        addView(swatch(p.accent, p.accent))
    })
    return box
}

// ---------- шаги заказа ----------

private fun MainActivity.ordersBlock(body: LinearLayout, again: () -> Unit) {
    sectionLabel(body, "Заказы")
    val c = groupCard()
    c.addView(caption("Как показывать шаги заказа"))
    c.addView(segmented(listOf("Значок", "Полоса"), Store.stepsStyle.coerceIn(0, 1)) { i ->
        Store.stepsStyle = i
        again()
    })
    c.addView(caption("Так будет выглядеть заказ, который оплачен и выдаётся:"))
    if (Store.stepsStyle == 1) {
        c.addView(stepsView(1).apply { layoutParams = lp(0) })
    } else {
        val color = BLUE
        c.addView(txt("Оплачен · выдаём", 12f, color, true).apply {
            background = shape((color and 0x00FFFFFF) or 0x33000000, 10)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
    }
    body.addView(c)
}

// ---------- уведомления ----------

internal fun MainActivity.openNotificationSettings() {
    try {
        startActivity(
            Intent("android.settings.APP_NOTIFICATION_SETTINGS")
                .putExtra("android.provider.extra.APP_PACKAGE", packageName)
                .putExtra("app_package", packageName)
                .putExtra("app_uid", applicationInfo.uid)
        )
    } catch (e: Exception) {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (e2: Exception) {
            toast("Не удалось открыть настройки")
        }
    }
}

private fun MainActivity.notificationsBlock(body: LinearLayout) {
    sectionLabel(body, "Уведомления")
    val c = groupCard()
    c.addView(switchRow("Push-уведомления", "Ответы поддержки и статусы заказов", Store.pushEnabled) { on ->
        Store.pushEnabled = on
        if (on) {
            PollWorker.schedule(this)
            if (Build.VERSION.SDK_INT >= 33 && !Notifier.enabled(this)) {
                notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            toast("Сообщим об ответе поддержки и статусе заказов")
        } else {
            PollWorker.cancel(this)
            toast("Push-уведомления выключены")
        }
    })
    c.addView(switchRow("Напоминания о покупке", "Редкие напоминания про звёзды и Premium, не ночью", Reminders.enabled(this)) { on ->
        Reminders.setEnabled(this, on)
    })
    if (!Notifier.enabled(this)) {
        c.addView(txt("⚠ Уведомления выключены в системе телефона — сообщения не придут.", 12f, AMBER).apply {
            layoutParams = lp(8)
        })
    }
    c.addView(LinearLayout(this).apply {
        addView(chip("Системные настройки уведомлений", false) { openNotificationSettings() })
    })
    body.addView(c)
}

// ---------- защита входа ----------

private fun MainActivity.securityBlock(body: LinearLayout, again: () -> Unit) {
    sectionLabel(body, "Безопасность")
    val c = groupCard()
    c.addView(txt("Вход в приложение", 16f, TEXT, true).apply { layoutParams = lp(2) })
    c.addView(txt("Защитите историю заказов и чат с поддержкой от посторонних", 12f, MUTE).apply { layoutParams = lp(10) })
    val mode = if (Store.lockMode != 0 && Store.hasPin) Store.lockMode.coerceIn(1, 2) else 0
    c.addView(segmented(listOf("Выкл", "PIN", "Отпечаток"), mode) { i -> chooseLock(i, again) })

    if (mode != 0) {
        c.addView(caption("Просить вход, если приложение было свёрнуто дольше"))
        val delays = listOf(15, 60, 300)
        val di = delays.indexOf(Store.lockDelaySec).let { if (it < 0) 1 else it }
        c.addView(segmented(listOf("15 сек", "1 мин", "5 мин"), di) { i ->
            Store.lockDelaySec = delays[i]
            again()
        })
        c.addView(LinearLayout(this).apply {
            addView(chip("Сменить PIN", false) { showPinSetup(true) { _ -> reopenSettings() } })
        })
        if (mode == 2) c.addView(txt("PIN остаётся запасным входом: если палец не распознан, введите его.", 12f, MUTE).apply {
            layoutParams = lp(0).apply { topMargin = dp(8) }
        })
    }
    body.addView(c)
}

private fun MainActivity.chooseLock(i: Int, again: () -> Unit) {
    when (i) {
        0 -> {
            Store.lockMode = 0
            Store.clearPin()
            AppState.unlocked = true
            again()
        }
        1 -> {
            if (Store.hasPin) {
                Store.lockMode = 1
                AppState.unlocked = true
                again()
            } else {
                showPinSetup(false) { ok ->
                    if (ok) { Store.lockMode = 1; AppState.unlocked = true }
                    reopenSettings()
                }
            }
        }
        else -> {
            if (!biometricAvailable()) {
                AlertDialog.Builder(this)
                    .setTitle("Отпечаток не настроен")
                    .setMessage("Добавьте отпечаток пальца в настройках телефона (Безопасность → Биометрия), затем вернитесь сюда. Пока можно использовать PIN.")
                    .setPositiveButton("Открыть настройки") { _, _ ->
                        try { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } catch (e: Exception) { toast("Не удалось открыть настройки") }
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
                return
            }
            // сначала запасной PIN, затем проверяем, что отпечаток действительно срабатывает
            val confirmBio = {
                askBiometric("Включить вход по отпечатку", "Приложите палец, чтобы подтвердить") {
                    Store.lockMode = 2
                    AppState.unlocked = true
                    reopenSettings()
                }
            }
            if (Store.hasPin) confirmBio()
            else showPinSetup(false) { ok ->
                reopenSettings()
                if (ok) confirmBio()
            }
        }
    }
}

// ---------- обновления ----------

private fun MainActivity.updatesBlock(body: LinearLayout, again: () -> Unit) {
    sectionLabel(body, "Обновления")
    val c = groupCard()
    val ok = Updater.configured
    c.addView(switchRow(
        "Обновлять в фоне по Wi-Fi",
        if (ok) "Новая версия скачается сама, когда телефон на Wi-Fi. Android требует подтвердить установку — останется нажать «Установить»."
        else "Доступно только в сборке, собранной на GitHub",
        Store.wifiUpdate && ok, ok
    ) { on ->
        Store.wifiUpdate = on
        if (on) UpdateWorker.schedule(this) else UpdateWorker.cancel(this)
    })
    val pending = Store.pendingUpdateCode > BuildConfig.VERSION_CODE
    if (pending) {
        c.addView(txt(
            "Готова к установке: ${Store.pendingUpdateName.ifEmpty { "сборка ${Store.pendingUpdateCode}" }}",
            13f, GREEN, true
        ).apply { layoutParams = lp(8) })
    }
    c.addView(LinearLayout(this).apply {
        if (pending) addView(chip("Установить", true) { installPendingUpdate() })
        addView(chip("Проверить обновления", false) { checkForUpdate(false) })
    })
    body.addView(c)
}
