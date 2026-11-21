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
    internal val BG = Color.parseColor("#121214")
    internal val CARD = Color.parseColor("#1D1C21")
    internal val LINE = Color.parseColor("#2B2A31")
    internal val TEXT = Color.parseColor("#F2F2F4")
    internal val MUTE = Color.parseColor("#9A9DA3")
    internal val BLUE = Color.parseColor("#3B8EF3")
    internal val GREEN = Color.parseColor("#34C759")
    internal val AMBER = Color.parseColor("#E0A030")
    internal val RED = Color.parseColor("#FF5A52")

    // палитра экранов покупки (главный экран остаётся синим)
    internal val GOLD = Color.parseColor("#FFB020")
    internal val GOLD_A = Color.parseColor("#FFC83D")
    internal val GOLD_B = Color.parseColor("#FF8A3D")
    internal val ORANGE = Color.parseColor("#FF7A3D")
    internal val PINK = Color.parseColor("#EC4899")
    internal val VIOLET = Color.parseColor("#8B5CF6")
    internal val VIOLET_A = Color.parseColor("#7C3AED")
    internal val VIOLET_L = Color.parseColor("#B794F6")
    internal val CYAN = Color.parseColor("#22C1DC")
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val ba = backAction
                if (ba != null) { ba(); return }
                if (onHome) finish() else { navBack = true; showHome() }
            }
        })
        // напоминания: разрешение спрашиваем один раз, будильник ставим при каждом запуске
        if (Reminders.shouldAskPermission(this)) {
            Reminders.markAsked(this)
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        Reminders.ensureScheduled(this)
        if (Store.pushEnabled) PollWorker.schedule(this)
        if (enforceUpdate()) return
        if (!openFromIntent(intent)) showHome()
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
            else -> false
        }
    }

    override fun onStart() {
        super.onStart()
        AppState.visible = true
    }

    override fun onStop() {
        AppState.visible = false
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
