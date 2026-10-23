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

/** Steps a row right of any camera hole level with it; elsewhere it keeps its place. [indentPx] is how far, for the row's own use. */
internal class HoleDodge(private val holes: List<Rect>, private val margin: Float) {
    /** In the row's own units (the player list may be scaled down to fit). */
    var indentPx by mutableFloatStateOf(0f)
        private set

    /** Goes first on the row: it measures where the row is, before the step. */
    val track: Modifier = if (holes.isEmpty()) Modifier else Modifier.onGloballyPositioned { c ->
        val b = c.boundsInWindow()
        val scale = if (c.size.width > 0 && b.width > 0f) b.width / c.size.width else 1f
        indentPx = holeIndent(b.left, b.top, b.bottom, holes, margin) / scale.coerceAtLeast(0.1f)
    }
}

@Composable
internal fun rememberHoleDodge(holes: List<Rect>, margin: Dp = 4.dp): HoleDodge {
    val m = with(LocalDensity.current) { margin.toPx() }
    return remember(holes, m) { HoleDodge(holes, m) }
}

/** The row moved right of any camera hole level with it. */
internal fun Modifier.dodgeHoles(holes: List<Rect>): Modifier = if (holes.isEmpty()) this else composed {
    val dodge = rememberHoleDodge(holes)
    dodge.track.padding(start = with(LocalDensity.current) { dodge.indentPx.toDp() })
}
