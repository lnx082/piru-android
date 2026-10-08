package glass.kagerou.piru.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.R
import glass.kagerou.piru.engine.MoleculeBond
import glass.kagerou.piru.engine.MoleculeShape
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme
import androidx.compose.ui.graphics.nativeCanvas

/**
 * A substance's 2-D structure, drawn from the catalogue's own coordinates.
 *
 * Ported from the structure-diagram section upstream draws over `molecule_shapes`, which covers **958 of 1689**
 * substances with atoms at x/y positions and a bond list.
 *
 * ## Why the drawing is a scale rather than a layout
 * The coordinates are already a depiction — a pipeline laid the molecule out — so this is a scale into whatever
 * canvas it is given. Re-deriving positions would be a force-directed layout, which is a different project and
 * would disagree with the picture upstream shows for the same substance.
 *
 * ## The carbon convention
 * Carbon atoms are **vertices with no label**, which is how every chemistry drawing works: an unlabelled junction
 * in a skeletal formula is a carbon. Labelling them would fill a 295-atom molecule with 200 identical letters and
 * hide the shape, which is the only thing the diagram is for.
 *
 * ## The worst case is 295 atoms
 * A large molecule scales its own atoms down until they are sub-pixel. The drawing handles that by dropping the
 * labels once they cannot be read and keeping the bonds, so a big molecule reads as a skeletal formula rather than
 * as a smear of text.
 */
@Composable
fun StructureCard(shape: MoleculeShape) {
    val ink = PiruTheme.colors.tertiaryLabel
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.shell_section_structure), style = MaterialTheme.typography.titleSmall)
            Box(modifier = Modifier.fillMaxWidth().height(180.dp)) {
                Canvas(Modifier.fillMaxWidth().height(180.dp)) {
                    drawMolecule(shape, ink, labelInk = ink)
                }
            }
            Text(
                stringResource(R.string.shell_structure_note),
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
    }
}

/**
 * The element colours, by symbol.
 *
 * Muted rather than CPK-bright: this is one card in a list of prose sections, and a saturated palette would make
 * the structure the loudest thing on the screen. Carbon has no entry because carbon is unlabelled — see
 * [drawMolecule].
 */
private val ELEMENT_COLORS: Map<String, Color> = mapOf(
    "O" to Color(0xFFD32F2F),
    "N" to Color(0xFF1976D2),
    "S" to Color(0xFFF9A825),
    "P" to Color(0xFFEF6C00),
    "F" to Color(0xFF2E7D32),
    "Cl" to Color(0xFF2E7D32),
    "Br" to Color(0xFF6D4C41),
    "I" to Color(0xFF6A1B9A),
    "H" to Color(0xFF757575),
)

/**
 * Draws the shape into the current canvas, scaled to fit with a margin.
 *
 * [ShapeGeometry.place] does the arithmetic; this only issues draw calls, so the transform is testable without a
 * canvas.
 */
private fun DrawScope.drawMolecule(shape: MoleculeShape, bondInk: Color, labelInk: Color) {
    val placed = ShapeGeometry.place(
        shape = shape,
        canvasWidth = size.width,
        canvasHeight = size.height,
    )

    // Bonds first, so the atom labels sit on top of the line ends.
    for (bond in shape.bonds) {
        drawBond(
            from = placed.positions[bond.a],
            to = placed.positions[bond.b],
            order = bond.order,
            ink = bondInk,
            atomRadius = placed.atomRadius,
        )
    }

    // Then the atoms that carry a label. Carbon is a bare vertex, which is the convention.
    for ((index, atom) in shape.atoms.withIndex()) {
        val symbol = atom.el
        if (symbol.equals("C", ignoreCase = true)) continue
        val centre = placed.positions[index]
        drawCircle(
            color = Color.White,
            radius = placed.atomRadius,
            center = centre,
        )
        drawCircle(
            color = ELEMENT_COLORS[symbol] ?: labelInk,
            radius = placed.atomRadius,
            center = centre,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f),
        )
        // Labels only when they can be read: at 295 atoms the radius is a couple of pixels and a letter would be
        // an unreadable dot that makes the drawing look broken.
        if (placed.labelsFit) {
            drawAtomLabel(symbol, centre, placed.atomRadius, ELEMENT_COLORS[symbol] ?: labelInk)
        }
    }
}

/** A bond, drawn as one line or as the parallel lines a double or triple bond needs. */
private fun DrawScope.drawBond(
    from: Offset,
    to: Offset,
    order: Int,
    ink: Color,
    atomRadius: Float,
) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val length = kotlin.math.hypot(dx, dy)
    if (length <= 0.001f) return
    // Unit perpendicular, for offsetting multiple lines.
    val px = -dy / length
    val py = dx / length

    // Trim both ends by the atom radius so a line does not run under a label.
    val trim = atomRadius.coerceAtMost(length / 3f)
    val ux = dx / length
    val uy = dy / length
    val start = Offset(from.x + ux * trim, from.y + uy * trim)
    val end = Offset(to.x - ux * trim, to.y - uy * trim)

    val lines = when (order) {
        2 -> 2
        3 -> 3
        else -> 1
    }
    // A triple bond needs tighter spacing than a double one, and a thin stroke at this scale.
        .let { count -> if (count == 1) listOf(0f) else List(count) { i -> (i - (count - 1) / 2f) * 3f } }

    val stroke = if (order >= 2) 1.5f else 2f
    for (offset in lines) {
        drawLine(
            color = ink,
            start = Offset(start.x + px * offset, start.y + py * offset),
            end = Offset(end.x + px * offset, end.y + py * offset),
            strokeWidth = stroke,
        )
    }
}

/**
 * An atom's label, drawn with the platform canvas because Compose's `DrawScope` has no text.
 *
 * Sized from the atom radius and centred on it, which is approximate — the platform's text metrics are what
 * would centre it exactly, and a chemistry label a pixel off centre is not a defect worth the dependency.
 */
private fun DrawScope.drawAtomLabel(symbol: String, centre: Offset, radius: Float, ink: Color) {
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.argb(
            (ink.alpha * 255).toInt(),
            (ink.red * 255).toInt(),
            (ink.green * 255).toInt(),
            (ink.blue * 255).toInt(),
        )
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
        textSize = (radius * 1.3f).coerceAtLeast(6f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val baseline = centre.y - (paint.descent() + paint.ascent()) / 2f
    drawContext.canvas.nativeCanvas.drawText(symbol, centre.x, baseline, paint)
}

/**
 * Where each atom goes on the canvas, and how big a label can be.
 *
 * ## Why the arithmetic is separate from the drawing
 * The drawing needs a `DrawScope` and a device; this needs neither, so the part that can be wrong in a way nobody
 * notices — an inverted axis, a scale that ignores one dimension, a division by a zero span — is the part that can
 * be tested.
 *
 * ## The two rules that are easy to get backwards
 * - **`y` increases downward** in the catalogue's own coordinates as well as on the canvas, so the axis is *not*
 *   flipped. Getting this wrong draws every molecule mirrored vertically, which a symmetric ring hides.
 * - **The scale is the smaller of the two ratios**, so the drawing fits inside the canvas in both dimensions. Using
 *   the larger would overflow the narrower axis.
 */
internal object ShapeGeometry {

    /** A placed shape: one canvas position per atom, and the label radius that goes with them. */
    data class Placed(
        val positions: List<Offset>,
        val atomRadius: Float,
        /** False once the atoms are too small for a letter, which a 295-atom molecule reaches. */
        val labelsFit: Boolean,
    )

    /** The margin kept clear at the canvas edge, as a fraction of the smaller dimension. */
    const val MARGIN_FRACTION: Float = 0.08f

    /** Below this radius a label is a dot rather than a letter. */
    const val MINIMUM_LABEL_RADIUS: Float = 7f

    fun place(shape: MoleculeShape, canvasWidth: Float, canvasHeight: Float): Placed {
        val margin = minOf(canvasWidth, canvasHeight) * MARGIN_FRACTION
        val usableWidth = (canvasWidth - margin * 2).coerceAtLeast(1f)
        val usableHeight = (canvasHeight - margin * 2).coerceAtLeast(1f)
        // The smaller ratio, so the shape fits both ways.
        //
        // The span is floored **here** rather than on the shape: a lone atom has a zero span, and dividing by it
        // gives an infinite scale and `NaN` positions. The floor is a property of the drawing — it exists so a
        // scale can be computed — and putting it on the box corrupts the box's geometry, which the centre is
        // derived from. That confusion cost three cycles; the floor appears exactly once now.
        val spanX = shape.width.toFloat().coerceAtLeast(MoleculeShape.MINIMUM_SPAN.toFloat())
        val spanY = shape.height.toFloat().coerceAtLeast(MoleculeShape.MINIMUM_SPAN.toFloat())
        val scale = minOf(usableWidth / spanX, usableHeight / spanY)

        // Atom to screen is an affine map in two steps: subtract the box's centre so the box is centred on the
        // origin, scale, then add the canvas's centre. Written as one expression with nowhere for a stray
        // half-span to hide — which is how three earlier variants of this arithmetic survived reading.
        val canvasCentreX = canvasWidth / 2f
        val canvasCentreY = canvasHeight / 2f

        val positions = shape.atoms.map { atom ->
            Offset(
                x = canvasCentreX + ((atom.x - shape.centreX).toFloat() * scale),
                // Not flipped: the catalogue's y already increases downward, as the canvas's does.
                y = canvasCentreY + ((atom.y - shape.centreY).toFloat() * scale),
            )
        }

        // A radius from the drawing's own scale, with a floor so a sparse molecule's atoms are visible and a
        // ceiling so a three-atom molecule's are not enormous.
        val radius = (scale * 6f).coerceIn(3f, 14f)
        return Placed(
            positions = positions,
            atomRadius = radius,
            labelsFit = radius >= MINIMUM_LABEL_RADIUS,
        )
    }
}
