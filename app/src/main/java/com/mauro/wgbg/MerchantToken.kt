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

/**
 * The Merchant (#112, reports "we need a way of knowing where the merchant is"): a standing pawn in the holder's colour,
 * so it shows on a tilted board, with a worn-brass coin on its chest. [center] is where its base touches the tile,
 * [unit] one hex side in px, [squash] the board's tilt (1 flat, smaller tilted). A theme can replace the pawn with its
 * own sprite through [art] (drawn upright on the same spot, base at the bottom centre of the image).
 */
internal fun DrawScope.drawMerchantToken(center: Offset, unit: Float, squash: Float, owner: Color, art: ImageBitmap? = null) {
    val h = MERCHANT_PAWN_HEIGHT * unit
    val sq = squash.coerceIn(0.3f, 1f)
    fun oval(c: Offset, rx: Float, ry: Float) = c - Offset(rx, ry) to Size(rx * 2, ry * 2)
    // Shadow under it, cast a little to the lower right like the other pieces.
    oval(center + Offset(h * 0.06f, h * 0.03f), h * 0.36f, h * 0.36f * sq * 0.55f).let { (o, s) -> drawOval(Color.Black.copy(alpha = 0.38f), o, s) }
    if (art != null) {
        drawImage(art, dstOffset = IntOffset((center.x - h * 0.6f).roundToInt(), (center.y - h * 1.15f).roundToInt()),
            dstSize = IntSize((h * 1.2f).roundToInt(), (h * 1.2f).roundToInt()))
        return
    }
    val line = Stroke(h * 0.035f)
    val light = androidx.compose.ui.graphics.lerp(owner, Color.White, 0.35f)
    val dark = androidx.compose.ui.graphics.lerp(owner, Color.Black, 0.4f)
    val body = Brush.horizontalGradient(listOf(light, owner, dark), startX = center.x - h * 0.3f, endX = center.x + h * 0.3f)
    // Base: a flat disc lying on the tile.
    oval(center, h * 0.30f, h * 0.30f * sq * 0.5f).let { (o, s) -> drawOval(dark, o, s); drawOval(PAWN_LINE, o, s, style = line) }
    oval(center - Offset(0f, h * 0.06f), h * 0.30f, h * 0.30f * sq * 0.5f).let { (o, s) -> drawOval(body, o, s); drawOval(PAWN_LINE, o, s, style = line) }
    // Skirt: from the base up to a narrow neck.
    val neckY = center.y - h * 0.62f
    val skirt = Path().apply {
        moveTo(center.x - h * 0.24f, center.y - h * 0.06f)
        quadraticTo(center.x - h * 0.10f, center.y - h * 0.32f, center.x - h * 0.08f, neckY)
        lineTo(center.x + h * 0.08f, neckY)
        quadraticTo(center.x + h * 0.10f, center.y - h * 0.32f, center.x + h * 0.24f, center.y - h * 0.06f)
        close()
    }
    drawPath(skirt, body); drawPath(skirt, PAWN_LINE, style = line)
    // Collar, then the head.
    oval(Offset(center.x, neckY), h * 0.15f, h * 0.05f).let { (o, s) -> drawOval(body, o, s); drawOval(PAWN_LINE, o, s, style = line) }
    val head = Offset(center.x, neckY - h * 0.17f)
    drawCircle(Brush.radialGradient(listOf(light, owner, dark), head - Offset(h * 0.06f, h * 0.06f), h * 0.22f), h * 0.16f, head)
    drawCircle(PAWN_LINE, h * 0.16f, head, style = line)
    // The theme's trade symbol: a worn-brass coin on the chest with a coin purse engraved in it.
    val coin = Offset(center.x, center.y - h * 0.30f)
    val r = h * 0.1f
    drawCircle(Brush.radialGradient(listOf(BRASS_LIGHT, BRASS, BRASS_DARK), coin - Offset(r * 0.3f, r * 0.3f), r * 1.3f), r, coin)
    drawCircle(PAWN_LINE, r, coin, style = Stroke(h * 0.022f))
    val k = r * 0.5f
    val sack = Path().apply {
        moveTo(coin.x - k * 0.35f, coin.y - k * 0.55f)
        lineTo(coin.x + k * 0.35f, coin.y - k * 0.55f)
        quadraticTo(coin.x + k * 1.05f, coin.y + k * 0.2f, coin.x + k * 0.7f, coin.y + k * 0.75f)
        lineTo(coin.x - k * 0.7f, coin.y + k * 0.75f)
        quadraticTo(coin.x - k * 1.05f, coin.y + k * 0.2f, coin.x - k * 0.35f, coin.y - k * 0.55f)
        close()
    }
    drawPath(sack, Color(0xFF3B2A12))
}


