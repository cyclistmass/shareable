package com.mauro.wgbg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Texture size of a runtime number, px; the quad covers the whole plate ([NUMBER_TOKEN_R] each way). */
private const val LIVE_DIGIT_PX = 256

/**
 * A tiny glTF quad with a runtime text texture. Filament depth-tests it against the actual scenery. The number and
 * pips are drawn exactly as big, bold and low on the plate as the flat board's `drawNumberToken` (Mauro: "the numbers
 * got smaller again", "scale everything proportionally").
 */
internal fun liveNumberGlb(context: Context, number: Int): ByteBuffer {
    require(number in 2..12 && number != 7)
    val px = LIVE_DIGIT_PX.toFloat()
    val image = Bitmap.createBitmap(LIVE_DIGIT_PX, LIVE_DIGIT_PX, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(image)
    val perUnit = px / (2f * NUMBER_TOKEN_R)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = numberPaint(number).toArgb()
        textSize = NUMBER_TEXT * perUnit
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    // As on the flat board: the text's line box is raised by NUMBER_TEXT_RAISE of its height from the plate centre.
    val fm = paint.fontMetrics
    val c = px / 2f
    canvas.drawText(number.toString(), c, c - NUMBER_TEXT_RAISE * (fm.bottom - fm.top) - fm.top, paint)
    val pips = 6 - abs(7 - number)
    val plate = NUMBER_TOKEN_R * perUnit
    for (i in 0 until pips) canvas.drawCircle(c + (i - (pips - 1) / 2f) * 0.12f * plate, c + NUMBER_PIPS_DOWN * plate, 0.05f * plate, paint)
    val png = ByteArrayOutputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
    image.recycle()
    val pngSize = (png.size + 3) and -4
    val binary = ByteBuffer.allocate(92 + pngSize).order(ByteOrder.LITTLE_ENDIAN)
    val r = NUMBER_TOKEN_R
    for (v in floatArrayOf(-r, 0f, -r, -r, 0f, r, r, 0f, r, r, 0f, -r)) binary.putFloat(v)
    for (v in floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f, 1f, 0f)) binary.putFloat(v)
    for (i in intArrayOf(0, 1, 2, 0, 2, 3)) binary.putShort(i.toShort())
    binary.put(png)
    fun obj(s: String) = JSONObject(s)
    val gltf = JSONObject().apply {
        put("asset", obj("""{"version":"2.0","generator":"WGBG runtime number"}"""))
        put("extensionsUsed", JSONArray(listOf("KHR_materials_unlit")))
        put("buffers", JSONArray().put(obj("""{"byteLength":${binary.capacity()}}""")))
        put("bufferViews", JSONArray().put(obj("""{"buffer":0,"byteOffset":0,"byteLength":48,"target":34962}"""))
            .put(obj("""{"buffer":0,"byteOffset":48,"byteLength":32,"target":34962}"""))
            .put(obj("""{"buffer":0,"byteOffset":80,"byteLength":12,"target":34963}"""))
            .put(obj("""{"buffer":0,"byteOffset":92,"byteLength":${png.size}}""")))
        put("accessors", JSONArray().put(obj("""{"bufferView":0,"componentType":5126,"count":4,"type":"VEC3","min":[-$r,0,-$r],"max":[$r,0,$r]}"""))
            .put(obj("""{"bufferView":1,"componentType":5126,"count":4,"type":"VEC2"}"""))
            .put(obj("""{"bufferView":2,"componentType":5123,"count":6,"type":"SCALAR"}""")))
        put("images", JSONArray().put(obj("""{"bufferView":3,"mimeType":"image/png"}""")))
        put("samplers", JSONArray().put(obj("""{"magFilter":9729,"minFilter":9729,"wrapS":33071,"wrapT":33071}""")))
        put("textures", JSONArray().put(obj("""{"source":0,"sampler":0}""")))
        put("materials", JSONArray().put(obj("""{"name":"RuntimeDigits","alphaMode":"BLEND","extensions":{"KHR_materials_unlit":{}},"pbrMetallicRoughness":{"baseColorTexture":{"index":0},"metallicFactor":0,"roughnessFactor":1}}""")))
        put("meshes", JSONArray().put(obj("""{"primitives":[{"attributes":{"POSITION":0,"TEXCOORD_0":1},"indices":2,"material":0}]}""")))
        put("nodes", JSONArray().put(obj("""{"mesh":0}""")))
        put("scenes", JSONArray().put(obj("""{"nodes":[0]}"""))); put("scene", 0)
    }
    // Android JSONObject escapes '/', while cgltf's MIME lookup uses the literal token.
    val json = gltf.toString().replace("\\/", "/").toByteArray(Charsets.UTF_8)
    val jsonSize = (json.size + 3) and -4
    return ByteBuffer.allocateDirect(28 + jsonSize + binary.capacity()).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(0x46546C67); putInt(2); putInt(capacity())
        putInt(jsonSize); putInt(0x4E4F534A); put(json)
        repeat(jsonSize - json.size) { put(32.toByte()) }
        putInt(binary.capacity()); putInt(0x004E4942); put(binary.array()); flip()
    }
}
