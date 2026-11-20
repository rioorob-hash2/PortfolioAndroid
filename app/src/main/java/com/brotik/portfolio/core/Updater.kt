package com.brotik.portfolio.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Обновления из GitHub Releases: проверка последнего релиза, скачивание APK
 * и передача его системному установщику. Никаких сторонних библиотек.
 */
object Updater {

    private const val OWNER = "rioorob-hash2"
    private const val REPO = "PortfolioAndroid-releases"
    private const val API = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val UA = "PortfolioAndroid-updater"

    data class Release(
        val tag: String,
        val apkUrl: String,
        val apkName: String,
        val sizeBytes: Long,
        val notes: String,
    )

    /** Последний релиз; null — сеть/репозиторий недоступны. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val text = httpGet(API) ?: return@withContext null
        val json = JSONObject(text)
        val tag = json.optString("tag_name").removePrefix("v")
        val notes = json.optString("body").lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(300)
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                return@withContext Release(
                    tag = tag,
                    apkUrl = asset.optString("browser_download_url"),
                    apkName = name,
                    sizeBytes = asset.optLong("size"),
                    notes = notes,
                )
            }
        }
        Release(tag, "", "", 0, notes)
    }

    /** Сравнение версий «1.9» / «v1.10» по числовым частям. >0 — candidate новее current. */
    fun compare(candidate: String, current: String): Int {
        fun parts(v: String) = v.removePrefix("v").split('.', '-', '+')
            .map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /** Скачать APK в cache/updates. onProgress — проценты 0..100. */
    suspend fun download(
        context: Context,
        release: Release,
        onProgress: (Int) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(release.apkUrl.isNotBlank()) { "в релизе нет APK" }
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, release.apkName.ifBlank { "update.apk" })
            val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
            }
            try {
                if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
                conn.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            target
        }
    }

    /** Разрешена ли установка из этого приложения (Android 8+). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Экран «разрешить установку из неизвестных источников» для нашего пакета. */
    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Передать скачанный APK системному установщику. */
    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun httpGet(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
