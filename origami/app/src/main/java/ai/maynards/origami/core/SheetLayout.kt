package ai.maynards.origami.core

import kotlin.math.max

data class StockSheet(val widthMm: Int = 600, val heightMm: Int = 400, val marginMm: Int = 5, val gapMm: Int = 2) {
    init { require(widthMm > 0 && heightMm > 0 && marginMm >= 0 && gapMm >= 0) }
}

data class PartPlacement(
    val partId: String,
    /** Location of the rotated/unrotated part bounds, including tabs. */
    val xMm: Double,
    val yMm: Double,
    val rotated: Boolean,
    val boundsWidthMm: Double,
    val boundsHeightMm: Double,
)

data class LayoutPlan(
    val stock: StockSheet,
    val placements: List<PartPlacement>,
    val fits: Boolean,
    val layoutName: String,
    val materialAreaMm2: Double,
    val utilizationPercent: Double,
    val error: String? = null,
) {
    val wastePercent: Double get() = (100.0 - utilizationPercent).coerceAtLeast(0.0)
    fun placement(partId: String): PartPlacement? = placements.firstOrNull { it.partId == partId }
}

/** A small shelf packer that compares several deterministic orders and tries 90-degree rotation. */
object SheetLayoutPlanner {
    /** Loose one-row layout used only as the flat pose in the assembly animation. */
    fun previewLayout(design: OrganizerDesign): LayoutPlan {
        val margin = 12
        val gap = 14
        val height = (design.parts.maxOfOrNull { it.boundsHeight } ?: 1.0).toInt() + margin * 2
        val width = (design.parts.sumOf { it.boundsWidth }.toInt() + max(0, design.parts.size - 1) * gap + margin * 2).coerceAtLeast(100)
        val stock = StockSheet(width, height, margin, gap)
        var x = margin.toDouble()
        val placements = design.parts.map { part ->
            PartPlacement(part.id, x, margin.toDouble(), false, part.boundsWidth, part.boundsHeight).also { x += part.boundsWidth + gap }
        }
        return LayoutPlan(stock, placements, true, "Animation preview", design.totalMaterialAreaMm2,
            (design.totalMaterialAreaMm2 / (width.toDouble() * height) * 100).coerceIn(0.0, 100.0))
    }

    fun bestLayout(design: OrganizerDesign, stock: StockSheet = StockSheet()): LayoutPlan {
        val orders = listOf(
            "Tall first" to design.parts.sortedByDescending { it.boundsHeight },
            "Wide first" to design.parts.sortedByDescending { it.boundsWidth },
            "Area first" to design.parts.sortedByDescending { it.boundsWidth * it.boundsHeight },
            "Balanced" to design.parts.sortedByDescending { max(it.boundsWidth, it.boundsHeight) },
        )
        val allOrders = if (design.parts.size <= 8) {
            permutations(design.parts).mapIndexed { index, parts -> "Shelf arrangement ${index + 1}" to parts }
        } else emptySequence()
        val candidates = (orders.asSequence() + allOrders).mapNotNull { (name, parts) -> pack(parts, stock)?.let { name to it } }.toList()
        val area = design.totalMaterialAreaMm2
        val utilization = (area / (stock.widthMm.toDouble() * stock.heightMm) * 100.0).coerceIn(0.0, 100.0)
        val best = candidates.minByOrNull { (_, placements) ->
            val right = placements.maxOfOrNull { it.xMm + it.boundsWidthMm } ?: stock.marginMm.toDouble()
            val bottom = placements.maxOfOrNull { it.yMm + it.boundsHeightMm } ?: stock.marginMm.toDouble()
            (right - stock.marginMm) * (bottom - stock.marginMm)
        }
        return if (best != null) {
            LayoutPlan(stock, best.second, true, best.first, area, utilization)
        } else {
            LayoutPlan(stock, emptyList(), false, "No fitting layout", area, utilization,
                "These parts do not fit on a ${stock.widthMm} × ${stock.heightMm} mm sheet with a ${stock.marginMm} mm margin.")
        }
    }

    private fun permutations(parts: List<Part>): Sequence<List<Part>> = sequence {
        suspend fun SequenceScope<List<Part>>.build(prefix: List<Part>, remaining: List<Part>) {
            if (remaining.isEmpty()) {
                yield(prefix)
            } else {
                remaining.forEachIndexed { index, part ->
                    build(prefix + part, remaining.filterIndexed { i, _ -> i != index })
                }
            }
        }
        build(emptyList(), parts)
    }

    private fun pack(parts: List<Part>, stock: StockSheet): List<PartPlacement>? {
        val maxX = stock.widthMm - stock.marginMm
        val maxY = stock.heightMm - stock.marginMm
        val start = stock.marginMm.toDouble()
        var x = start
        var y = start
        var rowHeight = 0.0
        val placements = mutableListOf<PartPlacement>()
        for (part in parts) {
            val options = listOf(
                Triple(false, part.boundsWidth, part.boundsHeight),
                Triple(true, part.boundsHeight, part.boundsWidth),
            ).filter { (_, w, h) -> w <= maxX - start && h <= maxY - start }
            if (options.isEmpty()) return null

            fun chooseAt(currentX: Double, currentY: Double): Triple<Boolean, Double, Double>? = options
                .filter { (_, w, h) -> currentX + w <= maxX && currentY + h <= maxY }
                .minByOrNull { (_, w, h) -> max(rowHeight, h) * 10000 + (maxX - currentX - w) }

            var chosen = chooseAt(x, y)
            if (chosen == null) {
                x = start
                y += rowHeight + stock.gapMm
                rowHeight = 0.0
                if (y >= maxY) return null
                chosen = options
                    .filter { (_, w, h) -> x + w <= maxX && y + h <= maxY }
                    .minByOrNull { (_, w, h) -> h * 10000 + (maxX - x - w) }
            }
            if (chosen == null) return null
            val (rotated, w, h) = chosen
            placements += PartPlacement(part.id, x, y, rotated, w, h)
            x += w + stock.gapMm
            rowHeight = max(rowHeight, h)
        }
        return placements
    }
}

fun Part.sheetPoint(point: Point2, placement: PartPlacement): Point2 {
    val localX = point.x - minX
    val localY = point.y - minY
    return if (!placement.rotated) {
        Point2(placement.xMm + localX, placement.yMm + localY)
    } else {
        // Rotate clockwise within the part's bounding box.
        Point2(placement.xMm + boundsHeight - localY, placement.yMm + localX)
    }
}

fun Part.sheetCutout(cutout: Cutout, placement: PartPlacement): Cutout {
    val corners = listOf(
        Point2(cutout.x, cutout.y), Point2(cutout.x + cutout.width, cutout.y),
        Point2(cutout.x + cutout.width, cutout.y + cutout.height), Point2(cutout.x, cutout.y + cutout.height),
    ).map { sheetPoint(it, placement) }
    return Cutout(corners.minOf { it.x }, corners.minOf { it.y },
        corners.maxOf { it.x } - corners.minOf { it.x }, corners.maxOf { it.y } - corners.minOf { it.y })
}
