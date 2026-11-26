package com.nmx.intradaylens.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nmx.intradaylens.engine.Direction
import com.nmx.intradaylens.engine.Indicators
import com.nmx.intradaylens.engine.Session
import com.nmx.intradaylens.engine.TradePlan
import com.nmx.intradaylens.engine.TradeStatus

object C {
    val Bg = Color(0xFF0B0F14)
    val Card = Color(0xFF141B24)
    val Card2 = Color(0xFF1B2430)
    val Text = Color(0xFFE6EDF3)
    val Muted = Color(0xFF8B98A5)
    val Green = Color(0xFF26A69A)
    val Red = Color(0xFFEF5350)
    val Gold = Color(0xFFFFC94D)
    val Blue = Color(0xFF4FA3FF)
}

val AppColors = darkColorScheme(
    primary = C.Gold, onPrimary = Color.Black,
    secondary = C.Blue, background = C.Bg, surface = C.Card,
    onBackground = C.Text, onSurface = C.Text, surfaceVariant = C.Card2, onSurfaceVariant = C.Muted
)

fun Double.p2() = String.format("%.2f", this)
fun Double.pct0() = String.format("%.0f%%", this * 100)

fun dirColor(d: Direction) = if (d == Direction.LONG) C.Green else C.Red

fun statusColor(s: TradeStatus) = when (s) {
    TradeStatus.WIN, TradeStatus.TRAIL_EXIT -> C.Green
    TradeStatus.LOSS -> C.Red
    TradeStatus.ACTIVE -> C.Blue
    TradeStatus.TIME_EXIT -> C.Gold
    TradeStatus.BREAKEVEN -> C.Blue
    else -> C.Muted
}

@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = C.Card),
        shape = RoundedCornerShape(16.dp)
    ) { Column(Modifier.padding(14.dp)) { content() } }
}

@Composable
fun Chip(text: String, color: Color, filled: Boolean = true) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (filled) color.copy(alpha = 0.18f) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) { Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, color = C.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp))
}

/** Circular probability meter. */
@Composable
fun ProbRing(p: Double, size: Int = 56) {
    val color = when {
        p >= 0.65 -> C.Green
        p >= 0.5 -> C.Gold
        else -> C.Muted
    }
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size.dp)) {
            val stroke = 6.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(C.Card2, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(color, -90f, (360 * p).toFloat(), false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        }
        Text(p.pct0(), color = C.Text, fontWeight = FontWeight.Bold, fontSize = (size / 4).sp)
    }
}

@Composable
fun LabelValue(label: String, value: String, color: Color = C.Text, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = C.Muted, fontSize = 11.sp)
        Text(value, color = color, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
fun PlanRow(plan: TradePlan) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        LabelValue("Entry above/below", plan.entry.p2(), C.Gold)
        LabelValue("Stop-loss", plan.stop.p2(), C.Red)
        LabelValue("Target", plan.target.p2(), C.Green)
    }
}

/** 5-minute candle chart with entry / stop / target lines and VWAP. */
@Composable
fun IntradayChart(session: Session, plan: TradePlan, modifier: Modifier = Modifier) {
    val bars = session.bars
    if (bars.isEmpty()) return
    Canvas(modifier.fillMaxWidth().height(220.dp)) {
        val hi = maxOf(bars.maxOf { it.h }, plan.target, plan.stop, plan.entry)
        val lo = minOf(bars.minOf { it.l }, plan.target, plan.stop, plan.entry)
        val pad = (hi - lo) * 0.05
        val top = hi + pad; val bottom = lo - pad
        fun y(v: Double) = ((top - v) / (top - bottom) * size.height).toFloat()
        val slots = (375 * 60 / session.barSeconds).toInt().coerceAtLeast(bars.size)
        val w = size.width / slots
        // Opening range shading
        val orBars = session.orBars.ifEmpty { bars.take(1) }
        drawRect(C.Gold.copy(alpha = 0.08f), Offset(0f, y(orBars.maxOf { it.h })),
            Size(w * orBars.size, y(orBars.minOf { it.l }) - y(orBars.maxOf { it.h })))
        bars.forEachIndexed { i, b ->
            val x = i * w + w / 2
            val col = if (b.c >= b.o) C.Green else C.Red
            drawLine(col, Offset(x, y(b.h)), Offset(x, y(b.l)), strokeWidth = 1.5f)
            val bodyTop = y(maxOf(b.o, b.c)); val bodyBot = y(minOf(b.o, b.c))
            drawRect(col, Offset(x - w * 0.35f, bodyTop), Size(w * 0.7f, maxOf(1.5f, bodyBot - bodyTop)))
        }
        // VWAP
        val path = Path()
        var pv = 0.0; var vol = 0.0
        bars.indices.forEach { i ->
            val b = bars[i]; val tp = (b.h + b.l + b.c) / 3
            pv += tp * maxOf(b.v, 1L); vol += maxOf(b.v, 1L)
            val v = pv / vol
            val pt = Offset(i * w + w / 2, y(v))
            if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
        }
        drawPath(path, C.Blue, style = Stroke(2f))
        val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
        listOf(plan.entry to C.Gold, plan.stop to C.Red, plan.target to C.Green).forEach { (v, c) ->
            drawLine(c, Offset(0f, y(v)), Offset(size.width, y(v)), strokeWidth = 2f, pathEffect = dash)
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Legend(C.Gold, "Entry"); Legend(C.Red, "Stop"); Legend(C.Green, "Target"); Legend(C.Blue, "VWAP")
    }
}

@Composable
private fun Legend(c: Color, t: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(c))
        Spacer(Modifier.width(4.dp))
        Text(t, color = C.Muted, fontSize = 11.sp)
    }
}

/** Horizontal bar for a +/- contribution. */
@Composable
fun ContributionBar(name: String, value: Double, maxAbs: Double) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, color = C.Text, fontSize = 13.sp)
            Text(if (value >= 0) "raises %" else "lowers %", color = if (value >= 0) C.Green else C.Red, fontSize = 12.sp)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.Card2)) {
            val frac = (kotlin.math.abs(value) / maxAbs).coerceIn(0.02, 1.0).toFloat()
            Box(Modifier.fillMaxWidth(frac).height(6.dp).clip(RoundedCornerShape(3.dp))
                .background(if (value >= 0) C.Green else C.Red))
        }
    }
}

fun strengthColor(s: com.nmx.intradaylens.engine.Strength) = when (s) {
    com.nmx.intradaylens.engine.Strength.STRONG -> C.Green
    com.nmx.intradaylens.engine.Strength.GOOD -> C.Gold
    com.nmx.intradaylens.engine.Strength.WEAK -> C.Muted
    com.nmx.intradaylens.engine.Strength.SKIP -> C.Red
}

fun actionColor(k: com.nmx.intradaylens.data.ActionKind) = when (k) {
    com.nmx.intradaylens.data.ActionKind.BUY_NOW, com.nmx.intradaylens.data.ActionKind.HOLD,
    com.nmx.intradaylens.data.ActionKind.DONE_WIN -> C.Green
    com.nmx.intradaylens.data.ActionKind.EXIT_NOW, com.nmx.intradaylens.data.ActionKind.DONE_LOSS -> C.Red
    com.nmx.intradaylens.data.ActionKind.WAIT -> C.Gold
    com.nmx.intradaylens.data.ActionKind.DONE_EVEN, com.nmx.intradaylens.data.ActionKind.DONE_TIME -> C.Blue
    else -> C.Muted
}

/** The bar at the top: which part of the trading day we're in. */
@Composable
fun ClockBar(step: com.nmx.intradaylens.data.DayStep, timeText: String) {
    val order = listOf(
        com.nmx.intradaylens.data.DayStep.OPENING, com.nmx.intradaylens.data.DayStep.ENTRY,
        com.nmx.intradaylens.data.DayStep.MANAGE, com.nmx.intradaylens.data.DayStep.EXIT
    )
    val pos = when (step) {
        com.nmx.intradaylens.data.DayStep.BEFORE -> -1
        com.nmx.intradaylens.data.DayStep.CLOSED -> 4
        else -> order.indexOf(step)
    }
    AppCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            order.indices.forEach { i ->
                val col = when {
                    i < pos -> C.Green
                    i == pos -> C.Gold
                    else -> C.Card2
                }
                Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(col))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("9:15 Open", "9:30 Entry", "1:00 No entry", "3:15 Exit").forEach { Text(it, color = C.Muted, fontSize = 10.sp) }
        }
        Spacer(Modifier.height(8.dp))
        Text("$timeText • ${step.label}", color = if (step == com.nmx.intradaylens.data.DayStep.EXIT) C.Red else C.Gold,
            fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(step.hint, color = C.Muted, fontSize = 12.sp)
    }
}

@Composable
fun Banner(text: String, color: Color) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.15f)).padding(12.dp)
    ) { Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}
