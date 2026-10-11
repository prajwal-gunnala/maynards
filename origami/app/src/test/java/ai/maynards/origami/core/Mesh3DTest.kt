package ai.maynards.origami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class Mesh3DTest {
    private fun area(poly: List<Point2>, tris: List<Triple<Int, Int, Int>>) = tris.sumOf { (a, b, c) ->
        abs((poly[b].x - poly[a].x) * (poly[c].y - poly[a].y) - (poly[c].x - poly[a].x) * (poly[b].y - poly[a].y)) / 2 }

    @Test fun earClippingCoversEveryPartOutlineExactly() {
        listOf(OrganizerSpec(), OrganizerSpec(widthMm = 400, heightMm = 120, depthMm = 220, materialThicknessMm = 6, template = Template.TABLE))
            .flatMap { OrganizerGenerator.generate(it).parts }.forEach { p ->
                val poly = p.outline.let { if (it.first() == it.last()) it.dropLast(1) else it }.filterIndexed { i, q -> i == 0 || q != p.outline[i - 1] }
                val tris = Mesh3D.triangulate(poly)
                assertEquals("${p.id}", abs(Mesh3D.signedArea(poly)), area(poly, tris), 1e-6)
            }
    }

    @Test fun aConcaveLShapeTriangulates() {
        val l = listOf(Point2(0.0, 0.0), Point2(4.0, 0.0), Point2(4.0, 1.0), Point2(1.0, 1.0), Point2(1.0, 3.0), Point2(0.0, 3.0))
        val tris = Mesh3D.triangulate(l)
        assertEquals(4, tris.size)
        assertEquals(6.0, area(l, tris), 1e-9)
    }

    @Test fun eachPartIsASolidOfTheMaterialThickness() {
        val spec = OrganizerSpec(widthMm = 400, heightMm = 120, depthMm = 220, materialThicknessMm = 6, template = Template.TABLE)
        val top = Mesh3D.part(OrganizerGenerator.generate(spec).part("top")!!, spec)
        val ys = top.positions.filterIndexed { i, _ -> i % 3 == 1 }.distinct().sorted()
        assertEquals(2, ys.size)                                    // the top lies flat: two heights, 6 mm apart
        assertEquals(0.006, (ys[1] - ys[0]).toDouble(), 1e-6)
        assertEquals(0.123, ys[1].toDouble(), 1e-6)                 // its upper face at 120 mm + half the thickness
        assertTrue(top.indices.all { it in 0 until top.vertexCount })
        assertEquals(top.positions.size, top.normals.size)
    }

    @Test fun glbIsAValidBinaryGltfWithOneNodePerPartPlusFloor() {
        val design = OrganizerGenerator.generate(OrganizerSpec())
        val glb = GlbWriter.write(Mesh3D.build(design), byteArrayOf(1, 2, 3, 4))
        val b = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546C67, b.getInt(0))                       // "glTF"
        assertEquals(2, b.getInt(4))
        assertEquals(glb.size, b.getInt(8))
        val jsonLen = b.getInt(12)
        assertEquals(0, jsonLen % 4)
        val json = org.json.JSONObject(String(glb, 20, jsonLen, Charsets.UTF_8).trim())
        assertEquals(design.parts.size + 1, json.getJSONArray("nodes").length())
        assertEquals("base", json.getJSONArray("nodes").getJSONObject(0).getString("name"))
        assertEquals(0x004E4942, b.getInt(20 + jsonLen + 4))       // "BIN\0"
        System.getenv("GLB_OUT")?.let { dir ->                       // for the Khronos validator, when asked
            java.io.File(dir, "organizer.glb").writeBytes(glb)
            val t = OrganizerSpec(widthMm = 400, heightMm = 120, depthMm = 220, materialThicknessMm = 6, template = Template.TABLE)
            java.io.File(dir, "table.glb").writeBytes(GlbWriter.write(Mesh3D.build(OrganizerGenerator.generate(t)), byteArrayOf(1, 2, 3, 4)))
        }
    }
}
