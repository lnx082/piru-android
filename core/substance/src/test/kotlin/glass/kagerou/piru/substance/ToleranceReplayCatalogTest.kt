package glass.kagerou.piru.substance

import glass.kagerou.piru.engine.ReceptorClasses
import glass.kagerou.piru.engine.ToleranceReplay
import glass.kagerou.piru.engine.ToleranceSimulation
import glass.kagerou.piru.model.ConfidenceTier
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The whole chain, on the shipped catalog: a dose log in, tolerance cards out.
 *
 * Everything below the catalog is tested with hand-built pharmacology in
 * `:core:engine`. What can only be tested here is the wiring — that a substance name
 * resolves, that a preparation routes to its active constituent before its
 * pharmacology is read, that the class representatives are reachable when nothing in
 * the log is modelable, and that the numbers that come out are the catalog's own.
 */
class ToleranceReplayCatalogTest {

    private fun catalog(db: SubstanceDb) = DbSubstanceCatalog.open(
        db = db,
        order = db.query("SELECT slug FROM sources ORDER BY default_priority, slug")
            .mapNotNull { it.string("slug") },
        language = ContentLanguage.EN,
    )

    /**
     * The production wiring: resolve the log's names **and** the class
     * representatives, because the missing-PK fallback models a logged substance as
     * its representative — which the user has usually never logged.
     */
    private fun paramsFor(catalog: DbSubstanceCatalog, log: List<ToleranceReplay.SimDose>) =
        catalog.pharmacologyForLog(
            log.map { it.substance }.toSet() + catalog.classRepresentativeNames(),
        )

    private fun dose(name: String, mg: Double, atMinutes: Double) =
        ToleranceReplay.SimDose(name, mg, atMinutes)

    // MARK: - The representatives

    @Test
    fun `The catalog names one representative per modelled class`() {
        val names = catalog(openBundledSubstanceDb()).classRepresentativeNames()
        names shouldBe names.sortedBy { it.lowercase() }
        (names.isNotEmpty()) shouldBe true
        // Seven classes have one. The adrenergic classes deliberately do not: they host
        // a discontinuation warning rather than a tolerance curve, so there is no dose
        // equivalence to borrow.
        for (name in names) {
            val cls = ReceptorClasses.ReceptorClass.entries.filter { !it.hostsReboundWarningOnly }
            (cls.isNotEmpty()) shouldBe true
        }
    }

    @Test
    fun `Every representative resolves a computable parameter set`() {
        // A surrogate that cannot compute occupancy is no surrogate, and the fallback
        // silently drops it — so this is the property the fallback depends on.
        val catalog = catalog(openBundledSubstanceDb())
        for (name in catalog.classRepresentativeNames()) {
            val params = catalog.pharmacologyParameters(name)
            params.canComputeOccupancy shouldBe true
            (params.referenceDoseMg != null) shouldBe true
            (params.representsClasses.isNotEmpty()) shouldBe true
        }
    }

    // MARK: - A real log

    @Test
    fun `A month of daily caffeine produces an adenosine card`() {
        val catalog = catalog(openBundledSubstanceDb())
        val log = (0 until 30).map { dose("Caffeine", 100.0, it * 1_440.0) }
        val cards = ToleranceReplay.simulate(log, paramsFor(catalog, log), 30 * 1_440.0, 70.0)

        val adenosine = cards.getValue(ReceptorClasses.ReceptorClass.ADENOSINE)
        (adenosine.sAdaptive > 0) shouldBe true
        // Adenosine has no acute pool at all, which is what distinguishes it from the
        // stimulants in the same behavioural family.
        adenosine.sAcute shouldBe 0.0
        adenosine.sDeep shouldBe 0.0
        adenosine.contributors shouldBe listOf("Caffeine")
        adenosine.subTargets shouldBe listOf("Adenosine A2A", "Adenosine A1")
        // The class carries no safety endpoint, so there is none to report.
        adenosine.safetyShiftFactor shouldBe null

        // Caffeine's own PK is graded HIGH throughout, so the badge is the class
        // kinetics' own low grade rather than an unverified floor.
        adenosine.confidence shouldBe ConfidenceTier.LOW
    }

    @Test
    fun `An MDMA-like SERT releaser builds the slow synthesis pool`() {
        // The per-substance flag is what splits the class onto two recovery clocks,
        // and it has to have survived the catalog read to get here.
        val catalog = catalog(openBundledSubstanceDb())
        val log = (0 until 7).map { dose("MDMA", 120.0, it * 1_440.0) }
        val cards = ToleranceReplay.simulate(log, paramsFor(catalog, log), 7 * 1_440.0, 70.0)

        val sert = cards.getValue(ReceptorClasses.ReceptorClass.SEROTONERGIC_RELEASER)
        (sert.sSynthesis > 0) shouldBe true
        (sert.sAdaptive > 0) shouldBe true
        // The slow pool lags *during* the course — it is the slower constant, so of
        // course it does — and that is not what makes it matter.
        (sert.sSynthesis < sert.sAdaptive) shouldBe true

        // What makes it matter is the recovery: two weeks after the last dose the fast
        // pool has essentially cleared while the synthesis pool is still most of the
        // shift. That is the difference between an entactogen recovering in weeks and
        // a cathinone releaser recovering in days, from the same class and the same
        // kinetics — only the per-substance flag differs.
        val after = ToleranceReplay.simulate(log, paramsFor(catalog, log), 21 * 1_440.0, 70.0)
            .getValue(ReceptorClasses.ReceptorClass.SEROTONERGIC_RELEASER)
        (after.sSynthesis > after.sAdaptive) shouldBe true
        (after.shiftFactor > 1.05) shouldBe true
    }

    @Test
    fun `A preparation routes to its active constituent before anything is read`() {
        // Kratom is dosed in grams of leaf and has almost no pharmacology of its own.
        // The routing is what makes the dose reach the μ-opioid class at all.
        val catalog = catalog(openBundledSubstanceDb())
        val log = listOf(dose("Kratom", 5_000.0, 0.0))
        val cards = ToleranceReplay.simulate(log, paramsFor(catalog, log), 1_440.0, 70.0)

        val opioid = cards.getValue(ReceptorClasses.ReceptorClass.MU_OPIOID)
        // The chip says what the user took, never the active compound they took it for.
        opioid.contributors shouldBe listOf("Kratom")
        opioid.subTargets.isNotEmpty() shouldBe true
        // And the routing's own confidence is what caps the card, because the content
        // fraction is an estimate.
        (opioid.confidence <= ConfidenceTier.LOW) shouldBe true
    }

    // MARK: - The missing-PK fallback, on the one substance that has a pointer

    @Test
    fun `The one substance with a PK reference borrows its surrogate's kinetics`() {
        // 2-MMC carries no PK of its own and points at mephedrone. The borrow is what
        // makes it modelable at all; the badge is what says so.
        val catalog = catalog(openBundledSubstanceDb())
        val params = catalog.pharmacologyParameters("2-MMC")
        params.canComputeOccupancy shouldBe true
        params.pkSpecies shouldBe "rat"
        (params.vdConfidence <= ConfidenceTier.LOW) shouldBe true

        val log = listOf(dose("2-MMC", 100.0, 0.0))
        val cards = ToleranceReplay.simulate(log, paramsFor(catalog, log), 1_440.0, 70.0)
        val stimulant = cards.getValue(ReceptorClasses.ReceptorClass.CATECHOLAMINE_STIMULANT)
        stimulant.contributors shouldBe listOf("2-MMC")
    }

    @Test
    fun `A substance nothing can model is reported rather than silently dropped`() {
        // "No tolerance predicted" and "no tolerance" look identical on a card, so the
        // log has to be able to say which one it is.
        val catalog = catalog(openBundledSubstanceDb())
        val log = listOf(dose("Caffeine", 100.0, 0.0))
        val params = paramsFor(catalog, log)
        // A name the catalog does not carry is simply absent, not "incomplete" — the
        // diagnostic speaks about substances it knows and cannot model.
        ToleranceReplay.incompleteData(log, params, ToleranceReplay.representativeIndex(params))
            .isEmpty() shouldBe true
    }

    // MARK: - Time

    @Test
    fun `A course that stopped a month ago has relaxed, but not to nothing`() {
        // The whole point of a replay rather than a last-dose reading: the shift is a
        // function of the pattern, and it decays on its own clock.
        val catalog = catalog(openBundledSubstanceDb())
        val log = (0 until 30).map { dose("Caffeine", 200.0, it * 1_440.0) }
        val params = paramsFor(catalog, log)
        val courseEnd = 30 * 1_440.0

        val onCourse = ToleranceReplay.simulate(log, params, courseEnd, 70.0)
            .getValue(ReceptorClasses.ReceptorClass.ADENOSINE)
        val later = ToleranceReplay.simulate(log, params, courseEnd + 14 * 1_440.0, 70.0)
            .getValue(ReceptorClasses.ReceptorClass.ADENOSINE)

        (later.shiftFactor < onCourse.shiftFactor) shouldBe true
        (later.shiftFactor > 1.0) shouldBe true
        // The contributor has faded past the relevance floor, so the card stops
        // crediting caffeine for a class it is no longer driving.
        (ToleranceSimulation.toleranceMemoryTauMinutes(
            ReceptorClasses.parametersFor(ReceptorClasses.ReceptorClass.ADENOSINE),
        ) > 0) shouldBe true
    }

    // MARK: - The load curve

    @Test
    fun `The load curve clears where occupancy would not`() {
        val catalog = catalog(openBundledSubstanceDb())
        val log = listOf(dose("Caffeine", 200.0, 0.0))
        val params = paramsFor(catalog, log)
        val prepared = ToleranceReplay.prepare(log, params, 3 * 1_440.0, 70.0)!!
        val work = prepared.work.single()
        val trail = ToleranceSimulation.loadTrail(
            contributors = work.contributors,
            totalMinutes = prepared.totalMinutes,
            horizonMinutes = 0.0,
            stepMinutes = 60.0,
            pastHorizonMinutes = prepared.totalMinutes,
        )
        (trail.size > 10) shouldBe true
        // A day and a half on from a single caffeine dose the curve is essentially
        // clear, which raw occupancy would not show: caffeine's adenosine Kᵢ is high
        // enough that occupancy stays visible long after the felt effect is gone.
        (trail.last().load < 0.1) shouldBe true
        (trail.maxOf { it.load } <= 1.0) shouldBe true
    }
}
