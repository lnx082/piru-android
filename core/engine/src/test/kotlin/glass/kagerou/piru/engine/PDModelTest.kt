package glass.kagerou.piru.engine

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.exp
import org.junit.jupiter.api.Test

/**
 * Ported from `PiruTests/PDModelToleranceTests.swift`.
 *
 * Pure-model gate for the tolerance engine's right-shift core. These exercise
 * [PDModel] in isolation — no store, no database — so the dynamics are proven
 * before any wiring: clustered occupancy drives a layer up while spacing and idle
 * decay it, the deep gate is off below the escalation threshold and on above it,
 * the response gauge is 1 at rest and decreasing in the shift, and the recovery
 * forecast is monotone.
 *
 * **One upstream test is not ported**: `Clustered occupancy builds more shift
 * than spaced` needs `ReceptorClasses.parameters(for: .psychedelic5HT2A)` for its
 * rate constants, and that table lives in the pharmacology layer, which has not
 * been ported yet. The test is a property check over [stepShift] and will come
 * with it.
 */
class PDModelTest {

    private fun layer(s: Double, tau: Double) = PDModel.Layer(s, tau)

    // MARK: - stepShift

    @Test
    fun `Occupancy drives the layer up toward its ceiling`() {
        val s = PDModel.stepShift(
            current = 0.0, shiftMax = 2.0, occupancy = 1.0, drive = 1.0,
            dtMinutes = 1_000_000.0, tauMinutes = 1_440.0,
        )
        (abs(s - 2.0) < 1e-6) shouldBe true
    }

    @Test
    fun `With no occupancy the layer decays toward zero`() {
        val s = PDModel.stepShift(
            current = 1.5, shiftMax = 2.0, occupancy = 0.0, drive = 1.0,
            dtMinutes = 1_000_000.0, tauMinutes = 1_440.0,
        )
        (s < 1e-3) shouldBe true
    }

    @Test
    fun `A disabled layer only decays, never grows`() {
        val s = PDModel.stepShift(
            current = 0.4, shiftMax = 0.0, occupancy = 1.0, drive = 1.0,
            dtMinutes = 1_440.0, tauMinutes = 1_440.0,
        )
        (s < 0.4) shouldBe true
        (s >= 0) shouldBe true

        PDModel.stepShift(
            current = 0.0, shiftMax = 0.0, occupancy = 1.0, drive = 1.0,
            dtMinutes = 1_440.0, tauMinutes = 1_440.0,
        ) shouldBe 0.0
    }

    @Test
    fun `Guards — non-positive dt or tau is a no-op`() {
        PDModel.stepShift(0.7, 2.0, 1.0, 1.0, dtMinutes = 0.0, tauMinutes = 100.0) shouldBe 0.7
        PDModel.stepShift(0.7, 2.0, 1.0, 1.0, dtMinutes = 30.0, tauMinutes = 0.0) shouldBe 0.7
    }

    // MARK: - smoothstepGate

    @Test
    fun `Smoothstep gate is zero below the threshold and one above the band`() {
        PDModel.smoothstepGate(1.0, threshold = 2.0, width = 3.0) shouldBe 0.0
        PDModel.smoothstepGate(2.0, threshold = 2.0, width = 3.0) shouldBe 0.0
        (abs(PDModel.smoothstepGate(5.0, threshold = 2.0, width = 3.0) - 1) < 1e-9) shouldBe true

        val mid = PDModel.smoothstepGate(3.5, threshold = 2.0, width = 3.0)
        (mid > 0 && mid < 1) shouldBe true
        (abs(mid - 0.5) < 1e-9) shouldBe true // smoothstep(0.5) = 0.5
    }

    @Test
    fun `Magnitude gate soft-on near the heavy ceiling, chronicity gate near the duty knee`() {
        // Magnitude knobs: soft-on as escalation approaches 1, near zero well
        // below, full by about 1.5x.
        PDModel.smoothstepGate(0.0, threshold = 0.5, width = 1.0) shouldBe 0.0
        PDModel.smoothstepGate(0.3, threshold = 0.5, width = 1.0) shouldBe 0.0
        (PDModel.smoothstepGate(1.0, threshold = 0.5, width = 1.0) > 0) shouldBe true
        (abs(PDModel.smoothstepGate(1.5, threshold = 0.5, width = 1.0) - 1) < 1e-9) shouldBe true

        // Chronicity knobs: a once-daily therapeutic duty of about 0.15 sits
        // below the knee.
        PDModel.smoothstepGate(0.15, threshold = 0.25, width = 0.35) shouldBe 0.0
        (abs(PDModel.smoothstepGate(0.6, threshold = 0.25, width = 0.35) - 1) < 1e-9) shouldBe true
    }

    // MARK: - responseFraction

    @Test
    fun `Response fraction is 1 at S=1 and decreases as S grows`() {
        val rested = PDModel.responseFraction(1.0, 0.5)
        (abs(rested - 1) < 1e-12) shouldBe true

        val shifted = PDModel.responseFraction(3.0, 0.5)
        val moreShifted = PDModel.responseFraction(6.0, 0.5)
        (shifted < 1) shouldBe true
        (moreShifted < shifted) shouldBe true
        (moreShifted > 0) shouldBe true
    }

    @Test
    fun `At low occupancy the gauge approaches one over S`() {
        val frac = PDModel.responseFraction(4.0, 0.001)
        (abs(frac - 0.25) < 0.01) shouldBe true
    }

    @Test
    fun `At high occupancy the gauge is buffered`() {
        // A near-saturated receptor loses less response to the same shift than a
        // barely-engaged one.
        val lowOcc = PDModel.responseFraction(4.0, 0.05)
        val highOcc = PDModel.responseFraction(4.0, 0.95)
        (highOcc > lowOcc) shouldBe true
    }

    @Test
    fun `Mechanism-aware cap — an agonist shows residual response, a releaser shows real tolerance`() {
        // The over-read the mechanism-aware cap fixes. A heavy agonist user at an
        // elevated usual dose with a big shift still feels a residual half —
        // uncapped, which is the physically exact usual-dose ratio.
        val agonist = PDModel.responseFraction(10.0, 0.9, occupancyCap = null)
        (agonist > 0.4 && agonist < 0.7) shouldBe true

        // Capping the same agonist would pretend its escalated dose is a
        // half-saturated one, throwing away the escalation and over-stating
        // tolerance. Uncapped shows strictly more residual.
        val agonistIfCapped = PDModel.responseFraction(10.0, 0.9, occupancyCap = 0.5)
        (agonist > agonistIfCapped) shouldBe true

        // A releaser saturates its transporter at recreational doses; without the
        // cap the same shift washes out to "no tolerance". Capped at the ED50, a
        // meaningful shift surfaces.
        val releaser = PDModel.responseFraction(10.0, 0.96, occupancyCap = 0.5)
        (releaser < 0.25) shouldBe true
    }

    // MARK: - shiftDecayMinutes

    @Test
    fun `Decay forecast lands at the target shift when stepped forward`() {
        val layers = listOf(layer(1.2, 14_400.0), layer(0.3, 720.0))
        val target = 2.0
        val mins = PDModel.shiftDecayMinutes(layers, target)!!
        val landed = exp(layers.sumOf { it.s * exp(-mins / it.tau) })
        (abs(landed - target) < 1e-3) shouldBe true
        (mins > 0) shouldBe true
    }

    @Test
    fun `Decay forecast — already-recovered is zero, sub-1 target is unreachable`() {
        // S = exp(0.2) is already below the target of 1.5.
        PDModel.shiftDecayMinutes(listOf(layer(0.2, 1_440.0)), 1.5) shouldBe 0.0
        // A full reset below S = 1 is asymptotic.
        PDModel.shiftDecayMinutes(listOf(layer(1.0, 1_440.0)), 0.9) shouldBe null
    }

    @Test
    fun `Decay forecast is monotone — a bigger shift takes longer to relax`() {
        val shallow = PDModel.shiftDecayMinutes(listOf(layer(0.7, 1_440.0)), 1.2)!!
        val deep = PDModel.shiftDecayMinutes(listOf(layer(1.6, 1_440.0)), 1.2)!!
        (deep > shallow) shouldBe true
    }

    // MARK: - competitiveOccupancy

    @Test
    fun `Competitive occupancy is Gaddum summation, exact for a single ligand`() {
        PDModel.competitiveOccupancy(emptyList()) shouldBe 0.0
        // A single ligand reduces exactly to its own occupancy, so
        // single-substance behaviour is unchanged.
        (abs(PDModel.competitiveOccupancy(listOf(0.5)) - 0.5) < 1e-9) shouldBe true
        (abs(PDModel.competitiveOccupancy(listOf(0.9)) - 0.9) < 1e-9) shouldBe true
        // Two half-saturated ligands competing at one site give 2/3, not the
        // union's 0.75.
        (abs(PDModel.competitiveOccupancy(listOf(0.5, 0.5)) - 2.0 / 3.0) < 1e-9) shouldBe true
        // A fully saturating ligand pins the site at 1, with no divide by zero.
        PDModel.competitiveOccupancy(listOf(1.0, 0.5)) shouldBe 1.0
        PDModel.competitiveOccupancy(listOf(2.0)) shouldBe 1.0
    }
}
