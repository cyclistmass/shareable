package com.mauro.wgbg

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random

/** Final standings: the winner first, then by points; equal points keep seat order. */
internal fun standings(players: List<PlayerInfo>): List<PlayerInfo> =
    players.sortedWith(compareByDescending<PlayerInfo> { it.winner }.thenByDescending { it.vp }.thenBy { it.seat })

/** The headline at the end of a game, from [me]'s seat (-1 when only bots played). */
internal fun congratsTitle(winner: Int, me: Int, names: List<String>): String = when (winner) {
    -1 -> "A draw!"
    me -> "Congratulations, you win!"
    else -> "${names[winner]} wins!"
}

/**
 * The end of a game (Mauro: "game end needs a congrats screen"): confetti in the players' colours, the
 * standings with points and the Highway and Watch holders, and Play again / Exit. "See the board" folds it
 * away to look at the final board; "Results" brings it back.
 */
@Composable
internal fun CongratsScreen(
    players: List<PlayerInfo>, names: List<String>, winner: Int, me: Int, turns: Int,
    folded: Boolean, onFold: (Boolean) -> Unit, onPlayAgain: (() -> Unit)?, onExit: () -> Unit,
    /** Online, a guest: "Join rematch" once the host has opened one (#72). */
    playAgainLabel: String = "Play again",
) {
    if (folded) {
        Box(Modifier.fillMaxSize()) {
            BrassButton("Results", "congrats-results", Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).height(44.dp)) { onFold(false) }
        }
        return
    }
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).displayCutoutPadding().testTag("congrats")) {
        if (winner >= 0) Confetti(PLAYER_COLORS[winner])
        // Scrolls: four players and the buttons just fit 320 dp tall (CI's screen); six (v3) would not.
        Column(
            Modifier.align(Alignment.Center).widthIn(min = 320.dp, max = 460.dp).chest().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(congratsTitle(winner, me, names), color = Color(0xFFF1D58A), fontSize = 28.sp, fontWeight = FontWeight.Bold,
                fontFamily = ALEGREYA_SC, modifier = Modifier.testTag("congrats-title"))
            val top = players.firstOrNull { it.seat == winner }
            Text(if (top != null) "${top.vp} points after $turns turns" else "after $turns turns",
                color = Color(0xFFEDE6D8), fontSize = 14.sp)
            Spacer(Modifier.height(2.dp))
            for ((place, pl) in standings(players).withIndex()) Row(
                Modifier.widthIn(min = 280.dp)
                    .then(if (pl.winner) Modifier.background(Color(0x33C9A04A), RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFFC9A04A), RoundedCornerShape(8.dp)) else Modifier)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${place + 1}.", Modifier.width(24.dp), color = Color(0xFFEDE6D8), fontWeight = FontWeight.Bold)
                Box(Modifier.size(14.dp).background(PLAYER_COLORS[pl.seat], CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(names[pl.seat] + if (pl.seat == me && names[pl.seat] != "You") " (you)" else "", Modifier.weight(1f), color = Color(0xFFEDE6D8),
                    // A 16-letter name ends in "…" before the award tags, which it ran into (0.3.4).
                    fontWeight = if (pl.winner) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull("Highway".takeIf { pl.road }, "Watch".takeIf { pl.army }).joinToString(" · "),
                    Modifier.padding(start = 6.dp), color = Color(0xFFF1D58A), fontSize = 12.sp, maxLines = 1, softWrap = false)
                Text("${pl.vp} VP", Modifier.padding(start = 10.dp), color = Color(0xFFEDE6D8), fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onPlayAgain != null) BrassButton(playAgainLabel, "play-again", Modifier.height(44.dp), onClick = onPlayAgain)
                WoodButton("See the board", "congrats-board") { onFold(true) }
                WoodButton("Exit", "game-over-exit", onClick = onExit)
            }
        }
    }
}

/** Brass flecks and the winner's colour, drifting down and turning; drawn behind the chest. */
@Composable
private fun Confetti(color: Color) {
    val bits = remember(color) {
        val r = Random(7)
        val colors = listOf(color, Color(0xFFF1D58A), Color(0xFFC9A04A), color, Color(0xFFEDE6D8))
        List(70) { floatArrayOf(r.nextFloat(), r.nextFloat(), 0.5f + r.nextFloat(), r.nextFloat() * 360f, r.nextInt(colors.size).toFloat()) } to colors
    }
    val t by rememberInfiniteTransition(label = "confetti").animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "fall")
    Canvas(Modifier.fillMaxSize()) {
        val (list, colors) = bits
        val w = 6.dp.toPx(); val h = 10.dp.toPx()
        for (b in list) {
            val y = ((b[1] + t * b[2]) % 1f) * (size.height + h * 2) - h
            val x = b[0] * size.width + kotlin.math.sin((t * b[2] * 6.283f * 2) + b[3]) * 12.dp.toPx()
            rotate(b[3] + t * 720f * b[2], Offset(x, y)) {
                drawRect(colors[b[4].toInt()], Offset(x - w / 2, y - h / 2), Size(w, h))
            }
        }
    }
}
