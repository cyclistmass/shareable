package com.mauro.wgbg

/**
 * The art sets a piece can come from. Space (#88) joins the picker when its art is wired in; until then
 * [ArtObject.sets] doesn't offer it, so nobody can pick a set that draws nothing.
 */
enum class ArtSet(val label: String) { DEFAULT("Default"), PIRATE("Pirate"), SPACE("Space") }

/**
 * What the player can theme one at a time on the Inventory screen (Mauro: "tap in inventory, with theme groupings by
 * default"). Each lists the sets that have art for it today, in tap order.
 */
enum class ArtObject(val label: String, val sets: List<ArtSet>) {
    /** Homesteads, towns, roads, Ramparts and Capitals. */
    BUILDINGS("Buildings", ArtSet.entries),
    /** Knight pieces, their 3D models and the knight cards in the guide. */
    KNIGHTS("Knights", ArtSet.entries),
    DICE("Dice", ArtSet.entries),
    /** The Age of Chivalry Merchant: the generic pawn until a theme supplies its own (#112). */
    MERCHANT("Trader", listOf(ArtSet.DEFAULT, ArtSet.SPACE)),
    BOARD("Board", listOf(ArtSet.DEFAULT, ArtSet.SPACE)),
    BANDIT("Bandit", listOf(ArtSet.DEFAULT, ArtSet.SPACE)),
    CARDS("Cards", listOf(ArtSet.DEFAULT, ArtSet.SPACE));

    /** The set this object uses under [theme]: the theme's own when it has one, else Default. */
    fun under(theme: ArtSet): ArtSet = if (theme in sets) theme else sets.first()
}

/**
 * The chosen [theme] plus any pieces the player swapped to another set. Picking a theme regroups everything under it;
 * [reset] drops the swaps ("default reset if they mess it up").
 */
data class ArtChoice(val theme: ArtSet = ArtSet.DEFAULT, val swaps: Map<ArtObject, ArtSet> = emptyMap()) {
    companion object {
        /** The themes the Theme row offers: those at least one object has art for. */
        val themes: List<ArtSet> get() = ArtSet.entries.filter { s -> ArtObject.entries.any { s in it.sets } }
    }

    operator fun get(o: ArtObject): ArtSet = swaps[o]?.takeIf { it in o.sets } ?: o.under(theme)

    fun pirate(o: ArtObject) = this[o] == ArtSet.PIRATE
    fun space(o: ArtObject) = this[o] == ArtSet.SPACE

    /** Whether [o] differs from what the theme would give it. */
    fun swapped(o: ArtObject) = this[o] != o.under(theme)

    /** The next set for [o], wrapping round: one tap on its piece in the Inventory. */
    fun cycle(o: ArtObject): ArtChoice {
        val next = o.sets[(o.sets.indexOf(this[o]) + 1) % o.sets.size]
        return copy(swaps = swaps + (o to next)).tidy()
    }

    /** Sets [o] straight to [set] (the 3D Inventory's rows); ignored when [o] has no art in that set. */
    fun with(o: ArtObject, set: ArtSet): ArtChoice = if (set in o.sets) copy(swaps = swaps + (o to set)).tidy() else this

    fun withTheme(t: ArtSet) = ArtChoice(t)

    fun reset() = ArtChoice(theme)

    /** Drops swaps that match the theme anyway, so "swapped" always means something shows. */
    fun tidy() = copy(swaps = swaps.filter { (o, s) -> s in o.sets && s != o.under(theme) })
}
