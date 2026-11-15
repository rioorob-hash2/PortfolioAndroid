package com.brotik.portfolio.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.brotik.portfolio.R
import com.brotik.portfolio.core.Calc
import com.brotik.portfolio.core.Fmt
import com.brotik.portfolio.core.Portfolio
import com.brotik.portfolio.data.PriceRepository
import com.brotik.portfolio.data.Store
import com.brotik.portfolio.ui.MainActivity
import com.brotik.portfolio.work.RefreshScheduler

/**
 * Виджет «Портфель»: стоимость, PnL и время обновления цен.
 * Данные берёт из Store (JSON) и кэша цен — сеть не трогает: это делает воркер.
 */
object WidgetRenderer {

    fun widgetIds(ctx: Context): IntArray = runCatching {
        AppWidgetManager.getInstance(ctx)
            .getAppWidgetIds(ComponentName(ctx, PortfolioWidgetProvider::class.java))
    }.getOrDefault(IntArray(0))

    /** Перерисовать все виджеты (после обновления цен/данных). */
    fun refreshAll(ctx: Context) {
        val ids = widgetIds(ctx)
        if (ids.isEmpty()) return
        val manager = AppWidgetManager.getInstance(ctx)
        val store = Store(ctx)
        val portfolio = runCatching { store.load() ?: store.seed() }.getOrNull() ?: Portfolio()
        val repo = PriceRepository(ctx)
        val views = buildViews(ctx, portfolio, repo.cached(), repo.lastUpdateAt())
        ids.forEach { id -> runCatching { manager.updateAppWidget(id, views) } }
    }

    /** Раскладка виджета по текущим данным. Отдельно — чтобы проверять тестами. */
    fun buildViews(
        ctx: Context,
        portfolio: Portfolio,
        prices: Map<String, Double>,
        updatedAt: Long,
    ): RemoteViews {
        val views = RemoteViews(ctx.packageName, R.layout.widget_portfolio)
        val totals = Calc.portfolioTotals(portfolio.assets, prices)
        val hasPrices = prices.isNotEmpty()
        val up = totals.pnl >= 0

        views.setTextViewText(R.id.widgetValue, if (hasPrices) Fmt.money(totals.value) else "—")
        views.setTextViewText(
            R.id.widgetPnl,
            if (hasPrices) "${Fmt.moneySigned(totals.pnl)} ${Fmt.pct(totals.pnlPct)}" else "нет цен",
        )
        val pnlColor = ContextCompat.getColor(ctx, if (up) R.color.up else R.color.down)
        views.setTextColor(R.id.widgetPnl, if (hasPrices) pnlColor else ContextCompat.getColor(ctx, R.color.text_faint))
        views.setTextColor(R.id.widgetValue, ContextCompat.getColor(ctx, R.color.text))
        views.setTextViewText(
            R.id.widgetUpdated,
            if (updatedAt > 0) ctx.getString(R.string.widget_updated, Fmt.timeOfDay(updatedAt))
            else ctx.getString(R.string.widget_no_data),
        )

        views.setOnClickPendingIntent(R.id.widgetRoot, openAppIntent(ctx))
        views.setOnClickPendingIntent(
            R.id.widgetRefresh,
            WidgetRefreshReceiver.pendingIntent(ctx),
        )
        return views
    }

    private fun openAppIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx,
        10,
        Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** Провайдер виджета: при добавлении на стол и изменении размера — перерисовка. */
class PortfolioWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetRenderer.refreshAll(ctx)
        RefreshScheduler.ensureScheduled(ctx)
    }

    override fun onEnabled(ctx: Context) {
        RefreshScheduler.ensureScheduled(ctx)
        WidgetRenderer.refreshAll(ctx)
    }

    override fun onAppWidgetOptionsChanged(
        ctx: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        WidgetRenderer.refreshAll(ctx)
    }
}

/** Кнопка обновления на виджете: сразу перерисовка из кэша + фоновое обновление цен. */
class WidgetRefreshReceiver : android.content.BroadcastReceiver() {

    companion object {
        private const val ACTION = "com.brotik.portfolio.WIDGET_REFRESH"

        fun pendingIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx,
            20,
            Intent(ctx, WidgetRefreshReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        WidgetRenderer.refreshAll(ctx)
        RefreshScheduler.checkNow(ctx)
    }
}
