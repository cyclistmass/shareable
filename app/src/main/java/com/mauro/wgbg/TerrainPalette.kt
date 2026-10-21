package com.mauro.wgbg

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import wgbg.engine.Resource
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Board terrain colours (#86, Mauro "OK, go", 2026-10-20): Settings › Board › Terrain colours, apart from the theme and
 * hidden in Space. Each palette grades every terrain's art its own way, the same CSS filters the design canvas mocked
 * up, and the graded copies are baked once when the palette changes.
 */
enum class TerrainPalette(val label: String, private val grades: Map<Int, List<Grade>>) {
    TODAY("Today", emptyMap()),
    AUTUMN("Autumn", mapOf(
        Resource.WOOD to listOf(Grade.HueRotate(-38f), Grade.Saturate(1.05f)),
        Resource.BRICK to listOf(Grade.HueRotate(18f), Grade.Saturate(.75f), Grade.Brightness(1.05f)),
        Resource.SHEEP to listOf(Grade.HueRotate(-22f), Grade.Saturate(.85f)),
        Resource.WHEAT to listOf(Grade.Saturate(.75f), Grade.Brightness(.95f)),
        Resource.ORE to listOf(Grade.Sepia(.35f), Grade.Brightness(.95f)),
        Resource.NONE to listOf(Grade.Saturate(.8f)),
    )),
    MOORLAND("Moorland", mapOf(
        Resource.WOOD to listOf(Grade.HueRotate(32f), Grade.Brightness(.85f)),
        Resource.BRICK to listOf(Grade.HueRotate(-12f), Grade.Saturate(.6f), Grade.Brightness(.85f)),
        Resource.SHEEP to listOf(Grade.HueRotate(165f), Grade.Saturate(.55f)),
        Resource.WHEAT to listOf(Grade.HueRotate(-14f), Grade.Saturate(1.1f)),
        Resource.ORE to listOf(Grade.HueRotate(185f), Grade.Saturate(.6f)),
        Resource.NONE to listOf(Grade.Saturate(.6f), Grade.Brightness(1.05f)),
    )),
    OLD_MAP("Old map", mapOf(
        Resource.WOOD to listOf(Grade.Sepia(.55f), Grade.HueRotate(40f), Grade.Saturate(1.2f)),
        Resource.BRICK to listOf(Grade.Sepia(.6f), Grade.Saturate(1.3f)),
        Resource.SHEEP to listOf(Grade.Sepia(.5f), Grade.HueRotate(50f)),
        Resource.WHEAT to listOf(Grade.Sepia(.6f), Grade.Saturate(1.1f)),
        Resource.ORE to listOf(Grade.Sepia(.7f), Grade.HueRotate(160f), Grade.Saturate(.6f)),
        Resource.NONE to listOf(Grade.Sepia(.7f)),
    ));

    /** The grades for one terrain (a [Resource] constant, [Resource.NONE] for the desert), applied in order. */
    fun grades(terrain: Int): List<Grade> = grades[terrain].orEmpty()

    /** [faces] (terrain to art) graded; Today hands back the same map, so nothing is copied. */
    fun grade(faces: Map<Int, ImageBitmap>): Map<Int, ImageBitmap> =
        if (this == TODAY) faces else faces.mapValues { (t, img) -> grade(img, grades(t)) }

    /** The 3D tiles (Timber, Clay, Fleece, Grain, Stone, then the desert; each 8 tilt steps) graded. */
    fun grade3d(terrains: List<List<ImageBitmap>>): List<List<ImageBitmap>> =
        if (this == TODAY) terrains else terrains.mapIndexed { i, steps ->
            val g = grades(if (i < 5) i else Resource.NONE)
            steps.map { grade(it, g) }
        }

    /** The 3D board's tiles in this palette: each tile model named for its grade (`tile_grain_lod0@autumn`); Today keeps them. */
    internal fun live(placements: List<LivePlacement>): List<LivePlacement> =
        if (this == TODAY) placements else placements.map { p ->
            if (liveTerrain(p.model) != null) p.copy(model = "${p.model}@${name.lowercase()}") else p
        }

    /** One CSS filter function, as Chrome applies it to sRGB values (Filter Effects Level 1 matrices). */
    sealed class Grade {
        /** Row-major 3x3 on r, g, b. */
        abstract val m: FloatArray

        data class Saturate(val s: Float) : Grade() {
            override val m = floatArrayOf(
                .213f + .787f * s, .715f - .715f * s, .072f - .072f * s,
                .213f - .213f * s, .715f + .285f * s, .072f - .072f * s,
                .213f - .213f * s, .715f - .715f * s, .072f + .928f * s)
        }

        data class HueRotate(val deg: Float) : Grade() {
            override val m = Math.toRadians(deg.toDouble()).let { a -> floatArrayOf(cos(a).toFloat(), sin(a).toFloat()) }.let { (c, s) ->
                floatArrayOf(
                    .213f + c * .787f - s * .213f, .715f - c * .715f - s * .715f, .072f - c * .072f + s * .928f,
                    .213f - c * .213f + s * .143f, .715f + c * .285f + s * .140f, .072f - c * .072f - s * .283f,
                    .213f - c * .213f - s * .787f, .715f - c * .715f + s * .715f, .072f + c * .928f + s * .072f)
            }
        }

        data class Sepia(val amount: Float) : Grade() {
            override val m = (1f - amount.coerceIn(0f, 1f)).let { k ->
                floatArrayOf(
                    .393f + .607f * k, .769f - .769f * k, .189f - .189f * k,
                    .349f - .349f * k, .686f + .314f * k, .168f - .168f * k,
                    .272f - .272f * k, .534f - .534f * k, .131f + .869f * k)
            }
        }

        data class Brightness(val b: Float) : Grade() {
            override val m = floatArrayOf(b, 0f, 0f, 0f, b, 0f, 0f, 0f, b)
        }
    }

    companion object {
        /**
         * [pixels] (ARGB, not premultiplied) graded in place, clamping after each grade as a browser does between
         * filter functions; alpha is kept.
         */
        fun gradePixels(pixels: IntArray, grades: List<Grade>) {
            if (grades.isEmpty()) return
            for (i in pixels.indices) {
                val p = pixels[i]
                var r = (p shr 16 and 0xFF) / 255f; var g = (p shr 8 and 0xFF) / 255f; var b = (p and 0xFF) / 255f
                for (grade in grades) {
                    val m = grade.m
                    val r2 = m[0] * r + m[1] * g + m[2] * b
                    val g2 = m[3] * r + m[4] * g + m[5] * b
                    val b2 = m[6] * r + m[7] * g + m[8] * b
                    r = r2.coerceIn(0f, 1f); g = g2.coerceIn(0f, 1f); b = b2.coerceIn(0f, 1f)
                }
                pixels[i] = (p and 0xFF000000.toInt()) or ((r * 255).roundToInt() shl 16) or ((g * 255).roundToInt() shl 8) or (b * 255).roundToInt()
            }
        }

        /**
         * The 3D board's vertex colours graded in place: [colors] holds [components] floats per vertex (RGB or RGBA),
         * linear as glTF stores them. Each colour goes to sRGB, through the grades as on the painted tiles, and back.
         */
        fun gradeLinearRgb(colors: FloatArray, components: Int, grades: List<Grade>) {
            if (grades.isEmpty()) return
            for (i in colors.indices step components) {
                var r = toSrgb(colors[i]); var g = toSrgb(colors[i + 1]); var b = toSrgb(colors[i + 2])
                for (grade in grades) {
                    val m = grade.m
                    val r2 = m[0] * r + m[1] * g + m[2] * b
                    val g2 = m[3] * r + m[4] * g + m[5] * b
                    val b2 = m[6] * r + m[7] * g + m[8] * b
                    r = r2.coerceIn(0f, 1f); g = g2.coerceIn(0f, 1f); b = b2.coerceIn(0f, 1f)
                }
                colors[i] = toLinear(r); colors[i + 1] = toLinear(g); colors[i + 2] = toLinear(b)
            }
        }

        private fun toSrgb(c: Float): Float { val x = c.coerceIn(0f, 1f); return if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1 / 2.4f) - 0.055f }
        private fun toLinear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

        /** The board's 3D tile model names (`tile_<terrain>_lod<n>`) and the terrain each one shows. */
        private val LIVE_TERRAINS = mapOf("timber" to Resource.WOOD, "clay" to Resource.BRICK, "fleece" to Resource.SHEEP,
            "grain" to Resource.WHEAT, "stone" to Resource.ORE, "desert" to Resource.NONE)

        /** The terrain a 3D tile model shows, or null when [model] is no board tile. */
        fun liveTerrain(model: String): Int? =
            if (!model.startsWith("tile_")) null else LIVE_TERRAINS[model.removePrefix("tile_").substringBefore("_lod")]

        /** The grades a live model name asks for (`tile_grain_lod0@autumn`), or null for an ungraded model. */
        fun liveGrades(model: String): List<Grade>? {
            val palette = entries.firstOrNull { it.name.lowercase() == model.substringAfter('@', "") } ?: return null
            return palette.grades(liveTerrain(model.substringBefore('@')) ?: return null)
        }

        /** A GLB (a 3D tile) with every primitive's float COLOR_0 graded; the rest of the file is untouched. */
        fun gradeGlb(data: ByteArray, grades: List<Grade>): ByteArray {
            if (grades.isEmpty()) return data
            val out = data.copyOf()
            val buf = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val jsonLength = buf.getInt(12)
            val json = org.json.JSONObject(String(out, 20, jsonLength, Charsets.UTF_8))
            val bin = 20 + jsonLength + 8 // the BIN chunk's data, after its length and type
            val accessors = json.getJSONArray("accessors"); val views = json.getJSONArray("bufferViews")
            val done = HashSet<Int>()
            val meshes = json.getJSONArray("meshes")
            for (m in 0 until meshes.length()) {
                val primitives = meshes.getJSONObject(m).getJSONArray("primitives")
                for (p in 0 until primitives.length()) {
                    val attributes = primitives.getJSONObject(p).getJSONObject("attributes")
                    if (!attributes.has("COLOR_0")) continue
                    val index = attributes.getInt("COLOR_0")
                    if (!done.add(index)) continue
                    val a = accessors.getJSONObject(index)
                    if (a.getInt("componentType") != 5126) continue // only float colours, as the tiles are exported
                    val components = if (a.getString("type") == "VEC4") 4 else 3
                    val view = views.getJSONObject(a.getInt("bufferView"))
                    val stride = view.optInt("byteStride", components * 4)
                    val start = bin + view.optInt("byteOffset", 0) + a.optInt("byteOffset", 0)
                    val count = a.getInt("count")
                    val c = FloatArray(components)
                    for (v in 0 until count) {
                        val at = start + v * stride
                        for (k in 0 until components) c[k] = buf.getFloat(at + 4 * k)
                        gradeLinearRgb(c, components, grades)
                        for (k in 0 until 3) buf.putFloat(at + 4 * k, c[k])
                    }
                }
            }
            return out
        }

        private fun grade(img: ImageBitmap, grades: List<Grade>): ImageBitmap {
            if (grades.isEmpty()) return img
            val src = img.asAndroidBitmap()
            val px = IntArray(src.width * src.height)
            src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
            gradePixels(px, grades)
            return Bitmap.createBitmap(px, src.width, src.height, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }
}
