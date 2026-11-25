package ru.pharmshop.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.TextView

/**
 * Диагностика VPN в один тап (Настройки → VPN → «Проверить VPN»).
 * Показывает: подключён ли VPN, достижим ли сервер и какой у него пинг, сколько серверов отвечает, не на штрафе ли какой,
 * прошла ли последняя проверка канала, возраст файлов маршрутов, IPv6, DNS, kill switch, какой сервер выбран автоматически
 * и последнюю ошибку подключения. Тот же отчёт можно сразу отправить в поддержку.
 * Ссылка подписки и данные входа в отчёт не попадают.
 */
object VpnDiag {
    /** ok: true — всё хорошо, false — проблема, null — просто сведения. */
    class Line(val ok: Boolean?, val text: String)

    private fun ago(ms: Long): String {
        val s = ((System.currentTimeMillis() - ms) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "$s с назад"
            s < 3600 -> "${s / 60} мин назад"
            s < 86400 -> "${s / 3600} ч назад"
            else -> "${s / 86400} дн. назад"
        }
    }

    private fun age(ms: Long): String {
        val d = ms / 86_400_000L
        return if (d <= 0) "обновлены сегодня" else "обновлены $d дн. назад"
    }

    /** Полный отчёт. Блокирующий (замеряет пинги, может скачать список серверов): вызывать не в потоке интерфейса. */
    fun collect(ctx: Context): List<Line> {
        val out = ArrayList<Line>()

        when (VpnState.state) {
            VpnState.S.ON -> {
                val up = ((SystemClock.elapsedRealtime() - VpnState.since) / 60_000L).coerceAtLeast(0)
                out.add(Line(true, "VPN включён" + (if (VpnState.message.isNotEmpty()) " · ${VpnState.message}" else "") + " · $up мин"))
            }
            VpnState.S.CONNECTING -> out.add(Line(null, "VPN подключается" + (if (VpnState.message.isNotEmpty()) ": ${VpnState.message}" else "")))
            VpnState.S.ERROR -> out.add(Line(false, "VPN не подключился: ${VpnState.message}"))
            VpnState.S.OFF -> out.add(Line(null, "VPN выключен"))
        }
        val net = VpnNet.kind(ctx)
        out.add(Line(net != VpnNet.NONE, "Сеть под VPN: ${VpnNet.name(net)}"))

        val url = Store.vpnSubUrl
        if (url.isEmpty()) {
            out.add(Line(false, "Ссылки подписки нет: откройте раздел VPN и нажмите «Подключить»"))
        } else {
            val list: List<VpnServer>? = try {
                VpnSub.cachedAny(url) ?: VpnSub.load(url)
            } catch (e: Exception) {
                out.add(Line(false, "Список серверов не загрузился: ${VpnErrors.classify(ctx, e).text}"))
                null
            }
            if (list != null) {
                val auto = Store.vpnServer.isEmpty()
                val chosen: VpnServer
                if (auto) {
                    val last = Store.vpnLastAuto
                    chosen = list.firstOrNull { it.name == last } ?: VpnAuto.label(list)
                    out.add(Line(null, "Режим «Автоматически»: " + (if (last.isEmpty()) "сервер ещё не выбирался, первым будет ${chosen.name}" else "сейчас ${chosen.name}")))
                } else {
                    chosen = VpnSub.pick(list)
                    out.add(Line(null, "Сервер выбран вручную: ${chosen.name}"))
                }
                val res = VpnAuto.measureAll(list, fresh = true)
                val p = res[chosen.name] ?: -1
                out.add(Line(p >= 0, if (p >= 0) "Сервер отвечает, пинг $p мс" else "Сервер не отвечает на проверку"))
                val alive = res.values.count { it >= 0 }
                out.add(Line(alive > 0, "Доступно серверов: $alive из ${list.size}"))
                val bad = list.filter { VpnAuto.penalized(it.name) }
                if (bad.isNotEmpty()) out.add(Line(false, "На штрафе (не пропускали трафик): " + bad.take(3).joinToString(", ") { it.name } + if (bad.size > 3) " и ещё ${bad.size - 3}" else ""))
            }
        }

        if (VpnState.state == VpnState.S.ON) {
            val at = Store.vpnProbeAt
            if (at > 0L) out.add(Line(Store.vpnProbeOk, "Проверка канала ${if (Store.vpnProbeOk) "прошла" else "не прошла"} ${ago(at)}"))
            else out.add(Line(null, "Проверка канала ещё не проводилась"))
        }

        val g = GeoFiles.ageMs(ctx)
        out.add(if (g < 0) Line(null, "Файлы маршрутов: нет, скачаются после первого подключения")
                else Line(g < 10L * 86_400_000L, "Файлы маршрутов ${age(g)}"))
        out.add(Line(null, "IPv6: ${if (Store.vpnIpv6) "включён" else "выключен"} · DNS: ${VpnDns.current().title}"))
        if (Store.vpnFragment) out.add(Line(null, "Обход DPI: включён"))
        if (Build.VERSION.SDK_INT >= 29) {
            when (Store.vpnLockdown) {
                2 -> out.add(Line(true, "Kill switch включён"))
                1 -> out.add(Line(false, "Kill switch выключен: при обрыве трафик пойдёт напрямую"))
                else -> out.add(Line(null, "Kill switch: статус станет известен после подключения"))
            }
        }

        val err = Store.vpnLastError
        if (err.isEmpty()) {
            out.add(Line(true, "Ошибок подключения не было"))
        } else {
            val raw = Store.vpnLastErrorRaw
            out.add(Line(false, "Последняя ошибка ${ago(Store.vpnLastErrorAt)}: $err" + if (raw.isNotEmpty() && !err.contains(raw)) "\n   Ядро: ${raw.take(100)}" else ""))
        }
        return out
    }

    fun plain(lines: List<Line>): String =
        lines.joinToString("\n") { (when (it.ok) { true -> "✅ "; false -> "⚠️ "; null -> "• " }) + it.text }

    /** Для чата поддержки: лимит сообщения на сервере — 1000 знаков. */
    fun supportText(report: String): String = ("🩺 Проверка VPN (${BuildConfig.VERSION_NAME})\n" + report).take(980)

    /** Одна короткая строка для «Сообщить об ошибке»: без сети и замеров. null — VPN не использовался. */
    fun brief(): String? {
        if (Store.vpnSubUrl.isEmpty()) return null
        val st = when (VpnState.state) {
            VpnState.S.ON -> "вкл"; VpnState.S.CONNECTING -> "подключается"; VpnState.S.ERROR -> "ошибка"; VpnState.S.OFF -> "выкл"
        }
        val srv = if (Store.vpnServer.isEmpty()) "авто: ${Store.vpnLastAuto.ifEmpty { "—" }}" else "вручную: ${Store.vpnServer}"
        val sb = StringBuilder("VPN: $st · $srv · IPv6 ${if (Store.vpnIpv6) "вкл" else "выкл"} · DNS ${VpnDns.current().title}")
        if (Store.vpnFragment) sb.append(" · DPI")
        if (Store.vpnLastError.isNotEmpty()) sb.append("\nОшибка VPN (").append(ago(Store.vpnLastErrorAt)).append("): ").append(Store.vpnLastError.take(90))
        return sb.toString()
    }
}

/** Окно «Проверка VPN»: идёт проверка, затем отчёт и кнопки «Копировать» и «Отправить в поддержку». */
internal fun MainActivity.openVpnDiag() {
    Diag.feature("vpn_check")
    sheet("Проверка VPN", scroll = true) { box, close ->
        val status = txt("Проверяю: сервер, пинг, файлы маршрутов…", 14f, MUTE).apply { layoutParams = lp(8) }
        val report = txt("", 13f, TEXT).apply {
            setTextIsSelectable(true)
            background = shape(CARD, 12, LINE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = lp(10)
            visibility = View.GONE
        }
        box.addView(status)
        box.addView(report)

        var text = ""
        var sending = false
        lateinit var send: TextView
        val copy = gbtn("Копировать", false) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("vpn-check", text))
            toast("Отчёт скопирован")
        }
        send = gbtn("В поддержку", true) {
            if (sending || text.isEmpty()) return@gbtn
            sending = true
            send.text = "Отправляем…"
            val msg = VpnDiag.supportText(text)
            val order = Store.orders().firstOrNull()?.optString("order_number").orEmpty()
            io.execute {
                try {
                    ChatApi.userSend(Store.deviceId, Store.deviceSecret, Store.lastUser, order, BuildConfig.VERSION_NAME, msg)
                    ui { toast("Отправлено. Ответ придёт в чат поддержки"); close() }
                } catch (e: Exception) {
                    AppLog.e("report", e)
                    ui { sending = false; send.text = "В поддержку"; toast("Не отправилось: ${e.message}") }
                }
            }
        }
        val buttons = btnGrid(listOf(copy, send, gbtn("Закрыть", false) { close() }), 0).apply { visibility = View.GONE }
        box.addView(buttons)

        io.execute {
            val lines = try {
                VpnDiag.collect(this)
            } catch (e: Exception) {
                AppLog.e("vpn-diag", e)
                listOf(VpnDiag.Line(false, "Не удалось собрать отчёт: ${e.message ?: e.javaClass.simpleName}"))
            }
            val t = VpnDiag.plain(lines)
            ui {
                text = t
                status.visibility = View.GONE
                report.text = t
                report.visibility = View.VISIBLE
                buttons.visibility = View.VISIBLE
            }
        }
    }
}
