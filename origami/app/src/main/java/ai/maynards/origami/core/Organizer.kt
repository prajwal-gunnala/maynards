package ai.maynards.origami.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class Point2(val x: Double, val y: Double)
data class Point3(val x: Double, val y: Double, val z: Double)
data class Cutout(val x: Double, val y: Double, val width: Double, val height: Double)

enum class Edge { TOP, RIGHT, BOTTOM, LEFT }
enum class PanelPlane { FLOOR, FRONT, BACK, LEFT_SIDE, RIGHT_SIDE, DIVIDER, TOP, SHELF, LOOSE }

/** How parts hold together. Both are screwless; the wedged tenon also cannot pull apart until its wedge is knocked out. */
enum class JointStyle(val label: String) { TAB_SLOT("Tab & slot"), WEDGED("Wedged tenon") }

/** What is being built. Every template uses the same parts, tab-and-slot joints, layout and exports. */
enum class Template(val label: String) { ORGANIZER("Organizer"), TABLE("Table"), BOOKSHELF("Bookshelf") }
enum class JointType { TAB_SLOT }

data class OrganizerSpec(
    val widthMm: Int = 300,
    val heightMm: Int = 160,
    val depthMm: Int = 180,
    val materialThicknessMm: Int = 3,
    val compartments: Int = 2,
    val clearanceMm: Double = 0.2,
    val template: Template = Template.ORGANIZER,
    val joint: JointStyle = JointStyle.TAB_SLOT,
) {
    fun validate() {
        if (template == Template.BOOKSHELF) {
            require(widthMm in 300..1000) { "Bookshelf width must be 300–1000 mm" }
            require(heightMm in 300..1200) { "Bookshelf height must be 300–1200 mm" }
            require(depthMm in 150..400) { "Bookshelf depth must be 150–400 mm" }
            require(materialThicknessMm in 6..18) { "A bookshelf needs 6–18 mm material" }
            require(compartments in 2..6) { "Choose 2–6 shelves" }
            require(clearanceMm in 0.0..1.5) { "Joint clearance must be 0–1.5 mm" }
            require((heightMm - 40 - materialThicknessMm) / (compartments - 1) >= 120) { "Shelves would be under 120 mm apart" }
            return
        }
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
        val base = when (spec.template) {
            Template.TABLE -> table(spec)
            Template.BOOKSHELF -> bookshelf(spec)
            Template.ORGANIZER -> organizer(spec)
        }
        return if (spec.joint == JointStyle.WEDGED) wedged(base) else base
    }

    /**
     * Wedged (tusk) tenons: every tab is lengthened to come out past the far face of the board it passes
     * through, and gets a wedge hole whose inner edge sits [WEDGE_BITE] inside that face, so a driven wedge pulls
     * the joint tight. Distances come from the assembled 3D positions, so this works for every template.
     * One wedge part per joint is added to the cut list.
     */
    private fun wedged(design: OrganizerDesign): OrganizerDesign {
        val spec = design.spec
        val t = spec.materialThicknessMm.toDouble()
        val newTabs = design.parts.associate { it.id to it.tabs.toMutableList() }
        val holes = design.parts.associate { it.id to mutableListOf<Cutout>() }
        val wedges = ArrayList<Part>()
        for ((n, j) in design.joints.withIndex()) {
            val a = design.part(j.partA) ?: continue
            val b = design.part(j.partB) ?: continue
            val idx = newTabs.getValue(a.id).indexOfFirst { it.edge == j.edgeA && abs(it.centerMm - j.tabCenterMm) < 1e-6 }
            if (idx < 0) continue
            val tab = newTabs.getValue(a.id)[idx]
            // the tab's root point on its edge, and the outward direction in the part's own 2D coordinates
            val (root, out) = when (tab.edge) {
                Edge.TOP -> Point2(tab.centerMm, 0.0) to Point2(0.0, -1.0)
                Edge.BOTTOM -> Point2(tab.centerMm, a.heightMm) to Point2(0.0, 1.0)
                Edge.LEFT -> Point2(0.0, tab.centerMm) to Point2(-1.0, 0.0)
                Edge.RIGHT -> Point2(a.widthMm, tab.centerMm) to Point2(1.0, 0.0)
            }
            val p0 = a.assembledPoint(root, spec)
            val u = a.assembledPoint(Point2(root.x + out.x, root.y + out.y), spec) - p0      // 1 mm along the tab, in 3D
            val farFace = ((b.assembledPoint(j.slotCenter, spec) - p0) dot u) + t / 2         // mm from the edge
            val holeIn = farFace - WEDGE_BITE
            val depth = holeIn + t + 1.5 * t                                                   // hole + strong end
            newTabs.getValue(a.id)[idx] = tab.copy(depthMm = depth)
            val holeLen = tab.spanMm * 0.6
            val c0 = Point2(root.x + out.x * holeIn, root.y + out.y * holeIn)
            val c1 = Point2(root.x + out.x * (holeIn + t), root.y + out.y * (holeIn + t))
            val along = if (out.x != 0.0) Point2(0.0, 1.0) else Point2(1.0, 0.0)
            val x0 = minOf(c0.x, c1.x) - along.x * holeLen / 2; val y0 = minOf(c0.y, c1.y) - along.y * holeLen / 2
            holes.getValue(a.id) += Cutout(x0, y0, abs(c1.x - c0.x) + along.x * holeLen, abs(c1.y - c0.y) + along.y * holeLen)
            // a tapered wedge: thin end slides in, the thick end stops it
            val lw = 4 * t; val w0 = holeLen * 0.55; val w1 = holeLen * 1.2
            val outline = listOf(Point2(0.0, (w1 - w0) / 2), Point2(lw, 0.0), Point2(lw, w1), Point2(0.0, (w1 + w0) / 2))
            wedges += Part("wedge-${n + 1}", "Wedge ${n + 1}", lw, w1, outline, emptyList(), emptyList(), PanelPlane.LOOSE, n, "#B98C5A")
        }
        val parts = design.parts.map { p ->
            val tabs = newTabs.getValue(p.id)
            if (tabs == p.tabs && holes.getValue(p.id).isEmpty()) p
            else p.copy(outline = outlineWithTabs(p.widthMm, p.heightMm, tabs), tabs = tabs, cutouts = p.cutouts + holes.getValue(p.id))
        } + wedges
        val result = OrganizerDesign(spec, parts, design.joints)
        validateGenerated(result)
        return result
    }

    const val WEDGE_BITE = 0.5

    private fun organizer(spec: OrganizerSpec): OrganizerDesign {
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
                // the divider's x runs from the back wall (0) to the front wall (its width), so its front tab is on RIGHT
                tabSlot("$longPart-$divider", divider,
                    if (front) Edge.RIGHT else Edge.LEFT,
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

    /**
     * A bookshelf: two full-depth sides, [OrganizerSpec.compartments] shelves (the lowest on a 40 mm plinth, the
     * highest flush with the top) each with two tabs through each side, and a back strip at the top, tabbed
     * into both sides, against racking. Shelves stand 1 t in front of the back edge, behind the strip.
     */
    private fun bookshelf(spec: OrganizerSpec): OrganizerDesign {
        val t = spec.materialThicknessMm.toDouble()
        val fit = spec.clearanceMm
        val tabSpan = minOf(30.0, maxOf(12.0, t * 3))
        val slotW = t + fit
        val slotL = tabSpan + fit
        val w = spec.widthMm.toDouble(); val h = spec.heightMm.toDouble(); val d = spec.depthMm.toDouble()
        val shelfW = w - 2 * t
        val shelfD = d - t
        val strip = minOf(80.0, h * 0.2)
        val joints = mutableListOf<Joint>()
        val tabs = mutableMapOf<String, MutableList<Tab>>()
        val slots = mutableMapOf<String, MutableList<Cutout>>()
        fun tabSlot(id: String, tabPart: String, tabEdge: Edge, center: Double, slotPart: String, slot: Point2, longAxisX: Boolean, dir: Point3) {
            tabs.getOrPut(tabPart) { mutableListOf() } += Tab(tabEdge, center, tabSpan, t)
            val cw = if (longAxisX) slotL else slotW; val ch = if (longAxisX) slotW else slotL
            slots.getOrPut(slotPart) { mutableListOf() } += Cutout(slot.x - cw / 2, slot.y - ch / 2, cw, ch)
            joints += Joint(id, tabPart, tabEdge, center, slotPart, Edge.LEFT, slot, tabSpan, t, slotW, slotL, dir, fit)
        }
        val n = spec.compartments
        for (i in 0 until n) {
            val z = shelfZ(spec, i)                         // height of the shelf's middle plane
            for ((k, frac) in listOf(0.25, 0.75).withIndex()) {
                val c = shelfD * frac                      // along the shelf's depth
                // side coordinates: x = depth (0 at the back), y = down from the top
                tabSlot("shelf-${i + 1}-left-${k + 1}", "shelf-${i + 1}", Edge.LEFT, c, "left", Point2(t + c, h - z), true, Point3(-1.0, 0.0, 0.0))
                tabSlot("shelf-${i + 1}-right-${k + 1}", "shelf-${i + 1}", Edge.RIGHT, c, "right", Point2(t + c, h - z), true, Point3(1.0, 0.0, 0.0))
            }
        }
        tabSlot("strip-left", "strip", Edge.LEFT, strip / 2, "left", Point2(t / 2, strip / 2), false, Point3(-1.0, 0.0, 0.0))
        tabSlot("strip-right", "strip", Edge.RIGHT, strip / 2, "right", Point2(t / 2, strip / 2), false, Point3(1.0, 0.0, 0.0))

        val colors = listOf("#E7C99A", "#C9D8C5", "#C8D5E2", "#E6C3B7", "#D5C9E7")
        fun part(id: String, name: String, pw: Double, ph: Double, plane: PanelPlane, index: Int, color: Int): Part {
            val ts = tabs[id].orEmpty()
            val cs = slots[id].orEmpty().mapNotNull { c ->
                val l = max(0.0, c.x); val tp = max(0.0, c.y); val r = min(pw, c.x + c.width); val b = min(ph, c.y + c.height)
                if (r <= l || b <= tp) null else Cutout(l, tp, r - l, b - tp)
            }
            return Part(id, name, pw, ph, outlineWithTabs(pw, ph, ts), cs, ts, plane, index, colors[color % colors.size])
        }
        val parts = buildList {
            add(part("left", "Left side", d, h, PanelPlane.LEFT_SIDE, 0, 0))
            add(part("right", "Right side", d, h, PanelPlane.RIGHT_SIDE, 0, 0))
            for (i in 0 until n) add(part("shelf-${i + 1}", "Shelf ${i + 1}", shelfW, shelfD, PanelPlane.SHELF, i, 1 + i % 3))
            add(part("strip", "Back strip", shelfW, strip, PanelPlane.BACK, 0, 4))
        }
        val design = OrganizerDesign(spec, parts, joints)
        validateGenerated(design)
        return design
    }

    /** Middle-plane height of shelf [i]: the lowest on a 40 mm plinth, the highest with its top flush at H. */
    fun shelfZ(spec: OrganizerSpec, i: Int): Double {
        val t = spec.materialThicknessMm.toDouble()
        val low = 40 + t / 2; val high = spec.heightMm - t / 2
        return if (spec.compartments <= 1) low else low + (high - low) * i / (spec.compartments - 1)
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
        // every tab, followed straight out in 3D, must go through its slot where the other board really is
        jointMisses(design).let { require(it.isEmpty()) { "joint geometry: ${it.first()}" } }
        design.parts.forEach { part ->
            require(part.cutouts.all { c ->
                listOf(Point2(c.x, c.y), Point2(c.x + c.width, c.y), Point2(c.x, c.y + c.height), Point2(c.x + c.width, c.y + c.height))
                    .all { inOrOn(it, part.outline) }
            }) { "${part.id}: a cut-out leaves the part" }
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

/** Problems: joints whose tab centre line misses the slot where it crosses the other board's mid-plane. */
fun jointMisses(design: OrganizerDesign): List<String> {
    val spec = design.spec
    return design.joints.mapNotNull { j ->
        val a = design.part(j.partA)!!; val b = design.part(j.partB)!!
        val tab = a.tabs.first { it.edge == j.edgeA && kotlin.math.abs(it.centerMm - j.tabCenterMm) < 1e-6 }
        val (root, out) = when (tab.edge) {
            Edge.TOP -> Point2(tab.centerMm, 0.0) to Point2(0.0, -1.0)
            Edge.BOTTOM -> Point2(tab.centerMm, a.heightMm) to Point2(0.0, 1.0)
            Edge.LEFT -> Point2(0.0, tab.centerMm) to Point2(-1.0, 0.0)
            Edge.RIGHT -> Point2(a.widthMm, tab.centerMm) to Point2(1.0, 0.0)
        }
        val p0 = a.assembledPoint(root, spec)
        val u = a.assembledPoint(Point2(root.x + out.x, root.y + out.y), spec) - p0
        // B's plane: origin and in-plane axes
        val o = b.assembledPoint(Point2(0.0, 0.0), spec)
        val bx = b.assembledPoint(Point2(1.0, 0.0), spec) - o; val by = b.assembledPoint(Point2(0.0, 1.0), spec) - o
        val n = (bx cross by).unit()
        val denom = u dot n
        if (kotlin.math.abs(denom) < 1e-9) return@mapNotNull "${j.id}: tab runs parallel to ${b.id}"
        val s = ((o - p0) dot n) / denom                     // mm along the tab to B's mid-plane
        if (s < 0 || s > tab.depthMm + 1e-6) return@mapNotNull "${j.id}: ${b.id}'s mid-plane is ${"%.1f".format(s)} mm along a ${tab.depthMm} mm tab"
        val q = p0 + u * s - o
        val qx = (q dot bx) / (bx dot bx); val qy = (q dot by) / (by dot by)
        val hit = b.cutouts.any { qx in (it.x - 1e-6)..(it.x + it.width + 1e-6) && qy in (it.y - 1e-6)..(it.y + it.height + 1e-6) }
        if (hit) null else "${j.id}: tab meets ${b.id} at (${"%.1f".format(qx)}, ${"%.1f".format(qy)}), no slot there"
    }
}

/** A joint seen in section along its tab: everything in mm from the tab's root on its own edge. */
data class JointSection(val thickness: Double, val clearance: Double, val boardMid: Double, val tabDepth: Double,
                        val tabSpan: Double, val hole: ClosedFloatingPointRange<Double>?, val wedgeThin: Double, val wedgeThick: Double,
                        /** where the tab crosses the other board, in assembled mm (x right, y depth, z up) */
                        val at: Point3 = Point3(0.0, 0.0, 0.0))

/** The section of [joint]: where the other board sits along the tab, how far the tab goes, and the wedge hole if any. */
fun jointSection(design: OrganizerDesign, joint: Joint): JointSection {
    val spec = design.spec
    val t = spec.materialThicknessMm.toDouble()
    val a = design.part(joint.partA)!!; val b = design.part(joint.partB)!!
    val tab = a.tabs.first { it.edge == joint.edgeA && abs(it.centerMm - joint.tabCenterMm) < 1e-6 }
    val (root, out) = when (tab.edge) {
        Edge.TOP -> Point2(tab.centerMm, 0.0) to Point2(0.0, -1.0)
        Edge.BOTTOM -> Point2(tab.centerMm, a.heightMm) to Point2(0.0, 1.0)
        Edge.LEFT -> Point2(0.0, tab.centerMm) to Point2(-1.0, 0.0)
        Edge.RIGHT -> Point2(a.widthMm, tab.centerMm) to Point2(1.0, 0.0)
    }
    val p0 = a.assembledPoint(root, spec)
    val u = a.assembledPoint(Point2(root.x + out.x, root.y + out.y), spec) - p0
    val mid = (b.assembledPoint(joint.slotCenter, spec) - p0) dot u
    val hole = if (spec.joint == JointStyle.WEDGED) (mid + t / 2 - OrganizerGenerator.WEDGE_BITE).let { it..(it + t) } else null
    val holeLen = tab.spanMm * 0.6
    return JointSection(t, spec.clearanceMm, mid, tab.depthMm, tab.spanMm, hole, holeLen * 0.55, holeLen * 1.2, p0 + u * mid)
}

/** Point inside or on the boundary of a simple polygon (ray casting with an edge tolerance). */
internal fun inOrOn(p: Point2, poly: List<Point2>, eps: Double = 1e-6): Boolean {
    var inside = false
    for (i in poly.indices) {
        val a = poly[i]; val b = poly[(i + 1) % poly.size]
        val cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
        if (abs(cross) <= eps * maxOf(1.0, abs(b.x - a.x) + abs(b.y - a.y)) &&
            p.x in (minOf(a.x, b.x) - eps)..(maxOf(a.x, b.x) + eps) && p.y in (minOf(a.y, b.y) - eps)..(maxOf(a.y, b.y) + eps)) return true
        if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
    }
    return inside
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
    if (spec.template == Template.BOOKSHELF) {
        return when (plane) {
            PanelPlane.LEFT_SIDE -> Point3(t / 2, point.x, h - point.y)
            PanelPlane.RIGHT_SIDE -> Point3(w - t / 2, point.x, h - point.y)
            PanelPlane.SHELF -> Point3(t + point.x, t + point.y, OrganizerGenerator.shelfZ(spec, dividerIndex))
            else -> Point3(t + point.x, t / 2, h - point.y)       // back strip at the top, behind the shelves
        }
    }
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
        PanelPlane.SHELF -> Point3(point.x, point.y, h)   // organizers have no shelves
        PanelPlane.LOOSE -> Point3(point.x, point.y, 0.0)  // wedges are loose parts, not placed
        PanelPlane.FLOOR -> Point3(point.x, point.y, 0.0)
        PanelPlane.BACK -> Point3(spec.materialThicknessMm / 2.0 + point.x, spec.materialThicknessMm / 2.0, z)
        PanelPlane.FRONT -> Point3(spec.materialThicknessMm / 2.0 + point.x, d - spec.materialThicknessMm / 2.0, z)
        PanelPlane.LEFT_SIDE -> Point3(spec.materialThicknessMm / 2.0, spec.materialThicknessMm / 2.0 + point.x, z)
        PanelPlane.RIGHT_SIDE -> Point3(w - spec.materialThicknessMm / 2.0, spec.materialThicknessMm / 2.0 + point.x, z)
        PanelPlane.DIVIDER -> Point3(spec.materialThicknessMm / 2.0 + (w - spec.materialThicknessMm) * dividerIndex / spec.compartments, spec.materialThicknessMm / 2.0 + point.x, z)
    }
}
