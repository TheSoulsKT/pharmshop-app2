package ru.pharmshop.app

import android.content.Intent
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

// Настройки VPN (вкладка «Настройки» в разделе VPN): проверка VPN, kill switch, российские сайты напрямую, DNS, блокировка рекламы, IPv6,
// автоподключение, обход DPI, приложения в VPN.
// Все параметры применяются при следующем подключении; если VPN включён — переподключаем сразу.

/** Если VPN сейчас включён — переподключаем, чтобы новые настройки вступили в силу. */
private fun MainActivity.vpnApplyNow() {
    if (VpnState.state == VpnState.S.ON) {
        toast("Переподключаю…")
        PharmVpnService.start(this)
    }
}

private fun MainActivity.splitSummary(): String {
    val n = Store.vpnSplitApps.size
    return when {
        Store.vpnSplitMode == 1 && n > 0 -> "Только выбранные: $n"
        Store.vpnSplitMode == 2 && n > 0 -> "Все, кроме выбранных: $n"
        else -> "Все приложения"
    }
}

/** Системные настройки VPN: там включается «Блокировать соединения без VPN». */
internal fun MainActivity.openSystemVpnSettings() {
    for (a in listOf(android.provider.Settings.ACTION_VPN_SETTINGS, android.provider.Settings.ACTION_WIRELESS_SETTINGS, android.provider.Settings.ACTION_SETTINGS)) {
        try { startActivity(Intent(a)); return } catch (e: Exception) { /* пробуем более общий экран */ }
    }
    toast("Не удалось открыть настройки")
}

/**
 * Подсказка про kill switch. Статус знает только сам сервис (VpnService.isLockdownEnabled, API 29+) и пишет его в Store при подключении.
 * Без «Блокировать соединения без VPN» при обрыве туннеля трафик уходит напрямую.
 */
private fun MainActivity.killSwitchHint(): LinearLayout? {
    if (Build.VERSION.SDK_INT < 29) return null
    val st = Store.vpnLockdown   // 0 — ещё не знаем, 1 — выключено, 2 — включено
    val accent = if (st == 2) GREEN else GOLD
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(mix(accent, 0.10f), 14, edge(accent))
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = lp(10)
    }
    box.addView(txt(
        when (st) { 2 -> "Kill switch включён"; 1 -> "Kill switch выключен"; else -> "Kill switch (защита от обрыва)" },
        15f, accent, true))
    box.addView(txt(
        when (st) {
            2 -> "Если VPN оборвётся, трафик не уйдёт напрямую, а будет заблокирован до восстановления туннеля."
            1 -> "Если VPN оборвётся, трафик пойдёт напрямую, в обход защиты. В системных настройках VPN нажмите ⚙ рядом с PharmShop и включите «Always-on VPN» и «Блокировать соединения без VPN»."
            else -> "Статус определится после первого подключения. Включить защиту можно в системных настройках VPN: ⚙ рядом с PharmShop → «Always-on VPN» и «Блокировать соединения без VPN»."
        }, 12.5f, MUTE).apply { layoutParams = lp(0).apply { topMargin = dp(2) } })
    if (st != 2) {
        box.addView(chip("Открыть настройки VPN", true) { openSystemVpnSettings() }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
        })
    }
    return box
}

internal fun MainActivity.vpnSettingsBlock(body: LinearLayout, again: () -> Unit, withTitle: Boolean = true) {
    if (withTitle) body.addView(txt("VPN", 20f, TEXT, true).apply { layoutParams = lp(10).apply { topMargin = dp(10) } })
    val c = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(CARD, 18, LINE)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = lp(12)
    }

    killSwitchHint()?.let { c.addView(it) }

    // диагностика в один тап: сервер, пинг, файлы маршрутов, последняя ошибка; отчёт можно отправить в поддержку
    c.addView(gbtn("Проверить VPN", false) { openVpnDiag() }.apply { layoutParams = lp(10) })

    val geo = GeoFiles.ready(this)
    val dnsNote = txt("", 12f, MUTE).apply { layoutParams = lp(10) }
    fun drawDnsNote() {
        dnsNote.text = VpnDns.current().note +
            if (Store.vpnBypassRu && geo) "\nРоссийские сайты (.ru, .su, .рф) резолвятся через Яндекс DNS напрямую, не через VPN." else ""
    }
    drawDnsNote()
    c.addView(switchRow(
        "Российские сайты напрямую",
        if (geo) "Банки, Госуслуги и другие .ru — без VPN, заблокированное в РФ — через VPN"
        else "Файлы маршрутов скачаются после первого подключения (раз в неделю обновляются сами)",
        Store.vpnBypassRu
    ) { on ->
        Store.vpnBypassRu = on
        drawDnsNote()
        vpnApplyNow()
    })

    // блокировка рекламы: нужен geosite.dat именно с тегом category-ads-all, иначе ядро не запустится
    val adsReady = geo && GeoFiles.hasSite(this)
    c.addView(switchRow(
        "Блокировать рекламу",
        when {
            !geo -> "Файлы маршрутов скачаются после первого подключения"
            !adsReady -> "В текущем geosite.dat нет списка ${GeoFiles.ADS_TAG}: блокировка недоступна"
            else -> "Рекламные и трекерные домены не открываются (geosite:${GeoFiles.ADS_TAG})"
        },
        Store.vpnBlockAds && adsReady,
        enabled = adsReady
    ) { on ->
        Store.vpnBlockAds = on
        vpnApplyNow()
    })

    // выбор DNS
    c.addView(txt("DNS", 16f, TEXT, true).apply { setPadding(0, dp(8), 0, dp(6)) })
    val dnsHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fun drawDns() {
        dnsHolder.removeAllViews()
        dnsHolder.addView(segmented(VpnDns.PRESETS.map { it.title }, Store.vpnDns) { i ->
            Store.vpnDns = i
            drawDns()
            drawDnsNote()
            vpnApplyNow()
        })
    }
    drawDns()
    c.addView(dnsHolder)
    c.addView(dnsNote)

    c.addView(switchRow(
        "IPv6",
        "Выключено — быстрее: сайты и приложения сразу идут по IPv4, без ожидания таймаута",
        Store.vpnIpv6
    ) { on ->
        Store.vpnIpv6 = on
        vpnApplyNow()
    })

    // автоподключение: при включении телефона или в мобильной сети
    c.addView(txt("Автоподключение", 16f, TEXT, true).apply { setPadding(0, dp(8), 0, dp(6)) })
    val autoNote = txt("", 12f, MUTE).apply { layoutParams = lp(10) }
    fun drawAutoNote() {
        val base = when (Store.vpnAuto) {
            1 -> "VPN включится сам, когда телефон загрузится."
            2 -> "VPN включится сам в мобильной сети (когда нет Wi‑Fi) и при загрузке телефона. Если выключить его вручную, до смены сети он не включится."
            else -> "VPN включается только кнопкой, плиткой или ярлыком."
        }
        val needAllow = Store.vpnAuto != 0 && android.net.VpnService.prepare(this) != null
        autoNote.text = base +
            (if (needAllow) "\nСначала один раз подключитесь вручную: система должна выдать разрешение на VPN." else "") +
            (if (Store.vpnAuto != 0) "\nЕсли телефон не даёт приложению запускаться сам (экономия заряда), разрешите это в настройках батареи." else "")
    }
    drawAutoNote()
    val autoHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fun drawAuto() {
        autoHolder.removeAllViews()
        autoHolder.addView(segmented(listOf("Выкл", "При включении", "В моб. сети"), Store.vpnAuto) { i ->
            Store.vpnAuto = i
            Store.vpnManualOff = false
            VpnAutostart.register(this, force = true)
            drawAuto()
            drawAutoNote()
        })
    }
    drawAuto()
    c.addView(autoHolder)
    c.addView(autoNote)

    // обход DPI: фрагментация TLS-handshake на соединении до сервера
    c.addView(switchRow(
        "Обход DPI (экспериментально)",
        "Для провайдеров, которые режут handshake: первый пакет к серверу уходит кусками. Для Reality помогает не всегда, проверьте на своей сети",
        Store.vpnFragment
    ) { on ->
        Store.vpnFragment = on
        vpnApplyNow()
    })

    // выбор приложений
    val sub = txt(splitSummary(), 12f, MUTE)
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = lp(0)
        setPadding(0, dp(8), 0, dp(4))
        isClickable = true
        contentDescription = "Приложения в VPN"
        setOnClickListener { vpnPickApps { again() } }
        addView(LinearLayout(this@vpnSettingsBlock).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(txt("Приложения в VPN", 16f, TEXT, true))
            addView(sub)
        })
        addView(txt("›", 22f, MUTE))
    }
    pressable(row)
    c.addView(row)
    body.addView(c)
}

/** Защита VPN (раньше была в «Защите данных»): прятать ссылку подписки, PIN при выходе; подсказка про виджет. */
internal fun MainActivity.vpnPrivacyBlock(body: LinearLayout) {
    body.addView(txt("Защита", 16f, TEXT, true).apply { layoutParams = lp(8).apply { topMargin = dp(4) } })
    val c = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(CARD, 18, LINE)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = lp(12)
    }
    val lock = lockEnabled()
    c.addView(switchRow("Прятать ссылку подписки", "Ссылка показывается только после нажатия на неё", Store.vpnMaskLink) { on ->
        Store.vpnMaskLink = on
    })
    c.addView(switchRow(
        "PIN для выхода из VPN",
        if (lock) "Спрашивать PIN при выходе из аккаунта и смене ссылки" else "Сначала включите вход по PIN или отпечатку в Настройки → Безопасность",
        Store.vpnPinGuard && lock, lock
    ) { on -> Store.vpnPinGuard = on })
    c.addView(txt("Виджет VPN: удерживайте палец на рабочем столе → «Виджеты» → PharmShop.", 12f, MUTE).apply {
        layoutParams = lp(0).apply { topMargin = dp(6) }
    })
    body.addView(c)
}

/** Окно выбора приложений: режим (все / только выбранные / все, кроме выбранных), поиск и список приложений из лаунчера. */
internal fun MainActivity.vpnPickApps(onDone: () -> Unit) {
    val act = this
    val picked = HashSet(Store.vpnSplitApps)
    val before = Store.vpnSplitMode to HashSet(picked)
    val dlg = sheet("Приложения в VPN", scroll = true) { box, _ ->
        val modeHolder = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val hint = txt("", 12.5f, MUTE).apply { layoutParams = lp(8) }
        val hints = listOf(
            "Через VPN идёт весь трафик, кроме самого приложения PharmShop.",
            "Через VPN идут только отмеченные приложения, остальные — напрямую.",
            "Через VPN идёт всё, кроме отмеченных приложений (например, банков)."
        )
        fun drawMode() {
            modeHolder.removeAllViews()
            modeHolder.addView(segmented(listOf("Все", "Выбранные", "Кроме"), Store.vpnSplitMode) { i ->
                Store.vpnSplitMode = i
                drawMode()
            })
            hint.text = hints[Store.vpnSplitMode]
        }
        drawMode()
        box.addView(modeHolder)
        box.addView(hint)

        val search = input("Поиск приложения", InputType.TYPE_CLASS_TEXT).apply { layoutParams = lp(8) }
        box.addView(search)
        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list)
        list.addView(txt("Загружаю список…", 14f, MUTE).apply { layoutParams = lp(8) })

        io.execute {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .filter { it.first != packageName }
                .distinctBy { it.first }
                .sortedBy { it.second.lowercase() }
            ui {
                list.removeAllViews()
                val rows = ArrayList<Triple<String, String, View>>()
                for ((pkg, label) in apps) {
                    val r = switchRow(label, pkg, pkg in picked) { on ->
                        if (on) picked.add(pkg) else picked.remove(pkg)
                        Store.vpnSplitApps = picked
                    }
                    list.addView(r)
                    rows.add(Triple(label.lowercase(), pkg.lowercase(), r))
                }
                search.addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {
                        val q = s?.toString()?.trim()?.lowercase().orEmpty()
                        for ((l, p, v) in rows) v.visibility = if (q.isEmpty() || l.contains(q) || p.contains(q)) View.VISIBLE else View.GONE
                    }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
            }
        }
    }
    dlg.setOnDismissListener {
        Store.vpnSplitApps = picked
        onDone()
        if (before != (Store.vpnSplitMode to HashSet(picked))) vpnApplyNow()
    }
}
