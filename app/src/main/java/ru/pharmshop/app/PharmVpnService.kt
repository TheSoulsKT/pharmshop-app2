package ru.pharmshop.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Встроенный VPN: поднимает TUN-интерфейс и отдаёт его ядру Xray.
 * Сервер и ссылка подписки берутся из Store (их сохраняет экран VPN при нажатии «Подключить»).
 *
 * Пока пользователь не нажал «Отключить», сервис сам следит за туннелем: раз в CHECK_EVERY_S секунд просит ядро
 * сходить в интернет через туннель и при двух неудачах подряд (или если ядро остановилось) переподключается.
 * Смена сети (Wi-Fi ↔ мобильная) тоже запускает внеочередные проверки.
 */
class PharmVpnService : VpnService() {
    companion object {
        const val ACTION_START = "ru.pharmshop.app.VPN_START"
        const val ACTION_STOP = "ru.pharmshop.app.VPN_STOP"
        private const val CHANNEL = "pharm_vpn"
        private const val NID = 4101

        private const val PROBE_URL = "https://www.gstatic.com/generate_204"
        private const val CHECK_EVERY_S = 20L
        private const val MAX_RETRIES = 5

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, PharmVpnService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, PharmVpnService::class.java).setAction(ACTION_STOP))
        }
    }

    private val worker = Executors.newSingleThreadExecutor()          // подключение и отключение — строго по очереди
    private val watch = Executors.newSingleThreadScheduledExecutor()  // проверки и отложенные повторы
    private var tun: ParcelFileDescriptor? = null

    /** Пользователь хочет, чтобы VPN работал. Сбрасывается только по «Отключить», отзыву системой или окончательной ошибке. */
    @Volatile private var wanted = false
    @Volatile private var fails = 0
    @Volatile private var retries = 0
    private var watchTask: ScheduledFuture<*>? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            wanted = false
            stopWatching()
            goForeground("Отключение…")
            worker.execute {
                teardown()
                VpnState.set(VpnState.S.OFF)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        // ACTION_START, а также перезапуск системой («Всегда включённый VPN») — тогда intent == null
        wanted = true
        retries = 0
        goForeground("Подключение…")
        VpnState.set(VpnState.S.CONNECTING)
        worker.execute { connect(false) }
        return START_STICKY
    }

    /** auto = true — это повтор после обрыва, а не нажатие пользователя: ошибка не показывается сразу, а повторяется с паузой. */
    private fun connect(auto: Boolean) {
        if (!wanted) return
        try {
            teardown()   // переподключение (например, после смены сервера)
            val url = Store.vpnSubUrl
            if (url.isEmpty()) throw VpnSub.SubException("Нет ссылки подписки. Откройте раздел VPN и нажмите «Подключить»")
            val server = VpnSub.pick(VpnSub.loadFast(url))
            val config = VpnSub.buildConfig(server)

            val b = Builder()
                .setSession("PharmShop VPN")
                .setMtu(1500)
                .addAddress("172.19.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addAddress("fdfe:dcba:9876::1", 126)
                .addRoute("::", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
            // само приложение (запросы к панели, обновления) и сокеты ядра идут мимо туннеля — иначе трафик ядра зациклится
            try { b.addDisallowedApplication(packageName) } catch (e: Exception) { }
            if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)

            val pfd = b.establish() ?: throw IllegalStateException("Система не создала VPN-интерфейс: разрешение отозвано")
            tun = pfd
            VpnCore.start(this, config, pfd.fd)

            VpnState.set(VpnState.S.ON, server.name)
            goForeground("Подключено · ${server.name}")
            fails = 0
            retries = 0
            startWatching()
        } catch (e: Throwable) {   // Throwable: ошибки загрузки нативной библиотеки — не Exception
            teardown()
            AppLog.e("vpn", e)
            if (wanted && auto && retries < MAX_RETRIES) {
                retries++
                val wait = minOf(5L * retries, 30L)
                VpnState.set(VpnState.S.CONNECTING, "Переподключение, попытка $retries из $MAX_RETRIES")
                goForeground("Переподключение…")
                try { watch.schedule({ worker.execute { connect(true) } }, wait, TimeUnit.SECONDS) } catch (x: Exception) { }
                return
            }
            wanted = false
            stopWatching()
            VpnState.set(VpnState.S.ERROR, e.message ?: e.javaClass.simpleName)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun teardown() {
        VpnCore.stop()
        try { tun?.close() } catch (e: Exception) { }
        tun = null
    }

    // ---------- слежение за туннелем ----------

    @Synchronized
    private fun startWatching() {
        stopWatching()
        try {
            watchTask = watch.scheduleWithFixedDelay({ healthCheck() }, CHECK_EVERY_S, CHECK_EVERY_S, TimeUnit.SECONDS)
        } catch (e: Exception) { }
        registerNet()
    }

    @Synchronized
    private fun stopWatching() {
        watchTask?.cancel(false)
        watchTask = null
        unregisterNet()
    }

    private fun healthCheck() {
        if (!wanted || VpnState.state != VpnState.S.ON) return
        if (!VpnCore.running) { reconnect("ядро остановилось"); return }
        val d = VpnCore.measureDelay(PROBE_URL) ?: return   // null — проверка недоступна в этой сборке ядра, не трогаем туннель
        if (d >= 0) { fails = 0; return }
        fails++
        AppLog.e("vpn", "проверка туннеля не прошла ($fails)")
        if (fails >= 2) reconnect("туннель не отвечает")
    }

    private fun reconnect(why: String) {
        if (!wanted) return
        AppLog.e("vpn", "переподключение: $why")
        fails = 0
        stopWatching()
        VpnState.set(VpnState.S.CONNECTING, "Переподключение…")
        goForeground("Переподключение…")
        worker.execute { connect(true) }
    }

    /**
     * Следим за физической сетью. Приложение вынесено из туннеля, поэтому «сеть по умолчанию» для него — настоящий Wi-Fi или LTE.
     * Сообщаем её системе как нижележащую (иначе Android считает, что под VPN ничего нет) и после смены проверяем туннель.
     */
    @Synchronized
    private fun registerNet() {
        unregisterNet()
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var first = true
            override fun onAvailable(network: Network) {
                try { setUnderlyingNetworks(arrayOf(network)) } catch (e: Exception) { }
                if (first) { first = false; return }   // первый вызов — просто текущая сеть, а не смена
                for (s in longArrayOf(3, 8)) {
                    try { watch.schedule({ healthCheck() }, s, TimeUnit.SECONDS) } catch (e: Exception) { }
                }
            }
            override fun onLost(network: Network) {
                try { setUnderlyingNetworks(null) } catch (e: Exception) { }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCb = cb
        } catch (e: Exception) {
            AppLog.e("vpn", e)
        }
    }

    @Synchronized
    private fun unregisterNet() {
        val cb = netCb ?: return
        netCb = null
        try { (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(cb) } catch (e: Exception) { }
    }

    /** Система или другой VPN забрали туннель. */
    override fun onRevoke() {
        wanted = false
        stopWatching()
        worker.execute {
            teardown()
            VpnState.set(VpnState.S.OFF)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        wanted = false
        stopWatching()
        teardown()
        if (VpnState.state != VpnState.S.ERROR) VpnState.set(VpnState.S.OFF)
        watch.shutdownNow()
        worker.shutdown()
        super.onDestroy()
    }

    // ---------- уведомление (для foreground-сервиса обязательно) ----------

    private fun goForeground(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "VPN", NotificationManager.IMPORTANCE_LOW))
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, LauncherActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        val off = PendingIntent.getService(this, 1, Intent(this, PharmVpnService::class.java).setAction(ACTION_STOP), flags)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_star)
            .setContentTitle("PharmShop VPN")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Отключить", off)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        // тип сервиса обязателен только с Android 14; на старых версиях систему он не интересует
        if (Build.VERSION.SDK_INT >= 34) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        else startForeground(NID, n)
    }
}
