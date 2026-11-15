package com.brotik.portfolio.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.brotik.portfolio.core.EntryJson
import com.brotik.portfolio.core.Portfolio
import org.json.JSONObject
import java.io.File

/**
 * Резервные копии. Две дороги, чтобы данные не терялись:
 *  1) выбранная пользователем папка (SAF): папка может быть на телефоне или в облаке;
 *  2) общая папка «Загрузки/Portfolio» через MediaStore — переживает переустановку.
 *
 * В каждой папке держим ОДИН файл portfolio-latest.json и перезаписываем его.
 * Старые снимки (portfolio-<дата>.json) удаляются при каждой записи, чтобы папка не засорялась.
 * Дополнительно всегда пишется приватная копия внутри приложения.
 */
class BackupManager(private val ctx: Context) {

    companion object {
        private const val PREFS = "backup"
        private const val KEY_TREE = "tree_uri"
        private const val KEY_AUTO = "auto"
        private const val KEY_LAST = "last_at"
        private const val KEY_DL_OK = "downloads_ok"
        private const val KEY_MANUAL_MIGRATED = "manual_migrated"
        private const val LATEST = "portfolio-latest.json"
        private const val TEMP_LATEST = "portfolio-latest.tmp.json"
        private const val PREFIX = "portfolio-"
    }

    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val localCopy = File(ctx.filesDir, "backup-latest.json")

    /** Копии в общих Загрузках (MediaStore). */
    val downloads = DownloadBackups(ctx)

    fun treeUri(): Uri? = prefs.getString(KEY_TREE, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun hasFolder(): Boolean = treeUri() != null

    /** Сохраняет выданное системой разрешение на папку (переживает перезапуск). */
    fun setTreeUri(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, flags) }
        prefs.edit().putString(KEY_TREE, uri.toString()).apply()
    }

    fun clearFolder() = prefs.edit().remove(KEY_TREE).apply()

    /** Авто-копия при каждом изменении. По умолчанию ВЫКЛ: копию делает пользователь вручную. */
    fun autoBackup(): Boolean = prefs.getBoolean(KEY_AUTO, false)

    /** Один раз при обновлении: выключаем авто-копии, оставляем ручную кнопку. */
    fun migrateToManualOnce() {
        if (prefs.getBoolean(KEY_MANUAL_MIGRATED, false)) return
        prefs.edit()
            .putBoolean(KEY_AUTO, false)
            .putBoolean(KEY_MANUAL_MIGRATED, true)
            .apply()
    }

    fun setAutoBackup(value: Boolean) = prefs.edit().putBoolean(KEY_AUTO, value).apply()

    fun lastBackupAt(): Long = prefs.getLong(KEY_LAST, 0L)

    fun folderName(): String? {
        val uri = treeUri() ?: return null
        return runCatching {
            DocumentFile.fromTreeUri(ctx, uri)?.name
                ?: uri.lastPathSegment?.substringAfterLast(':')?.replace("%2F", "/")
                ?: uri.toString()
        }.getOrNull()
    }

    /**
     * Пишет копии: приватная + Загрузки всегда, папка SAF — если выбрана.
     * Ошибка папки не мешает копии в Загрузках, но попадает в результат.
     * Вызывать только из фонового потока: внутри есть диск и SAF.
     */
    fun backup(p: Portfolio): Result<Unit> = runCatching {
        val json = EntryJson.toJson(p).toString()
        localCopy.writeText(json, Charsets.UTF_8)

        // Копия в общих Загрузках — best effort: без неё приложение работает.
        val savedToDownloads = runCatching {
            downloads.save(DownloadBackups.LATEST, json)
            runCatching { pruneDownloads() }
            true
        }.getOrDefault(false)

        var safOk = true
        val tree = selectedTree()
        if (tree != null) {
            safOk = writeLatestAtomically(tree, json)
            prune(tree)
        }

        if (tree != null && !safOk && !savedToDownloads) {
            throw IllegalStateException("не удалось записать копию в Загрузки")
        }
        prefs.edit()
            .putLong(KEY_LAST, System.currentTimeMillis())
            .putBoolean(KEY_DL_OK, savedToDownloads)
            .apply()
    }

    /** Удалось ли в последний раз записать копию в Загрузки (для честной подсказки в настройках). */
    fun lastSavedToDownloads(): Boolean = prefs.getBoolean(KEY_DL_OK, true)

    /**
     * Пишем latest через временный файл: старую копию удаляем только после успешной записи,
     * иначе сбой на середине оставлял бы папку без точки восстановления.
     */
    private fun writeLatestAtomically(tree: DocumentFile, json: String): Boolean {
        val tmpName = TEMP_LATEST
        tree.findFile(tmpName)?.delete()
        val tmp = tree.createFile("application/json", tmpName) ?: return false
        val written = runCatching {
            ctx.contentResolver.openOutputStream(tmp.uri, "wt")?.use {
                it.write(json.toByteArray(Charsets.UTF_8))
            } != null
        }.getOrDefault(false)
        if (!written) {
            runCatching { tmp.delete() }
            return false
        }
        tree.findFile(LATEST)?.delete()
        if (runCatching { tmp.renameTo(LATEST) }.getOrDefault(false)) return true
        // Переименование не поддержал провайдер — пишем поверх напрямую и убираем tmp.
        val direct = runCatching {
            val f = tree.createFile("application/json", LATEST) ?: return@runCatching false
            ctx.contentResolver.openOutputStream(f.uri, "wt")?.use {
                it.write(json.toByteArray(Charsets.UTF_8))
            } != null
        }.getOrDefault(false)
        runCatching { tmp.delete() }
        return direct
    }

    private fun selectedTree(): DocumentFile? {
        val uri = treeUri() ?: return null
        val tree = DocumentFile.fromTreeUri(ctx, uri) ?: return null
        if (!tree.canWrite()) throw IllegalStateException("папка доступна только для чтения")
        return tree
    }

    /** Оставляет только portfolio-latest.json: все прочие portfolio-* удаляем. */
    private fun prune(tree: DocumentFile) {
        tree.listFiles()
            .filter { it.isFile }
            .filter { it.name.orEmpty().startsWith(PREFIX) }
            .filter { it.name != LATEST && it.name != TEMP_LATEST }
            .forEach { runCatching { it.delete() } }
    }

    private fun pruneDownloads() {
        downloads.list()
            .filter { it.name != DownloadBackups.LATEST }
            .forEach { runCatching { downloads.delete(it.name) } }
    }

    /** Чистит уже накопленные снимки (после обновления приложения). Фоновый поток. */
    fun cleanupOldSnapshots() {
        runCatching { selectedTree()?.let { prune(it) } }
        runCatching { pruneDownloads() }
    }

    fun latestFile(): DocumentFile? = runCatching {
        selectedTree()?.findFile(LATEST)
    }.getOrNull()

    /** Читает последнюю копию из выбранной папки (для автовосстановления). */
    fun readLatest(): Portfolio? = runCatching {
        val f = latestFile() ?: return null
        ctx.contentResolver.openInputStream(f.uri)?.use { stream ->
            EntryJson.fromJson(JSONObject(stream.bufferedReader(Charsets.UTF_8).readText()))
        }
    }.getOrNull()

    fun readLatestTimestamp(): Long = runCatching { latestFile()?.lastModified() ?: 0L }.getOrDefault(0L)

    /** Самая свежая копия из Загрузок/Portfolio — запасной путь восстановления. */
    fun readDownloadLatest(): Portfolio? {
        val newest = downloads.newest() ?: return null
        return downloads.read(newest)
    }

    fun downloadLatestTimestamp(): Long = downloads.newest()?.modifiedAt ?: 0L

    /** Приватная копия внутри приложения — тоже годится для восстановления. */
    fun readLocalCopy(): Portfolio? = runCatching {
        if (!localCopy.exists()) return null
        EntryJson.fromJson(JSONObject(localCopy.readText(Charsets.UTF_8)))
    }.getOrNull()

    fun exportTo(uri: Uri, p: Portfolio): Result<Unit> = runCatching {
        val json = EntryJson.toJson(p).toString()
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            ?: throw IllegalStateException("нет доступа на запись")
    }

    fun importFrom(uri: Uri): Result<Portfolio> = runCatching {
        val text = ctx.contentResolver.openInputStream(uri)?.use {
            it.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw IllegalStateException("не удалось открыть файл")
        val p = EntryJson.fromJson(JSONObject(text))
        require(p.assets.isNotEmpty()) { "в файле нет активов" }
        p
    }

    fun localCopyExists(): Boolean = localCopy.exists()

    fun localCopyTimestamp(): Long = if (localCopy.exists()) localCopy.lastModified() else 0L
}
