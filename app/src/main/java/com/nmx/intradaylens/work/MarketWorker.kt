package com.nmx.intradaylens.work

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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nmx.intradaylens.MainActivity
import com.nmx.intradaylens.R
import com.nmx.intradaylens.data.Phase
import com.nmx.intradaylens.data.Repository
import com.nmx.intradaylens.engine.TradeStatus
import java.util.concurrent.TimeUnit

/** Runs every 15 minutes; only does work during market hours (Mon–Fri 9:30–3:30). */
class MarketWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        if (Repository.phaseNow() != Phase.LIVE) return Result.success()
        val repo = Repository.get(applicationContext)
        val hadToday = repo.store.journal().any { it.date == Repository.now().toLocalDate().toString() }
        return try {
            val (snap, changes) = repo.refresh()
            if (!repo.store.settings().notifications) return Result.success()
            val todayKey = Repository.now().toLocalDate().toString()
            val todays = repo.store.journal().filter { it.date == todayKey }
            if (!hadToday && todays.isNotEmpty()) {
                notify(1, "Today's picks are ready",
                    todays.joinToString("  •  ") { "${it.symbol} ${label(it)} ${(it.probability * 100).toInt()}%" })
            }
            changes.filter { it.old != null && it.pick.date == todayKey }.forEach { c ->
                val p = c.pick
                val msg = when (p.status) {
                    TradeStatus.ACTIVE -> "Entry triggered at ${"%.2f".format(p.fill ?: p.entry)} • SL ${"%.2f".format(p.stop)} • Target ${"%.2f".format(p.target)}"
                    TradeStatus.WIN -> "Target ${"%.2f".format(p.target)} reached (+${"%.1f".format(p.r)}R)"
                    TradeStatus.LOSS -> "Stop-loss ${"%.2f".format(p.stop)} hit (${"%.1f".format(p.r)}R)"
                    TradeStatus.TIME_EXIT -> "Closed at 3:15 PM (${"%+.1f".format(p.r)}R)"
                    else -> null
                } ?: return@forEach
                notify(p.symbol.hashCode(), "${p.symbol} ${label(p)}: ${p.status.label}", msg)
            }
            if (snap.picks.isEmpty()) Result.retry() else Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun label(j: com.nmx.intradaylens.data.JournalPick) =
        if (Repository.isIndex(j.symbol)) (if (j.direction == com.nmx.intradaylens.engine.Direction.LONG) "CALL" else "PUT") else j.direction.label

    private fun notify(id: Int, title: String, text: String) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }

    companion object {
        const val CHANNEL = "trades"

        fun schedule(ctx: Context) {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(NotificationChannel(CHANNEL, "Trade alerts", NotificationManager.IMPORTANCE_HIGH))
            val req = PeriodicWorkRequestBuilder<MarketWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("market", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
