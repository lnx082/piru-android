package glass.kagerou.piru.engine

import glass.kagerou.piru.model.ConfidenceTier
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Ported from the `scaledToHuman` cases in
 * `PiruTests/PharmacologyParametersTests.swift`, plus the properties the
 * allometric exponents imply.
 */
class InterspeciesScalingTest {

    private fun row(
        species: String?,
        vd: Double? = null,
        halfLife: Double? = null,
        clearance: Double? = null,
        confidence: ConfidenceTier = ConfidenceTier.UNVERIFIED,
    ) = PKRouteHit(
        id = 0,
        route = "oral",
        halfLifeMin = halfLife,
        vdLPerKg = vd,
        clearanceMlPerMinPerKg = clearance,
        species = species,
        sourceSlug = "test",
        confidence = confidence,
    )

    @Test
    fun `A human or unspecified row is returned unchanged`() {
        // No confidence floor and no half-life scaling: the measured human value
        // is already the target of the projection.
        val human = InterspeciesScaling.scaledToHuman(
            row("human", vd = 3.0, halfLife = 120.0, clearance = 5.0, confidence = ConfidenceTier.HIGH),
        )
        human.vdLPerKg shouldBe 3.0
        human.halfLifeMin shouldBe 120.0
        human.clearanceMlPerMinPerKg shouldBe 5.0
        human.confidence shouldBe ConfidenceTier.HIGH

        val unspecified = InterspeciesScaling.scaledToHuman(
            row(null, vd = 2.0, halfLife = 90.0, clearance = 3.0, confidence = ConfidenceTier.MEDIUM),
        )
        unspecified.halfLifeMin shouldBe 90.0
        unspecified.confidence shouldBe ConfidenceTier.MEDIUM
    }

    @Test
    fun `A rat row keeps its Vd per kg but floors its confidence`() {
        // Vd/kg is species-invariant — it reflects tissue partitioning, not body
        // size — so it passes through. Its *confidence* does not: a non-human Vd
        // is a class-default proxy and never a human-anchored value, whatever
        // grade the source carried.
        val rat = InterspeciesScaling.scaledToHuman(
            row("rat", vd = 2.6, halfLife = 60.0, clearance = 100.0, confidence = ConfidenceTier.HIGH),
        )
        rat.vdLPerKg shouldBe 2.6
        rat.confidence shouldBe ConfidenceTier.LOW
        // A small animal's half-life scales UP to a human.
        (rat.halfLifeMin!! > 60.0) shouldBe true
    }

    @Test
    fun `The allometric exponents are the classic quarter and three quarters`() {
        // Rat weighs 0.25 kg against the 70 kg reference, so the mass ratio is
        // 280: half-life scales by 280^0.25 ≈ 4.09 and clearance by 280^0.75
        // ≈ 68.4.
        val rat = InterspeciesScaling.scaledToHuman(row("rat", halfLife = 100.0, clearance = 10.0))
        rat.halfLifeMin shouldBe (100.0 * 280.0.pow(0.25) plusOrMinus 1e-9)
        rat.clearanceMlPerMinPerKg shouldBe (10.0 * 280.0.pow(0.75) plusOrMinus 1e-9)
    }

    @Test
    fun `A species with no reference weight is left unscaled rather than defaulted`() {
        // Projecting from an unknown body mass would invent a scale factor. An
        // unscaled animal row badged at its own low confidence is the honest
        // answer; a silently-scaled one would look like a human measurement.
        val unknown = InterspeciesScaling.scaledToHuman(
            row("pangolin", vd = 1.5, halfLife = 200.0, clearance = 4.0, confidence = ConfidenceTier.HIGH),
        )
        unknown.halfLifeMin shouldBe 200.0
        unknown.clearanceMlPerMinPerKg shouldBe 4.0
        // And it is not floored either — nothing was derived, so nothing was
        // degraded.
        unknown.confidence shouldBe ConfidenceTier.HIGH
    }

    @Test
    fun `A missing field stays missing`() {
        // The scaling multiplies what is there and invents nothing.
        val rat = InterspeciesScaling.scaledToHuman(row("rat", vd = 2.6))
        rat.halfLifeMin shouldBe null
        rat.clearanceMlPerMinPerKg shouldBe null
        rat.vdLPerKg shouldBe 2.6
    }

    @Test
    fun `Species matching is case-insensitive`() {
        // The column is lowercased on read upstream; this does not depend on that.
        InterspeciesScaling.scaledToHuman(row("RAT", halfLife = 100.0)).halfLifeMin shouldBe
            InterspeciesScaling.scaledToHuman(row("rat", halfLife = 100.0)).halfLifeMin
        InterspeciesScaling.scaledToHuman(row("HUMAN", halfLife = 100.0)).halfLifeMin shouldBe 100.0
    }

    @Test
    fun `Flooring confidence never raises it`() {
        // `flooringConfidence` is the borrow path's tool: a borrowed field
        // inherits at most the pointer's grade, and never a better one.
        val high = row("human", halfLife = 100.0, confidence = ConfidenceTier.HIGH)
        InterspeciesScaling.flooringConfidence(high, ConfidenceTier.LOW).confidence shouldBe ConfidenceTier.LOW
        val unverified = row("human", halfLife = 100.0, confidence = ConfidenceTier.UNVERIFIED)
        InterspeciesScaling.flooringConfidence(unverified, ConfidenceTier.HIGH).confidence shouldBe
            ConfidenceTier.UNVERIFIED
    }

    private fun Double.pow(exponent: Double): Double = Math.pow(this, exponent)
}
