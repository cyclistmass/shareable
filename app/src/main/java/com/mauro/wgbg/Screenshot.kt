package com.mauro.wgbg

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import java.io.ByteArrayOutputStream

/**
 * Screenshots for "Report a bug or idea" (#128): the game as it was when the report opened, and optionally a picture
 * picked from the gallery (Mauro: "can it take a screen shot or add my last shot if i pick it").
 */
object Screenshot {
    /** Largest edge of an uploaded shot, and the most base64 characters one may take (the Firestore rule's cap). */
    const val MAX_EDGE = 1280
    const val MAX_CHARS = 450_000

    /**
     * The whole window with the 3D board's surface underneath, or null when the copy fails. [done] runs on the main
     * thread. A window copy alone leaves the 3D board black: its SurfaceView is a separate layer (player reports on
     * 0.3.1 showed only the ports and road hints), so each visible SurfaceView is copied too and drawn first.
     */
    fun capture(activity: Activity, done: (Bitmap?) -> Unit) {
        val view = activity.window.decorView
        if (view.width <= 0 || view.height <= 0) return done(null)
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val surfaces = surfaceViews(view).filter { it.isShown && it.width > 0 && it.height > 0 && it.holder.surface.isValid }
        runCatching {
            PixelCopy.request(activity.window, bmp, { r ->
                when {
                    r != PixelCopy.SUCCESS -> done(null)
                    surfaces.isEmpty() -> done(bmp)
                    else -> underlay(bmp, surfaces, done)
                }
            }, Handler(Looper.getMainLooper()))
        }.onFailure { done(null) }
    }

    /** [window] drawn over copies of [surfaces] at their places in it; a surface that fails to copy is left out. */
    private fun underlay(window: Bitmap, surfaces: List<SurfaceView>, done: (Bitmap?) -> Unit) {
        val out = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        var left = surfaces.size
        fun one() { if (--left == 0) { canvas.drawBitmap(window, 0f, 0f, null); done(out) } }
        for (s in surfaces) {
            val shot = Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ARGB_8888)
            val at = IntArray(2).also { s.getLocationInWindow(it) }
            runCatching {
                PixelCopy.request(s, shot, { r ->
                    if (r == PixelCopy.SUCCESS) canvas.drawBitmap(shot, at[0].toFloat(), at[1].toFloat(), null)
                    one()
                }, Handler(Looper.getMainLooper()))
            }.onFailure { one() }
        }
    }

    private fun surfaceViews(v: View): List<SurfaceView> = when (v) {
        is SurfaceView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { surfaceViews(v.getChildAt(it)) }
        else -> emptyList()
    }

    /** A picture from the gallery, decoded at about [MAX_EDGE] on its long side, or null. */
    fun load(context: Context, uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }.getOrNull()

    /** [bmp] as a base64 JPEG no longer than [MAX_CHARS]: scaled to [MAX_EDGE], then lower quality until it fits. */
    fun jpegBase64(bmp: Bitmap): String? {
        val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(bmp.width, bmp.height))
        var img = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        for (attempt in 0 until 6) {
            for (q in intArrayOf(80, 65, 50, 35)) {
                val out = ByteArrayOutputStream()
                img.compress(Bitmap.CompressFormat.JPEG, q, out)
                val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                if (b64.length <= MAX_CHARS) return b64
            }
            img = Bitmap.createScaledBitmap(img, img.width * 3 / 4, img.height * 3 / 4, true)
        }
        return null
    }
}
