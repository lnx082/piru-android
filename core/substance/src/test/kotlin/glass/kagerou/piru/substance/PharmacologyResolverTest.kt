package glass.kagerou.piru.substance

import glass.kagerou.piru.engine.InterspeciesScaling
import glass.kagerou.piru.engine.PharmacologyAssembly
import glass.kagerou.piru.engine.PharmacologyParameters
import glass.kagerou.piru.engine.ReceptorClasses
import glass.kagerou.piru.model.BindingAction
import glass.kagerou.piru.model.ConfidenceTier
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.math.abs
import org.junit.jupiter.api.Test

/**
 * The Foundation-A resolver against the shipped catalog: do the flagship Vd, Kᵢ,
 * EC₅₀ and IC₅₀ seeded into the database resolve into usable occupancy inputs,
 * and does occupancy come out dose-dependent on *real* data?
 *
 * Ported from `PiruTests/PharmacologyParametersTests.swift`. Every expectation
 * was read out of the real database with `sqlite3` before it was written down.
 */
class PharmacologyResolverTest {

    private fun catalog(db: SubstanceDb) = DbSubstanceCatalog.open(
        db = db,
        order = db.query("SELECT slug FROM sources ORDER BY default_priority, slug")
            .mapNotNull { it.string("slug") },
        language = ContentLanguage.EN,
    )

    private fun params(name: String) = catalog(openBundledSubstanceDb()).pharmacologyParameters(name)

    // MARK: - Empty and complete

    @Test
    fun `An unknown substance resolves to empty parameters`() {
        // Not a zero-filled record: a null molar mass and no targets is what the
        // engine reads as "uncomputable", which is a different claim from
        // "occupancy is zero".
        val p = params("zzzNotARealCompound")
        p.vdLPerKg.shouldBeNull()
        p.targets.isEmpty() shouldBe true
        p.canComputeOccupancy shouldBe false
        p.molarMassGramsPerMole.shouldBeNull()
    }

    @Test
    fun `Amphetamine resolves graded Vd and a releaser primary target`() {
        val p = params("Amphetamine")
        p.vdLPerKg shouldBe 4.0
        // The flagship seed grades the Vd HIGH, and the coherent-row pick has to
        // find that row among five oral rows that disagree.
        p.vdConfidence shouldBe ConfidenceTier.HIGH
        p.molarMassGramsPerMole shouldBe 135.21
        p.canComputeOccupancy shouldBe true
        // F and half-life come from the *same* row as the Vd, never paired across
        // studies — which is why F inherits that row's own grade rather than a
        // blend of the studies that happen to agree on the number.
        p.bioavailabilityFraction shouldBe 0.75
        p.bioavailabilityConfidence shouldBe ConfidenceTier.HIGH
        p.halfLifeMinutes shouldBe 600.0
        // Tmax is the one field allowed to come from a different row: it is a rate
        // descriptor independent of the F/Vd coupling, and the coherent row has
        // none — so it falls to the best-graded row that carries one.
        p.tmaxMinutes shouldBe 180.0
        p.tmaxConfidence shouldBe ConfidenceTier.MEDIUM

        val primary = p.primaryTarget.shouldNotBeNull()
        primary.target shouldBe "NET"
        primary.action shouldBe BindingAction.RELEASING_AGENT
        // Functional release EC₅₀, not a binding Kᵢ — amphetamine is a releaser.
        primary.kind shouldBe PharmacologyParameters.HalfMaxKind.EC50
        primary.halfMaxNanomolar shouldBe 7.2
    }

    @Test
    fun `Targets are ordered most-potent first`() {
        val halfMaxes = params("Amphetamine").targets.map { it.halfMaxNanomolar }
        halfMaxes shouldBe halfMaxes.sorted()
    }

    @Test
    fun `Methylphenidate resolves as a reuptake inhibitor, not a releaser`() {
        // The flagship distinction: methylphenidate must not be lumped with
        // amphetamine — it is a transporter *blocker*, a different mechanism and a
        // different tolerance.
        val primary = params("Methylphenidate").primaryTarget.shouldNotBeNull()
        primary.action shouldBe BindingAction.REUPTAKE_INHIBITOR
        primary.action shouldNotBe BindingAction.RELEASING_AGENT
        primary.kind shouldBe PharmacologyParameters.HalfMaxKind.KI
        (primary.halfMaxNanomolar < 500) shouldBe true
    }

    // MARK: - The therapeutic-threshold override

    @Test
    fun `Gabapentinoids take their half-max from the therapeutic threshold, not the binding Ki`() {
        // Pregabalin binds α2δ at 32 nM but acts at 2–5 µg/mL. The TDM floor of
        // 2 µg/mL is 12.56 µM at 159.23 g/mol, and gabapentin's is the same 2 µg/mL
        // at 171.24 g/mol.
        for ((name, expected) in listOf("Pregabalin" to 12_560.0, "Gabapentin" to 11_680.0)) {
            val p = params(name)
            val primary = p.primaryTarget.shouldNotBeNull()
            primary.kind shouldBe PharmacologyParameters.HalfMaxKind.THERAPEUTIC_THRESHOLD
            (abs(primary.halfMaxNanomolar - expected) / expected < 0.03) shouldBe true
            primary.citationKey shouldBe "doi:10.1055/s-0043-116492"
            // A TDM range is a population consensus rather than a measurement of
            // this drug's own curve, so it is badged medium and marked human.
            primary.confidence shouldBe ConfidenceTier.MEDIUM
            primary.species shouldBe "human"
            p.targets.all { it.kind == PharmacologyParameters.HalfMaxKind.THERAPEUTIC_THRESHOLD } shouldBe true
            p.targets.all { it.targetBase.startsWith("alpha-2-delta") } shouldBe true
        }
    }

    @Test
    fun `A flagged class whose substance has no threshold row keeps its binding constant`() {
        // Phenibut is in the same class but ships no therapeutic-range row, so the
        // floor has nothing to stand in with. The floor substitutes for a constant;
        // it does not mint targets.
        val primary = params("Phenibut").primaryTarget.shouldNotBeNull()
        primary.kind shouldBe PharmacologyParameters.HalfMaxKind.KI
        primary.halfMaxNanomolar shouldBe 23_000.0
    }

    // MARK: - Occupancy on real data

    @Test
    fun `Occupancy is dose-dependent on real flagship caffeine data`() {
        // The end-to-end gate: through the curated database, the resolver, the
        // absolute molar pathway and the Hill step. A normalized model would make
        // these two numbers identical.
        val p = params("Caffeine")
        val low = p.peakPrimaryOccupancy(doseMg = 50.0, weightKg = 70.0).shouldNotBeNull()
        val high = p.peakPrimaryOccupancy(doseMg = 200.0, weightKg = 70.0).shouldNotBeNull()
        (low > 0) shouldBe true
        (high > low) shouldBe true
        (low < 0.45) shouldBe true
        (high > 0.5) shouldBe true
    }

    @Test
    fun `The same dose yields higher occupancy in a lighter person`() {
        // Body weight is the denominator turning a dose into an exposure — the
        // keystone input of the whole pathway.
        val p = params("Caffeine")
        val light = p.peakPrimaryOccupancy(doseMg = 100.0, weightKg = 50.0).shouldNotBeNull()
        val heavy = p.peakPrimaryOccupancy(doseMg = 100.0, weightKg = 100.0).shouldNotBeNull()
        (light > heavy) shouldBe true
    }

    @Test
    fun `Unmeasured bioavailability defaults to 1, flagged unverified`() {
        // MDMA's absolute oral F is underivable without an IV arm, and its stored
        // Vd is an apparent V/F — so F = 1 is the *consistent* reading and a
        // separately invented F would double-count it.
        val p = params("MDMA")
        p.bioavailabilityFraction shouldBe 1.0
        p.bioavailabilityConfidence shouldBe ConfidenceTier.UNVERIFIED
        // Which is what keeps the canonical serotonergic tolerance computable
        // instead of silently dropped.
        p.canComputeOccupancy shouldBe true
    }

    @Test
    fun `A dose by alias resolves the same pharmacology as its canonical name`() {
        // Regression the upstream suite carries: the per-field accessors used to
        // resolve via the canonical-name index alone, so a dose logged under a
        // non-canonical name came back empty and the engine silently dropped it.
        val byCanonical = params("LSD")
        val byAlias = params("Lysergic Acid Diethylamide")
        byCanonical.canComputeOccupancy shouldBe true
        byAlias.canComputeOccupancy shouldBe true
        byAlias.vdLPerKg shouldBe byCanonical.vdLPerKg
        byAlias.molarMassGramsPerMole shouldBe byCanonical.molarMassGramsPerMole
        byAlias.primaryTarget?.id shouldBe byCanonical.primaryTarget?.id
        byAlias.targets.any { it.target.contains("5-HT2A") } shouldBe true
        byAlias.targets.isEmpty() shouldBe false
    }

    @Test
    fun `Diazepam resolves a molar mass, a free fraction and computable occupancy`() {
        // The pipeline backfills `molecular_weight` from a present `formula`;
        // diazepam shipped with the formula and a null mass, which made its GABA
        // tolerance uncomputable.
        val p = params("Diazepam")
        val mass = p.molarMassGramsPerMole.shouldNotBeNull()
        (mass > 280 && mass < 290) shouldBe true // C16H13ClN2O ≈ 284.74
        p.canComputeOccupancy shouldBe true
        // 98.5% protein-bound in the shipped row, so occupancy is computed against
        // free drug — a fiftieth of the total.
        (p.fractionUnbound < 0.02) shouldBe true
        (p.fractionUnbound > 0.01) shouldBe true
    }

    // MARK: - Preparation routing

    @Test
    fun `Kratom routes to mitragynine at the content fraction`() {
        val p = params("Kratom")
        p.doseScale shouldBe 0.015
        p.doseScaleConfidence shouldBe ConfidenceTier.LOW
        p.canComputeOccupancy shouldBe true
        val primary = p.primaryTarget.shouldNotBeNull()
        ReceptorClasses.ReceptorClass.classify(primary.target, primary.action) shouldBe
            ReceptorClasses.ReceptorClass.MU_OPIOID

        // A logged plant dose occupies the target exactly as the equivalent active
        // mass would — the routing's whole purpose.
        val kratomPeak = p.peakPrimaryOccupancy(doseMg = 5_000.0, weightKg = 75.0).shouldNotBeNull()
        val mitragynine = params("Mitragynine")
        val mitragyninePeak = mitragynine.peakPrimaryOccupancy(doseMg = 5_000.0 * 0.015, weightKg = 75.0)
            .shouldNotBeNull()
        abs(kratomPeak - mitragyninePeak) shouldBe 0.0
    }

    @Test
    fun `Cannabis routes to THC at full scale`() {
        // Curated cannabis doses are already mg Δ9-THC, so the logged mass *is*
        // active mass.
        val p = params("Cannabis")
        p.doseScale shouldBe 1.0
        p.doseScaleConfidence shouldBe ConfidenceTier.MEDIUM
        p.canComputeOccupancy shouldBe true
        val primary = p.primaryTarget.shouldNotBeNull()
        ReceptorClasses.ReceptorClass.classify(primary.target, primary.action) shouldBe
            ReceptorClasses.ReceptorClass.CANNABINOID_CB1
    }

    @Test
    fun `A preparation keeps its own escalation reference, not the active compound's`() {
        // The reference comes from the **logged** substance's ladder — Kratom's
        // dose and Kratom's ladder are both in preparation milligrams — while every
        // molecular fact routes to mitragynine.
        //
        val kratom = params("Kratom")
        val mitragynine = params("Mitragynine")
        // Kratom's best ladder is psychonautwiki's 5/8/8 g of leaf, so the
        // escalation denominator is 8 g of preparation — the unit the user logs in.
        kratom.referenceDoseMg shouldBe 8.0

        // Mitragynine's is 6 mg, not the 80 mg its *curated* ladder carries — because
        // that row is authored as a therapeutic regime and `dose_context` sinks it
        // below every recreational ladder before source rank is consulted. The
        // distinction is load-bearing rather than tidy: a therapeutic ceiling as the
        // escalation denominator would need an 80 mg dose to open the deep tolerance
        // gate, for a substance whose recreational ladder tops out at 8 mg. The gate
        // would never open.
        mitragynine.referenceDoseMg shouldBe 6.0
    }

    // MARK: - The derivation layer

    @Test
    fun `2-MMC borrows mephedrone PK, flagged at most low confidence`() {
        // 2-MMC has no citeable PK of its own; its pointer borrows mephedrone's
        // kinetics wholesale. The borrow is flagged, while 2-MMC's OWN transporter
        // bindings — never borrowed — still drive the target.
        val p = params("2-MMC")
        p.vdLPerKg shouldBe 2.6
        p.halfLifeMinutes.shouldNotBeNull()
        p.molarMassGramsPerMole.shouldNotBeNull()
        // The borrowed coherent row is the rat one, and it stays flagged.
        p.pkSpecies shouldBe "rat"
        (p.vdConfidence <= ConfidenceTier.LOW) shouldBe true
        val primary = p.primaryTarget.shouldNotBeNull()
        primary.action shouldBe BindingAction.RELEASING_AGENT
    }

    @Test
    fun `A borrowed parameter set is computable but never badged above low`() {
        val p = params("2-MMC")
        p.canComputeOccupancy shouldBe true
        val occupancy = p.peakPrimaryOccupancy(doseMg = 100.0, weightKg = 70.0).shouldNotBeNull()
        (occupancy > 0) shouldBe true
        (p.vdConfidence <= ConfidenceTier.LOW) shouldBe true
        (p.bioavailabilityConfidence <= ConfidenceTier.LOW) shouldBe true
    }

    @Test
    fun `The reference borrow is single-hop with no transitive chain`() {
        val db = openBundledSubstanceDb()
        val reader = SubstanceReader(
            db,
            db.query("SELECT slug FROM sources ORDER BY default_priority, slug").mapNotNull { it.string("slug") },
            ContentLanguage.EN,
        )
        val index = SubstanceIdentityIndex.build(db)
        val mephedroneID = index.resolve("Mephedrone").shouldNotBeNull()
        val twoMmcID = index.resolve("2-MMC").shouldNotBeNull()

        // The surrogate carries real PK and no onward pointer.
        reader.pkReference(mephedroneID).shouldBeNull()
        (reader.pharmacokinetics(mephedroneID).isEmpty()) shouldBe false

        // The subject's pointer names it, and is badged at most low.
        val reference = reader.pkReference(twoMmcID).shouldNotBeNull()
        reference.name shouldBe "Mephedrone"
        (reference.confidence <= ConfidenceTier.LOW) shouldBe true
        reference.fields shouldBe setOf("vd", "bioavailability", "tmax", "half_life")

        // And the engine refuses an onward pointer however the table is edited.
        val chained = PharmacologyAssembly.applyPKReference(
            ownRows = emptyList(),
            candidate = glass.kagerou.piru.engine.ReferenceCandidate(
                reference = reference,
                hasOnwardPointer = true,
                rows = reader.pharmacokinetics(mephedroneID),
            ),
        )
        chained.isEmpty() shouldBe true
    }

    @Test
    fun `A borrow tops up only the fields the pointer licenses`() {
        val db = openBundledSubstanceDb()
        val reader = SubstanceReader(
            db,
            db.query("SELECT slug FROM sources ORDER BY default_priority, slug").mapNotNull { it.string("slug") },
            ContentLanguage.EN,
        )
        val mephedroneID = index(db, "Mephedrone")
        val reference = glass.kagerou.piru.engine.PKReference(
            name = "Mephedrone",
            fields = setOf("half_life"),
            confidence = ConfidenceTier.LOW,
        )
        val own = reader.pharmacokinetics(index(db, "Amphetamine"))
        val merged = PharmacologyAssembly.applyPKReference(
            ownRows = own,
            candidate = glass.kagerou.piru.engine.ReferenceCandidate(
                reference = reference,
                rows = reader.pharmacokinetics(mephedroneID),
            ),
        )
        // Amphetamine's coherent row already carries a Vd, so a half-life-only
        // pointer must leave the rows otherwise untouched.
        merged.map { it.vdLPerKg } shouldBe own.map { it.vdLPerKg }
        merged.map { it.bioavailabilityPct } shouldBe own.map { it.bioavailabilityPct }
    }

    private fun index(db: SubstanceDb, name: String): Long =
        SubstanceIdentityIndex.build(db).resolve(name).shouldNotBeNull()

    // MARK: - Zero-order wiring

    @Test
    fun `Zero-order kinetics carry the bioavailability the occupancy math reads`() {
        // The curve the timeline draws and the occupancy the tolerance engine
        // predicts must not be able to disagree about F for one dose, so the
        // kinetics take it from the same resolve rather than a second lookup.
        val catalog = catalog(openBundledSubstanceDb())
        val kinetics = catalog.zeroOrderKinetics("Alcohol", weightKg = 60.0).shouldNotBeNull()
        val resolved = catalog.pharmacologyParameters("Alcohol").bioavailabilityFraction ?: 1.0
        kinetics.bioavailability shouldBe resolved
        kinetics.vmaxMgPerMin shouldBe 95.0
        kinetics.ka shouldBe 0.026

        // And the switch engages through an alias too.
        catalog.zeroOrderKinetics("ethanol", weightKg = 60.0) shouldBe kinetics
        catalog.zeroOrderKinetics("Caffeine", weightKg = 60.0).shouldBeNull()
    }

    @Test
    fun `Vmax scales with weight inside the modeled band and is held outside it`() {
        val catalog = catalog(openBundledSubstanceDb())
        val at60 = catalog.zeroOrderKinetics("Alcohol", weightKg = 60.0).shouldNotBeNull()
        val at120 = catalog.zeroOrderKinetics("Alcohol", weightKg = 120.0).shouldNotBeNull()
        (abs(at120.vmaxMgPerMin - 2 * at60.vmaxMgPerMin) < 1e-9) shouldBe true
        // Held at the band edges rather than extrapolated: a clearance projected
        // from a 5 kg body is arithmetic, not physiology.
        catalog.zeroOrderKinetics("Alcohol", weightKg = 5.0) shouldBe
            catalog.zeroOrderKinetics("Alcohol", weightKg = glass.kagerou.piru.engine.PKModel.MINIMUM_MODELED_WEIGHT_KG)
        catalog.zeroOrderKinetics("Alcohol", weightKg = 999.0) shouldBe
            catalog.zeroOrderKinetics("Alcohol", weightKg = glass.kagerou.piru.engine.PKModel.MAXIMUM_MODELED_WEIGHT_KG)
    }

    // MARK: - Interspecies scaling, on the shipped rows

    @Test
    fun `The shipped animal rows scale and the human ones do not`() {
        val db = openBundledSubstanceDb()
        val reader = SubstanceReader(
            db,
            db.query("SELECT slug FROM sources ORDER BY default_priority, slug").mapNotNull { it.string("slug") },
            ContentLanguage.EN,
        )
        val mephedrone = reader.pharmacokinetics(index(db, "Mephedrone"))
        val rat = mephedrone.first { it.species == "rat" }
        val scaled = InterspeciesScaling.scaledToHuman(rat)
        // Vd per kg is species-invariant; the confidence is not.
        scaled.vdLPerKg shouldBe rat.vdLPerKg
        scaled.confidence shouldBe ConfidenceTier.LOW
        // A human row passes through untouched.
        val human = mephedrone.first { it.species == "human" }
        InterspeciesScaling.scaledToHuman(human) shouldBe human
    }
}
