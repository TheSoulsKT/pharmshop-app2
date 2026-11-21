package ru.pharmshop.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Открыто ли приложение на экране. Пока открыто — фоновые уведомления не нужны, всё видно и так. */
object AppState {
    @Volatile var visible = false
}

/** Уведомления о событиях: ответ поддержки, смена статуса заказа, новые чаты (для админа). */
object Notifier {
    const val CH_SUPPORT = "support"
    const val CH_ORDERS = "orders"
    const val CH_ADMIN = "admin"

    private const val ID_SUPPORT = 2001
    private const val ID_ADMIN = 2002
    private const val ID_ORDER_BASE = 3000

    fun enabled(c: Context): Boolean = NotificationManagerCompat.from(c).areNotificationsEnabled()

    private fun ensureChannels(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        fun ch(id: String, name: String, desc: String) = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH)
            .apply { description = desc }
        nm.createNotificationChannel(ch(CH_SUPPORT, "Ответы поддержки", "Когда поддержка ответила в чате"))
        nm.createNotificationChannel(ch(CH_ORDERS, "Статусы заказов", "Когда заказ выполнен или отменён"))
        nm.createNotificationChannel(ch(CH_ADMIN, "Новые обращения", "Для администратора: новые сообщения в чатах"))
    }

    @SuppressLint("MissingPermission")
    private fun post(c: Context, channel: String, id: Int, title: String, text: String, open: String) {
        if (!enabled(c)) return
        ensureChannels(c)
        val intent = Intent(c, MainActivity::class.java)
            .putExtra(Reminders.EXTRA_OPEN, open)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(c, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_stat_star)
            .setColor(Color.parseColor("#3B8EF3"))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try { NotificationManagerCompat.from(c).notify(id, n) } catch (e: SecurityException) { /* нет разрешения */ }
    }

    fun support(c: Context, unread: Int, text: String) {
        val title = if (unread > 1) "Поддержка: новых сообщений — $unread" else "Поддержка ответила"
        post(c, CH_SUPPORT, ID_SUPPORT, title, text.ifEmpty { "Откройте чат, чтобы прочитать ответ." }, "support")
    }

    fun order(c: Context, o: org.json.JSONObject, kind: Int) {
        val num = o.optString("order_number").ifEmpty { o.optString("id").take(8) }
        val what = if (o.optString("product") == "premium")
            "Premium · ${o.optInt("months", o.optInt("quantity"))} мес. → @${o.optString("username")}"
        else "${o.optInt("quantity")} ★ → @${o.optString("username")}"
        val title = if (kind == 2) "Заказ $num выполнен" else "Заказ $num отменён"
        val id = ID_ORDER_BASE + (o.optString("id").hashCode() and 0x3FF)
        post(c, CH_ORDERS, id, title, what, "profile")
    }

    fun admin(c: Context, chats: Int, nick: String, text: String) {
        val title = if (chats > 1) "Новых обращений: $chats" else "Новое обращение" + if (nick.isNotEmpty()) " от @$nick" else ""
        post(c, CH_ADMIN, ID_ADMIN, title, text.ifEmpty { "Откройте админ-панель." }, "admin")
    }
}
