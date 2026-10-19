package com.mauro.wgbg

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import kotlin.math.roundToInt

/** Raised burnished-brass plate styles (Codex art via Pip): brass, green go, red stop, bright gold. */
enum class Plate(val normal: Int, val pressed: Int) {
    BRASS(R.drawable.btn_brass, R.drawable.btn_brass_pressed),
    GO(R.drawable.btn_go, R.drawable.btn_go_pressed),
    STOP(R.drawable.btn_stop, R.drawable.btn_stop_pressed),
    GOLD(R.drawable.btn_gold, R.drawable.btn_gold_pressed),
}

/** 9-slice insets of the 384x128 plate images (the 768x256 originals' 88/82 halved). */
private const val INSET_X = 44
private const val INSET_Y = 41

/**
 * Draws [img] stretched to the whole area as a 9-slice: the riveted corners keep their size, the
 * edges stretch along one axis and the middle both ways. Corner size on screen is [corner] px tall.
 */
fun DrawScope.nineSlice(img: ImageBitmap, corner: Float) {
    val k = (corner / INSET_Y).coerceAtMost(size.height / 2f / INSET_Y).coerceAtMost(size.width / 2f / INSET_X)
    val cx = (INSET_X * k).roundToInt(); val cy = (INSET_Y * k).roundToInt()
    val w = size.width.roundToInt(); val h = size.height.roundToInt()
    val sx = intArrayOf(0, INSET_X, img.width - INSET_X, img.width)
    val sy = intArrayOf(0, INSET_Y, img.height - INSET_Y, img.height)
    val dx = intArrayOf(0, cx, w - cx, w)
    val dy = intArrayOf(0, cy, h - cy, h)
    for (i in 0..2) for (j in 0..2) {
        val sw = sx[i + 1] - sx[i]; val sh = sy[j + 1] - sy[j]
        val dw = dx[i + 1] - dx[i]; val dh = dy[j + 1] - dy[j]
        if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) continue
        drawImage(img, IntOffset(sx[i], sy[j]), IntSize(sw, sh), IntOffset(dx[i], dy[j]), IntSize(dw, dh), filterQuality = FilterQuality.Medium)
    }
}

/** A raised brass plate behind [content]; it sinks (pressed art) while held. Dimmed and inert when disabled. */
@Composable
fun PlateBox(
    plate: Plate,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val normal = ImageBitmap.imageResource(plate.normal)
    val down = ImageBitmap.imageResource(plate.pressed)
    Box(
        modifier
            .drawBehind {
                nineSlice(if (pressed) down else normal, corner = minOf(size.height * 0.42f, 18.dp.toPx()))
                if (!enabled) drawRect(androidx.compose.ui.graphics.Color(0x8C1A0F07))
            }
            .clickable(interactionSource = src, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The drawn icons on the move buttons (Mauro: "buttons not words, with icons on them"). */
enum class MoveIcon { TRADE, END_TURN, ROAD, HOMESTEAD, TOWN, CARD, KNIGHT, ROUSE, PROMOTE, RAMPARTS, CLOSE }

/**
 * A brass plate with an engraved [icon] instead of words; [label] is what a screen reader (and the test scripts)
 * call it. [lit] marks it pressed-in and glowing gold (an open trade, a trade waiting on you).
 */
@Composable
fun IconPlateButton(icon: MoveIcon, label: String, tag: String, modifier: Modifier = Modifier, plate: Plate = Plate.BRASS, lit: Boolean = false,
                    enabled: Boolean = true, width: androidx.compose.ui.unit.Dp = 66.dp, height: androidx.compose.ui.unit.Dp = 54.dp,
                    caption: String? = null, onClick: () -> Unit) {
    PlateBox(if (lit) Plate.GOLD else plate, enabled = enabled, onClick = onClick,
        modifier = modifier.size(width = width, height = height).testTag(tag).semantics(mergeDescendants = true) { contentDescription = label }) {
      // An optional word under the icon, for buttons a new player hunts for (Fable review W8: End turn).
      androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        androidx.compose.foundation.Canvas(Modifier.size(if (caption != null) 24.dp else 30.dp)) {
            val ink = androidx.compose.ui.graphics.Color(if (lit) 0xFF3A2410 else 0xFFFFF4D8)
            val w = size.width; val h = size.height; val sw = w * 0.11f
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
            when (icon) {
                MoveIcon.TRADE -> {
                    // Two arrows passing each other: give one way, get the other.
                    fun arrow(y: Float, right: Boolean) {
                        val (a, b) = if (right) 0.12f * w to 0.86f * w else 0.88f * w to 0.14f * w
                        drawLine(ink, androidx.compose.ui.geometry.Offset(a, y), androidx.compose.ui.geometry.Offset(b, y), sw, androidx.compose.ui.graphics.StrokeCap.Round)
                        val d = if (right) -1f else 1f
                        val p = androidx.compose.ui.graphics.Path().apply { moveTo(b + d * 0.22f * w, y - 0.17f * h); lineTo(b, y); lineTo(b + d * 0.22f * w, y + 0.17f * h) }
                        drawPath(p, ink, style = stroke)
                    }
                    arrow(0.3f * h, right = true); arrow(0.72f * h, right = false)
                }
                MoveIcon.ROAD -> {
                    // A plank road seen at a slant, two boards.
                    drawLine(ink, androidx.compose.ui.geometry.Offset(0.1f * w, 0.8f * h), androidx.compose.ui.geometry.Offset(0.9f * w, 0.2f * h), sw * 2.2f, androidx.compose.ui.graphics.StrokeCap.Round)
                }
                MoveIcon.HOMESTEAD, MoveIcon.TOWN -> {
                    fun house(x0: Float, x1: Float, roof: Float) {
                        val p = androidx.compose.ui.graphics.Path().apply { moveTo(x0 * w, 0.92f * h); lineTo(x0 * w, roof * h + 0.2f * h); lineTo((x0 + x1) / 2 * w, roof * h)
                            lineTo(x1 * w, roof * h + 0.2f * h); lineTo(x1 * w, 0.92f * h); close() }
                        drawPath(p, ink)   // solid: the piece's silhouette (#124)
                    }
                    if (icon == MoveIcon.HOMESTEAD) house(0.2f, 0.8f, 0.12f)
                    else {   // a town: a hall with a tall gabled tower beside it, like the piece
                        drawRect(ink, androidx.compose.ui.geometry.Offset(0.04f * w, 0.5f * h), androidx.compose.ui.geometry.Size(0.6f * w, 0.42f * h))
                        house(0.56f, 0.96f, 0.02f)
                    }
                }
                MoveIcon.CARD -> {
                    drawRoundRect(ink, androidx.compose.ui.geometry.Offset(0.22f * w, 0.06f * h), androidx.compose.ui.geometry.Size(0.56f * w, 0.88f * h),
                        androidx.compose.ui.geometry.CornerRadius(0.08f * w), style = stroke)
                    drawCircle(ink, 0.11f * w, androidx.compose.ui.geometry.Offset(0.5f * w, 0.5f * h))
                }
                MoveIcon.KNIGHT, MoveIcon.ROUSE, MoveIcon.PROMOTE -> {
                    // A shield; Supply adds a pennant, Promote an upward chevron.
                    val shield = androidx.compose.ui.graphics.Path().apply { moveTo(0.18f * w, 0.18f * h); lineTo(0.82f * w, 0.18f * h); lineTo(0.82f * w, 0.5f * h)
                        quadraticTo(0.8f * w, 0.82f * h, 0.5f * w, 0.95f * h); quadraticTo(0.2f * w, 0.82f * h, 0.18f * w, 0.5f * h); close() }
                    drawPath(shield, ink, style = stroke)
                    when (icon) {
                        MoveIcon.ROUSE -> { val f = androidx.compose.ui.graphics.Path().apply { moveTo(0.5f * w, 0.0f); lineTo(0.95f * w, 0.1f * h); lineTo(0.5f * w, 0.2f * h); close() }; drawPath(f, ink) }
                        MoveIcon.PROMOTE -> { val c = androidx.compose.ui.graphics.Path().apply { moveTo(0.32f * w, 0.68f * h); lineTo(0.5f * w, 0.45f * h); lineTo(0.68f * w, 0.68f * h) }; drawPath(c, ink, style = stroke) }
                        else -> drawLine(ink, androidx.compose.ui.geometry.Offset(0.5f * w, 0.32f * h), androidx.compose.ui.geometry.Offset(0.5f * w, 0.78f * h), sw)
                    }
                }
                MoveIcon.RAMPARTS -> {
                    // A wall with crenellations.
                    val wall = androidx.compose.ui.graphics.Path().apply {
                        moveTo(0.06f * w, 0.92f * h); lineTo(0.06f * w, 0.3f * h); lineTo(0.24f * w, 0.3f * h); lineTo(0.24f * w, 0.48f * h); lineTo(0.4f * w, 0.48f * h)
                        lineTo(0.4f * w, 0.3f * h); lineTo(0.6f * w, 0.3f * h); lineTo(0.6f * w, 0.48f * h); lineTo(0.76f * w, 0.48f * h); lineTo(0.76f * w, 0.3f * h)
                        lineTo(0.94f * w, 0.3f * h); lineTo(0.94f * w, 0.92f * h); close() }
                    drawPath(wall, ink)   // solid silhouette (#124)
                }
                MoveIcon.CLOSE -> {
                    drawLine(ink, androidx.compose.ui.geometry.Offset(0.2f * w, 0.2f * h), androidx.compose.ui.geometry.Offset(0.8f * w, 0.8f * h), sw * 1.4f, androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(ink, androidx.compose.ui.geometry.Offset(0.8f * w, 0.2f * h), androidx.compose.ui.geometry.Offset(0.2f * w, 0.8f * h), sw * 1.4f, androidx.compose.ui.graphics.StrokeCap.Round)
                }
                MoveIcon.END_TURN -> {
                    // An hourglass: your time is up, pass it on.
                    drawLine(ink, androidx.compose.ui.geometry.Offset(0.18f * w, 0.08f * h), androidx.compose.ui.geometry.Offset(0.82f * w, 0.08f * h), sw, androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(ink, androidx.compose.ui.geometry.Offset(0.18f * w, 0.92f * h), androidx.compose.ui.geometry.Offset(0.82f * w, 0.92f * h), sw, androidx.compose.ui.graphics.StrokeCap.Round)
                    val glass = androidx.compose.ui.graphics.Path().apply {
                        moveTo(0.26f * w, 0.1f * h); lineTo(0.74f * w, 0.1f * h); lineTo(0.54f * w, 0.5f * h); lineTo(0.74f * w, 0.9f * h)
                        lineTo(0.26f * w, 0.9f * h); lineTo(0.46f * w, 0.5f * h); close()
                    }
                    drawPath(glass, ink, style = stroke)
                    // The sand run down into the bottom bulb.
                    val sand = androidx.compose.ui.graphics.Path().apply { moveTo(0.5f * w, 0.62f * h); lineTo(0.66f * w, 0.86f * h); lineTo(0.34f * w, 0.86f * h); close() }
                    drawPath(sand, ink)
                }
            }
        }
        if (caption != null) Text(caption, style = Wood.sans(10.sp, FontWeight.Bold,
            androidx.compose.ui.graphics.Color(if (lit) 0xFF3A2410 else 0xFFFFF4D8)), maxLines = 1)
      }
    }
}
