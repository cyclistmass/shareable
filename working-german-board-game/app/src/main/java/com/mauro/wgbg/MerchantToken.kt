package com.mauro.wgbg

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Where the Age of Chivalry Merchant stands on its tile, in hex sides from the centre (y down, before the tilt squash):
 * lower LEFT, mirroring the number plate's lower right, so it never covers the number (#112).
 */
internal val MERCHANT_SPOT = Offset(-0.36f, 0.30f)
internal const val MERCHANT_RADIUS = 0.2f

/** The generic Merchant pawn's height in hex sides: about a homestead's width, so it reads on a tilted board. */
internal const val MERCHANT_PAWN_HEIGHT = 0.5f

private val PAWN_LINE = Color(0xFF2A1C0E)
private val BRASS_LIGHT = Color(0xFFD9B66A)
private val BRASS = Color(0xFF9C7A3C)
private val BRASS_DARK = Color(0xFF6E5427)

/** The pawn's turned profile: radius (in pawn heights) at height [z] (0 base .. 1 top) for the body below the collar. */
internal fun merchantSkirtRadius(z: Float): Float {
    val t = ((z - 0.08f) / (MERCHANT_NECK - 0.08f)).coerceIn(0f, 1f)
    return 0.24f + (0.08f - 0.24f) * (1f - (1f - t) * (1f - t)) // flares out to the base
}

private const val MERCHANT_NECK = 0.62f

/**
 * The Merchant (#112, reports "we need a way of knowing where the merchant is"): a standing pawn in the holder's colour
 * with a worn-brass coin on its chest. It is drawn as a turned solid seen from the board camera, the same view the
 * Bandit's render uses (Mauro: "same angle as robber"): every round slice squashes by [squash], heights shrink by the
 * tilt's sine, so on a flat-on board you look down on it and on a tilted board you see its side. [center] is where its
 * base touches the tile, [unit] one hex side in px, [squash] the board's tilt (1 flat, smaller tilted). A theme can
 * replace the pawn with its own sprite through [art] (drawn upright on the same spot, base at the bottom centre).
 */
internal fun DrawScope.drawMerchantToken(center: Offset, unit: Float, squash: Float, owner: Color, art: ImageBitmap? = null) {
    val h = MERCHANT_PAWN_HEIGHT * unit
    val sq = squash.coerceIn(0.3f, 1f)
    val rise = sqrt(1f - sq * sq).coerceAtLeast(0.12f) // how much of the pawn's height the camera sees
    fun oval(c: Offset, rx: Float, ry: Float) = c - Offset(rx, ry) to Size(rx * 2, ry * 2)
    /** Screen centre of the pawn's slice at height [z]. */
    fun at(z: Float) = center - Offset(0f, z * h * rise)
    // Shadow under it, cast a little to the lower right like the other pieces.
    oval(center + Offset(h * 0.06f, h * 0.03f), h * 0.36f, h * 0.36f * sq).let { (o, s) -> drawOval(Color.Black.copy(alpha = 0.38f), o, s) }
    if (art != null) {
        drawImage(art, dstOffset = IntOffset((center.x - h * 0.6f).roundToInt(), (center.y - h * 1.15f).roundToInt()),
            dstSize = IntSize((h * 1.2f).roundToInt(), (h * 1.2f).roundToInt()))
        return
    }
    val line = Stroke(h * 0.03f)
    val light = androidx.compose.ui.graphics.lerp(owner, Color.White, 0.35f)
    val dark = androidx.compose.ui.graphics.lerp(owner, Color.Black, 0.4f)
    fun body(r: Float) = Brush.horizontalGradient(listOf(light, owner, dark), startX = center.x - r, endX = center.x + r)
    /** A short upright cylinder from [z0] to [z1] of radius [r] (pawn heights), outlined. */
    fun cylinder(z0: Float, z1: Float, r: Float) {
        val rx = r * h; val ry = rx * sq
        val lo = at(z0); val hi = at(z1)
        val side = Path().apply {
            moveTo(lo.x - rx, lo.y); lineTo(hi.x - rx, hi.y); lineTo(hi.x + rx, hi.y); lineTo(lo.x + rx, lo.y)
            arcTo(androidx.compose.ui.geometry.Rect(lo.x - rx, lo.y - ry, lo.x + rx, lo.y + ry), 0f, 180f, false); close()
        }
        drawPath(side, body(rx)); drawPath(side, PAWN_LINE, style = line)
        oval(hi, rx, ry).let { (o, s) -> drawOval(body(rx), o, s); drawOval(PAWN_LINE, o, s, style = line) }
    }
    cylinder(0f, 0.08f, 0.30f)
    // The skirt: stacked slices give the turned shape at any tilt; then its two silhouette edges.
    val steps = 18
    val left = Path(); val right = Path()
    for (i in 0..steps) {
        val z = 0.08f + (MERCHANT_NECK - 0.08f) * i / steps
        val r = merchantSkirtRadius(z) * h
        val c = at(z)
        oval(c, r, r * sq).let { (o, s) -> drawOval(body(r), o, s) }
        if (i == 0) { left.moveTo(c.x - r, c.y); right.moveTo(c.x + r, c.y) } else { left.lineTo(c.x - r, c.y); right.lineTo(c.x + r, c.y) }
    }
    drawPath(left, PAWN_LINE, style = line); drawPath(right, PAWN_LINE, style = line)
    // The theme's trade symbol: a worn-brass coin on the front of the chest, foreshortened like the surface it sits on.
    val cz = 0.30f
    val coin = at(cz) + Offset(0f, merchantSkirtRadius(cz) * h * sq)
    val r = h * 0.1f
    val ry = r * rise
    val sack = Path().apply {
        val k = r * 0.5f; val ky = k * rise
        moveTo(coin.x - k * 0.35f, coin.y - ky * 0.55f)
        lineTo(coin.x + k * 0.35f, coin.y - ky * 0.55f)
        quadraticTo(coin.x + k * 1.05f, coin.y + ky * 0.2f, coin.x + k * 0.7f, coin.y + ky * 0.75f)
        lineTo(coin.x - k * 0.7f, coin.y + ky * 0.75f)
        quadraticTo(coin.x - k * 1.05f, coin.y + ky * 0.2f, coin.x - k * 0.35f, coin.y - ky * 0.55f)
        close()
    }
    if (rise > 0.3f) {
        oval(coin, r, ry).let { (o, s) ->
            drawOval(Brush.radialGradient(listOf(BRASS_LIGHT, BRASS, BRASS_DARK), coin - Offset(r * 0.3f, ry * 0.3f), r * 1.3f), o, s)
            drawOval(PAWN_LINE, o, s, style = Stroke(h * 0.02f))
        }
        drawPath(sack, Color(0xFF3B2A12))
    }
    // Collar, then the head: a ball looks round from any angle.
    cylinder(MERCHANT_NECK, MERCHANT_NECK + 0.04f, 0.15f)
    val head = at(0.80f)
    drawCircle(Brush.radialGradient(listOf(light, owner, dark), head - Offset(h * 0.06f, h * 0.06f), h * 0.22f), h * 0.16f, head)
    drawCircle(PAWN_LINE, h * 0.16f, head, style = line)
}
