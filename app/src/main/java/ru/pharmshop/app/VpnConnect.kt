package ru.pharmshop.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// Кнопка «Подключить / Отключить» встроенного VPN, выбор сервера и запрос системного разрешения.
// Сам туннель — в PharmVpnService, ядро Xray — в VpnCore.

/** Включает или выключает VPN. Первый раз система покажет окно «Разрешить подключение». */
internal fun MainActivity.vpnToggle(url: String, live: Boolean) {
    when (VpnState.state) {
        VpnState.S.ON, VpnState.S.CONNECTING -> PharmVpnService.stop(this)
        else -> {
            if (!live) { toast("Подписка не активна"); return }
            Store.vpnSubUrl = url
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)   // без него уведомление «VPN включён» не видно
            }
            val ask = VpnService.prepare(this)
            if (ask == null) {
                PharmVpnService.start(this)
            } else {
                vpnPermOk = { PharmVpnService.start(this) }
                vpnPermission.launch(ask)
            }
        }
    }
}

/** Подключение с ярлыка и плитки: ссылка подписки уже сохранена, нажимать «Подключить» на экране не нужно. */
internal fun MainActivity.vpnQuickConnect() {
    if (VpnState.state == VpnState.S.ON || VpnState.state == VpnState.S.CONNECTING) return
    val url = Store.vpnSubUrl
    if (url.isEmpty()) { toast("Сначала откройте раздел VPN и нажмите «Подключить»"); return }
    val subs = VpnWatch.cached()
    val live = subs.length() == 0 || (0 until subs.length()).any { i ->
        subs.optJSONObject(i)?.let { it.optBoolean("active") && !it.optBoolean("frozen") } == true
    }
    vpnToggle(url, live)
}

private const val C_WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// ---------- пинг серверов ----------

private class PingMem(val at: Long, val ms: Int)
private val pingMem = ConcurrentHashMap<String, PingMem>()
private const val PING_TTL = 60_000L

private fun cachedPing(name: String): Int? =
    pingMem[name]?.takeIf { System.currentTimeMillis() - it.at < PING_TTL }?.ms

/** Замер (или свежий замер из памяти). Вызывать не в потоке интерфейса. */
private fun measurePing(s: VpnServer): Int {
    cachedPing(s.name)?.let { return it }
    val ms = VpnSub.ping(s)
    pingMem[s.name] = PingMem(System.currentTimeMillis(), ms)
    return ms
}

private fun MainActivity.pingColor(ms: Int): Int = when {
    ms < 0 -> MUTE
    ms < 90 -> GREEN
    ms < 220 -> GOLD
    else -> RED
}

private fun pingText(ms: Int) = if (ms < 0) "—" else "$ms мс"

/** «🇩🇪 Германия» → («🇩🇪», «Германия»). Если флага в названии нет — глобус. */
internal fun vpnSplitName(name: String): Pair<String, String> {
    val t = name.trim()
    if (t.isNotEmpty()) {
        val a = t.codePointAt(0)
        if (a in 0x1F1E6..0x1F1FF) {
            val i = Character.charCount(a)
            if (i < t.length) {
                val b = t.codePointAt(i)
                if (b in 0x1F1E6..0x1F1FF) {
                    val end = i + Character.charCount(b)
                    val rest = t.substring(end).trim().trimStart('-', '|', '·', '—').trim()
                    return t.substring(0, end) to rest.ifEmpty { t }
                }
            }
        }
    }
    return "🌐" to t
}

private fun sameServers(a: List<VpnServer>, b: List<VpnServer>) =
    a.size == b.size && a.indices.all { a[it].name == b[it].name }

// ---------- окно выбора сервера ----------

/**
 * Окно «Сервер»: «Автоматически» и список серверов с флагами и пингом.
 * Список показывается сразу из памяти (если уже загружали), свежий подгружается в фоне; пинг меряется параллельно.
 * Нажатие на сервер сразу выбирает его и, если VPN включён, переподключает. Кнопка внизу выбирает самый быстрый.
 */
internal fun MainActivity.vpnPickServer(url: String, onDone: () -> Unit) {
    val act = this
    sheet("Сервер", scroll = true) { box, close ->
        box.addView(txt("Загружаю список…", 14f, MUTE).apply { layoutParams = lp(8) })

        fun choose(name: String) {
            val changed = Store.vpnServer != name
            Store.vpnServer = name
            close()
            onDone()
            if (changed && VpnState.state == VpnState.S.ON) {
                toast("Переключаю сервер…")
                PharmVpnService.start(act)
            }
        }

        fun row(flag: String, title: String, sub: String?, sel: Boolean, pill: TextView?, onClick: () -> Unit): View {
            val check = FrameLayout(act).apply {
                background = GradientDrawable().apply {
                    setShape(GradientDrawable.OVAL)
                    if (sel) setColor(BLUE) else { setColor(Color.TRANSPARENT); setStroke(dp(1), LINE) }
                }
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { leftMargin = dp(10) }
                if (sel) addView(VpnIconView(act, VIc.CHECK, Color.BLACK).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER)
                })
            }
            val mid = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, C_WRAP, 1f)
                addView(txt(title, 15f, TEXT, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                if (sub != null) addView(txt(sub, 12f, MUTE).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            }
            return LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = if (sel) ripple(mix(BLUE, 0.12f), 14, edge(BLUE)) else ripple(CARD, 14, LINE)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = lp(8)
                isClickable = true
                setOnClickListener { onClick() }
                pressable(this)
                addView(txt(flag, 22f, TEXT).apply {
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(36), C_WRAP)
                })
                addView(mid)
                if (pill != null) addView(pill)
                addView(check)
            }
        }

        fun newPill(): TextView = txt("…", 12f, MUTE, true).apply {
            background = shape(BG, 10, LINE)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            layoutParams = LinearLayout.LayoutParams(C_WRAP, C_WRAP)
        }

        fun setPill(p: TextView, ms: Int) { p.text = pingText(ms); p.setTextColor(pingColor(ms)) }

        fun render(list: List<VpnServer>) {
            box.removeAllViews()
            val autoSel = Store.vpnServer.isEmpty()
            val cur = VpnSub.pick(list).name
            val results = IntArray(list.size) { -2 }
            val pills = ArrayList<TextView>()

            box.addView(row("⚡", "Автоматически", "Первый в списке: " + vpnSplitName(list.first().name).second, autoSel, null) { choose("") })
            for (s in list) {
                val (flag, nm) = vpnSplitName(s.name)
                val p = newPill()
                pills.add(p)
                box.addView(row(flag, nm, null, !autoSel && s.name == cur, p) { choose(s.name) })
            }

            val fast = gbtn("Измеряю пинг…", true) {
                var bi = -1
                var bm = Int.MAX_VALUE
                for (i in results.indices) if (results[i] in 0 until bm) { bm = results[i]; bi = i }
                if (bi >= 0) choose(list[bi].name) else toast("Ни один сервер не ответил")
            }.apply { alpha = 0.5f; isEnabled = false }
            box.addView(btnGrid(listOf(fast), 4).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(4)
            })

            val left = AtomicInteger(list.size)
            fun done() {
                if (left.decrementAndGet() == 0) { fast.text = "⚡ Выбрать самый быстрый"; fast.alpha = 1f; fast.isEnabled = true }
            }
            list.forEachIndexed { i, s ->
                val c = cachedPing(s.name)
                if (c != null) {
                    results[i] = c; setPill(pills[i], c); done()
                } else {
                    net.execute {
                        val ms = measurePing(s)
                        ui { results[i] = ms; setPill(pills[i], ms); done() }
                    }
                }
            }
        }

        io.execute {
            val quick = VpnSub.cachedAny(url)
            if (quick != null) ui { render(quick) }
            try {
                val fresh = VpnSub.loadFast(url)
                if (quick == null || !sameServers(quick, fresh)) ui { render(fresh) }
            } catch (e: Exception) {
                if (quick == null) ui {
                    box.removeAllViews()
                    box.addView(txt("Не удалось получить серверы: ${e.message}", 14f, RED).apply { layoutParams = lp(12) })
                    box.addView(btnGrid(listOf(gbtn("Повторить", true) { close(); vpnPickServer(url, onDone) }), 0))
                }
            }
        }
    }
}

/** Большая кнопка, карточка сервера и строка состояния. Подписывается на VpnState, пока находится на экране. */
internal fun MainActivity.vpnConnectBlock(url: String, live: Boolean, onExternal: () -> Unit): LinearLayout {
    val btn = vpnPrimary("Подключить", VIc.BOLT) { vpnToggle(url, live) }
    val label = btn.getChildAt(btn.childCount - 1) as TextView

    val status = txt("", 12.5f, MUTE).apply {
        gravity = Gravity.CENTER
        visibility = View.GONE
        layoutParams = lp(6)
    }

    // Подсказка про kill switch: показывается только при включённом VPN, если «Блокировать соединения без VPN» выключено (API 29+)
    val killHint = txt("⚠ Kill switch выключен: при обрыве VPN трафик пойдёт напрямую. Включить ›", 12f, GOLD).apply {
        gravity = Gravity.CENTER
        visibility = View.GONE
        isClickable = true
        setPadding(dp(8), dp(4), dp(8), dp(4))
        layoutParams = lp(6)
        setOnClickListener { openSystemVpnSettings() }
    }

    // карточка сервера: флаг, название, пинг; нажатие открывает список
    val flagTv = txt("🌐", 22f, TEXT).apply {
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(36), C_WRAP)
    }
    val capTv = txt("Сервер", 11f, MUTE)
    val nameTv = txt("Автоматически", 14.5f, TEXT, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    val pingTv = txt("", 12f, MUTE, true).apply {
        layoutParams = LinearLayout.LayoutParams(C_WRAP, C_WRAP).apply { rightMargin = dp(8) }
    }
    val arrow = txt("›", 22f, MUTE)

    fun refreshServer(list: List<VpnServer>?) {
        val auto = Store.vpnServer.isEmpty()
        val real = if (auto) list?.let { VpnAuto.label(it).name } else Store.vpnServer
        if (real == null) {
            flagTv.text = "🌐"; nameTv.text = "Автоматически"; pingTv.text = ""
        } else {
            val (f, n) = vpnSplitName(real)
            flagTv.text = f
            nameTv.text = n
            val ms = cachedPing(real)
            pingTv.text = if (ms == null) "" else pingText(ms)
            pingTv.setTextColor(if (ms == null) MUTE else pingColor(ms))
        }
        capTv.text = if (auto) "Сервер · автоматически" else "Сервер"
    }
    refreshServer(VpnSub.peek(url))

    /** Подтягивает список (из памяти или с панели) и замеряет пинг выбранного сервера. */
    fun reload(fetch: Boolean) {
        io.execute {
            val quick = VpnSub.cachedAny(url)
            if (quick != null) ui { refreshServer(quick) }
            val list = if (fetch) (try { VpnSub.loadFast(url) } catch (e: Exception) { quick }) else quick
            if (list != null) {
                measurePing(VpnSub.pick(list))
                ui { refreshServer(list) }
            }
        }
    }
    if (live) reload(true)

    val serverCard = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = ripple(BG, 16, LINE)
        setPadding(dp(12), dp(10), dp(14), dp(10))
        layoutParams = lp(6)
        isClickable = true
        contentDescription = "Выбрать сервер"
        setOnClickListener { vpnPickServer(url) { refreshServer(VpnSub.peek(url)); reload(false) } }
        pressable(this)
        addView(flagTv)
        addView(LinearLayout(this@vpnConnectBlock).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, C_WRAP, 1f)
            addView(capTv)
            addView(nameTv)
        })
        addView(pingTv)
        addView(arrow)
    }

    val external = txt("Открыть в Happ / v2rayTun", 12f, MUTE).apply {
        gravity = Gravity.CENTER
        isClickable = true
        setPadding(dp(8), dp(6), dp(8), dp(6))
        layoutParams = lp(0)
        setOnClickListener { onExternal() }
    }

    val paint: (VpnState.S, String) -> Unit = { s, msg ->
        killHint.visibility = if (s == VpnState.S.ON && Build.VERSION.SDK_INT >= 29 && Store.vpnLockdown == 1) View.VISIBLE else View.GONE
        when (s) {
            VpnState.S.OFF -> { label.text = "Подключить"; status.visibility = View.GONE }
            VpnState.S.CONNECTING -> {
                label.text = "Подключение…"
                status.text = msg.ifEmpty { "Поднимаю туннель" }   // при повторе после сбоя здесь причина и пауза
                status.setTextColor(MUTE); status.visibility = View.VISIBLE
            }
            VpnState.S.ON -> {
                label.text = "Отключить"
                status.text = "Защита включена" + if (msg.isNotEmpty()) " · $msg" else ""
                status.setTextColor(GREEN); status.visibility = View.VISIBLE
            }
            VpnState.S.ERROR -> {
                label.text = "Подключить"
                status.text = "Не удалось подключиться: $msg"
                status.setTextColor(RED); status.visibility = View.VISIBLE
            }
        }
    }
    btn.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            VpnState.listen(paint)
            paint(VpnState.state, VpnState.message)
        }
        override fun onViewDetachedFromWindow(v: View) { VpnState.unlisten(paint) }
    })

    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = lp(0)
        addView(btn)
        addView(status)
        addView(killHint)
        addView(serverCard)
        addView(external)
    }
}
