package com.mauro.wgbg

import android.content.Context
import android.graphics.BitmapFactory
import com.google.android.filament.*
import com.google.android.filament.android.TextureHelper
import java.nio.ByteBuffer

/** Owned textures/materials for the MIT stylized-water adaptation; no scene-depth or reflection pass. */
internal class LiveWaterMaterial(private val engine: Engine, context: Context) : AutoCloseable {
    private val material: Material
    val instance: MaterialInstance
    private val ocean: Texture
    private val coast: Texture
    private var shownCoast: List<LiveCoastEdge>? = null

    init {
        val bytes = context.assets.open("filament/live_water.filamat").use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() }
        material = Material.Builder().payload(buffer, bytes.size).build(engine)
        instance = material.createInstance()
        val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.ocean_tile)
        ocean = Texture.Builder().width(bitmap.width).height(bitmap.height).levels(1)
            .sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.SRGB8_A8).build(engine)
        TextureHelper.setBitmap(engine, ocean, 0, bitmap)
        engine.flushAndWait(); bitmap.recycle()
        coast = Texture.Builder().width(LIVE_COAST_TEXTURE_SIZE).height(LIVE_COAST_TEXTURE_SIZE).levels(1)
            .sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.RGBA8).build(engine)
        instance.setParameter("ocean", ocean, TextureSampler(TextureSampler.MinFilter.LINEAR,
            TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.REPEAT))
        instance.setParameter("coast", coast, TextureSampler(TextureSampler.MinFilter.LINEAR,
            TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE))
    }

    fun update(edges: List<LiveCoastEdge>, sea: Sea?) {
        if (shownCoast !== edges) {
            val pixels = if (edges.isEmpty()) ByteArray(LIVE_COAST_TEXTURE_SIZE * LIVE_COAST_TEXTURE_SIZE * 4) { 255.toByte() }
                else liveCoastTexture(edges)
            val buffer = ByteBuffer.allocateDirect(pixels.size).apply { put(pixels); flip() }
            coast.setImage(engine, 0, Texture.PixelBufferDescriptor(buffer, Texture.Format.RGBA, Texture.Type.UBYTE))
            shownCoast = edges
        }
        instance.setParameter("time", liveWaterPhase(sea?.time ?: 0f))
        instance.setParameter("waveStrength", sea?.whitecapScale ?: 1f)
        instance.setParameter("foamStrength", sea?.whitecapAlpha ?: 1f)
        instance.setParameter("coastEnabled", if (edges.isEmpty()) 0f else 1f)
        instance.setParameter("boardSaturation", BOARD_SATURATION)
        instance.setParameter("boardWarmth", BOARD_WARMTH)
    }

    override fun close() {
        engine.destroyMaterialInstance(instance); engine.destroyMaterial(material)
        engine.destroyTexture(ocean); engine.destroyTexture(coast)
    }
}
