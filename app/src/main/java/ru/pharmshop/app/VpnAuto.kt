package ru.pharmshop.app

import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Режим «Автоматически»: выбор сервера по пингу. Раньше при пустом Store.vpnServer брался просто первый сервер списка.
 *
 *  • пингуем все серверы параллельно (TCP-connect, VpnSub.ping), общий лимит ~3.5 с;
 *  • берём самый быстрый из ответивших; предыдущий автоматический выбор не бросаем ради выигрыша меньше STICKY_MS
 *    (иначе при дрожании пинга сервер менялся бы на каждом подключении, а с ним и внешний IP);
 *  • ranked() отдаёт весь список по порядку — сервис пробует следующий, если первый не поднялся (failover);
 *  • пинги, измеренные в фоне (prefetch, раз в несколько часов из PollWorker), ranked() берёт готовыми, если сеть та же, —
 *    поэтому подключение не ждёт замера и стартует быстрее;
 *  • сервер, не прошедший проверку канала (generate_204 через туннель, см. VpnProbe), получает штраф на PENALTY_MS:
 *    такие серверы идут после здоровых, даже если их пинг лучше (TCP-пинг не видит «порт открыт, но трафик не идёт»).
 * Всё блокирующее: вызывать не в потоке интерфейса.
 */
object VpnAuto {
    private class Rec(val at: Long, val ms: Int)
    private val mem = ConcurrentHashMap<String, Rec>()
    private const val TTL = 60_000L
    private const val TOTAL_TIMEOUT_MS = 3_500L
    private const val STICKY_MS = 30
    private const val PENALTY_MS = 15 * 60_000L
    private const val WARM_TTL = 4 * 60 * 60_000L

    @Volatile private var appCtx: android.content.Context? = null

    /** Контекст нужен, чтобы знать тип сети для фоновых замеров. Зовут сервис VPN и фоновая задача. */
    fun init(ctx: android.content.Context) { appCtx = ctx.applicationContext }

    // ---- пинги, измеренные заранее ----
    @Volatile private var warmMem: Pair<Long, Map<String, Int>>? = null

    private fun warmMap(): Map<String, Int> {
        val at = Store.vpnWarmAt
        warmMem?.let { if (it.first == at) return it.second }
        val m = HashMap<String, Int>()
        for (l in Store.vpnWarm.lines()) {
            val i = l.lastIndexOf('\t')
            if (i > 0) l.substring(i + 1).toIntOrNull()?.let { m[l.substring(0, i)] = it }
        }
        warmMem = at to m
        return m
    }

    /** Готовый пинг из фоновой предзагрузки: не старше WARM_TTL и измеренный в той же сети. Недоступный сервер (-1) не берём: мог ожить. */
    private fun warmFor(name: String): Int? {
        val c = appCtx ?: return null
        if (System.currentTimeMillis() - Store.vpnWarmAt > WARM_TTL) return null
        if (Store.vpnWarmNet != VpnNet.kind(c)) return null
        return warmMap()[name]?.takeIf { it >= 0 }
    }

    /**
     * Фоновая предзагрузка: свежие пинги всех серверов кладём в память и в Store (с типом сети). Дальше ranked() не ждёт замера.
     * Блокирующий: вызывать из фонового потока.
     */
    fun prefetch(list: List<VpnServer>) {
        val c = appCtx ?: return
        val kind = VpnNet.kind(c)
        if (kind == VpnNet.NONE || list.isEmpty()) return
        val res = measureAll(list, fresh = true)
        Store.vpnWarm = res.entries.joinToString("\n") { "${it.key}\t${it.value}" }
        Store.vpnWarmNet = kind
        Store.vpnWarmAt = System.currentTimeMillis()
    }

    // ---- штрафы за недавние сбои (в памяти и в Store, чтобы пережить перезапуск сервиса) ----
    private val bad = ConcurrentHashMap<String, Long>().also { m ->
        try {
            for (l in Store.vpnBad.lines()) {
                val i = l.lastIndexOf('\t')
                if (i > 0) l.substring(i + 1).toLongOrNull()?.let { m[l.substring(0, i)] = it }
            }
        } catch (e: Exception) { }
    }

    private fun saveBad() {
        val now = System.currentTimeMillis()
        bad.entries.removeIf { now - it.value >= PENALTY_MS }
        Store.vpnBad = bad.entries.joinToString("\n") { "${it.key}\t${it.value}" }
    }

    /** Сервер недавно не прошёл проверку канала. */
    fun penalized(name: String): Boolean = bad[name]?.let { System.currentTimeMillis() - it < PENALTY_MS } ?: false

    /** Штрафует сервер: не ставить первым только из-за хорошего пинга. */
    fun markBad(name: String) { bad[name] = System.currentTimeMillis(); saveBad() }

    /** Сервер снова работает — штраф снимается. */
    fun clearBad(name: String) { if (bad.remove(name) != null) saveBad() }

    fun cached(name: String): Int? = mem[name]?.takeIf { System.currentTimeMillis() - it.at < TTL }?.ms

    /** name → пинг в мс (-1: не ответил). Свежие значения берёт из памяти. */
    fun measureAll(list: List<VpnServer>, fresh: Boolean = false, useWarm: Boolean = false): Map<String, Int> {
        val pool = Executors.newFixedThreadPool(list.size.coerceIn(1, 8))
        try {
            val futs = list.map { s ->
                s.name to pool.submit(Callable {
                    val known: Int? = if (fresh) null else (cached(s.name) ?: if (useWarm) warmFor(s.name) else null)
                    known ?: VpnSub.ping(s).also { mem[s.name] = Rec(System.currentTimeMillis(), it) }
                })
            }
            val deadline = System.currentTimeMillis() + TOTAL_TIMEOUT_MS
            val out = HashMap<String, Int>()
            for ((name, fut) in futs) {
                val left = (deadline - System.currentTimeMillis()).coerceAtLeast(1)
                out[name] = try { fut.get(left, TimeUnit.MILLISECONDS) } catch (e: Exception) { fut.cancel(true); -1 }
            }
            return out
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Все серверы от лучшего к худшему. Порядок: здоровые по пингу → недавно упавшие по пингу → не ответившие на пинг.
     * Если упавшими оказались все, порядок обычный (штраф бессмысленен).
     */
    fun ranked(list: List<VpnServer>): List<VpnServer> {
        if (list.size <= 1) return list
        val res = measureAll(list, useWarm = true)
        val alive = list.filter { (res[it.name] ?: -1) >= 0 }.sortedBy { res[it.name]!! }
        if (alive.isEmpty()) return list
        val healthy = alive.filter { !penalized(it.name) }
        val failed = alive.filter { penalized(it.name) }
        val pool = if (healthy.isEmpty()) alive else healthy
        val best = pool.first()
        val keep = pool.firstOrNull { it.name == Store.vpnLastAuto }
        val first = if (keep != null && res[keep.name]!! - res[best.name]!! <= STICKY_MS) keep else best
        val dead = list.filter { s -> alive.none { it.name == s.name } }
        val ordered = if (healthy.isEmpty()) pool.filter { it !== first } else healthy.filter { it !== first } + failed
        return listOf(first) + ordered + dead
    }

    /** Что показать на карточке сервера в режиме «Автоматически»: без сети и без замеров. */
    fun label(list: List<VpnServer>): VpnServer {
        list.firstOrNull { it.name == Store.vpnLastAuto }?.let { return it }
        val live = list.filter { (cached(it.name) ?: -1) >= 0 }
        return (live.filter { !penalized(it.name) }.ifEmpty { live }).minByOrNull { cached(it.name)!! } ?: list.first()
    }
}
