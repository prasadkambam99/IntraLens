package com.nmx.intradaylens.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nmx.intradaylens.MainActivity
import com.nmx.intradaylens.R
import com.nmx.intradaylens.data.JournalPick
import com.nmx.intradaylens.data.Repository
import com.nmx.intradaylens.data.Snapshot
import com.nmx.intradaylens.data.StatusChange
import com.nmx.intradaylens.engine.Direction
import com.nmx.intradaylens.engine.Options
import com.nmx.intradaylens.engine.TradeStatus
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.temporal.IsoFields

/** All notifications: loud alarm for "Buy now" / "Exit", normal ones for everything else. */
object Alerts {
    const val CH_LOUD = "entry_alarm"
    const val CH_INFO = "trades"
    const val CH_LIVE = "live_status"

    fun createChannels(ctx: Context) {
        val mgr = ctx.getSystemService(NotificationManager::class.java)
        val loud = NotificationChannel(CH_LOUD, "Buy now / Exit alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Loud alarm when it's time to enter or exit a trade"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 900)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
        }
        val info = NotificationChannel(CH_INFO, "Trade updates", NotificationManager.IMPORTANCE_DEFAULT)
        val live = NotificationChannel(CH_LIVE, "Live tracking (silent)", NotificationManager.IMPORTANCE_MIN)
        mgr.createNotificationChannels(listOf(loud, info, live))
    }

    private fun name(j: JournalPick): String {
        if (!Repository.isIndex(j.symbol)) return j.symbol
        return Options.idea(j.symbol, j.plan)?.text?.substringBefore(" (")?.removePrefix("Buy ") ?: Repository.label(j.symbol)
    }

    private fun fmt(x: Double) = if (x >= 1000) String.format("%,.0f", x) else String.format("%.2f", x)

    fun process(ctx: Context, snap: Snapshot, changes: List<StatusChange>) {
        val repo = Repository.get(ctx)
        val s = repo.store.settings()
        if (!s.notifications) return
        val now = Repository.now()
        val today = now.toLocalDate().toString()
        val t = now.toLocalTime()
        val todays = repo.store.journal().filter { it.date == today }
        val limitHit = s.dailyLossLimit in 1..repo.lossesToday()

        // Wild day
        if (snap.isToday && snap.blockReason != null && !limitHit && repo.store.flag("wildDay") != today && !t.isBefore(LocalTime.of(9, 30))) {
            repo.store.setFlag("wildDay", today)
            notify(ctx, 2, CH_INFO, "Skip today", snap.blockReason)
        }
        // Picks ready
        if (todays.isNotEmpty() && repo.store.flag("readyDay") != today) {
            repo.store.setFlag("readyDay", today)
            notify(ctx, 1, CH_INFO, "Today's picks are ready",
                todays.joinToString("  •  ") { name(it) + " " + if (Repository.isIndex(it.symbol)) "" else it.direction.label })
        }
        // Trade events
        changes.filter { it.old != null && it.pick.date == today }.forEach { c ->
            val p = c.pick
            val id = p.symbol.hashCode()
            if (c.old?.status == TradeStatus.WAITING && p.status == TradeStatus.ACTIVE) {
                if (limitHit) {
                    notify(ctx, id, CH_INFO, "${name(p)}: entry signal — SKIP", "Daily loss limit reached. Don't take new trades today.")
                } else {
                    val verb = if (Repository.isIndex(p.symbol) || p.direction == Direction.LONG) "BUY NOW" else "SELL NOW"
                    notify(ctx, id, CH_LOUD, "$verb: ${name(p)}",
                        "Entry ${fmt(p.fill ?: p.entry)} • Exit if ${fmt(p.stop)} • Target ${fmt(p.target)}", loud = true)
                }
            } else if (c.old?.movedToCost == false && p.movedToCost && p.status == TradeStatus.ACTIVE) {
                notify(ctx, id, CH_INFO, "${name(p)}: move stop-loss to entry", "Set stop-loss to ${fmt(p.fill ?: p.entry)}. Now this trade can't lose.")
            } else if (p.status != c.old?.status) {
                val msg = when (p.status) {
                    TradeStatus.WIN -> "Target ${fmt(p.target)} reached ✅"
                    TradeStatus.LOSS -> "Stop-loss ${fmt(p.stop)} hit ❌"
                    TradeStatus.BREAKEVEN -> "Exited at entry price (no loss)"
                    TradeStatus.TIME_EXIT -> "Closed at 3:15 PM"
                    TradeStatus.NOT_TRIGGERED -> null
                    else -> null
                } ?: return@forEach
                notify(ctx, id, CH_INFO, "${name(p)}: ${p.status.label}", msg)
            }
        }
        // Daily loss limit
        if (limitHit && repo.store.flag("limitDay") != today) {
            repo.store.setFlag("limitDay", today)
            notify(ctx, 3, CH_LOUD, "Stop trading today", "${repo.lossesToday()} stop-losses today. Protect your money — no more trades.", loud = true)
        }
        // 3:10 PM exit reminder
        if (!t.isBefore(LocalTime.of(15, 10)) && t.isBefore(LocalTime.of(15, 30)) &&
            todays.any { it.status == TradeStatus.ACTIVE } && repo.store.flag("exitDay") != today
        ) {
            repo.store.setFlag("exitDay", today)
            notify(ctx, 4, CH_LOUD, "Exit all trades by 3:15 PM", "Close every open intraday position now.", loud = true)
        }
        weeklyReport(ctx)
    }

    /** Friday after the close: one summary of the week. */
    fun weeklyReport(ctx: Context) {
        val repo = Repository.get(ctx)
        val now = Repository.now()
        if (now.dayOfWeek != DayOfWeek.FRIDAY || now.toLocalTime().isBefore(LocalTime.of(15, 35))) return
        val week = "${now.get(IsoFields.WEEK_BASED_YEAR)}-${now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"
        if (repo.store.flag("week") == week) return
        val monday = now.toLocalDate().minusDays(4)
        val j = repo.store.journal().filter { java.time.LocalDate.parse(it.date) >= monday }
        val traded = j.filter { it.status.traded && it.status != TradeStatus.ACTIVE }
        if (j.isEmpty()) return
        repo.store.setFlag("week", week)
        val wins = traded.count { it.status == TradeStatus.WIN }
        val totalR = traded.sumOf { it.r }
        val idxPts = traded.filter { Repository.isIndex(it.symbol) && it.fill != null && it.exitPrice != null }
            .sumOf { (it.exitPrice!! - it.fill!!) * it.direction.sign }
        notify(ctx, 5, CH_INFO, "Weekly report card",
            "${j.size} picks, ${traded.size} trades, $wins wins (${if (traded.isEmpty()) 0 else wins * 100 / traded.size}%). " +
                "Result ${String.format("%+.1fR", totalR)}, index ${String.format("%+.0f", idxPts)} pts.")
    }

    fun notify(ctx: Context, id: Int, channel: String, title: String, text: String, loud: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
        if (loud) b.setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_ALARM)
        NotificationManagerCompat.from(ctx).notify(id, b.build())
    }
}
