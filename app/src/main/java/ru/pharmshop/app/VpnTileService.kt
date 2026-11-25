package ru.pharmshop.app

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Плитка «VPN» в шторке быстрых настроек: нажатие включает и выключает VPN.
 *
 * Если для подключения нужно что-то, чего без экрана не сделать (нет ссылки подписки, система ещё не выдала разрешение на VPN,
 * Android 13+ не выдал разрешение на уведомления), плитка сворачивает шторку и открывает приложение с тем же действием,
 * что и ярлык «Подключить VPN» (Reminders.EXTRA_OPEN = «vpn_connect»).
 * Состояние берётся из VpnState и обновляется, пока шторка открыта.
 */
class VpnTileService : TileService() {

    private val listener: (VpnState.S, String) -> Unit = { _, _ -> paint() }

    override fun onCreate() {
        super.onCreate()
        // сервис может стартовать в «холодном» процессе
        Store.init(applicationContext)
        AppLog.init(applicationContext)
    }

    override fun onStartListening() {
        VpnState.listen(listener)
        paint()
    }

    override fun onStopListening() {
        VpnState.unlisten(listener)
    }

    private fun paint() {
        val tile = qsTile ?: return
        val sub: String
        when (VpnState.state) {
            VpnState.S.ON -> { tile.state = Tile.STATE_ACTIVE; sub = VpnState.message.ifEmpty { "Подключено" } }
            VpnState.S.CONNECTING -> { tile.state = Tile.STATE_ACTIVE; sub = "Подключение…" }
            VpnState.S.ERROR -> { tile.state = Tile.STATE_INACTIVE; sub = "Ошибка" }
            VpnState.S.OFF -> { tile.state = Tile.STATE_INACTIVE; sub = "Выключено" }
        }
        tile.label = "VPN"
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = sub
        tile.updateTile()
    }

    override fun onClick() {
        when (VpnState.state) {
            VpnState.S.ON, VpnState.S.CONNECTING -> PharmVpnService.stop(this)
            else -> connect()
        }
    }

    private fun connect() {
        val needNotif = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (Store.vpnSubUrl.isEmpty() || needNotif || VpnService.prepare(this) != null) {
            openApp()
            return
        }
        PharmVpnService.start(this)
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val i = Intent(this, LauncherActivity::class.java)
            .putExtra(Reminders.EXTRA_OPEN, "vpn_connect")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 7003, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            startActivityAndCollapse(i)
        }
    }
}
