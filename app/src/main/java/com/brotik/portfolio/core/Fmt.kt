package com.brotik.portfolio.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Форматирование чисел/дат «по-русски»: 1 234,56; -57,21; +1,7 %. */
object Fmt {
    private val RU: Locale = Locale("ru", "RU")
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yy")
    private val DATE_FULL: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private fun group(n: Double, decimals: Int): String {
        val nf = NumberFormat.getNumberInstance(RU).apply {
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
            isGroupingUsed = true
        }
        return nf.format(n)
    }

    private fun trimmed(n: Double, maxDecimals: Int): String {
        val bd = BigDecimal(n).setScale(maxDecimals, RoundingMode.HALF_UP).stripTrailingZeros()
        val decimals = maxOf(0, bd.scale())
        return group(bd.toDouble(), minOf(decimals, maxDecimals))
    }

    fun money(v: Double): String {
        val dec = if (v != 0.0 && (Math.abs(v) >= 1_000_000.0)) 0 else 2
        return "$" + group(v, dec)
    }

    fun moneySigned(v: Double): String {
        val s = money(Math.abs(v))
        return if (v > 0) "+$s" else if (v < 0) "-$s" else s
    }

    fun moneyPlain(v: Double): String = "$" + group(Math.abs(v), 2)

    fun pct(v: Double): String {
        val s = trimmed(Math.abs(v), 1)
        return if (v > 0) "+$s %" else if (v < 0) "-$s %" else "0 %"
    }

    fun pctPlain(v: Double): String = trimmed(v, 1) + " %"

    /** Количество монеты: точнее для мелких сумм, короче для крупных. */
    fun amount(v: Double): String {
        val a = Math.abs(v)
        return when {
            a >= 1_000_000 -> trimmed(v, 2)
            a >= 1000 -> trimmed(v, 2)
            a >= 1 -> trimmed(v, 4)
            else -> trimmed(v, 8)
        }
    }

    fun price(v: Double): String = if (v >= 1000.0) "$" + group(v, 0) else "$" + trimmed(v, 4)

    /** Короткое количество для списка: 0,0178 вместо 0,01775286. */
    fun amountShort(v: Double): String {
        val a = Math.abs(v)
        return when {
            a >= 1 -> trimmed(v, 2)
            a >= 0.01 -> trimmed(v, 4)
            else -> trimmed(v, 6)
        }
    }

    fun date(d: LocalDate?): String = d?.format(DATE) ?: "—"

    fun dateFull(d: LocalDate?): String = d?.format(DATE_FULL) ?: "—"

    fun timeOfDay(ts: Long): String {
        if (ts <= 0L) return "—"
        return Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(TIME)
    }

    fun dateTime(ts: Long): String {
        if (ts <= 0L) return "—"
        val z = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
        return z.format(DATE) + " " + z.format(TIME)
    }
}
