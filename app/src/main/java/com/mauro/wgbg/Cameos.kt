package com.mauro.wgbg

import java.text.Normalizer

// ------------------------------------------------------------------ player cameos (0.3.5, no Compose, unit tested)

/**
 * A painted portrait a seat can show instead of a plain initial (0.3.5, style B: Mauro's pick, 2026-10-21). [id] names
 * the drawable `cameo_<id>`; a theme's cameos belong to one of its names ([theme], [name]), the rest are for anyone.
 */
data class Cameo(val id: String, val label: String, val theme: NameTheme? = null, val name: String? = null)

object Cameos {
    private val PREFIX = mapOf(NameTheme.ENGLISH_QUEENS to "eq", NameTheme.FRENCH_QUEENS to "fq",
        NameTheme.OLD_TRADES to "ot", NameTheme.JAPANESE_EMPERORS to "je")

    /** Cameos for anyone (your seat, or a bot that wants one), not tied to a theme name. */
    val OTHERS = listOf("Fox", "Owl", "Stag", "Sloop", "Lighthouse", "Navigator").map { Cameo(slug(it), it) }

    /**
     * The art in the app, by id: all four themes and the six for anyone (Codex's grids, cut 1:1). A name without art
     * shows its initial. Listed by hand so resource shrinking keeps them; CameosTest checks the drawables.
     */
    val ART: Map<String, Int> = mapOf(
        "eq_matilda" to R.drawable.cameo_eq_matilda, "eq_eleanor" to R.drawable.cameo_eq_eleanor,
        "eq_isabella" to R.drawable.cameo_eq_isabella, "eq_philippa" to R.drawable.cameo_eq_philippa,
        "eq_margaret" to R.drawable.cameo_eq_margaret, "eq_catherine" to R.drawable.cameo_eq_catherine,
        "eq_mary_i" to R.drawable.cameo_eq_mary_i, "eq_elizabeth_i" to R.drawable.cameo_eq_elizabeth_i,
        "eq_mary_ii" to R.drawable.cameo_eq_mary_ii, "eq_anne" to R.drawable.cameo_eq_anne,
        "eq_charlotte" to R.drawable.cameo_eq_charlotte, "eq_victoria" to R.drawable.cameo_eq_victoria,
        "fq_alienor" to R.drawable.cameo_fq_alienor, "fq_blanche" to R.drawable.cameo_fq_blanche,
        "fq_jeanne" to R.drawable.cameo_fq_jeanne, "fq_clemence" to R.drawable.cameo_fq_clemence,
        "fq_isabeau" to R.drawable.cameo_fq_isabeau, "fq_anne" to R.drawable.cameo_fq_anne,
        "fq_catherine" to R.drawable.cameo_fq_catherine, "fq_marguerite" to R.drawable.cameo_fq_marguerite,
        "fq_marie" to R.drawable.cameo_fq_marie, "fq_louise" to R.drawable.cameo_fq_louise,
        "fq_josephine" to R.drawable.cameo_fq_josephine, "fq_eugenie" to R.drawable.cameo_fq_eugenie,
        "ot_cooper" to R.drawable.cameo_ot_cooper, "ot_chandler" to R.drawable.cameo_ot_chandler,
        "ot_tanner" to R.drawable.cameo_ot_tanner, "ot_fletcher" to R.drawable.cameo_ot_fletcher,
        "ot_mason" to R.drawable.cameo_ot_mason, "ot_weaver" to R.drawable.cameo_ot_weaver,
        "ot_brewer" to R.drawable.cameo_ot_brewer, "ot_miller" to R.drawable.cameo_ot_miller,
        "ot_thatcher" to R.drawable.cameo_ot_thatcher, "ot_potter" to R.drawable.cameo_ot_potter,
        "je_jimmu" to R.drawable.cameo_je_jimmu, "je_suiko" to R.drawable.cameo_je_suiko,
        "je_tenmu" to R.drawable.cameo_je_tenmu, "je_jito" to R.drawable.cameo_je_jito,
        "je_shomu" to R.drawable.cameo_je_shomu, "je_koken" to R.drawable.cameo_je_koken,
        "je_kanmu" to R.drawable.cameo_je_kanmu, "je_saga" to R.drawable.cameo_je_saga,
        "je_daigo" to R.drawable.cameo_je_daigo, "je_go_daigo" to R.drawable.cameo_je_go_daigo,
        "je_meiji" to R.drawable.cameo_je_meiji, "je_taisho" to R.drawable.cameo_je_taisho,
        "fox" to R.drawable.cameo_fox, "owl" to R.drawable.cameo_owl, "stag" to R.drawable.cameo_stag,
        "sloop" to R.drawable.cameo_sloop, "lighthouse" to R.drawable.cameo_lighthouse, "navigator" to R.drawable.cameo_navigator,
    )

    val PAINTED: Set<String> get() = ART.keys

    /** Every cameo with art, theme by theme in name order, then the others: the picker's grid. */
    val all: List<Cameo> get() = NameTheme.entries.flatMap { t -> t.names.map { Cameo(forName(t, it.name)!!, it.name, t, it.name) } }
        .plus(OTHERS).filter { it.id in PAINTED }

    fun byId(id: String?): Cameo? = all.firstOrNull { it.id == id }

    /** The id of [name]'s cameo in [theme], painted or not (null under Colours or for a name the theme lacks). */
    fun forName(theme: NameTheme, name: String?): String? =
        PREFIX[theme]?.takeIf { theme.who(name) != null }?.let { "${it}_${slug(name!!)}" }

    /** "Elizabeth I" -> "elizabeth_i", "Jitō" -> "jito": drawable-safe. */
    fun slug(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        .lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    /**
     * The cameo each seat shows, in seat order: the one picked for it if it has art and no earlier pick claimed it, else
     * the cameo of the seat's theme name ([names]) if painted and nobody has it, else null (the initial, or your photo). [takes]
     * false: nobody sits there. Works for any number of seats.
     */
    fun resolve(picked: List<String?>, names: List<String?>, theme: NameTheme, takes: List<Boolean>): List<String?> {
        require(picked.size == takes.size && names.size == takes.size)
        val out = MutableList<String?>(picked.size) { null }
        val used = mutableSetOf<String>()
        for (i in picked.indices) if (takes[i] && picked[i] in PAINTED && used.add(picked[i]!!)) out[i] = picked[i]
        for (i in picked.indices) if (takes[i] && out[i] == null)
            out[i] = forName(theme, names[i])?.takeIf { it in PAINTED && used.add(it) }
        return out
    }
}
