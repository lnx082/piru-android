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
import glass.kagerou.piru.model.SubstanceCategory

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
     */
    fun substancesIn(category: SubstanceCategory): List<Substance> =
        byID.values
            .filter { it.category == category || category in it.extraBrowseCategories }
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

        return shells.associate { shell ->
            // Sorted into the route enum's own order, so `defaultRoute` is the
            // most-common route — oral first, then sublingual, insufflation, … —
            // rather than whichever route the read happened to emit first. Without
            // this a substance like Diazepam would default to IV instead of oral.
            val substanceRoutes = routes[shell.id].orEmpty().sortedBy { it.route.ordinal }
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
