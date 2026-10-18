package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.security.MessageDigest
import wgbg.engine.Resource

class SpaceArtTest {
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
