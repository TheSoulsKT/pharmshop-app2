package ru.pharmshop.app

import android.os.Build
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLDecoder

/** Сервер из подписки: либо готовый Xray-конфиг целиком (формат JSON), либо один outbound, собранный из ссылки. */
class VpnServer(val name: String, val outbound: JSONObject?, val full: JSONObject?)

/**
 * Скачивание подписки и превращение её в конфиг Xray.
 * Формат ответа определяется сам: «[» или «{» — Xray-JSON, иначе base64-список ссылок (или список ссылок как есть).
 * Поддерживаются vless://, trojan://, vmess://, ss://.
 */
object VpnSub {
    /** Панель выбирает формат ответа по User-Agent; браузерный даёт веб-страницу, поэтому у нас свой. */
    const val UA = "PharmShopVPN/1.0 (Android; Xray-core)"

    open class SubException(msg: String) : Exception(msg)

    /** Панель ответила 401/403: не пустила это устройство. Не всегда это настоящий лимит: ссылку могли перевыпустить или панель дала временный отказ. */
    class DeviceRejected(val code: Int) : SubException("Сервер не пустил это устройство (HTTP $code). Проверьте лимит устройств")

    private fun ascii(s: String?) = (s ?: "").filter { it.code in 32..126 }.take(64)

    private fun download(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "*/*")
            // по этим заголовкам панель считает устройства (лимит «2 / 5»); HWID постоянный, чтобы не занимать новое место при каждом запуске
            c.setRequestProperty("x-hwid", Store.vpnHwid)
            c.setRequestProperty("x-device-os", "Android")
            c.setRequestProperty("x-ver-os", ascii(Build.VERSION.RELEASE))
            c.setRequestProperty("x-device-model", ascii(Build.MODEL))
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code == 401 || code == 403) throw DeviceRejected(code)
            if (code !in 200..299) throw SubException(when (code) {
                404 -> "Подписка не найдена. Возможно, ссылка была перевыпущена"
                429 -> "Слишком много запросов, попробуйте через минуту"
                else -> "Ошибка сервера подписки (HTTP $code)"
            })
            return body
        } finally {
            c.disconnect()
        }
    }

    /** Список серверов в памяти: чтобы окно выбора сервера и подключение не ждали сеть при каждом нажатии. */
    private class Mem(val url: String, val at: Long, val list: List<VpnServer>)
    @Volatile private var mem: Mem? = null
    private const val MEM_TTL = 5 * 60_000L
    /** Сохранённой копии списка (с прошлой загрузки или фоновой предзагрузки) доверяем при подключении до 8 часов. */
    private const val STORED_TTL = 8 * 60 * 60_000L

    /** Последний отданный список — сохранённая копия, а не свежая загрузка. Если по ней не подключились, сервис берёт свежий список. */
    @Volatile var fromStore = false
        private set

    /** Забывает список (при выходе из аккаунта). */
    fun clearMem() { mem = null }

    /** Список по сохранённой копии (без сети), с пометкой «из хранилища». null — копии нет или она повреждена. */
    fun storedList(): List<VpnServer>? {
        val raw = Store.vpnSubRaw
        if (raw.isEmpty()) return null
        return try { parse(raw).also { fromStore = true } } catch (e: Exception) { null }
    }

    /**
     * Сверяет сохранённую ссылку подписки с тем, что отдаёт кабинет. Ссылка записывается в Store только при нажатии
     * «Подключить», поэтому после перевыпуска ссылки плитка, автоподключение и фоновая предзагрузка продолжали ходить
     * по старой и получали отказ (его приложение выдавало за «лимит устройств»). Если сохранённой ссылки в кабинете
     * больше нет, берём ссылку действующей подписки и сбрасываем копию списка серверов от старой ссылки.
     */
    fun syncUrl(subs: JSONArray) {
        val cur = Store.vpnSubUrl
        if (cur.isEmpty()) return
        var fresh: String? = null
        for (i in 0 until subs.length()) {
            val s = subs.optJSONObject(i) ?: continue
            val u = s.optString("subscription_url")
            if (u.isEmpty() || u == "null") continue
            if (u == cur) return   // ссылка актуальна
            if (fresh == null && s.optBoolean("is_active") && !s.optBoolean("is_frozen")) fresh = u
        }
        val f = fresh ?: return
        Store.vpnSubUrl = f
        Store.vpnSubRaw = ""
        Store.vpnSubAt = 0L
        mem = null
    }

    /** Свежий список серверов; без сети — последний сохранённый. */
    fun load(url: String): List<VpnServer> {
        try {
            val raw = download(url)
            val list = parse(raw)
            Store.vpnSubRaw = raw
            Store.vpnSubAt = System.currentTimeMillis()
            mem = Mem(url, System.currentTimeMillis(), list)
            fromStore = false
            return list
        } catch (e: java.io.IOException) {
            val cached = Store.vpnSubRaw
            if (cached.isNotEmpty()) { fromStore = true; return parse(cached) }
            throw e
        }
    }

    /** Как load, но если список скачан меньше 5 минут назад — отдаёт его сразу, без запроса к панели. */
    fun loadFast(url: String): List<VpnServer> {
        val m = mem
        if (m != null && m.url == url && System.currentTimeMillis() - m.at < MEM_TTL) { fromStore = false; return m.list }
        return load(url)
    }

    /**
     * Список для подключения. Если он скачан недавно (в приложении или фоновой предзагрузкой, см. VpnWatch.prefetch),
     * берём сохранённую копию и подключаемся сразу, не дожидаясь панели подписки (до 15 с, если панель медленная или недоступна).
     * Окна и экраны по-прежнему пользуются loadFast: им нужен свежий список.
     */
    fun loadForConnect(url: String): List<VpnServer> {
        val now = System.currentTimeMillis()
        val m = mem
        if (m != null && m.url == url && now - m.at < MEM_TTL) { fromStore = false; return m.list }
        val at = Store.vpnSubAt
        val raw = Store.vpnSubRaw
        if (at > 0L && now - at < STORED_TTL && raw.isNotEmpty()) {
            try {
                val list = parse(raw)
                mem = Mem(url, at, list)
                fromStore = true
                return list
            } catch (e: Exception) { /* копия повреждена — скачиваем заново */ }
        }
        return load(url)
    }

    /** Любой уже известный список без сети (из памяти или с прошлого запуска) либо null. Годится, чтобы сразу показать экран. */
    fun cachedAny(url: String): List<VpnServer>? {
        mem?.takeIf { it.url == url }?.let { return it.list }
        val raw = Store.vpnSubRaw
        if (raw.isEmpty()) return null
        return try { parse(raw).also { mem = Mem(url, 0L, it) } } catch (e: Exception) { null }   // at = 0: считается устаревшим
    }

    /** Список из памяти без обращения к сети и к хранилищу. */
    fun peek(url: String): List<VpnServer>? = mem?.takeIf { it.url == url }?.list

    /** Адрес и порт, куда подключается сервер (для замера пинга). null — не удалось определить. */
    fun endpoint(s: VpnServer): Pair<String, Int>? {
        try {
            val obs: JSONArray = when {
                s.outbound != null -> JSONArray().put(s.outbound)
                s.full != null -> s.full.optJSONArray("outbounds") ?: return null
                else -> return null
            }
            for (i in 0 until obs.length()) {
                val o = obs.optJSONObject(i) ?: continue
                if (o.optString("protocol") in listOf("freedom", "blackhole", "dns", "loopback")) continue
                val st = o.optJSONObject("settings") ?: continue
                val e = st.optJSONArray("vnext")?.optJSONObject(0) ?: st.optJSONArray("servers")?.optJSONObject(0) ?: st
                val host = e.optString("address")
                val port = e.optInt("port", 0)
                if (host.isNotEmpty() && port > 0) return host to port
            }
        } catch (e: Exception) {
            // нестандартный конфиг — пинга не будет, подключению это не мешает
        }
        return null
    }

    /**
     * Пинг до сервера в миллисекундах: время TCP-соединения, лучший из трёх замеров. -1 — сервер не ответил.
     * Приложение вынесено из туннеля, поэтому замер честный и при включённом VPN. Вызывать не в потоке интерфейса.
     */
    fun ping(s: VpnServer): Int {
        val ep = endpoint(s) ?: return -1
        val addr = try { InetAddress.getByName(ep.first) } catch (e: Exception) { return -1 }
        var best = Long.MAX_VALUE
        for (n in 0 until 3) {
            val sock = Socket()
            try {
                val t0 = System.nanoTime()
                sock.connect(InetSocketAddress(addr, ep.second), 2000)
                best = minOf(best, (System.nanoTime() - t0) / 1_000_000)
            } catch (e: Exception) {
                if (best == Long.MAX_VALUE) break   // первый замер не прошёл — сервер молчит, не ждём ещё 4 секунды
            } finally {
                try { sock.close() } catch (e: Exception) { }
            }
        }
        return if (best == Long.MAX_VALUE) -1 else best.toInt()
    }

    fun pick(list: List<VpnServer>): VpnServer =
        list.firstOrNull { it.name == Store.vpnServer } ?: list.first()

    fun parse(raw: String): List<VpnServer> {
        val t = raw.trim().removePrefix("\uFEFF")
        if (t.isEmpty()) throw SubException("Подписка вернула пустой ответ")
        if (t.startsWith("<")) throw SubException("Сервер вернул веб-страницу вместо конфига (проверьте User-Agent в VpnSub.UA)")
        val list: List<VpnServer> = try {
            when {
                t.startsWith("[") -> parseJsonArray(JSONArray(t))
                t.startsWith("{") -> listOfNotNull(parseJsonConfig(JSONObject(t), "Сервер"))
                else -> parseLinks(decodeIfBase64(t))
            }
        } catch (e: org.json.JSONException) {
            throw SubException("Не удалось разобрать подписку")
        }
        if (list.isEmpty()) throw SubException("В подписке нет поддерживаемых серверов")
        return list
    }

    // ---------- Xray-JSON ----------

    private fun parseJsonArray(a: JSONArray): List<VpnServer> {
        val out = ArrayList<VpnServer>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            parseJsonConfig(o, "Сервер ${i + 1}")?.let { out.add(it) }
        }
        return out
    }

    private fun parseJsonConfig(o: JSONObject, def: String): VpnServer? {
        if (o.optJSONArray("outbounds") == null) return null
        return VpnServer(o.optString("remarks").ifEmpty { def }, null, o)
    }

    // ---------- ссылки ----------

    private fun dec(s: String) = try { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { s }

    private fun b64(s: String): ByteArray {
        var t = s.trim().replace('-', '+').replace('_', '/').replace("\n", "").replace("\r", "")
        while (t.length % 4 != 0) t += "="
        return Base64.decode(t, Base64.DEFAULT)
    }

    private fun decodeIfBase64(t: String): String {
        if (t.contains("://")) return t
        return try { String(b64(t), Charsets.UTF_8) } catch (e: Exception) { throw SubException("Неизвестный формат подписки") }
    }

    private fun parseLinks(text: String): List<VpnServer> {
        val out = ArrayList<VpnServer>()
        for (line in text.lines()) {
            val l = line.trim()
            if (l.isEmpty()) continue
            try {
                val s = when {
                    l.startsWith("vless://") -> vless(l)
                    l.startsWith("trojan://") -> trojan(l)
                    l.startsWith("vmess://") -> vmess(l)
                    l.startsWith("ss://") -> ss(l)
                    else -> null
                }
                if (s != null) out.add(s)
            } catch (e: Exception) {
                // битую строку пропускаем, остальные серверы работают
            }
        }
        return out
    }

    private fun params(q: String): Map<String, String> =
        q.split('&').filter { it.contains('=') }.associate { it.substringBefore('=') to dec(it.substringAfter('=')) }

    private fun hostPort(hp: String): Pair<String, Int> {
        val host = if (hp.startsWith("[")) hp.substring(1, hp.indexOf(']')) else hp.substringBeforeLast(':')
        return host to hp.substringAfterLast(':').trim().toInt()
    }

    private class Parts(val user: String, val host: String, val port: Int, val q: Map<String, String>, val name: String)

    private fun split(l: String): Parts {
        val body = l.substringAfter("://")
        val name = dec(body.substringAfter('#', ""))
        val main = body.substringBefore('#')
        val q = params(main.substringAfter('?', ""))
        val auth = main.substringBefore('?').trimEnd('/')
        val user = dec(auth.substringBeforeLast('@', ""))
        val (host, port) = hostPort(auth.substringAfterLast('@'))
        return Parts(user, host, port, q, name.ifEmpty { "$host:$port" })
    }

    private fun vless(l: String): VpnServer {
        val p = split(l)
        val user = JSONObject().put("id", p.user).put("encryption", p.q["encryption"] ?: "none")
        p.q["flow"]?.takeIf { it.isNotEmpty() }?.let { user.put("flow", it) }
        val ob = JSONObject().put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(
                JSONObject().put("address", p.host).put("port", p.port).put("users", JSONArray().put(user))
            )))
            .put("streamSettings", stream(p.q, "none"))
        return VpnServer(p.name, ob, null)
    }

    private fun trojan(l: String): VpnServer {
        val p = split(l)
        val ob = JSONObject().put("protocol", "trojan")
            .put("settings", JSONObject().put("servers", JSONArray().put(
                JSONObject().put("address", p.host).put("port", p.port).put("password", p.user)
            )))
            .put("streamSettings", stream(p.q, "tls"))
        return VpnServer(p.name, ob, null)
    }

    private fun vmess(l: String): VpnServer {
        val j = JSONObject(String(b64(l.removePrefix("vmess://").substringBefore('#')), Charsets.UTF_8))
        val host = j.optString("add")
        val port = j.optString("port").toInt()
        val q = HashMap<String, String>()
        q["type"] = j.optString("net").ifEmpty { "tcp" }
        q["security"] = j.optString("tls").ifEmpty { "none" }
        q["sni"] = j.optString("sni")
        q["host"] = j.optString("host")
        q["path"] = j.optString("path")
        q["alpn"] = j.optString("alpn")
        q["fp"] = j.optString("fp")
        q["headerType"] = j.optString("type")
        val user = JSONObject().put("id", j.optString("id")).put("alterId", j.optInt("aid", 0))
            .put("security", j.optString("scy").ifEmpty { "auto" })
        val ob = JSONObject().put("protocol", "vmess")
            .put("settings", JSONObject().put("vnext", JSONArray().put(
                JSONObject().put("address", host).put("port", port).put("users", JSONArray().put(user))
            )))
            .put("streamSettings", stream(q, "none"))
        return VpnServer(j.optString("ps").ifEmpty { "$host:$port" }, ob, null)
    }

    private fun ss(l: String): VpnServer {
        val body = l.removePrefix("ss://")
        val name = dec(body.substringAfter('#', ""))
        val main = body.substringBefore('#').substringBefore('?').trimEnd('/')
        val method: String
        val pass: String
        val hp: String
        if (main.contains('@')) {
            val ui = main.substringBeforeLast('@')
            val d = if (ui.contains(':')) dec(ui) else String(b64(ui), Charsets.UTF_8)
            method = d.substringBefore(':')
            pass = d.substringAfter(':')
            hp = main.substringAfterLast('@')
        } else {
            val d = String(b64(main), Charsets.UTF_8)
            method = d.substringBefore(':')
            pass = d.substringAfter(':').substringBeforeLast('@')
            hp = d.substringAfterLast('@')
        }
        val (host, port) = hostPort(hp)
        val ob = JSONObject().put("protocol", "shadowsocks")
            .put("settings", JSONObject().put("servers", JSONArray().put(
                JSONObject().put("address", host).put("port", port).put("method", method).put("password", pass)
            )))
        return VpnServer(name.ifEmpty { "$host:$port" }, ob, null)
    }

    /** streamSettings из параметров ссылки: транспорт (tcp/ws/grpc/httpupgrade/xhttp) и защита (tls/reality). */
    private fun stream(q: Map<String, String>, defSec: String): JSONObject {
        val net = q["type"].orEmpty().ifEmpty { "tcp" }.let { if (it == "splithttp") "xhttp" else it }
        val sec = q["security"].orEmpty().ifEmpty { defSec }
        val s = JSONObject().put("network", net).put("security", sec)
        val sni = q["sni"].orEmpty()
        val host = q["host"].orEmpty()
        val path = q["path"].orEmpty()
        when (sec) {
            "tls" -> s.put("tlsSettings", JSONObject().also { t ->
                if (sni.isNotEmpty()) t.put("serverName", sni)
                q["fp"]?.takeIf { it.isNotEmpty() }?.let { t.put("fingerprint", it) }
                q["alpn"]?.takeIf { it.isNotEmpty() }?.let { t.put("alpn", JSONArray(it.split(','))) }
                if (q["allowInsecure"] == "1") t.put("allowInsecure", true)
            })
            "reality" -> s.put("realitySettings", JSONObject()
                .put("serverName", sni)
                .put("fingerprint", q["fp"].orEmpty().ifEmpty { "chrome" })
                .put("publicKey", q["pbk"].orEmpty())
                .put("shortId", q["sid"].orEmpty())
                .put("spiderX", q["spx"].orEmpty()))
        }
        when (net) {
            "ws" -> s.put("wsSettings", JSONObject().put("path", path.ifEmpty { "/" }).also {
                if (host.isNotEmpty()) it.put("headers", JSONObject().put("Host", host))
            })
            "grpc" -> s.put("grpcSettings", JSONObject()
                .put("serviceName", q["serviceName"].orEmpty().ifEmpty { path })
                .put("multiMode", q["mode"] == "multi"))
            "httpupgrade" -> s.put("httpupgradeSettings", JSONObject().put("path", path.ifEmpty { "/" }).put("host", host))
            "xhttp" -> s.put("xhttpSettings", JSONObject()
                .put("path", path.ifEmpty { "/" }).put("host", host)
                .put("mode", q["mode"].orEmpty().ifEmpty { "auto" })
                .also { x ->
                    q["extra"]?.takeIf { it.startsWith("{") }?.let { e -> try { x.put("extra", JSONObject(e)) } catch (ex: Exception) { } }
                })
            "tcp" -> if (q["headerType"] == "http") s.put("tcpSettings", JSONObject().put("header", JSONObject()
                .put("type", "http")
                .put("request", JSONObject()
                    .put("path", JSONArray().put(path.ifEmpty { "/" }))
                    .put("headers", JSONObject().put("Host", JSONArray().put(host))))))
        }
        return s
    }

    // ---------- итоговый конфиг ----------

    /** Локальные сети идут напрямую. Списком адресов, а не «geoip:private»: для того нужен файл geoip.dat, которого в приложении нет. */
    private val PRIVATE = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16", "100.64.0.0/10",
        "fc00::/7", "fe80::/10", "::1/128"
    )

    private fun isGeo(v: String) = v.startsWith("geoip:") || v.startsWith("geosite:") || v.startsWith("ext:")

    /** Убирает из списка записи, требующие geoip.dat / geosite.dat; «geoip:private» заменяет на PRIVATE. */
    private fun stripList(arr: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i)
            when {
                v == "geoip:private" -> PRIVATE.forEach { out.put(it) }
                isGeo(v) -> { }
                else -> out.put(v)
            }
        }
        return out
    }

    /**
     * Без geoip.dat / geosite.dat ядро не принимает правила с «geoip:…» и «geosite:…» и не запускается вовсе.
     * Поэтому из присланного подпиской конфига такие условия убираем (правило без условий выбрасываем).
     * Цена: пропадают разделения вроде «российские сайты напрямую» — они вернутся, если положить .dat-файлы в filesDir.
     */
    private fun stripGeo(cfg: JSONObject) {
        val rules = cfg.optJSONObject("routing")?.optJSONArray("rules")
        if (rules != null) {
            val keep = JSONArray()
            for (i in 0 until rules.length()) {
                val r = rules.optJSONObject(i) ?: continue
                var hasCondition = false
                for (k in listOf("ip", "source", "domain")) {
                    val a = r.optJSONArray(k) ?: continue
                    val clean = stripList(a)
                    if (clean.length() == 0) r.remove(k) else { r.put(k, clean); hasCondition = true }
                }
                for (k in listOf("port", "sourcePort", "network", "user", "inboundTag", "protocol", "attrs", "process")) {
                    if (r.has(k)) hasCondition = true
                }
                if (hasCondition) keep.put(r)
            }
            cfg.optJSONObject("routing")!!.put("rules", keep)
        }
        val servers = cfg.optJSONObject("dns")?.optJSONArray("servers")
        if (servers != null) {
            val keep = JSONArray()
            for (i in 0 until servers.length()) {
                val sv = servers.opt(i)
                if (sv !is JSONObject) { keep.put(sv); continue }
                var drop = false
                for (k in listOf("domains", "expectIPs", "expectedIPs", "unexpectedIPs")) {
                    val a = sv.optJSONArray(k) ?: continue
                    val clean = stripList(a)
                    if (clean.length() == 0 && a.length() > 0) drop = true   // сервер был только для geosite-доменов
                    else sv.put(k, clean)
                }
                if (!drop) keep.put(sv)
            }
            if (keep.length() == 0) VpnDns.current().servers.forEach { keep.put(it) }
            cfg.getJSONObject("dns").put("servers", keep)
        }
    }

    private fun rule() = JSONObject().put("type", "field")

    /** Тег первого «настоящего» исходящего (не freedom/blackhole/dns/loopback): сюда уходит всё, что должно идти через VPN. */
    private fun proxyTag(cfg: JSONObject): String? {
        val obs = cfg.optJSONArray("outbounds") ?: return null
        for (i in 0 until obs.length()) {
            val o = obs.optJSONObject(i) ?: continue
            if (o.optString("protocol") in listOf("freedom", "blackhole", "dns", "loopback")) continue
            val tag = o.optString("tag")
            if (tag.isNotEmpty()) return tag
        }
        return null
    }

    private fun ensureOutbound(obs: JSONArray, tag: String, protocol: String) {
        for (i in 0 until obs.length()) if (obs.optJSONObject(i)?.optString("tag") == tag) return
        obs.put(JSONObject().put("tag", tag).put("protocol", protocol))
    }

    private fun hasOutboundProtocol(obs: JSONArray, protocol: String): Boolean {
        for (i in 0 until obs.length()) if (obs.optJSONObject(i)?.optString("protocol") == protocol) return true
        return false
    }

    private const val FRAG_TAG = "pharm-frag"

    /**
     * Обход DPI (экспериментально): первый пакет TLS (ClientHello) уходит к серверу кусками, как в Happ и v2rayNG.
     * Делается так: отдельный исходящий «freedom» с настройкой fragment, а на исходящем до сервера в streamSettings.sockopt
     * указывается dialerProxy на него. Трогаем только серверы с tls и reality на TCP-подобных транспортах;
     * если подписка уже задала свой dialerProxy, оставляем как есть. Для Reality помогает не всегда.
     */
    private fun applyFragment(cfg: JSONObject) {
        val obs = cfg.optJSONArray("outbounds") ?: return
        var used = false
        for (i in 0 until obs.length()) {
            val o = obs.optJSONObject(i) ?: continue
            if (o.optString("protocol") in listOf("freedom", "blackhole", "dns", "loopback")) continue
            val st = o.optJSONObject("streamSettings") ?: continue
            val sec = st.optString("security")
            if (sec != "tls" && sec != "reality") continue
            if (st.optString("network", "tcp") in listOf("kcp", "mkcp", "quic")) continue   // это UDP: дробить нечего
            val sock = st.optJSONObject("sockopt") ?: JSONObject().also { st.put("sockopt", it) }
            if (sock.optString("dialerProxy").isNotEmpty()) continue
            sock.put("dialerProxy", FRAG_TAG)
            used = true
        }
        if (!used) return
        for (i in 0 until obs.length()) if (obs.optJSONObject(i)?.optString("tag") == FRAG_TAG) return
        obs.put(JSONObject().put("tag", FRAG_TAG).put("protocol", "freedom")
            .put("settings", JSONObject().put("fragment", JSONObject()
                .put("packets", "tlshello").put("length", "100-200").put("interval", "10-20"))))
    }

    /** Собственный DNS ядра на случай, если подписка не принесла секцию «dns»: выбранный пользователем провайдер. */
    private fun ownDns(): JSONObject {
        val servers = JSONArray()
        VpnDns.current().servers.forEach { servers.put(it) }
        return JSONObject().put("servers", servers).put("queryStrategy", if (Store.vpnIpv6) "UseIP" else "UseIPv4")
    }

    /**
     * Российские домены резолвим через Яндекс DNS: зарубежный резолвер мог бы вернуть IP зарубежного CDN, и правило geoip:ru
     * не сработало бы. Запись ставится первой в списке серверов и действует только для перечисленных доменов; остальные
     * по-прежнему идут на выбранный DNS. Запросы к самому Яндекс DNS уходят напрямую (правило в applyPolicies).
     */
    private fun yandexDns(cfg: JSONObject) {
        val dns = cfg.optJSONObject("dns") ?: JSONObject().also { cfg.put("dns", it) }
        val servers = dns.optJSONArray("servers") ?: JSONArray().also { dns.put("servers", it) }
        for (i in 0 until servers.length()) {
            val v = servers.opt(i)
            val addr = if (v is JSONObject) v.optString("address") else v?.toString()
            if (addr in VpnDns.YANDEX) return
        }
        val ru = JSONObject().put("address", VpnDns.YANDEX[0]).put("domains", JSONArray()
            .put("domain:ru").put("domain:su").put("domain:xn--p1ai").put("geosite:ru-available-only-inside"))
        val out = JSONArray().put(ru)
        for (i in 0 until servers.length()) out.put(servers.get(i))
        if (servers.length() == 0) VpnDns.current().servers.forEach { out.put(it) }   // кроме российских доменов, должен остаться обычный DNS
        dns.put("servers", out)
    }

    /**
     * Правила приложения поверх конфига (и из подписки, и из ссылки). Свои исходящие с уникальными тегами,
     * чтобы не зависеть от того, как назвала теги панель. Порядок правил: проверочный вход → реклама → IPv6 → DNS (перехват) → российское.
     *  • Проверка канала: трафик локального SOCKS-входа (см. VpnProbe) идёт в прокси-исходящий, что бы ни говорили остальные правила.
     *  • Блокировка рекламы (нужны geo-файлы с тегом category-ads-all): домены рекламы уходят в blackhole.
     *  • IPv6 выключен (по умолчанию): весь глобальный IPv6 (2000::/3) сразу «блокируем» в ядре. Маршрут ::/0 остаётся в туннеле,
     *    поэтому утечки мимо VPN нет, а приложения мгновенно откатываются на IPv4 вместо ожидания таймаута.
     *  • «Российское напрямую» (нужны geo-файлы): заблокированное в РФ — через VPN, .ru/.su/.рф и IP России — напрямую;
     *    российские домены резолвятся через Яндекс DNS напрямую, а DNS-запросы приложений перехватывает ядро, чтобы это работало.
     */
    private fun applyPolicies(cfg: JSONObject, geo: Boolean, probe: VpnProbe.Cfg?, ads: Boolean) {
        val ruDirect = geo && Store.vpnBypassRu
        if (Store.vpnIpv6 && !ruDirect && !ads && probe == null) return
        val obs = cfg.optJSONArray("outbounds") ?: JSONArray().also { cfg.put("outbounds", it) }
        val routing = cfg.optJSONObject("routing") ?: JSONObject().also { cfg.put("routing", it) }
        val rules = routing.optJSONArray("rules") ?: JSONArray()
        val add = ArrayList<JSONObject>()
        val proxy = proxyTag(cfg)

        if (probe != null && proxy != null) {
            add.add(rule().put("inboundTag", JSONArray().put(VpnProbe.INBOUND_TAG)).put("outboundTag", proxy))
        }
        if (ads) {
            ensureOutbound(obs, "pharm-block", "blackhole")
            add.add(rule().put("domain", JSONArray().put("geosite:${GeoFiles.ADS_TAG}")).put("outboundTag", "pharm-block"))
        }
        if (!Store.vpnIpv6) {
            ensureOutbound(obs, "pharm-block", "blackhole")
            add.add(rule().put("ip", JSONArray().put("2000::/3")).put("outboundTag", "pharm-block"))
        }
        if (ruDirect && proxy != null) {
            ensureOutbound(obs, "pharm-direct", "freedom")
            yandexDns(cfg)
            if (!hasOutboundProtocol(obs, "dns")) {
                // DNS-запросы приложений обрабатывает ядро: только так работает раздельный DNS (Яндекс для .ru, выбранный — для остального)
                ensureOutbound(obs, "pharm-dns", "dns")
                add.add(rule().put("inboundTag", JSONArray().put("tun-in")).put("port", "53").put("outboundTag", "pharm-dns"))
            }
            add.add(rule().put("ip", JSONArray(VpnDns.YANDEX)).put("outboundTag", "pharm-direct"))
            add.add(rule().put("domain", JSONArray().put("geosite:ru-blocked")).put("outboundTag", proxy))
            add.add(rule().put("ip", JSONArray().put("geoip:ru-blocked")).put("outboundTag", proxy))
            add.add(rule().put("domain", JSONArray()
                .put("regexp:\\.ru\$").put("regexp:\\.su\$").put("regexp:\\.xn--p1ai\$")
                .put("geosite:ru-available-only-inside")).put("outboundTag", "pharm-direct"))
            add.add(rule().put("ip", JSONArray().put("geoip:ru")).put("outboundTag", "pharm-direct"))
            // правила по IP работают для доменов, только если ядро умеет их резолвить
            if (routing.optString("domainStrategy", "AsIs") == "AsIs") routing.put("domainStrategy", "IPIfNonMatch")
        }
        val out = JSONArray()
        add.forEach { out.put(it) }
        for (i in 0 until rules.length()) out.put(rules.get(i))
        routing.put("rules", out)
    }

    /**
     * Конфиг Xray для выбранного сервера. Вход — TUN: файловый дескриптор интерфейса отдаёт VpnService.
     * [probe] — локальный вход для проверки канала; [ads] — блокировка рекламы (вызывающий уже убедился, что в geosite.dat есть тег).
     */
    fun buildConfig(s: VpnServer, geo: Boolean = false, probe: VpnProbe.Cfg? = null, ads: Boolean = false): String {
        val cfg: JSONObject
        if (s.full != null) {
            cfg = JSONObject(s.full.toString())   // копия: подписку не портим
            cfg.remove("remarks")
        } else {
            cfg = JSONObject()
            cfg.put("outbounds", JSONArray()
                .put(JSONObject(s.outbound.toString()).put("tag", "proxy"))
                .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
                .put(JSONObject().put("tag", "block").put("protocol", "blackhole")))
            cfg.put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", JSONArray().put(
                JSONObject().put("type", "field").put("ip", JSONArray(PRIVATE)).put("outboundTag", "direct")
            )))
        }
        if (!geo) stripGeo(cfg)   // с geo-файлами правила подписки работают как есть
        if (!cfg.has("dns")) cfg.put("dns", ownDns())
        if (Store.vpnFragment) applyFragment(cfg)
        applyPolicies(cfg, geo, probe, ads && geo)
        cfg.put("log", JSONObject().put("loglevel", "warning"))
        val inbounds = JSONArray().put(JSONObject()
            .put("tag", "tun-in")
            .put("protocol", "tun")
            .put("settings", JSONObject().put("name", "xray0").put("MTU", 1500))
            .put("sniffing", JSONObject().put("enabled", true).put("routeOnly", true)
                .put("destOverride", JSONArray().put("http").put("tls").put("quic"))))
        if (probe != null) {
            inbounds.put(JSONObject()
                .put("tag", VpnProbe.INBOUND_TAG)
                .put("listen", "127.0.0.1")
                .put("port", probe.port)
                .put("protocol", "socks")
                .put("settings", JSONObject()
                    .put("auth", "password")
                    .put("accounts", JSONArray().put(JSONObject().put("user", probe.user).put("pass", probe.pass)))
                    .put("udp", false)))
        }
        cfg.put("inbounds", inbounds)
        return cfg.toString()
    }
}
