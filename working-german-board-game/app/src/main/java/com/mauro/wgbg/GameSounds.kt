package com.mauro.wgbg

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Short game sounds (#44, #57): our own synthesised dice and chime (tools/make_sounds.py), and CC0 clips for the
 * clunk, bell and horn (tools/import_sounds.sh, CREDITS.md). Each is a different kind of sound, so you know which it
 * is without looking. Volume follows Settings > Sound.
 */
class GameSounds(context: Context) : AutoCloseable {
    private val pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
    ).build()
    private val chime = pool.load(context, R.raw.trade_chime, 1)
    private val dice = pool.load(context, R.raw.dice_roll, 1)
    private val clunk = pool.load(context, R.raw.event_clunk, 1)
    private val bell = pool.load(context, R.raw.turn_bell, 1)
    private val horn = pool.load(context, R.raw.raiders_horn, 1)

    /** A trade offer asks for cards you hold. */
    fun tradeChime(volume: Float) { if (volume > 0f) pool.play(chime, volume, volume, 1, 0, 1f) }

    /** Dice shaken and thrown onto the table (every roll; plays with Animations off too). */
    fun diceRoll(volume: Float) { if (volume > 0f) pool.play(dice, volume, volume, 1, 0, 1f) }

    /**
     * The Raiders' ship lands face up on the event die: a wooden clunk, a little after the dice settle (Mauro, 0.3.4:
     * the synthesised one sounded like a happy ding, "not ok").
     */
    fun eventClunk(volume: Float) { if (volume > 0f) pool.play(clunk, volume, volume, 1, 0, 1f) }

    /** It's your turn: one bell strike (Settings > Sound > Your turn). */
    fun turnBell(volume: Float) { if (volume > 0f) pool.play(bell, volume * 0.9f, volume * 0.9f, 1, 0, 1f) }

    /** The Raiders are close: a horn blast (Settings > Sound > Raiders close). */
    fun raidersHorn(volume: Float) { if (volume > 0f) pool.play(horn, volume * 0.9f, volume * 0.9f, 1, 0, 1f) }

    override fun close() = pool.release()
}

/** Steps left before the Raiders' ship reaches the island, or null outside Age of Chivalry. */
fun raidersStepsLeft(s: wgbg.engine.GameState): Int? = s.chivalry?.let { wgbg.engine.ChivalryPiece.BARBARIAN_TRACK - it.barbarianPos }

/**
 * True when the Raiders' ship has just moved closer, from [before] to [after] steps left, and is now [warnAt] steps away
 * or nearer: the horn sounds once per move, not again while it waits, and not for the attack itself (0 left).
 */
fun raidersWarn(before: Int?, after: Int?, warnAt: Int): Boolean = before != null && after != null && after < before && after in 1..warnAt

/**
 * Changes each time it becomes [me]'s turn, null while it isn't (or the game is over): the turn bell's key. Setup turns
 * count too ([wgbg.engine.GameState.turn] stays 0 through setup; its step moves on).
 */
fun myTurnKey(s: wgbg.engine.GameState, me: Int): Int? =
    if (me >= 0 && !s.isOver && s.currentPlayer == me) (s.turn * 32 + s.setupStep) * 8 + me else null

/** Haptics you feel through the phone (#57). */
object Haptics {
    /** The Raiders' ship came up: a light wooden clonk you feel. */
    fun raidersClonk(context: Context) = play(context, CLONK_TIMINGS, CLONK_AMPLITUDES)

    /** It's your turn: two firm buzzes, unlike the dice's rattle and the clonk's single knock. */
    fun turnBuzz(context: Context) = play(context, TURN_TIMINGS, TURN_AMPLITUDES)

    /**
     * The dice rattle, in step with the dice sound: quick soft clicks while they're shaken, then each die's bounces on
     * the table, getting softer and closer together.
     */
    val DICE_TIMINGS = longArrayOf(0, 12, 30, 10, 28, 12, 32, 10, 26, 12, 30, 10, 70, 28, 95, 22, 62, 16, 40, 12, 26, 9)
    val DICE_AMPLITUDES = intArrayOf(0, 70, 0, 60, 0, 75, 0, 60, 0, 70, 0, 55, 0, 255, 0, 170, 0, 110, 0, 70, 0, 45)

    /** The Raiders' ship on the event die: one solid knock and a faint bounce, like a wooden die landing (Mauro). */
    val CLONK_TIMINGS = longArrayOf(0, 38, 90, 12)   // Mauro: 10 ms longer
    val CLONK_AMPLITUDES = intArrayOf(0, 200, 0, 60)

    /** Your turn: two long buzzes. */
    val TURN_TIMINGS = longArrayOf(0, 180, 120, 180)
    val TURN_AMPLITUDES = intArrayOf(0, 230, 0, 230)

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)

    /** Your own roll: the rattle (a plain buzz on phones without strength control). */
    fun diceRattle(context: Context) = play(context, DICE_TIMINGS, DICE_AMPLITUDES)

    private fun play(context: Context, timings: LongArray, amplitudes: IntArray) {
        val v = vibrator(context)?.takeIf { it.hasVibrator() } ?: return
        runCatching {
            v.vibrate(if (v.hasAmplitudeControl()) VibrationEffect.createWaveform(timings, amplitudes, -1)
                else VibrationEffect.createWaveform(timings, -1))
        }
    }
}
