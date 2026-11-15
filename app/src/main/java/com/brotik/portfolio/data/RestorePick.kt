package com.brotik.portfolio.data

import com.brotik.portfolio.core.Portfolio

/**
 * Выбор копии для автовосстановления: берём САМУЮ СВЕЖУЮ из доступных источников,
 * а не первый попавшийся. Иначе старая копия в SAF-папке перебивала бы вчерашнюю в Загрузках.
 */
object RestorePick {

    enum class Source { SAF_FOLDER, DOWNLOADS, LOCAL_COPY }

    data class Candidate(val portfolio: Portfolio, val timestamp: Long, val source: Source)

    /** При равном времени приоритет: SAF-папка → Загрузки → приватная копия. */
    private fun weight(source: Source): Int = when (source) {
        Source.SAF_FOLDER -> 2
        Source.DOWNLOADS -> 1
        Source.LOCAL_COPY -> 0
    }

    fun best(list: List<Candidate>): Candidate? = list
        .filter { it.portfolio.assets.isNotEmpty() }
        .maxWithOrNull(compareBy({ it.timestamp }, { weight(it.source) }))
}
