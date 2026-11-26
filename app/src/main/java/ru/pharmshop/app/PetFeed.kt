package ru.pharmshop.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * «Мой питомец»: кормление и учёт покупок. Работает без экрана питомца: сервис VPN и главная активность вызывают init().
 *
 * Кормление: пока VPN включён, раз в BEAT_MS шлём серверу, сколько секунд он работал (ChatApi.petFeed). Сервер не верит
 * на слово — засчитывает не больше реально прошедшего времени (см. server/worker.js). Когда VPN выключают, шлём остаток.
 * Нужен ник в чате: без него кота на сервере нет, и сигналы не отправляются.
 *
 * Уровень: после загрузки подписок экран VPN зовёт syncSubs. Каждая купленная подписка засчитывается один раз,
 * продление (дней осталось выросло почти на срок тарифа) — ещё раз. Пробные подписки не считаются.
 */
object PetFeed {
    private const val BEAT_MS = 5 * 60_000L
    private const val MIN_SEND_SEC = 30
    private const val MAX_SEC = 900

    private val main = Handler(Looper.getMainLooper())

    /** Когда в последний раз отправляли сигнал (SystemClock.elapsedRealtime); 0 — VPN не работает. */
    @Volatile private var lastBeat = 0L
    @Volatile private var inited = false

    /** Последний ответ сервера по коту (кормление или открытие экрана) — JSON поля pet. */
    @Volatile var lastPet: JSONObject? = null
        private set

    private val listener: (VpnState.S, String) -> Unit = { s, _ -> onState(s) }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            beat()
            if (lastBeat != 0L) main.postDelayed(this, BEAT_MS)
        }
    }

    fun init() {
        if (inited) return
        inited = true
        VpnState.listen(listener)
        onState(VpnState.state)
    }

    private fun onState(s: VpnState.S) {
        if (s == VpnState.S.ON) {
            if (lastBeat == 0L) {
                lastBeat = SystemClock.elapsedRealtime()
                main.removeCallbacks(tick)
                main.postDelayed(tick, BEAT_MS)
            }
        } else if (s == VpnState.S.OFF || s == VpnState.S.ERROR) {
            if (lastBeat != 0L) {
                beat()   // остаток времени
                lastBeat = 0L
                main.removeCallbacks(tick)
            }
        }
    }

    /** Сколько секунд VPN работает с прошлого сигнала: экран питомца добавляет это к еде в миске, чтобы не ждать 5 минут. */
    fun pendingSec(): Int {
        val l = lastBeat
        return if (l == 0L) 0 else ((SystemClock.elapsedRealtime() - l) / 1000L).toInt()
    }

    private fun beat() {
        val last = lastBeat
        if (last == 0L) return
        val now = SystemClock.elapsedRealtime()
        val sec = ((now - last) / 1000L).toInt()
        if (sec < MIN_SEND_SEC) return
        if (Store.chatNick.isEmpty()) { lastBeat = now; return }   // кота нет, пока не выбран ник в чате
        val claim = minOf(sec, MAX_SEC)
        // что не вошло в один сигнал, остаётся на следующий
        lastBeat = if (VpnState.state == VpnState.S.ON) now - (sec - claim) * 1000L else 0L
        Thread {
            try {
                val r = ChatApi.petFeed(claim)
                r.optJSONObject("pet")?.let { remember(it) }
            } catch (e: Exception) {
                // нет сети или сервер недоступен: время не теряем, отправим со следующим сигналом
                if (VpnState.state == VpnState.S.ON && lastBeat != 0L) lastBeat = minOf(lastBeat, last)
            }
        }.start()
    }

    /** Запомнить свежее состояние кота (для экрана и для быстрого открытия). */
    fun remember(pet: JSONObject) {
        lastPet = pet
        Store.petCache = pet.toString()
    }

    /** Уровень, до которого кот вырос при последнем учёте покупок (0 — ничего нового). Экран питомца показывает поздравление один раз. */
    @Volatile var levelUp = 0

    /**
     * Сообщить серверу о купленных подписках. Вызывать из фонового потока. Возвращает последний ответ /api/pet/buy,
     * если что-то засчитано (в нём level_up), иначе null.
     */
    fun syncSubs(subs: JSONArray): JSONObject? {
        if (Store.chatNick.isEmpty()) return null
        val seen = HashMap<String, Int>()
        for (l in Store.petSubs.lines()) {
            val i = l.indexOf('=')
            if (i > 0) l.substring(i + 1).toIntOrNull()?.let { seen[l.substring(0, i)] = it }
        }
        var result: JSONObject? = null
        var changed = false
        for (i in 0 until subs.length()) {
            val s = subs.optJSONObject(i) ?: continue
            if (s.optBoolean("is_trial")) continue
            val id = VpnApi.subId(s)
            if (id.isEmpty()) continue
            val plan = if (s.optBoolean("is_lifetime")) 365 else s.optInt("days")
            if (plan !in 1..400) continue
            val left = maxOf(0, s.optInt("days_left"))
            val prev = seen[id]
            var ref = ""
            if (prev == null) ref = "sub:$id"
            else if (plan >= 7 && left >= prev + plan - 5) ref = "ren:$id:${s.optString("end_date").take(10)}"
            if (ref.isNotEmpty()) {
                val r = report(ref, plan)
                if (r == null) continue   // не получилось: попробуем при следующей загрузке
                if (r.has("pet")) result = r
            }
            if (prev != left) { seen[id] = left; changed = true }
        }
        if (changed) Store.petSubs = seen.entries.joinToString("\n") { it.key + "=" + it.value }
        val res = result
        res?.optJSONObject("pet")?.let { remember(it) }
        if (res != null && res.optBoolean("level_up")) levelUp = res.optJSONObject("pet")?.optInt("level") ?: 0
        return res
    }

    /** Один запрос /api/pet/buy. «Уже засчитано» (409) — тоже успех, чтобы не повторять его каждый раз. null — ошибка. */
    private fun report(ref: String, days: Int): JSONObject? = try {
        ChatApi.petBuy(ref, days)
    } catch (e: Exception) {
        if ((e.message ?: "").contains("засчитан")) JSONObject() else { AppLog.e("pet-buy", e); null }
    }
}