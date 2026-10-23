package com.mauro.wgbg

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// ------------------------------------------------------------------ the clasp box picker (0.3.5)
//
// Mauro approved the art lane's storyboard (2026-10-22): a seat's name picker is a small brass-framed wood box with the
// clasp at its right end. Tap it, or pull it down, and the hasp swings out to the right; then the box front, hinged at its
// bottom, drops into a brass-framed wood panel holding the list, over the controls below it. Closing plays it backwards.
// The sprites, their timing and where they sit are Codex's (wgbg-art/buildrow-0.3.5/final/claspbox: timeline.json,
// slices.json), drawn at the box's width over their 384 px; the names and the highlight's place are drawn live.

/** When each frame of the hasp starts (ms): it swings out over the first 220 ms. */
val CLASPBOX_HASP_MS = listOf(0, 32, 65, 98, 130, 160, 190, 220)

/** When each frame of the dropping front starts: it shows from 220 ms, and has dropped by 560. */
val CLASPBOX_FRONT_MS = listOf(220, 310, 400, 450, 500, 560)

/** When the front has become the list panel. */
const val CLASPBOX_OPEN_MS = 600

/** How long the settled panel takes to grow (or shrink) to fit its rows, when they don't fit the dropped front. */
const val CLASPBOX_SETTLE_MS = 120

/** How far into the open a pull takes it (the front mid-drop); let go there and it finishes, short of it it shuts. */
const val CLASPBOX_PULL_MS = 400

/** The most names the panel shows at once; more scroll. */
const val CLASPBOX_ROWS = 4

/** The hasp's frame at [t] ms into the open. */
fun claspBoxHasp(t: Float): Int = CLASPBOX_HASP_MS.indexOfLast { t >= it }.coerceAtLeast(0)

/** The front's frame at [t] ms into the open, or null while it's still shut. */
fun claspBoxFront(t: Float): Int? = if (t < CLASPBOX_FRONT_MS[0]) null else CLASPBOX_FRONT_MS.indexOfLast { t >= it }

// Where things sit, in the art's own pixels (timeline.json's assembly, slices.json).
private const val ART_W = 384f
private const val BOX_H = 96f
private const val HASP_X = 280f           // the keeper and hasp: their 128 px canvas at 0.75, at the box's right end
private const val HASP_SIZE = 96f
private const val FRONT_Y = 84f           // the front's canvas, hinged 8 px down it, at the box's bottom edge
private const val FRONT_H = 224f
private const val PANEL_DY = 8f           // the settled panel, from the front's canvas top
private const val PANEL_H = 208f          // the dropped front's height, which the panel replaces
private const val HINGE_H = 16f
private const val PANEL_INSET = 20f
private const val LABEL_X = 20f
private const val LABEL_W = 260f

/**
 * A name picker as a clasp box: shows [label]; opens to [rows] (the one at [selected] highlighted), [note] beside each,
 * and [onPick] when one is tapped, then shuts. [tag] is the box's, [panelTag] the open panel's, [rowTag] each row's.
 */
@Composable
fun ClaspBox(
    label: String, rows: List<String>, selected: Int?, onPick: (Int) -> Unit, tag: String, panelTag: String,
    rowTag: (Int) -> String, modifier: Modifier = Modifier, note: (Int) -> String? = { null }, ink: Color = Wood.INK,
    maxWidth: Dp = 200.dp,
) {
    val hasp = CLASPBOX_HASP_MS.indices.map { ImageBitmap.imageResource(HASP_ART[it]) }
    val front = CLASPBOX_FRONT_MS.indices.map { ImageBitmap.imageResource(FRONT_ART[it]) }
    val box = ImageBitmap.imageResource(R.drawable.claspbox_box_frame)
    val keeper = ImageBitmap.imageResource(R.drawable.claspbox_keeper)
    val panel = ImageBitmap.imageResource(R.drawable.claspbox_panel_frame)
    val hinge = ImageBitmap.imageResource(R.drawable.claspbox_hinge_strip)
    val fill = ImageBitmap.imageResource(R.drawable.claspbox_panel_wood_fill)
    val highlight = ImageBitmap.imageResource(R.drawable.claspbox_row_selected)
    val divider = ImageBitmap.imageResource(R.drawable.claspbox_row_divider)
    val clock = remember { Animatable(0f) }
    val held = remember { mutableFloatStateOf(-1f) }    // where a pull holds the open, while it does
    var open by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    BoxWithConstraints(modifier) {
        val widthDp = if (this.maxWidth < maxWidth) this.maxWidth else maxWidth
        val w = with(density) { widthDp.toPx() }
        val u = w / ART_W                                                // screen px per art px
        val rowH = max(40f * u, with(density) { 32.dp.toPx() })
        val shown = rows.size.coerceIn(1, CLASPBOX_ROWS)
        val panelH = 2 * PANEL_INSET * u + shown * rowH
        val end = if (abs(panelH - PANEL_H * u) < 1f) CLASPBOX_OPEN_MS.toFloat() else (CLASPBOX_OPEN_MS + CLASPBOX_SETTLE_MS).toFloat()
        val t = if (held.floatValue >= 0f) held.floatValue else clock.value
        val swing = { to: Boolean ->
            open = to
            scope.launch {
                val target = if (to) end else 0f
                clock.animateTo(target, tween(abs(target - clock.value).roundToInt(), easing = LinearEasing))
            }
        }
        // The gesture outlives recompositions (keyed on Unit), so it reads these as they are now.
        val isOpen by rememberUpdatedState(open)
        val swingNow by rememberUpdatedState(swing)
        val boxH = with(density) { (BOX_H * u).toDp() }

        Box(
            Modifier.size(widthDp, boxH)
                .pointerInput(Unit) {
                    // A downward pull on the shut box opens it with the finger: past CLASPBOX_PULL_MS it finishes on its own.
                    val reach = CLASP_REACH.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (isOpen) return@awaitEachGesture
                        val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, over -> if (over > 0f) change.consume() }
                            ?: return@awaitEachGesture
                        var pull = 0f
                        verticalDrag(start.id) { change ->
                            pull = (pull + change.positionChange().y / reach).coerceIn(0f, 1f)
                            held.floatValue = pull * CLASPBOX_PULL_MS
                            change.consume()
                        }
                        val at = held.floatValue
                        scope.launch { clock.snapTo(at); held.floatValue = -1f; swingNow(pull >= 1f) }
                    }
                }
                .clickable(role = Role.Button) { swing(!open) }
                .testTag(tag),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawArt(box, 0f, 0f, ART_W * u, BOX_H * u)
                drawArt(keeper, HASP_X * u, 0f, HASP_SIZE * u, HASP_SIZE * u)
                drawArt(hasp[claspBoxHasp(t)], HASP_X * u, 0f, HASP_SIZE * u, HASP_SIZE * u)
            }
            Text(label, style = Wood.sans(13.sp, FontWeight.Bold, ink), maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.CenterStart).offset(x = with(density) { (LABEL_X * u).toDp() })
                    .width(with(density) { (LABEL_W * u).toDp() }))
        }

        val k = claspBoxFront(t)
        if (k != null) {
            val settled = t >= CLASPBOX_OPEN_MS
            val growth = ((t - CLASPBOX_OPEN_MS) / CLASPBOX_SETTLE_MS).coerceIn(0f, 1f)
            val nowH = if (settled) PANEL_H * u + (panelH - PANEL_H * u) * growth else PANEL_H * u
            val popupH = max(FRONT_H * u, PANEL_DY * u + nowH)
            Popup(
                offset = IntOffset(0, (FRONT_Y * u).roundToInt()),
                onDismissRequest = { if (open) swing(false) },
                properties = PopupProperties(focusable = open),
            ) {
                Box(Modifier.size(widthDp, with(density) { popupH.toDp() }).testTag(if (open && t >= end) panelTag else "$panelTag-moving")) {
                    Canvas(Modifier.fillMaxSize()) {
                        // The front's frames carry their hinge; the settled panel takes the hinge strip over its top edge.
                        if (settled) {
                            drawNine(panel, PANEL_INSET, 0f, PANEL_DY * u, ART_W * u, nowH, u, fill)
                            drawArt(hinge, 0f, 0f, ART_W * u, HINGE_H * u)
                        }
                        else drawArt(front[k], 0f, 0f, ART_W * u, FRONT_H * u)
                    }
                    // The names show, and take taps, only once the panel has settled; closing hides them at once.
                    if (open && t >= end) {
                        Column(
                            Modifier.offset(with(density) { (PANEL_INSET * u).toDp() }, with(density) { ((PANEL_DY + PANEL_INSET) * u).toDp() })
                                .size(with(density) { ((ART_W - 2 * PANEL_INSET) * u).toDp() }, with(density) { (shown * rowH).toDp() })
                                .verticalScroll(rememberScrollState()),
                        ) {
                            rows.forEachIndexed { r, name ->
                                val picked = r == selected
                                Row(
                                    Modifier.fillMaxWidth().height(with(density) { rowH.toDp() })
                                        .drawBehind {
                                            if (picked) drawNine(highlight, 12f, 0f, 0f, size.width, size.height, u, null, top = 10f)
                                            if (r < rows.size - 1) drawArt(divider, 0f, size.height - 4f * u, size.width, 4f * u)
                                        }
                                        .clickable(role = Role.RadioButton) { onPick(r); swing(false) }
                                        .padding(horizontal = 8.dp).testTag(rowTag(r)),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val rowInk = if (picked) Wood.WOOD_DARK else Wood.INK
                                    Text(name, style = Wood.sans(14.sp, FontWeight.Bold, rowInk), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                                    note(r)?.let { Text(it, style = Wood.sans(11.sp, color = if (picked) rowInk else Wood.INK_SOFT), maxLines = 1) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val HASP_ART = listOf(
    R.drawable.claspbox_hasp_00, R.drawable.claspbox_hasp_01, R.drawable.claspbox_hasp_02, R.drawable.claspbox_hasp_03,
    R.drawable.claspbox_hasp_04, R.drawable.claspbox_hasp_05, R.drawable.claspbox_hasp_06, R.drawable.claspbox_hasp_07,
)
private val FRONT_ART = listOf(
    R.drawable.claspbox_front_00, R.drawable.claspbox_front_01, R.drawable.claspbox_front_02,
    R.drawable.claspbox_front_03, R.drawable.claspbox_front_04, R.drawable.claspbox_front_05,
)

/** Draws all of [img] into the rectangle at ([x], [y]), [w] by [h] screen px. */
private fun DrawScope.drawArt(img: ImageBitmap, x: Float, y: Float, w: Float, h: Float) {
    drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset(x.roundToInt(), y.roundToInt()),
        IntSize(w.roundToInt(), h.roundToInt()), filterQuality = FilterQuality.High)
}

/**
 * Draws [img] as a 9-slice into ([x], [y]) [w] by [h]: corners at [u] times their art size, edges stretched along their
 * length, and the centre stretched, or [fill] tiled 1:1 (times [u]) in its place (slices.json's rules). [side] is the
 * left and right inset, [top] the top and bottom one (both [side] unless given).
 */
private fun DrawScope.drawNine(img: ImageBitmap, side: Float, x: Float, y: Float, w: Float, h: Float, u: Float, fill: ImageBitmap?,
                               top: Float = side) {
    val sw = img.width; val sh = img.height
    val sl = side.roundToInt(); val st = top.roundToInt()
    val dl = (side * u).roundToInt(); val dt = (top * u).roundToInt()
    val xs = intArrayOf(x.roundToInt(), x.roundToInt() + dl, (x + w).roundToInt() - dl, (x + w).roundToInt())
    val ys = intArrayOf(y.roundToInt(), y.roundToInt() + dt, (y + h).roundToInt() - dt, (y + h).roundToInt())
    val sx = intArrayOf(0, sl, sw - sl, sw)
    val sy = intArrayOf(0, st, sh - st, sh)
    for (row in 0..2) for (col in 0..2) {
        if (row == 1 && col == 1 && fill != null) continue
        val dw = xs[col + 1] - xs[col]; val dh = ys[row + 1] - ys[row]
        if (dw <= 0 || dh <= 0) continue
        drawImage(img, IntOffset(sx[col], sy[row]), IntSize(sx[col + 1] - sx[col], sy[row + 1] - sy[row]),
            IntOffset(xs[col], ys[row]), IntSize(dw, dh), filterQuality = FilterQuality.High)
    }
    if (fill != null) clipRect(xs[1].toFloat(), ys[1].toFloat(), xs[2].toFloat(), ys[2].toFloat()) {
        val tw = fill.width * u; val th = fill.height * u
        var ty = ys[1].toFloat()
        while (ty < ys[2]) {
            var tx = xs[1].toFloat()
            while (tx < xs[2]) { drawArt(fill, tx, ty, tw, th); tx += tw }
            ty += th
        }
    }
}
