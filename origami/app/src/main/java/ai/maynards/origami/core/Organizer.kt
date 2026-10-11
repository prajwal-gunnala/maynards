package ai.maynards.origami.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class Point2(val x: Double, val y: Double)
data class Point3(val x: Double, val y: Double, val z: Double)
data class Cutout(val x: Double, val y: Double, val width: Double, val height: Double)

enum class Edge { TOP, RIGHT, BOTTOM, LEFT }
enum class PanelPlane { FLOOR, FRONT, BACK, LEFT_SIDE, RIGHT_SIDE, DIVIDER, TOP }

/** What is being built. Every template uses the same parts, tab-and-slot joints, layout and exports. */
enum class Template(val label: String) { ORGANIZER("Organizer"), TABLE("Table") }
enum class JointType { TAB_SLOT }

data class OrganizerSpec(
    val widthMm: Int = 300,
    val heightMm: Int = 160,
    val depthMm: Int = 180,
    val materialThicknessMm: Int = 3,
    val compartments: Int = 2,
    val clearanceMm: Double = 0.2,
    val template: Template = Template.ORGANIZER,
) {
    fun validate() {
        if (template == Template.TABLE) {
            require(widthMm in 150..800) { "Table width must be 150–800 mm" }
            require(heightMm in 60..400) { "Table height must be 60–400 mm" }
            require(depthMm in 100..500) { "Table depth must be 100–500 mm" }
            require(materialThicknessMm in 3..12) { "A table needs 3–12 mm material" }
            require(clearanceMm in 0.0..1.5) { "Joint clearance must be 0–1.5 mm" }
            require(depthMm >= materialThicknessMm * 12) { "The table is too shallow for its leg joints" }
            return
        }
        require(widthMm in 100..800) { "Width must be 100–800 mm" }
        require(heightMm in 60..500) { "Height must be 60–500 mm" }
        require(depthMm in 80..600) { "Depth must be 80–600 mm" }
        require(materialThicknessMm in 1..12) { "Material thickness must be 1–12 mm" }
        require(compartments in 1..4) { "Choose 1–4 compartments" }
        require(clearanceMm in 0.0..1.5) { "Joint clearance must be 0–1.5 mm" }
        require(widthMm / compartments >= materialThicknessMm * 3) { "The organizer is too narrow for this many compartments" }
    }
}

data class Tab(
    val edge: Edge,
    val centerMm: Double,
    val spanMm: Double,
    val depthMm: Double,
)

data class Part(
    val id: String,
    val name: String,
    val widthMm: Double,
    val heightMm: Double,
    val outline: List<Point2>,
    val cutouts: List<Cutout>,
    val tabs: List<Tab>,
    val plane: PanelPlane,
    val dividerIndex: Int = 0,
    val colorHex: String,
) {
    val minX: Double get() = outline.minOf { it.x }
    val minY: Double get() = outline.minOf { it.y }
    val maxX: Double get() = outline.maxOf { it.x }
    val maxY: Double get() = outline.maxOf { it.y }
    val boundsWidth: Double get() = maxX - minX
    val boundsHeight: Double get() = maxY - minY
    val materialAreaMm2: Double get() = polygonArea(outline) - cutouts.sumOf { it.width * it.height }
}

/** One physical tab and its mating slot. Both cutting and 3D assembly use this record. */
data class Joint(
    val id: String,
    val partA: String,
    val edgeA: Edge,
    val tabCenterMm: Double,
    val partB: String,
    val edgeB: Edge,
    val slotCenter: Point2,
    val tabWidthMm: Double,
    val tabDepthMm: Double,
    val slotWidthMm: Double,
    val slotLengthMm: Double,
    val insertionDirection: Point3,
    val clearanceMm: Double,
    val type: JointType = JointType.TAB_SLOT,
)

data class OrganizerDesign(
    val spec: OrganizerSpec,
    val parts: List<Part>,
    val joints: List<Joint>,
) {
    val totalMaterialAreaMm2: Double get() = parts.sumOf { it.materialAreaMm2 }
    fun part(id: String): Part? = parts.firstOrNull { it.id == id }
}

object OrganizerGenerator {
    fun generate(spec: OrganizerSpec): OrganizerDesign {
        spec.validate()
        if (spec.template == Template.TABLE) return table(spec)
        val t = spec.materialThicknessMm.toDouble()
        val fit = spec.clearanceMm
        val tabSpan = minOf(18.0, maxOf(12.0, t * 5))
        val tabDepth = t * 2
        val slotWidth = t + fit
        val slotLength = tabSpan + fit
        val joints = mutableListOf<Joint>()
        val tabsByPart = mutableMapOf<String, MutableList<Tab>>()
        val slotsByPart = mutableMapOf<String, MutableList<Cutout>>()

        fun tabSlot(
            id: String,
            tabPart: String,
            tabEdge: Edge,
            tabCenter: Double,
            slotPart: String,
            slotEdge: Edge,
            slotCenter: Point2,
            width: Double = tabSpan,
            depth: Double = tabDepth,
            slotW: Double = slotWidth,
            slotL: Double = slotLength,
            slotLongAxisX: Boolean = false,
            direction: Point3 = Point3(0.0, 0.0, 1.0),
        ) {
            tabsByPart.getOrPut(tabPart) { mutableListOf() }.add(Tab(tabEdge, tabCenter, width, depth))
            val cutW = if (slotLongAxisX) slotL else slotW
            val cutH = if (slotLongAxisX) slotW else slotL
            slotsByPart.getOrPut(slotPart) { mutableListOf() }.add(
                Cutout(slotCenter.x - cutW / 2, slotCenter.y - cutH / 2, cutW, cutH),
            )
            joints += Joint(id, tabPart, tabEdge, tabCenter, slotPart, slotEdge, slotCenter,
                width, depth, slotW, slotL, direction, fit)
        }

        val width = spec.widthMm.toDouble()
        val height = spec.heightMm.toDouble()
        val depth = spec.depthMm.toDouble()
        val innerWidth = width - t
        val innerDepth = depth - t
        val compartments = spec.compartments

        // Bottom tabs seat in the base. Two per wall keeps the generated pattern compact and legible.
        for (i in 0..1) {
            val x = innerWidth * (i + 1) / 3
            tabSlot("back-base-${i + 1}", "back", Edge.BOTTOM, x, "base", Edge.TOP,
                Point2(t / 2 + x, t / 2), slotLongAxisX = true)
            tabSlot("front-base-${i + 1}", "front", Edge.BOTTOM, x, "base", Edge.BOTTOM,
                Point2(t / 2 + x, depth - t / 2), slotLongAxisX = true)

            val y = innerDepth * (i + 1) / 3
            tabSlot("left-base-${i + 1}", "left", Edge.BOTTOM, y, "base", Edge.LEFT,
                Point2(t / 2, t / 2 + y))
            tabSlot("right-base-${i + 1}", "right", Edge.BOTTOM, y, "base", Edge.RIGHT,
                Point2(width - t / 2, t / 2 + y))
            for (dividerIndex in 1 until compartments) {
                val dividerX = innerWidth * dividerIndex / compartments
                tabSlot("divider-$dividerIndex-base-${i + 1}", "divider-$dividerIndex", Edge.BOTTOM, y, "base", Edge.BOTTOM,
                    Point2(t / 2 + dividerX, t / 2 + y), direction = Point3(0.0, 0.0, 1.0))
            }
        }

        // End tabs tie the side walls into the front/back walls; divider ends tie into both long walls.
        val lockHeight = height * 0.56
        for (front in listOf(true, false)) {
            val longPart = if (front) "front" else "back"
            for (side in listOf("left", "right")) {
                val wallEdge = if (side == "left") Edge.LEFT else Edge.RIGHT
                val sideSlotX = if (front) t / 2 + fit / 2 else innerDepth - t / 2 - fit / 2
                val sidePart = side
                tabSlot("$longPart-$side-${if (front) "front" else "back"}", longPart,
                    wallEdge, lockHeight, sidePart,
                    if (front) Edge.LEFT else Edge.RIGHT,
                    Point2(sideSlotX, lockHeight), width = tabSpan, depth = tabDepth,
                    slotW = slotWidth, slotL = slotLength,
                    direction = Point3(if (side == "left") 1.0 else -1.0, 0.0, 0.0))
            }

            for (i in 1 until compartments) {
                val divider = "divider-$i"
                val dividerX = innerWidth * i / compartments
                tabSlot("$longPart-$divider", divider,
                    if (front) Edge.LEFT else Edge.RIGHT,
                    lockHeight, longPart,
                    if (front) Edge.TOP else Edge.BOTTOM,
                    Point2(dividerX, lockHeight), width = tabSpan, depth = tabDepth,
                    slotW = slotWidth, slotL = slotLength,
                    direction = Point3(0.0, if (front) 1.0 else -1.0, 0.0))
            }
        }

        val colors = listOf("#C9D8C5", "#E7C99A", "#C8D5E2", "#E6C3B7", "#D5C9E7", "#BBD7CF")
        fun part(id: String, name: String, w: Double, h: Double, plane: PanelPlane, index: Int = 0): Part {
            val tabs = tabsByPart[id].orEmpty()
            val slots = slotsByPart[id].orEmpty().mapNotNull { cut ->
                val left = max(0.0, cut.x); val top = max(0.0, cut.y)
                val right = min(w, cut.x + cut.width); val bottom = min(h, cut.y + cut.height)
                if (right <= left || bottom <= top) null else Cutout(left, top, right - left, bottom - top)
            }
            return Part(id, name, w, h, outlineWithTabs(w, h, tabs), slots, tabs,
                plane, index, colors[(partsIndex(id, compartments)) % colors.size])
        }

        val parts = buildList {
            add(part("base", "Base", width, depth, PanelPlane.FLOOR))
            add(part("back", "Back", innerWidth, height, PanelPlane.BACK))
            add(part("front", "Front", innerWidth, height, PanelPlane.FRONT))
            add(part("left", "Left side", innerDepth, height, PanelPlane.LEFT_SIDE))
            add(part("right", "Right side", innerDepth, height, PanelPlane.RIGHT_SIDE))
            for (i in 1 until compartments) add(part("divider-$i", "Divider $i", innerDepth, height, PanelPlane.DIVIDER, i))
        }

        val design = OrganizerDesign(spec, parts, joints)
        validateGenerated(design)
        return design
    }

    /**
     * A small table (desk shelf, monitor riser): a top, two legs and a back stretcher against racking.
     * Each leg's top edge has two tabs into slots in the top; the stretcher's ends tab into both legs.
     */
    private fun table(spec: OrganizerSpec): OrganizerDesign {
        val t = spec.materialThicknessMm.toDouble()
        val fit = spec.clearanceMm
        val tabSpan = minOf(18.0, maxOf(12.0, t * 5))
        val tabDepth = t
        val slotW = t + fit
        val slotL = tabSpan + fit
        val width = spec.widthMm.toDouble()
        val height = spec.heightMm.toDouble()
        val depth = spec.depthMm.toDouble()
        val innerDepth = depth - t
        val legHeight = height - t                       // the top's thickness sits above the legs
        val stretcherHeight = minOf(70.0, legHeight * 0.45)
        val joints = mutableListOf<Joint>()
        val tabs = mutableMapOf<String, MutableList<Tab>>()
        val slots = mutableMapOf<String, MutableList<Cutout>>()
        fun tabSlot(id: String, tabPart: String, tabEdge: Edge, center: Double, slotPart: String, slotEdge: Edge,
                    slot: Point2, longAxisX: Boolean, direction: Point3) {
            tabs.getOrPut(tabPart) { mutableListOf() } += Tab(tabEdge, center, tabSpan, tabDepth)
            val w = if (longAxisX) slotL else slotW
            val h = if (longAxisX) slotW else slotL
            slots.getOrPut(slotPart) { mutableListOf() } += Cutout(slot.x - w / 2, slot.y - h / 2, w, h)
            joints += Joint(id, tabPart, tabEdge, center, slotPart, slotEdge, slot, tabSpan, tabDepth, slotW, slotL, direction, fit)
        }
        for (i in 0..1) {
            val y = innerDepth * (i + 1) / 3
            tabSlot("left-top-${i + 1}", "left", Edge.TOP, y, "top", Edge.LEFT, Point2(t * 1.5, t / 2 + y), false, Point3(0.0, 0.0, 1.0))
            tabSlot("right-top-${i + 1}", "right", Edge.TOP, y, "top", Edge.RIGHT, Point2(width - t * 1.5, t / 2 + y), false, Point3(0.0, 0.0, 1.0))
        }
        val lock = stretcherHeight / 2
        // the stretcher stands 1.5 t behind the back edge: leg x = t (leg planes start at t/2)
        tabSlot("stretcher-left", "stretcher", Edge.LEFT, lock, "left", Edge.LEFT, Point2(t, lock), false, Point3(-1.0, 0.0, 0.0))
        tabSlot("stretcher-right", "stretcher", Edge.RIGHT, lock, "right", Edge.LEFT, Point2(t, lock), false, Point3(1.0, 0.0, 0.0))

        val colors = listOf("#E7C99A", "#C9D8C5", "#C8D5E2", "#E6C3B7")
        fun part(id: String, name: String, w: Double, h: Double, plane: PanelPlane, color: Int): Part {
            val ts = tabs[id].orEmpty()
            val cs = slots[id].orEmpty().mapNotNull { c ->
                val l = max(0.0, c.x); val tp = max(0.0, c.y); val r = min(w, c.x + c.width); val b = min(h, c.y + c.height)
                if (r <= l || b <= tp) null else Cutout(l, tp, r - l, b - tp)
            }
            return Part(id, name, w, h, outlineWithTabs(w, h, ts), cs, ts, plane, 0, colors[color])
        }
        val parts = listOf(
            part("top", "Top", width, depth, PanelPlane.TOP, 0),
            part("left", "Left leg", innerDepth, legHeight, PanelPlane.LEFT_SIDE, 1),
            part("right", "Right leg", innerDepth, legHeight, PanelPlane.RIGHT_SIDE, 2),
            // between the legs' inner faces (legs occupy t..2t and w-2t..w-t); its end tabs pass through the legs
            part("stretcher", "Back stretcher", width - 4 * t, stretcherHeight, PanelPlane.BACK, 3),
        )
        val design = OrganizerDesign(spec, parts, joints)
        validateGenerated(design)
        return design
    }

    private fun partsIndex(id: String, compartments: Int): Int = when {
        id == "base" -> 0
        id == "back" -> 1
        id == "front" -> 2
        id == "left" -> 3
        id == "right" -> 4
        else -> 4 + id.removePrefix("divider-").toIntOrNull().orEmptyIndex(compartments)
    }

    private fun Int?.orEmptyIndex(compartments: Int): Int = this ?: compartments

    private fun validateGenerated(design: OrganizerDesign) {
        require(design.parts.map { it.id }.distinct().size == design.parts.size)
        require(design.joints.map { it.id }.distinct().size == design.joints.size)
        design.joints.forEach { joint ->
            require(design.part(joint.partA)?.tabs?.any { it.edge == joint.edgeA && abs(it.centerMm - joint.tabCenterMm) < 0.001 } == true)
            require(design.part(joint.partB)?.cutouts?.any {
                joint.slotCenter.x >= it.x - 0.001 && joint.slotCenter.x <= it.x + it.width + 0.001 &&
                    joint.slotCenter.y >= it.y - 0.001 && joint.slotCenter.y <= it.y + it.height + 0.001
            } == true)
        }
        require(design.parts.all { it.outline.size >= 4 && it.materialAreaMm2 > 0 })
        design.parts.forEach { part ->
            require(part.cutouts.all { it.x >= 0 && it.y >= 0 && it.x + it.width <= part.widthMm && it.y + it.height <= part.heightMm })
        }
    }
}

private fun outlineWithTabs(width: Double, height: Double, tabs: List<Tab>): List<Point2> {
    val points = mutableListOf<Point2>()
    fun add(p: Point2) { if (points.lastOrNull() != p) points += p }
    fun edge(start: Point2, end: Point2, edge: Edge, outward: (Double) -> Point2) {
        val horizontal = edge == Edge.TOP || edge == Edge.BOTTOM
        val startValue = if (horizontal) start.x else start.y
        val endValue = if (horizontal) end.x else end.y
        val direction = if (endValue >= startValue) 1 else -1
        val matching = tabs.filter { it.edge == edge }.sortedBy { it.centerMm * direction }
        var cursor = startValue
        add(start)
        for (tab in matching) {
            val a = tab.centerMm - tab.spanMm / 2 * direction
            val b = tab.centerMm + tab.spanMm / 2 * direction
            if ((a - cursor) * direction < -0.001 || (endValue - b) * direction < -0.001) continue
            val baseStart = if (horizontal) Point2(a, start.y) else Point2(start.x, a)
            val outerStart = outward(tab.depthMm).let { if (horizontal) Point2(a, it.y) else Point2(it.x, a) }
            val outerEnd = if (horizontal) Point2(b, outerStart.y) else Point2(outerStart.x, b)
            val baseEnd = if (horizontal) Point2(b, end.y) else Point2(end.x, b)
            add(baseStart); add(outerStart); add(outerEnd); add(baseEnd)
            cursor = b
        }
        add(end)
    }
    edge(Point2(0.0, 0.0), Point2(width, 0.0), Edge.TOP) { d -> Point2(0.0, -d) }
    edge(Point2(width, 0.0), Point2(width, height), Edge.RIGHT) { d -> Point2(width + d, 0.0) }
    edge(Point2(width, height), Point2(0.0, height), Edge.BOTTOM) { d -> Point2(0.0, height + d) }
    edge(Point2(0.0, height), Point2(0.0, 0.0), Edge.LEFT) { d -> Point2(-d, 0.0) }
    return points
}

fun polygonArea(points: List<Point2>): Double {
    if (points.size < 3) return 0.0
    var sum = 0.0
    points.indices.forEach { i ->
        val a = points[i]
        val b = points[(i + 1) % points.size]
        sum += a.x * b.y - b.x * a.y
    }
    return abs(sum) / 2
}

fun Part.assembledPoint(point: Point2, spec: OrganizerSpec): Point3 {
    val w = spec.widthMm.toDouble()
    val h = spec.heightMm.toDouble()
    val d = spec.depthMm.toDouble()
    val t = spec.materialThicknessMm.toDouble()
    if (spec.template == Template.TABLE) {
        val legTop = h - t
        val zt = legTop - point.y
        return when (plane) {
            PanelPlane.TOP -> Point3(point.x, point.y, h)
            PanelPlane.LEFT_SIDE -> Point3(t * 1.5, t / 2 + point.x, zt)
            PanelPlane.RIGHT_SIDE -> Point3(w - t * 1.5, t / 2 + point.x, zt)
            else -> Point3(2 * t + point.x, t * 1.5, zt)   // back stretcher, just under the top
        }
    }
    val z = h - point.y
    return when (plane) {
        PanelPlane.TOP -> Point3(point.x, point.y, h)
        PanelPlane.FLOOR -> Point3(point.x, point.y, 0.0)
        PanelPlane.BACK -> Point3(spec.materialThicknessMm / 2.0 + point.x, spec.materialThicknessMm / 2.0, z)
        PanelPlane.FRONT -> Point3(spec.materialThicknessMm / 2.0 + point.x, d - spec.materialThicknessMm / 2.0, z)
        PanelPlane.LEFT_SIDE -> Point3(spec.materialThicknessMm / 2.0, spec.materialThicknessMm / 2.0 + point.x, z)
        PanelPlane.RIGHT_SIDE -> Point3(w - spec.materialThicknessMm / 2.0, spec.materialThicknessMm / 2.0 + point.x, z)
        PanelPlane.DIVIDER -> Point3(spec.materialThicknessMm / 2.0 + (w - spec.materialThicknessMm) * dividerIndex / spec.compartments, spec.materialThicknessMm / 2.0 + point.x, z)
    }
}
