package com.mauro.wgbg

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

/** One tune in the set list (#17, #58): its title and its recording in res/raw (tools/make_sounds.py). */
data class Song(val title: String, val res: Int)

/**
 * Plays the set list round and round at the Music volume (#58). Volume 0 stops it; [pause] and [resume] follow the app
 * going to the background and back; [hold] is the player's own Pause button, which stays paused across both. [play]
 * jumps to a song, [next] skips. Any player error just stops the music: it never takes the game down.
 */
class MusicPlayer(private val context: Context, private val songs: List<Song>) : AutoCloseable {
    private var player: MediaPlayer? = null
    private var index = 0
    private var volume = 0f
    private var paused = false
    private var held = false
    /** Bumped by every start and stop: a song still loading when it changes is let go instead of played. */
    private var generation = 0
    private var loading = false
    private var closed = false
    private val main = Handler(Looper.getMainLooper())
    /** Told the index of each song as it starts. */
    var onSong: (Int) -> Unit = {}
    /** Whether song i is ticked in the set list (#131); unticked songs are passed over, unless tapped. */
    var enabled: (Int) -> Boolean = { true }
    /** The song playing now, or null. */
    var playing: Song? = null; private set

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        if (volume <= 0f) stop() else { player?.setVolume(musicLevel(volume), musicLevel(volume)); if (player == null && !paused && !held) start() }
    }

    /** The Pause button: [on] pauses where it is, off carries on from there. */
    fun hold(on: Boolean) {
        held = on
        if (on) runCatching { player?.pause() } else if (!paused && volume > 0f) { if (player == null) start() else runCatching { player?.start() } }
    }

    /** Plays song [i] of the set list from the start (and lets go of Pause). */
    fun play(i: Int) {
        if (songs.isEmpty()) return
        stop(); index = ((i % songs.size) + songs.size) % songs.size; held = false
        if (!paused && volume > 0f) start(tapped = true) // a tapped song plays even if it is unticked
    }

    /** Skips to the next song. */
    fun next() { val n = nextOn(index + 1, songs.size, enabled); if (n < 0) { stop(); return }; play(n) }

    fun pause() { paused = true; runCatching { player?.pause() }; log("pause") }
    fun resume() {
        paused = false
        if (held) return
        if (volume > 0f) { if (player == null) start() else runCatching { player?.start() }.onFailure { log("resume failed: $it") } }
        log("resume volume=$volume player=${player != null}")
    }
    private fun log(m: String) { runCatching { android.util.Log.i("WGBG-MUSIC", m) } }

    /**
     * Loads the song on a background thread (#111: MediaPlayer.create reads and prepares the file, which held up the
     * main thread as a game opened), then starts it on the main thread if it's still wanted.
     */
    private fun start(tapped: Boolean = false) {
        if (songs.isEmpty() || closed || (loading && !tapped)) return
        index = if (tapped) index else nextOn(index % songs.size, songs.size, enabled).takeIf { it >= 0 } ?: return
        val song = songs[index]
        val gen = ++generation; loading = true
        Thread({
            val p = runCatching {
                MediaPlayer.create(context, song.res, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(), 0)
            }.getOrNull()
            main.post {
                if (gen == generation) loading = false
                if (p == null) return@post
                if (!musicStillWanted(gen, generation, paused, held, volume, closed)) { runCatching { p.release() }; return@post }
                runCatching {
                    p.setVolume(musicLevel(volume), musicLevel(volume))
                    p.setOnCompletionListener { it.release(); player = null; playing = null; index++; if (!paused && !held && volume > 0f) start() }
                    p.setOnErrorListener { mp, _, _ -> mp.release(); player = null; playing = null; true }
                    p.start(); player = p; playing = song; onSong(index)
                }.onFailure { runCatching { p.release() } }
            }
        }, "music-load").apply { isDaemon = true }.start()
    }

    private fun stop() { generation++; loading = false; runCatching { player?.release() }; player = null; playing = null }

    override fun close() { closed = true; stop() }
}

/**
 * What the player hears for a Music slider value: half of it, −6 dB (Mauro, 2026-10-13: "about half current scale,
 * it was loud"). The tune files stay full-scale so this is the one place music level is set.
 */
internal const val MUSIC_GAIN = 0.5f
internal fun musicLevel(slider: Float) = slider.coerceIn(0f, 1f) * MUSIC_GAIN

/** The first ticked song from [from] on (wrapping round [size]), or -1 when every song is unticked (#131). */
internal fun nextOn(from: Int, size: Int, on: (Int) -> Boolean): Int {
    if (size <= 0) return -1
    for (k in 0 until size) { val i = ((from + k) % size + size) % size; if (on(i)) return i }
    return -1
}

/** Whether a song that finished loading should play: nothing newer was asked for, and the music is on and not paused. */
internal fun musicStillWanted(gen: Int, current: Int, paused: Boolean, held: Boolean, volume: Float, closed: Boolean): Boolean =
    gen == current && !paused && !held && volume > 0f && !closed
