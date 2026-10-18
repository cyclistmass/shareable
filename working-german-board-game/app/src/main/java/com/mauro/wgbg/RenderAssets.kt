package com.mauro.wgbg

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext

/**
 * Decode close to the draw size, keeping nodpi pixel geometry and the original aspect ratio.
 * Not for sprite strips unless the caller also adjusts its frame coordinates.
 */
internal fun decodeRenderBitmap(resources: Resources, @DrawableRes id: Int,
                                targetWidth: Int, targetHeight: Int): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
    BitmapFactory.decodeResource(resources, id, bounds)
    val options = BitmapFactory.Options().apply {
        inScaled = false
        inSampleSize = renderDecodeSample(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
    }
    return requireNotNull(BitmapFactory.decodeResource(resources, id, options)) { "Drawable could not be decoded" }
}

/** Width-sized texture decode; retained aspect ratio means no full-size intermediate is necessary. */
internal fun decodeRenderTexture(resources: Resources, @DrawableRes id: Int, width: Int): Bitmap {
    require(width > 0)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
    BitmapFactory.decodeResource(resources, id, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0)
    val height = (width.toLong() * bounds.outHeight / bounds.outWidth).toInt().coerceAtLeast(1)
    val options = BitmapFactory.Options().apply {
        inScaled = false
        inSampleSize = renderDecodeSample(bounds.outWidth, bounds.outHeight, width, height)
    }
    val sampled = requireNotNull(BitmapFactory.decodeResource(resources, id, options))
    val scaled = Bitmap.createScaledBitmap(sampled, width, height, true)
    if (scaled !== sampled) sampled.recycle() // intermediate has never escaped this function
    return scaled
}

/**
 * Dice images decoded once per (image, pixel size) and shared by every roll. Decoding happens off the main
 * thread (Mauro's phone froze or crashed on the first roll when 20-30 dice frames were decoded during
 * composition); until an image is ready the caller gets null and draws without it.
 */
internal object DiceImages {
    private val cache = java.util.concurrent.ConcurrentHashMap<Long, ImageBitmap>()
    private fun key(id: Int, px: Int) = (id.toLong() shl 20) or px.toLong()
    fun cached(id: Int, px: Int): ImageBitmap? = cache[key(id, px)]

    /** Decodes [id] at [px] square (null if Android can't decode it, e.g. under memory pressure); never throws. */
    fun load(resources: Resources, id: Int, px: Int): ImageBitmap? = cache[key(id, px)] ?: runCatching {
        decodeRenderBitmap(resources, id, px, px).asImageBitmap()
    }.getOrNull()?.also { cache[key(id, px)] = it }
}

private fun dicePixels(density: androidx.compose.ui.unit.Density, sideDp: Int) =
    with(density) { androidx.compose.ui.unit.Dp(sideDp.toFloat()).roundToPx() }.coerceAtLeast(1)

/** Sampled themed art at a density-aware size: from the shared cache, else decoded in the background (null until then). */
@Composable
internal fun rememberDiceBitmap(id: Int, sideDp: Int): ImageBitmap? {
    val resources = LocalContext.current.resources
    val pixels = dicePixels(androidx.compose.ui.platform.LocalDensity.current, sideDp)
    // Keyed state, not produceState: produceState keeps its old value when the key changes, so a new roll's die showed
    // the last roll's face, and with a non-null old face it never loaded the new one (report Q8MX2, 0.3.0).
    val image = androidx.compose.runtime.remember(resources, id, pixels) { androidx.compose.runtime.mutableStateOf(DiceImages.cached(id, pixels)) }
    androidx.compose.runtime.LaunchedEffect(image) {
        if (image.value == null) image.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { DiceImages.load(resources, id, pixels) }
    }
    return image.value
}

/** All of [ids] at a density-aware size, or an empty list until every frame is decoded (in the background). */
@Composable
internal fun rememberDiceFrames(ids: List<Int>, sideDp: Int): List<ImageBitmap> {
    val resources = LocalContext.current.resources
    val pixels = dicePixels(androidx.compose.ui.platform.LocalDensity.current, sideDp)
    fun ready() = ids.map { DiceImages.cached(it, pixels) }.takeIf { l -> l.all { it != null } }?.map { it!! } ?: emptyList()
    // Keyed like rememberDiceBitmap, so a theme change never tumbles the old theme's frames.
    val frames = androidx.compose.runtime.remember(resources, ids, pixels) { androidx.compose.runtime.mutableStateOf(ready()) }
    androidx.compose.runtime.LaunchedEffect(frames) {
        if (frames.value.isEmpty() && ids.isNotEmpty()) frames.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val loaded = ids.map { DiceImages.load(resources, it, pixels) }
            if (loaded.all { it != null }) loaded.map { it!! } else emptyList()
        }
    }
    return frames.value
}
