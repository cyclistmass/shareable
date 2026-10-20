package com.mauro.wgbg

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos

/**
 * Two hand-made dice (Codex art via mauro-7a, Normal or Pirate set) are thrown across the screen and land
 * square on the turn panel's dice icons. In the air they cycle through 4 tumbling renders while spinning;
 * at the first touch-down they turn into the panel's resting face for the roll, so the hand-over is seamless.
 */
object DiceSprites {
    const val TUMBLE = 4
    /** Seconds from release to rest. */
    const val FALL_S = 1.6f
    /** Die size at rest when the panel's slots aren't known yet, dp. */
    const val SIDE_DP = 55
    /** Share of a tumbling render's width the cube fills (the resting faces fill their whole image). */
    const val TUMBLE_SHARE = 347f / 512f   // every tumble render normalised to a 347 px body in a 512 px frame (Codex)
    /** Animation time of the first touch-down: from here on the die shows its rolled face, square. */
    const val LAND = 0.62f
    /** Animation time when both dice have settled and the panel's own dice take over. */
    const val HANDOVER = 0.97f

    /**
     * Tumbling render (0 until [frames]) for a die at animation time [t], or -1 once it shows its face. The art's
     * frames are ordered so cycling them reads as one spin; more frames cycle faster to keep the same spin speed.
     */
    fun frame(t: Float, spin: Int, frames: Int = TUMBLE): Int =
        if (t >= LAND) -1 else (spin + (t * 22f * frames / TUMBLE).toInt()) % frames

    /** Spin in degrees: fast in the air, slowing to exactly 0 (square on the pad) at the first touch-down. */
    fun spin(t: Float, turns: Float): Float = if (t >= LAND) 0f else (1f - t / LAND).let { it * it } * turns * 360f

    /** Share of the way from the throw to the pad: arrives exactly at the first touch-down and stays there. */
    fun fall(t: Float): Float = if (t >= LAND) 1f else (t / LAND).let { it * it }

    /** Height of the in-place bounces after touch-down, as a share of the die's size (0 = on the pad). */
    fun bounce(t: Float): Float {
        if (t <= LAND) return 0f
        val u = (t - LAND) / (1f - LAND)
        return abs(kotlin.math.sin(u * 2f * Math.PI.toFloat())) * 0.22f * (1f - u) * (1f - u)
    }
}

/** The tumbling frames of one die in a theme (paired like [diceFace]: red + ivory, or red timber + bone). */
fun diceTumble(red: Boolean, pirate: Boolean): List<Int> = DiceArt.tumble(red, pirate)

/**
 * Full-screen dice throw: each roll comes in from a random direction around the board, from more than
 * halfway out (often off screen), tumbles in and settles square on the panel's dice icons ([slots]), or
 * beside [rest] (the HUD's top-right corner) before those are measured. Pure decoration.
 */
@Composable
fun RollingDice(die1: Int, die2: Int, rollKey: Any?, rest: Offset?, modifier: Modifier = Modifier,
                /** The panel's dice icons (root coordinates): each die lands exactly on its icon, at its size. */
                slots: Pair<Rect, Rect>? = null,
                /** Settings > Theme: the Pirate dice instead of the Normal ones. */
                pirate: Boolean = false,
                /** Settings > Event die: which pirate event die art (DiceArt.EVENT_STYLES). */
                eventStyle: Int = 0,
                /** Age of Chivalry: the event die's face (-1 = none) and the pad it lands on (root coordinates). */
                event: Int = -1,
                eventSlot: Rect? = null,
                /** How long the throw takes (the Pace setting scales it). */
                fallMs: Int = (DiceSprites.FALL_S * 1000).toInt(),
                /** True while the dice are in the air; the panel hides its resting dice until they land. */
                onFlying: (Boolean) -> Unit = {}) {
    var origin by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(Offset.Zero) }
    val t = remember { Animatable(1f) }
    LaunchedEffect(rollKey) {
        if (rollKey == null || die1 <= 0) return@LaunchedEffect
        t.snapTo(0f)
        onFlying(true)
        var landed = false
        try {
            t.animateTo(1f, tween(fallMs, easing = LinearEasing)) {
                // Hand over to the panel's resting dice once both have settled (the second die lags 7%).
                if (!landed && value >= DiceSprites.HANDOVER) { landed = true; onFlying(false) }
            }
        } finally { onFlying(false) }
    }
    if (die1 <= 0 || rest == null) return
    // Cover the fallback cube, airborne lift and transparent tumble margins.
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val restDp = maxOf(DiceSprites.SIDE_DP.toFloat(), (slots?.first?.width ?: 0f) / density,
        (slots?.second?.width ?: 0f) / density, (eventSlot?.width ?: 0f) / density)
    val artDp = kotlin.math.ceil(restDp * DIE_FACE_SHARE * 1.5f / DiceSprites.TUMBLE_SHARE).toInt()
    val choice = LocalArtChoice.current
    val redTumble = rememberDiceFrames(if (choice.space(ArtObject.DICE)) listOf(SpaceArt.diceFace(true, die1)) else diceTumble(red = true, pirate), artDp)
    val ivoryTumble = rememberDiceFrames(if (choice.space(ArtObject.DICE)) listOf(SpaceArt.diceFace(false, die2)) else diceTumble(red = false, pirate), artDp)
    val redFace = rememberDiceBitmap(ThemeArt.dice(choice, true, die1), artDp)
    val ivoryFace = rememberDiceBitmap(ThemeArt.dice(choice, false, die2), artDp)
    val showEvent = event >= 0 && eventSlot != null
    val eventTumble = if (showEvent) rememberDiceFrames(if (choice.space(ArtObject.DICE)) listOf(SpaceArt.eventFace(event)) else DiceArt.eventTumble(pirate, eventStyle), artDp) else emptyList()
    val eventFace = if (showEvent) rememberDiceBitmap(ThemeArt.event(choice, event, eventStyle), artDp) else null

    // Where this roll is thrown from: a direction and distance picked from the roll itself (same roll, same throw).
    val rnd = remember(rollKey) { java.util.Random(rollKey.hashCode().toLong() * 7919L + 17) }
    val angle = remember(rollKey) { rnd.nextFloat() * 2 * Math.PI.toFloat() }
    val reach = remember(rollKey) { 0.55f + rnd.nextFloat() * 0.5f }
    Canvas(modifier.onGloballyPositioned { origin = it.positionInRoot() }) {
        val p = t.value
        if (p >= DiceSprites.HANDOVER) return@Canvas        // settled: the panel's own dice are showing
        val q = ((p - 0.07f) / 0.93f).coerceIn(0f, 1f)
        val qe = ((p - 0.03f) / 0.94f).coerceIn(0f, 1f)          // the event die, between the two
        val center = Offset(size.width / 2, size.height / 2)
        val half = kotlin.math.hypot(size.width / 2, size.height / 2)
        val start = center + Offset(cos(angle), kotlin.math.sin(angle)) * (half * reach)
        // Each die's resting square: its panel icon, or beside the HUD's corner until the icons are measured.
        val side = DiceSprites.SIDE_DP.dp.toPx()
        val rest1 = slots?.first?.center?.minus(origin) ?: (rest + Offset(-side * 1.75f, -side * 0.3f))
        val rest2 = slots?.second?.center?.minus(origin) ?: (rest1 + Offset(side * 1.12f, 0f))
        val dir = (rest1 - start).let { it / it.getDistance().coerceAtLeast(1f) }
        val perp = Offset(-dir.y, dir.x)
        drawDie(rest1, slots?.first?.width ?: side, p, start, perp, redTumble, redFace, spin = 1, turns = 1.4f)
        drawDie(rest2, slots?.second?.width ?: side, q, start, perp, ivoryTumble, ivoryFace, spin = 3, turns = -1.6f)
        if (eventSlot != null && showEvent)
            drawDie(eventSlot.center - origin, eventSlot.width, qe, start, -perp, eventTumble, eventFace, spin = 5, turns = 1.2f)
    }
}

/** One die at animation time [u], flying from [start] to its resting square (drawn as the panel draws it). */
private fun DrawScope.drawDie(restCenter: Offset, restWidth: Float, u: Float, start: Offset, perp: Offset, tumble: List<ImageBitmap>, face: ImageBitmap?,
                              spin: Int, turns: Float) {
    val cube = restWidth * DIE_FACE_SHARE                                  // exactly the size Die3d draws it
    val hop = kotlin.math.sin(u.coerceAtMost(DiceSprites.LAND) / DiceSprites.LAND * Math.PI.toFloat())
    val b = DiceSprites.bounce(u)
    val c = start + (restCenter - start) * DiceSprites.fall(u) + perp * (hop * cube * 0.6f) - Offset(0f, b * cube)
    val lift = 1f + 0.35f * hop + 0.3f * b                               // a little bigger in the air: it's up off the table
    // Frames still decoding in the background: show the face (or nothing yet) rather than index an empty list.
    val f = if (tumble.isEmpty()) -1 else DiceSprites.frame(u, spin, tumble.size)
    val img = (if (f < 0) face else tumble.getOrNull(f)) ?: return
    val drawn = cube * lift / (if (f < 0) 1f else DiceSprites.TUMBLE_SHARE)
    rotate(DiceSprites.spin(u, turns), pivot = c) {
        drawImage(img, IntOffset.Zero, IntSize(img.width, img.height),
            IntOffset((c.x - drawn / 2).toInt(), (c.y - drawn / 2).toInt()), IntSize(drawn.toInt(), drawn.toInt()),
            filterQuality = FilterQuality.Medium)
    }
}
