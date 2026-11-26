package com.nmx.intradaylens.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nmx.intradaylens.data.DayStep
import com.nmx.intradaylens.data.RefreshMode
import com.nmx.intradaylens.data.Repository
import java.util.concurrent.TimeUnit

/**
 * Backup check every 15 minutes (in case the live service was stopped by the phone).
 * Also sends the Friday weekly report.
 */
class MarketWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val step = DayStep.now()
        if (step == DayStep.CLOSED) { Alerts.weeklyReport(applicationContext); return Result.success() }
        if (step == DayStep.BEFORE || step == DayStep.OPENING) return Result.success()
        return try {
            val (snap, changes) = Repository.get(applicationContext).refresh(mode = RefreshMode.LIVE)
            Alerts.process(applicationContext, snap, changes)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun schedule(ctx: Context) {
            Alerts.createChannels(ctx)
            val req = PeriodicWorkRequestBuilder<MarketWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("market", ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
