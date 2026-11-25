package ru.pharmshop.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build

/**
 * Автоподключение VPN (Настройки → VPN). Режимы Store.vpnAuto:
 *  0 — выключено;
 *  1 — при включении телефона (BootReceiver);
 *  2 — в мобильной сети: при загрузке и каждый раз, когда телефон оказывается в мобильной сети без Wi‑Fi.
 * Привязки к конкретному Wi‑Fi нет: имя сети на новых Android требует разрешения на геолокацию.
 *
 * Условия: ссылка подписки сохранена, подписка не закончилась (по кэшу), системное разрешение на VPN уже выдано
 * (окно «Разрешить подключение» без экрана не показать, поэтому первый раз подключаются вручную).
 * Если пользователь сам выключил VPN в мобильной сети, автоподключение до смены сети (Wi‑Fi) его не включает: Store.vpnManualOff.
 *
 * Слежение за сетью в режиме 2 — NetworkCallback с PendingIntent: система будит приложение, даже если процесса нет.
 * Это API 26+; на Android 7 в режиме 2 срабатывает только проверка из PollWorker раз в 15 минут.
 */
object VpnAutostart {
    private const val ACTION = "ru.pharmshop.app.VPN_NET"
    private const val REQ = 7301
    private const val MIN_GAP_MS = 20_000L
    @Volatile private var registered = false
    @Volatile private var lastStart = 0L

    private fun ready(ctx: Context): Boolean {
        if (Store.vpnSubUrl.isEmpty()) return false
        if (VpnState.state != VpnState.S.OFF) return false
        if (VpnService.prepare(ctx) != null) return false   // разрешение на VPN не выдано
        val subs = VpnWatch.cached()
        if (subs.length() > 0 && (0 until subs.length()).none { i ->
                subs.optJSONObject(i)?.let { it.optBoolean("active") && !it.optBoolean("frozen") } == true }) return false
        return true
    }

    private fun startQuiet(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastStart < MIN_GAP_MS) return
        lastStart = now
        try {
            PharmVpnService.start(ctx, auto = true)
        } catch (e: Exception) {
            // Android 12+ может не разрешить запуск службы из фона; тогда VPN включится, когда пользователь откроет приложение
            AppLog.e("vpn-auto", e)
        }
    }

    /** Телефон загрузился. */
    fun onBoot(ctx: Context) {
        Store.vpnManualOff = false
        when (Store.vpnAuto) {
            1 -> if (ready(ctx)) startQuiet(ctx)
            2 -> {
                register(ctx, force = true)
                if (VpnNet.kind(ctx) == VpnNet.CELL && ready(ctx)) startQuiet(ctx)
            }
        }
    }

    /** Сеть изменилась (режим 2). */
    fun onNetworkEvent(ctx: Context) {
        if (Store.vpnAuto != 2) return
        if (VpnNet.kind(ctx) != VpnNet.CELL) { Store.vpnManualOff = false; return }   // Wi‑Fi или нет сети: в следующий раз в мобильной сети включим
        if (Store.vpnManualOff) return
        if (ready(ctx)) startQuiet(ctx)
    }

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, VpnAutoReceiver::class.java).setAction(ACTION)
        // система дописывает в Intent сведения о сети, поэтому на Android 12+ PendingIntent должен быть изменяемым
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, REQ, i, flags)
    }

    /** Включает или выключает слежение за сетью по текущему режиму. force — перерегистрировать, даже если уже зарегистрировано. */
    fun register(ctx: Context, force: Boolean = false) {
        if (Build.VERSION.SDK_INT < 26) return
        val app = ctx.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Store.vpnAuto != 2) {
            if (registered || force) {
                try { cm.unregisterNetworkCallback(pending(app)) } catch (e: Exception) { }
                registered = false
            }
            return
        }
        if (registered && !force) return
        try {
            try { cm.unregisterNetworkCallback(pending(app)) } catch (e: Exception) { }
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm.registerNetworkCallback(req, pending(app))
            registered = true
        } catch (e: Exception) {
            AppLog.e("vpn-auto", e)
        }
    }

    /** Из PollWorker: подстраховка. Перерегистрирует слежение (процесс мог перезапуститься), а на Android 7 заменяет его проверкой раз в 15 минут. */
    fun poll(ctx: Context) {
        if (Store.vpnAuto != 2) return
        if (Build.VERSION.SDK_INT >= 26) register(ctx) else onNetworkEvent(ctx)
    }
}

/** Принимает события сети от системы (см. VpnAutostart.register). */
class VpnAutoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        Store.init(app)
        AppLog.init(app)
        VpnAutostart.onNetworkEvent(app)
    }
}
