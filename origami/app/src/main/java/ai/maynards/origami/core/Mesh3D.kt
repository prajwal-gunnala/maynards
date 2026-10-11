package ai.maynards.origami.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** One part as triangles: positions and normals in metres (glTF: Y up), UVs for wood grain, and indices. */
class PartMesh(val id: String, val name: String, val positions: FloatArray, val normals: FloatArray,
               val uvs: FloatArray, val indices: IntArray) {
    val vertexCount: Int get() = positions.size / 3
}

/**
 * Builds a solid for every part: its outline (with tabs) extruded to the material thickness at the part's
 * assembled place. The same assembledPoint() that the cut sheet and line preview use, so all three agree.
 */
object Mesh3D {
    private const val GRAIN_MM = 300.0      // one texture repeat per 30 cm of board

    fun build(design: OrganizerDesign): List<PartMesh> = design.parts.filter { it.plane != PanelPlane.LOOSE }.map { part(it, design.spec) }

    fun part(part: Part, spec: OrganizerSpec): PartMesh {
        val t = spec.materialThicknessMm.toDouble()
        val o = part.assembledPoint(Point2(0.0, 0.0), spec)
        val ex = part.assembledPoint(Point2(1.0, 0.0), spec) - o
        val ey = part.assembledPoint(Point2(0.0, 1.0), spec) - o
        val n = (ex cross ey).unit()
        val outline = part.outline.let { if (it.first() == it.last()) it.dropLast(1) else it }.let(::dedupe)
        val ccw = signedArea(outline) > 0
        val tris = triangulate(outline)

        val pos = ArrayList<Float>(); val nor = ArrayList<Float>(); val uv = ArrayList<Float>(); val idx = ArrayList<Int>()
        fun vert(p: Point3, normal: Point3, u: Double, v: Double): Int {
            val g = gltf(p); val gn = gltfDir(normal)
            pos += g.x.toFloat(); pos += g.y.toFloat(); pos += g.z.toFloat()
            nor += gn.x.toFloat(); nor += gn.y.toFloat(); nor += gn.z.toFloat()
            uv += u.toFloat(); uv += v.toFloat()
            return pos.size / 3 - 1
        }
        fun at(q: Point2, side: Double) = part.assembledPoint(q, spec) + n * (side * t / 2)

        // two caps; winding follows each cap's outward normal
        for ((side, normal) in listOf(1.0 to n, -1.0 to n * -1.0)) {
            val base = outline.map { q -> vert(at(q, side), normal, q.x / GRAIN_MM, q.y / GRAIN_MM) }
            for ((a, b, c) in tris) {
                val front = (side > 0) == ccw
                if (front) { idx += base[a]; idx += base[b]; idx += base[c] } else { idx += base[a]; idx += base[c]; idx += base[b] }
            }
        }
        // the edge band: one quad per outline edge, facing out of the board
        var run = 0.0
        for (i in outline.indices) {
            val a = outline[i]; val b = outline[(i + 1) % outline.size]
            val d = part.assembledPoint(b, spec) - part.assembledPoint(a, spec)
            val len = sqrt(d dot d)
            if (len < 1e-9) continue
            val out = (if (ccw) d cross n else n cross d).unit()
            val u0 = run / GRAIN_MM; val u1 = (run + len) / GRAIN_MM; run += len
            val v0 = 0.0; val v1 = t / GRAIN_MM
            val p0 = vert(at(a, 1.0), out, u0, v0); val p1 = vert(at(b, 1.0), out, u1, v0)
            val p2 = vert(at(b, -1.0), out, u1, v1); val p3 = vert(at(a, -1.0), out, u0, v1)
            idx += p0; idx += p3; idx += p2; idx += p0; idx += p2; idx += p1
        }
        return PartMesh(part.id, part.name, pos.toFloatArray(), nor.toFloatArray(), uv.toFloatArray(), idx.toIntArray())
    }

    /** Ear clipping for a simple polygon (no holes); returns index triples into [poly]. */
    fun triangulate(poly: List<Point2>): List<Triple<Int, Int, Int>> {
        val n = poly.size
        if (n < 3) return emptyList()
        val ccw = signedArea(poly) > 0
        val left = (0 until n).toMutableList()
        val out = ArrayList<Triple<Int, Int, Int>>(n - 2)
        var guard = 0
        while (left.size > 3 && guard < n * n) {
            guard++
            var clipped = false
            for (k in left.indices) {
                val i0 = left[(k - 1 + left.size) % left.size]; val i1 = left[k]; val i2 = left[(k + 1) % left.size]
                val a = poly[i0]; val b = poly[i1]; val c = poly[i2]
                val cross = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
                if ((cross > 1e-12) != ccw || abs(cross) <= 1e-12) continue          // reflex or flat corner
                if (left.any { j -> j != i0 && j != i1 && j != i2 && inside(poly[j], a, b, c) }) continue
                out += Triple(i0, i1, i2)
                left.removeAt(k)
                clipped = true
                break
            }
            if (!clipped) {   // only collinear points left: drop a flat corner and carry on
                val k = left.indices.firstOrNull { k ->
                    val a = poly[left[(k - 1 + left.size) % left.size]]; val b = poly[left[k]]; val c = poly[left[(k + 1) % left.size]]
                    abs((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)) <= 1e-12
                } ?: break
                left.removeAt(k)
            }
        }
        if (left.size == 3) out += Triple(left[0], left[1], left[2])
        return out
    }

    fun signedArea(p: List<Point2>): Double = p.indices.sumOf { i -> val a = p[i]; val b = p[(i + 1) % p.size]; a.x * b.y - b.x * a.y } / 2

    private fun inside(p: Point2, a: Point2, b: Point2, c: Point2): Boolean {
        fun s(p1: Point2, p2: Point2, p3: Point2) = (p1.x - p3.x) * (p2.y - p3.y) - (p2.x - p3.x) * (p1.y - p3.y)
        val d1 = s(p, a, b); val d2 = s(p, b, c); val d3 = s(p, c, a)
        val neg = d1 < 0 || d2 < 0 || d3 < 0; val pos = d1 > 0 || d2 > 0 || d3 > 0
        return !(neg && pos)
    }

    private fun dedupe(p: List<Point2>): List<Point2> = p.filterIndexed { i, q -> i == 0 || q != p[i - 1] }

    /** Origami is x right, y depth, z up, in mm; glTF is Y up, metres, right-handed: (x, z, -y). */
    private fun gltf(p: Point3) = Point3(p.x / 1000, p.z / 1000, -p.y / 1000)
    private fun gltfDir(p: Point3) = Point3(p.x, p.z, -p.y)
}

operator fun Point3.minus(o: Point3) = Point3(x - o.x, y - o.y, z - o.z)
operator fun Point3.plus(o: Point3) = Point3(x + o.x, y + o.y, z + o.z)
operator fun Point3.times(s: Double) = Point3(x * s, y * s, z * s)
infix fun Point3.cross(o: Point3) = Point3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
infix fun Point3.dot(o: Point3) = x * o.x + y * o.y + z * o.z
fun Point3.unit(): Point3 = sqrt(this dot this).let { l -> if (l < 1e-12) this else Point3(x / l, y / l, z / l) }

/**
 * A binary glTF (.glb): one node per part (named by part id, so the viewer can move parts to explode them),
 * one wood material with an embedded texture, and a floor to catch shadows.
 */
object GlbWriter {
    fun write(parts: List<PartMesh>, woodPng: ByteArray, floorY: Float = 0f): ByteArray {
        val bin = java.io.ByteArrayOutputStream()
        val views = org.json.JSONArray(); val accessors = org.json.JSONArray(); val meshes = org.json.JSONArray(); val nodes = org.json.JSONArray()
        fun align() { while (bin.size() % 4 != 0) bin.write(0) }
        fun view(bytes: ByteArray, target: Int?): Int {
            align()
            val o = org.json.JSONObject().put("buffer", 0).put("byteOffset", bin.size()).put("byteLength", bytes.size)
            target?.let { o.put("target", it) }
            bin.write(bytes); views.put(o); return views.length() - 1
        }
        fun floats(a: FloatArray) = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { a.forEach { putFloat(it) } }.array()
        fun ints(a: IntArray) = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { a.forEach { putInt(it) } }.array()
        fun accessor(view: Int, type: String, count: Int, comp: Int, minMax: Pair<FloatArray, FloatArray>? = null): Int {
            val a = org.json.JSONObject().put("bufferView", view).put("componentType", comp).put("count", count).put("type", type)
            minMax?.let { (mn, mx) -> a.put("min", org.json.JSONArray(mn.map { it.toDouble() })).put("max", org.json.JSONArray(mx.map { it.toDouble() })) }
            accessors.put(a); return accessors.length() - 1
        }
        fun bounds(p: FloatArray): Pair<FloatArray, FloatArray> {
            val mn = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE); val mx = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            for (i in p.indices) { val k = i % 3; if (p[i] < mn[k]) mn[k] = p[i]; if (p[i] > mx[k]) mx[k] = p[i] }
            return mn to mx
        }
        fun mesh(m: PartMesh, material: Int): Int {
            val pa = accessor(view(floats(m.positions), 34962), "VEC3", m.vertexCount, 5126, bounds(m.positions))
            val na = accessor(view(floats(m.normals), 34962), "VEC3", m.vertexCount, 5126)
            val ua = accessor(view(floats(m.uvs), 34962), "VEC2", m.vertexCount, 5126)
            val ia = accessor(view(ints(m.indices), 34963), "SCALAR", m.indices.size, 5125)
            meshes.put(org.json.JSONObject().put("name", m.name).put("primitives", org.json.JSONArray().put(
                org.json.JSONObject().put("attributes", org.json.JSONObject().put("POSITION", pa).put("NORMAL", na).put("TEXCOORD_0", ua))
                    .put("indices", ia).put("material", material))))
            return meshes.length() - 1
        }
        parts.forEach { p -> nodes.put(org.json.JSONObject().put("name", p.id).put("mesh", mesh(p, 0))) }
        // floor: a wide quad just under the lowest point, light grey, receives shadows
        val all = parts.flatMap { it.positions.asList() }
        val minX = all.filterIndexed { i, _ -> i % 3 == 0 }.minOrNull() ?: 0f; val maxX = all.filterIndexed { i, _ -> i % 3 == 0 }.maxOrNull() ?: 1f
        val minZ = all.filterIndexed { i, _ -> i % 3 == 2 }.minOrNull() ?: 0f; val maxZ = all.filterIndexed { i, _ -> i % 3 == 2 }.maxOrNull() ?: 1f
        val pad = maxOf(maxX - minX, maxZ - minZ) * 0.35f      // enough for the shadow, small enough not to shrink the framing
        val fy = floorY - 0.0005f
        val floor = PartMesh("floor", "Floor",
            floatArrayOf(minX - pad, fy, minZ - pad, maxX + pad, fy, minZ - pad, maxX + pad, fy, maxZ + pad, minX - pad, fy, maxZ + pad),
            floatArrayOf(0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f), floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), intArrayOf(0, 2, 1, 0, 3, 2))
        nodes.put(org.json.JSONObject().put("name", "floor").put("mesh", mesh(floor, 1)))
        val image = view(woodPng, null)
        val json = org.json.JSONObject()
            .put("asset", org.json.JSONObject().put("version", "2.0").put("generator", "Origami Lab"))
            .put("scene", 0).put("scenes", org.json.JSONArray().put(org.json.JSONObject().put("nodes", org.json.JSONArray((0 until nodes.length()).toList()))))
            .put("nodes", nodes).put("meshes", meshes).put("accessors", accessors).put("bufferViews", views)
            .put("images", org.json.JSONArray().put(org.json.JSONObject().put("bufferView", image).put("mimeType", "image/png")))
            .put("samplers", org.json.JSONArray().put(org.json.JSONObject().put("magFilter", 9729).put("minFilter", 9987).put("wrapS", 10497).put("wrapT", 10497)))
            .put("textures", org.json.JSONArray().put(org.json.JSONObject().put("source", 0).put("sampler", 0)))
            .put("materials", org.json.JSONArray()
                .put(org.json.JSONObject().put("name", "plywood").put("pbrMetallicRoughness", org.json.JSONObject()
                    .put("baseColorTexture", org.json.JSONObject().put("index", 0)).put("metallicFactor", 0.0).put("roughnessFactor", 0.72)))
                .put(org.json.JSONObject().put("name", "floor").put("pbrMetallicRoughness", org.json.JSONObject()
                    .put("baseColorFactor", org.json.JSONArray(listOf(0.93, 0.93, 0.91, 1.0))).put("metallicFactor", 0.0).put("roughnessFactor", 0.95))))
        align()
        json.put("buffers", org.json.JSONArray().put(org.json.JSONObject().put("byteLength", bin.size())))
        val jsonBytes = json.toString().toByteArray(Charsets.UTF_8).let { b -> b + ByteArray((4 - b.size % 4) % 4) { ' '.code.toByte() } }
        val binBytes = bin.toByteArray()
        val total = 12 + 8 + jsonBytes.size + 8 + binBytes.size
        return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46546C67); putInt(2); putInt(total)
            putInt(jsonBytes.size); putInt(0x4E4F534A); put(jsonBytes)
            putInt(binBytes.size); putInt(0x004E4942); put(binBytes)
        }.array()
    }
}
