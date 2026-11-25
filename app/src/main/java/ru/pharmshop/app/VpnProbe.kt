package ru.pharmshop.app

import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Проверка, что канал реально пропускает трафик.
 *
 * Само приложение вынесено из туннеля (addDisallowedApplication), поэтому обычный запрос из приложения пошёл бы мимо VPN.
 * Чтобы проверить именно туннель, в конфиг Xray добавляется локальный SOCKS-вход (только 127.0.0.1, со случайным логином и
 * паролем на каждое подключение — другие приложения им воспользоваться не смогут), а правило маршрутизации направляет его
 * трафик в прокси-исходящий. Через этот вход делается HTTPS-запрос generate_204.
 */
object VpnProbe {
    const val INBOUND_TAG = "pharm-probe"

    /** Параметры локального входа для одного подключения. */
    class Cfg(val port: Int, val user: String, val pass: String)

    private val TARGETS = listOf("www.gstatic.com" to "/generate_204", "cp.cloudflare.com" to "/generate_204")
    private const val IO_TIMEOUT_MS = 4000

    fun newCfg(): Cfg? = try {
        val port = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val rnd = SecureRandom()
        fun token(): String = ByteArray(12).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        Cfg(port, token(), token())
    } catch (e: Exception) {
        null   // без проверки канала VPN всё равно работает
    }

    /** true — запрос generate_204 прошёл через туннель хотя бы к одному из двух узлов. Блокирующий: не вызывать в потоке интерфейса. */
    fun viaTunnel(cfg: Cfg): Boolean {
        for ((host, path) in TARGETS) {
            if (try { get(cfg, host, path) } catch (e: Exception) { false }) return true
        }
        return false
    }

    /** Тот же запрос напрямую (приложение вне туннеля): отличает «упал сервер» от «пропала сеть». */
    fun direct(): Boolean {
        for ((host, path) in TARGETS) {
            val ok = try {
                val c = URL("https://$host$path").openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = IO_TIMEOUT_MS
                    c.readTimeout = IO_TIMEOUT_MS
                    c.instanceFollowRedirects = false
                    c.requestMethod = "GET"
                    c.responseCode == 204
                } finally { c.disconnect() }
            } catch (e: Exception) { false }
            if (ok) return true
        }
        return false
    }

    private fun get(cfg: Cfg, host: String, path: String): Boolean {
        val raw = Socket()
        try {
            raw.soTimeout = IO_TIMEOUT_MS
            raw.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), cfg.port), 2000)
            socks5(raw, cfg, host, 443)
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 443, true) as SSLSocket
            ssl.soTimeout = IO_TIMEOUT_MS
            ssl.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) return false
            ssl.outputStream.write("GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: ${VpnSub.UA}\r\nConnection: close\r\n\r\n".toByteArray())
            ssl.outputStream.flush()
            val line = StringBuilder()
            val inp = ssl.inputStream
            while (line.length < 64) {
                val b = inp.read()
                if (b < 0 || b == '\n'.code) break
                line.append(b.toChar())
            }
            return line.startsWith("HTTP/") && line.contains(" 204")
        } finally {
            try { raw.close() } catch (e: Exception) { }
        }
    }

    /** Минимальный клиент SOCKS5 с логином и паролем (RFC 1928 / 1929): CONNECT по имени узла. */
    private fun socks5(s: Socket, cfg: Cfg, host: String, port: Int) {
        val out = s.getOutputStream()
        val inp = DataInputStream(s.getInputStream())
        out.write(byteArrayOf(5, 1, 2)); out.flush()
        val m = ByteArray(2); inp.readFully(m)
        if (m[0] != 5.toByte() || m[1] != 2.toByte()) throw java.io.IOException("socks: метод не принят")
        val u = cfg.user.toByteArray(); val p = cfg.pass.toByteArray()
        out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p); out.flush()
        val a = ByteArray(2); inp.readFully(a)
        if (a[1] != 0.toByte()) throw java.io.IOException("socks: вход отклонён")
        val h = host.toByteArray()
        out.write(byteArrayOf(5, 1, 0, 3, h.size.toByte()) + h + byteArrayOf((port shr 8).toByte(), (port and 255).toByte())); out.flush()
        val r = ByteArray(4); inp.readFully(r)
        if (r[1] != 0.toByte()) throw java.io.IOException("socks: сервер не ответил (код ${r[1]})")
        val skip = when (r[3].toInt()) { 1 -> 4; 4 -> 16; 3 -> inp.readUnsignedByte(); else -> 0 } + 2
        inp.readFully(ByteArray(skip))
    }
}
