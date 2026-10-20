package com.mauro.wgbg

import androidx.compose.ui.geometry.Offset
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The 3D board's roads and pieces move in the same frame as the board (0.3.4, Mauro: "roads lag behind the board when you
 * tilt or pan, then catch up").
 */
class LiveLagTest {
    private val view = File("src/main/java/com/mauro/wgbg/LiveBoardView.kt").readText()

    private fun times(a: FloatArray, b: FloatArray) = FloatArray(16) { i ->
        val col = i / 4; val row = i % 4
        (0..3).sumOf { (a[it * 4 + row] * b[col * 4 + it]).toDouble() }.toFloat()
    }
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    @Test
    fun `each frame reads the camera as it is then, not as the last recomposition left it`() {
        val frame = view.substringAfter("override fun doFrame").substringBefore("\n    }\n")
        assertTrue("projectionNow?.invoke() ?: projection" in frame, "doFrame takes the live camera first")
        val main = File("src/main/java/com/mauro/wgbg/MainActivity.kt").readText()
        val call = main.substringAfter("LiveBoardView(placements").substringBefore("{ reason ->")
        assertTrue("projectionNow = {" in call, "the game screen passes its live camera")
    }

    @Test
    fun `a Space layer moved toward the camera lands where the old baked-in bias put each piece`() {
        // Roads, number plates and standing pieces each get their own bias; the layer's root carries it now.
        val pieces = listOf(LivePlacement("space_road_v", Offset(1.2f, -0.4f), 0.035f, 0.9f, 0.7f, 2, 2.5f),
            LivePlacement("space_number_6", Offset(-2f, 1f), 0.04f), LivePlacement("space_homestead", Offset(0.3f, 2.2f), owner = 1))
        for (tilt in listOf(0f, 0.4f, 0.9f, 1.3f)) for (rotation in listOf(0f, 0.7f, -2.1f, 3.1f)) for (p in pieces) {
            val bias = liveDepthBias(p.model)
            assertTrue(bias > 0f)
            val baked = liveTowardCamera(liveModelMatrix(p), tilt, rotation, bias)
            val layered = times(liveTowardCamera(identity.copyOf(), tilt, rotation, bias), liveModelMatrix(p))
            assertArrayEquals(baked, layered, 1e-5f, "${p.model} at tilt $tilt, rotation $rotation")
        }
    }

    @Test
    fun `only pieces posed by the camera are rebuilt as it moves - the board and roads never wait on it`() {
        for (m in listOf("space_homestead", "space_town", "space_bandit", "space_knight_l2", "space_number_8")) assertTrue(liveTurnsWithCamera(m), m)
        for (m in listOf("space_road_v", "space_tile_grain", "space_deck", "space_sea")) assertFalse(liveTurnsWithCamera(m), m)
        val still = view.substringAfter("LaunchedEffect(space) {").substringBefore("\n    }\n")
        assertTrue("filterNot { liveTurnsWithCamera" in still, "the still layers are keyed on the pieces alone")
        val turning = view.substringAfter("LaunchedEffect(space, projection?.rotation, projection?.tilt, facing) {").substringBefore("\n    }\n")
        assertTrue("filter { liveTurnsWithCamera" in turning)
        // No bias is baked into the batch any more: it's applied per layer each frame.
        assertFalse("liveTowardCamera(it, projection" in view)
        assertTrue("for ((layer, asset) in batchAssets) engine.transformManager.setTransform" in view)
    }
}
