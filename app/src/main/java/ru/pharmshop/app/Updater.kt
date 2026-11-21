package ru.pharmshop.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Обновление приложения через GitHub Releases.
 * На каждый push в main GitHub Actions собирает APK и публикует релиз с тегом build-<versionCode>.
 * Приложение смотрит последний релиз и, если versionCode больше текущего, скачивает APK и запускает установку.
 */
object Updater {
    class Info(val versionCode: Int, val versionName: String, val notes: String, val apkUrl: String, val size: Long)

    /** Репозиторий подставляется сборкой на GitHub (GITHUB_REPOSITORY), например "user/pharmshop-app2". */
    private val repo: String get() = BuildConfig.GITHUB_REPO

    val configured: Boolean get() = repo.isNotEmpty()

    /** Пояснение к последней проверке (для диагностики, когда обновлений не нашлось). */
    @Volatile var lastNote = ""

    /** null — установлена последняя версия. */
    fun check(): Info? {
        if (!configured) throw IllegalStateException("Обновления доступны только в сборке с GitHub")
        val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "PharmShopApp")
            val code = c.responseCode
            if (code == 404) {   // релизов ещё нет (или репозиторий закрыт)
                lastNote = "В репозитории $repo не найдено ни одного релиза (или репозиторий закрыт)."
                return null
            }
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw Exception("GitHub ответил кодом $code")
            val r = JSONObject(text)
            val tag = r.optString("tag_name")
            val remote = tag.removePrefix("build-").toIntOrNull()
            if (remote == null) {
                lastNote = "Последний релиз называется «$tag», а нужен тег вида build-107."
                return null
            }
            if (remote <= BuildConfig.VERSION_CODE) {
                lastNote = "Последний релиз на GitHub: сборка $remote, у вас: сборка ${BuildConfig.VERSION_CODE}."
                return null
            }
            val assets = r.optJSONArray("assets")
            if (assets == null) { lastNote = "В релизе $tag нет файлов."; return null }
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    return Info(
                        remote,
                        r.optString("name").ifEmpty { "сборка $remote" },
                        r.optString("body").trim(),
                        a.optString("browser_download_url"),
                        a.optLong("size")
                    )
                }
            }
            lastNote = "В релизе $tag нет файла .apk."
            return null
        } finally {
            c.disconnect()
        }
    }

    /** Скачивает APK в кэш приложения. onProgress получает проценты 0..100. */
    fun download(ctx: Context, info: Info, onProgress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "update.apk")
        val c = URL(info.apkUrl).openConnection() as HttpURLConnection   // GitHub перенаправляет на CDN — HttpURLConnection идёт сам
        try {
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "PharmShopApp")
            if (c.responseCode !in 200..299) throw Exception("Не удалось скачать (код ${c.responseCode})")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: info.size
            var done = 0L
            var lastPct = -1
            c.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
            if (total > 0 && done < total) throw Exception("Файл скачался не полностью, попробуйте ещё раз")
            return out
        } finally {
            c.disconnect()
        }
    }

    /** Можно ли ставить APK из этого приложения (Android 8+ требует отдельного разрешения). */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(ctx: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
        ctx.startActivity(i)
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}
