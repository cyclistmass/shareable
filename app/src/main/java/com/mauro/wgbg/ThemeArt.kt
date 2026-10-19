package com.mauro.wgbg

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource

internal val LocalArtChoice = staticCompositionLocalOf { ArtChoice() }

/** Pure dispatch shared by hand, chest, guide, previews and dice. Names always belong to the rules. */
internal object ThemeArt {
    fun resource(choice: ArtChoice, card: Int) = if (choice.space(ArtObject.CARDS)) SpaceArt.resourceCard(card) else ALL_CARD_ART[card]
    fun knight(choice: ArtChoice, level: Int) = if (choice.space(ArtObject.KNIGHTS) || choice.space(ArtObject.CARDS)) SpaceArt.knightCard(level)
        else GuideArt.knightCard(level, choice.pirate(ArtObject.KNIGHTS))
    fun fortune(choice: ArtChoice, card: Int) = if (choice.space(ArtObject.CARDS)) SpaceArt.fortuneCard(card)
        else GuideArt.fortuneArt(card, choice.pirate(ArtObject.KNIGHTS))
    fun progress(choice: ArtChoice, card: Int) = if (choice.space(ArtObject.CARDS)) SpaceArt.progressCard(card) else progressArt(card)
    fun dice(choice: ArtChoice, red: Boolean, value: Int) = if (choice.space(ArtObject.DICE)) SpaceArt.diceFace(red, value.coerceIn(1, 6))
        else DiceArt.face(red, choice.pirate(ArtObject.DICE), value)
    fun event(choice: ArtChoice, face: Int, style: Int) = if (choice.space(ArtObject.DICE)) SpaceArt.eventFace(face.coerceIn(0, 5))
        else DiceArt.eventFace(choice.pirate(ArtObject.DICE), face, style)
    fun namedCard(choice: ArtChoice, name: String, fallback: Int?): Int? {
        if (!choice.space(ArtObject.CARDS)) return fallback
        PROGRESS_NAMES.indexOf(name).takeIf { it >= 0 }?.let { return SpaceArt.progressCard(it) }
        FORTUNE.indexOf(name).takeIf { it >= 0 }?.let { return SpaceArt.fortuneCard(it) }
        DECK_NAMES.indexOf(name).takeIf { it >= 0 }?.let { return SpaceArt.deckBack(it) }
        return fallback
    }
    fun image(choice: ArtChoice, id: Int): Int {
        ALL_CARD_ART.indexOf(id).takeIf { it >= 0 }?.let { return resource(choice, it) }
        for (level in 1..3) if (id == GuideArt.knightCard(level, false) || id == GuideArt.knightCard(level, true)) return knight(choice, level)
        return id
    }
}

/** Space portraits are deliberately unlettered. Draw the game's resource names at runtime. */
@Composable
internal fun artPainter(id: Int): Painter {
    val choice = LocalArtChoice.current
    val mapped = ThemeArt.image(choice, id)
    val source = painterResource(mapped)
    val card = ALL_CARD_ART.indexOf(id)
    if (card < 0 || !choice.space(ArtObject.CARDS)) return source
    val font = androidx.compose.ui.platform.LocalContext.current.resources.getFont(R.font.alegreya_sans_regular)
    return remember(source, card, font) { object : Painter() {
        override val intrinsicSize: Size get() = source.intrinsicSize
        override fun DrawScope.onDraw() {
            with(source) { draw(size) }
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { typeface = font }
            p.color = if (card < 5) 0xFFEFE3C8.toInt() else 0xFFD5C39C.toInt()
            drawContext.canvas.nativeCanvas.drawRect(0f, size.height * .80f, size.width, size.height, p)
            p.color = 0xFF30251B.toInt(); p.textSize = size.width * .14f; p.textAlign = android.graphics.Paint.Align.CENTER
            drawContext.canvas.nativeCanvas.drawText(ALL_CARD_NAMES[card], size.width / 2, size.height * .93f, p)
        }
    } }
}
