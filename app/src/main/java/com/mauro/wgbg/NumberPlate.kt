package com.mauro.wgbg

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.hypot

/**
 * The number plate in aged brass (Mauro, 0.3.4: "numbers original but brass option, same paint"; option A: brass replaces
 * the wood everywhere): today's round plate,
 * same size and place, with the digits and pips painted exactly as on the wood one (dark ink, red 6 and 8). The brass is
 * worn, never new (AGENTS.md): polished pale by years of hands wherever the paint sits, so the paint reads as well as on
 * the wood, with a dulled darker rim, a finely engraved ring and a little verdigris in the crevice at its edge.
 */
internal object BrassPlateLook {
    val POLISHED = Color(0xFFF7E08E)
    val FACE = Color(0xFFF2D676)
    val WORN = Color(0xFFEFD06A)
    val RIM = Color(0xFF8A6420)
    /** The plate's side, seen below its face when the board tilts. */
    val EDGE = Color(0xFF5E4721)
    val ENGRAVING = Color(0xFF5A4320)
    val VERDIGRIS = Color(0xFF4F7F6A)
    /** The face's gradient: its centre (up left, toward the light) and radius, in plate radii, and its stops. */
    val CENTER = Offset(-0.12f, -0.14f)
    const val RADIUS = 1.2f
    val STOPS = arrayOf(0f to POLISHED, 0.5f to FACE, 0.9f to WORN, 1f to RIM)
    /** Two-digit numbers span nearly the whole plate: everything inside this much of the radius may carry paint. */
    const val PAINTED = 0.9f
    /** How strongly the worn brass texture (`old_brass.jpg`, the panel edges' brass) shows over the face: its mottling and wear. */
    const val TEXTURE = 0.25f
    /** The 3D plate's colour and finish: a little metallic only, since the board's light has no reflections to show. */
    val LIVE_COLOR = Color(0xFFEFCB5E)
    const val LIVE_METALLIC = 0.35f
    const val LIVE_ROUGHNESS = 0.42f

    /** The face's colour at ([x], [y]) plate radii from the plate's centre, as the gradient paints it. */
    fun faceAt(x: Float, y: Float): Color {
        val f = (hypot(x - CENTER.x, y - CENTER.y) / RADIUS).coerceIn(0f, 1f)
        val i = STOPS.indexOfLast { it.first <= f }.coerceAtMost(STOPS.size - 2)
        val (a, ca) = STOPS[i]; val (b, cb) = STOPS[i + 1]
        return lerp(ca, cb, ((f - a) / (b - a)).coerceIn(0f, 1f))
    }
}

/** The 3D board's number plate model, tinted brass ([BrassPlateLook.LIVE_COLOR]); the knights' pedestals reuse its disc untinted. */
internal const val LIVE_PLATE_MODEL = "number_plate"

/** WCAG contrast ratio of two colours, 1..21. */
internal fun contrast(a: Color, b: Color): Float {
    val la = a.luminance(); val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/**
 * The brass face of a plate of radius [r] at [t], under the digits: its side, the face lit from the upper left like the
 * wood token, the engraved ring and the verdigris. [texture], the panel edges' worn brass, mottles the face. [k] scales
 * the side and the outline with the plate, as on the wood.
 */
internal fun DrawScope.drawBrassPlateFace(t: Offset, r: Float, k: Float, scale: Float, texture: Brush? = null) {
    drawCircle(BrassPlateLook.EDGE, r, t + Offset(0f, 0.04f * k * scale))
    drawCircle(Brush.radialGradient(*BrassPlateLook.STOPS, center = t + BrassPlateLook.CENTER * r, radius = BrassPlateLook.RADIUS * r), r, t)
    if (texture != null) drawCircle(texture, r, t, alpha = BrassPlateLook.TEXTURE)
    // Verdigris settled in the crevice where the face meets the rim.
    drawCircle(BrassPlateLook.VERDIGRIS.copy(alpha = 0.4f), 0.95f * r, t, style = Stroke(0.035f * r))
    // A fine engraved ring: the cut in shadow, its lower lip catching the light.
    drawCircle(BrassPlateLook.ENGRAVING.copy(alpha = 0.55f), 0.86f * r, t, style = Stroke(0.02f * r))
    drawCircle(BrassPlateLook.POLISHED.copy(alpha = 0.5f), 0.86f * r, t + Offset(0f, 0.022f * r), style = Stroke(0.012f * r))
    // Highlights polished by hands, upper left, as on the wood token.
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.3f), Color.Transparent),
        center = t + Offset(-0.35f * r, -0.35f * r), radius = 1.1f * r), r, t)
    // The metal's sheen: a soft polished arc along the upper left of the rim (lighter only, so the paint reads no worse).
    drawArc(Color.White.copy(alpha = 0.4f), 195f, 75f, useCenter = false, topLeft = t - Offset(0.9f * r, 0.9f * r),
        size = Size(1.8f * r, 1.8f * r), style = Stroke(0.05f * r, cap = StrokeCap.Round))
    drawCircle(BrassPlateLook.EDGE, r, t, style = Stroke(0.02f * k * scale))
}

/** The numbers' paint, the same on every plate (flat and 3D, wood and brass): dark ink, and red for the 6 and 8. */
internal val NUMBER_INK = Color(0xFF2B2118)
internal val NUMBER_RED = Color(0xFFC62828)
internal fun numberPaint(n: Int): Color = if (n == 6 || n == 8) NUMBER_RED else NUMBER_INK
