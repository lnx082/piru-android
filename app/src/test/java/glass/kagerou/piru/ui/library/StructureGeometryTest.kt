package glass.kagerou.piru.ui.library

import glass.kagerou.piru.engine.MoleculeAtom
import glass.kagerou.piru.engine.MoleculeBond
import glass.kagerou.piru.engine.MoleculeShape
import io.kotest.matchers.doubles.plusOrMinus as doublesPlusOrMinus
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Where each atom goes, and which rows are refused.
 *
 * ## Why the geometry needs tests and the drawing does not
 * A drawing cannot be asserted without a device, and most of what could be wrong with it is visible. The
 * **transform** is the exception: an inverted axis, a scale taken from the larger ratio, or a division by a
 * degenerate span all produce a picture that still looks like a molecule. A mirrored benzene ring looks exactly
 * like benzene.
 *
 * So `ShapeGeometry.place` is separated from the draw calls — it needs no `DrawScope` and no device — and this is
 * where the arithmetic is pinned.
 *
 * ## And why the refuse-to-draw cases matter more than the drawing
 * `molecule_shapes` is 958 rows of pipeline-generated JSON with no schema behind them. A bond index past the end
 * of the atom list would draw a line to nowhere, and a self-bond would draw a zero-length line that is invisible
 * — both are pictures that look deliberate. The reader drops those rows, and the section hides.
 */
class StructureGeometryTest {

    private fun atom(element: String, x: Double, y: Double) = MoleculeAtom(el = element, x = x, y = y)

    /** A benzene-like hexagon: six carbons, six bonds, spanning a square. */
    private fun hexagon() = MoleculeShape(
        atoms = listOf(
            atom("C", 0.0, 0.0),
            atom("C", 10.0, 0.0),
            atom("C", 15.0, 8.66),
            atom("C", 10.0, 17.32),
            atom("C", 0.0, 17.32),
            atom("C", -5.0, 8.66),
        ),
        bonds = (0 until 6).map { MoleculeBond(a = it, b = (it + 1) % 6, order = 2) },
    )

    // MARK: - The transform

    /**
     * The drawing stays inside the canvas.
     *
     * The property every other assertion depends on: a shape scaled by the **larger** ratio would overflow the
     * narrower axis, and the overflow is the part of the molecule the reader wanted.
     */
    @Test
    fun `every atom lands inside the canvas`() {
        val placed = ShapeGeometry.place(hexagon(), canvasWidth = 300f, canvasHeight = 120f)
        for (position in placed.positions) {
            (position.x >= 0f) shouldBe true
            (position.x <= 300f) shouldBe true
            (position.y >= 0f) shouldBe true
            (position.y <= 120f) shouldBe true
        }
    }

    /**
     * The narrower axis is the one that decides the scale.
     *
     * A wide canvas and a short one: the shape's own box is taller than it is wide, so the height decides. Asserted
     * by checking the drawing fills most of the short axis and only part of the long one — which is what "fit"
     * means, and what taking `max` instead of `min` would break.
     */
    @Test
    fun `the scale comes from the axis that runs out first`() {
        val placed = ShapeGeometry.place(hexagon(), canvasWidth = 1000f, canvasHeight = 100f)
        val usedHeight = placed.positions.maxOf { it.y } - placed.positions.minOf { it.y }
        val usedWidth = placed.positions.maxOf { it.x } - placed.positions.minOf { it.x }
        // The height is the constrained one, so it uses most of the canvas.
        (usedHeight > 70f) shouldBe true
        // And the width does not, because the shape is not that wide.
        (usedWidth < 400f) shouldBe true
    }

    /**
     * The y axis is **not** flipped.
     *
     * The catalogue's y increases downward, as the canvas's does, so the atom with the larger y is drawn lower on
     * the screen. Getting this backwards mirrors every molecule vertically — invisible on a symmetric ring like
     * this fixture's, which is precisely why the assertion uses two atoms with different y and compares their
     * order rather than asserting a coordinate.
     */
    @Test
    fun `a larger catalogue y draws lower on the screen`() {
        val placed = ShapeGeometry.place(hexagon(), canvasWidth = 300f, canvasHeight = 300f)
        // Atom 0 is at y 0.0 and atom 3 at y 17.32 — the top and bottom of the hexagon.
        (placed.positions[3].y > placed.positions[0].y) shouldBe true
    }

    /**
     * A molecule whose atoms share a row still gets a finite scale.
     *
     * A zero-height span divides by zero. The catalogue has no such row — the flattest is a chain with some y
     * variation — but a future pipeline run could produce one, and a crash on a detail screen is the wrong
     * outcome for a drawing.
     */
    @Test
    fun `a degenerate span does not divide by zero`() {
        val flat = MoleculeShape(
            atoms = listOf(atom("C", 0.0, 5.0), atom("O", 10.0, 5.0)),
            bonds = listOf(MoleculeBond(a = 0, b = 1)),
        )
        val placed = ShapeGeometry.place(flat, canvasWidth = 300f, canvasHeight = 120f)
        placed.positions.all { it.x.isFinite() && it.y.isFinite() } shouldBe true
    }

    /** A single atom is a legitimate shape, and it lands centred rather than at a corner. */
    @Test
    fun `a single atom centres`() {
        val lone = MoleculeShape(atoms = listOf(atom("Na", 3.0, 4.0)), bonds = emptyList())
        val placed = ShapeGeometry.place(lone, canvasWidth = 200f, canvasHeight = 100f)
        val centre = placed.positions.single()
        (centre.x to centre.y) shouldBe (100f to 50f)
        (kotlin.math.abs(centre.x - 100f) < 1f) shouldBe true
        (kotlin.math.abs(centre.y - 50f) < 1f) shouldBe true
    }

    /**
     * The atom radius has a floor and a ceiling.
     *
     * The floor keeps a 295-atom molecule's atoms visible; the ceiling keeps a three-atom molecule's from filling
     * the canvas. Both sides asserted, because a radius with only one clamp still looks reasonable most of the
     * time.
     */
    @Test
    fun `the atom radius is clamped at both ends`() {
        val tiny = MoleculeShape(
            atoms = listOf(atom("C", 0.0, 0.0), atom("C", 0.001, 0.001)),
            bonds = listOf(MoleculeBond(a = 0, b = 1)),
        )
        val tinyPlaced = ShapeGeometry.place(tiny, canvasWidth = 1000f, canvasHeight = 1000f)
        (tinyPlaced.atomRadius <= 14f) shouldBe true
        // And labels fit, because the radius is large.
        tinyPlaced.labelsFit shouldBe true

        // A wide molecule on a small canvas drives the radius to the floor.
        val wide = MoleculeShape(
            atoms = (0..200).map { atom("C", it.toDouble() * 5.0, 0.0) },
            bonds = (0 until 200).map { MoleculeBond(a = it, b = it + 1) },
        )
        val widePlaced = ShapeGeometry.place(wide, canvasWidth = 300f, canvasHeight = 180f)
        (widePlaced.atomRadius >= 3f) shouldBe true
        // And at that size a label would be a dot, so they are dropped.
        widePlaced.labelsFit shouldBe false
    }

    /** One position per atom, in the atoms' own order — the bond list indexes into it. */
    @Test
    fun `there is exactly one position per atom, in order`() {
        val shape = hexagon()
        val placed = ShapeGeometry.place(shape, canvasWidth = 300f, canvasHeight = 300f)
        placed.positions.size shouldBe shape.atoms.size
        // The order is the atoms': atom 1's x is greater than atom 0's, as the catalogue has them.
        (placed.positions[1].x > placed.positions[0].x) shouldBe true
    }

    // MARK: - Refusing to draw

    /** A bond index outside the atom list is refused, because it would draw a line to nowhere. */
    @Test
    fun `a bond past the end of the atom list is refused`() {
        MoleculeShape.parse(
            atoms = listOf(atom("C", 0.0, 0.0), atom("C", 1.0, 1.0)),
            bonds = listOf(MoleculeBond(a = 0, b = 7)),
        ) shouldBe null
        MoleculeShape.parse(
            atoms = listOf(atom("C", 0.0, 0.0), atom("C", 1.0, 1.0)),
            bonds = listOf(MoleculeBond(a = -1, b = 1)),
        ) shouldBe null
    }

    /**
     * A self-bond is refused.
     *
     * A zero-length line draws as nothing, so the row would look like a lone atom rather than a malformed one —
     * the failure would be invisible rather than wrong.
     */
    @Test
    fun `a bond from an atom to itself is refused`() {
        MoleculeShape.parse(
            atoms = listOf(atom("C", 0.0, 0.0)),
            bonds = listOf(MoleculeBond(a = 0, b = 0)),
        ) shouldBe null
    }

    /** No atoms is nothing to draw, and an atom with no element symbol is not an atom. */
    @Test
    fun `an empty or unlabelled shape is refused`() {
        MoleculeShape.parse(emptyList(), emptyList()) shouldBe null
        MoleculeShape.parse(listOf(atom("", 0.0, 0.0)), emptyList()) shouldBe null
        MoleculeShape.parse(listOf(atom("   ", 0.0, 0.0)), emptyList()) shouldBe null
    }

    /**
     * A valid shape is kept, and an empty bond list is **not** a failure.
     *
     * A lone atom is a legitimate drawing of a monatomic ion, and the catalogue stores `bonds_json` as null on
     * rows in practice. Treating a missing bond list as malformed would hide every such substance.
     */
    @Test
    fun `a valid shape is kept and a lone atom is valid`() {
        MoleculeShape.parse(listOf(atom("Na", 1.0, 2.0)), emptyList())
            .shouldBeInstanceOf<MoleculeShape>()
        val full = MoleculeShape.parse(hexagon().atoms, hexagon().bonds)
        full.shouldBeInstanceOf<MoleculeShape>()
        full!!.atoms.size shouldBe 6
        full.bonds.size shouldBe 6
    }

    /**
     * The spans and the origin, which the transform reads.
     *
     * `width` and `height` have a floor, so a degenerate shape scales by 1 rather than by zero; `minX`/`minY` are
     * the box's corner, so the drawing is offset into the canvas rather than drawn at the origin.
     */
    @Test
    fun `the box is reported from the atoms`() {
        val shape = hexagon()
        shape.minX shouldBe -5.0
        shape.minY shouldBe 0.0
        shape.width shouldBe (20.0 doublesPlusOrMinus 1e-9)
        shape.height shouldBe (17.32 doublesPlusOrMinus 1e-9)
        shape.centreX shouldBe (5.0 doublesPlusOrMinus 1e-9)
        shape.centreY shouldBe (8.66 doublesPlusOrMinus 1e-9)

        // A degenerate box has a **zero** span and is centred on its atom. Both halves matter: asserting
        // `width shouldBe MINIMUM_SPAN` passed whatever the floor was, which is how a floor of 2 instead of 1
        // survived four cycles — the flat atom then drew 42 pixels off centre and no assertion could see it.
        val flat = MoleculeShape(atoms = listOf(atom("C", 4.0, 4.0)), bonds = emptyList())
        flat.width shouldBe 0.0
        flat.height shouldBe 0.0
        flat.centreX shouldBe 4.0
        flat.centreY shouldBe 4.0

        // The floor is a Double of exactly one, and the **drawing** is what applies it — not the box.
        MoleculeShape.MINIMUM_SPAN shouldBe (1.0 doublesPlusOrMinus 1e-12)
    }

    /** A ring is recognised as one, which is the cheapest sanity property over 958 rows. */
    @Test
    fun `a ring is distinguishable from a chain`() {
        hexagon().looksCyclic shouldBe true
        val chain = MoleculeShape(
            atoms = listOf(atom("C", 0.0, 0.0), atom("C", 1.0, 0.0), atom("C", 2.0, 0.0)),
            bonds = listOf(MoleculeBond(a = 0, b = 1), MoleculeBond(a = 1, b = 2)),
        )
        chain.looksCyclic shouldBe false
    }

    /** The placement is centred, so the margin is symmetric. */
    @Test
    fun `the drawing is centred within its box`() {
        val placed = ShapeGeometry.place(hexagon(), canvasWidth = 400f, canvasHeight = 400f)
        val left = placed.positions.minOf { it.x }
        val right = placed.positions.maxOf { it.x }
        val top = placed.positions.minOf { it.y }
        val bottom = placed.positions.maxOf { it.y }
        // Equal margins on each axis, within a pixel. The plusOrMinus needs its own parentheses because it
        // binds tighter than the subtraction that produces the value.
        val leftMargin = left - 0f
        val rightMargin = 400f - right
        val topMargin = top - 0f
        val bottomMargin = 400f - bottom
        (leftMargin - rightMargin) shouldBe (0f plusOrMinus 1f)
        (topMargin - bottomMargin) shouldBe (0f plusOrMinus 1f)
    }
}
