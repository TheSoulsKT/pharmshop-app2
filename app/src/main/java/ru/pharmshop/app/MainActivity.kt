package ru.pharmshop.app

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.hcaptcha.sdk.HCaptcha
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    // цвета берутся из выбранного оформления (Настройки → Тема) и режима (тёмная/светлая)
    internal val BG get() = Theme.bg
    internal val CARD get() = Theme.card
    internal val LINE get() = Theme.line
    internal val TEXT get() = Theme.text
    internal val MUTE get() = Theme.mute
    internal val BLUE get() = Theme.accent   // главный акцентный цвет темы (название историческое)
    internal val GREEN get() = if (Theme.light) Theme.L_GREEN else Color.parseColor("#34C759")
    internal val AMBER get() = if (Theme.light) Theme.L_AMBER else Color.parseColor("#E0A030")
    internal val RED get() = if (Theme.light) Theme.L_RED else Color.parseColor("#FF5A52")

    // палитра экранов покупки (главный экран остаётся синим)
    internal val GOLD get() = if (Theme.light) Theme.L_GOLD else Color.parseColor("#FFB020")
    internal val GOLD_A = Color.parseColor("#FFC83D")
    internal val GOLD_B = Color.parseColor("#FF8A3D")
    internal val ORANGE get() = if (Theme.light) Theme.L_ORANGE else Color.parseColor("#FF7A3D")
    internal val PINK = Color.parseColor("#EC4899")
    internal val VIOLET get() = if (Theme.light) Theme.L_VIOLET else Color.parseColor("#8B5CF6")
    internal val VIOLET_A = Color.parseColor("#7C3AED")
    internal val VIOLET_L get() = if (Theme.light) Theme.L_VIOLET_L else Color.parseColor("#B794F6")
    internal val CYAN get() = if (Theme.light) Theme.L_CYAN else Color.parseColor("#22C1DC")
    internal val DARK_ON = Color.parseColor("#231400")

    internal val BOT_URL = "https://t.me/pharmaishop_bot"
    /** Единая высота всех карточек главного меню */
    internal val CARD_H get() = dp(96)

    internal val io = Executors.newFixedThreadPool(3)
    internal val main = Handler(Looper.getMainLooper())
    internal var cfg: JSONObject? = null
    internal var busy = false
    internal var onHome = true
    internal var screenId = 0
    internal var backAction: (() -> Unit)? = null   // особое действие кнопки «назад» (чаты)
    internal val net = Executors.newFixedThreadPool(4)   // параллельные запросы статусов
    internal var refreshing = false
    internal var lastRefreshError = ""
    internal var returnFromPay = false
    internal var hc: HCaptcha? = null
    internal var hcOk: ((String) -> Unit)? = null
    internal var hcFail: ((String) -> Unit)? = null
    internal var navBack = false        // возврат назад: экран выезжает слева
    internal var firstScreen = true     // первый экран после запуска — только плавное появление
    internal val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    internal var onImagePicked: ((Uri) -> Unit)? = null
    internal val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onImagePicked?.invoke(uri)
    }
    internal var updateChecked = false
    internal var remoteAt = 0L          // когда последний раз спрашивали удалённые настройки
    internal var forceShown = false     // показан экран обязательного обновления
    internal var lockView: android.view.View? = null   // экран входа по PIN/отпечатку (поверх всего)
    internal var bioBusy = false                       // окно отпечатка уже на экране

    override fun onCreate(savedInstanceState: Bundle?) {
        Store.init(this)
        Theme.update(this)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(Theme.nightMode())
        super.onCreate(savedInstanceState)
        applyWindowTheme()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (lockView != null) { moveTaskToBack(true); return }   // на экране входа «назад» просто сворачивает
                val ba = backAction
                if (ba != null) { ba(); return }
                if (onHome) finish() else { navBack = true; showHome() }
            }
        })
        // скачанное в фоне обновление уже установлено — убираем файл
        if (Store.pendingUpdateCode != 0 && Store.pendingUpdateCode <= BuildConfig.VERSION_CODE) {
            Store.pendingUpdateCode = 0
            Store.pendingUpdateName = ""
            java.io.File(cacheDir, "updates").listFiles()?.forEach { it.delete() }
        }
        // напоминания: разрешение спрашиваем один раз, будильник ставим при каждом запуске
        if (Reminders.shouldAskPermission(this)) {
            Reminders.markAsked(this)
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        Reminders.ensureScheduled(this)
        if (Store.pushEnabled) PollWorker.schedule(this)
        if (Store.wifiUpdate && Updater.configured) UpdateWorker.schedule(this)
        Push.init(this)
        if (!enforceUpdate() && !openFromIntent(intent)) {
            // после смены темы в настройках возвращаемся в настройки, а не на главную
            if (AppState.reopen == "settings") showSettings() else showHome()
        }
        AppState.reopen = null
        showLockIfNeeded()
    }

    /** Цвета строки состояния и навигации под текущую тему. */
    internal fun applyWindowTheme() {
        window.statusBarColor = BG
        window.navigationBarColor = BG
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = Theme.light
            isAppearanceLightNavigationBars = Theme.light
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFromIntent(intent)
    }

    /** Нажатие на уведомление открывает экран покупки звёзд или Premium. */
    private fun openFromIntent(i: Intent?): Boolean {
        val target = i?.getStringExtra(Reminders.EXTRA_OPEN) ?: return false
        i.removeExtra(Reminders.EXTRA_OPEN)
        return when (target) {
            "stars" -> { showStars(); true }
            "premium" -> { showPremium(); true }
            "profile" -> { showProfile(); true }
            "support" -> { showSupport(); true }
            "admin" -> { showAdminEntry(); true }
            "update" -> { showHome(); installPendingUpdate(); true }
            else -> false
        }
    }

    override fun onStart() {
        super.onStart()
        AppState.visible = true
        // вернулись в приложение после паузы — снова просим PIN / отпечаток
        if (lockEnabled() && AppState.unlocked && AppState.stoppedAt > 0L &&
            android.os.SystemClock.elapsedRealtime() - AppState.stoppedAt >= Store.lockDelaySec * 1000L
        ) {
            AppState.unlocked = false
            showLock()
        }
    }

    override fun onStop() {
        AppState.visible = false
        AppState.stoppedAt = android.os.SystemClock.elapsedRealtime()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (returnFromPay) {
            returnFromPay = false
            showProfile()
        }
        refreshRemote()
    }

}
