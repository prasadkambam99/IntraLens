package com.nmx.intradaylens.work

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.nmx.intradaylens.MainActivity
import com.nmx.intradaylens.R
import com.nmx.intradaylens.data.DayStep
import com.nmx.intradaylens.data.RefreshMode
import com.nmx.intradaylens.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs only during market hours (9:15–3:30) when "Live alerts" is on.
 * Checks prices about every minute so "Buy now" alarms arrive on time, then stops itself.
 */
class LiveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Alerts.createChannels(this)
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, Alerts.CH_LIVE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Intraday Lens is watching the market")
            .setContentText("Alerts on until 3:30 PM")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, 77, n, type)
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_STICKY
    }

    private suspend fun run() {
        val repo = Repository.get(applicationContext)
        while (scope.isActive) {
            val step = DayStep.now()
            if (step == DayStep.CLOSED || (step == DayStep.BEFORE && Repository.now().hour < 9)) break
            if (step != DayStep.OPENING && step != DayStep.BEFORE) {
                runCatching {
                    val (snap, changes) = repo.refresh(mode = RefreshMode.LIVE)
                    Alerts.process(applicationContext, snap, changes)
                }
            }
            delay(60_000L)
        }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Start if it's a market day, during market hours, and live alerts are on. */
        fun startIfNeeded(ctx: Context) {
            val repo = Repository.get(ctx)
            if (!repo.store.settings().liveAlerts) return
            val step = DayStep.now()
            if (step == DayStep.CLOSED || (step == DayStep.BEFORE && Repository.now().hour < 9)) return
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, LiveService::class.java))
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, LiveService::class.java))
        }
    }
}
