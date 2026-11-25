package ru.pharmshop.app

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * geoip.dat / geosite.dat для Xray. Ядро ищет их в каталоге, который мы передаём в initCoreEnv (у нас это filesDir).
 *
 * Порядок работы:
 *  1. prepare()  — быстро: если файлов нет, копирует их из assets/geo (их кладёт CI, см. build.yml). Зовётся из PharmVpnService.
 *  2. updateIfStale() — медленно (сеть), раз в неделю, из PollWorker/любого фонового потока. Новые файлы подхватятся при следующем подключении.
 *
 * Файлы проверяются по размеру и пишутся через .tmp + rename, чтобы оборванная загрузка не оставила битый .dat
 * (битый .dat = ядро не стартует, поэтому сервис при ошибке старта вызывает reset() и пробует без geo).
 */
object GeoFiles {
    private val NAMES = arrayOf("geoip.dat", "geosite.dat")

    // Источник: runetfreedom/russia-v2ray-rules-dat (обычные geoip/geosite + категории ru-blocked, ru-available-only-inside).
    // Если GitHub у пользователя недоступен — второй адрес (зеркало jsDelivr, ветка release).
    private val SOURCES = arrayOf(
        "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/",
        "https://cdn.jsdelivr.net/gh/runetfreedom/russia-v2ray-rules-dat@release/"
    )
    private const val MIN_SIZE = 50_000L
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

    private fun f(ctx: Context, n: String) = File(ctx.filesDir, n)
    private fun ok(file: File) = file.isFile && file.length() >= MIN_SIZE

    fun ready(ctx: Context): Boolean = NAMES.all { ok(f(ctx, it)) }

    /** Копирует из assets то, чего нет в filesDir. Возвращает true, если оба файла на месте. */
    fun prepare(ctx: Context): Boolean {
        for (n in NAMES) {
            val dst = f(ctx, n)
            if (ok(dst)) continue
            try {
                val tmp = File(ctx.filesDir, "$n.tmp")
                ctx.assets.open("geo/$n").use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (tmp.length() >= MIN_SIZE) tmp.renameTo(dst) else tmp.delete()
            } catch (e: Exception) {
                // в сборке нет assets/geo — тогда ждём updateIfStale()
            }
        }
        return ready(ctx)
    }

    /** Тег, который отправляется в blackhole при включённой блокировке рекламы. */
    const val ADS_TAG = "category-ads-all"

    @Volatile private var adsCache: Pair<Long, Boolean>? = null

    /**
     * Есть ли в geosite.dat категория [tag]. Файл — protobuf GeoSiteList: повторяющиеся записи (поле 1), в каждой первым полем
     * идёт код категории. Читаем только заголовки записей и пропускаем тела, поэтому проверка занимает миллисекунды.
     * Если тега нет, правило с ним не запустило бы ядро, поэтому без проверки включать блокировку рекламы нельзя.
     */
    fun hasSite(ctx: Context, tag: String = ADS_TAG): Boolean {
        val file = f(ctx, NAMES[1])
        if (!ok(file)) return false
        val stamp = file.lastModified() xor file.length()
        adsCache?.let { if (it.first == stamp && tag == ADS_TAG) return it.second }
        val found = try { scan(file, tag) } catch (e: Exception) { false }
        if (tag == ADS_TAG) adsCache = stamp to found
        return found
    }

    private fun scan(file: File, tag: String): Boolean {
        java.io.BufferedInputStream(file.inputStream(), 1 shl 16).use { inp ->
            fun varint(): Long {
                var r = 0L; var sh = 0
                while (true) {
                    val b = inp.read()
                    if (b < 0) return -1
                    r = r or ((b and 0x7F).toLong() shl sh)
                    if (b and 0x80 == 0) return r
                    sh += 7
                    if (sh > 63) return -1
                }
            }
            fun skip(n: Long) {
                var left = n
                while (left > 0) { val k = inp.skip(left); if (k <= 0) { if (inp.read() < 0) return; left-- } else left -= k }
            }
            while (true) {
                val key = varint(); if (key < 0) return false
                val len = varint(); if (len < 0) return false
                if (key != 0x0AL) { skip(len); continue }   // не запись GeoSite
                // первое поле записи — country_code (тег 0x0A, длина, строка)
                val k2 = inp.read(); if (k2 < 0) return false
                var used = 1L
                if (k2 != 0x0A) { skip(len - used); continue }
                val l2 = varint(); if (l2 < 0) return false
                used += if (l2 < 128) 1 else if (l2 < 16384) 2 else 3
                val buf = ByteArray(l2.toInt().coerceIn(0, 256))
                var got = 0
                while (got < buf.size) { val n = inp.read(buf, got, buf.size - got); if (n < 0) return false; got += n }
                used += buf.size
                if (String(buf, Charsets.UTF_8).equals(tag, ignoreCase = true)) return true
                skip(len - used)
            }
        }
    }

    /** Удаляет файлы (если ядро на них упало). Следующий prepare()/update вернёт свежие. */
    fun reset(ctx: Context) { for (n in NAMES) f(ctx, n).delete() }

    /** Возраст geosite.dat в миллисекундах; Long.MAX_VALUE, если файла нет. */
    private fun age(ctx: Context): Long {
        val g = f(ctx, NAMES[1])
        return if (ok(g)) System.currentTimeMillis() - g.lastModified() else Long.MAX_VALUE
    }

    /** Возраст файлов маршрутов в миллисекундах (по более старому из двух); -1, если их нет. Для «Проверить VPN». */
    fun ageMs(ctx: Context): Long {
        if (!ready(ctx)) return -1L
        val now = System.currentTimeMillis()
        return NAMES.maxOf { now - f(ctx, it).lastModified() }
    }

    /** Качает свежие файлы, если старым больше недели (или их нет). Вызывать не в потоке интерфейса. true — файлы обновлены. */
    fun updateIfStale(ctx: Context): Boolean {
        if (age(ctx) < MAX_AGE_MS) return false
        val staged = ArrayList<Pair<File, File>>()
        try {
            for (n in NAMES) {
                val tmp = File(ctx.filesDir, "$n.new")
                if (!download(n, tmp)) { tmp.delete(); staged.forEach { it.first.delete() }; return false }
                staged.add(tmp to f(ctx, n))
            }
            // оба скачались — только теперь подменяем (пара geoip+geosite должна быть из одной версии)
            staged.forEach { (tmp, dst) -> if (!tmp.renameTo(dst)) return false }
            return true
        } catch (e: Exception) {
            staged.forEach { it.first.delete() }
            return false
        }
    }

    private fun download(name: String, to: File): Boolean {
        for (base in SOURCES) {
            val c = URL(base + name).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", VpnSub.UA)
                if (c.responseCode !in 200..299) continue
                c.inputStream.use { i -> to.outputStream().use { i.copyTo(it) } }
                if (to.length() >= MIN_SIZE) return true
            } catch (e: Exception) {
                // пробуем следующий источник
            } finally {
                c.disconnect()
            }
        }
        return false
    }
}
