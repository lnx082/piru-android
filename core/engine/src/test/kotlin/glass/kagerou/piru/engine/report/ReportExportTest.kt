package glass.kagerou.piru.engine.report

import glass.kagerou.piru.engine.InteractionSeverity
import glass.kagerou.piru.engine.PKModel
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.jupiter.api.Test

/**
 * The elimination arithmetic and the two Markdown renderers. The math tests pin
 * the *rules* (superposition, the per-total fraction, the rise-then-decline
 * zero-order shape) on doses whose every number the test chose, so the real
 * catalog cases in `:core:data` only have to prove the numbers are read, not
 * that the arithmetic is right. The Markdown tests pin the portable sections and
 * the stable English tokens rather than clock strings, which belong to the JDK's
 * CLDR data and are not the port's job to reproduce byte-for-byte.
 */
class ReportExportTest {

    private val start = Instant.ofEpochSecond(0)

    // MARK: - First-order elimination

    @Test
    fun `a single first-order dose starts at full body and decays monotonically`() {
        val ke = PKModel.keFromHalfLifeMinutes(60.0)
        val ka = PKModel.defaultKa(ke)
        val group = EliminationModel.firstOrderGroup(
            name = "MDMA",
            halfLifeMinutes = 60.0,
            doses = listOf(FirstOrderDose(offsetMinutes = 0.0, amount = 100.0, ke = ke, ka = ka)),
            unit = "mg",
            groupStart = start,
            doseCount = 1,
            now = start,
        )

        group.name shouldBe "MDMA"
        group.doseCount shouldBe 1
        group.unit shouldBe "mg"
        group.curve.size shouldBe EliminationModel.ELIM_SAMPLES + 1
        // At the moment of the dose the whole amount is still in the body (in the
        // gut), so the combined curve opens at the full dose.
        group.curve.first() shouldBe (100.0 plusOrMinus 1e-9)
        val model = group.model as Elimination.FirstOrder
        model.halfLifeMinutes shouldBe 60.0
        model.amountRemaining shouldBe (100.0 plusOrMinus 1e-9)
        model.fractionRemaining shouldBe (1.0 plusOrMinus 1e-9)
        // 50% < 10% < 3% of the dose are crossed strictly in that order.
        (model.t50.isAfter(group.groupStart)) shouldBe true
        (model.t90.isAfter(model.t50)) shouldBe true
        (model.cleared.isAfter(model.t90)) shouldBe true
        isMonotonicNonIncreasing(group.curve) shouldBe true
    }

    @Test
    fun `stacked first-order doses superpose and the fraction is per-total`() {
        val ke = PKModel.keFromHalfLifeMinutes(180.0)
        val ka = PKModel.defaultKa(ke)
        val group = EliminationModel.firstOrderGroup(
            name = "Alcohol",
            halfLifeMinutes = 180.0,
            doses = listOf(
                FirstOrderDose(offsetMinutes = 0.0, amount = 100.0, ke = ke, ka = ka),
                FirstOrderDose(offsetMinutes = 60.0, amount = 100.0, ke = ke, ka = ka),
            ),
            unit = "mg",
            groupStart = start,
            doseCount = 2,
            now = start,
        )

        // Only the first dose is on board at t = 0, so the curve opens at 100,
        // but the fraction is measured against the 200 dosed in total.
        group.curve.first() shouldBe (100.0 plusOrMinus 1e-9)
        val model = group.model as Elimination.FirstOrder
        model.amountRemaining shouldBe (100.0 plusOrMinus 1e-9)
        model.fractionRemaining shouldBe (0.5 plusOrMinus 1e-9)
    }

    // MARK: - Zero-order elimination

    @Test
    fun `zero-order body content rises to a peak then declines`() {
        val kinetics = PKModel.ZeroOrderKinetics(
            bioavailability = 1.0,
            vmaxMgPerMin = 100.0,
            ka = 0.05,
        )
        val group = EliminationModel.zeroOrderGroup(
            name = "Alcohol",
            kinetics = kinetics,
            doses = listOf(ZeroOrderDose(offsetMinutes = 0.0, doseMg = 50_000.0)),
            groupStart = start,
            doseCount = 1,
            now = start,
        )

        group.unit shouldBe "g"
        group.curve.size shouldBe EliminationModel.ELIM_SAMPLES + 1
        // Nothing has been absorbed at the instant of the dose.
        group.curve.first() shouldBe (0.0 plusOrMinus 1e-9)
        val peak = group.curve.maxOrNull()!!
        (peak > 0.0) shouldBe true
        (peak <= 50.0) shouldBe true // cannot exceed the 50 g dosed
        // The shape rises to an interior peak, then declines — not first-order's
        // monotone fall, which is the whole point of the alcohol ceiling model.
        (group.curve.last() < peak) shouldBe true
        val model = group.model as Elimination.ZeroOrder
        model.gramsRemaining shouldBe (0.0 plusOrMinus 1e-9)
        model.fractionRemaining shouldBe (0.0 plusOrMinus 1e-9)
        (model.t90.isAfter(model.t50)) shouldBe true
        (model.modelZero.isAfter(model.t90)) shouldBe true
    }

    @Test
    fun `an unknown substance carries no curve and no model times`() {
        val group = EliminationModel.unknown(
            name = "Unmeasured",
            unit = "mg",
            doseCount = 1,
            groupStart = start,
        )
        group.model shouldBe Elimination.Unknown
        group.curve shouldBe emptyList()
        group.horizonMinutes shouldBe 1.0
    }

    // MARK: - TripReport

    private val note = TripReport.Note(
        timestamp = start.plusSeconds(3600),
        checkIn = true,
        text = "feeling it",
        shulgin = 2,
        mood = 2,
        energy = 1,
        social = null,
        worked = null,
        heartRate = 84,
        descriptors = listOf(TripReport.Descriptor("euphoria", "Euphoria", "Mood")),
    )

    @Test
    fun `TripReport markdown assembles the portable sections`() {
        val report = TripReport(
            title = null,
            sessionStart = start,
            doses = listOf(
                TripReport.Dose(timestamp = start, name = "MDMA", amount = 120.0, unit = "mg", route = "oral"),
            ),
            notes = listOf(note),
            summary = "Good session.",
        )
        val md = report.markdown(Locale.US, ZoneId.of("UTC"))

        md shouldStartWith "# MDMA"
        md shouldContain "January 1, 1970"
        md shouldContain "## Doses"
        md shouldContain "| T+ | Time | Substance | Dose | Route |"
        md shouldContain "MDMA | 120 mg | oral"
        md shouldContain "## Timeline"
        md shouldContain "**Check-in**"
        md shouldContain "## Descriptors by domain"
        md shouldContain "| Domain | Descriptor | First noted |"
        md shouldContain "Mood | Euphoria |"
        md shouldContain "## Summary"
        md shouldContain "Good session."
        md shouldContain "_Not medical advice. A record of one session, written with Piru._"

        // Shulgin and mood/energy were recorded; worked and social were not, so
        // their columns must be absent rather than printed empty.
        md shouldContain "Shulgin"
        md shouldContain "Mood / Energy"
        md shouldNotContain "Worked"
        md shouldNotContain "| Social |"
    }

    @Test
    fun `TripReport markdown prints the title and the substance line together`() {
        val report = TripReport(
            title = "My roll",
            sessionStart = start,
            doses = listOf(
                TripReport.Dose(timestamp = start, name = "MDMA", amount = 120.0, unit = "mg", route = "oral"),
            ),
            notes = emptyList(),
            summary = null,
        )
        val md = report.markdown(Locale.US, ZoneId.of("UTC"))
        md shouldStartWith "# My roll"
        md shouldContain "**MDMA**"
        // A title is present, so the summary section is omitted entirely.
        md shouldNotContain "## Summary"
    }

    @Test
    fun `descriptorsByDomain groups by first appearance and orders by time then name`() {
        val early = TripReport.Note(
            timestamp = start.plusSeconds(600),
            checkIn = false,
            text = "",
            shulgin = null, mood = null, energy = null, social = null, worked = null, heartRate = null,
            descriptors = listOf(TripReport.Descriptor("euphoria", "Euphoria", "Mood")),
        )
        val later = TripReport.Note(
            timestamp = start.plusSeconds(1200),
            checkIn = false,
            text = "",
            shulgin = null, mood = null, energy = null, social = null, worked = null, heartRate = null,
            descriptors = listOf(
                TripReport.Descriptor("jitter", "Jitter", "Body"),
                TripReport.Descriptor("talkative", "Talkative", "Mood"),
            ),
        )
        val report = TripReport(
            title = null,
            sessionStart = start,
            doses = emptyList(),
            notes = listOf(early, later),
            summary = null,
        )

        val grouped = report.descriptorsByDomain
        // Mood appears first, so it leads; Body follows.
        grouped.map { it.first } shouldBe listOf("Mood", "Body")
        grouped[0].second.map { it.descriptor.name } shouldBe listOf("Euphoria", "Talkative")
        grouped[1].second.map { it.descriptor.name } shouldBe listOf("Jitter")
        // First-noted timestamps are the note that introduced the descriptor.
        grouped[0].second.first { it.descriptor.name == "Euphoria" }.at shouldBe early.timestamp
        grouped[0].second.first { it.descriptor.name == "Talkative" }.at shouldBe later.timestamp
    }

    // MARK: - SessionStateExport

    @Test
    fun `SessionStateExport markdown assembles the live snapshot`() {
        val now = start.plusSeconds(3600)
        val ke = PKModel.keFromHalfLifeMinutes(180.0)
        val ka = PKModel.defaultKa(ke)
        val group = EliminationModel.firstOrderGroup(
            name = "MDMA",
            halfLifeMinutes = 180.0,
            doses = listOf(FirstOrderDose(offsetMinutes = 0.0, amount = 120.0, ke = ke, ka = ka)),
            unit = "mg",
            groupStart = start,
            doseCount = 1,
            now = now,
        )
        val export = SessionStateExport(
            generatedAt = now,
            sessionStart = start,
            substances = listOf(
                SessionStateExport.SubstanceState(
                    name = "MDMA",
                    amount = 120.0,
                    unit = "mg",
                    route = "oral",
                    doseTimestamp = start,
                    phase = SessionPhase.PEAK,
                    intensity = 0.6,
                    progress = 0.5,
                    totalMinutes = 180.0,
                    phaseBoundaries = listOf(0.08, 0.16, 0.66, 1.0),
                    next = SessionStateExport.NextPhase(SessionPhase.OFFSET, now.plusSeconds(1800)),
                    baselineAt = now.plusSeconds(7200),
                    effectCurve = listOf(0.0, 0.5, 1.0),
                ),
            ),
            eliminations = listOf(group),
            interactions = listOf(
                SessionStateExport.InteractionLine(InteractionSeverity.CAUTION, "MDMA", "Alcohol", "additive"),
            ),
            isLive = true,
            notes = listOf(
                SessionStateExport.NoteLine(
                    timestamp = start.plusSeconds(1200),
                    checkIn = true,
                    text = "peaking",
                    structure = "++ · ♥ 84",
                    descriptors = listOf("Euphoria"),
                ),
            ),
        )
        val md = export.markdown(Locale.US, ZoneId.of("UTC"))

        md shouldStartWith "# Piru — Session Snapshot"
        md shouldContain "**Generated:**"
        md shouldContain "## Current state (subjective)"
        md shouldContain "### MDMA — 120 mg oral"
        md shouldContain "**Peak** — offset in ~30m"
        md shouldContain "**60%** of this dose's peak"
        md shouldContain "## Elimination"
        md shouldContain "| Substance | Half-life | In body now | Eliminated | 50% gone | 90% gone | Cleared |"
        md shouldContain "(t½ 3h)"
        md shouldContain "## Notes"
        md shouldContain "check-in"
        md shouldContain "> ⚠️ **Caution:** MDMA + Alcohol — additive"
        md shouldContain "*Model estimates (one-compartment oral PK; alcohol zero-order). Individual clearance varies. Intensity is peak-relative. Not medical advice.*"
    }

    // MARK: - Helpers

    private fun isMonotonicNonIncreasing(curve: List<Double>): Boolean =
        curve.zipWithNext().all { (a, b) -> b <= a + 1e-9 }
}
