package com.brotik.portfolio.data

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** Минимальный HTTP-клиент без зависимостей: только GET, gzip, таймауты. */
object Http {

    class HttpException(val code: Int, message: String) : Exception(message)

    fun get(url: String, timeoutMs: Int = 12000): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", "Portfolio/1.0 (Android)")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Encoding", "gzip")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw HttpException(code, "HTTP $code for $url")
            }
            val enc = conn.contentEncoding
            val raw = conn.inputStream
            val stream = if (enc?.contains("gzip", ignoreCase = true) == true) GZIPInputStream(raw) else raw
            return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        } finally {
            conn?.disconnect()
        }
    }
}
