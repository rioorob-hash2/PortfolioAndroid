package com.brotik.portfolio.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.brotik.portfolio.core.Alerts
import com.brotik.portfolio.data.AlertPrefs
import com.brotik.portfolio.data.PriceRepository
import com.brotik.portfolio.data.Store
import com.brotik.portfolio.notify.AlertNotifier
import com.brotik.portfolio.widget.WidgetRenderer
import java.util.concurrent.TimeUnit

/**
 * Периодический воркер: тянет цены, проверяет уведомления по цене и обновляет виджет.
 * Один воркер на всё — иначе система душила бы фоновые задачи и показывала разные данные.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val store = Store(ctx)
        val portfolio = runCatching { store.load() ?: store.seed() }.getOrNull()
            ?: return Result.success()
        val symbols = portfolio.assets.map { it.symbol }
        // Пустой портфель — нечего проверять: retry тут только жёг бы батарею.
        if (symbols.isEmpty()) return Result.success()

        val repo = PriceRepository(ctx)
        val fresh = runCatching { repo.fetch(symbols) }.getOrNull()
        val prices = if (fresh.isNullOrEmpty()) repo.cached() else fresh
        if (prices.isEmpty()) return Result.retry()

        val alertPrefs = AlertPrefs(ctx)
        val settings = alertPrefs.settings()
        // По протухшему кэшу уведомлять нельзя: «ниже средней» сработало бы ложно.
        val pricesFresh = isFresh(repo.lastUpdateAt())
        if (settings.enabled && pricesFresh) {
            val alerts = Alerts.evaluate(portfolio.assets, prices, settings, alertPrefs.lastSent())
            if (alerts.isNotEmpty()) {
                val shown = AlertNotifier.show(ctx, alerts)
                alertPrefs.record(shown)
            }
        }

        runCatching { WidgetRenderer.refreshAll(ctx) }
        return Result.success()
    }

    /** Цены считаем актуальными, если они моложе двух часов. */
    private fun isFresh(ts: Long): Boolean {
        if (ts <= 0L) return false
        return System.currentTimeMillis() - ts <= 2 * 60 * 60 * 1000L
    }
}

/** Расписание фоновых обновлений и ручной запуск проверки. */
object RefreshScheduler {

    private const val WORK_NAME = "portfolio_refresh"
    private const val ONE_SHOT_NAME = "portfolio_refresh_now"

    /** Периодическое обновление: WorkManager не даёт меньше 15 минут. */
    const val INTERVAL_MINUTES = 15

    private fun constraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Ставит периодическую работу, если её ещё нет (настройки не сбрасываются). */
    fun ensureScheduled(ctx: Context): Boolean = runCatching {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(INTERVAL_MINUTES.toLong(), TimeUnit.MINUTES)
            .setConstraints(constraints())
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        true
    }.getOrDefault(false)

    /** Проверка «прямо сейчас»: цены, уведомления, виджет. */
    fun checkNow(ctx: Context): Boolean = runCatching {
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(constraints())
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniqueWork(ONE_SHOT_NAME, ExistingWorkPolicy.REPLACE, request)
        true
    }.getOrDefault(false)

    fun cancel(ctx: Context) = runCatching {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
    }
}
