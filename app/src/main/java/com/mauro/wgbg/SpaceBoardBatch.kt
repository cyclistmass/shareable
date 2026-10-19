package com.mauro.wgbg

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import kotlin.math.abs
import kotlin.math.sqrt

/** Material batching for the Space 3D hook (#88).
 * Rebuild on placement/colour changes, off the UI thread, then give [Result.glb] to gltfio.
 * Static, texture-free Space meshes only. Animated critters stay separate and keep their clips.
 * Merging transformed copies preserves every triangle and material; it creates no new artwork.
 */
internal object SpaceBoardBatch {
    data class Instance(val role: String, val matrix: FloatArray, val linearTint: List<Float>? = null)
    data class Result(val glb: ByteArray, val triangles: Int, val draws: Int)

    private fun Any?.obj() = this as Map<*, *>
    private fun Any?.arr() = this as List<*>
    private fun Any?.int() = (this as Number).toInt()
    private fun Map<*, *>.list(key: String) = this[key]?.arr().orEmpty()
    private fun Map<*, *>.number(key: String, default: Int = 0) = this[key]?.int() ?: default

    private class Source(bytes: ByteArray) {
        val json: Map<*, *>
        val binary: ByteBuffer
        init {
            val b = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN)
            require(b.remaining() >= 28 && b.int == 0x46546c67 && b.int == 2 && b.int == bytes.size) { "Invalid GLB header" }
            val length = b.int
            require(b.int == 0x4e4f534a && length > 0 && length <= b.remaining() - 8)
            val text = ByteArray(length).also { b.get(it) }
            json = MiniJson.parse(text.toString(Charsets.UTF_8)).obj()
            val binaryLength = b.int
            require(b.int == 0x004e4942 && binaryLength == b.remaining())
            binary = b.slice().order(LITTLE_ENDIAN)
            require(json.list("textures").isEmpty() && json.list("animations").isEmpty() && json.list("skins").isEmpty()) {
                "Batch only static texture-free board models"
            }
            for (node in json.list("nodes")) require(node.obj().keys.none { it in listOf("matrix", "rotation", "translation", "scale", "skin", "children") }) {
                "Space board exports must have baked node transforms"
            }
            val nodes = json.list("nodes").map { it.obj().number("mesh", -1) }.sorted()
            require(nodes == json.list("meshes").indices.toList()) { "Every mesh must appear exactly once" }
        }
        fun accessor(index: Int) = json.list("accessors")[index].obj()
        fun count(index: Int) = accessor(index).number("count")
        fun offset(index: Int, element: Int, components: Int, bytes: Int): Int {
            val a = accessor(index)
            require("sparse" !in a)
            val view = json.list("bufferViews")[a.number("bufferView")].obj()
            require(view.number("buffer") == 0 && element in 0 until count(index))
            val stride = view.number("byteStride", components * bytes)
            val within = a.number("byteOffset") + element * stride
            require(within >= 0 && within + components * bytes <= view.number("byteLength"))
            val offset = view.number("byteOffset") + within
            require(offset >= 0 && offset + components * bytes <= binary.limit())
            return offset
        }
        fun floats(index: Int, element: Int, components: Int): FloatArray {
            val a = accessor(index)
            require(a.number("componentType") == 5126 && a["type"] == "VEC$components")
            val offset = offset(index, element, components, 4)
            return FloatArray(components) { binary.getFloat(offset + it * 4).also { f -> require(f.isFinite()) } }
        }
        fun index(index: Int, element: Int): Int {
            val a = accessor(index)
            require(a["type"] == "SCALAR")
            return when (a.number("componentType")) {
                5123 -> binary.getShort(offset(index, element, 1, 2)).toInt() and 65535
                5125 -> binary.getInt(offset(index, element, 1, 4)).also { require(it >= 0) }
                else -> error("Unsupported Space index type")
            }
        }
    }

    private class Group(val material: Map<*, *>) {
        val positions = ByteArrayOutputStream()
        val normals = ByteArrayOutputStream()
        val colors = ByteArrayOutputStream()
        val indices = ByteArrayOutputStream()
        var vertices = 0
        var indexCount = 0
        val min = FloatArray(3) { Float.POSITIVE_INFINITY }
        val max = FloatArray(3) { Float.NEGATIVE_INFINITY }
    }
    private fun ByteArrayOutputStream.float(value: Float) = integer(value.toRawBits())
    private fun ByteArrayOutputStream.integer(value: Int) { repeat(4) { write(value ushr (it * 8) and 255) } }

    /** [open] reads an asset path from SpaceArt; tint values are linear RGB, just as glTF expects. */
    fun build(instances: List<Instance>, open: (String) -> ByteArray): Result {
        require(instances.isNotEmpty())
        val sources = mutableMapOf<String, Source>()
        val groups = linkedMapOf<Map<*, *>, Group>()
        for (instance in instances) {
            val m = instance.matrix
            require(m.size == 16 && m.all { it.isFinite() })
            require(m[3] == 0f && m[7] == 0f && m[11] == 0f && m[15] == 1f)
            // Uniform positive scale and rotation: normals use the same rotation then normalize.
            val lengths = (0..2).map { c -> (0..2).sumOf { r -> (m[c * 4 + r] * m[c * 4 + r]).toDouble() } }
            require(lengths[0] > 0 && lengths.all { abs(it - lengths[0]) < lengths[0] * 1e-4 })
            for (a in 0..2) for (b in a + 1..2) require(abs((0..2).sumOf { r -> (m[a*4+r]*m[b*4+r]).toDouble() }) < lengths[0] * 1e-4)
            val determinant = m[0]*(m[5]*m[10]-m[6]*m[9]) - m[4]*(m[1]*m[10]-m[2]*m[9]) + m[8]*(m[1]*m[6]-m[2]*m[5])
            require(determinant > 0)
            instance.linearTint?.let { require(it.size == 3 && it.all { c -> c.isFinite() && c in 0f..1f }) }
            val source = sources.getOrPut(instance.role) { Source(open(SpaceArt.model(instance.role))) }
            for (mesh in source.json.list("meshes")) for (p in mesh.obj().list("primitives")) {
                val primitive = p.obj()
                require(primitive.number("mode", 4) == 4)
                val original = source.json.list("materials")[primitive.number("material")].obj()
                val material = original.filterKeys { it != "name" }.toMutableMap()
                val name = original["name"] as? String ?: ""
                // The same rule as the per-model path (liveTint): wood bodies, plus the town and Capital dome glass.
                if ((woodTint(name) || spaceGlassTint("space_" + instance.role, name)) && instance.linearTint != null) {
                    val pbr = material["pbrMetallicRoughness"].obj().toMutableMap()
                    val base = pbr["baseColorFactor"]?.arr() ?: listOf(1.0, 1.0, 1.0, 1.0)
                    pbr["baseColorFactor"] = (0..2).map { (base[it] as Number).toFloat() * instance.linearTint[it] } + (base[3] as Number).toFloat()
                    material["pbrMetallicRoughness"] = pbr
                }
                val group = groups.getOrPut(material) { Group(material) }
                val attrs = primitive["attributes"].obj()
                val position = attrs["POSITION"].int(); val normal = attrs["NORMAL"].int(); val color = attrs["COLOR_0"].int()
                val vertices = source.count(position)
                require(source.count(normal) == vertices && source.count(color) == vertices)
                val colorComponents = if (source.accessor(color)["type"] == "VEC4") 4 else 3
                for (v in 0 until vertices) {
                    val point = source.floats(position, v, 3)
                    val n = source.floats(normal, v, 3)
                    val transformedNormal = FloatArray(3) { r -> m[r]*n[0] + m[4+r]*n[1] + m[8+r]*n[2] }
                    val length = sqrt(transformedNormal.sumOf { (it*it).toDouble() }).toFloat()
                    require(length > 0)
                    for (r in 0..2) {
                        val x = m[r]*point[0] + m[4+r]*point[1] + m[8+r]*point[2] + m[12+r]
                        group.positions.float(x)
                        group.min[r] = minOf(group.min[r], x); group.max[r] = maxOf(group.max[r], x)
                        group.normals.float(transformedNormal[r] / length)
                    }
                    val rgba = source.floats(color, v, colorComponents)
                    repeat(4) { group.colors.float(if (it < colorComponents) rgba[it] else 1f) }
                }
                val indices = primitive["indices"].int()
                require(source.count(indices) % 3 == 0)
                for (i in 0 until source.count(indices)) {
                    val index = source.index(indices, i)
                    require(index in 0 until vertices)
                    group.indices.integer(group.vertices + index)
                }
                group.vertices += vertices
                group.indexCount += source.count(indices)
            }
        }
        val binary = ByteArrayOutputStream()
        val views = mutableListOf<Any>()
        val accessors = mutableListOf<Any>()
        fun attribute(data: ByteArrayOutputStream, count: Int, type: String, component: Int, bounds: Group? = null): Int {
            views.add(mapOf("buffer" to 0, "byteOffset" to binary.size(), "byteLength" to data.size(),
                "target" to if (type == "SCALAR") 34963 else 34962))
            data.writeTo(binary)
            val a = mutableMapOf<String, Any>("bufferView" to views.lastIndex, "componentType" to component, "count" to count, "type" to type)
            if (bounds != null) { a["min"] = bounds.min.toList(); a["max"] = bounds.max.toList() }
            accessors.add(a)
            return accessors.lastIndex
        }
        val primitives = groups.values.mapIndexed { i, g ->
            mapOf("attributes" to mapOf("POSITION" to attribute(g.positions, g.vertices, "VEC3", 5126, g),
                "NORMAL" to attribute(g.normals, g.vertices, "VEC3", 5126), "COLOR_0" to attribute(g.colors, g.vertices, "VEC4", 5126)),
                "indices" to attribute(g.indices, g.indexCount, "SCALAR", 5125), "material" to i)
        }
        val extensions = groups.keys.flatMap { (it["extensions"] as Map<*, *>?).orEmpty().keys }.distinct()
        val json = encode(mapOf("asset" to mapOf("version" to "2.0"), "extensionsUsed" to extensions,
            "scene" to 0, "scenes" to listOf(mapOf("nodes" to listOf(0))),
            "nodes" to listOf(mapOf("mesh" to 0)), "meshes" to listOf(mapOf("primitives" to primitives)),
            "materials" to groups.keys.toList(), "accessors" to accessors, "bufferViews" to views,
            "buffers" to listOf(mapOf("byteLength" to binary.size())))).toByteArray(Charsets.UTF_8)
        val padded = (json.size + 3) / 4 * 4
        val out = ByteBuffer.allocate(12 + 8 + padded + 8 + binary.size()).order(LITTLE_ENDIAN)
        out.putInt(0x46546c67).putInt(2).putInt(out.capacity()).putInt(padded).putInt(0x4e4f534a).put(json)
        repeat(padded - json.size) { out.put(32.toByte()) }
        out.putInt(binary.size()).putInt(0x004e4942).put(binary.toByteArray())
        return Result(out.array(), groups.values.sumOf { it.indexCount / 3 }, groups.size)
    }

    private fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { encode(it.key) + ":" + encode(it.value) }
        is List<*> -> value.joinToString(",", "[", "]") { encode(it) }
        is Number, is Boolean -> value.toString()
        else -> error("Unsupported JSON value")
    }
}
