package com.nmx.intradaylens.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nmx.intradaylens.data.ActionKind
import com.nmx.intradaylens.data.Actions
import com.nmx.intradaylens.data.DayStep
import com.nmx.intradaylens.data.JournalPick
import com.nmx.intradaylens.data.Repository
import com.nmx.intradaylens.data.Settings
import com.nmx.intradaylens.data.hm
import com.nmx.intradaylens.engine.Direction
import com.nmx.intradaylens.engine.IST
import com.nmx.intradaylens.engine.Options
import com.nmx.intradaylens.engine.Pick
import com.nmx.intradaylens.engine.Rules
import com.nmx.intradaylens.engine.Strategy
import com.nmx.intradaylens.engine.TradePlan
import com.nmx.intradaylens.engine.TradeStatus
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.floor

private val dayFmt = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")
private fun timeOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(IST).toLocalTime().hm()
private fun timeOfSec(s: Long) = Instant.ofEpochSecond(s).atZone(IST).toLocalTime().hm()
private fun px(x: Double) = if (x >= 1000) String.format("%,.0f", x) else String.format("%.2f", x)

// ============================ TODAY ============================
@Composable
fun TodayScreen(st: UiState, vm: MainViewModel) {
    val snap = st.snapshot
    var showAll by remember { mutableStateOf(false) }
    var nowText by remember { mutableStateOf(Repository.now().toLocalTime().hm()) }
    var step by remember { mutableStateOf(DayStep.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowText = Repository.now().toLocalTime().hm()
            step = DayStep.now()
            delay(20_000L)
        }
    }
    LazyColumn(Modifier.fillMaxWidth()) {
        item { ClockBar(step, nowText) }
        item {
            if (st.loading) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    val pr = st.progress
                    if (pr != null) LinearProgressIndicator(progress = { pr.first.toFloat() / pr.second }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if (pr != null) "Downloading prices ${pr.first}/${pr.second}… then AI check" else "Working…", color = C.Muted, fontSize = 11.sp)
                }
            } else if (snap != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    val auto = if (step == DayStep.ENTRY || step == DayStep.MANAGE || step == DayStep.EXIT || step == DayStep.OPENING)
                        "● Live • auto-updates every 30 s • " else ""
                    val asOf = snap.dataTime?.let { "prices ${timeOfSec(it + 60)}" } ?: ""
                    Text(if (snap.isToday) auto + asOf else "Showing ${snap.sessionDate.format(dayFmt)} (last session)",
                        color = C.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.refresh() }) { Text("Refresh") }
                }
            }
            st.error?.let { Banner(it, C.Gold) }
        }
        if (snap != null) {
            snap.blockReason?.let { r -> item { Banner("⛔ Skip today — $r", C.Red) } }
            snap.eventNote?.let { n -> item { Banner("⚠ $n", C.Gold) } }

            // Indices
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("NIFTY & BANK NIFTY", color = C.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    snap.indices.forEach { q ->
                        Text("${String.format("%,.0f", q.last)} ", color = C.Text, fontSize = 12.sp)
                        Text(String.format("%+.2f%%  ", q.changePct), color = if (q.changePct >= 0) C.Green else C.Red, fontSize = 12.sp)
                    }
                }
            }
            snap.indexNote?.let { n -> item { AppCard { Text(n, color = C.Muted, fontSize = 13.sp) } } }
            items(snap.indexPicks, key = { "i" + it.symbol }) { p ->
                ActionCard(p, st, snap.blockReason, snap.isToday, big = true) { vm.select(p) }
            }

            // Stocks
            val tradeable = snap.picks.filter { Actions.tradeable(it, st.settings) }.take(st.settings.topStocks)
            item { SectionTitle("TOP ${st.settings.topStocks} STOCKS") }
            if (snap.picks.isEmpty()) item {
                AppCard { Text("Stock picks appear at 9:30 AM, after the first 15 minutes.", color = C.Muted) }
            } else if (tradeable.isEmpty()) item {
                AppCard { Text("No stock has a good enough setup today. Skip stocks — that's a decision too.", color = C.Muted) }
            }
            items(tradeable, key = { "s" + it.symbol }) { p -> ActionCard(p, st, snap.blockReason, snap.isToday) { vm.select(p) } }
            item {
                TextButton(onClick = { showAll = !showAll }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(if (showAll) "Hide other stocks" else "See all ${snap.picks.size} stocks")
                }
            }
            if (showAll) items(snap.picks.filter { it !in tradeable }, key = { "a" + it.symbol }) { p ->
                ActionCard(p, st, snap.blockReason, snap.isToday, compact = true) { vm.select(p) }
            }
        }
        item {
            Text("Research tool only — not investment advice.", color = C.Muted, fontSize = 11.sp, modifier = Modifier.padding(16.dp))
        }
    }
}

/** A card with ONE big action word. */
@Composable
fun ActionCard(p: Pick, st: UiState, blockReason: String?, isToday: Boolean, big: Boolean = false, compact: Boolean = false, onClick: () -> Unit) {
    val a = Actions.actionFor(p, st.settings, blockReason, isToday)
    val col = actionColor(a.kind)
    val idx = Repository.isIndex(p.symbol)
    val opt = Options.idea(p.symbol, p.plan)
    AppCard(Modifier.clickable { onClick() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Repository.label(p.symbol), fontWeight = FontWeight.Bold, fontSize = if (big) 19.sp else 17.sp, color = C.Text, modifier = Modifier.weight(1f))
            if (a.kind != ActionKind.SKIP || p.strength.ordinal <= 1) {
                Chip(opt?.shortLabel ?: p.plan.direction.label, dirColor(p.plan.direction))
                Spacer(Modifier.width(6.dp))
            }
            Chip(p.strength.label, strengthColor(p.strength))
        }
        Text(a.title, color = col, fontWeight = FontWeight.Bold, fontSize = if (compact) 16.sp else 24.sp,
            modifier = Modifier.padding(top = 4.dp))
        Text(a.detail, color = if (a.kind == ActionKind.WAIT) C.Text else C.Muted, fontSize = 13.sp)
        val showLevels = !compact && a.kind != ActionKind.SKIP && a.kind != ActionKind.NO_ENTRY
        if (showLevels) {
            val stop = p.result.stop ?: p.plan.stop
            val target = p.result.target ?: p.plan.target
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LabelValue(if (p.plan.direction == Direction.LONG) "Entry above" else "Entry below", px(p.plan.entry), C.Gold)
                LabelValue(if (p.result.stop != null && p.result.stop != p.plan.stop && p.result.status == TradeStatus.ACTIVE && p.rule.trail) "Stop (trailing)"
                    else if (p.result.movedToCost) "Stop (moved)" else "Exit if", px(stop), C.Red)
                LabelValue("Target", px(target), C.Green)
            }
            Spacer(Modifier.height(4.dp))
            if (idx) {
                Text("SL ${String.format("%.0f", abs(p.plan.entry - p.plan.stop))} pts • Target ${String.format("%.0f", p.plan.reward)} pts" +
                    (if (p.rule.trail) " • trailing stop" else "") + " • exit by 3:15 PM",
                    color = C.Muted, fontSize = 12.sp)
            } else {
                val qty = floor(st.settings.riskRupees / p.plan.risk).toInt()
                Text("Qty for ₹${st.settings.riskRupees} risk: $qty • exit by 3:15 PM", color = C.Muted, fontSize = 12.sp)
            }
        }
        if (st.journal.any { it.date == p.date.toString() && it.symbol == p.symbol && it.taken }) {
            Spacer(Modifier.height(4.dp))
            Chip("✓ You took this trade", C.Blue)
        }
    }
}

// ============================ DETAIL ============================
@Composable
fun DetailScreen(p: Pick, st: UiState, vm: MainViewModel) {
    val ctx = LocalContext.current
    val session = remember(p) { vm.session(p) }
    val model = remember(p) { vm.model(p) }
    val snap = st.snapshot
    val a = Actions.actionFor(p, st.settings, snap?.blockReason, snap?.isToday == true)
    val idx = Repository.isIndex(p.symbol)
    val opt = Options.idea(p.symbol, p.plan)
    val taken = st.journal.any { it.date == p.date.toString() && it.symbol == p.symbol && it.taken }
    val stop = p.result.stop ?: p.plan.stop
    val target = p.result.target ?: p.plan.target
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Repository.label(p.symbol), fontWeight = FontWeight.Bold, fontSize = 24.sp, color = C.Text)
                    Text(p.date.format(dayFmt), color = C.Muted, fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Chip(p.strength.label, strengthColor(p.strength))
                    Spacer(Modifier.height(4.dp))
                    Text("AI: ${p.probability.pct0()}", color = C.Muted, fontSize = 12.sp)
                }
            }
            Text(a.title, color = actionColor(a.kind), fontWeight = FontWeight.Bold, fontSize = 28.sp, modifier = Modifier.padding(top = 6.dp))
            Text(a.detail, color = C.Text, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            if (!taken) Button(onClick = { vm.toggleTaken(p) }, modifier = Modifier.fillMaxWidth()) { Text("I took this trade") }
            else OutlinedButton(onClick = { vm.toggleTaken(p) }, modifier = Modifier.fillMaxWidth()) { Text("✓ Marked as taken (tap to undo)") }
        }

        SectionTitle("STEP BY STEP")
        AppCard {
            val buy = p.plan.direction == Direction.LONG
            val steps = listOf(
                "1. Time: after 9:30 AM and before ${Rules.ENTRY_DEADLINE.hm()}.",
                "2. Wait for a 5-minute candle to CLOSE ${if (buy) "above" else "below"} ${px(p.plan.entry)}" +
                    if (idx) " on the ${Repository.label(p.symbol)} chart." else ".",
                "3. Then " + (opt?.text?.substringBefore(" (")?.let { "buy $it".replace("buy Buy", "Buy") }
                    ?: if (buy) "BUY ${p.symbol}" else "SELL (short) ${p.symbol}") + ". The app alerts you (Buy now).",
                "4. Stop-loss: exit if ${if (idx) Repository.label(p.symbol) else "price"} reaches ${px(stop)}" +
                    if (idx) " (${String.format("%.0f", abs(p.plan.entry - p.plan.stop))} pts)." else ".",
                "5. Target: exit at ${px(target)}" + if (idx) " (${String.format("%.0f", p.plan.reward)} pts)." else ".",
                if (p.rule.trail) "6. Trailing stop: as price moves your way, move the stop-loss with it (same distance behind). Never move it back. The app tells you each new level."
                else "6. Halfway to target → move stop-loss to your entry price (no-loss). The app tells you.",
                "7. Exit everything at ${Rules.EXIT_TIME.hm()} if nothing hit."
            )
            steps.forEach { Text(it, color = C.Text, fontSize = 13.sp, modifier = Modifier.padding(vertical = 3.dp)) }
            Spacer(Modifier.height(6.dp))
            if (idx) Text("Levels are on the INDEX chart, not the option price. An at-the-money option moves about half as many points.",
                color = C.Gold, fontSize = 12.sp)
            else {
                val qty = floor(st.settings.riskRupees / p.plan.risk).toInt()
                Text("Quantity for ₹${st.settings.riskRupees} risk: $qty shares (≈ ₹${String.format("%,.0f", qty * p.plan.entry)})", color = C.Gold, fontSize = 13.sp)
            }
        }

        if (session != null) {
            SectionTitle("CHART")
            AppCard { IntradayChart(session, TradePlan(p.plan.direction, p.result.fillPrice ?: p.plan.entry, stop, target)) }
        }

        st.news?.let { n -> vm.stockEvent(n)?.let { ev -> Banner("⚠ $ev", C.Gold) } }

        SectionTitle("WHY")
        AppCard {
            p.reasons.forEach { Text("• $it", color = C.Text, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp)) }
            if (model != null) {
                Spacer(Modifier.height(8.dp))
                Text("Biggest factors", color = C.Muted, fontSize = 12.sp)
                val contrib = model.lr.contributions(p.features)
                val ranked = Strategy.FEATURE_NAMES.indices.sortedByDescending { abs(contrib[it]) }.take(5)
                val maxAbs = ranked.maxOf { abs(contrib[it]) }.coerceAtLeast(1e-6)
                ranked.forEach { ContributionBar(Strategy.FEATURE_NAMES[it], contrib[it], maxAbs) }
            }
        }

        SectionTitle("KEY LEVELS")
        AppCard {
            val l = p.levels
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LabelValue("Prev high", px(l.prevHigh)); LabelValue("Prev low", px(l.prevLow)); LabelValue("Prev close", px(l.prevClose))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LabelValue("Pivot", px(l.pivot)); LabelValue("R1", px(l.r1), C.Green); LabelValue("S1", px(l.s1), C.Red)
            }
        }

        SectionTitle("NEWS")
        AppCard {
            val news = st.news
            when {
                news == null -> Text("Loading headlines…", color = C.Muted)
                news.isEmpty() -> Text("No recent headlines.", color = C.Muted)
                else -> news.take(6).forEach { h ->
                    Column(Modifier.fillMaxWidth().clickable {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(h.link))) }
                    }.padding(vertical = 6.dp)) {
                        Text(h.title, color = C.Text, fontSize = 13.sp)
                        Text(h.source + if (h.publishedMs > 0) " • " + timeOf(h.publishedMs) else "", color = C.Muted, fontSize = 11.sp)
                    }
                    HorizontalDivider(color = C.Card2)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ============================ RESULTS ============================
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(st: UiState) {
    var mineOnly by remember { mutableStateOf(false) }
    val all = st.journal
    val j = if (mineOnly) all.filter { it.taken } else all
    val trades = j.filter { it.status.traded && it.status != TradeStatus.ACTIVE }
    val wins = trades.count { it.status == TradeStatus.WIN }
    val totalR = trades.sumOf { it.r }
    val idxPts = trades.filter { Repository.isIndex(it.symbol) && it.fill != null && it.exitPrice != null }
        .sumOf { (it.exitPrice!! - it.fill!!) * it.direction.sign }
    LazyColumn {
        item {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                FilterChip(selected = !mineOnly, onClick = { mineOnly = false }, label = { Text("All app picks") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = mineOnly, onClick = { mineOnly = true }, label = { Text("My trades") })
            }
        }
        item {
            AppCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    LabelValue("Trades", trades.size.toString())
                    LabelValue("Wins", "$wins")
                    LabelValue("Win rate", if (trades.isEmpty()) "—" else (wins.toDouble() / trades.size).pct0())
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    LabelValue("Index points", String.format("%+.0f", idxPts), if (idxPts >= 0) C.Green else C.Red)
                    LabelValue("Stocks ₹ (at ₹${st.settings.riskRupees} risk)",
                        String.format("₹%,.0f", trades.filter { !Repository.isIndex(it.symbol) }.sumOf { it.r } * st.settings.riskRupees),
                        if (totalR >= 0) C.Green else C.Red)
                }
                Spacer(Modifier.height(6.dp))
                Text("Picks are saved at 9:30 AM before the result is known. Judge after 30+ trades. Before brokerage and taxes.",
                    color = C.Muted, fontSize = 11.sp)
            }
        }
        if (j.isEmpty()) item {
            AppCard {
                Text(if (mineOnly) "Tap \"I took this trade\" on a pick to track your own trades here."
                else "Nothing yet. On market days the app saves its picks automatically at about 9:30 AM.", color = C.Muted)
            }
        }
        j.groupBy { it.date }.toSortedMap(compareByDescending { it }).forEach { (date, list) ->
            item(key = "h$date") { SectionTitle(date) }
            items(list, key = { "r" + it.key }) { e -> ResultRow(e) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ResultRow(e: JournalPick) {
    val idx = Repository.isIndex(e.symbol)
    val label = if (idx) (if (e.direction == Direction.LONG) "CALL" else "PUT") else e.direction.label
    val pts = if (e.fill != null && e.exitPrice != null) (e.exitPrice - e.fill) * e.direction.sign else null
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Repository.label(e.symbol), fontWeight = FontWeight.Bold, color = C.Text, modifier = Modifier.weight(1f))
            if (e.taken) { Chip("Mine", C.Blue); Spacer(Modifier.width(6.dp)) }
            Chip(label, dirColor(e.direction))
        }
        val res = when {
            pts != null && idx -> String.format(" %+.0f pts", pts)
            e.status.traded -> String.format(" %+.1fR", e.r)
            else -> ""
        }
        Text(e.status.label + res, color = statusColor(e.status), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text("Entry ${px(e.fill ?: e.entry)} • SL ${px(e.stop)} • Target ${px(e.target)}" +
            (e.entryTime?.let { " • in ${timeOfSec(it)}" } ?: ""), color = C.Muted, fontSize = 12.sp)
    }
}

// ============================ WATCHLIST ============================
@Composable
fun WatchlistScreen(st: UiState) {
    val watch = st.snapshot?.watch.orEmpty()
    LazyColumn {
        item {
            Text("Before 9:15 AM: stocks set up for a bigger move today, from yesterday's data. Final picks come at 9:30 AM.",
                color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
        }
        items(watch.take(20), key = { it.symbol }) { w ->
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(w.symbol, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = C.Text, modifier = Modifier.weight(1f))
                    w.bias?.let { Chip(if (it == Direction.LONG) "Bullish" else "Bearish", dirColor(it)) }
                }
                Spacer(Modifier.height(6.dp))
                if (w.notes.isEmpty()) Text("No special setup", color = C.Muted, fontSize = 12.sp)
                w.notes.forEach { Text("• $it", color = C.Text, fontSize = 13.sp) }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    LabelValue("Prev high", px(w.levels.prevHigh)); LabelValue("Prev low", px(w.levels.prevLow))
                    LabelValue("Prev close", px(w.levels.prevClose))
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ============================ AI MODEL ============================
@Composable
private fun ReportCard(title: String, r: com.nmx.intradaylens.engine.ModelReport, unitLabel: String, unitScale: Double) {
    SectionTitle(title)
    AppCard {
        Text("AI type: ${r.modelName} • tested on ${r.testFrom} → ${r.testTo} (${r.testSamples} trades it never saw)",
            color = C.Muted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LabelValue("Any breakout", r.baseWinRate.pct0())
            LabelValue("AI's picks", if (r.topTriggered == 0) "—" else r.topWinRate.pct0(), if (r.topWinRate > r.baseWinRate) C.Green else C.Red)
            LabelValue("Avg $unitLabel/trade", String.format("%+.1f", r.topAvgR * unitScale), if (r.topAvgR > 0) C.Green else C.Red)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (r.modelBeatsBaseline) "✓ The AI predicted better than guessing on unseen days."
            else "✗ On unseen days the AI did NOT beat guessing yet. Trust it less.",
            color = if (r.modelBeatsBaseline) C.Green else C.Gold, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text("When the AI said… it really hit:", color = C.Muted, fontSize = 12.sp)
        r.buckets.forEach { b ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(b.label, color = C.Text, modifier = Modifier.weight(1f), fontSize = 13.sp)
                Text("${b.count} trades", color = C.Muted, modifier = Modifier.weight(1f), fontSize = 13.sp)
                Text(b.actualWinRate.pct0(), color = if (abs(b.actualWinRate - b.avgPredicted) < 0.1) C.Green else C.Gold,
                    modifier = Modifier.weight(1f), fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun ModelScreen(st: UiState) {
    val r = st.snapshot?.modelReport
    val ir = st.snapshot?.indexReport
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        AppCard {
            Text("How the AI works", fontWeight = FontWeight.Bold, color = C.Text, fontSize = 17.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Every day the app re-learns on your phone from real prices of the last ~2 months: which confirmed breakouts reached " +
                    "their target before the stop-loss. It tries a simple model and a boosted-trees model, tests both on days " +
                    "it didn't train on, and keeps the one that predicted best. More history every day = better.",
                color = C.Muted, fontSize = 13.sp
            )
        }
        if (ir != null) ReportCard("NIFTY / BANK NIFTY AI (${st.settings.indexRiskPts}/${st.settings.indexTargetPts} pts)", ir, "pts", st.settings.indexRiskPts.toDouble())
        if (r != null) ReportCard("STOCKS AI", r, "R", 1.0)
        if (r == null && ir == null) AppCard { Text("Not trained yet. Open Today and tap Refresh.", color = C.Muted) }
        r?.let {
            Text("Last trained: ${Instant.ofEpochMilli(it.trainedAt).atZone(IST).toLocalDateTime().format(DateTimeFormatter.ofPattern("d MMM, h:mm a"))}",
                color = C.Muted, fontSize = 11.sp, modifier = Modifier.padding(16.dp))
        }
    }
}

// ============================ SETTINGS ============================
@Composable
private fun SwitchRow(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = C.Text)
            Text(sub, color = C.Muted, fontSize = 11.sp)
        }
        Switch(value, onChange)
    }
}

@Composable
fun SettingsScreen(st: UiState, vm: MainViewModel, openWatchlist: () -> Unit, openModel: () -> Unit) {
    val s = st.settings
    var risk by remember(s) { mutableStateOf(s.riskRupees.toString()) }
    var idxRisk by remember(s) { mutableStateOf(s.indexRiskPts.toString()) }
    var idxTarget by remember(s) { mutableStateOf(s.indexTargetPts.toString()) }
    var strict by remember(s) { mutableStateOf(s.strictMode) }
    var safeDay by remember(s) { mutableStateOf(s.safeDayFilter) }
    var lossLimit by remember(s) { mutableStateOf(s.dailyLossLimit.toFloat()) }
    var live by remember(s) { mutableStateOf(s.liveAlerts) }
    var notify by remember(s) { mutableStateOf(s.notifications) }
    var topStocks by remember(s) { mutableStateOf(s.topStocks.toFloat()) }
    var smartStop by remember(s) { mutableStateOf(s.smartIndexStop) }
    var universe by remember(s) { mutableStateOf(s.universe.joinToString(", ")) }
    var showAdvanced by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        SectionTitle("SAFETY")
        AppCard {
            SwitchRow("Safe-day filter", "Skip everything on wild days (high VIX, big gap)", safeDay) { safeDay = it }
            Text("Daily loss limit: ${if (lossLimit.toInt() == 0) "off" else "${lossLimit.toInt()} stop-losses"}", color = C.Text)
            Slider(lossLimit, { lossLimit = it }, valueRange = 0f..4f, steps = 3)
            SwitchRow("Strict mode", "Only \"Strong\" setups (fewer, better trades)", strict) { strict = it }
        }
        SectionTitle("ALERTS")
        AppCard {
            SwitchRow("Notifications", "Picks ready, results, weekly report", notify) { notify = it }
            SwitchRow("Live loud alerts", "Alarm sound on Buy now / Exit (checks every minute in market hours)", live) { live = it }
        }
        SectionTitle("TRADES")
        AppCard {
            OutlinedTextField(risk, { risk = it.filter(Char::isDigit) }, label = { Text("Risk per stock trade (₹)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            SwitchRow("Smart index stop-loss", "NIFTY / BANK NIFTY stop based on today's normal movement (recommended). Off = fixed points below.", smartStop) { smartStop = it }
            if (!smartStop) Row {
                OutlinedTextField(idxRisk, { idxRisk = it.filter(Char::isDigit) }, label = { Text("Index SL (pts)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(idxTarget, { idxTarget = it.filter(Char::isDigit) }, label = { Text("Index target (pts)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text("Stocks to show per day: ${topStocks.toInt()}", color = C.Text)
            Slider(topStocks, { topStocks = it }, valueRange = 1f..5f, steps = 3)
        }
        Button(onClick = {
            vm.saveSettings(
                s.copy(
                    riskRupees = risk.toIntOrNull() ?: 500, notifications = notify,
                    indexRiskPts = (idxRisk.toIntOrNull() ?: 20).coerceIn(5, 500),
                    indexTargetPts = (idxTarget.toIntOrNull() ?: 20).coerceIn(5, 1000),
                    strictMode = strict, safeDayFilter = safeDay, dailyLossLimit = lossLimit.toInt(),
                    liveAlerts = live, topStocks = topStocks.toInt(), smartIndexStop = smartStop,
                    universe = universe.split(",", "\n", " ").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
                )
            )
        }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) { Text("Save") }

        SectionTitle("MORE")
        AppCard {
            OutlinedButton(onClick = openWatchlist, modifier = Modifier.fillMaxWidth()) { Text("Pre-market watchlist") }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = openModel, modifier = Modifier.fillMaxWidth()) { Text("AI accuracy report") }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) "Hide advanced" else "Advanced…") }
            if (showAdvanced) {
                OutlinedTextField(universe, { universe = it }, label = { Text("Stocks to scan (NSE symbols, comma separated)") },
                    modifier = Modifier.fillMaxWidth(), minLines = 4)
                Text("Changing the list re-downloads data and retrains the AI. Tap Save above.", color = C.Muted, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { vm.refresh(force = true) }, modifier = Modifier.fillMaxWidth()) { Text("Re-download data & retrain AI") }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { universe = Settings.DEFAULT_UNIVERSE.joinToString(", ") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Reset stock list to default")
                }
                Spacer(Modifier.height(6.dp))
                if (!confirmClear) OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) { Text("Clear results") }
                else Button(onClick = { vm.clearJournal(); confirmClear = false }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = C.Red)) { Text("Tap again to delete all results") }
            }
        }
        AppCard {
            Text("Tips", fontWeight = FontWeight.Bold, color = C.Text)
            Text("• Open the app once after 9:15 AM each market day to start live alerts.\n" +
                "• Set battery usage for this app to \"Unrestricted\" so alarms arrive on time.\n" +
                "• Add the home-screen widget: long-press home screen → Widgets → Intraday Lens.\n" +
                "• Research tool only. Prices from Yahoo Finance (may be a few minutes delayed).",
                color = C.Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}
