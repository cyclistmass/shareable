package com.mauro.wgbg

import kotlin.math.pow
import kotlin.math.sqrt

// ------------------------------------------------------------------ what colour each seat plays (UI-free, unit tested)

/**
 * A colour a seat can play in (0.3.4, Mauro: "random or I can pick the color when I make the game"). Today's four, then
 * the three tried for the third seat (Gold, Violet, Charcoal; they go by Yellow, Purple and Black). [argb] paints the
 * pieces; [text] is the lighter shade that reads on the dark wood (a seat's title, its name in the picker).
 */
enum class SeatColour(val label: String, val argb: Long, val text: Long) {
    RED("Red", 0xFFFF5C59, 0xFFEF8A8A),
    BLUE("Blue", 0xFF3FB4EE, 0xFF7FB0F0),
    ORANGE("Orange", 0xFFFF9B3E, 0xFFF7A24B),
    WHITE("White", 0xFFF5F5F5, 0xFFF5F5F5),
    YELLOW("Yellow", 0xFFE0A800, 0xFFF0C850),
    PURPLE("Purple", 0xFF6A3FB5, 0xFFB9A0F0),
    BLACK("Black", 0xFF2B2B30, 0xFFB8B8C0);

    /** Too close to [other] for someone (normal sight or colour-blind), so the two seats would blur. */
    fun clashes(other: SeatColour) = other != this && apart(this, other) < APART

    companion object {
        /**
         * The colours by player when nobody picks (Mauro, 0.3.4): Red, Blue, Purple, White, no two alike for anyone. Orange
         * (too near Red with deuteranopia) is no longer a default; then Yellow, Orange, Black for games with more seats.
         */
        val DEFAULT: List<SeatColour> = listOf(RED, BLUE, PURPLE, WHITE) + listOf(YELLOW, ORANGE, BLACK)

        /** At least this CIELAB ΔE between two seats in every sight (SeatColoursTest, from 36's colour options). */
        const val APART = 30.0

        fun of(name: String?): SeatColour? = entries.firstOrNull { it.name == name }

        // Machado 2009, full severity: how each colour looks with deuteranopia, protanopia and tritanopia.
        private val sights = listOf(
            null,
            doubleArrayOf(0.367322, 0.860646, -0.227968, 0.280085, 0.672501, 0.047413, -0.011820, 0.042940, 0.968881),
            doubleArrayOf(0.152286, 1.052583, -0.204868, 0.114503, 0.786281, 0.099216, -0.003882, -0.048116, 1.051998),
            doubleArrayOf(1.255528, -0.076749, -0.178779, -0.078411, 0.930809, 0.147602, 0.004733, 0.691367, 0.303900),
        )

        private fun lin(c: Long): Double { val v = c / 255.0; return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4) }

        private fun lab(argb: Long, m: DoubleArray?): DoubleArray {
            val c = doubleArrayOf(lin(argb shr 16 and 0xFF), lin(argb shr 8 and 0xFF), lin(argb and 0xFF))
            val v = if (m == null) c else DoubleArray(3) { i -> (m[3 * i] * c[0] + m[3 * i + 1] * c[1] + m[3 * i + 2] * c[2]).coerceIn(0.0, 1.0) }
            val x = (0.4124 * v[0] + 0.3576 * v[1] + 0.1805 * v[2]) / 0.95047
            val y = 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2]
            val z = (0.0193 * v[0] + 0.1192 * v[1] + 0.9505 * v[2]) / 1.08883
            fun f(t: Double) = if (t > 0.008856) t.pow(1.0 / 3) else 7.787 * t + 16.0 / 116
            return doubleArrayOf(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
        }

        /** The smallest ΔE between [a] and [b] over normal sight and the three kinds of colour blindness. */
        fun apart(a: SeatColour, b: SeatColour): Double = sights.minOf { m ->
            val p = lab(a.argb, m); val q = lab(b.argb, m)
            sqrt((0..2).sumOf { (p[it] - q[it]).pow(2) })
        }

        /** The pairs of [colours] (by index) that clash. */
        fun clashes(colours: List<SeatColour?>): List<Pair<Int, Int>> = buildList {
            for (i in colours.indices) for (j in i + 1 until colours.size) {
                val a = colours[i] ?: continue; val b = colours[j] ?: continue
                if (a.clashes(b)) add(i to j)
            }
        }

        /**
         * [n] different colours in a random order, from the sets where no two clash. Past 5 there is no such set in
         * this palette, so it draws from the sets whose closest pair is furthest apart: never two the same.
         */
        fun draw(n: Int, random: kotlin.random.Random): List<SeatColour> {
            require(n in 0..entries.size) { "$n seats, ${entries.size} colours" }
            val sets = combinations(entries, n)
            fun worst(s: List<SeatColour>) = s.indices.minOfOrNull { i -> (i + 1 until s.size).minOfOrNull { j -> apart(s[i], s[j]) } ?: Double.MAX_VALUE } ?: Double.MAX_VALUE
            val clean = sets.filter { clashes(it).isEmpty() }
            val pool = clean.ifEmpty { val best = sets.maxOf(::worst); sets.filter { worst(it) == best } }
            return pool.random(random).shuffled(random)
        }

        private fun <T> combinations(items: List<T>, k: Int): List<List<T>> =
            if (k == 0) listOf(emptyList()) else items.indices.flatMap { i -> combinations(items.drop(i + 1), k - 1).map { listOf(items[i]) + it } }

        /**
         * The colour each seat plays in, in seat order: the one picked for it if no earlier seat has it, else the first
         * [DEFAULT] colour nobody has, taken in seat order (so with no picks player p is DEFAULT[p], as before 0.3.4).
         * Null for a seat nobody sits in ([plays] false). Works for any number of seats.
         */
        fun resolve(picked: List<SeatColour?>, plays: List<Boolean>): List<SeatColour?> {
            require(picked.size == plays.size && plays.size <= entries.size)
            val out = MutableList<SeatColour?>(picked.size) { null }
            val used = mutableSetOf<SeatColour>()
            // Picks first, so a later seat's pick is never taken by an earlier seat's default.
            for (i in picked.indices) if (plays[i] && picked[i] != null && used.add(picked[i]!!)) out[i] = picked[i]
            for (i in picked.indices) if (plays[i] && out[i] == null) out[i] = DEFAULT.first { it !in used }.also { used += it }
            return out
        }
    }
}
