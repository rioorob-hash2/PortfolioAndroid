package com.brotik.portfolio.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.brotik.portfolio.R
import com.brotik.portfolio.core.AlertKind
import com.brotik.portfolio.core.Fmt
import com.brotik.portfolio.core.PriceAlert
import com.brotik.portfolio.ui.CoinActivity
import com.brotik.portfolio.ui.MainActivity

/** Канал и показ уведомлений «цена ниже средней» / «цель достигнута». */
object AlertNotifier {

    const val CHANNEL_ID = "price_alerts"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            ctx.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = ctx.getString(R.string.notif_channel_desc)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    /** Разрешены ли уведомления: и системное разрешение, и пользовательский запрет канала. */
    fun allowed(ctx: Context): Boolean {
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return true
    }

    /** Показывает уведомления; возвращает те, что реально ушли (для отметки анти-спама). */
    fun show(ctx: Context, alerts: List<PriceAlert>): List<PriceAlert> {
        if (alerts.isEmpty()) return emptyList()
        ensureChannel(ctx)
        if (!allowed(ctx)) return emptyList()
        val manager = NotificationManagerCompat.from(ctx)
        val shown = ArrayList<PriceAlert>(alerts.size)
        alerts.forEach { alert ->
            val notification = runCatching {
                NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notify)
                    .setContentTitle(title(ctx, alert))
                    .setContentText(body(ctx, alert))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body(ctx, alert)))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .setContentIntent(contentIntent(ctx, alert))
                    .build()
            }.getOrNull() ?: return@forEach
            val ok = runCatching { manager.notify(idOf(alert), notification) }.isSuccess
            if (ok) shown.add(alert)
        }
        return shown
    }

    private fun title(ctx: Context, alert: PriceAlert): String = when (alert.kind) {
        AlertKind.BELOW_AVG -> ctx.getString(
            R.string.notif_below_title,
            alert.symbol,
            Fmt.pctPlain(alert.dropPct),
        )
        AlertKind.TARGET_HIT -> ctx.getString(R.string.notif_target_title, alert.symbol)
    }

    private fun body(ctx: Context, alert: PriceAlert): String = when (alert.kind) {
        AlertKind.BELOW_AVG -> ctx.getString(
            R.string.notif_below_text,
            Fmt.price(alert.price),
            Fmt.price(alert.avgPrice),
        )
        AlertKind.TARGET_HIT -> ctx.getString(
            R.string.notif_target_text,
            Fmt.price(alert.price),
            Fmt.price(alert.targetPrice),
        )
    }

    /** Стабильный id: одно уведомление на пару «монета + тип». */
    fun idOf(alert: PriceAlert): Int = 1000 + alert.key.hashCode().and(0xFFFF)

    /**
     * Тап по уведомлению открывает монету, а «назад» ведёт на главный экран,
     * а не выкидывает из приложения.
     */
    private fun contentIntent(ctx: Context, alert: PriceAlert): PendingIntent? = runCatching {
        val main = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val coin = Intent(ctx, CoinActivity::class.java).apply {
            putExtra(CoinActivity.EXTRA_SYMBOL, alert.symbol)
        }
        androidx.core.app.TaskStackBuilder.create(ctx)
            .addNextIntent(main)
            .addNextIntent(coin)
            .getPendingIntent(
                idOf(alert),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }.getOrNull()
}
