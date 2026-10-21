package com.mauro.wgbg

// ------------------------------------------------------------------ what the bots are called (UI-free, unit tested)

/** A name a bot can take, with a line on who she was (or what the trade did), shown under it in the picker. */
data class ThemeName(val name: String, val who: String)

/**
 * What the other seats are called (Mauro, 0.3.4: "I would also like to name my AI", then "English Queens and French ones
 * option of theming for names"). [COLOURS] is how it always was: each player goes by their colour. A theme gives every
 * other seat one of its names instead; the colour stays the seat's colour. All names are historical or our own: no
 * living people and none of the original game's names (OurNamesTest checks every list).
 */
enum class NameTheme(val label: String, val names: List<ThemeName>) {
    COLOURS("Colours", emptyList()),
    ENGLISH_QUEENS("English queens", listOf(
        ThemeName("Matilda", "Empress Matilda, Henry I's daughter, Lady of the English in the 1140s"),
        ThemeName("Eleanor", "Eleanor of Aquitaine, Henry II's queen, and queen of France before that"),
        ThemeName("Isabella", "Isabella of France, Edward II's queen, the She-Wolf who deposed him"),
        ThemeName("Philippa", "Philippa of Hainault, Edward III's queen"),
        ThemeName("Margaret", "Margaret of Anjou, Henry VI's queen, who led the Lancastrians"),
        ThemeName("Catherine", "Catherine of Aragon, Henry VIII's first queen"),
        ThemeName("Mary I", "Queen from 1553 to 1558"),
        ThemeName("Elizabeth I", "Queen from 1558 to 1603"),
        ThemeName("Mary II", "Queen with William III, 1689 to 1694"),
        ThemeName("Anne", "Queen Anne, 1702 to 1714, the first monarch of Great Britain"),
        ThemeName("Charlotte", "Charlotte of Mecklenburg-Strelitz, George III's queen"),
        ThemeName("Victoria", "Queen from 1837 to 1901"),
    )),
    FRENCH_QUEENS("French queens", listOf(
        ThemeName("Aliénor", "Eleanor of Aquitaine, Louis VII's queen"),
        ThemeName("Blanche", "Blanche of Castile, Louis VIII's queen, regent for Saint Louis"),
        ThemeName("Jeanne", "Jeanne of Navarre, Philip IV's queen, queen of Navarre in her own right"),
        ThemeName("Clémence", "Clémence of Hungary, Louis X's queen"),
        ThemeName("Isabeau", "Isabeau of Bavaria, Charles VI's queen"),
        ThemeName("Anne", "Anne of Austria, Louis XIII's queen, regent for Louis XIV"),
        ThemeName("Catherine", "Catherine de' Medici, Henri II's queen, regent for three sons"),
        ThemeName("Marguerite", "Marguerite de Valois, la reine Margot, Henri IV's first queen"),
        ThemeName("Marie", "Marie Antoinette, Louis XVI's queen"),
        ThemeName("Louise", "Louise of Lorraine, Henri III's queen"),
        ThemeName("Joséphine", "Empress Joséphine, Napoleon's first wife"),
        ThemeName("Eugénie", "Empress Eugénie, Napoleon III's wife, the last empress of France"),
    )),
    OLD_TRADES("Old trades", listOf(
        ThemeName("Cooper", "Made barrels and casks"),
        ThemeName("Chandler", "Made and sold candles"),
        ThemeName("Tanner", "Turned hides into leather"),
        ThemeName("Fletcher", "Made arrows"),
        ThemeName("Mason", "Cut and laid stone"),
        ThemeName("Weaver", "Wove cloth on a loom"),
        ThemeName("Brewer", "Brewed ale"),
        ThemeName("Miller", "Ground grain into flour"),
        ThemeName("Thatcher", "Roofed houses with straw and reed"),
        ThemeName("Potter", "Threw pots and fired them"),
    )),
    JAPANESE_EMPERORS("Japanese emperors", listOf(
        ThemeName("Jimmu", "The legendary first emperor, said to have founded Japan in 660 BC"),
        ThemeName("Suiko", "The first empress regnant on record, 593 to 628"),
        ThemeName("Tenmu", "Emperor 673 to 686, who ordered the first histories of Japan"),
        ThemeName("Jitō", "Empress regnant 690 to 697, who built the capital Fujiwara-kyō"),
        ThemeName("Shōmu", "Emperor 724 to 749, who built Tōdai-ji and its Great Buddha"),
        ThemeName("Kōken", "Empress regnant twice, 749 to 758 and 764 to 770"),
        ThemeName("Kanmu", "Emperor 781 to 806, who moved the capital to Heian-kyō (Kyoto)"),
        ThemeName("Saga", "Emperor 809 to 823, poet and one of the Three Brushes of calligraphy"),
        ThemeName("Daigo", "Emperor 897 to 930, who ordered the first imperial poetry anthology"),
        ThemeName("Go-Daigo", "Emperor 1318 to 1339, who overthrew the Kamakura shogunate"),
        ThemeName("Meiji", "Emperor 1867 to 1912, whose restoration opened and modernised Japan"),
        ThemeName("Taishō", "Emperor 1912 to 1926"),
    ));

    /** Who [name] was in this theme, or null if the theme has no such name. */
    fun who(name: String?): String? = names.firstOrNull { it.name == name }?.who

    /**
     * The name each seat goes by, in seat order: the one picked for it if this theme has it and no other seat claimed
     * it first, else the first of the theme's names nobody has. Null for a seat that takes no theme name ([takes]
     * false: you, or nobody sitting there), for every seat under [COLOURS], and once a theme runs out of names. Works
     * for any number of seats (never assumes 4).
     */
    fun resolve(picked: List<String?>, takes: List<Boolean>): List<String?> {
        require(picked.size == takes.size)
        val out = MutableList<String?>(picked.size) { null }
        if (this == COLOURS) return out
        val used = mutableSetOf<String>()
        // Picks first, so a later seat's pick is never taken by an earlier seat's default.
        for (i in picked.indices) if (takes[i] && who(picked[i]) != null && used.add(picked[i]!!)) out[i] = picked[i]
        for (i in picked.indices) if (takes[i] && out[i] == null)
            out[i] = names.firstOrNull { it.name !in used }?.name?.also { used += it }
        return out
    }
}

/** A player's name by colour when their seat has no theme name: the game's colours ([GameColours]), then "Player 5" and on. */
fun colourName(player: Int): String = PLAYER_NAMES.getOrElse(player) { "Player ${player + 1}" }
