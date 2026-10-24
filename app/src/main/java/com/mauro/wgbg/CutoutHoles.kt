package com.mauro.wgbg

import android.os.Build
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * The camera cutout's own rects (window px), not the whole strip its inset takes. The side panel runs flush to the screen
 * edge beside the camera, and only what sits at the camera's height steps around it (0.3.6: players asked for the blank
 * strip back; 0.3.4 kept the lens off the dice).
 */
@Composable
internal fun cutoutHoles(): List<Rect> {
    val d = LocalDensity.current
    val cut = WindowInsets.displayCutout   // read here, so a turn of the phone or a new cutout redraws with new rects
    val key = listOf(cut.getLeft(d, LayoutDirection.Ltr), cut.getTop(d), cut.getRight(d, LayoutDirection.Ltr), cut.getBottom(d))
    val view = LocalView.current
    return remember(key, view) {
        if (Build.VERSION.SDK_INT < 28) emptyList()
        else view.rootWindowInsets?.displayCutout?.boundingRects.orEmpty()
            .filter { !it.isEmpty }.map { Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
    }
}

/**
 * How far right something at [left], from [top] to [bottom] (window px), moves to clear every hole level with it, by
 * [margin] more: 0 when no hole at its height reaches its left edge (a hole on the far side of the screen is not its own).
 */
internal fun holeIndent(left: Float, top: Float, bottom: Float, holes: List<Rect>, margin: Float): Float =
    holes.filter { it.top - margin < bottom && it.bottom + margin > top && it.left - margin < left && it.right + margin > left }
        .maxOfOrNull { it.right + margin - left } ?: 0f

/**
 * The same for something on the right edge ending at [right]: how far left it moves to clear every hole level with it
 * that reaches its right edge (the camera on the right, the phone turned the other way).
 */
internal fun holeIndentEnd(right: Float, top: Float, bottom: Float, holes: List<Rect>, margin: Float): Float =
    holes.filter { it.top - margin < bottom && it.bottom + margin > top && it.right + margin > right && it.left - margin < right }
        .maxOfOrNull { right + margin - it.left } ?: 0f

/** Steps a row right of any camera hole level with it; elsewhere it keeps its place. [indentPx] is how far, for the row's own use. */
internal class HoleDodge(private val holes: List<Rect>, private val margin: Float, private val end: Boolean = false) {
    /** In the row's own units (the player list may be scaled down to fit). */
    var indentPx by mutableFloatStateOf(0f)
        private set

    /** Goes first on the row: it measures where the row is, before the step. */
    val track: Modifier = if (holes.isEmpty()) Modifier else Modifier.onGloballyPositioned { c ->
        val b = c.boundsInWindow()
        val scale = if (c.size.width > 0 && b.width > 0f) b.width / c.size.width else 1f
        indentPx = (if (end) holeIndentEnd(b.right, b.top, b.bottom, holes, margin) else holeIndent(b.left, b.top, b.bottom, holes, margin)) /
            scale.coerceAtLeast(0.1f)
    }
}

@Composable
internal fun rememberHoleDodge(holes: List<Rect>, margin: Dp = 4.dp, end: Boolean = false): HoleDodge {
    val m = with(LocalDensity.current) { margin.toPx() }
    return remember(holes, m, end) { HoleDodge(holes, m, end) }
}

/** The row moved right of any camera hole level with it. */
internal fun Modifier.dodgeHoles(holes: List<Rect>, margin: Dp = 4.dp): Modifier = if (holes.isEmpty()) this else composed {
    val dodge = rememberHoleDodge(holes, margin)
    dodge.track.padding(start = with(LocalDensity.current) { dodge.indentPx.toDp() })
}

/**
 * Something on the right edge moved left of any camera hole level with it; it keeps its right edge, so it stays flush.
 * A plate smaller than the 48 dp touch target passes a [margin] of half the difference more, so its touch area clears too.
 */
internal fun Modifier.dodgeHolesEnd(holes: List<Rect>, margin: Dp = 4.dp): Modifier = if (holes.isEmpty()) this else composed {
    val dodge = rememberHoleDodge(holes, margin, end = true)
    dodge.track.padding(end = with(LocalDensity.current) { dodge.indentPx.toDp() })
}

/**
 * How far in from the right edge (dp) a camera hole near the top of the screen reaches, for what is centred along the
 * top (the turn prompt): 0 with none there. On a small phone a notch can be wider than the room kept for the buttons.
 */
internal fun topRightHoleDp(holes: List<Rect>, widthPx: Float, density: Float, topDp: Float = 120f): Float =
    holes.filter { it.top < topDp * density && it.right >= widthPx - 1f }.maxOfOrNull { (widthPx - it.left) / density } ?: 0f
