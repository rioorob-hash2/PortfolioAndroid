package com.brotik.portfolio

import android.app.Application
import com.brotik.portfolio.core.Candle
import com.brotik.portfolio.core.CandleRange
import com.brotik.portfolio.core.HistoryRange
import com.brotik.portfolio.data.BackupManager
import com.brotik.portfolio.data.PriceRepository
import com.brotik.portfolio.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class App : Application() {

    lateinit var store: Store
        private set
    lateinit var backup: BackupManager
        private set
    lateinit var prices: PriceRepository
        private set

    /** Точка подмены для тестов: по умолчанию — реальная сеть. */
    var priceFetcher: suspend (Collection<String>) -> Map<String, Double> = { symbols ->
        withContext(Dispatchers.IO) { prices.fetch(symbols) }
    }

    var candleFetcher: suspend (String, CandleRange) -> List<Candle> = { symbol, range ->
        withContext(Dispatchers.IO) { prices.fetchCandles(symbol, range) }
    }

    var historyFetcher: suspend (List<String>, HistoryRange) -> Map<String, List<Candle>> = { symbols, range ->
        withContext(Dispatchers.IO) {
            symbols.associateWith { prices.fetchCandles(it, range) }.filterValues { it.isNotEmpty() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(this)
        backup = BackupManager(this)
        backup.migrateToManualOnce()
        prices = PriceRepository(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
