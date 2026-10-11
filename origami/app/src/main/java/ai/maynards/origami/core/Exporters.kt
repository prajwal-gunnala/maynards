package ai.maynards.origami.core

import java.util.Locale

object SvgExporter {
    /** Red hairline cut paths, grouped by part. This is a 1:1 mm SVG for a laser/CNC workflow. */
    fun render(design: OrganizerDesign, layout: LayoutPlan): String {
        require(layout.fits) { "Generate a fitting sheet layout before exporting SVG" }
        val out = StringBuilder()
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${layout.stock.widthMm}mm\" height=\"${layout.stock.heightMm}mm\" viewBox=\"0 0 ${layout.stock.widthMm} ${layout.stock.heightMm}\">\n")
        out.append("  <title>Screwless organizer cutting pattern</title>\n")
        out.append("  <desc>${design.spec.widthMm} x ${design.spec.depthMm} x ${design.spec.heightMm} mm; ${design.spec.materialThicknessMm} mm stock; ${design.parts.size} parts; ${design.joints.size} tab-slot joints. Red hairlines are cuts.</desc>\n")
        out.append("  <g fill=\"none\" stroke=\"#ff0000\" stroke-width=\"0.1\" vector-effect=\"non-scaling-stroke\">\n")
        for (part in design.parts) {
            val placement = layout.placement(part.id) ?: error("Missing placement for ${part.name}")
            val outline = part.outline.map { part.sheetPoint(it, placement) }
            out.append("    <path id=\"${xml(part.id)}\" d=\"${polygonPath(outline)}\"/>\n")
            for ((index, cutout) in part.cutouts.withIndex()) {
                val r = part.sheetCutout(cutout, placement)
                out.append("    <rect id=\"${xml(part.id)}-slot-${index + 1}\" x=\"${fmt(r.x)}\" y=\"${fmt(r.y)}\" width=\"${fmt(r.width)}\" height=\"${fmt(r.height)}\"/>\n")
            }
        }
        out.append("  </g>\n</svg>\n")
        return out.toString()
    }
}

object ProjectJsonExporter {
    fun render(design: OrganizerDesign, layout: LayoutPlan): String = buildString {
        val flatPreview = if (layout.fits) layout else SheetLayoutPlanner.previewLayout(design)
        appendLine("{")
        appendLine("  \"schema\": \"maynards.origami.organizer.v1\",")
        appendLine("  \"units\": \"mm\",")
        appendLine("  \"spec\": {\"width\": ${design.spec.widthMm}, \"height\": ${design.spec.heightMm}, \"depth\": ${design.spec.depthMm}, \"materialThickness\": ${design.spec.materialThicknessMm}, \"compartments\": ${design.spec.compartments}, \"clearance\": ${fmt(design.spec.clearanceMm)}},")
        appendLine("  \"stock\": {\"width\": ${layout.stock.widthMm}, \"height\": ${layout.stock.heightMm}, \"margin\": ${layout.stock.marginMm}, \"gap\": ${layout.stock.gapMm}, \"fits\": ${layout.fits}, \"utilizationPercent\": ${fmt(layout.utilizationPercent)}},")
        appendLine("  \"flatPreview\": {\"kind\": \"${if (layout.fits) "stock-layout" else "animation-preview"}\", \"width\": ${flatPreview.stock.widthMm}, \"height\": ${flatPreview.stock.heightMm}},")
        appendLine("  \"parts\": [")
        design.parts.forEachIndexed { partIndex, part ->
            val placement = layout.placement(part.id) ?: flatPreview.placement(part.id)
            append("    {\"id\": ${json(part.id)}, \"name\": ${json(part.name)}, \"plane\": ${json(part.plane.name)}, \"width\": ${fmt(part.widthMm)}, \"height\": ${fmt(part.heightMm)}, \"color\": ${json(part.colorHex)}, \"outline\": [")
            append(part.outline.joinToString(",") { "[${fmt(it.x)},${fmt(it.y)}]" })
            append("], \"cutouts\": [")
            append(part.cutouts.joinToString(",") { "{\"x\":${fmt(it.x)},\"y\":${fmt(it.y)},\"width\":${fmt(it.width)},\"height\":${fmt(it.height)}}" })
            append("], \"flatPlacement\": ")
            if (placement == null) append("null") else append("{\"x\":${fmt(placement.xMm)},\"y\":${fmt(placement.yMm)},\"rotated\":${placement.rotated}}")
            append("}")
            appendLine(if (partIndex < design.parts.lastIndex) "," else "")
        }
        appendLine("  ],")
        appendLine("  \"joints\": [")
        design.joints.forEachIndexed { index, j ->
            append("    {\"id\":${json(j.id)},\"type\":\"TAB_SLOT\",\"partA\":${json(j.partA)},\"edgeA\":\"${j.edgeA}\",\"partB\":${json(j.partB)},\"edgeB\":\"${j.edgeB}\",\"tabCenterMm\":${fmt(j.tabCenterMm)},\"slotCenterMm\":[${fmt(j.slotCenter.x)},${fmt(j.slotCenter.y)}],\"tabWidthMm\":${fmt(j.tabWidthMm)},\"tabDepthMm\":${fmt(j.tabDepthMm)},\"slotWidthMm\":${fmt(j.slotWidthMm)},\"slotLengthMm\":${fmt(j.slotLengthMm)},\"clearanceMm\":${fmt(j.clearanceMm)},\"insertionDirection\":[${fmt(j.insertionDirection.x)},${fmt(j.insertionDirection.y)},${fmt(j.insertionDirection.z)}]}")
            appendLine(if (index < design.joints.lastIndex) "," else "")
        }
        appendLine("  ]")
        appendLine("}")
    }
}

private fun polygonPath(points: List<Point2>): String = buildString {
    points.forEachIndexed { i, point ->
        append(if (i == 0) "M" else "L")
        append(fmt(point.x)); append(' '); append(fmt(point.y))
    }
    append(" Z")
}

private fun fmt(value: Double): String = String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')
private fun xml(value: String): String = value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
private fun json(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
