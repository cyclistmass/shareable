package com.mauro.wgbg

import wgbg.engine.Resource
import kotlin.math.roundToInt

/** Approved Space assets (#88). Selection is deliberately left to the theme picker change.
 * Keys are roles/file identifiers, never display names. Card labels still come from the game.
 */
object SpaceArt {
    const val TILT_STEPS = 18
    const val MAX_TILT_DEGREES = 52.8f
    const val TILE_DECK_HEIGHT = 0.22f
    /** Theme-wide simultaneous critter cap used by the performance ledger. */
    const val MAX_CRITTERS = 3
    const val PLATE_X = 0.36f
    const val PLATE_Y = 0.30f

    /** Draw shadow first, tinted body second, fixed trim last; neutral objects use full.
     * Frame centre is the anchor. [contactHeight] is the authored model plane at that centre.
     * A neutral dock/Bandit must use white even if its source has a WoodBody layer.
     */
    data class Sprite(
        val prefix: String, val frameUnits: Float, val contactHeight: Float, val pixels: Int,
        val model: String, val body: List<Int>, val trim: List<Int>, val full: List<Int>, val shadow: List<Int>,
    ) {
        val tintable get() = body.isNotEmpty()
        fun resourceNames(): List<String> = listOf("body" to body, "trim" to trim, "full" to full, "shadow" to shadow)
            .flatMap { (layer, ids) -> ids.indices.map { "${prefix}_${layer}_tilt_$it" } }
        /** Existing eight-view consumers can sample these; preserve frameUnits when drawing. */
        fun legacyPiece(): BuildingArt.Piece {
            require(tintable) { "$prefix has no player-colour body" }
            return BuildingArt.Piece((0..7).map { body[legacyTilt(it)] }, (0..7).map { trim[legacyTilt(it)] })
        }
    }

    val sprites: Map<String, Sprite> get() = SpaceArtData.sprites
    val models: Map<String, String> get() = SpaceArtData.models
    val cards: Map<String, Int> get() = SpaceArtData.cards
    /** Nested under critters/space so the existing global loader does not enable them in other sets. */
    val critters: List<String> get() = SpaceArtData.critters
    fun sprite(role: String): Sprite = requireNotNull(sprites[role]) { "Unknown Space role: $role" }
    fun model(role: String): String = requireNotNull(models[role]) { "Unknown Space model: $role" }

    private val terrainRoles = listOf("timber", "clay", "fleece", "grain", "stone")
    fun tile(resource: Int, lod: Int = 0): Sprite {
        require(lod in 0..1)
        require(resource == Resource.NONE || resource in 0..4)
        // Both settings use the delivered lean mesh; no unapproved decimation or new art.
        return sprite("tile_" + if (resource == Resource.NONE) "desert" else terrainRoles[resource])
    }
    fun numberPlate(number: Int? = null): Sprite {
        require(number == null || number in 2..12)
        return sprite(if (number == null) "number_plate" else "number_$number")
    }
    fun knight(level: Int, active: Boolean): Sprite {
        require(level in 1..3)
        return sprite("knight_l${level}_${if (active) "active" else "inactive"}")
    }
    fun ramparts(height: Int): Sprite {
        require(height in 1..3)
        return sprite("ramparts_h$height")
    }
    /** Engine track order: Guilds, Crown, Academy. */
    fun capital(track: Int): Sprite {
        require(track in 0..2)
        return sprite("capital_" + listOf("guilds", "crown", "academy")[track])
    }
    fun port(resource: Int): Sprite {
        require(resource == Resource.NONE || resource in 0..4)
        return sprite("port_" + if (resource == Resource.NONE) "3to1" else terrainRoles[resource])
    }
    val homestead get() = sprite("homestead")
    val town get() = sprite("town")
    val bandit get() = sprite("bandit")
    val merchant get() = sprite("merchant")
    val raidersShip get() = sprite("raiders_ship")
    val boat get() = sprite("boat")
    val sea get() = sprite("sea")
    val cliff get() = sprite("cliff")
    val lane get() = sprite("mycelium_lane")
    fun road(direction: Int): Sprite {
        require(direction in 0..2)
        return sprite("road_" + listOf("v", "p30", "m30")[direction])
    }

    private val resourceCards = listOf("glass_bamboo_fibre", "regolith_bricks", "o2_canister", "mushrooms", "nickel_iron",
        "data_slate", "nano_cloth", "platinum_coin")
    private val fortuneCards = listOf("space_marshal", "full_hold", "trade_beacon", "ferry_lanes", "new_horizon")
    private val progressCards = listOf("orbit_calibrator", "gravity_lift", "shieldwright", "signal_swap", "spore_bloom",
        "habitat_upgrade", "core_cutters", "open_archive", "lane_survey", "field_promotion", "dock_accord", "cargo_audit",
        "exchange_envoy", "jump_manifest", "supply_signal", "rare_cargo", "drone_override", "free_orbits", "crew_transfer",
        "lane_redirect", "quiet_intercept", "cargo_embargo", "signal_listener", "ranger_rally", "orbital_banquet")
    private fun card(keys: List<String>, index: Int): Int {
        require(index in keys.indices)
        return cards.getValue(keys[index])
    }
    fun resourceCard(card: Int) = card(resourceCards, card)
    fun fortuneCard(card: Int) = card(fortuneCards, card)
    fun progressCard(card: Int) = card(progressCards, card)
    fun deckBack(track: Int) = card(listOf("platinum_exchange_back", "nano_alliance_back", "data_network_back"), track)
    val cardBack get() = cards.getValue("card_back")
    /** Complete neutral preview, including the banner/body, from the supplied full render. */
    fun knightCard(level: Int): Int {
        require(level in 1..3)
        return listOf(R.drawable.space_card_knight_1, R.drawable.space_card_knight_2, R.drawable.space_card_knight_3)[level - 1]
    }

    fun die(red: Boolean) = sprite(if (red) "die_coral" else "die_cream")
    fun diceFace(red: Boolean, value: Int): Int {
        require(value in 1..6)
        return sprite("die_${if (red) "coral" else "cream"}_face_$value").full[0]
    }
    /** Source faces 4/5/6 are Academy/Crown/Guilds, unlike the shipped Pirate numbering. */
    fun eventFace(event: Int): Int {
        require(event in 0..5)
        val sourceFace = listOf(1, 2, 3, 6, 5, 4)[event]
        return sprite("die_event_face_$sourceFace").full[0]
    }
    val eventDie get() = sprite("die_event")
    // No tumbling animation was supplied: consumers must hold a face or animate the GLB,
    // never pass the 18 camera tilts off as a ten-frame tumbling loop.

    fun legacyTilt(step: Int): Int {
        require(step in 0..7)
        return (step * 17f / 7).roundToInt()
    }
    fun tiltStep(degrees: Float): Int {
        require(degrees.isFinite())
        return (degrees.coerceIn(0f, MAX_TILT_DEGREES) * 17 / MAX_TILT_DEGREES).roundToInt()
    }
}
