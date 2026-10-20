package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every button outside the board can be reached on a small screen (CI's 640x320 dp): panels that can outgrow 320 dp
 * scroll, and rows that can outgrow 640 dp wrap. 0.3.2 cut off the game-start screen's Points, Report and Intro and the
 * start menu's Settings with a saved game. On a device: tools/ci_reach.sh.
 */
class SmallScreenTest {
    private val src = File("src/main/java/com/mauro/wgbg")
    private fun text(name: String) = File(src, name).readText().replace("\r\n", "\n")

    @Test
    fun `the game-start screen's panels scroll and its buttons wrap`() {
        val screen = text("NewGameScreen.kt")
        assertTrue("Panel(Modifier.weight(1f), scroll = true)" in screen, "the left panel (players, game, Points, Report) scrolls")
        assertTrue("Panel(Modifier.width(300.dp), scroll = true)" in screen, "the right panel (dice, Play, Host, Join) scrolls")
        val buttons = screen.substringAfter("FlowRow(").substringBefore("\n            }\n")
        for (b in listOf("\"Report a bug or idea\"", "\"Intro\"", "\"Look & art\"", "\"Card tester\"")) assertTrue(b in buttons, "$b wraps with the others")
        // Points shares that row, so on a phone the panel needs no scroll to show the buttons.
        assertTrue("\"Points\"" in buttons)
    }

    @Test
    fun `seats stack on a narrow panel so their choices keep their names`() {
        val screen = text("NewGameScreen.kt")
        val seats = screen.substringAfter("BoxWithConstraints(").substringBefore("\n            }\n")
        assertTrue("maxWidth >=" in seats && "stacked = true" in seats, "below the width three seats need, they stack")
    }

    @Test
    fun `the game-over panel and the dice histogram scroll`() {
        assertTrue("chest().verticalScroll(rememberScrollState())" in text("Congrats.kt"), "six players (v3) outgrow 320 dp")
        assertTrue("chest().verticalScroll(rememberScrollState())" in text("Histogram.kt"), "with the event die it outgrows 320 dp")
        // ci_reach.sh finds the dice by this name to double-tap them.
        assertTrue("contentDescription = \"Dice\"" in text("Hud.kt"))
    }

    @Test
    fun `the start menu, lobby, Report sheet and card guide scroll`() {
        val splash = text("Splash.kt")
        for (tag in listOf("start-menu", "log-in-panel")) {
            val line = splash.lines().first { "testTag(\"$tag\")" in it }
            assertTrue("verticalScroll(rememberScrollState())" in line && "weight(1f, fill = false)" in line, "$tag takes the height left and scrolls")
        }
        assertTrue(Regex("""brassEdgedWood\(\)\.verticalScroll\(rememberScrollState\(\)\)""").findAll(text("Lobby.kt")).count() >= 2,
            "both lobby columns scroll")
        assertTrue("brassEdgedWood().verticalScroll(rememberScrollState())" in text("Feedback.kt"), "the Report sheet scrolls (a picture squashed Send)")
        val guide = text("Guide.kt")
        assertTrue("brassEdgedWood().verticalScroll(rememberScrollState())" in guide)
        assertTrue("FlowRow(" in guide.substringAfter("heading(\"Cards\")").substringBefore("heading(\"Build\")"), "the eight cards wrap")
    }

    @Test
    fun `the build menu and view bars hide under an open sheet`() {
        val main = text("MainActivity.kt")
        val open = main.lines().first { "val sheetOpen =" in it }
        for (s in listOf("guideOpen", "feedbackOpen", "confirmExit", "confirmEnd", "histogramOpen")) assertTrue(s in open, "$s hides the build menu")
        val after = main.substringAfter("val sheetOpen =")
        assertTrue(after.lines().first { "BuildMenu" in it || "tick.let" in it }.contains("!sheetOpen"), "the build menu is gated")
        assertTrue(after.lines().first { "ViewBarsPanel(" in it }.contains("!sheetOpen"), "the view bars are gated")
    }

    @Test
    fun `a choice label shrinks to fit instead of being cut`() {
        val choice = text("Tabletop.kt").substringAfter("fun Choice(").substringBefore("\n}\n")
        assertTrue("TextAutoSize.StepBased" in choice && "maxLines = 1" in choice)
    }
}
