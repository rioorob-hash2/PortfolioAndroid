package com.brotik.portfolio.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.brotik.portfolio.core.EntryJson
import com.brotik.portfolio.core.Portfolio
import org.json.JSONObject

/**
 * Копия резервного бэкапа в общей папке «Загрузки/Portfolio» через MediaStore (Android 10+).
 *
 * Зачем: SAF-папку пользователь может не выбрать или она окажется недоступной, а
 * Загрузки переживают переустановку приложения. Свои файлы приложение читает без разрешений;
 * чужие (записанные прошлой установкой) система может скрыть — тогда UI предлагает
 * выбрать файл вручную через системный диалог.
 */
class DownloadBackups(private val ctx: Context) {

    data class StoredBackup(
        val uri: Uri,
        val name: String,
        val modifiedAt: Long,
        val size: Long,
    )

    companion object {
        const val FOLDER = "Portfolio"
        const val LATEST = "portfolio-latest.json"
        const val PREFIX = "portfolio-"

        fun relativePath(): String = "Download/$FOLDER"

        /** Наше ли это имя файла копии (а не случайный json в Загрузках). */
        fun isBackupName(name: String?): Boolean = when {
            name.isNullOrBlank() -> false
            !name.startsWith(PREFIX) -> false
            !name.endsWith(".json", ignoreCase = true) -> false
            else -> true
        }

        /** Самая свежая копия; при равном времени приоритет у portfolio-latest.json. */
        fun newest(list: List<StoredBackup>): StoredBackup? = list.maxWithOrNull(
            compareBy({ it.modifiedAt }, { if (it.name == LATEST) 1 else 0 })
        )
    }

    private val collection: Uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI

    /** Пишет файл копии в Загрузки (перезаписывая одноимённый). false — не получилось. */
    fun save(name: String, json: String): Boolean = runCatching {
        delete(name)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath())
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(collection, values) ?: return@runCatching false
        val written = runCatching {
            ctx.contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(json.toByteArray(Charsets.UTF_8))
            } != null
        }.getOrDefault(false)
        if (!written) {
            runCatching { ctx.contentResolver.delete(uri, null, null) }
            return@runCatching false
        }
        runCatching {
            ctx.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
        }
        true
    }.getOrDefault(false)

    /** Все копии в Загрузках/Portfolio, новые сверху. Пусто — если доступа нет или пусто. */
    fun list(): List<StoredBackup> = runCatching {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.DATE_MODIFIED,
            MediaStore.Downloads.SIZE,
        )
        val out = ArrayList<StoredBackup>()
        ctx.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
            arrayOf("${relativePath()}%"),
            null,
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameIdx = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val modIdx = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
            val sizeIdx = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            while (c.moveToNext()) {
                val name = c.getString(nameIdx) ?: continue
                if (!isBackupName(name)) continue
                out.add(
                    StoredBackup(
                        uri = ContentUris.withAppendedId(collection, c.getLong(idIdx)),
                        name = name,
                        modifiedAt = c.getLong(modIdx) * 1000L,
                        size = c.getLong(sizeIdx),
                    )
                )
            }
        }
        out.sortedByDescending { it.modifiedAt }
    }.getOrDefault(emptyList())

    fun newest(): StoredBackup? = newest(list())

    /** Читает и разбирает копию; null — файл битый или недоступен. */
    fun read(backup: StoredBackup): Portfolio? = runCatching {
        val text = ctx.contentResolver.openInputStream(backup.uri)?.use {
            it.bufferedReader(Charsets.UTF_8).readText()
        } ?: return null
        val p = EntryJson.fromJson(JSONObject(text))
        p.takeIf { it.assets.isNotEmpty() }
    }.getOrNull()

    fun readUri(uri: Uri): Portfolio? = runCatching {
        val text = ctx.contentResolver.openInputStream(uri)?.use {
            it.bufferedReader(Charsets.UTF_8).readText()
        } ?: return null
        EntryJson.fromJson(JSONObject(text)).takeIf { it.assets.isNotEmpty() }
    }.getOrNull()

    fun delete(name: String): Boolean = runCatching {
        ctx.contentResolver.delete(
            collection,
            "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
            arrayOf(name, "${relativePath()}%"),
        )
        true
    }.getOrDefault(false)
}
