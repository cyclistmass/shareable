package com.mauro.wgbg

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.translate
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
            // The Space renders are bare objects on clear ground: the game draws the card around them (a night-blue
            // face with a brass edge, the object inset, the rules' name on a band), so they read as cards in the hand.
            val w = size.width; val h = size.height; val r = w * .08f; val edge = w * .035f
            val c = drawContext.canvas.nativeCanvas
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { typeface = font }
            p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h, 0xFF34405F.toInt(), 0xFF151B2C.toInt(), android.graphics.Shader.TileMode.CLAMP)
            c.drawRoundRect(0f, 0f, w, h, r, r, p)
            p.shader = null
            withTransform({ translate(w * .09f, h * .07f) }) { with(source) { draw(Size(w * .82f, h * .66f)) } }
            p.color = if (card < 5) 0xFFEFE3C8.toInt() else 0xFFD5C39C.toInt()
            c.drawRoundRect(edge, h * .78f, w - edge, h - edge, r * .6f, r * .6f, p)
            p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = edge; p.color = 0xFFC9A25A.toInt()
            c.drawRoundRect(edge / 2, edge / 2, w - edge / 2, h - edge / 2, r, r, p)
            p.style = android.graphics.Paint.Style.FILL
            p.color = 0xFF30251B.toInt(); p.textSize = w * .14f; p.textAlign = android.graphics.Paint.Align.CENTER
            c.drawText(ALL_CARD_NAMES[card], w / 2, h * .915f, p)
        }
    } }
}
