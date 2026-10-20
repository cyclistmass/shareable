package com.mauro.wgbg

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Ways to roll each total 2..12 with two dice, out of 36 (index = total). */
val DICE_WAYS = intArrayOf(0, 0, 1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1)

/** One bar of a histogram: its label, how often it came up, and how often fair dice would have given it. */
data class Bar(val label: String, val count: Int, val expected: Float, val color: Color)

/** The totals 2..12 with what [rolls] fair rolls would give. */
fun totalBars(counts: IntArray): List<Bar> {
    val n = counts.sum()
    return (2..12).map { t -> Bar("$t", counts[t], n * DICE_WAYS[t] / 36f, if (t == 6 || t == 8) Color(0xFFFF6B5E) else if (t == 7) Color(0xFFC9A04A) else Color(0xFFEDE6D8)) }
}

/** The Age of Chivalry event die: the Raiders' ship (3 faces of 6) and the three gates (1 each). */
fun eventBars(counts: IntArray): List<Bar> {
    val n = counts.sum()
    return listOf(Bar("Ship", counts[0] + counts[1] + counts[2], n / 2f, Color(0xFFE57373))) +
        listOf("Guilds", "Crown", "Academy").mapIndexed { i, name -> Bar(name, counts[3 + i], n / 6f, TRACK_COLORS[i]) }
}

/**
 * The dice so far this game (Mauro: "the dice histogram when double clicking the dice, include the event die, a brass
 * x to exit"): a bar per total with a thin mark where fair dice would be, and the event die's ship and gates.
 */
@Composable
fun DiceHistogram(rollCounts: IntArray, eventCounts: IntArray, chivalry: Boolean, onClose: () -> Unit, modifier: Modifier = Modifier) {
    // Scrolls: with the event die it is about as tall as CI's 320 dp screen.
    Column(modifier.widthIn(max = 520.dp).chest().verticalScroll(rememberScrollState()).testTag("dice-histogram"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Dice this game · ${rollCounts.sum()} rolls", color = CHEST_INK, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconPlateButton(MoveIcon.CLOSE, "Close", "histogram-close", width = 44.dp, height = 40.dp) { onClose() }
        }
        Bars(totalBars(rollCounts))
        if (chivalry) {
            Text("Event die", color = CHEST_INK, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Bars(eventBars(eventCounts), wide = true)
        }
        Text("The line on each bar is what fair dice would give.", color = CHEST_INK.copy(alpha = 0.7f), fontSize = 11.sp)
    }
}

@Composable
private fun Bars(bars: List<Bar>, wide: Boolean = false) {
    val top = (bars.maxOf { maxOf(it.count.toFloat(), it.expected) }).coerceAtLeast(1f)
    val height = 64.dp
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        for (b in bars) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(if (wide) 64.dp else 30.dp).testTag("bar-${b.label}")) {
            Text("${b.count}", color = CHEST_INK, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.height(height).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.fillMaxWidth(0.8f).height(height * (b.count / top)).background(b.color.copy(alpha = 0.85f), RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
                // Where fair dice would be.
                Box(Modifier.fillMaxWidth().height(2.dp).offset(y = -(height * (b.expected / top))).background(Color(0xFFF1D58A)))
            }
            Text(b.label, color = CHEST_INK, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}
