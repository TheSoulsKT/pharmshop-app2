package ru.pharmshop.app

import android.content.Context
import java.io.IOException

/**
 * Понятные ошибки VPN: таблица «причина → что делать» вместо сырого e.message.
 * Текст короткий (до ~90 знаков): он попадает в карточку на экране VPN, в статус и в плитку.
 * retry — стоит ли пробовать снова само: сеть, перегруженная панель, разовый сбой. Если ждать нечего
 * (подписка закончилась, лимит устройств, нет ядра), ошибка показывается сразу.
 */
object VpnErrors {
    class Friendly(val text: String, val retry: Boolean)

    /** Паузы перед повторами, секунды: нарастающие, всего около минуты. */
    val RETRY_DELAYS = intArrayOf(2, 5, 10, 20, 30)

    /** По сохранённому кэшу подписок: все ли они закончились или заморожены. Кэш может отставать, поэтому только для подсказки, не для запрета. */
    private fun subEnded(): Boolean {
        val subs = VpnWatch.cached()
        if (subs.length() == 0) return false
        for (i in 0 until subs.length()) {
            val s = subs.optJSONObject(i) ?: continue
            if (s.optBoolean("active") && !s.optBoolean("frozen")) return false
        }
        return true
    }

    private fun chain(e: Throwable): List<Throwable> {
        val out = ArrayList<Throwable>()
        var t: Throwable? = e
        while (t != null && out.size < 6 && t !in out) { out.add(t); t = t.cause }
        return out
    }

    fun classify(ctx: Context, e: Throwable): Friendly {
        val msg = e.message.orEmpty()
        val all = chain(e)
        return when {
            all.any { it is UnsatisfiedLinkError || it is NoClassDefFoundError } || msg.contains("Ядро Xray не подключено") ->
                Friendly("Ядро VPN не подключено к сборке. Скачайте приложение заново", false)
            msg.contains("Нет ссылки подписки") ->
                Friendly("Нет ссылки подписки. Откройте раздел VPN и нажмите «Подключить»", false)
            msg.contains("Подписка не найдена") ->
                Friendly("Ссылка подписки устарела. Откройте раздел VPN и подключитесь заново", false)
            msg.contains("Сервер не пустил это устройство") ->
                if (subEnded()) Friendly("Подписка закончилась. Продлите её в разделе VPN", false)
                else Friendly("Достигнут лимит устройств. Удалите лишнее в личном кабинете VPN", false)
            msg.contains("пустой ответ") || msg.contains("нет поддерживаемых серверов") ->
                if (subEnded()) Friendly("Подписка закончилась. Продлите её в разделе VPN", false)
                else Friendly("Подписка пуста или заморожена. Проверьте её в разделе VPN", false)
            msg.contains("веб-страницу") || msg.contains("Не удалось разобрать") || msg.contains("Неизвестный формат") ->
                Friendly("Панель вернула непонятный ответ. Сообщите в поддержку", false)
            msg.contains("Слишком много запросов") ->
                Friendly("Слишком много запросов. Подождите минуту, пробуем снова", true)
            msg.contains("Ошибка сервера подписки") ->
                Friendly("Панель подписки временно недоступна. Пробуем снова", true)
            msg.contains("Система не создала VPN-интерфейс") ->
                Friendly("VPN не разрешён. Нажмите «Подключить» и подтвердите доступ", false)
            msg.contains("Нет доступных серверов") ->
                Friendly("Нет доступных серверов. Пробуем снова", true)
            all.any { it is IOException } ->
                if (!VpnNet.hasInternet(ctx)) Friendly("Нет сети. Проверьте Wi‑Fi или мобильный интернет", true)
                else Friendly("Сервер не отвечает. Пробуем снова", true)
            !VpnNet.hasInternet(ctx) -> Friendly("Нет сети. Проверьте Wi‑Fi или мобильный интернет", true)
            else -> Friendly((msg.ifEmpty { e.javaClass.simpleName }).take(80), true)
        }
    }

    /** Запоминает последнюю ошибку для «Проверить VPN» и отчёта в поддержку. */
    fun record(f: Friendly, e: Throwable) {
        Store.vpnLastError = f.text
        Store.vpnLastErrorRaw = (e.message ?: e.javaClass.simpleName).take(200)
        Store.vpnLastErrorAt = System.currentTimeMillis()
    }
}
