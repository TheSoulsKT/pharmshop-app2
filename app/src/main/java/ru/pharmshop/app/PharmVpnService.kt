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
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Встроенный VPN: поднимает TUN-интерфейс и отдаёт его ядру Xray.
 * Сервер и ссылка подписки берутся из Store (их сохраняет экран VPN при нажатии «Подключить»).
 *
 * После подключения раз в HEALTH_EVERY_MS идёт проверка канала: запрос generate_204 через сам туннель (VpnProbe).
 * Три сбоя подряд при живой прямой сети — сервер штрафуется (VpnAuto.markBad), а в режиме «Автоматически» сервис
 * переподключается: VpnAuto.ranked() ставит упавший сервер после здоровых и берёт следующий.
 */
class PharmVpnService : VpnService() {
    companion object {
        const val ACTION_START = "ru.pharmshop.app.VPN_START"
        const val ACTION_STOP = "ru.pharmshop.app.VPN_STOP"
        /** Запуск не пользователем, а автоподключением: «выключил сам» (vpnManualOff) при этом не сбрасывается. */
        const val EXTRA_AUTO = "auto"
        private const val CHANNEL = "pharm_vpn"
        private const val NID = 4101

        fun start(ctx: Context, auto: Boolean = false) {
            ContextCompat.startForegroundService(ctx,
                Intent(ctx, PharmVpnService::class.java).setAction(ACTION_START).putExtra(EXTRA_AUTO, auto))
        }

        fun stop(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, PharmVpnService::class.java).setAction(ACTION_STOP))
        }

        @Volatile private var instance: PharmVpnService? = null

        /** Перечитать состояние «Блокировать соединения без VPN» (например, после возврата из системных настроек VPN). Если VPN выключен — ничего. */
        fun recheckLockdown() { instance?.let { s -> try { s.worker.execute { s.checkLockdown() } } catch (e: Exception) { } } }

        private const val HEALTH_FIRST_MS = 4_000L      // первая проверка после подключения
        private const val HEALTH_EVERY_MS = 30_000L     // пока всё хорошо
        private const val HEALTH_RETRY_MS = 4_000L      // после сбоя перепроверяем чаще
        private const val HEALTH_FAILS = 3              // столько сбоев подряд — канал считается мёртвым
        private const val MAX_FAILOVERS = 3             // подряд переключений без единой удачной проверки
    }

    private val worker = Executors.newSingleThreadExecutor()
    private var tun: ParcelFileDescriptor? = null

    // ---- слежение за сменой сети (Wi‑Fi ⇄ LTE) ----
    private val main = Handler(Looper.getMainLooper())
    private var netCb: ConnectivityManager.NetworkCallback? = null
    @Volatile private var curNet: Network? = null
    @Volatile private var netLost = false
    @Volatile private var connectedAt = 0L
    // ---- проверка канала ----
    private val probeExec = Executors.newSingleThreadExecutor()
    @Volatile private var probeCfg: VpnProbe.Cfg? = null
    @Volatile private var healthGen = 0
    @Volatile private var fails = 0
    @Volatile private var failovers = 0
    @Volatile private var curServer: VpnServer? = null
    @Volatile private var degraded = false
    private val healthTick = Runnable { runProbe() }
    // ---- повтор подключения после сбоя ----
    @Volatile private var attempt = 0                       // сколько повторов уже было подряд (сбрасывается при удачном подключении)
    @Volatile private var stopping = false        // пользователь отключил VPN: повторов больше нет
    private val retryTask = Runnable {
        try { worker.execute { if (VpnState.state == VpnState.S.CONNECTING && !stopping) connect() } } catch (e: Exception) { }
    }

    private val netSwitch = Runnable {
        worker.execute {
            if (VpnState.state != VpnState.S.ON) return@execute
            failovers = 0   // другая сеть — запасным серверам дан новый шанс
            VpnState.set(VpnState.S.CONNECTING)
            goForeground("Смена сети…")
            connect()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // сервис может стартовать в «холодном» процессе (перезапуск системой, плитка): хранилище нужно поднять самим
        Store.init(applicationContext)
        AppLog.init(applicationContext)
        VpnAuto.init(applicationContext)
        PetFeed.init()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopping = true
            Store.vpnManualOff = true   // выключил сам: автоподключение в мобильной сети не включит VPN снова до смены сети
            main.removeCallbacks(retryTask)
            goForeground("Отключение…")
            unwatchNetwork()
            worker.execute {
                teardown()
                VpnState.set(VpnState.S.OFF)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        // ACTION_START, а также перезапуск системой («Всегда включённый VPN») — тогда intent == null
        stopping = false
        attempt = 0
        main.removeCallbacks(retryTask)
        if (intent != null && !intent.getBooleanExtra(EXTRA_AUTO, false)) Store.vpnManualOff = false
        goForeground("Подключение…")
        VpnState.set(VpnState.S.CONNECTING)
        worker.execute { connect() }
        return START_STICKY
    }

    private fun connect() {
        try {
            teardown()   // переподключение (смена сервера, смена сети)
            val url = Store.vpnSubUrl
            if (url.isEmpty()) throw VpnSub.SubException("Нет ссылки подписки. Откройте раздел VPN и нажмите «Подключить»")
            var list = loadList(url, true)
            var geo = GeoFiles.prepare(this)

            var err: Throwable? = null
            var pass = 0
            while (true) {
                // «Автоматически» (сервер не выбран): самый быстрый по пингу, при неудаче — следующие два
                val auto = Store.vpnServer.isEmpty()
                val candidates = if (auto) VpnAuto.ranked(list).take(3) else listOf(VpnSub.pick(list))
                for (server in candidates) {
                    try {
                        try {
                            startTunnel(server, geo)
                        } catch (e: Throwable) {
                            if (!geo) throw e
                            // возможно, битый .dat: стираем и остаёмся без geo до следующего обновления
                            teardown(); GeoFiles.reset(this); geo = false
                            startTunnel(server, false)
                        }
                        if (auto) Store.vpnLastAuto = server.name
                        connectedAt = SystemClock.elapsedRealtime()
                        attempt = 0
                        VpnState.set(VpnState.S.ON, server.name)
                        goForeground("Подключено · ${server.name}")
                        watchNetwork()
                        startHealth(server)
                        if (!geo) Thread { try { GeoFiles.updateIfStale(applicationContext) } catch (e: Exception) { } }.start()
                        return
                    } catch (e: Throwable) {
                        err = e
                        teardown()
                    }
                }
                // подключались по сохранённой копии списка, а она могла устареть (ключи сменили): берём свежий и пробуем ещё раз
                if (pass == 0 && VpnSub.fromStore) { pass = 1; list = loadList(Store.vpnSubUrl.ifEmpty { url }, false); continue }
                break
            }
            throw err ?: IllegalStateException("Нет доступных серверов")
        } catch (e: Throwable) {   // Throwable: ошибки загрузки нативной библиотеки — не Exception
            fail(e)
        }
    }

    /**
     * Список серверов с защитой от ложного «лимита устройств». Отказ панели (401/403) бывает не только из-за лимита:
     * ссылку могли перевыпустить (старая в Store), панель могла ответить временным отказом. Поэтому при отказе
     * 1) сверяем ссылку с кабинетом, 2) повторяем запрос, 3) если отказ остался, а сохранённая копия списка есть,
     * подключаемся по ней, и только без копии показываем ошибку.
     */
    private fun loadList(url0: String, fast: Boolean): List<VpnServer> {
        try {
            return if (fast) VpnSub.loadForConnect(url0) else VpnSub.load(url0)
        } catch (e: VpnSub.DeviceRejected) {
            AppLog.e("vpn-sub-rejected", e)
            var url = url0
            if (VpnApi.loggedIn()) {
                try {
                    VpnWatch.refresh(this)   // заодно синхронизирует ссылку (VpnSub.syncUrl)
                    val fresh = Store.vpnSubUrl
                    if (fresh.isNotEmpty()) url = fresh
                } catch (x: Exception) { AppLog.e("vpn-sync-url", x) }
            }
            try { Thread.sleep(1500) } catch (x: InterruptedException) { }
            try {
                return VpnSub.load(url)
            } catch (e2: VpnSub.DeviceRejected) {
                VpnSub.storedList()?.let { return it }
                throw e2
            }
        }
    }

    /**
     * Сбой подключения. Если причина временная (нет сети, панель перегружена), повторяем с нарастающей паузой
     * (VpnErrors.RETRY_DELAYS), а на экране остаётся «подключение» с пояснением. Если ждать нечего (подписка закончилась,
     * лимит устройств, нет ядра) или повторы кончились, показываем понятную ошибку сразу.
     */
    private fun fail(e: Throwable) {
        unwatchNetwork()
        teardown()
        val f = VpnErrors.classify(this, e)
        VpnErrors.record(f, e)
        AppLog.e("vpn-connect", e)
        val delays = VpnErrors.RETRY_DELAYS
        if (f.retry && !stopping && attempt < delays.size) {
            val d = delays[attempt]
            attempt++
            val note = "${f.text} — повтор через $d с ($attempt/${delays.size})"
            VpnState.set(VpnState.S.CONNECTING, note)
            goForeground(note)
            main.removeCallbacks(retryTask)
            main.postDelayed(retryTask, d * 1000L)
            return
        }
        VpnState.set(VpnState.S.ERROR, f.text)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Поднимает TUN и отдаёт его ядру. Состав приложений зависит от настройки «Приложения в VPN». */
    private fun startTunnel(server: VpnServer, geo: Boolean) {
        val probe = VpnProbe.newCfg()
        probeCfg = probe
        // реклама блокируется, только если в geosite.dat реально есть тег (иначе ядро не запустится)
        val ads = Store.vpnBlockAds && geo && GeoFiles.hasSite(applicationContext)
        val config = VpnSub.buildConfig(server, geo, probe, ads)

        val b = Builder()
            .setSession("PharmShop VPN")
            .setMtu(1500)
            .addAddress("172.19.0.1", 30)
            .addRoute("0.0.0.0", 0)
            .addAddress("fdfe:dcba:9876::1", 126)
            .addRoute("::", 0)
        VpnDns.current().servers.forEach { b.addDnsServer(it) }

        // Приложения. Сами приложение и сокеты ядра идут мимо туннеля — иначе трафик ядра зациклится.
        // addAllowedApplication и addDisallowedApplication взаимоисключающие: в одном туннеле нельзя оба.
        val picked = Store.vpnSplitApps - packageName
        var allowed = 0
        if (Store.vpnSplitMode == 1 && picked.isNotEmpty()) {
            for (p in picked) try { b.addAllowedApplication(p); allowed++ } catch (e: Exception) { /* удалено с телефона */ }
        }
        if (allowed == 0) {
            try { b.addDisallowedApplication(packageName) } catch (e: Exception) { }
            if (Store.vpnSplitMode == 2) for (p in picked) try { b.addDisallowedApplication(p) } catch (e: Exception) { }
        }
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)

        val pfd = b.establish() ?: throw IllegalStateException("Система не создала VPN-интерфейс: разрешение отозвано")
        tun = pfd
        checkLockdown()
        curNet?.let { try { setUnderlyingNetworks(arrayOf(it)) } catch (e: Exception) { } }
        VpnCore.start(this, config, pfd.fd)
    }

    // ---------- смена сети ----------

    /**
     * Сообщаем системе, какая сеть лежит под туннелем (иначе она может неверно показывать трафик и «не заметить» смену сети),
     * а при переходе Wi‑Fi ⇄ LTE или возврате связи переподключаемся: старые сокеты мертвы, «Автоматически» заодно выберет
     * лучший сервер для новой сети. Первое срабатывание после подключения и всплески раньше чем через 5 с игнорируем.
     */
    private fun watchNetwork() {
        if (netCb != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
                val prev = curNet
                val changed = (prev != null && prev != network) || netLost
                curNet = network
                netLost = false
                try { setUnderlyingNetworks(arrayOf(network)) } catch (e: Exception) { }
                if (!changed) return
                main.removeCallbacks(netSwitch)
                if (SystemClock.elapsedRealtime() - connectedAt > 5000) main.postDelayed(netSwitch, 2000)
            }

            override fun onLost(network: Network) {
                if (network != curNet) return
                netLost = true
                try { setUnderlyingNetworks(null) } catch (e: Exception) { }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCb = cb
        } catch (e: Exception) {
            AppLog.e("vpn-net", e)
        }
    }

    private fun unwatchNetwork() {
        main.removeCallbacks(netSwitch)
        val cb = netCb ?: return
        netCb = null
        try { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb) } catch (e: Exception) { }
        curNet = null
        netLost = false
    }

    private fun teardown() {
        stopHealth()
        VpnCore.stop()
        try { tun?.close() } catch (e: Exception) { }
        tun = null
    }

    /** Система или другой VPN забрали туннель. */
    override fun onRevoke() {
        unwatchNetwork()
        worker.execute {
            teardown()
            VpnState.set(VpnState.S.OFF)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        instance = null
        main.removeCallbacks(retryTask)
        unwatchNetwork()
        teardown()
        if (VpnState.state != VpnState.S.ERROR) VpnState.set(VpnState.S.OFF)
        probeExec.shutdownNow()
        worker.shutdown()
        super.onDestroy()
    }

    // ---------- kill switch ----------

    /**
     * «Блокировать соединения без VPN» (API 29+). Это свойство самого сервиса, поэтому узнать его можно только здесь,
     * пока VPN поднят; результат кладём в Store, откуда его читают настройки. Без этой опции при обрыве туннеля трафик уходит напрямую.
     */
    private fun checkLockdown() {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val v = if (isLockdownEnabled) 2 else 1
            if (Store.vpnLockdown != v) {
                Store.vpnLockdown = v
                VpnState.refresh()   // подсказка на экране VPN обновится сразу
            }
        } catch (e: Exception) {
            AppLog.e("vpn-lockdown", e)
        }
    }

    // ---------- проверка канала ----------

    private fun startHealth(server: VpnServer) {
        healthGen++
        fails = 0
        degraded = false
        curServer = server
        main.removeCallbacks(healthTick)
        main.postDelayed(healthTick, HEALTH_FIRST_MS)
    }

    private fun stopHealth() {
        healthGen++   // результаты уже запущенных проверок устареют
        main.removeCallbacks(healthTick)
    }

    private fun scheduleHealth(delay: Long) {
        main.removeCallbacks(healthTick)
        main.postDelayed(healthTick, delay)
    }

    /** Одна проверка: generate_204 через туннель. Запускается на главном потоке, сам запрос — в отдельном. */
    private fun runProbe() {
        val gen = healthGen
        val cfg = probeCfg ?: return
        if (VpnState.state != VpnState.S.ON) return
        if (netLost) { scheduleHealth(HEALTH_EVERY_MS); return }   // сети под туннелем нет: сервер тут ни при чём
        try {
            probeExec.execute {
                val ok = VpnProbe.viaTunnel(cfg)
                main.post { if (gen == healthGen) onProbe(ok) }
            }
        } catch (e: Exception) { /* сервис закрывается */ }
    }

    private fun onProbe(ok: Boolean) {
        val server = curServer ?: return
        Store.vpnProbeAt = System.currentTimeMillis()
        Store.vpnProbeOk = ok
        if (ok) {
            fails = 0
            failovers = 0
            VpnAuto.clearBad(server.name)
            if (degraded) { degraded = false; goForeground("Подключено · ${server.name}") }
            worker.execute { checkLockdown() }
            scheduleHealth(HEALTH_EVERY_MS)
            return
        }
        fails++
        AppLog.e("vpn-probe", RuntimeException("generate_204 через туннель не прошёл ($fails/$HEALTH_FAILS): ${server.name}"))
        if (fails < HEALTH_FAILS) { scheduleHealth(HEALTH_RETRY_MS); return }
        fails = 0
        val gen = healthGen
        // Прежде чем винить сервер, проверяем прямую сеть (приложение вне туннеля): нет её — меняется не сервер, а сеть
        try {
            probeExec.execute {
                val direct = VpnProbe.direct()
                main.post { if (gen == healthGen) onChannelDead(server, direct) }
            }
        } catch (e: Exception) { }
    }

    private fun onChannelDead(server: VpnServer, directOk: Boolean) {
        if (!directOk) { scheduleHealth(HEALTH_EVERY_MS); return }
        VpnAuto.markBad(server.name)
        val auto = Store.vpnServer.isEmpty()
        if (!auto || failovers >= MAX_FAILOVERS) {
            // сервер выбран вручную (его меняет только пользователь) либо запасные кончились: честно говорим, что связи нет
            degraded = true
            goForeground(if (auto) "Нет связи ни через один сервер" else "Подключено · ${server.name} · сервер не отвечает")
            scheduleHealth(HEALTH_EVERY_MS)
            return
        }
        failovers++
        worker.execute {
            if (VpnState.state != VpnState.S.ON) return@execute
            VpnState.set(VpnState.S.CONNECTING)
            goForeground("Сервер не отвечает, переключаю…")
            connect()   // ranked() поставит штрафованный сервер после здоровых
        }
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
