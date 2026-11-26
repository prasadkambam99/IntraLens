package com.nmx.intradaylens.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.nmx.intradaylens.MainActivity
import com.nmx.intradaylens.R
import com.nmx.intradaylens.data.ActionKind
import com.nmx.intradaylens.data.Actions
import com.nmx.intradaylens.data.DayStep
import com.nmx.intradaylens.data.Repository
import com.nmx.intradaylens.data.Settings
import com.nmx.intradaylens.data.Snapshot
import com.nmx.intradaylens.data.hm

/** Home-screen widget: NIFTY and BANK NIFTY action word at a glance. */
class IndexWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val repo = Repository.get(context)
        val snap = repo.lastSnapshot
        render(context, manager, ids, snap, repo.store.settings())
    }

    companion object {
        private val GREEN = 0xFF26A69A.toInt()
        private val RED = 0xFFEF5350.toInt()
        private val GOLD = 0xFFFFC94D.toInt()
        private val MUTED = 0xFF8B98A5.toInt()

        fun update(ctx: Context, snap: Snapshot, settings: Settings) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, IndexWidget::class.java))
            if (ids.isNotEmpty()) render(ctx, mgr, ids, snap, settings)
        }

        private fun color(k: ActionKind) = when (k) {
            ActionKind.BUY_NOW, ActionKind.DONE_WIN, ActionKind.HOLD -> GREEN
            ActionKind.EXIT_NOW, ActionKind.DONE_LOSS -> RED
            ActionKind.WAIT -> GOLD
            else -> MUTED
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray, snap: Snapshot?, settings: Settings) {
            val views = RemoteViews(ctx.packageName, R.layout.widget_index)
            val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            views.setTextViewText(R.id.widget_step, DayStep.now().label + " • " + Repository.now().toLocalTime().hm())
            val rows = listOf(R.id.widget_line1 to "NIFTY", R.id.widget_line2 to "BANKNIFTY")
            rows.forEach { (viewId, sym) ->
                val pick = snap?.indexPicks?.firstOrNull { it.symbol == sym }
                if (pick == null || snap == null) {
                    views.setTextViewText(viewId, "${Repository.label(sym)}: open app")
                    views.setTextColor(viewId, MUTED)
                } else {
                    val a = Actions.actionFor(pick, settings, snap.blockReason, snap.isToday)
                    views.setTextViewText(viewId, "${Repository.label(sym)}: ${a.title}")
                    views.setTextColor(viewId, color(a.kind))
                }
            }
            mgr.updateAppWidget(ids, views)
        }
    }
}
