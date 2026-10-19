package com.mauro.wgbg

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import wgbg.engine.Resource

/**
 * The game screen's fixed sprite art, decoded off the main thread (#111: a relaunch straight into a saved game decoded
 * all of it while composing the first frame, and on a busy phone the window missed Android's 5 s input deadline).
 */
internal class GameArt(
    val cliff: List<ImageBitmap>,
    val raiders: ImageBitmap,
    val knightsPirate: List<ImageBitmap>,
    val knightsNormal: List<ImageBitmap>,
    val plainFaces: Map<Int, ImageBitmap>,
    val framedFaces: Map<Int, ImageBitmap>,
    val gullFlap: ImageBitmap,
    val gullGlide: ImageBitmap,
    val gullShadow: ImageBitmap,
    val boatWake: ImageBitmap,
    val boatSprites: List<ImageBitmap>,
    val ocean: Bitmap,
) {
    companion object {
        /** Boat sails in the players' colours (Red, Blue, Orange, White), then the extra two. */
        val BOATS = listOf(R.drawable.boat_red, R.drawable.boat_blue, R.drawable.boat_orange, R.drawable.boat_white, R.drawable.boat_green, R.drawable.boat_brown)
        val CLIFFS = listOf(R.drawable.cliff_top_1, R.drawable.cliff_top_2, R.drawable.cliff_top_3)

        /** Decodes everything; any thread. */
        fun load(res: Resources): GameArt {
            fun img(id: Int) = ImageBitmap.imageResource(res, id)
            return GameArt(
                cliff = CLIFFS.map(::img),
                raiders = img(R.drawable.raiders_ship),
                knightsPirate = KNIGHTS_PIRATE.map(::img),
                knightsNormal = KNIGHTS_NORMAL.map(::img),
                plainFaces = TILE_FACES.withIndex().associate { (r, id) -> r to img(id) } + (Resource.NONE to img(TILE_DESERT)),
                framedFaces = TILE_FACES_FRAMED.withIndex().associate { (r, id) -> r to img(id) } + (Resource.NONE to img(TILE_DESERT_FRAMED)),
                gullFlap = img(R.drawable.gull_flap),
                gullGlide = img(R.drawable.gull_glide),
                gullShadow = img(R.drawable.gull_shadow),
                boatWake = img(R.drawable.boat_wake),
                boatSprites = BOATS.map(::img),
                ocean = BitmapFactory.decodeResource(res, R.drawable.ocean_tile),
            )
        }
    }
}

/**
 * [load] run on a background thread whenever [keys] change, or null until the first result. A later load keeps showing
 * the previous result until it finishes, so changing a setting mid-game never blanks the board.
 */
@Composable
internal fun <T : Any> rememberLoaded(vararg keys: Any?, load: () -> T): T? {
    val state = remember { mutableStateOf<T?>(null) }
    LaunchedEffect(*keys) { state.value = withContext(Dispatchers.Default) { load() } }
    return state.value
}

/** The light first frame while a game's art loads: the start screen's sea and one wood plate. */
@Composable
internal fun SettingTheTable() {
    Box(Modifier.fillMaxSize().background(SPLASH_SEA).testTag("loading-game"), contentAlignment = Alignment.Center) {
        Box(Modifier.brassEdgedWood().padding(horizontal = 28.dp, vertical = 16.dp)) {
            Text("Setting the table…", style = Wood.title(22.sp))
        }
    }
}
