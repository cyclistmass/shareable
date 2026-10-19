package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * #111: a relaunch into a saved game decoded all its art on the main thread while composing the first frame, and on a
 * busy phone the window missed Android's 5 s input deadline. The art now loads in the background ([GameArt]).
 */
class StartupLoadTest {
    private val src = File("src/main/java/com/mauro/wgbg")

    @Test
    fun `the game screen and start menu decode no art and start no Firebase while composing`() {
        val main = File(src, "MainActivity.kt").readText()
        // The composable form (ImageBitmap.imageResource(R.drawable.x)) decodes on the main thread during composition.
        assertEquals(0, Regex("""ImageBitmap\.imageResource\(R\.""").findAll(main).count(), "MainActivity decodes art in composition")
        assertTrue("BitmapFactory.decodeResource(oceanRes" !in main, "the ocean decodes in GameArt")
        assertTrue("SettingTheTable()" in main, "the game shows a light frame until its art is ready")
        val splash = File(src, "Splash.kt").readText()
        assertTrue(Regex("""remember \{ runCatching \{ LobbyRepository\.create""").find(splash) == null, "the start menu sets up Firebase in the background")
    }

    @Test
    fun `every drawable the game art decodes ships in the app`() {
        val drawables = File("src/main/res/drawable-nodpi").list()!!.map { it.substringBeforeLast('.') }.toSet()
        val names = listOf("cliff_top_1", "cliff_top_2", "cliff_top_3", "raiders_ship", "gull_flap", "gull_glide", "gull_shadow", "boat_wake",
            "boat_red", "boat_blue", "boat_orange", "boat_white", "boat_green", "boat_brown", "ocean_tile",
            "tile_timber", "tile_clay", "tile_fleece", "tile_grain", "tile_stone", "tile_desert")
        for (n in names) assertTrue(n in drawables, n)
        assertEquals(6, GameArt.BOATS.size, "one sail per seat, six seats")
        assertEquals(3, GameArt.CLIFFS.size)
        assertEquals(5, TILE_FACES.size); assertEquals(5, TILE_FACES_FRAMED.size)
        assertEquals(6, KNIGHTS_PIRATE.size); assertEquals(6, KNIGHTS_NORMAL.size)
    }
}
