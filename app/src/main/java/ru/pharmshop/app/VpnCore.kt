package ru.pharmshop.app

import android.content.Context
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * ЕДИНСТВЕННОЕ место, где приложение знает про ядро Xray (app/libs/libv2ray.aar, сборка AndroidLibXrayLite).
 * Обращение к библиотеке идёт через рефлексию, поэтому проект собирается и без .aar: тогда кнопка «Подключить»
 * покажет «Ядро Xray не подключено», а не уронит сборку. Если у вашей версии .aar другие имена методов
 * (initCoreEnv / newCoreController / startLoop / stopLoop) — правятся только строки ниже.
 */
internal object VpnCore {
    private const val LIB = "libv2ray.Libv2ray"
    private const val HANDLER = "libv2ray.CoreCallbackHandler"

    private var ctl: Any? = null
    private var inited = false

    /** Есть ли ядро в сборке. */
    fun available(): Boolean = try { Class.forName(LIB); true } catch (e: Throwable) { false }

    /** Запускает Xray с конфигом; tunFd — дескриптор интерфейса, который вернул VpnService.Builder.establish(). */
    @Synchronized
    fun start(ctx: Context, config: String, tunFd: Int) {
        try {
            val lib = try { Class.forName(LIB) } catch (e: ClassNotFoundException) {
                throw IllegalStateException("Ядро Xray не подключено: положите libv2ray.aar в app/libs и пересоберите приложение")
            }
            if (!inited) {
                lib.getMethod("initCoreEnv", String::class.java, String::class.java)
                    .invoke(null, ctx.filesDir.absolutePath, Store.vpnHwid)
                inited = true
            }
            stop()
            val hCls = Class.forName(HANDLER)
            // обратные вызовы ядра (startup / shutdown / onEmitStatus) нам не нужны: на всё отвечаем «0»
            val handler = Proxy.newProxyInstance(hCls.classLoader, arrayOf(hCls), InvocationHandler { _, m, _ ->
                when (m.name) {
                    "hashCode" -> 0
                    "equals" -> false
                    "toString" -> "VpnCoreHandler"
                    else -> if (m.returnType == java.lang.Long.TYPE) 0L else null
                }
            })
            val c = lib.getMethod("newCoreController", hCls).invoke(null, handler)
                ?: throw IllegalStateException("Ядро Xray не создало контроллер")
            c.javaClass.getMethod("startLoop", String::class.java, Integer.TYPE).invoke(c, config, tunFd)
            ctl = c
        } catch (e: InvocationTargetException) {
            throw (e.targetException ?: e)   // настоящая причина — внутри обёртки рефлексии
        }
    }

    @Synchronized
    fun stop() {
        val c = ctl ?: return
        try { c.javaClass.getMethod("stopLoop").invoke(c) } catch (e: Throwable) { }
        ctl = null
    }

    val running: Boolean
        get() {
            val c = ctl ?: return false
            return try {
                c.javaClass.getMethod("isRunning").invoke(c) as? Boolean ?: false
            } catch (e: Throwable) {
                try { c.javaClass.getMethod("getIsRunning").invoke(c) as? Boolean ?: false } catch (e2: Throwable) { false }
            }
        }
}
