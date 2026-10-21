package com.mauro.wgbg

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ------------------------------------------------------------------ the tabletop look (game start, lobby)
// Ported from the approved canvases (docs/design/NewGame.dc.html, LobbyA.dc.html): dark wood,
// a thin old-brass edge, brass buttons, Alegreya SC titles over Alegreya Sans.

val ALEGREYA_SC = FontFamily(Font(R.font.alegreya_sc_medium, FontWeight.Medium), Font(R.font.alegreya_sc_bold, FontWeight.Bold))
val ALEGREYA_SANS = FontFamily(
    Font(R.font.alegreya_sans_regular, FontWeight.Normal),
    Font(R.font.alegreya_sans_medium, FontWeight.Medium),
    Font(R.font.alegreya_sans_medium, FontWeight.SemiBold),
    Font(R.font.alegreya_sans_bold, FontWeight.Bold),
)

/**
 * Material typography in the house fonts, so any Text without its own style is Alegreya too, not Roboto: titles in
 * Alegreya SC, everything else in Alegreya Sans (Fable review T1: about a third of the text on screen fell back to Roboto).
 */
val WOOD_TYPOGRAPHY: Typography = Typography().run {
    fun TextStyle.sc() = copy(fontFamily = ALEGREYA_SC)
    fun TextStyle.sans() = copy(fontFamily = ALEGREYA_SANS)
    Typography(
        displayLarge = displayLarge.sc(), displayMedium = displayMedium.sc(), displaySmall = displaySmall.sc(),
        headlineLarge = headlineLarge.sc(), headlineMedium = headlineMedium.sc(), headlineSmall = headlineSmall.sc(),
        titleLarge = titleLarge.sc(), titleMedium = titleMedium.sc(), titleSmall = titleSmall.sc(),
        bodyLarge = bodyLarge.sans(), bodyMedium = bodyMedium.sans(), bodySmall = bodySmall.sans(),
        labelLarge = labelLarge.sans(), labelMedium = labelMedium.sans(), labelSmall = labelSmall.sans(),
    )
}

object Wood {
    val NIGHT = Color(0xFF0B0907)
    val WOOD_MID = Color(0xFF4A3020)
    val WOOD_DARK = Color(0xFF2A1B10)
    val BRASS = Color(0xFFC9A04A)
    val BRASS_LIGHT = Color(0xFFF1D58A)
    val BRASS_EDGE = Color(0xFFD9B865)
    val BRASS_SHADOW = Color(0xFF4A3008)
    val BRASS_DIM = Color(0xFF8A6A2A)
    val BRASS_INK = Color(0xFF2A1A08)
    val INK = Color(0xFFEDE6D8)
    val INK_SOFT = Color(0xFFCDBFA6)
    val INK_MUTED = Color(0xFFD9CBB0)
    val PARCHMENT = Color(0xFFEFE3C8)
    val PARCHMENT_INK = Color(0xFF2B2118)
    val GO = Color(0xFF3E9B4F)
    val READY = Color(0xFF8BD18B)
    val WAITING = Color(0xFFE0B23A)

    /** Text colors for player seats on dark wood (lifted so blue and white read; design values). */
    val SEAT_TEXT = listOf(Color(0xFFEF8A8A), Color(0xFF7FB0F0), Color(0xFFF7A24B), Color(0xFFF5F5F5))

    fun sans(size: TextUnit, weight: FontWeight = FontWeight.Normal, color: Color = INK) =
        TextStyle(fontFamily = ALEGREYA_SANS, fontSize = size, fontWeight = weight, color = color)

    fun title(size: TextUnit, color: Color = BRASS_LIGHT) =
        TextStyle(fontFamily = ALEGREYA_SC, fontSize = size, fontWeight = FontWeight.Bold, color = color)
}

/** A texture tiled at [tileDp] wide, like CSS `background-size`; [turned] a quarter turn. Decoded once per size. */
@Composable
fun rememberTexture(res: Int, tileDp: Dp, turned: Boolean = false): ShaderBrush {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { tileDp.roundToPx() }.coerceAtLeast(8)
    return remember(res, px, turned) {
        val scaled = decodeRenderTexture(context.resources, res, px).let {
            if (!turned) it else android.graphics.Bitmap.createBitmap(it, 0, 0, it.width, it.height, android.graphics.Matrix().apply { postRotate(90f) }, true)
        }
        ShaderBrush(ImageShader(scaled.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
    }
}

/** The table under everything: wood grain under a near-black stain. */
@Composable
fun Modifier.tableTop(): Modifier {
    val wood = rememberTexture(R.drawable.wood_grain, 320.dp)
    return drawWithCache {
        val wash = Brush.verticalGradient(listOf(Color(0xCC140B05), Color(0xE6080402)))
        onDrawBehind {
            drawRect(wood)
            drawRect(wash)
        }
    }
}

/**
 * A panel of wood in an old-brass frame. Panels get Mauro's pick D (0.3.4): hammered brass bent round rounded corners
 * on aged wood ([hammeredFrame]). [edge] below 5 dp keeps today's thin straight edge, for small chips.
 */
@Composable
fun Modifier.brassEdgedWood(edge: Dp = 5.dp, woodAlpha: Float = 1f): Modifier {
    if (edge >= 5.dp) return hammeredFrame(woodAlpha)
    val wood = rememberTexture(R.drawable.wood_grain, 240.dp)
    val brass = rememberTexture(R.drawable.old_brass, 200.dp)
    // woodAlpha < 1: a see-through panel (Settings > Side panel) shows the board through the wood; the brass frame stays.
    return (if (woodAlpha >= 1f) shadow(8.dp, RectangleShapeCompat) else this).drawBehind {
        drawRect(wood, alpha = woodAlpha)
        drawRect(Brush.verticalGradient(listOf(Color(0xC22E1A0C), Color(0xE6120A04))), alpha = woodAlpha)
        val e = edge.toPx()
        // The brass frame, then a bevel: light on the outer top-left, dark on the inner edge.
        drawRect(brass, Offset(e / 2, e / 2), Size(size.width - e, size.height - e), style = Stroke(e))
        drawRect(Color(0x66FFF2CD), Offset.Zero, size, style = Stroke(1.dp.toPx()))
        drawRect(Color(0xB31E1204), Offset(e, e), Size(size.width - 2 * e, size.height - 2 * e), style = Stroke(1.dp.toPx()))
    }
}

private val RectangleShapeCompat: Shape = RoundedCornerShape(0.dp)

/** Widest hammered band and outer corner radius; the band bends round the corner in one piece. */
private val FRAME_BAND = 10.dp
private val FRAME_CORNER = 14.dp

/**
 * The hammered frame's band width, outer corner radius and inner (wood) corner radius, px, for a [w] x [h] panel. The
 * band grows with the panel (6% of its short side, at most [band]) and adds no padding, so a small box keeps a slim
 * band and no panel's content is pushed out (a padded first try cut off the game-start screen's Host and Join).
 */
internal fun frameBand(w: Float, h: Float, band: Float, corner: Float, least: Float): Triple<Float, Float, Float> {
    val short = minOf(w, h)
    val f = minOf(band, 0.06f * short).coerceAtLeast(least)
    val r = minOf(corner, short / 2)
    return Triple(f, r, (r - f).coerceAtLeast(least))
}

/**
 * Hand-beaten brass round warm, aged wood (Mauro, 0.3.4: "panels should have brushed brass framing, aged, and wooden
 * backgrounds", then "I like round corners, preferably hammered that way", and pick D). One rounded band of hammered
 * brass (`hammered_brass.webp`, tools/make_hammered_brass.py), no joins, lit from the top left and rolled at its edges;
 * the wood's grain shows, darker toward the frame where years of hands have not rubbed it.
 */
@Composable
private fun Modifier.hammeredFrame(woodAlpha: Float): Modifier {
    val wood = rememberTexture(R.drawable.wood_grain, 240.dp)
    val brass = rememberTexture(R.drawable.hammered_brass, 72.dp)
    val stain = androidx.compose.ui.graphics.ColorFilter.tint(Color(0xFF9A6A40), androidx.compose.ui.graphics.BlendMode.Multiply)
    // woodAlpha < 1: a see-through panel (Settings > Side panel) shows the board through the wood; the brass band stays.
    return (if (woodAlpha >= 1f) shadow(8.dp, RoundedCornerShape(FRAME_CORNER)) else this).drawWithCache {
        val w = size.width; val h = size.height
        val (f, r, ri) = frameBand(w, h, FRAME_BAND.toPx(), FRAME_CORNER.toPx(), 2.dp.toPx())
        fun rounded(inset: Float, radius: Float) = androidx.compose.ui.graphics.Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(inset, inset, w - inset, h - inset,
                androidx.compose.ui.geometry.CornerRadius(radius)))
        }
        val inner = rounded(f, ri)
        val band = androidx.compose.ui.graphics.Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, rounded(0f, r), inner)
        val wash = Brush.verticalGradient(listOf(Color(0x8C2A170A), Color(0xB80E0703)))
        val age = 20.dp.toPx()
        val ageInk = Color(0x700A0502)
        // The band is lit along its top and left, in shade along its bottom and right, and bends smoothly between.
        val clear = Color.Transparent
        val litTop = Brush.verticalGradient(0f to Color(0x40FFF0C8), f / h to clear, 1 - f / h to clear, 1f to Color(0x66000000))
        val litLeft = Brush.horizontalGradient(0f to Color(0x30FFF0C8), f / w to clear, 1 - f / w to clear, 1f to Color(0x55000000))
        val px1 = 1.dp.toPx()
        onDrawBehind {
            // Wood: the grain stained warm, a wash, and darkening toward the frame.
            clipPath(inner) {
                drawRect(wood, alpha = woodAlpha, colorFilter = stain)
                drawRect(wash, alpha = woodAlpha)
                drawRect(Brush.verticalGradient(listOf(ageInk, clear), f, f + age), Offset(0f, f), Size(w, age), alpha = woodAlpha)
                drawRect(Brush.verticalGradient(listOf(clear, ageInk), h - f - age, h - f), Offset(0f, h - f - age), Size(w, age), alpha = woodAlpha)
                drawRect(Brush.horizontalGradient(listOf(ageInk, clear), f, f + age), Offset(f, 0f), Size(age, h), alpha = woodAlpha)
                drawRect(Brush.horizontalGradient(listOf(clear, ageInk), w - f - age, w - f), Offset(w - f - age, 0f), Size(age, h), alpha = woodAlpha)
            }
            // The hammered band, lit from the top left.
            drawPath(band, brass)
            drawPath(band, litTop)
            drawPath(band, litLeft)
            // A little verdigris where the band meets the wood at the corners.
            for ((x, y) in listOf(f to f, w - f to f, f to h - f, w - f to h - f))
                drawCircle(Brush.radialGradient(listOf(Color(0x404E8C74), clear), Offset(x, y), f * 1.4f), f * 1.4f, Offset(x, y))
            // A rolled outer edge catching the light, and a dark lip where the band meets the wood.
            drawRoundRect(Color(0x73FFF2CD), Offset(px1 / 2, px1 / 2), Size(w - px1, h - px1), androidx.compose.ui.geometry.CornerRadius(r), style = Stroke(px1))
            drawRoundRect(Color(0x40FFE9B0), Offset(f - 2.5f * px1, f - 2.5f * px1), Size(w - 2 * f + 5 * px1, h - 2 * f + 5 * px1),
                androidx.compose.ui.geometry.CornerRadius(ri + 2.5f * px1), style = Stroke(px1 * 0.75f))
            drawRoundRect(Color(0xCC1A0F04), Offset(f - px1 / 2, f - px1 / 2), Size(w - 2 * f + px1, h - 2 * f + px1),
                androidx.compose.ui.geometry.CornerRadius(ri + px1 / 2), style = Stroke(1.5f * px1))
        }
    }
}

/** Old-brass plate: the texture under a light-to-dark wash, a lit top edge and a dark rim. */
@Composable
fun Modifier.brassPlate(shape: RoundedCornerShape = RoundedCornerShape(8.dp)): Modifier {
    val brass = rememberTexture(R.drawable.old_brass, 220.dp)
    return shadow(3.dp, shape).clip(shape).drawBehind {
        drawRect(brass)
        drawRect(Brush.verticalGradient(listOf(Color(0x38FFECAF), Color(0x7A50340C))))
        drawLine(Color(0x99FFF5D4), Offset(0f, 1f), Offset(size.width, 1f), 1.dp.toPx())
    }.border(1.dp, Wood.BRASS_SHADOW, shape)
}

/**
 * A choice button in the two looks the canvases use: selected = brass with dark ink,
 * unselected = dark wood with a brass rim. [enabled] false dims it and ignores taps.
 */
@Composable
fun Choice(
    label: String,
    selected: Boolean,
    tag: String,
    modifier: Modifier = Modifier,
    radius: Dp = 8.dp,
    size: TextUnit = 14.sp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .clip(shape)
            .background(if (selected) Wood.BRASS else Wood.WOOD_DARK)
            .border(2.dp, if (selected) Wood.BRASS_LIGHT else Wood.BRASS, shape)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 6.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        // Shrinks to fit rather than cut off: three seat cards side by side at 320 dp tall (CI's screen) read "Gree".
        Text(
            label, maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = size),
            style = Wood.sans(size, if (selected) FontWeight.Bold else FontWeight.SemiBold, if (selected) Wood.WOOD_DARK else Wood.INK)
                .let { if (enabled) it else it.copy(color = it.color.copy(alpha = 0.4f)) },
        )
    }
}

/** A plain in-game button in the motif: dark wood with a brass rim. */
@Composable
fun WoodButton(label: String, tag: String, modifier: Modifier = Modifier, size: TextUnit = 14.sp, onClick: () -> Unit) =
    Choice(label, selected = false, tag = tag, modifier = modifier.heightIn(min = 40.dp), size = size, onClick = onClick)

/** A coloured action button (green Yes/Confirm, red No/Cancel/Exit) with the brass edge. */
@Composable
fun ColorButton(label: String, color: Color, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // Green and red map to the go / stop plates; anything else keeps the brass plate.
    val plate = when { color.green > color.red -> Plate.GO; color.red > 0.6f && color.green < 0.4f -> Plate.STOP; else -> Plate.BRASS }
    PlateBox(plate, enabled = true, onClick = onClick, modifier = modifier.heightIn(min = 40.dp).testTag(tag)) {
        Text(label, style = Wood.sans(15.sp, FontWeight.Bold, Color.White), maxLines = 1)
    }
}

/** A brass-plate action button with dark ink. */
@Composable
fun BrassButton(label: String, tag: String, modifier: Modifier = Modifier, size: TextUnit = 14.sp, onClick: () -> Unit) {
    // Raised burnished-brass plate (9-slice art); light ink reads on the brass.
    PlateBox(Plate.BRASS, enabled = true, onClick = onClick, modifier = modifier.testTag(tag)) {
        Text(label, style = Wood.sans(size, FontWeight.Bold, Color(0xFFFFF4D8)), maxLines = 1)
    }
}

/** The big green go button (Play / Start game); greyed and inert when [enabled] is false. */
@Composable
fun GoButton(label: String, tag: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    PlateBox(Plate.GO, enabled = enabled, onClick = onClick, modifier = modifier.testTag(tag)) {
        Text(label, style = Wood.sans(17.sp, FontWeight.Bold, if (enabled) Color.White else Wood.INK.copy(alpha = 0.45f)))
    }
}

/** A red stop plate (Cancel, Leave, No). */
@Composable
fun StopButton(label: String, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    PlateBox(Plate.STOP, enabled = true, onClick = onClick, modifier = modifier.heightIn(min = 40.dp).testTag(tag)) {
        Text(label, style = Wood.sans(15.sp, FontWeight.Bold, Color.White), maxLines = 1)
    }
}
