package com.mauro.wgbg

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ------------------------------------------------------------------ the box clasp on a picker (0.3.5)
//
// Mauro: the text ▾ on the setup screen's pickers becomes a brass box clasp, hinged at the bottom. Pull the picker down
// and the clasp swings open with your finger (closed, a third, two thirds, open); let go fully open and the picker opens.
// A plain tap opens it as before.

/** The clasp's frames, closed to open. */
val CLASP_FRAMES = listOf(R.drawable.clasp_closed, R.drawable.clasp_open_33, R.drawable.clasp_open_66, R.drawable.clasp_open)

/** How far a finger pulls to swing the clasp fully open. */
val CLASP_REACH: Dp = 40.dp

/** The frame to show [pull] of the way open (0 closed, 1 open). */
fun claspFrame(pull: Float): Int = (pull.coerceIn(0f, 1f) * (CLASP_FRAMES.size - 1) + 0.5f).toInt()

/** Whether a pull let go at [pull] opens the picker: only once the clasp is all the way open. */
fun claspOpens(pull: Float): Boolean = claspFrame(pull) == CLASP_FRAMES.size - 1

/** The clasp, [pull] of the way open. */
@Composable
fun Clasp(pull: Float, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Image(painterResource(CLASP_FRAMES[claspFrame(pull)]), null, modifier.size(size))
}

/**
 * A downward pull on this element swings the clasp ([pull], 0 to 1) and opens the picker ([onOpen]) when let go fully
 * open. Only a downward pull is the clasp's: an upward one, or a tap, goes on to the screen's scroll and the click as before.
 */
fun Modifier.claspPull(pull: MutableFloatState, onOpen: () -> Unit): Modifier = composed {
    // The pull recomposes the picker (a new onOpen each time); keyed on Unit so that doesn't restart the gesture.
    val open by rememberUpdatedState(onOpen)
    pointerInput(Unit) {
        val reach = CLASP_REACH.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, over -> if (over > 0f) change.consume() } ?: return@awaitEachGesture
            pull.floatValue = 0f
            verticalDrag(start.id) { change ->
                pull.floatValue = (pull.floatValue + change.positionChange().y / reach).coerceIn(0f, 1f)
                change.consume()
            }
            if (claspOpens(pull.floatValue)) open()
            pull.floatValue = 0f
        }
    }
}
