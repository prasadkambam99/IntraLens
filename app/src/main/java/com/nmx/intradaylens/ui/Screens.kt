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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.nmx.intradaylens.data.Phase
import com.nmx.intradaylens.data.Settings
import com.nmx.intradaylens.data.hm
import com.nmx.intradaylens.engine.Direction
import com.nmx.intradaylens.engine.IST
import com.nmx.intradaylens.engine.Pick
import com.nmx.intradaylens.engine.Rules
import com.nmx.intradaylens.engine.Strategy
import com.nmx.intradaylens.engine.TradeStatus
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.floor

private val dayFmt = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")
private fun timeOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(IST).toLocalTime().hm()
private fun timeOfSec(s: Long) = Instant.ofEpochSecond(s).atZone(IST).toLocalTime().hm()

// ============================ TODAY ============================
@Composable
fun TodayScreen(st: UiState, vm: MainViewModel) {
    val snap = st.snapshot
    var showAll by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(snap?.sessionDate?.format(dayFmt) ?: "Loading…", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = C.Text)
                        val phase = snap?.phase
                        val sub = when {
                            snap == null -> "Downloading prices"
                            !snap.isToday -> "Showing last session (replay) • ${com.nmx.intradaylens.data.Repository.phaseNow().label}"
                            else -> phase?.label ?: ""
                        }
                        Text(sub, color = C.Muted, fontSize = 12.sp)
                    }
                    snap?.nifty?.let { n ->
                        Column(horizontalAlignment = Alignment.End) {
                            Text(n.name, color = C.Muted, fontSize = 11.sp)
                            Text(String.format("%,.0f", n.last), color = C.Text, fontWeight = FontWeight.Bold)
                            Text(String.format("%+.2f%%", n.changePct), color = if (n.changePct >= 0) C.Green else C.Red, fontSize = 12.sp)
                        }
                    }
                }
                if (st.loading) {
                    Spacer(Modifier.height(10.dp))
                    val pr = st.progress
                    if (pr != null) LinearProgressIndicator(progress = { pr.first.toFloat() / pr.second }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if (pr != null) "Prices ${pr.first}/${pr.second}… then AI training" else "Working…", color = C.Muted, fontSize = 11.sp)
                } else if (snap != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val asOf = snap.dataTime?.let { " • Prices as of ${timeOfSec(it + 300)}" } ?: ""
                        Text("Updated ${timeOf(snap.updatedAt)}$asOf", color = C.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.refresh() }) { Text("Refresh") }
                    }
                }
                st.error?.let { Text(it, color = C.Gold, fontSize = 12.sp) }
            }
        }
        item {
            Text(
                "Research only. Picks are a 15-min opening-range breakout; the % is the AI's estimate that the target is hit before the stop-loss, once entry triggers. Break-even is 40%.",
                color = C.Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        if (snap != null) {
            val strong = snap.picks.filter { it.probability >= st.settings.minProb }.take(st.settings.topN)
            // Never show an empty list: if nothing passes the filter, show the best 5 with a clear warning.
            val top = strong.ifEmpty { snap.picks.take(5) }
            val weakOnly = strong.isEmpty() && top.isNotEmpty()
            item {
                SectionTitle(
                    when {
                        weakOnly -> "BEST OF THE SCAN (BELOW YOUR ${st.settings.minProb.pct0()} MINIMUM)"
                        snap.isToday -> "TODAY'S PICKS (${top.size})"
                        else -> "PICKS FOR ${snap.sessionDate.format(dayFmt).uppercase()}"
                    }
                )
            }
            if (top.isEmpty()) item {
                AppCard { Text("No scan yet. Picks appear at 9:30 AM once the first 15 minutes of trading are done.", color = C.Muted) }
            }
            if (weakOnly) item {
                AppCard {
                    Text("No stock reached your minimum confidence. These are the strongest ones anyway — lower conviction, consider sitting out.",
                        color = C.Gold, fontSize = 13.sp)
                }
            }
            items(top, key = { "t" + it.symbol }) { PickCard(it, st.settings.riskRupees, minProb = st.settings.minProb) { vm.select(it) } }
            item {
                TextButton(onClick = { showAll = !showAll }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(if (showAll) "Hide other stocks" else "Show all ${snap.picks.size} scanned stocks")
                }
            }
            if (showAll) items(snap.picks.drop(top.size), key = { "a" + it.symbol }) { PickCard(it, st.settings.riskRupees, compact = true, minProb = st.settings.minProb) { vm.select(it) } }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun PickCard(p: Pick, risk: Int, compact: Boolean = false, minProb: Double = 0.0, onClick: () -> Unit) {
    AppCard(Modifier.clickable { onClick() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.symbol, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = C.Text)
                    Spacer(Modifier.width(8.dp))
                    Chip(p.plan.direction.label, dirColor(p.plan.direction))
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    Chip(p.result.status.label + if (p.result.status == TradeStatus.ACTIVE || p.result.status == TradeStatus.WIN ||
                        p.result.status == TradeStatus.LOSS || p.result.status == TradeStatus.TIME_EXIT) String.format(" (%+.1fR)", p.result.r) else "",
                        statusColor(p.result.status))
                    if (p.probability < 0.40) { Spacer(Modifier.width(6.dp)); Chip("Below break-even", C.Red) }
                    else if (p.probability < minProb) { Spacer(Modifier.width(6.dp)); Chip("Low confidence", C.Gold) }
                }
            }
            ProbRing(p.probability, if (compact) 44 else 58)
        }
        if (!compact) {
            Spacer(Modifier.height(10.dp))
            PlanRow(p.plan)
            Spacer(Modifier.height(6.dp))
            val qty = floor(risk / p.plan.risk).toInt()
            Text("Risk ${String.format("%.2f", p.plan.riskPct)}% • Reward ${String.format("%.2f", p.plan.rewardPct)}% • Qty for ₹$risk risk: $qty",
                color = C.Muted, fontSize = 12.sp)
            p.result.lastPrice?.let { Text("Last price: ${it.p2()}", color = C.Muted, fontSize = 12.sp) }
        }
    }
}

// ============================ DETAIL ============================
@Composable
fun DetailScreen(p: Pick, st: UiState, vm: MainViewModel) {
    val ctx = LocalContext.current
    val session = remember(p) { vm.session(p) }
    val model = remember { vm.model() }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.symbol, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = C.Text)
                        Spacer(Modifier.width(8.dp))
                        Chip(p.plan.direction.label, dirColor(p.plan.direction))
                    }
                    Text(p.date.format(dayFmt), color = C.Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Chip(p.result.status.label, statusColor(p.result.status))
                }
                ProbRing(p.probability, 72)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "If the entry triggers, the AI estimates a ${p.probability.pct0()} chance of reaching the target before the stop-loss. " +
                    "Expected result: ${String.format("%+.2f", p.expectedR)}R per trade.",
                color = C.Muted, fontSize = 12.sp
            )
        }

        SectionTitle("TRADE PLAN")
        AppCard {
            PlanRow(p.plan)
            Spacer(Modifier.height(10.dp))
            val buy = p.plan.direction == Direction.LONG
            val steps = listOf(
                "When: after 9:30 AM, only until ${Rules.ENTRY_DEADLINE.hm()}.",
                if (buy) "Entry: BUY when price goes above ${p.plan.entry.p2()} (top of first 15 minutes)."
                else "Entry: SELL (short) when price goes below ${p.plan.entry.p2()} (bottom of first 15 minutes).",
                "Stop-loss: exit at ${p.plan.stop.p2()} (${String.format("%.2f", p.plan.riskPct)}% risk).",
                "Target: exit at ${p.plan.target.p2()} (${String.format("%.2f", p.plan.rewardPct)}% = 1.5× risk).",
                "Exit anyway at ${Rules.EXIT_TIME.hm()} if neither is hit."
            )
            steps.forEach { Text("• $it", color = C.Text, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp)) }
            Spacer(Modifier.height(8.dp))
            val qty = floor(st.settings.riskRupees / p.plan.risk).toInt()
            Text("Position size for ₹${st.settings.riskRupees} risk: $qty shares (≈ ₹${String.format("%,.0f", qty * p.plan.entry)} capital)",
                color = C.Gold, fontSize = 13.sp)
            p.result.entryTime?.let {
                Spacer(Modifier.height(6.dp))
                Text("Entry triggered at ${timeOfSec(it)} @ ${p.result.fillPrice?.p2()}", color = C.Muted, fontSize = 12.sp)
            }
            p.result.exitTime?.let {
                Text("Exit at ${timeOfSec(it)} @ ${p.result.exitPrice?.p2()} → ${String.format("%+.2f", p.result.r)}R", color = C.Muted, fontSize = 12.sp)
            }
        }

        if (session != null) {
            SectionTitle("TODAY'S 5-MIN CHART")
            AppCard { IntradayChart(session, p.plan) }
        }

        SectionTitle("WHY THIS STOCK")
        AppCard { p.reasons.forEach { Text("• $it", color = C.Text, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp)) } }

        if (model != null) {
            SectionTitle("WHAT MOVED THE AI'S %")
            AppCard {
                val contrib = model.contributions(p.features)
                val ranked = Strategy.FEATURE_NAMES.indices.sortedByDescending { abs(contrib[it]) }.take(6)
                val maxAbs = ranked.maxOf { abs(contrib[it]) }.coerceAtLeast(1e-6)
                ranked.forEach { ContributionBar(Strategy.FEATURE_NAMES[it], contrib[it], maxAbs) }
            }
        }

        SectionTitle("KEY LEVELS")
        AppCard {
            val l = p.levels
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LabelValue("Prev high", l.prevHigh.p2()); LabelValue("Prev low", l.prevLow.p2()); LabelValue("Prev close", l.prevClose.p2())
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LabelValue("Pivot", l.pivot.p2()); LabelValue("R1", l.r1.p2(), C.Green); LabelValue("S1", l.s1.p2(), C.Red)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LabelValue("ATR (daily)", String.format("%.2f%%", l.atrPct)); LabelValue("RSI 14", String.format("%.0f", l.rsi)); LabelValue("EMA 20", l.ema20.p2())
            }
        }

        SectionTitle("NEWS (LAST 2 DAYS)")
        AppCard {
            val news = st.news
            when {
                news == null -> Text("Loading headlines…", color = C.Muted)
                news.isEmpty() -> Text("No recent headlines found.", color = C.Muted)
                else -> {
                    vm.newsTone(news)?.let { t ->
                        Text("Headline tone: " + when { t > 0.2 -> "positive"; t < -0.2 -> "negative"; else -> "mixed" },
                            color = if (t > 0.2) C.Green else if (t < -0.2) C.Red else C.Gold, fontWeight = FontWeight.SemiBold)
                        Text("(For information — not used in the %.)", color = C.Muted, fontSize = 11.sp)
                        Spacer(Modifier.height(6.dp))
                    }
                    news.take(8).forEach { h ->
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
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ============================ WATCHLIST ============================
@Composable
fun WatchlistScreen(st: UiState) {
    val watch = st.snapshot?.watch.orEmpty()
    LazyColumn {
        item {
            Text("Pre-market watchlist: stocks set up for a bigger move today, from yesterday's data. Final picks come at 9:30 AM.",
                color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
        }
        items(watch.take(20), key = { it.symbol }) { w ->
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(w.symbol, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = C.Text, modifier = Modifier.weight(1f))
                    w.bias?.let { Chip("Bias: " + if (it == Direction.LONG) "Bullish" else "Bearish", dirColor(it)) }
                    Spacer(Modifier.width(6.dp))
                    Chip("Score ${String.format("%.1f", w.score)}", C.Gold)
                }
                Spacer(Modifier.height(6.dp))
                if (w.notes.isEmpty()) Text("No special setup", color = C.Muted, fontSize = 12.sp)
                w.notes.forEach { Text("• $it", color = C.Text, fontSize = 13.sp) }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    LabelValue("Prev high", w.levels.prevHigh.p2()); LabelValue("Prev low", w.levels.prevLow.p2())
                    LabelValue("Prev close", w.levels.prevClose.p2())
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ============================ JOURNAL ============================
@Composable
fun JournalScreen(st: UiState) {
    val j = st.journal
    val closed = j.filter { it.closed }
    val trig = closed.filter { it.status != TradeStatus.NOT_TRIGGERED }
    val wins = trig.count { it.status == TradeStatus.WIN }
    val totalR = trig.sumOf { it.r }
    LazyColumn {
        item {
            AppCard {
                Text("Paper-trading track record", fontWeight = FontWeight.Bold, color = C.Text, fontSize = 17.sp)
                Text("Every pick is saved at 9:30 AM before the result is known, then checked against real prices.",
                    color = C.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    LabelValue("Days", j.map { it.date }.distinct().size.toString())
                    LabelValue("Picks", j.size.toString())
                    LabelValue("Triggered", trig.size.toString())
                    LabelValue("Win rate", if (trig.isEmpty()) "—" else (wins.toDouble() / trig.size).pct0(),
                        if (trig.isNotEmpty() && wins.toDouble() / trig.size >= 0.4) C.Green else C.Red)
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    LabelValue("Total", String.format("%+.1fR", totalR), if (totalR >= 0) C.Green else C.Red)
                    LabelValue("Avg / trade", if (trig.isEmpty()) "—" else String.format("%+.2fR", totalR / trig.size))
                    LabelValue("At ₹${st.settings.riskRupees}/trade", String.format("₹%,.0f", totalR * st.settings.riskRupees),
                        if (totalR >= 0) C.Green else C.Red)
                }
                Spacer(Modifier.height(6.dp))
                Text("Before brokerage, taxes and slippage. Judge the app only after 30+ triggered trades.", color = C.Muted, fontSize = 11.sp)
            }
        }
        if (j.isEmpty()) item {
            AppCard { Text("No picks recorded yet. Keep the app installed: on market days it records the picks automatically at about 9:30 AM.", color = C.Muted) }
        }
        j.groupBy { it.date }.toSortedMap(compareByDescending { it }).forEach { (date, list) ->
            item(key = "h$date") {
                val r = list.filter { it.closed }.sumOf { it.r }
                SectionTitle("$date   ${String.format("%+.1fR", r)}")
            }
            items(list, key = { it.date + it.symbol }) { e ->
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.symbol, fontWeight = FontWeight.Bold, color = C.Text, modifier = Modifier.weight(1f))
                        Chip(e.direction.label, dirColor(e.direction))
                        Spacer(Modifier.width(6.dp))
                        Chip(e.probability.pct0(), C.Gold)
                        Spacer(Modifier.width(6.dp))
                        Chip(e.status.label + if (e.status != TradeStatus.WAITING && e.status != TradeStatus.NOT_TRIGGERED) String.format(" %+.1fR", e.r) else "",
                            statusColor(e.status))
                    }
                    Text("Entry ${e.entry.p2()} • SL ${e.stop.p2()} • Target ${e.target.p2()}", color = C.Muted, fontSize = 12.sp)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ============================ AI MODEL ============================
@Composable
fun ModelScreen(st: UiState) {
    val r = st.snapshot?.modelReport
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        AppCard {
            Text("How the AI works", fontWeight = FontWeight.Bold, color = C.Text, fontSize = 17.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "A small machine-learning model (logistic regression) runs fully on your phone. Every day it re-learns from the last " +
                    "~2 months of real 5-minute prices of every stock in your list: for each past day it checks whether the opening-range " +
                    "breakout reached its target before its stop-loss, and learns which conditions (gap, volume, trend, RSI, NIFTY…) made that more likely. " +
                    "It keeps more history every day you use the app.",
                color = C.Muted, fontSize = 13.sp
            )
        }
        if (r == null) {
            AppCard { Text("Not trained yet — needs at least 21 days of data. Refresh on Today.", color = C.Muted) }
            return@Column
        }
        SectionTitle("HONEST TEST (data the AI did not train on)")
        AppCard {
            Text("Trained on older days, then tested on ${r.testFrom} → ${r.testTo}.", color = C.Muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LabelValue("Stocks", r.stocks.toString()); LabelValue("Days", r.days.toString())
                LabelValue("Train trades", r.trainSamples.toString()); LabelValue("Test trades", r.testSamples.toString())
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LabelValue("Any breakout", r.baseWinRate.pct0())
                LabelValue("AI top picks", if (r.topTriggered == 0) "—" else r.topWinRate.pct0(), if (r.topWinRate > r.baseWinRate) C.Green else C.Red)
                LabelValue("Avg R (all)", String.format("%+.2f", r.allAvgR))
                LabelValue("Avg R (AI)", String.format("%+.2f", r.topAvgR), if (r.topAvgR > 0) C.Green else C.Red)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (r.modelBeatsBaseline) "✓ The AI predicted better than guessing on unseen days."
                else "✗ On unseen days the AI did NOT beat simple guessing. Treat its % with extra caution.",
                color = if (r.modelBeatsBaseline) C.Green else C.Gold, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
            )
        }
        SectionTitle("IS THE % TRUSTWORTHY? (calibration)")
        AppCard {
            Text("When the AI said X%, how often did the target really hit?", color = C.Muted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("AI said", color = C.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("Trades", color = C.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("Really hit", color = C.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            }
            r.buckets.forEach { b ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(b.label, color = C.Text, modifier = Modifier.weight(1f))
                    Text(b.count.toString(), color = C.Text, modifier = Modifier.weight(1f))
                    Text(b.actualWinRate.pct0(), color = if (abs(b.actualWinRate - b.avgPredicted) < 0.1) C.Green else C.Gold, modifier = Modifier.weight(1f))
                }
            }
        }
        SectionTitle("WHAT THE AI LEARNED")
        AppCard {
            val maxAbs = r.featureWeights.maxOf { abs(it.second) }.coerceAtLeast(1e-6)
            r.featureWeights.sortedByDescending { abs(it.second) }.forEach { (n, w) -> ContributionBar(n, w, maxAbs) }
            Text("(Direction-adjusted: e.g. \"Trend\" raising % means trading with the trend helped.)", color = C.Muted, fontSize = 11.sp)
        }
        Text("Last trained: ${Instant.ofEpochMilli(r.trainedAt).atZone(IST).toLocalDateTime().format(DateTimeFormatter.ofPattern("d MMM, h:mm a"))}",
            color = C.Muted, fontSize = 11.sp, modifier = Modifier.padding(16.dp))
    }
}

// ============================ SETTINGS ============================
@Composable
fun SettingsScreen(st: UiState, vm: MainViewModel) {
    val s = st.settings
    var risk by remember(s) { mutableStateOf(s.riskRupees.toString()) }
    var minProb by remember(s) { mutableStateOf(s.minProb.toFloat()) }
    var topN by remember(s) { mutableStateOf(s.topN.toFloat()) }
    var notify by remember(s) { mutableStateOf(s.notifications) }
    var universe by remember(s) { mutableStateOf(s.universe.joinToString(", ")) }
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        AppCard {
            OutlinedTextField(risk, { risk = it.filter(Char::isDigit) }, label = { Text("Risk per trade (₹) — for position size") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Text("Minimum AI confidence to show a pick: ${(minProb * 100).toInt()}%", color = C.Text)
            Slider(minProb, { minProb = it }, valueRange = 0.40f..0.80f, steps = 7)
            Text("Max picks per day: ${topN.toInt()}", color = C.Text)
            Slider(topN, { topN = it }, valueRange = 3f..15f, steps = 11)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Alerts (picks ready, entry, target, stop-loss)", color = C.Text, modifier = Modifier.weight(1f))
                Switch(notify, { notify = it })
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(universe, { universe = it }, label = { Text("Stocks to scan (NSE symbols, comma separated)") },
                modifier = Modifier.fillMaxWidth(), minLines = 4)
            Text("Changing the list re-downloads 60 days and retrains the AI.", color = C.Muted, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            Button(onClick = {
                vm.saveSettings(
                    Settings(
                        universe = universe.split(",", "\n", " ").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct(),
                        topN = topN.toInt(), minProb = (Math.round(minProb * 100) / 100.0),
                        riskRupees = risk.toIntOrNull() ?: 500, notifications = notify
                    )
                )
            }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
        AppCard {
            OutlinedButton(onClick = { vm.refresh(force = true) }, modifier = Modifier.fillMaxWidth()) { Text("Re-download data & retrain AI now") }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { vm.saveSettings(s.copy(universe = Settings.DEFAULT_UNIVERSE)) }, modifier = Modifier.fillMaxWidth()) {
                Text("Reset stock list to default")
            }
            Spacer(Modifier.height(6.dp))
            if (!confirmClear) OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) { Text("Clear journal") }
            else Button(onClick = { vm.clearJournal(); confirmClear = false }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = C.Red)) { Text("Tap again to delete all journal entries") }
        }
        AppCard {
            Text("About", fontWeight = FontWeight.Bold, color = C.Text)
            Text(
                "Personal research tool. Not investment advice. Prices: Yahoo Finance (free, may be delayed a few minutes). " +
                    "News: Google News. Everything is calculated on this phone; no account or broker is connected.",
                color = C.Muted, fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
