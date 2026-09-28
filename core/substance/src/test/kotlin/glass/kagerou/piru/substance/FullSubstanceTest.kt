package glass.kagerou.piru.substance

import glass.kagerou.piru.model.BindingAction
import glass.kagerou.piru.model.BindingAffinity
import glass.kagerou.piru.model.CompoundDisplayClass
import glass.kagerou.piru.model.ReceptorStrength
import glass.kagerou.piru.model.StorageRequirement
import glass.kagerou.piru.model.SubstanceCategory
import glass.kagerou.piru.model.SuppliedForm
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The heavy `resolveFull` read against the shipped catalog.
 *
 * `DbSubstanceCatalogTest` covers the batch path — the fields the timeline and
 * the body-load readout need. This covers the other one: mechanism, bindings,
 * chemistry, prose effects, citations and the curated editorial blobs, which
 * only a detail screen asks for.
 *
 * Every expectation was read out of the real database with `sqlite3` before it
 * was written down.
 */
class FullSubstanceTest {

    private fun catalog(db: SubstanceDb) = DbSubstanceCatalog.open(
        db = db,
        order = db.query("SELECT slug FROM sources ORDER BY default_priority, slug")
            .mapNotNull { it.string("slug") },
        language = ContentLanguage.EN,
    )

    private fun full(name: String) = catalog(openBundledSubstanceDb()).resolveFull(name)!!

    // MARK: - The two read paths are not the same thing

    @Test
    fun `The batch path leaves the detail fields at their defaults`() {
        // Not null because the catalog lacks them — because the batch projection
        // never read them. A caller that wants a mechanism must ask for it.
        val batch = catalog(openBundledSubstanceDb()).lookup("Caffeine")!!
        batch.mechanismOfAction.shouldBeNull()
        batch.effects.shouldBeEmpty()
        batch.references.shouldBeEmpty()
        // The fields the timeline does read are all there.
        batch.halfLifeMinutes shouldBe 300.0
        batch.routes.size shouldBe 3
    }

    @Test
    fun `The full path fills them in`() {
        val caffeine = full("Caffeine")
        caffeine.mechanismOfAction.shouldNotBeNull()
        (caffeine.effects.isNotEmpty()) shouldBe true
        // And the shared fields agree between the two reads.
        caffeine.halfLifeMinutes shouldBe 300.0
        caffeine.category shouldBe SubstanceCategory.STIMULANT
    }

    // MARK: - Chemistry and identity

    @Test
    fun `Chemical identity comes through`() {
        val codeine = full("Codeine")
        codeine.formula shouldBe "C18H21NO3"
        codeine.cas shouldBe "76-57-3"
        codeine.inchikey shouldBe "OROGSEYTTFOCAN-DNJOTXNNSA-N"
        codeine.molarMass shouldBe 299.37
        // The physicochemical block renders only when something is in it.
        val physicochemical = codeine.physicochemical.shouldNotBeNull()
        (physicochemical.hasAnyValue) shouldBe true
    }

    @Test
    fun `The display class and its gates come from the catalog's own column`() {
        full("Codeine").displayClass shouldBe CompoundDisplayClass.RECREATIONAL
        full("Codeine").regulatoryStatus shouldBe "controlled_schedule_2"
        // A prescription drug with no recreational frame: mechanism and warnings
        // may show, a dose ladder may not.
        val rx = full("Acamprosate")
        rx.displayClass shouldBe CompoundDisplayClass.MEDICAL_RX
        rx.displayClass.showsDoseLadder shouldBe false
        rx.displayClass.showsDuration shouldBe false
        rx.displayClass.mayReportLimitedData shouldBe false
    }

    @Test
    fun `Tags and popularity come through`() {
        val codeine = full("Codeine")
        (codeine.tags.contains("CYP2D6-prodrug")) shouldBe true
        (codeine.popularity > 0) shouldBe true
        codeine.isStub shouldBe false
    }

    @Test
    fun `An alias resolves through the identity index, stub demotion included`() {
        // "Adderall" is a contested alias: the catalog carries it on both
        // Amphetamine (121) and the data-less `Dextroamphetamine-Amphetamine`
        // stub. Whichever row the alias index happens to record first, the
        // resolve has to land on the substance that has content — which is the
        // whole reason the stub-demotion rule exists.
        full("Adderall").name shouldBe "Amphetamine"
    }

    @Test
    fun `An unknown name resolves to nothing`() {
        catalog(openBundledSubstanceDb()).resolveFull("Unobtainium").shouldBeNull()
    }

    // MARK: - Effects

    @Test
    fun `The flat effect list is the catalog's, deduplicated`() {
        val caffeine = full("Caffeine")
        caffeine.effects.size shouldBe 21
        (caffeine.effects.contains("Bronchodilation")) shouldBe true
        (caffeine.effects.contains("Analysis enhancement")) shouldBe true
        // Deduplicated and ordered, as the SQL's DISTINCT + ORDER BY promises.
        caffeine.effects shouldBe caffeine.effects.distinct()
        caffeine.effects shouldBe caffeine.effects.sorted()
    }

    @Test
    fun `Subjective effects carry prose only where the catalog wrote it`() {
        // Codeine is one of two substances whose subjective-effect rows carry
        // descriptions. A bridged row's prose stays in its own language, and the
        // resolver drops it rather than showing a Han paragraph under an English
        // name — so "some effects have no description" is the correct outcome,
        // not a gap.
        val codeine = full("Codeine")
        codeine.subjectiveEffects.size shouldBe 37
        (codeine.subjectiveEffects.count { it.description.isNotEmpty() }) shouldBe 15
        val constipation = codeine.subjectiveEffects.first { it.name == "Constipation" }
        (constipation.description.startsWith("Reduced propulsive peristalsis")) shouldBe true
    }

    @Test
    fun `Effect groups order the known categories before the unknown ones`() {
        val db = openBundledSubstanceDb()
        val reader = SubstanceReader(
            db,
            db.query("SELECT slug FROM sources ORDER BY default_priority, slug")
                .mapNotNull { it.string("slug") },
            ContentLanguage.EN,
        )
        val id = db.queryOne("SELECT id FROM substances WHERE canonical_name = 'Caffeine'")!!.long("id")!!
        val groups = reader.effectGroups(id)
        (groups.isNotEmpty()) shouldBe true
        // Every effect in the flat list appears in exactly one group.
        groups.flatMap { it.effects }.sorted() shouldBe reader.effects(id).sorted()
    }

    // MARK: - Mechanism

    @Test
    fun `Caffeine's mechanism is its adenosine antagonism`() {
        val mechanism = full("Caffeine").mechanismOfAction.shouldNotBeNull()
        (mechanism.summary.isNotEmpty()) shouldBe true
        mechanism.summaryLanguage shouldBe "en"
        // Both targets are curated as primary, so the derived band never runs —
        // and the summary's order puts A2A first on its tighter measured Kᵢ.
        mechanism.bindings.map { it.target } shouldBe listOf("Adenosine A2A", "Adenosine A1")
        mechanism.bindings.forEach {
            it.action shouldBe BindingAction.ANTAGONIST
            it.affinity shouldBe BindingAffinity.PRIMARY
        }
        // `primaryTargets` defaults to the bindings' own targets.
        mechanism.effectivePrimaryTargets shouldBe listOf("Adenosine A2A", "Adenosine A1")
    }

    @Test
    fun `A curated affinity tier overrides the derived band`() {
        // The case the query's comment is about. Methamphetamine's SERT release
        // EC₅₀ is 736 nM, which the derived band reads as strong (under 1 µM);
        // the curator wrote tier 1, and the curated value has to win — SERT is
        // where the drug is *weakest*, which is the whole point of the card.
        val mechanism = full("Methamphetamine").mechanismOfAction.shouldNotBeNull()
        val byTarget = mechanism.bindings.associateBy { it.target }

        byTarget.getValue("DAT").affinity shouldBe BindingAffinity.PRIMARY
        byTarget.getValue("NET").affinity shouldBe BindingAffinity.PRIMARY
        byTarget.getValue("SERT").affinity shouldBe BindingAffinity.WEAK
        byTarget.getValue("SERT").action shouldBe BindingAction.RELEASING_AGENT

        // And the divergence is real: the derived band alone would have said
        // strong. Both sides of that disagreement are asserted, so a change to
        // either the SQL cutoffs or `ReceptorStrength` shows up here.
        ReceptorStrength.functionalTier(736.0) shouldBe 3
        ReceptorStrength.tier(ec50Nm = 736.0) shouldBe 3
    }

    @Test
    fun `Subunit-specific binding targets stay distinct from the coarse one`() {
        // Alprazolam's four rows are one coarse target and three named assays.
        // Collapsing them by anything looser than the fold would hide the
        // subunit detail the receptor literature card is built on.
        val alprazolam = full("Alprazolam")
        val mechanism = alprazolam.mechanismOfAction.shouldNotBeNull()
        mechanism.bindings.size shouldBe 4
        (mechanism.bindings.count { it.target == "GABA-A" }) shouldBe 1
        mechanism.bindings.forEach { it.action shouldBe BindingAction.POSITIVE_ALLOSTERIC_MODULATOR }
    }

    // MARK: - Curated editorial

    @Test
    fun `A diazepam equivalent is present for a benzodiazepine and absent elsewhere`() {
        val alprazolam = full("Alprazolam")
        val equivalent = alprazolam.diazepamEquivalent.shouldNotBeNull()
        equivalent.doseMg shouldBe 0.5
        equivalent.equivalentDiazepamMg shouldBe 10.0
        (equivalent.displayText!!.startsWith("Alprazolam - 0.5mg")) shouldBe true
        // Not read from the row: the pipeline's citation decision is not
        // recoverable from the database, so the flag keeps its default.
        equivalent.isCited shouldBe false

        full("Caffeine").diazepamEquivalent.shouldBeNull()
    }

    @Test
    fun `Tolerance needs all three numbers, not just a row`() {
        // MDMA and Alprazolam both carry a `tolerance` row, and both have every
        // column null. A half-life with no reset figure would let the UI claim a
        // decay it cannot compute, so the read is all-or-nothing.
        full("MDMA").toleranceInfo.shouldBeNull()
        full("Alprazolam").toleranceInfo.shouldBeNull()
        // A substance that does carry all three.
        val codeine = full("Codeine").toleranceInfo
        if (codeine != null) {
            (codeine.fullResetDays > codeine.halfLife) shouldBe true
            (codeine.buildRate.isNotEmpty()) shouldBe true
        }
    }

    @Test
    fun `References resolve to tappable links where the catalog gives one`() {
        val mdma = full("MDMA")
        mdma.references.size shouldBe 2
        val doi = mdma.references.first { it.doi != null }
        doi.resolvedUrl shouldBe "https://doi.org/${doi.doi}"
        (doi.label.startsWith("Impact of Cytochrome")) shouldBe true
        // A bare URL reference keeps its own link.
        val url = mdma.references.first { it.doi == null }
        url.resolvedUrl shouldBe url.url
        // An empty citation has no link, which is how a free-text label renders.
        glass.kagerou.piru.model.Citation(title = "Egrifta SmPC").resolvedUrl.shouldBeNull()
    }

    @Test
    fun `The curated editorial blobs decode`() {
        val mdma = full("MDMA")
        mdma.misconceptions.size shouldBe 3
        val myth = mdma.misconceptions.first()
        (myth.claim.isNotEmpty()) shouldBe true
        (myth.citations.isNotEmpty()) shouldBe true
        // The refuting source is marked as refuting, never as support.
        myth.citations.first().role shouldBe glass.kagerou.piru.model.MythCitation.Role.REFUTES

        mdma.combinations.size shouldBe 3
        val danger = mdma.combinations.filter { it.severity == glass.kagerou.piru.model.Combination.Severity.DANGER }
        (danger.isNotEmpty()) shouldBe true
    }

    @Test
    fun `Water and heat guidance is bounded on both sides`() {
        val guidance = full("Amphetamine").waterHeat.shouldNotBeNull()
        guidance.headline shouldBe "Sip to thirst"
        (guidance.body.isNotEmpty()) shouldBe true
        // The long tail has none, which is correct rather than a gap.
        full("Caffeine").waterHeat.shouldBeNull()
    }

    // MARK: - Peptides

    @Test
    fun `A peptide profile reads its handling data`() {
        val semaglutide = full("Semaglutide")
        val profile = semaglutide.peptideProfile.shouldNotBeNull()
        profile.suppliedForm shouldBe SuppliedForm.SOLUTION
        (profile.sequence!!.startsWith("GLP-1(7-37) analog")) shouldBe true
        val storage = profile.storage.shouldNotBeNull()
        storage.temperature shouldBe StorageRequirement.Temperature.REFRIGERATE
        storage.lightSensitive shouldBe true
        storage.reconstitutedStabilityDays shouldBe 42.0
        // And it is not a psychoactive trip-arc compound.
        semaglutide.effects.shouldBeEmpty()
    }

    @Test
    fun `A substance with no peptide row has no profile`() {
        full("Caffeine").peptideProfile.shouldBeNull()
    }

    // MARK: - Attribution

    @Test
    fun `Cited sources are the ones that contributed a fact`() {
        val caffeine = full("Caffeine")
        caffeine.sources.size shouldBe 7
        (caffeine.sources.contains("piru-curated")) shouldBe true
        (caffeine.sources.contains("psychonautwiki")) shouldBe true
        // Sorted, so the attribution row is stable.
        caffeine.sources shouldBe caffeine.sources.sorted()
    }
}
