package glass.kagerou.piru.substance

import glass.kagerou.piru.engine.ClassInteractionRule
import glass.kagerou.piru.engine.DrugClass
import glass.kagerou.piru.engine.Enzyme
import glass.kagerou.piru.engine.EnzymeModulator
import glass.kagerou.piru.engine.EsterRecord
import glass.kagerou.piru.engine.InteractionChecker
import glass.kagerou.piru.engine.InteractionData
import glass.kagerou.piru.engine.MetabolicModulation
import glass.kagerou.piru.engine.PKInteractionHit
import glass.kagerou.piru.engine.PKModel
import glass.kagerou.piru.engine.PharmacologyAssembly
import glass.kagerou.piru.engine.PharmacologyParameters
import glass.kagerou.piru.engine.PharmacologySource
import glass.kagerou.piru.engine.PreparationRouting
import glass.kagerou.piru.engine.ReceptorClasses
import glass.kagerou.piru.engine.ReferenceCandidate
import glass.kagerou.piru.engine.SubstanceCatalog
import glass.kagerou.piru.engine.TagEnzymeInteraction
import glass.kagerou.piru.model.ByVolumeDosing
import glass.kagerou.piru.model.DurationProfile
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.Substance
import glass.kagerou.piru.model.CompoundDisplayClass
import glass.kagerou.piru.model.SubstanceCategory
import glass.kagerou.piru.model.EffectGroup
import glass.kagerou.piru.model.SubjectiveEffect
import glass.kagerou.piru.engine.BindingHit
import glass.kagerou.piru.engine.MetabolismHit
import glass.kagerou.piru.engine.DownstreamSignallingHit
import glass.kagerou.piru.engine.OffTargetHit
import glass.kagerou.piru.engine.PharmacogeneticHit
import glass.kagerou.piru.engine.MoleculeShape

/**
 * The bundled catalog, seen through the engine's [SubstanceCatalog] port.
 *
 * This is the joint the port was waiting for: everything the curve and body-load
 * math needs to go from "a dose the user logged" to "a substance with a duration
 * profile and a half-life" passes through here, and none of it passes through the
 * engine's own code.
 *
 * Ported from `SubstanceReadModel.loadAllSubstancesBatch(db:order:)` plus the
 * whole-table indexes `SubstanceStore.buildIndexes` installs — the ester PK
 * index, the product-duration envelopes, the localized and regional name tables.
 *
 * ## Two shapes, one catalog
 * Upstream has a dozen entry points because a SwiftUI view asks for one substance
 * at a time and the batch path exists to keep that from becoming 21,000 queries.
 * The port has [SubstanceCatalog] and its two real callers — the timeline, which
 * resolves a day's worth of doses at once, and search, which is already
 * in-memory. So there is one resolution path and it is the **batch** one, warmed
 * on first use and reused for the life of the object. A single lookup pays for the
 * whole catalog once; every lookup after it is a map read.
 */
class DbSubstanceCatalog private constructor(
    private val reader: SubstanceReader,
    private val index: SubstanceIdentityIndex,
    private val language: ContentLanguage,
    private val usesEnglishNames: Boolean,
    private val region: String?,
) : SubstanceCatalog, PharmacologySource, InteractionData {

    /**
     * Every substance in the catalog, by row id.
     *
     * Built once. Upstream splits this in two — a heavy per-substance resolve on
     * the main actor and a lighter batch projection for the list and timeline
     * paths — because SwiftUI re-resolves on every body evaluation and the heavy
     * SQL would land on the main thread. Nothing here re-resolves; a caller asks
     * once and holds the result.
     */
    private val byID: Map<Long, Substance> by lazy { build() }

    /** Lowercased name and alias to row id — the same first-wins rule the search index uses. */
    private val byName: Map<String, Substance> by lazy {
        val out = mutableMapOf<String, Substance>()
        for (substance in byID.values) {
            out.putIfAbsent(substance.name.lowercase(), substance)
        }
        // Aliases fill gaps only, so a shared alias never shadows a substance's own
        // row. A second pass rather than one, so the rule does not depend on the
        // order the rows happen to arrive in.
        for (substance in byID.values) {
            for (alias in substance.aliases) {
                out.putIfAbsent(alias.lowercase(), substance)
            }
        }
        out
    }

    private val localizedNames: Map<String, Map<String, String>> by lazy { reader.localizedNames() }
    private val regionalNames: Map<String, RegionalSubstanceName.Variant> by lazy { reader.regionalNames() }
    private val productDurations: Map<String, DurationProfile> by lazy { reader.productDurations() }
    private val esters: Map<String, List<EsterRecord>> by lazy { reader.estersByParentUID() }
    private val descriptorConcepts: Map<String, SubjectiveEffectConcept> by lazy {
        reader.subjectiveEffectConcepts().associateBy { it.id }
    }

    /** The `localized_names` tag this catalog resolves titles for, or null in English. */
    private val nameLanguage: String? = LocalizedSubstanceName.languageFor(language)

    // MARK: - SubstanceCatalog

    override fun lookup(name: String): Substance? = byName[name.lowercase()]

    /**
     * A note's descriptor concept id resolved to its canonical English name and
     * domain, or null when the id is unknown. The reports call this through their
     * `descriptorResolver` so a note whose concept is no longer in the catalog
     * simply omits the descriptor rather than printing an id.
     */
    fun descriptorConcept(id: String): SubjectiveEffectConcept? = descriptorConcepts[id]

    /**
     * The **full** per-substance record — mechanism, receptor bindings, chemistry
     * identifiers, prose effects, citations, curated misconceptions and
     * combinations — by canonical name or any alias.
     *
     * **Detail screens only.** Uncached, this runs about twenty SQL statements
     * per substance, and [lookup] never warms it. Everything that reads a name,
     * category, dose ladder, duration or half-life belongs on [lookup]; the name
     * is heavy on purpose, so reaching for it is a deliberate act.
     *
     * The resolution path is the identity index, which carries the stub-demotion
     * rule — so a name that resolves to a data-less row yields to the substance
     * that actually has content. Upstream spells the same rule twice (once for
     * the store's `substanceID(forNameOrAlias:)` and once inside the batch
     * index); here there is one.
     */
    fun resolveFull(nameOrAlias: String): Substance? {
        val id = index.resolve(nameOrAlias) ?: return null
        val substance = reader.fullSubstance(id) ?: return null
        return titled(substance)
    }

    /** A substance's read layer row with the two title fields its language and region imply. */
    private fun titled(substance: Substance): Substance = substance.copy(
        localizedName = LocalizedSubstanceName.resolve(
            canonicalName = substance.name,
            language = nameLanguage,
            usesEnglishNames = usesEnglishNames,
            table = localizedNames,
        ),
        regionalName = RegionalSubstanceName.resolve(substance.name, region, regionalNames),
    )

    /**
     * Ranked search over the catalog — the quick-log picker's engine.
     *
     * Runs entirely against the warmed indexes, so a keystroke never touches SQL.
     * It is deliberately *not* a `SubstanceCatalog` port method: the engine has no
     * notion of a search, and the one caller is a text field.
     */
    fun search(query: String, limit: Int = 50): List<SubstanceMatch<Substance>> =
        SubstanceSearch.rankedSearch(
            query = query,
            nameIndex = index.nameIndex,
            aliasIndex = index.aliasIndex,
            aliasDisplayIndex = index.aliasDisplayIndex,
            idToSubstance = byID,
            limit = limit,
        )

    // MARK: - Category browse

    /**
     * Every category that has at least one substance, most populated first.
     *
     * A substance counts toward its primary category **and** toward each of its
     * [Substance.extraBrowseCategories] — a curated multi-class compound appears
     * under every home it belongs to, which is the point of the browse grid. The
     * count is the card's badge; the members behind it are [substancesIn].
     *
     * Ties break by enum order, so a rebuild lists the same categories in the
     * same order rather than in whichever order the map happened to hand them
     * back.
     */
    fun categorySummary(): List<Pair<SubstanceCategory, Int>> {
        val counts = mutableMapOf<SubstanceCategory, Int>()
        for (substance in byID.values) {
            // Only substances that surface in the browse. Without this the badge counts
            // included the non-recreational compounds the grid itself excludes, so every
            // card claimed more members than it listed.
            if (!substance.displayClass.surfacesInBrowse) continue
            counts[substance.category] = (counts[substance.category] ?: 0) + 1
            for (extra in substance.extraBrowseCategories) {
                counts[extra] = (counts[extra] ?: 0) + 1
            }
        }
        return counts.toList()
            .sortedWith(
                compareByDescending<Pair<SubstanceCategory, Int>> { it.second }
                    .thenBy { it.first.ordinal },
            )
    }

    /**
     * The substances that appear under [category] in the browse, popularity first.
     *
     * A curated multi-class compound is listed under each of its browse homes, not
     * only its primary category. Popularity first is the upstream browse sort — the
     * long tail (score 0) falls to the end, then alphabetical.
     *
     * Non-recreational compounds are excluded: `CompoundDisplayClass.surfacesInBrowse`
     * documents them as "searchable, for medication tracking, but not surfaced in the
     * browse grid". That filter could not be expressed before `build` started reading
     * `display_class`, because every substance carried the `RECREATIONAL` default — so
     * 26 prescription-only compounds were listed in category browsing.
     */
    fun substancesIn(category: SubstanceCategory): List<Substance> =
        byID.values
            .filter { it.displayClass.surfacesInBrowse }
            .filter { it.category == category || category in it.extraBrowseCategories }
            .sortedWith(
                compareByDescending<Substance> { it.popularity }
                    .thenBy { it.displayTitle.lowercase() },
            )

    /**
     * Every substance carrying [tag], most popular first.
     *
     * A second browse axis beside the category grid, and one the grid cannot express: a substance has
     * one category and several tags, and tags are how the catalogue says "these are related" without
     * claiming a receptor class.
     *
     * The same two gates `substancesIn` applies — `surfacesInBrowse` and the popularity sort — so the
     * two browse axes agree about what belongs in a list and in what order.
     */
    fun substancesWithTag(tag: String): List<Substance> =
        reader.substanceIDsForTag(tag)
            .mapNotNull { byID[it] }
            .filter { it.displayClass.surfacesInBrowse }
            .sortedWith(
                compareByDescending<Substance> { it.popularity }
                    .thenBy { it.displayTitle.lowercase() },
            )

    // MARK: - Pharmacology

    /**
     * How a preparation's pharmacology is read from its active constituent.
     * Exposed so a caller can badge the routing without re-resolving it.
     */
    fun preparationRoute(name: String): PreparationRouting.Route = PreparationRouting.route(name)

    /**
     * Every resolved pharmacology input for the occupancy pipeline — molar mass,
     * the coherent Vd with bioavailability and half-life, the engaged targets with
     * their half-saturation constants, and the derivation layer's borrows.
     *
     * ## Which id each field resolves through, and why they differ
     * A **preparation** routes to its active compound for the molecule's own facts —
     * molar mass, PK, bindings, metabolism, the model flags — because those are
     * properties of the molecule. But the escalation reference comes from the
     * **logged** substance's own dose ladder, and its tolerance classes from the
     * logged substance's own category, because the logged dose and the ladder are
     * both in preparation milligrams and a preparation keeps its own tolerance
     * identity. Kratom's reference is Kratom's own, not mitragynine's.
     *
     * A name the catalog does not carry yields a parameters record with a null
     * molar mass and no targets, which the engine treats as uncomputable rather
     * than as a zero.
     */
    override fun pharmacologyParameters(nameOrAlias: String): PharmacologyParameters {
        val routed = PreparationRouting.route(nameOrAlias)

        // The logged substance's own rows: the escalation reference and the
        // category classes.
        val loggedID = index.resolve(nameOrAlias)
        val referenceDoseMg = loggedID?.let { PharmacologyAssembly.referenceDoseMg(reader.referenceDoseRows(it)) }
        val categoryClasses = loggedID?.let { reader.toleranceCategoryClasses(it) } ?: emptySet()

        // The active compound's rows: everything else.
        val pkID = index.resolve(routed.activeName)

        val ownRows = pkID?.let { reader.pharmacokinetics(it) } ?: emptyList()
        val effectivePK = PharmacologyAssembly.applyPKReference(ownRows, pkID?.let { referenceCandidate(it) })

        return PharmacologyAssembly.assemble(
            molarMass = pkID?.let { reader.molarMass(it) },
            pk = effectivePK,
            bindingHits = pkID?.let { reader.bindingRows(it) } ?: emptyList(),
            therapeuticRanges = pkID?.let { reader.therapeuticRangeRows(it) } ?: emptyList(),
            doseScale = routed.scale,
            doseScaleConfidence = routed.confidence,
            referenceDoseMg = referenceDoseMg,
            // Keyed on the active compound, defaulting to full-agonist 1.0 for every
            // substance with no row — which is nearly all of them, and means "no
            // reason to model this as partial", not "unknown".
            intrinsicEfficacy = pkID?.let { reader.intrinsicEfficacy(it) } ?: 1.0,
            categoryClasses = categoryClasses,
            metabolites = pkID?.let { PharmacologyAssembly.metaboliteContributors(reader.metabolismRows(it)) }
                ?: emptyList(),
            suppressesSerotoninSynthesis = pkID?.let {
                reader.hasFlag(PharmacologyParameters.Companion.Flag.SUPPRESSES_SEROTONIN_SYNTHESIS, it)
            } ?: false,
            diazepamPerMg = pkID?.let { reader.diazepamPerMg(it) },
            opioidMMEPerMg = pkID?.let { reader.opioidMme(it)?.mmePerMg },
            representsClasses = pkID?.let { classRepresentatives[it] } ?: emptySet(),
        )
    }

    /**
     * The names of the substances that stand in for a tolerance class.
     *
     * A tolerance replay must resolve these alongside the logged names: the
     * missing-PK fallback models a PK-less substance as its class representative, so
     * a log containing only the PK-less member still needs the surrogate in hand —
     * and it will typically never have been logged.
     */
    override fun classRepresentativeNames(): List<String> = reader.classRepresentativeNames()

    /**
     * What the substance's `pk_reference` pointer resolved to, with the two gates
     * the borrow decision needs.
     *
     * Single-hop is enforced by reading whether the *reference* itself carries a
     * pointer, rather than by trusting the pointer's own claim. The engine refuses
     * the borrow on either flag, so a transitive chain cannot form however the
     * table is edited.
     */
    private fun referenceCandidate(subjectID: Long): ReferenceCandidate? {
        val reference = reader.pkReference(subjectID) ?: return null
        val referenceID = index.resolve(reference.name)
        return ReferenceCandidate(
            reference = reference,
            isSelf = referenceID == subjectID,
            hasOnwardPointer = referenceID?.let { reader.pkReference(it) } != null,
            rows = referenceID?.let { reader.pharmacokinetics(it) } ?: emptyList(),
        )
    }

    private val classRepresentatives: Map<Long, Set<ReceptorClasses.ReceptorClass>> by lazy {
        reader.classRepresentatives()
    }

    /**
     * The dose-scaled zero-order elimination parameters for a substance, or null
     * when it clears first-order like nearly everything else.
     *
     * The bioavailability comes from the same [pharmacologyParameters] resolve the
     * occupancy math reads, never from a second lookup here. That is the whole
     * point: the curve the timeline draws and the occupancy the tolerance engine
     * predicts must not be able to disagree about F for one dose. Upstream says so
     * in the read's own doc, and a `zero_order_kinetics` column carrying F would
     * break it.
     */
    override fun zeroOrderKinetics(substanceName: String, weightKg: Double): PKModel.ZeroOrderKinetics? {
        val row = zeroOrderRow(substanceName) ?: return null
        val bioavailability = pharmacologyParameters(row.canonicalName).bioavailabilityFraction ?: 1.0
        return PKModel.zeroOrderKinetics(
            vmaxMgPerMin = row.vmaxMgPerMin,
            referenceWeightKg = row.referenceWeightKg,
            kaPerMin = row.kaPerMin,
            bioavailability = bioavailability,
            weightKg = weightKg,
        )
    }

    // MARK: - By-volume dosing

    /**
     * Whether a substance accepts a volume-and-concentration input, and the
     * constants the conversion needs.
     *
     * Keyed by lowercased canonical name and by every alias, so a dose logged as
     * "Booze" finds the same capability "Alcohol" does. Null for the overwhelming
     * majority — two substances in the shipped catalog opt in.
     *
     * Not on [SubstanceCatalog]: the engine never sees a volume. This answers a
     * question about the dose *form*, and the caller is the editor that renders it.
     */
    fun byVolumeDosing(nameOrAlias: String): ByVolumeDosing? =
        byVolumeCapabilities[nameOrAlias.lowercase()]

    /** Every substance that accepts a by-volume input, keyed as above — for the editor's capability probe. */
    val byVolumeCapabilities: Map<String, ByVolumeDosing> by lazy { reader.byVolumeCapabilities() }

    /**
     * The substance's effects grouped by PsychonautWiki category.
     *
     * `SubstanceReader.effectGroups` documents itself as feeding "the 'All effects' screen, which is
     * the only caller" — and it had **no caller at all** in this port, because the screen did not
     * exist and there was no way to reach the reader from the catalogue. This is that way.
     *
     * Not on [SubstanceCatalog]: the engine reads the flat [Substance.effects] union for browse and
     * search, and has no notion of a category. It answers a question about presentation, and the
     * caller is the screen that presents it.
     */
    /**
     * One substance's line in the pharmacology table.
     *
     * A seed-merge of three sources — identity and the half-life fallback from the browse metadata, the PK
     * detail from the preferred `pk_routes` row, and the class title from the write-ups — so the screen
     * draws one flat row rather than joining three reads per keystroke.
     */
    data class PharmaTableRow(
        val name: String,
        val displayName: String,
        val category: SubstanceCategory,
        /** The curated class this substance belongs to, or null. Named as the class deliberately: it is the family, not the molecule. */
        val classTitle: String?,
        val halfLifeMin: Double?,
        val tmaxMin: Double?,
        val bioavailabilityPct: Double?,
        val cmaxNgPerMl: Double?,
        val proteinBindingPct: Double?,
        val vdLPerKg: Double?,
        val clearanceMlPerMinPerKg: Double?,
        /** The route the PK numbers came from, so a reader knows what they describe. */
        val route: RouteOfAdministration?,
        val sourceSlug: String,
    ) {
        /**
         * Whether the row is worth showing.
         *
         * True as soon as one column has a number. The table's whole value is the columns, so a row with
         * none of them would be a name in a grid of dashes.
         */
        val hasAnyData: Boolean
            get() = halfLifeMin != null || tmaxMin != null || bioavailabilityPct != null ||
                cmaxNgPerMl != null || proteinBindingPct != null || vdLPerKg != null ||
                clearanceMlPerMinPerKg != null
    }

    /**
     * The identity facets a name or alias is annotated with.
     *
     * Upstream's `SubstanceLibrary.isomer(for:)` / `releaseForm(for:)`, which turns "Concerta" into
     * Methylphenidate·XR. The annotations were in the catalogue's `aliases` table the whole time and
     * the identity index read past them — see [SubstanceIdentityIndex.Facets].
     *
     * Not on [SubstanceCatalog]: the engine identifies a substance by its family uid and has no
     * notion of a branded form. A form is what a *dose* was taken as, which is the med form's and
     * the logger's business.
     */
    fun identityFacets(nameOrAlias: String): SubstanceIdentityIndex.Facets =
        index.facets(nameOrAlias)

    /**
     * The curated receptor-affinity rows for a substance: target, action, and whichever of Ki / EC50 /
     * IC50 its source measured, with the assay species and the citation.
     *
     * Distinct from `Substance.mechanismOfAction.bindings`, which is the *summary* — a target, an action
     * and a coarse affinity tier. These are the measurements, and they had no reader: the only thing
     * that asked was `pharmacologyParameters`, which folds them into the engine's modelling inputs.
     */
    fun bindingRows(nameOrAlias: String): List<BindingHit> {
        val id = index.resolve(nameOrAlias) ?: return emptyList()
        return reader.bindingRows(id)
    }

    /**
     * How a substance is cleared: each enzyme's share, and what becomes of the parent.
     *
     * Read for the engine's metabolite model and by nothing else, so the one table that answers "what
     * does my body turn this into" had no screen.
     */
    fun metabolismRows(nameOrAlias: String): List<MetabolismHit> {
        val id = index.resolve(nameOrAlias) ?: return emptyList()
        return reader.metabolismRows(id)
    }

    /**
     * The substance's 2-D structure diagram, or null when the catalogue has none.
     *
     * Read by nothing before this, so `molecule_shapes`' 958 rows were data with no screen.
     */
    fun moleculeShape(nameOrAlias: String): MoleculeShape? {
        val id = index.resolve(nameOrAlias) ?: return null
        return reader.moleculeShape(id)
    }

    /**
     * What the substance's engagement sets off beyond the receptor — the signal-cascade section.
     *
     * Prose per source. Read by nothing before this, so `downstream_signalling`'s 678 rows over 678 substances
     * were data with no screen.
     */
    fun downstreamSignallingRows(nameOrAlias: String): List<DownstreamSignallingHit> {
        val id = index.resolve(nameOrAlias) ?: return emptyList()
        return reader.downstreamSignallingRows(id)
    }

    /**
     * What the substance hits besides its mechanism — the off-target section.
     *
     * Deliberately separate from [bindingRows]: merging them would make the mechanism indistinguishable from
     * everything else the compound touches, which is the one distinction this table exists to draw.
     */
    fun offTargetRows(nameOrAlias: String): List<OffTargetHit> {
        val id = index.resolve(nameOrAlias) ?: return emptyList()
        return reader.offTargetRows(id)
    }

    /**
     * The genes that change what this substance does, and how — the pharmacogenomics and CYP2D6 sections.
     *
     * One read for both sections: the CYP2D6 section is this list filtered to one gene, and reading it twice would
     * let the two disagree.
     */
    fun pharmacogeneticRows(nameOrAlias: String): List<PharmacogeneticHit> {
        val id = index.resolve(nameOrAlias) ?: return emptyList()
        return reader.pharmacogeneticRows(id)
    }

    /** Every receptor target with binding rows, most-populated first — the advanced search's picker. */
    fun availableBindingTargets(): List<SubstanceReader.BindingTarget> =
        reader.availableBindingTargets()

    /**
     * Binding rows across the catalogue, filtered by target, a Ki ceiling and a name fragment.
     *
     * All three nullable and deliberately not defaulted to something: the caller decides what "active"
     * means, and the screen's own rule is that an unfiltered scan is not worth running.
     */
    fun bindingRowsFiltered(
        targetBase: String? = null,
        kiNmAtMost: Double? = null,
        substanceContains: String? = null,
    ): List<BindingHit> = reader.bindingRowsFiltered(targetBase, kiNmAtMost, substanceContains)

    /**
     * One row per substance for the pharmacology table.
     *
     * Joined here rather than in the screen because the join is the work: the preferred PK row comes from a
     * single windowed query, the identity and half-life from the browse metadata the catalogue already
     * holds, and the class title from the class write-ups. The screen gets a list it can sort and filter.
     */
    fun pharmaTableRows(): List<PharmaTableRow> {
        val pkByID = reader.preferredPKRouteRows()
        val classTitles = reader.classContexts().associate { it.slug to it.title }
        val out = mutableListOf<PharmaTableRow>()
        for ((id, substance) in byID) {
            val pk = pkByID[id]
            // The top-level half-life is the fallback when the PK row has none, which is upstream's rule
            // and the reason a substance with a curated half-life but no `pk_routes` row still appears.
            val halfLife = pk?.halfLifeMin ?: substance.halfLifeMinutes
            val row = PharmaTableRow(
                name = substance.name,
                displayName = substance.displayTitle,
                category = substance.category,
                classTitle = substance.classContextSlug?.let { classTitles[it] },
                halfLifeMin = halfLife,
                tmaxMin = pk?.tmaxMin,
                bioavailabilityPct = pk?.bioavailabilityPct,
                cmaxNgPerMl = pk?.cmaxNgPerMl,
                proteinBindingPct = pk?.proteinBindingPct,
                vdLPerKg = pk?.vdLPerKg,
                clearanceMlPerMinPerKg = pk?.clearanceMlPerMinPerKg,
                route = pk?.route?.let { RouteOfAdministration.from(it) },
                sourceSlug = pk?.sourceSlug.orEmpty(),
            )
            // The inclusion gate. A row of dashes is not a fact about a substance, so a compound with no
            // half-life and no PK at all is absent rather than present and empty.
            if (row.hasAnyData) out.add(row)
        }
        return out
    }

    /**
     * The sources this catalogue was built from, in the order it ranks them.
     *
     * The order is the reader's, not the table's: when the user has reordered sources, what this
     * returns is what the queries actually do. A screen that read `default_priority` here would be
     * describing a ranking the app is not using.
     */
    fun sources(): List<SubstanceReader.SourceInfo> = reader.sources()

    /** How many substances the catalogue carries, for the substance-database screen's count. */
    fun count(): Int = reader.substanceCount()

    fun effectGroups(nameOrAlias: String): List<EffectGroup> {
        val id = index.resolve(nameOrAlias) ?: return emptyList()
        return reader.effectGroups(id)
    }

    /**
     * The substance's effects with their own descriptions, as the "All effects" screen lists them.
     *
     * Distinct from [Substance.effects], which is the flat label union: this carries the sentence
     * that says what the effect is, which is the reason to open a page about it.
     */
    fun subjectiveEffects(nameOrAlias: String): List<SubjectiveEffect> {
        val id = index.resolve(nameOrAlias) ?: return emptyList()
        return reader.subjectiveEffects(id)
    }

    /**
     * The substance's stored zero-order elimination parameters before weight
     * scaling, or null when it clears first-order like nearly everything else.
     *
     * Exposed raw rather than scaled because the scale factor is the caller's body
     * weight, which this catalog does not know.
     */
    fun zeroOrderRow(substanceName: String): SubstanceReader.ZeroOrderRow? =
        zeroOrderRows[substanceName.lowercase()]

    private val zeroOrderRows: Map<String, SubstanceReader.ZeroOrderRow> by lazy { reader.zeroOrderRows() }

    /**
     * The PSID family of [name].
     *
     * The identity index resolves the name first — including its stub-demotion
     * rule, so `lorcet` finds Hydrocodone rather than the empty row that shares the
     * alias — and the family is then read off the resolved row.
     */
    override fun substanceUID(name: String): String? {
        val id = index.resolve(name) ?: return null
        return byID[id]?.substanceUID
    }

    override fun esters(parentUID: String): List<EsterRecord> = esters[parentUID].orEmpty()

    /**
     * The authored envelope for an extended-release product, keyed by normalized
     * name — which is why "  Adderall XR " finds it. Upstream normalizes at the
     * lookup rather than at the read.
     */
    override fun productDuration(productName: String): DurationProfile? =
        productDurations[normalizeProduct(productName)]

    // MARK: - Interaction reads

    /*
     * Ported from the `SubstanceStore` caches upstream: `interactionClasses()`,
     * `categoryInteractionClasses()`, `classInteractionRules()`,
     * `enzymeModulators()`, `tagEnzymeInteractions()` and
     * `pkInteractions(forSubstanceName:)`, which the interaction checker reads
     * through the `InteractionData` port this class implements.
     *
     * The three tables below are whole-table reads — 99, 213 and 29 rows — that
     * every checker call consults. They are read once and held, matching the
     * caching `SubstanceStore` does upstream, because rebuilding the class
     * override map on each keystroke of the explorer would be a query per
     * character typed.
     *
     * `interaction_rules` and `substance_interaction_classes` are read without an
     * enabled-source filter and `category_interaction_classes` has no source
     * column at all; see the reader's own notes for why.
     */

    private val classRuleRows: List<ClassInteractionRule> by lazy { reader.classInteractionRules() }
    private val classOverrides: Map<String, List<DrugClass>> by lazy { reader.substanceInteractionClasses() }
    private val categoryClasses: Map<String, DrugClass> by lazy { reader.categoryInteractionClasses() }
    private val modulatorRows: List<EnzymeModulator> by lazy { reader.enzymeModulatorRows() }

    private val tagEnzymeIndex: Map<String, Map<String, TagEnzymeInteraction>> by lazy {
        val out = mutableMapOf<String, MutableMap<String, TagEnzymeInteraction>>()
        for (row in reader.tagEnzymeRows()) {
            // A canonical name is unique, but the table is keyed on ids and a
            // rebuild could in principle emit the same pair twice; last wins, as
            // upstream's subscript assignment does.
            out.getOrPut(row.perpetratorName.lowercase()) { mutableMapOf() }[row.victimName.lowercase()] = row
        }
        out
    }

    /** Every `interaction_rules` row, unfiltered — the checker's whole severity ladder. */
    override fun classRules(): List<ClassInteractionRule> = classRuleRows

    /** The same rows under their read-layer name, for a caller resolving the tables directly. */
    fun classInteractionRules(): List<ClassInteractionRule> = classRuleRows

    /** `substance_interaction_classes` as lowercased name → classes, alias-expanded. */
    override fun interactionClasses(): Map<String, List<DrugClass>> = classOverrides

    /** The same map under its read-layer name. */
    fun substanceInteractionClasses(): Map<String, List<DrugClass>> = classOverrides

    /** `category_interaction_classes` as the category's raw value → its fallback class. */
    override fun categoryInteractionClasses(): Map<String, DrugClass> = categoryClasses

    /** The whole `enzyme_modulators` table, in curated rank order, matcher names attached. */
    override fun enzymeModulators(): List<EnzymeModulator> = modulatorRows

    /**
     * `tag_enzyme_interactions`, indexed `perpetrator → victim → row`, both
     * lowercased.
     *
     * The index is keyed on the pair while the table's own primary key adds
     * `enzyme`, so a pair that interacts on two enzymes keeps only one row — four
     * pairs in the shipped table, all of them Fluoxetine's (25C-NBOMe, 25I-NBOMe,
     * Galantamine, Hydrocodone). Upstream builds the same index the same way: the
     * checker asks one question per pair, so the last enzyme read wins rather than
     * both firing. [SubstanceReader.tagEnzymeRows] is where all 285 are still
     * available.
     */
    override fun tagEnzymeInteractions(): Map<String, Map<String, TagEnzymeInteraction>> = tagEnzymeIndex

    /**
     * The `drug_interactions_pk` rows naming [nameOrAlias], highest-evidence
     * source first, or an empty list for a name the catalog does not carry.
     *
     * Unlike the three tables above this one is **not** a whole-table read: it
     * has 205 rows spread over 123 substances, and only ever two are wanted at
     * once. 15 rows name a counterpart the catalog has no substance for; the
     * caller resolves the rest through [lookup].
     */
    override fun pharmacokineticInteractions(substanceName: String): List<PKInteractionHit> {
        val id = index.resolve(substanceName) ?: return emptyList()
        return reader.pkInteractionRows(id)
    }

    /**
     * The enzymes carrying a major share of [substanceName]'s clearance.
     *
     * Routed through the identity index for the same reason every other read is:
     * a dose logged as a brand name must find the compound's metabolism rows.
     * Empty when the name does not resolve, which leaves the checker's curated
     * layer silent for that pair rather than guessing an enzyme.
     */
    // MARK: - Whole-table reference reads

    /**
     * The three reference tables that are shown **whole** rather than resolved per
     * substance: the class write-ups, the opioid MME factors, and the diazepam
     * equivalences.
     *
     * They live here, as thin delegations, so that the app has one handle to the
     * 18 MB catalogue rather than two. They were on a second reader opened over the
     * same installed file — a workaround for this class not exposing them, which
     * cost a second SQLite connection and a second copy of the source-priority
     * ordering, both of which had to agree with this one. They did; nothing made
     * them.
     */
    fun classContexts(): List<SubstanceReader.ClassContext> = reader.classContexts()

    /** One class write-up, resolved by slug or by display name. */
    fun classContext(nameOrSlug: String): SubstanceReader.ClassContext? = reader.classContext(nameOrSlug)

    /** Every opioid's morphine-equivalent factor, including the rows that decline to convert. */
    fun opioidMmeTable(): List<SubstanceReader.OpioidMmeRowEntry> = reader.opioidMmeTable()

    /** Every benzodiazepine's diazepam equivalence, including the uncited ones. */
    fun diazepamEquivalents(): List<SubstanceReader.BenzoEquivalentEntry> = reader.diazepamEquivalents()

    override fun majorEnzymes(substanceName: String): Set<Enzyme> {
        val id = index.resolve(substanceName) ?: return emptySet()
        return MetabolicModulation.majorEnzymes(reader.metabolismRows(id))
    }

    // MARK: - InteractionData (the port the checker reads through)

    /**
     * The checker over this catalog and this same read layer.
     *
     * One instance per catalog, held lazily, because the checker memoizes the
     * whole rule table and the class resolution on first use. A caller that needs
     * a second, independent checker (a test wanting a cold cache) constructs one
     * itself from this object, which satisfies [InteractionData].
     */
    val interactionChecker: InteractionChecker by lazy { InteractionChecker(this, this) }

    // MARK: - Assembly

    /**
     * Build every substance in one pass.
     *
     * Five whole-catalog reads — shells, aliases, routes, categories, half-lives —
     * rather than the ~12-per-substance the detail path makes. The routes read is
     * the expensive one: it folds each route's dose ladders, salt and isomer
     * variants and duration phases together, which is exactly what the timeline
     * needs and what the browse list would otherwise redo per row.
     *
     * `customUnitAliases` is left empty: it is a user-data overlay, and it arrives
     * with the Room layer rather than from this read-only catalog. A substance
     * built here is the catalog's own answer, unmodified.
     */
    private fun build(): Map<Long, Substance> {
        val shells = reader.allShells()
        val ids = shells.mapTo(mutableSetOf()) { it.id }
        val routes = reader.routes(ids)
        val categories = reader.categories(ids)
        val halfLives = reader.halfLives(ids)
        val aliases = reader.displayAliases(ids)
        // The presentation fields. Absent here until v0.5.4, which meant every
        // substance carried the entity defaults: `popularity` 0.0 for all of them (so
        // `substancesIn`'s "popularity first" sorted nothing and every category list was
        // alphabetical), `displayClass` RECREATIONAL for all of them (so
        // `surfacesInBrowse` could never exclude the non-recreational compounds, which
        // then appeared in the browse grid), and no extra browse homes at all.
        val browse = reader.browseInfo()

        return shells.associate { shell ->
            // Sorted into the route enum's own order, so `defaultRoute` is the
            // most-common route — oral first, then sublingual, insufflation, … —
            // rather than whichever route the read happened to emit first. Without
            // this a substance like Diazepam would default to IV instead of oral.
            val substanceRoutes = routes[shell.id].orEmpty().sortedBy { it.route.ordinal }
            val info = browse[shell.id]
            // Titles are stamped here rather than looked up at every render — the
            // deliberate divergence `Substance.localizedName` documents. The two are
            // resolved in the order `displayTitle` consults them, so the field
            // values are what that ladder would have produced anyway.
            shell.id to titled(
                Substance(
                    name = shell.name,
                    displayName = shell.displayName,
                    aliases = aliases[shell.id].orEmpty(),
                    category = categories[shell.id] ?: SubstanceCategory.OTHER,
                    // Upstream falls back through the tag list to pick "inhalation"
                    // before "oral"; without tags read here, the unspecified route
                    // is oral, which is what every ladderless substance wants.
                    defaultRoute = substanceRoutes.firstOrNull()?.route ?: RouteOfAdministration.ORAL,
                    routes = substanceRoutes,
                    halfLifeMinutes = halfLives[shell.id],
                    substanceUID = shell.substanceUID,
                    popularity = info?.popularity ?: 0.0,
                    displayClass = info?.displayClass ?: CompoundDisplayClass.RECREATIONAL,
                    isStub = info?.isStub ?: false,
                    durationImplausible = info?.durationImplausible ?: false,
                    extraBrowseCategories = info?.extraBrowseCategories.orEmpty(),
                    // From the browse metadata, not the shell: `SubstanceShell` is the timeline's
                    // four-field input and deliberately carries no browse columns.
                    classContextSlug = info?.classContextSlug,
                ),
            )
        }
    }

    private fun normalizeProduct(name: String): String = name.trim().lowercase()

    companion object {
        /**
         * Open a catalog over [db].
         *
         * [order] is the user's enabled-source order, highest priority first;
         * [region] is an ISO 3166-1 alpha-2 code, or null for the US default.
         */
        fun open(
            db: SubstanceDb,
            order: List<String>,
            language: ContentLanguage,
            usesEnglishNames: Boolean = false,
            region: String? = null,
        ): DbSubstanceCatalog {
            val reader = SubstanceReader(db, order, language)
            return DbSubstanceCatalog(
                reader = reader,
                index = SubstanceIdentityIndex.build(db),
                language = language,
                usesEnglishNames = usesEnglishNames,
                region = region,
            )
        }
    }
}
