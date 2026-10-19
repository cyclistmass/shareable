package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.security.MessageDigest
import wgbg.engine.Resource

class SpaceArtTest {
    @Test fun `Space dispatch covers all cards and dice with engine names and event order`() {
        val choice = ArtChoice(ArtSet.SPACE)
        for (c in 0..7) {
            assertEquals(SpaceArt.resourceCard(c), ThemeArt.resource(choice, c))
            assertEquals(SpaceArt.resourceCard(c), ThemeArt.image(choice, ALL_CARD_ART[c]))
        }
        for (c in 0..4) {
            assertEquals(SpaceArt.fortuneCard(c), ThemeArt.fortune(choice, c))
            assertEquals(SpaceArt.fortuneCard(c), ThemeArt.namedCard(choice, FORTUNE[c], null))
        }
        for (c in 0..24) {
            assertEquals(SpaceArt.progressCard(c), ThemeArt.progress(choice, c))
            assertEquals(SpaceArt.progressCard(c), ThemeArt.namedCard(choice, PROGRESS_NAMES[c], null))
        }
        for (track in 0..2) assertEquals(SpaceArt.deckBack(track), ThemeArt.namedCard(choice, DECK_NAMES[track], null))
        for (lv in 1..3) assertEquals(SpaceArt.knightCard(lv), ThemeArt.knight(choice, lv))
        for (red in listOf(false, true)) for (face in 1..6) assertEquals(SpaceArt.diceFace(red, face), ThemeArt.dice(choice, red, face))
        for ((event, source) in listOf(1, 2, 3, 6, 5, 4).withIndex())
            assertEquals(SpaceArt.sprite("die_event_face_$source").full[0], ThemeArt.event(choice, event, 3))
        assertNull(ThemeArt.namedCard(choice, "unknown card", null))
    }

    @Test fun `every Space sprite has something to draw without an owner colour`() {
        // The Bandit ships only body and trim; SpaceSprites.draw must fall back to them, never draw just a shadow.
        for ((role, sp) in SpaceArt.sprites) assertTrue(sp.full.isNotEmpty() || sp.body.isNotEmpty(), "$role has no visible layer")
        assertTrue(SpaceArt.bandit.full.isEmpty() && SpaceArt.bandit.body.isNotEmpty())
    }

    @Test fun `Default and Pirate dispatch keeps existing art and each swap is independent`() {
        for (set in listOf(ArtSet.DEFAULT, ArtSet.PIRATE)) {
            val choice = ArtChoice(set)
            for (c in 0..7) assertEquals(ALL_CARD_ART[c], ThemeArt.resource(choice, c))
            for (c in 0..4) assertEquals(GuideArt.fortuneArt(c, set == ArtSet.PIRATE), ThemeArt.fortune(choice, c))
            for (red in listOf(false, true)) for (n in 1..6) assertEquals(DiceArt.face(red, set == ArtSet.PIRATE, n), ThemeArt.dice(choice, red, n))
            for (event in 0..5) for (style in 0..3) assertEquals(DiceArt.eventFace(set == ArtSet.PIRATE, event, style), ThemeArt.event(choice, event, style))
        }
        val mixed = ArtChoice(ArtSet.SPACE).with(ArtObject.CARDS, ArtSet.DEFAULT).with(ArtObject.DICE, ArtSet.PIRATE)
        assertEquals(ALL_CARD_ART[0], ThemeArt.resource(mixed, 0))
        assertEquals(DiceArt.face(true, true, 6), ThemeArt.dice(mixed, true, 6))
        assertEquals(SpaceArt.knightCard(3), ThemeArt.knight(mixed, 3))
    }

    @Test fun `Space live placements cover expansion state without duplicate plates or knight discs`() {
        val s = wgbg.engine.Rules.newGame(wgbg.engine.GameConfig(expansion = wgbg.engine.Expansion.CITIES_AND_KNIGHTS), 5)
        val ck = s.ck!!
        s.edgeOwner[0] = 1
        for (v in 0..3) { s.vertexOwner[v] = v; s.vertexBuilding[v] = if (v == 0) wgbg.engine.Piece.SETTLEMENT else wgbg.engine.Piece.CITY }
        for (t in 0..2) { ck.metroVertex[t] = t + 1; ck.metroOwner[t] = t + 1; ck.wall[t + 1] = 1 }
        for (i in 0..5) { ck.knightOwner[8 + i] = i % 4; ck.knightLevel[8 + i] = 1 + i / 2; if (i % 2 == 1) ck.knightFlags[8 + i] = wgbg.engine.CkState.ACTIVE }
        ck.merchantOwner = 2; ck.merchantHex = 7
        val hash = s.stateHash()
        for (lod in 0..1) {
            val placements = themedLivePlacements(s, ArtChoice(ArtSet.SPACE), lod) { true }
            assertTrue(placements.all { it.model.startsWith("space_") })
            assertEquals(19, placements.count { it.model.startsWith("space_tile_") })
            assertEquals(6, placements.count { it.model.startsWith("space_knight_") })
            assertEquals(2, placements.count { it.model == "space_road_v" })
            assertEquals(s.board.ports.size, placements.count { it.model.startsWith("space_port_") })
            for (t in 0..2) assertTrue(placements.any { it.model == spaceModel(SpaceArt.capital(t)) && it.position == liveVertex(s.board, t + 1) })
            for (p in placements) assertTrue(File(assets, "filament/${p.model}.glb").isFile, p.model)
            for (h in 0 until s.board.hexCount) if (s.numberAt(h) > 0 && h != s.robberHex) {
                val p = placements.single { it.model == "space_number_${s.numberAt(h)}" && it.position == liveHex(s.board, h) + androidx.compose.ui.geometry.Offset(.36f, .30f) }
                assertEquals(SpaceArt.TILE_DECK_HEIGHT + .012f, p.height)
            }
            assertTrue(placements.none { it.model in listOf("number_plate", "knight_ring", "knight_band") })
            val batch = SpaceBoardBatch.build(placements.map { SpaceBoardBatch.Instance(it.model.removePrefix("space_"), liveModelMatrix(it)) }, ::bytes)
            assertTrue(batch.triangles < 300_000); assertTrue(batch.draws <= 150 - 18)
            assertEquals(hash, s.stateHash())
        }
    }

    @Test fun `Space board swap leaves Default piece models and legacy placements unchanged`() {
        val s = wgbg.engine.Rules.newGame(wgbg.engine.GameConfig(), 23)
        s.vertexOwner[0] = 0; s.vertexBuilding[0] = wgbg.engine.Piece.SETTLEMENT
        for (set in listOf(ArtSet.DEFAULT, ArtSet.PIRATE)) {
            assertEquals(livePlacements(s, set == ArtSet.PIRATE, 0) { true }, themedLivePlacements(s, ArtChoice(set), 0) { true })
            assertEquals(liveInventoryPlacements(), themedInventoryPlacements(ArtChoice(set)))
        }
        val mixed = themedLivePlacements(s, ArtChoice().with(ArtObject.BOARD, ArtSet.SPACE), 0) { true }
        assertTrue(mixed.any { it.model == "default_homestead" && it.height == SpaceArt.TILE_DECK_HEIGHT })
        assertTrue(mixed.any { it.model == "bandit" })
        assertThrows(IllegalArgumentException::class.java) { themedLivePlacements(s, ArtChoice(ArtSet.SPACE), 99) { true } }
    }

    @Test fun `Space live Inventory includes all states and all model paths resolve`() {
        val list = themedInventoryPlacements(ArtChoice(ArtSet.SPACE))
        for (p in list) assertTrue(File(assets, "filament/${p.model}.glb").isFile, p.model)
        for (lv in 1..3) for (active in listOf(false, true)) assertTrue(list.any { it.model == spaceModel(SpaceArt.knight(lv, active)) })
        for (height in 1..3) assertTrue(list.any { it.model == spaceModel(SpaceArt.ramparts(height)) })
        for (track in 0..2) assertTrue(list.any { it.model == spaceModel(SpaceArt.capital(track)) })
    }

    private val assets = File("src/main/assets")
    private val drawables = File("src/main/res/drawable-nodpi")
    private fun bytes(path: String) = File(assets, path).readBytes()
    private fun json(glb: ByteArray): Map<*, *> {
        val b = ByteBuffer.wrap(glb).order(LITTLE_ENDIAN)
        assertEquals(0x46546c67, b.int); assertEquals(2, b.int); assertEquals(glb.size, b.int)
        val size = b.int; assertEquals(0x4e4f534a, b.int)
        return MiniJson.parse(ByteArray(size).also { b.get(it) }.toString(Charsets.UTF_8)) as Map<*, *>
    }
    private fun triangles(j: Map<*, *>): Int {
        val accessors = j["accessors"] as List<*>
        return (j["meshes"] as List<*>).sumOf { mesh ->
            ((mesh as Map<*, *>)["primitives"] as List<*>).sumOf { p ->
                val a = accessors[((p as Map<*, *>)["indices"] as Number).toInt()] as Map<*, *>
                (a["count"] as Number).toInt() / 3
            }
        }
    }

    @Test fun `all 75 sprite roles resolve every layer and resource ID`() {
        assertEquals(75, SpaceArt.sprites.size)
        for ((role, s) in SpaceArt.sprites) {
            assertTrue(s.frameUnits > 0 && s.pixels > 0, role)
            assertTrue(File(assets, s.model).isFile, s.model)
            for (ids in listOf(s.body, s.trim, s.full, s.shadow)) assertTrue(ids.isEmpty() || ids.size == 18, role)
            assertEquals(18, s.shadow.size, role)
            assertTrue(if (s.tintable) s.trim.size == 18 && s.full.isEmpty() else s.full.size == 18 && s.trim.isEmpty(), role)
            val ids = s.body + s.trim + s.full + s.shadow
            for ((name, id) in s.resourceNames().zip(ids)) {
                assertTrue(File(drawables, "$name.webp").isFile, name)
                assertEquals(R.drawable::class.java.getField(name).getInt(null), id, name)
                assertNotEquals(0, id, name)
            }
        }
    }

    @Test fun `all models retain source bytes and verified triangle totals`() {
        val catalog = MiniJson.parse(File(assets, "space_catalog.json").readText()) as Map<*, *>
        val entries = catalog["models"] as Map<*, *>
        assertEquals(164, SpaceArt.models.size)
        assertEquals(entries.keys, SpaceArt.models.keys)
        var total = 0
        for ((role, path) in SpaceArt.models) {
            val content = bytes(path)
            val meta = entries[role] as Map<*, *>
            assertEquals(meta["sha256"], MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }, role)
            val j = json(content)
            val count = triangles(j)
            assertEquals((meta["triangles"] as Number).toInt(), count, role)
            total += count
            if (role in SpaceArt.sprites) assertTrue((j["textures"] as List<*>?).isNullOrEmpty(), role)
        }
        assertEquals(97533, total)
    }

    @Test fun `every shipped default building terrain and expansion role has a Space counterpart`() {
        // Derive building coverage from the shipped Default table, not from Space's own manifest.
        val source = File("src/main/java/com/mauro/wgbg/BuildingArt.kt").readText()
        val roles = Regex("val DEFAULT_([A-Z0-9_]+) =").findAll(source).map { it.groupValues[1].lowercase() }.toSet()
        assertTrue(roles.size >= 11)
        assertTrue(SpaceArt.sprites.keys.containsAll(roles))
        for (r in -1..4) { assertNotNull(SpaceArt.tile(r)); assertNotNull(SpaceArt.port(r)) }
        for (lv in 1..3) for (active in listOf(false, true)) assertNotNull(SpaceArt.knight(lv, active))
        for (track in 0..2) { assertNotNull(SpaceArt.capital(track)); assertNotEquals(0, SpaceArt.deckBack(track)) }
        for (n in 2..12) assertNotNull(SpaceArt.numberPlate(n))
        for (role in listOf("bandit", "raiders_ship", "merchant", "boat", "sea", "cliff", "mycelium_lane")) assertNotNull(SpaceArt.sprite(role))
        assertEquals(5, SpaceArt.critters.size)
    }

    @Test fun `42 unlettered portraits cover resources commodities fortune and all progress cards`() {
        assertEquals(42, SpaceArt.cards.size)
        for ((key, id) in SpaceArt.cards) {
            assertTrue(File(drawables, "space_card_$key.webp").isFile)
            assertEquals(R.drawable::class.java.getField("space_card_$key").getInt(null), id)
        }
        assertEquals(8, (0..7).map { SpaceArt.resourceCard(it) }.toSet().size)
        assertEquals(5, (0..4).map { SpaceArt.fortuneCard(it) }.toSet().size)
        assertEquals(25, (0..24).map { SpaceArt.progressCard(it) }.toSet().size)
        assertEquals(SpaceArt.cards.getValue("glass_bamboo_fibre"), SpaceArt.resourceCard(Resource.WOOD))
        assertNotEquals(0, SpaceArt.cardBack)
        for (level in 1..3) {
            assertNotEquals(0, SpaceArt.knightCard(level))
            assertTrue(File(drawables, "space_card_knight_$level.webp").isFile)
        }
    }

    @Test fun `event faces follow engine tracks rather than Pirate file numbering`() {
        assertEquals(listOf(1, 2, 3, 6, 5, 4).map { SpaceArt.sprite("die_event_face_$it").full[0] }, (0..5).map { SpaceArt.eventFace(it) })
        for (red in listOf(false, true)) assertEquals(6, (1..6).map { SpaceArt.diceFace(red, it) }.toSet().size)
        assertEquals(18, SpaceArt.eventDie.full.size)
    }

    @Test fun `18 tilts retain anchors banner states and eight step compatibility`() {
        assertEquals(listOf(0, 2, 5, 7, 10, 12, 15, 17), (0..7).map { SpaceArt.legacyTilt(it) })
        assertEquals(0, SpaceArt.tiltStep(0f)); assertEquals(17, SpaceArt.tiltStep(52.8f))
        for (r in -1..4) assertEquals(0.22f, SpaceArt.tile(r).contactHeight)
        assertEquals(1.30f, SpaceArt.knight(1, true).frameUnits)
        assertEquals(8, SpaceArt.homestead.legacyPiece().body.size)
        for (lv in 1..3) assertFalse(bytes(SpaceArt.knight(lv, true).model).contentEquals(bytes(SpaceArt.knight(lv, false).model)))
    }

    @Test fun `garbage roles and wrong indices fail without mutating the table`() {
        val before = SpaceArt.sprites.toMap()
        val invalid = listOf<() -> Any>({ SpaceArt.sprite("../../missing") }, { SpaceArt.model("") },
            { SpaceArt.tile(5) }, { SpaceArt.tile(0, 2) }, { SpaceArt.port(-2) }, { SpaceArt.knight(0, false) },
            { SpaceArt.ramparts(4) }, { SpaceArt.capital(3) }, { SpaceArt.numberPlate(1) }, { SpaceArt.diceFace(true, 7) },
            { SpaceArt.eventFace(6) }, { SpaceArt.resourceCard(8) }, { SpaceArt.fortuneCard(-1) }, { SpaceArt.progressCard(25) },
            { SpaceArt.legacyTilt(8) }, { SpaceArt.tiltStep(Float.NaN) }, { SpaceArt.sea.legacyPiece() })
        for (action in invalid) assertThrows(IllegalArgumentException::class.java) { action() }
        assertEquals(before, SpaceArt.sprites)
    }

    @Test fun `five critters parse resolve every clip and keep the turtle anchored`() {
        for (path in SpaceArt.critters) {
            val folder = File(assets, path)
            val module = CritterModules.module(folder.name, File(folder, "frames").list()!!.toList(), File(folder, "critter.json").readText())
            assertEquals(emptyList<String>(), module.problems(folder.name), path)
            assertEquals(8, module.files.size)
            assertTrue(module.spec.clips.values.all { it.loop })
            assertTrue(File(folder, "${folder.name}.glb").isFile)
            if (folder.name.endsWith("turtle")) {
                assertNotNull(module.spec.anchor)
                assertEquals(0.0, module.spec.anchor!!.x)
                assertEquals(0.0, module.spec.anchor!!.y)
                assertTrue(module.spec.stages.all { s -> s.delta.all { it == 0.0 } })
            }
        }
        // Adding the third art set must not silently enable Space critters in shipped games.
        val loaded = CritterModules.load({ File(assets, it).list()?.toList().orEmpty() }, { File(assets, it).inputStream() })
        assertFalse(loaded.any { it.id.startsWith("space_") })
    }

    private fun matrix(x: Float = 0f) = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, x,0f,0f,1f)
    private fun worstBoard(players: Int): List<SpaceBoardBatch.Instance> = buildList {
        fun add(role: String, n: Int, owner: Int? = null) = repeat(n) {
            val tint = owner?.let { p -> listOf((p + 1f)/(players+1), 0.3f, 0.6f) }
            add(SpaceBoardBatch.Instance(role, matrix(size * 0.02f), tint))
        }
        add("tile_grain", if (players == 4) 19 else 30)
        add("number_8", if (players == 4) 18 else 28)
        for (p in 0 until players) {
            add("road_v", 30, p) // Existing livePlacements draws TWO sprites/models per road.
            add("homestead", 5, p); add("town", 4, p); add("ramparts_h3", 3, p)
            for (lv in 1..3) add("knight_l${lv}_active", 2, p)
        }
        add("capital_crown", 3, 0); add("bandit", 1); add("raiders_ship", 1); add("merchant", 1, 0)
        add("port_timber", if (players == 4) 9 else 11)
        add("boat", 6, 0); add("sea", 1)
        add("cliff", if (players == 4) 30 else 38)
        add("mycelium_lane", if (players == 4) 72 else 109)
    }

    @Test fun `worst four and six player boards fit triangle and material batch budgets`() {
        for (players in listOf(4, 6)) {
            val placements = worstBoard(players)
            val expected = placements.sumOf { triangles(json(bytes(SpaceArt.model(it.role)))) }
            val batch = SpaceBoardBatch.build(placements, ::bytes)
            assertEquals(expected, batch.triangles)
            assertEquals(expected, triangles(json(batch.glb)))
            val output = json(batch.glb)
            val declared = (output["extensionsUsed"] as List<*>).toSet()
            for (material in output["materials"] as List<*>)
                assertTrue(declared.containsAll(((material as Map<*, *>)["extensions"] as Map<*, *>?).orEmpty().keys))
            assertTrue((output["bufferViews"] as List<*>).all { ((it as Map<*, *>)["target"] as Number).toInt() in listOf(34962, 34963) })
            // Count actual primitives too: the animated jelly has six, not the static pieces' three.
            val critter = json(bytes(SpaceArt.model("lantern_jellies")))
            val critterTriangles = SpaceArt.MAX_CRITTERS * triangles(critter)
            val critterDraws = SpaceArt.MAX_CRITTERS * (critter["meshes"] as List<*>).sumOf {
                ((it as Map<*, *>)["primitives"] as List<*>).size
            }
            assertTrue(batch.triangles + critterTriangles <= if (players == 4) 300000 else 420000)
            assertTrue(batch.draws + critterDraws <= 150)
            File("build/space-budget-$players.glb").writeBytes(batch.glb)
            println("SPACE BUDGET players=$players static=${batch.triangles} critters=$critterTriangles draws=${batch.draws}+$critterDraws")
        }
    }

    @Test fun `batch preserves translation tint separation normals and triangle counts`() {
        val batch = SpaceBoardBatch.build(listOf(
            SpaceBoardBatch.Instance("homestead", matrix(10f), listOf(1f, 0f, 0f)),
            SpaceBoardBatch.Instance("homestead", matrix(20f), listOf(0f, 0f, 1f))), ::bytes)
        assertEquals(388, batch.triangles)
        val result = json(batch.glb)
        val materials = result["materials"] as List<*>
        assertEquals(4, materials.size) // Two owner bodies, shared brass, shared fixed details.
        val positions = (result["accessors"] as List<*>).map { it as Map<*, *> }.filter { it.containsKey("min") }
        assertTrue(positions.all { ((it["min"] as List<*>)[0] as Number).toFloat() > 9f })
        assertTrue(positions.any { ((it["max"] as List<*>)[0] as Number).toFloat() > 20f })
    }

    @Test fun `batch paints the town and Capital dome glass in the owner colour`() {
        for (role in listOf("town", "capital_crown")) {
            val batch = SpaceBoardBatch.build(listOf(SpaceBoardBatch.Instance(role, matrix(), listOf(1f, 0f, 0f))), ::bytes)
            val materials = (json(batch.glb)["materials"] as List<*>).map { it as Map<*, *> }
            val tinted = materials.count { (((it["pbrMetallicRoughness"] as Map<*, *>)["baseColorFactor"] as List<*>?)?.get(1) as Number?)?.toFloat() == 0f }
            assertEquals(materials.size - 1, tinted, role) // Body and glass (merged when equal) take red; only the brass keeps its own.
        }
    }

    @Test fun `batch rejects textured animated malformed and invalid transform input`() {
        assertThrows(IllegalArgumentException::class.java) { SpaceBoardBatch.build(emptyList(), ::bytes) }
        for (role in listOf("card_card_back", "star_whale"))
            assertThrows(IllegalArgumentException::class.java) { SpaceBoardBatch.build(listOf(SpaceBoardBatch.Instance(role, matrix())), ::bytes) }
        assertThrows(IllegalArgumentException::class.java) {
            SpaceBoardBatch.build(listOf(SpaceBoardBatch.Instance("homestead", matrix()))) { byteArrayOf(1, 2, 3) }
        }
        for (m in listOf(FloatArray(15), matrix().also { it[0] = Float.NaN }, matrix().also { it[0] = 2f }))
            assertThrows(IllegalArgumentException::class.java) { SpaceBoardBatch.build(listOf(SpaceBoardBatch.Instance("homestead", m)), ::bytes) }
    }
}
