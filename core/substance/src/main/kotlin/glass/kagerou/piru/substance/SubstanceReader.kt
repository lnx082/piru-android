package glass.kagerou.piru.substance

import glass.kagerou.piru.engine.BindingHit
import glass.kagerou.piru.engine.ClassInteractionRule
import glass.kagerou.piru.engine.DrugClass
import glass.kagerou.piru.engine.Enzyme
import glass.kagerou.piru.engine.EnzymeModulator
import glass.kagerou.piru.engine.EsterRecord
import glass.kagerou.piru.engine.MetabolismHit
import glass.kagerou.piru.engine.MetaboliteMechanism
import glass.kagerou.piru.engine.MetabolitePotencyBasis
import glass.kagerou.piru.engine.ModulationDirection
import glass.kagerou.piru.engine.ModulationStrength
import glass.kagerou.piru.engine.ModulatorOrigin
import glass.kagerou.piru.engine.OpioidConvertibility
import glass.kagerou.piru.engine.OpioidMmeRow
import glass.kagerou.piru.engine.PKInteractionHit
import glass.kagerou.piru.engine.PKReference
import glass.kagerou.piru.engine.PKRouteHit
import glass.kagerou.piru.engine.PharmacologyAssembly
import glass.kagerou.piru.engine.ReceptorClasses
import glass.kagerou.piru.engine.ReferenceDoseRow
import glass.kagerou.piru.engine.TagEnzymeInteraction
import glass.kagerou.piru.engine.TherapeuticRangeHit
import glass.kagerou.piru.model.BindingAction
import glass.kagerou.piru.model.ByVolumeDosing
import glass.kagerou.piru.model.BindingAffinity
import glass.kagerou.piru.model.Citation
import glass.kagerou.piru.model.CompoundDisplayClass
import glass.kagerou.piru.model.ConfidenceTier
import glass.kagerou.piru.model.DiazepamEquivalent
import glass.kagerou.piru.model.DoseContext
import glass.kagerou.piru.model.DrinkPreset
import glass.kagerou.piru.model.DoseRange
import glass.kagerou.piru.model.DoseVariant
import glass.kagerou.piru.model.DurationOfAction
import glass.kagerou.piru.model.DurationProfile
import glass.kagerou.piru.model.DurationRange
import glass.kagerou.piru.model.EffectGroup
import glass.kagerou.piru.model.MechanismOfAction
import glass.kagerou.piru.model.PeptideProfile
import glass.kagerou.piru.model.Physicochemical
import glass.kagerou.piru.model.ProtocolDosing
import glass.kagerou.piru.model.ReceptorBinding
import glass.kagerou.piru.model.ReceptorTargetKey
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.StorageRequirement
import glass.kagerou.piru.model.SuppliedForm
import glass.kagerou.piru.model.SubjectiveEffect
import glass.kagerou.piru.model.Substance
import glass.kagerou.piru.model.SubstanceCategory
import glass.kagerou.piru.model.SubstanceOverview
import glass.kagerou.piru.model.SubstanceRoute
import glass.kagerou.piru.model.TitrationStep
import glass.kagerou.piru.model.ToleranceInfo
import glass.kagerou.piru.model.WaterHeatGuidance
import kotlinx.serialization.json.Json
import glass.kagerou.piru.engine.DownstreamSignallingHit
import glass.kagerou.piru.engine.OffTargetHit
import glass.kagerou.piru.engine.PharmacogeneticHit
import glass.kagerou.piru.engine.MoleculeAtom
import glass.kagerou.piru.engine.MoleculeBond
import glass.kagerou.piru.engine.MoleculeShape
import glass.kagerou.piru.engine.SpectrumEffect
import glass.kagerou.piru.engine.SpectrumLevel
import glass.kagerou.piru.engine.ConcentrationEffectHit
import glass.kagerou.piru.engine.NeuroimagingHit

/**
 * Source-priority-aware reads over the bundled catalog.
 *
 * Ported from `SubstanceReadModel` in the iOS target. One value snapshot of the
 * inputs every resolver depends on — the connection, the user's enabled-source
 * order, and the content language — with the resolvers as methods. Identity
 * indexes and caches stay outside, as they do upstream.
 *
 * ## Enabled-source policy: the two kinds of content fail differently
 * This is the rule that matters most here, and it is deliberate rather than
 * incidental:
 *
 * - **Prose fails open.** Text resolvers ([textRow]) run a strict
 *   enabled-source pass, then retry *without* the enabled-source filter. Prose is
 *   inert reference content whose provenance badge names the source; a blank
 *   overview is strictly worse. The retry also covers the launch window before
 *   source preferences have loaded, when the enabled list is empty and matches
 *   nothing.
 * - **Structured values fail closed.** Dose ladders, routes, category, half-life
 *   and the equivalence tables resolve **only** from enabled sources. They feed
 *   calculations and safety-relevant displays, so a value from a source the user
 *   explicitly disabled must never leak in. Disabling every source therefore
 *   legitimately blanks doses and durations while descriptions remain.
 *
 * A new resolver picks its side by that test — does the value drive behaviour, or
 * is it read as text? — and not by copying whichever query is nearest.
 *
 * ## A deliberate divergence: `ORDER BY …, id`
 * The windowed queries end their ordering with the row's `id`, which upstream's
 * do not. That is not cosmetic.
 *
 * Both tables declare `UNIQUE (substance_id, route, source_id, salt_form, isomer)`
 * and the build relies on it to keep one row per source. It does not: SQLite
 * treats every NULL in a unique index as distinct from every other, and `salt_form`
 * and `isomer` are NULL for the overwhelming majority of rows. Duplicates
 * therefore exist — measured on the shipped catalog, **39 tied groups in
 * `dose_ranges` and 73 in `durations`**, of which **49 phases have the *winning*
 * source itself tied**. `4-Fluorophenylpiperazine` insufflation carries a
 * drug.community ladder of both 20–35 and 20–40 mg; `Amphetamine` insufflation
 * afterglow has two tripsit rows, 60–1440 and 60–720.
 *
 * With the order ending at the priority, `ROW_NUMBER()` breaks those ties by scan
 * order — which is unspecified, and which this port has already changed once by
 * switching to the bundled SQLite driver. The visible effect is a duration that
 * differs between two runs, or between iOS and Android, for the same substance.
 *
 * `id` is a stable, already-present column, and it is appended **after** the
 * priority, so it cannot change which *source* wins — only which of several rows
 * from that same source is used. Any of the tied rows is equally correct by the
 * stated ranking; this just makes the choice reproducible. Upstream's arbitrary
 * pick is not something a port can match, because it is not pinned to anything.
 */
class SubstanceReader(
    private val db: SubstanceDb,
    val order: List<String>,
    val language: ContentLanguage,
) {

    val priority: SourcePriority = SourcePriority(order)

    /** The enabled slugs as a SQL list, or `''` when the order has not loaded. */
    val enabledSourceListSQL: String get() = priority.enabledSourceListSQL

    private val priorityCaseSQL: String get() = priority.priorityCaseSQL

    /**
     * A source the catalogue ships data from.
     *
     * `displayName` is the publisher's name ("PsychonautWiki", "PDSP Ki database") and `slug` is the
     * stable identifier every query keys on. Both are carried because a screen needs the first to be
     * readable and the second to refer to the same row the SQL does.
     */
    data class SourceInfo(
        val slug: String,
        val displayName: String,
        val defaultPriority: Int,
        val defaultEnabled: Boolean,
    )

    /**
     * Every source, in the order this reader ranks them.
     *
     * Ordered by [order] rather than by `default_priority`: the whole point of the ranking is that the
     * user can change it, and a screen that listed the shipped order would be describing a ranking the
     * queries do not use. A slug in [order] that the table does not carry is skipped, and a slug in
     * the table that [order] does not name is appended — the same composition the catalogue does, so
     * this list and the SQL agree.
     */
    fun sources(): List<SourceInfo> {
        val rows = db.query(
            "SELECT slug, display_name, default_priority, default_enabled FROM sources",
        )
        val bySlug = rows.mapNotNull { row ->
            val slug = row.string("slug") ?: return@mapNotNull null
            slug to SourceInfo(
                slug = slug,
                displayName = row.string("display_name") ?: slug,
                defaultPriority = (row.long("default_priority") ?: 0L).toInt(),
                defaultEnabled = (row.long("default_enabled") ?: 1L) != 0L,
            )
        }.toMap()

        val ranked = order.mapNotNull { bySlug[it] }
        val unranked = bySlug.values.filterNot { it.slug in order }
            .sortedWith(compareBy({ it.defaultPriority }, { it.slug }))
        return ranked + unranked
    }

    /**
     * How many substances the catalogue carries.
     *
     * Counted rather than held: the screen that shows it is opened rarely, and a cached count would be
     * one more thing to keep in step with the database file.
     */
    /**
     * The ids of every substance carrying [tag].
     *
     * Empty for a tag nothing carries, which is not an error: a tag is a string a route can hold, and
     * a stale link should answer nothing rather than throw. Hidden rows are excluded here so no
     * caller can forget to.
     */
    fun substanceIDsForTag(tag: String): List<Long> = db.query(
        """
        SELECT DISTINCT substance_id AS id
        FROM tags
        WHERE LOWER(tag) = LOWER(?)
        AND COALESCE(hidden, 0) = 0
        """,
        listOf(tag.trim()),
    ).mapNotNull { it.long("id") }

    fun substanceCount(): Int =
        (db.query("SELECT COUNT(*) AS n FROM substances").firstOrNull()?.long("n") ?: 0L).toInt()

    // MARK: - Structured reads (fail closed)

    /** One dose ladder and the source it came from. */
    data class ResolvedDose(
        val substanceID: Long,
        val route: String,
        val saltForm: String?,
        val isomer: String?,
        val isomerDisplayName: String?,
        val saltRank: Int?,
        val elementalFraction: Double?,
        val unit: String,
        val doses: DoseRange,
        val doseContext: String,
        val sourceSlug: String,
    )

    /** The identity a dose ladder is keyed by — a salt and an isomer are different ladders. */
    data class DoseKey(
        val substanceID: Long,
        val route: String,
        val saltForm: String?,
        val isomer: String?,
    )

    /**
     * The highest-priority dose ladder per (substance, route, salt, isomer).
     *
     * One windowed query for any number of substances, partitioned by the four
     * identity columns — the shape upstream's `resolveRoutes` uses, which
     * collapsed the detail path's N+1 into a constant number of statements.
     *
     * Fail-closed: a ladder whose only sources are disabled is simply absent from
     * the result, and a caller that then draws no curve is correct.
     *
     * `doseContextLast` is on, so a therapeutic ladder sorts behind every
     * recreational or unknown one *before* source rank is consulted. A
     * therapeutic figure sitting next to a dose somebody logged reads as a
     * recommendation to take that much, and it is the recreational ladder their
     * number means anything against.
     */
    fun doseLadders(substanceIDs: Set<Long>): Map<DoseKey, ResolvedDose> {
        if (substanceIDs.isEmpty() || order.isEmpty()) return emptyMap()

        val rank = priority.fieldRankSQL(field = "doses", alias = "d", doseContextLast = true)
        // Row ids from our own Int64 column, never user input.
        val idList = substanceIDs.joinToString(", ")

        return db.query(
            """
            SELECT substance_id, route, salt_form, isomer, isomer_display_name, salt_rank,
                   elemental_fraction, unit, threshold, dose_context,
                   light_lower, light_upper, common_lower, common_upper,
                   strong_lower, strong_upper, heavy, source_slug
              FROM (
                SELECT d.*, src.slug AS source_slug, ROW_NUMBER() OVER (
                    PARTITION BY d.substance_id, d.route, d.salt_form, d.isomer
                    ORDER BY ${rank.orderBy}, d.id) AS rn
                  FROM dose_ranges d
                  JOIN sources src ON src.id = d.source_id
                  ${rank.join}
                 WHERE d.substance_id IN ($idList)
                   AND src.slug IN ($enabledSourceListSQL)
            ) WHERE rn = 1
            """,
        ).mapNotNull { row ->
            val id = row.long("substance_id") ?: return@mapNotNull null
            val route = row.string("route") ?: return@mapNotNull null
            val key = DoseKey(
                substanceID = id,
                route = route,
                saltForm = row.string("salt_form"),
                isomer = row.string("isomer"),
            )
            key to ResolvedDose(
                substanceID = id,
                route = route,
                saltForm = key.saltForm,
                isomer = key.isomer,
                isomerDisplayName = row.string("isomer_display_name"),
                saltRank = row.long("salt_rank")?.toInt(),
                elementalFraction = row.double("elemental_fraction"),
                unit = row.string("unit") ?: "mg",
                doses = DoseRange(
                    threshold = row.double("threshold"),
                    light = range(row.double("light_lower"), row.double("light_upper")),
                    common = range(row.double("common_lower"), row.double("common_upper")),
                    strong = range(row.double("strong_lower"), row.double("strong_upper")),
                    heavy = row.double("heavy"),
                ),
                doseContext = row.string("dose_context") ?: "unknown",
                sourceSlug = row.string("source_slug") ?: "",
            )
        }.toMap()
    }

    /**
     * The category the app would show, or null when no enabled source carries one.
     *
     * Fail-closed. `Other` is ranked last unless it comes from the curated source,
     * so a real classification beats a source's catch-all.
     */
    fun category(substanceID: Long): String? = db.queryOne(
        """
        SELECT c.category AS category
          FROM categories c
          JOIN sources src ON src.id = c.source_id
         WHERE c.substance_id = ?
           AND src.slug IN ($enabledSourceListSQL)
         ORDER BY (c.category = 'Other' AND src.slug != 'piru-curated') ASC,
                  $priorityCaseSQL ASC
         LIMIT 1
        """,
        listOf(substanceID),
    )?.string("category")

    /** The union of tags across enabled sources, hiding the ones the engine consumes. */
    fun tags(substanceID: Long): List<String> = db.query(
        """
        SELECT DISTINCT tag AS tag
          FROM tags t
          JOIN sources src ON src.id = t.source_id
         WHERE t.substance_id = ?
           AND src.slug IN ($enabledSourceListSQL)
           AND t.hidden = 0
         ORDER BY tag
        """,
        listOf(substanceID),
    ).mapNotNull { it.string("tag") }

    // MARK: - Prose reads (fail open)

    /** A resolved prose row, with the provenance a caller badges it with. */
    data class TextRow(val row: Row, val sourceSlug: String, val language: String, val machineTranslated: Boolean)

    /**
     * The best prose row for one field, resolved locale-first.
     *
     * [table] and [selecting] are fixed internal literals — no injection surface.
     *
     * [fieldPriority] names this table's field in `source_field_priority`; the
     * descriptions resolver is the one caller, because a dose.wiki summary on an
     * expert-reviewed article was written for that article while PsychonautWiki's
     * is a wiki lead copied whole.
     *
     * **Fails open.** The primary pass honours the enabled-source filter; the
     * retry drops only that filter, keeping the language filter. Dropping the
     * language filter too would let an English reader fall through to raw Chinese
     * prose whenever a compound had a Chinese row but no English one — and a
     * Chinese blob is worse than a blank that the bundled English template then
     * fills. The ordering still floats the preferred language and the user's
     * source priority.
     */
    fun textRow(
        table: String,
        selecting: String,
        substanceID: Long,
        fieldPriority: String? = null,
    ): TextRow? {
        val lang = language.clauses("t.language")
        val rank = priority.fieldRankSQL(field = fieldPriority, alias = "t")
        val columns =
            "$selecting, t.machine_translated AS machine_translated, " +
                "src.slug AS source_slug, t.language AS row_language"

        fun read(filterEnabled: Boolean): TextRow? {
            val enabledFilter =
                if (filterEnabled) "AND src.slug IN ($enabledSourceListSQL)" else ""
            val row = db.queryOne(
                """
                SELECT $columns
                  FROM $table t
                  JOIN sources src ON src.id = t.source_id
                  ${rank.join}
                 WHERE t.substance_id = ?
                   $enabledFilter
                   ${lang.whereAnd}
                 ORDER BY ${lang.orderPrefix}${rank.orderBy}
                 LIMIT 1
                """,
                listOf(substanceID),
            ) ?: return null
            return TextRow(
                row = row,
                sourceSlug = row.string("source_slug") ?: "",
                language = row.string("row_language") ?: "",
                machineTranslated = (row.long("machine_translated") ?: 0L) != 0L,
            )
        }

        return read(filterEnabled = true) ?: read(filterEnabled = false)
    }

    /** A closed range from two bounds, or null when either is missing or the pair is inverted. */
    private fun range(lower: Double?, upper: Double?): ClosedRange<Double>? =
        if (lower == null || upper == null || lower > upper) null else lower..upper

    private companion object {
        /**
         * Decodes the curated JSON blobs (`titration_json`) and the compact
         * columns. Unknown keys are ignored, as on the iOS side, so a blob
         * written by a newer build does not fail the whole resolve.
         */
    }

    // MARK: - Durations (fail closed)

    /**
     * The best duration per (substance, route, salt, isomer) and **phase**.
     *
     * The partition includes `phase` on purpose: different sources can supply
     * different phases of the same profile — the curated layer carries
     * onset/peak/offset while PsychonautWiki fills in come-up and afterglow — and
     * ranking per phase is what lets them combine. Ranking per route instead
     * would take all five phases from whichever source won once.
     */
    fun durations(substanceIDs: Set<Long>): Map<DoseKey, DurationProfile> {
        if (substanceIDs.isEmpty() || order.isEmpty()) return emptyMap()

        val rank = priority.fieldRankSQL(field = "durations", alias = "du")
        val idList = substanceIDs.joinToString(", ")

        val phasesByKey = mutableMapOf<DoseKey, MutableMap<String, DurationRange>>()
        for (row in db.query(
            """
            SELECT substance_id, route, salt_form, isomer, phase, min_minutes, max_minutes
              FROM (
                SELECT du.substance_id, du.route, du.salt_form, du.isomer, du.phase,
                       du.min_minutes, du.max_minutes,
                       ROW_NUMBER() OVER (
                           PARTITION BY du.substance_id, du.route, du.phase, du.salt_form, du.isomer
                           ORDER BY ${rank.orderBy}, du.id) AS rn
                  FROM durations du
                  JOIN sources src ON src.id = du.source_id
                  ${rank.join}
                 WHERE du.substance_id IN ($idList)
                   AND src.slug IN ($enabledSourceListSQL)
            ) WHERE rn = 1
            """,
        )) {
            val id = row.long("substance_id") ?: continue
            val route = row.string("route") ?: continue
            val phase = row.string("phase") ?: continue
            val min = row.double("min_minutes") ?: continue
            val max = row.double("max_minutes") ?: continue
            val key = DoseKey(id, route, row.string("salt_form"), row.string("isomer"))
            phasesByKey.getOrPut(key) { mutableMapOf() }[phase] = DurationRange(min, max)
        }

        return phasesByKey.mapValues { (_, phases) ->
            DurationProfile(
                onset = phases["onset"],
                comeup = phases["comeup"],
                peak = phases["peak"],
                offset = phases["offset"],
                afterglow = phases["afterglow"],
                total = phases["total"],
            )
        }
    }

    // MARK: - Batch identity, naming and catalog indexes

    /**
     * The identity and titling columns for a set of substances.
     *
     * Deliberately without a source filter: a substance row has no `source_id` —
     * it is the spine every sourced row hangs off — so it is present whether or
     * not any source is enabled, exactly as upstream's batch read has it.
     */
    data class SubstanceShell(
        val id: Long,
        val name: String,
        val displayName: String?,
        val substanceUID: String?,
    )

    /**
     * The columns the browse list needs and the shell does not carry.
     *
     * Read in one query rather than per substance, because every one of these is used
     * by `substancesIn`/`categorySummary` for *all* substances on every browse render.
     *
     * They exist separately from [SubstanceShell] because the shell is the timeline's
     * input — it is built on every curve rebuild and needs four fields, not nine — and
     * folding browse metadata into it would make the hot path read columns it never
     * looks at.
     */
    data class BrowseInfo(
        /** The upstream browse sort. Zero for the long tail, which sorts last. */
        val popularity: Double,
        val displayClass: CompoundDisplayClass,
        val isStub: Boolean,
        /**
         * Whether the substance's own duration data is known to be wrong.
         *
         * The OTC-only extra gate on the duration card consults this; see
         * `CompoundDisplayClass.showsDuration`.
         */
        val durationImplausible: Boolean,
        /** Curated extra browse homes beyond the substance's primary category. */
        val extraBrowseCategories: List<SubstanceCategory>,
        /**
         * The drug-class write-up this substance belongs to, or null.
         *
         * The slug rather than the title: it is what a route carries and it cannot be mistranslated.
         * Carried on the browse metadata because it belongs to the same per-substance row set as the
         * rest of it — the substance page needs it on first paint to decide whether the class name is
         * tappable, which is exactly what this map is for.
         */
        val classContextSlug: String? = null,
    )

    /**
     * Browse metadata for every substance, in two queries.
     *
     * The batch twin of the fields `fullSubstance` stamps one row at a time. Without it
     * the catalogue's browse path silently used the entity defaults: `popularity` was
     * `0.0` for all 1,689 substances (so "popularity first" was pure alphabetical),
     * `displayClass` was `RECREATIONAL` for all of them (so the documented
     * non-recreational browse exclusion could not be expressed), and
     * `extraBrowseCategories` was always empty.
     */
    fun browseInfo(): Map<Long, BrowseInfo> {
        val scalars = db.query(
            """
            SELECT id, popularity, display_class, is_stub, duration_implausible
              FROM substances
            """,
        ).mapNotNull { row ->
            val id = row.long("id") ?: return@mapNotNull null
            id to BrowseInfo(
                popularity = row.double("popularity") ?: 0.0,
                displayClass = CompoundDisplayClass.fromWire(row.string("display_class")),
                isStub = (row.long("is_stub") ?: 0L) != 0L,
                durationImplausible = (row.long("duration_implausible") ?: 0L) != 0L,
                extraBrowseCategories = emptyList(),
            )
        }.toMap()

        // The class each substance belongs to, read in one statement for the same reason as the
        // extras: the substance page asks about one substance, and the browse list asks about all of
        // them, so a per-substance query would be the wrong shape for half its callers.
        val classSlugs = mutableMapOf<Long, String>()
        for (row in db.query(
            """
            SELECT sc.substance_id AS substance_id, c.slug AS slug
            FROM substance_classes sc
            JOIN class_contexts c ON c.id = sc.class_context_id
            """,
        )) {
            val id = row.long("substance_id") ?: continue
            val slug = row.string("slug") ?: continue
            classSlugs.putIfAbsent(id, slug)
        }

        // The curated extra homes, merged in. A category string this build does not know
        // is skipped rather than mapped to `OTHER`: an unrecognised home is not the same
        // claim as "this belongs in Other", and iOS skips it too.
        val extras = mutableMapOf<Long, MutableList<SubstanceCategory>>()
        for (row in db.query("SELECT substance_id, category FROM browse_extra_categories")) {
            val id = row.long("substance_id") ?: continue
            val raw = row.string("category") ?: continue
            val category = SubstanceCategory.fromWire(raw) ?: continue
            extras.getOrPut(id) { mutableListOf() }.add(category)
        }

        // Both merges in one pass. isEmpty is deliberately not used as an early exit any more:
        // the class slugs are a second map, and returning early on one of them being empty would drop
        // the other. The map is built once either way.
        return scalars.mapValues { (id, info) ->
            var merged = info
            extras[id]?.let { merged = merged.copy(extraBrowseCategories = it.toList()) }
            classSlugs[id]?.let { merged = merged.copy(classContextSlug = it) }
            merged
        }
    }

    /**
     * Every substance's shell, `COLLATE NOCASE` ordered — the same order upstream
     * reads, so the first-wins outcomes that depend on row order (a duplicate
     * casing of one name) are defined rather than incidental.
     */
    fun allShells(): List<SubstanceShell> =
        db.query(
            """
            SELECT id, canonical_name, display_name, substance_uid
              FROM substances
             ORDER BY canonical_name COLLATE NOCASE
            """,
        ).mapNotNull { row ->
            val id = row.long("id") ?: return@mapNotNull null
            val name = row.string("canonical_name") ?: return@mapNotNull null
            SubstanceShell(
                id = id,
                name = name,
                displayName = row.string("display_name"),
                substanceUID = row.string("substance_uid"),
            )
        }

    /**
     * The category the app would show, per substance, in one windowed query.
     *
     * The batch twin of [category], with the same ranking: `Other` sinks below any
     * specific classification unless the curated source is the one saying it, and
     * the enabled-source filter is applied — this feeds dose gating and browse
     * filtering, so a disabled source must not leak a category in.
     */
    fun categories(substanceIDs: Set<Long>): Map<Long, SubstanceCategory> {
        if (substanceIDs.isEmpty() || order.isEmpty()) return emptyMap()
        val idList = substanceIDs.joinToString(", ")
        return db.query(
            """
            SELECT substance_id, category FROM (
                SELECT c.substance_id, c.category,
                       ROW_NUMBER() OVER (PARTITION BY c.substance_id
                                          ORDER BY (c.category = 'Other'
                                                    AND src.slug != 'piru-curated') ASC,
                                                   $priorityCaseSQL ASC) AS rn
                  FROM categories c
                  JOIN sources src ON src.id = c.source_id
                 WHERE c.substance_id IN ($idList)
                   AND src.slug IN ($enabledSourceListSQL)
            ) WHERE rn = 1
            """,
        ).mapNotNull { row ->
            val id = row.long("substance_id") ?: return@mapNotNull null
            val raw = row.string("category") ?: return@mapNotNull null
            val category = SubstanceCategory.fromWire(raw) ?: return@mapNotNull null
            id to category
        }.toMap()
        // Every `category` value the shipped catalog carries is a wire value, so
        // `fromWire` covers it. Upstream falls back to a TripSit-category mapping
        // for a database built by a different pipeline; that mapping is not ported,
        // and a value it would have rescued is dropped here rather than guessed at.
    }

    /**
     * Elimination half-lives in minutes, priority-resolved, one per substance.
     *
     * The batch twin of the half-life read, and the input to every PK calculation
     * the app makes. Fail-closed: a half-life whose only sources are disabled is
     * absent, and the caller then resolves no parameters and draws no body load.
     *
     * The tiebreak is `rowid` rather than `id`, because `half_lives` has no `id`
     * column — upstream orders on the priority alone. `rowid` serves the same
     * purpose as the `, d.id` on the ladders: it is appended *after* the priority,
     * so it cannot change which source wins, only which of several rows from that
     * same source is read. Two sources quoting one value is the ordinary case
     * anyway; this just keeps the choice reproducible rather than depending on
     * filesystem layout.
     */
    fun halfLives(substanceIDs: Set<Long>): Map<Long, Double> {
        if (substanceIDs.isEmpty() || order.isEmpty()) return emptyMap()
        val idList = substanceIDs.joinToString(", ")
        return db.query(
            """
            SELECT substance_id, half_life_minutes FROM (
                SELECT h.substance_id, h.half_life_minutes,
                       ROW_NUMBER() OVER (PARTITION BY h.substance_id
                                          ORDER BY ${priorityCaseSQL} ASC, h.rowid) AS rn
                  FROM half_lives h
                  JOIN sources src ON src.id = h.source_id
                 WHERE h.substance_id IN ($idList)
                   AND src.slug IN ($enabledSourceListSQL)
            ) WHERE rn = 1
            """,
        ).mapNotNull { row ->
            val id = row.long("substance_id") ?: return@mapNotNull null
            val value = row.double("half_life_minutes") ?: return@mapNotNull null
            id to value
        }.toMap()
    }

    /**
     * Display aliases per substance — what a header lists under the title.
     *
     * Ordered brand names first, then curated flagships (`brand_rank` 0, e.g.
     * Ritalin) ahead of auto-derived form brands (rank 1, e.g. Concerta), then
     * alphabetical, so the "Also known as" line leads with the names people know
     * rather than the alphabetically-first synonym.
     *
     * No enabled-source filter, matching upstream: an alias is a search and
     * titling aid rather than a value a calculation reads, and the same name stays
     * findable however the user has ordered their sources.
     */
    fun displayAliases(substanceIDs: Set<Long>): Map<Long, List<String>> {
        if (substanceIDs.isEmpty()) return emptyMap()
        val idList = substanceIDs.joinToString(", ")
        val out = mutableMapOf<Long, MutableList<String>>()
        for (row in db.query(
            """
            SELECT substance_id, alias
              FROM aliases
             WHERE substance_id IN ($idList)
             ORDER BY COALESCE(brand_rank, 9), alias
            """,
        )) {
            val id = row.long("substance_id") ?: continue
            val alias = row.string("alias") ?: continue
            out.getOrPut(id) { mutableListOf() }.add(alias)
        }
        // A duplicate alias row would otherwise list twice in the subtitle. The
        // order is kept — `distinct` keeps the first occurrence, which is the
        // ranked one.
        return out.mapValues { (_, aliases) -> aliases.distinct() }
    }

    /**
     * The whole `localized_names` table: lowercased canonical name → language tag →
     * the name in that language.
     *
     * Read whole rather than per substance, because it is consulted for every title
     * on every list the library renders.
     *
     * A database without the table throws. Upstream degrades instead — it catches,
     * logs, and leaves every title canonical — because it can meet a bundled
     * database predating the table on a device that has not been updated. That
     * cannot happen here: the catalog is an asset inside the APK, versioned with
     * the code that reads it, so a missing table is a build that shipped wrong and
     * should say so rather than quietly title 1,689 substances in the wrong
     * language.
     */
    fun localizedNames(): Map<String, Map<String, String>> {
        val out = mutableMapOf<String, MutableMap<String, String>>()
        for (row in db.query(
            """
            SELECT s.canonical_name AS name, l.lang AS lang, l.name AS localized
              FROM localized_names l
              JOIN substances s ON s.id = l.substance_id
            """,
        )) {
            val name = row.string("name")?.lowercase() ?: continue
            val lang = row.string("lang") ?: continue
            val localized = row.string("localized") ?: continue
            out.getOrPut(name) { mutableMapOf() }[lang] = localized
        }
        return out
    }

    /**
     * The whole `regional_names` table, keyed by lowercased canonical name.
     *
     * A missing table throws, for the reason [localizedNames] sets out.
     */
    fun regionalNames(): Map<String, RegionalSubstanceName.Variant> {
        val out = mutableMapOf<String, RegionalSubstanceName.Variant>()
        for (row in db.query(
            """
            SELECT s.canonical_name AS name, r.base_name, r.alternate_name, r.alternate_regions
              FROM regional_names r
              JOIN substances s ON s.id = r.substance_id
            """,
        )) {
            val name = row.string("name")?.lowercase() ?: continue
            val base = row.string("base_name") ?: continue
            val alternate = row.string("alternate_name") ?: continue
            val regions = row.string("alternate_regions") ?: continue
            out[name] = RegionalSubstanceName.Variant(
                base = base,
                alternate = alternate,
                alternateRegions = RegionalSubstanceName.regions(regions),
            )
        }
        return out
    }

    /**
     * The authored duration envelope of every extended-release product the catalog
     * models, keyed by normalized product name ("concerta", "adderall xr").
     *
     * Read whole: it is consulted for every dose on the timeline, and the table is
     * a dozen rows. A missing table throws, for the reason [localizedNames] sets
     * out — and here the failure would be especially quiet, since an ER product
     * with no envelope simply renders as a marker rather than as an error.
     */
    fun productDurations(): Map<String, DurationProfile> {
        fun range(row: Row, phase: String): DurationRange? {
            val lo = row.double("${phase}_min") ?: return null
            val hi = row.double("${phase}_max") ?: return null
            return DurationRange(lo, hi)
        }
        val out = mutableMapOf<String, DurationProfile>()
        for (row in db.query(
            """
            SELECT product_normalized, onset_min, onset_max, comeup_min, comeup_max,
                   peak_min, peak_max, offset_min, offset_max,
                   afterglow_min, afterglow_max, total_min, total_max
              FROM product_durations
            """,
        )) {
            val key = row.string("product_normalized") ?: continue
            out[key] = DurationProfile(
                onset = range(row, "onset"),
                comeup = range(row, "comeup"),
                peak = range(row, "peak"),
                offset = range(row, "offset"),
                afterglow = range(row, "afterglow"),
                total = range(row, "total"),
            )
        }
        return out
    }

    /**
     * The depot PK index: PSID family → its injectable esters, ester-id ordered.
     *
     * Read whole, like upstream's `esterPKIndex`. A **catalog-only** ester (a null
     * `k1`) is kept rather than dropped: undecylate is real and loggable, it simply
     * carries no validated curve, and the depot heuristic needs to know it is an
     * ester before it can decide to fall back.
     */
    fun estersByParentUID(): Map<String, List<EsterRecord>> {
        val out = mutableMapOf<String, MutableList<EsterRecord>>()
        for (row in db.query(
            "SELECT ester_id, parent_uid, ester_label, k1 FROM ester_pk ORDER BY ester_id",
        )) {
            val uid = row.string("parent_uid") ?: continue
            val label = row.string("ester_label") ?: continue
            out.getOrPut(uid) { mutableListOf() }.add(
                EsterRecord(label = label, terminalRatePerDay = row.double("k1")),
            )
        }
        return out
    }

    /**
     * The whole SubFxOnEx descriptor vocabulary — every `subjective_effect_concepts`
     * row, id keyed by the caller.
     *
     * Read whole and unfiltered: there is no source column to rank and no language
     * to bridge — the table is the fixed English vocabulary the reports spell out
     * ("Vocabulary: SubFxOnEx."), not reference prose. A missing table throws, for
     * the reason [localizedNames] sets out.
     */
    fun subjectiveEffectConcepts(): List<SubjectiveEffectConcept> =
        db.query(
            "SELECT id, name, domain FROM subjective_effect_concepts ORDER BY position",
        ).mapNotNull { row ->
            val id = row.string("id") ?: return@mapNotNull null
            val name = row.string("name") ?: return@mapNotNull null
            val domain = row.string("domain") ?: return@mapNotNull null
            SubjectiveEffectConcept(id = id, name = name, domain = domain)
        }

    /**
     * One `ester_pk` row in full — the depot curve's own columns, which the
     * engine's [EsterRecord] deliberately does not carry.
     *
     * @param d the population amplitude, output-unit per mg. Wraps F/Vd.
     * @param k1/k2/k3 the three rate constants, per day.
     * @param modelable false for a catalog-only ester (undecylate, propionate):
     *   real and loggable, but shipping no validated curve. Every PK column is
     *   null for those rows, so [modelable] is the gate rather than a null check
     *   on any one of them.
     * @param routes the routes the parameters apply to, decoded from the stored
     *   JSON array. Defaults to `["IM"]` when the text does not decode, matching
     *   `SubstanceStore.swift`.
     * @param caution a safety warning to surface when this ester is logged, or
     *   null. Only testosterone undecanoate carries one on the shipped catalog.
     */
    data class EsterPKRow(
        val esterID: String,
        val analyte: String,
        val parent: String,
        val parentUID: String?,
        val label: String,
        val modelable: Boolean,
        val d: Double?,
        val k1: Double?,
        val k2: Double?,
        val k3: Double?,
        val confidence: String,
        val provenance: String,
        val routes: List<String>,
        val caution: String?,
    )

    /**
     * The whole `ester_pk` table, ester-id ordered — eight rows on the shipped
     * catalog.
     *
     * A second read rather than a wider [estersByParentUID], because the two have
     * different consumers with different needs: that one feeds the engine's
     * `EsterRecord`, which the depot *heuristic* reads and which must stay a
     * two-field value the engine can construct in a test, while this one feeds the
     * three-compartment curve — amplitude, all three rates, the confidence tier
     * that sets the uncertainty band, and the caution text a logged undecylate
     * surfaces.
     *
     * Read whole and unfiltered: there is no source column to rank, the table is
     * a curated layer rather than a sourced one, and every row is either on the
     * curve list or in the "logged but not drawn" note.
     */
    fun esterPKRows(): List<EsterPKRow> = db.query(
        """
        SELECT ester_id, analyte, parent, parent_uid, ester_label,
               modelable, d, k1, k2, k3, confidence, provenance, routes, caution
          FROM ester_pk
         ORDER BY ester_id
        """,
    ).mapNotNull { row ->
        val esterID = row.string("ester_id") ?: return@mapNotNull null
        val label = row.string("ester_label") ?: return@mapNotNull null
        EsterPKRow(
            esterID = esterID,
            analyte = row.string("analyte").orEmpty(),
            parent = row.string("parent").orEmpty(),
            parentUID = row.string("parent_uid"),
            label = label,
            modelable = (row.long("modelable") ?: 1L) != 0L,
            d = row.double("d"),
            k1 = row.double("k1"),
            k2 = row.double("k2"),
            k3 = row.double("k3"),
            confidence = row.string("confidence") ?: "none",
            provenance = row.string("provenance").orEmpty(),
            routes = decodeRoutes(row.string("routes")),
            caution = row.string("caution"),
        )
    }

    /**
     * The stored route list, or `["IM"]` when the text does not decode.
     *
     * The fallback is upstream's, and it is the conservative direction: every
     * curated row names at least IM, so an unparseable one still claims the route
     * the ester is actually injected by rather than claiming none.
     */
    private fun decodeRoutes(raw: String?): List<String> {
        if (raw.isNullOrEmpty()) return listOf("IM")
        return runCatching { json.decodeFromString<List<String>>(raw) }.getOrNull()
            ?: listOf("IM")
    }

    // MARK: - Pharmacology reads

    /**
     * The preferred `pk_routes` row for every substance that has one, in one pass.
     *
     * ## The ranking, and why in this order
     * Oral first — upstream's rule, and the one that makes the result deterministic rather than a function
     * of the table's physical layout. Then by how many PK fields the row carries, so a row with a
     * half-life, a Tmax and a bioavailability beats one with a half-life alone. Then by confidence, so
     * among equally complete rows the better-graded one wins. `id` last, to break a total tie
     * reproducibly.
     *
     * A substance with no `pk_routes` row is absent from the result rather than present with nulls: the
     * table's inclusion gate is "has any PK signal at all", and a row of dashes is not a fact about a
     * substance.
     */
    fun preferredPKRouteRows(): Map<Long, PKRouteHit> {
        val rows = db.query(
            """
            SELECT * FROM (
                SELECT p.id AS id, p.substance_id AS substance_id, p.route AS route,
                       p.bioavailability_pct AS bioavailability_pct,
                       p.cmax_ng_per_ml AS cmax_ng_per_ml, p.tmax_min AS tmax_min,
                       p.half_life_min AS half_life_min, p.vd_l_per_kg AS vd_l_per_kg,
                       p.clearance_ml_per_min_per_kg AS clearance_ml_per_min_per_kg,
                       p.protein_binding_pct AS protein_binding_pct,
                       p.dose_in_study_mg AS dose_in_study_mg, p.subject_n AS subject_n,
                       p.demographics AS demographics, p.species AS species,
                       p.notes AS notes, p.confidence AS confidence,
                       src.slug AS source_slug, c.doi AS doi, c.pmid AS pmid,
                       ROW_NUMBER() OVER (
                           PARTITION BY p.substance_id
                           ORDER BY
                               CASE WHEN LOWER(p.route) = 'oral' THEN 0 ELSE 1 END,
                               (CASE WHEN p.half_life_min IS NOT NULL THEN 1 ELSE 0 END
                              + CASE WHEN p.tmax_min IS NOT NULL THEN 1 ELSE 0 END
                              + CASE WHEN p.bioavailability_pct IS NOT NULL THEN 1 ELSE 0 END
                              + CASE WHEN p.cmax_ng_per_ml IS NOT NULL THEN 1 ELSE 0 END
                              + CASE WHEN p.protein_binding_pct IS NOT NULL THEN 1 ELSE 0 END
                              + CASE WHEN p.vd_l_per_kg IS NOT NULL THEN 1 ELSE 0 END
                              + CASE WHEN p.clearance_ml_per_min_per_kg IS NOT NULL THEN 1 ELSE 0 END
                               ) DESC,
                               p.confidence DESC,
                               p.id ASC
                       ) AS rank
                  FROM pk_routes p
                  JOIN sources src ON src.id = p.source_id
                  LEFT JOIN citations c ON c.id = p.citation_id
            )
            WHERE rank = 1
            """,
        )
        val out = LinkedHashMap<Long, PKRouteHit>()
        for (row in rows) {
            val substanceID = row.long("substance_id") ?: continue
            out[substanceID] = PKRouteHit(
                id = row.long("id") ?: 0L,
                route = row.string("route").orEmpty(),
                bioavailabilityPct = row.double("bioavailability_pct"),
                cmaxNgPerMl = row.double("cmax_ng_per_ml"),
                tmaxMin = row.double("tmax_min"),
                halfLifeMin = row.double("half_life_min"),
                vdLPerKg = row.double("vd_l_per_kg"),
                clearanceMlPerMinPerKg = row.double("clearance_ml_per_min_per_kg"),
                proteinBindingPct = row.double("protein_binding_pct"),
                doseInStudyMg = row.double("dose_in_study_mg"),
                subjectN = row.long("subject_n")?.toInt(),
                demographics = row.string("demographics"),
                species = row.string("species")?.lowercase(),
                sourceSlug = row.string("source_slug").orEmpty(),
                doi = row.string("doi"),
                pmid = row.long("pmid")?.toInt(),
                notes = row.string("notes"),
                confidence = ConfidenceTier.fromGrade(row.string("confidence")),
            )
        }
        return out
    }

    /**
     * The raw `pk_routes` read for one substance, route-ranked so the oral row
     * comes first.
     *
     * The ordering is load-bearing rather than cosmetic: the coherent-row pick in
     * the assembly takes `.first` of several candidates, so "oral first" is what
     * makes a substance's primary PK row deterministic instead of a function of
     * the table's physical layout.
     *
     * No enabled-source filter, matching upstream. These rows are the coherent
     * basis for every occupancy number the app predicts, and the resolver's
     * priority ordering already floats the user's preferred sources; filtering
     * them out entirely would leave a substance whose only PK comes from a
     * deprioritized source with no prediction at all.
     */
    fun pharmacokinetics(substanceID: Long): List<PKRouteHit> =
        db.query(
            """
            SELECT p.id, p.route, p.bioavailability_pct, p.cmax_ng_per_ml, p.tmax_min,
                   p.half_life_min, p.vd_l_per_kg, p.clearance_ml_per_min_per_kg,
                   p.protein_binding_pct, p.dose_in_study_mg, p.subject_n, p.demographics,
                   p.species, p.notes, p.confidence, src.slug AS source_slug, c.doi, c.pmid
              FROM pk_routes p
              JOIN sources src ON src.id = p.source_id
              LEFT JOIN citations c ON c.id = p.citation_id
             WHERE p.substance_id = ?
            """,
            listOf(substanceID),
        ).map { row ->
            PKRouteHit(
                id = row.long("id") ?: 0L,
                route = row.string("route").orEmpty(),
                bioavailabilityPct = row.double("bioavailability_pct"),
                cmaxNgPerMl = row.double("cmax_ng_per_ml"),
                tmaxMin = row.double("tmax_min"),
                halfLifeMin = row.double("half_life_min"),
                vdLPerKg = row.double("vd_l_per_kg"),
                clearanceMlPerMinPerKg = row.double("clearance_ml_per_min_per_kg"),
                proteinBindingPct = row.double("protein_binding_pct"),
                doseInStudyMg = row.double("dose_in_study_mg"),
                subjectN = row.long("subject_n")?.toInt(),
                demographics = row.string("demographics"),
                // Lowercased on read, because the species table and the
                // "is this human" checks are all lowercase comparisons.
                species = row.string("species")?.lowercase(),
                sourceSlug = row.string("source_slug").orEmpty(),
                doi = row.string("doi"),
                pmid = row.long("pmid")?.toInt(),
                notes = row.string("notes"),
                confidence = ConfidenceTier.fromGrade(row.string("confidence")),
            )
        }.sortedBy { RouteOfAdministration.from(it.route).ordinal }

    /**
     * The raw `bindings` read for one substance, tightest Kᵢ first.
     *
     * Deliberately **not** source-filtered, matching upstream: the receptor
     * literature card labels which source supplied each row so a reader can apply
     * their own trust filter, and hiding rows from a deprioritized source would
     * make the card disagree with the published literature it cites.
     */
    fun bindingRows(substanceID: Long): List<BindingHit> =
        db.query(
            """
            SELECT b.id, b.target, b.target_base, b.action, b.ki_nm, b.ec50_nm, b.ic50_nm,
                   b.species, b.confidence, s.canonical_name AS substance_name,
                   src.slug AS source_slug, c.doi, c.pmid
              FROM bindings b
              JOIN substances s ON s.id = b.substance_id
              JOIN sources    src ON src.id = b.source_id
              LEFT JOIN citations c ON c.id = b.citation_id
             WHERE b.substance_id = ?
             ORDER BY b.ki_nm ASC NULLS LAST, b.ec50_nm ASC NULLS LAST
            """,
            listOf(substanceID),
        ).map { row ->
            BindingHit(
                id = row.long("id") ?: 0L,
                substanceName = row.string("substance_name").orEmpty(),
                target = row.string("target").orEmpty(),
                targetBase = row.string("target_base"),
                action = row.string("action").orEmpty(),
                kiNm = row.double("ki_nm"),
                ec50Nm = row.double("ec50_nm"),
                ic50Nm = row.double("ic50_nm"),
                species = row.string("species"),
                sourceSlug = row.string("source_slug").orEmpty(),
                doi = row.string("doi"),
                pmid = row.long("pmid")?.toInt(),
                confidence = ConfidenceTier.fromGrade(row.string("confidence")),
            )
        }

    /**
     * One receptor target and how many substances have a row against it.
     *
     * `targetBase` is the normalized spelling — the pipeline folds `α2δ-1 (recombinant human)`,
     * `α2δ-1` and `alpha2delta-1 (CACNA2D1)` into `alpha-2-delta-1` — so the picker lists one entry per
     * receptor rather than one per assay. The **display** target is the most common raw spelling under
     * that base, because a picker full of normalized slugs reads like a database dump.
     */
    /**
     * One `substance_flags` row.
     *
     * [sourceSlug] and the citation are nullable because the column is: five of the thirteen rows carry no
     * citation, and a flag without one is still a flag — the note is what makes it readable.
     */
    data class SubstanceFlagRow(
        val flag: String,
        val notes: String? = null,
        val sourceSlug: String? = null,
        val doi: String? = null,
        val pmid: Int? = null,
    )

    data class BindingTarget(val target: String, val targetBase: String, val substanceCount: Int)

    /**
     * Every target with at least one binding row, most-substances first.
     *
     * Counted by *distinct substance*, not by row: "how many compounds hit this receptor" is the question
     * the picker is answering, and a receptor with one compound measured forty times is not more
     * populated than one with ten compounds.
     */
    fun availableBindingTargets(): List<BindingTarget> {
        val rows = db.query(
            """
            SELECT COALESCE(NULLIF(b.target_base, ''), b.target) AS base,
                   COUNT(DISTINCT b.substance_id) AS n
              FROM bindings b
             WHERE COALESCE(NULLIF(b.target_base, ''), b.target) IS NOT NULL
             GROUP BY base
             ORDER BY n DESC, base ASC
            """,
        )
        val display = db.query(
            """
            SELECT COALESCE(NULLIF(b.target_base, ''), b.target) AS base, b.target AS target, COUNT(*) AS n
              FROM bindings b
             GROUP BY base, b.target
             ORDER BY n DESC
            """,
        )
        // First row per base wins, and the query is ordered so that is the most common spelling.
        val displayByBase = LinkedHashMap<String, String>()
        for (row in display) {
            val base = row.string("base") ?: continue
            val target = row.string("target") ?: continue
            displayByBase.putIfAbsent(base, target)
        }
        return rows.mapNotNull { row ->
            val base = row.string("base") ?: return@mapNotNull null
            BindingTarget(
                target = displayByBase[base] ?: base,
                targetBase = base,
                substanceCount = (row.long("n") ?: 0L).toInt(),
            )
        }
    }

    /**
     * Binding rows across the whole catalogue, filtered.
     *
     * Every filter is nullable, and **all-null is a valid query** — the unfiltered scan the screen refuses
     * to run on open, because "every measured binding in the catalogue" is 1,462 rows and not an answer to
     * anything. The screen's own guard is what stops that; this returns it if asked.
     *
     * Ordered by Ki then EC50, lowest first, so the tightest binders lead: tightness is what the screen's
     * one number means, and a ceiling filter makes the head of the list the interesting part.
     */
    fun bindingRowsFiltered(
        targetBase: String? = null,
        kiNmAtMost: Double? = null,
        substanceContains: String? = null,
    ): List<BindingHit> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        if (!targetBase.isNullOrBlank()) {
            // Matched on the **normalized** base rather than the raw target: the picker offers one entry
            // per receptor, so selecting it has to reach every spelling that folded into it.
            clauses += "COALESCE(NULLIF(b.target_base, ''), b.target) = ?"
            args += targetBase
        }
        if (kiNmAtMost != null) {
            // A ceiling on Ki only. A row measured by EC50 has no Ki and is excluded rather than treated
            // as zero, because "not measured" is not "binds more tightly than the ceiling".
            clauses += "b.ki_nm IS NOT NULL AND b.ki_nm <= ?"
            args += kiNmAtMost
        }
        if (!substanceContains.isNullOrBlank()) {
            clauses += "s.canonical_name LIKE ?"
            // Escaped so a user typing `%` searches for a percent sign rather than matching everything.
            args += "%" + substanceContains.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        }
        val where = if (clauses.isEmpty()) "" else "WHERE " + clauses.joinToString(" AND ")

        return db.query(
            """
            SELECT b.id, b.target, b.target_base, b.action, b.ki_nm, b.ec50_nm, b.ic50_nm,
                   b.species, b.confidence, s.canonical_name AS substance_name,
                   src.slug AS source_slug, c.doi, c.pmid
              FROM bindings b
              JOIN substances s ON s.id = b.substance_id
              JOIN sources    src ON src.id = b.source_id
              LEFT JOIN citations c ON c.id = b.citation_id
             $where
             ORDER BY b.ki_nm ASC NULLS LAST, b.ec50_nm ASC NULLS LAST, s.canonical_name ASC
             LIMIT 500
            """,
            args,
        ).map { row ->
            BindingHit(
                id = row.long("id") ?: 0L,
                substanceName = row.string("substance_name").orEmpty(),
                target = row.string("target").orEmpty(),
                targetBase = row.string("target_base"),
                action = row.string("action").orEmpty(),
                kiNm = row.double("ki_nm"),
                ec50Nm = row.double("ec50_nm"),
                ic50Nm = row.double("ic50_nm"),
                species = row.string("species"),
                sourceSlug = row.string("source_slug").orEmpty(),
                doi = row.string("doi"),
                pmid = row.long("pmid")?.toInt(),
                confidence = ConfidenceTier.fromGrade(row.string("confidence")),
            )
        }
    }

    /** Therapeutic-range rows, lowest threshold first. */
    fun therapeuticRangeRows(substanceID: Long): List<TherapeuticRangeHit> =
        db.query(
            """
            SELECT e.id, e.effect, e.concentration_unit, e.threshold, e.peak_effect,
                   src.slug AS source_slug, c.doi, c.pmid
              FROM concentration_effects e
              JOIN sources src ON src.id = e.source_id
              LEFT JOIN citations c ON c.id = e.citation_id
             WHERE e.substance_id = ? AND e.kind = 'therapeutic_range' AND e.threshold > 0
             ORDER BY e.threshold ASC
            """,
            listOf(substanceID),
        ).map { row ->
            TherapeuticRangeHit(
                id = row.long("id") ?: 0L,
                effect = row.string("effect").orEmpty(),
                concentrationUnit = row.string("concentration_unit").orEmpty(),
                thresholdValue = row.double("threshold") ?: 0.0,
                peakValue = row.double("peak_effect"),
                sourceSlug = row.string("source_slug").orEmpty(),
                doi = row.string("doi"),
                pmid = row.long("pmid")?.toInt(),
            )
        }

    /**
     * Metabolism rows, largest clearance share first.
     *
     * ## The metabolite's half-life comes from its own record first
     * Where the catalog carries the metabolite as a substance, the half-life is
     * read from **that** substance's `half_lives` — under the same source priority
     * — rather than from the scalar column beside it.
     *
     * Not an optimization, and not to be simplified away: the scalar column holds
     * whatever a single paper reported, on whatever basis it used, and is not
     * comparable with the parent's own half-life. Methamphetamine to amphetamine
     * stores 1242 minutes there, which that row's own note identifies as a
     * *urinary* elimination half-life; set against methamphetamine's *plasma* 606
     * it reads as a two-fold difference where there is none — both are about ten
     * hours of plasma.
     */
    fun metabolismRows(substanceID: Long): List<MetabolismHit> {
        if (order.isEmpty()) return emptyList()
        return db.query(
            """
            SELECT m.id, m.enzyme, m.fraction_of_clearance_pct, m.metabolite_name,
                   ms.canonical_name AS metabolite_substance_name,
                   m.metabolite_active, m.metabolite_potency_vs_parent_pct,
                   m.metabolite_potency_basis, m.metabolite_potency_target,
                   m.metabolite_mechanism_vs_parent, m.metabolite_half_life_min,
                   m.formation_fraction_pct, m.route,
                   src.slug AS source_slug, c.doi, c.pmid,
                   (SELECT h.half_life_minutes
                      FROM half_lives h
                      JOIN sources inner_src ON inner_src.id = h.source_id
                     WHERE h.substance_id = m.metabolite_substance_id
                       AND inner_src.slug IN ($enabledSourceListSQL)
                     ORDER BY ${priority.priorityCaseSQL("inner_src")} ASC, h.rowid
                     LIMIT 1) AS metabolite_own_half_life_min
              FROM metabolism m
              JOIN sources src ON src.id = m.source_id
              LEFT JOIN substances ms ON ms.id = m.metabolite_substance_id
              LEFT JOIN citations c ON c.id = m.citation_id
             WHERE m.substance_id = ?
             ORDER BY m.fraction_of_clearance_pct DESC NULLS LAST, m.enzyme ASC
            """,
            listOf(substanceID),
        ).map { row ->
            MetabolismHit(
                id = row.long("id") ?: 0L,
                enzyme = row.string("enzyme").orEmpty(),
                fractionOfClearancePct = row.double("fraction_of_clearance_pct"),
                metaboliteName = row.string("metabolite_name"),
                metaboliteSubstanceName = row.string("metabolite_substance_name"),
                metaboliteActive = row.long("metabolite_active")?.let { it != 0L },
                metabolitePotencyVsParentPct = row.double("metabolite_potency_vs_parent_pct"),
                metabolitePotencyBasis = MetabolitePotencyBasis.fromWire(row.string("metabolite_potency_basis")),
                metabolitePotencyTarget = row.string("metabolite_potency_target"),
                metaboliteMechanismVsParent = MetaboliteMechanism.fromWire(
                    row.string("metabolite_mechanism_vs_parent"),
                ),
                metaboliteHalfLifeMinutes = row.double("metabolite_own_half_life_min")
                    ?: row.double("metabolite_half_life_min"),
                formationFractionPct = row.double("formation_fraction_pct"),
                route = row.string("route"),
                sourceSlug = row.string("source_slug").orEmpty(),
                doi = row.string("doi"),
                pmid = row.long("pmid")?.toInt(),
            )
        }
    }

    /**
     * The substance's `downstream_signalling` rows.
     *
     * Prose, one row per source, ordered by source priority then rowid so the same source always comes first for
     * a given substance. Deduplicated on the summary text, because a substance whose signalling is described
     * identically by two sources has one description and two attributions — and the section lists the text once
     * with both sources rather than twice.
     */
    fun downstreamSignallingRows(substanceID: Long): List<DownstreamSignallingHit> {
        if (order.isEmpty()) return emptyList()
        return db.query(
            """
            SELECT d.summary, src.slug AS source_slug, c.doi, c.pmid
              FROM downstream_signalling d
              JOIN sources src ON src.id = d.source_id
              LEFT JOIN citations c ON c.id = d.citation_id
             WHERE d.substance_id = ?
               AND src.slug IN ($enabledSourceListSQL)
             ORDER BY ${priority.priorityCaseSQL("src")} ASC, d.rowid
            """,
            listOf(substanceID),
        ).map { row ->
            DownstreamSignallingHit(
                substanceId = substanceID,
                summary = row.string("summary").orEmpty(),
                sourceSlug = row.string("source_slug").orEmpty(),
                doi = row.string("doi"),
                pmid = row.long("pmid")?.toInt(),
            )
        }.filter { it.summary.isNotBlank() }
    }

    /**
     * The substance's `off_targets` rows: what it hits besides its mechanism.
     *
     * Ordered by affinity ascending — the tightest binding first — with the affinity-less rows last. That order is
     * the section's whole argument: a reader scanning it wants the targets most likely to matter, and a target
     * with no measured affinity cannot be ranked. `concern_level` is deliberately **not** the sort key: it is a
     * source's editorial judgement rather than a measurement, and sorting by it would put one source's opinion
     * above another's number.
     */
    fun offTargetRows(substanceID: Long): List<OffTargetHit> {
        if (order.isEmpty()) return emptyList()
        return db.query(
            """
            SELECT o.id, o.target, o.ki_or_ic50_nm, o.concern_level, o.clinical_consequence,
                   src.slug AS source_slug, c.doi, c.pmid
              FROM off_targets o
              JOIN sources src ON src.id = o.source_id
              LEFT JOIN citations c ON c.id = o.citation_id
             WHERE o.substance_id = ?
               AND src.slug IN ($enabledSourceListSQL)
             ORDER BY o.ki_or_ic50_nm ASC NULLS LAST, o.target ASC, o.id ASC
            """,
            listOf(substanceID),
        ).map { row ->
            OffTargetHit(
                id = row.long("id") ?: 0L,
                target = row.string("target").orEmpty(),
                kiOrIc50Nm = row.double("ki_or_ic50_nm"),
                concernLevel = row.string("concern_level"),
                clinicalConsequence = row.string("clinical_consequence"),
                sourceSlug = row.string("source_slug").orEmpty(),
                doi = row.string("doi"),
                pmid = row.long("pmid")?.toInt(),
            )
        }.filter { it.target.isNotBlank() }
    }

    /**
     * The substance's `pharmacogenetics` rows, one per gene.
     *
     * Ordered by gene name, because a reader looking for their own genotype scans for the gene rather than reading
     * the list — and a section ordered by source priority would move CYP2D6 around between substances.
     */
    fun pharmacogeneticRows(substanceID: Long): List<PharmacogeneticHit> {
        if (order.isEmpty()) return emptyList()
        return db.query(
            """
            SELECT p.id, p.gene, p.phenotype_effects,
                   src.slug AS source_slug, c.doi, c.pmid
              FROM pharmacogenetics p
              JOIN sources src ON src.id = p.source_id
              LEFT JOIN citations c ON c.id = p.citation_id
             WHERE p.substance_id = ?
               AND src.slug IN ($enabledSourceListSQL)
             ORDER BY p.gene ASC, p.id ASC
            """,
            listOf(substanceID),
        ).map { row ->
            PharmacogeneticHit(
                id = row.long("id") ?: 0L,
                gene = row.string("gene").orEmpty(),
                phenotypeEffects = row.string("phenotype_effects").orEmpty(),
                sourceSlug = row.string("source_slug").orEmpty(),
                doi = row.string("doi"),
                pmid = row.long("pmid")?.toInt(),
            )
        }.filter { it.gene.isNotBlank() }
    }

    /**
     * The substance's 2-D structure, or null when the catalogue has none or it does not hold together.
     *
     * 958 of the catalogue's 1689 substances have a row. Null is the honest answer for the other 731 and for a row
     * whose bonds do not land on its atoms — see `MoleculeShape.parse`. The section hides on null rather than
     * drawing an empty box.
     *
     * Decoded through [decodeJson], which returns null on a format break. That turns "wrong format" into "no
     * shape", so a test asserts a **count** for a substance known to have one: `null` from a malformed row and
     * `null` from no row are indistinguishable at the call site, and the count is what tells them apart.
     */
    fun moleculeShape(substanceID: Long): MoleculeShape? {
        val row = db.queryOne(
            "SELECT atoms_json, bonds_json FROM molecule_shapes WHERE substance_id = ?",
            listOf(substanceID),
        ) ?: return null
        val atoms = decodeJson<List<MoleculeAtom>>(row.string("atoms_json")) ?: return null
        // An absent bond list is a lone atom, not a failure; the column is nullable in practice.
        val bonds = decodeJson<List<MoleculeBond>>(row.string("bonds_json")).orEmpty()
        return MoleculeShape.parse(atoms, bonds)
    }

    /**
     * The substance's strength ladder, lowest rung first.
     *
     * 162 of the catalogue's substances have one, six bands each. Read in `band_index` order rather than by rowid:
     * the index is what the ladder means, and a source's row order is not guaranteed to follow it.
     *
     * Both JSON columns are decoded through [decodeJson], which returns null on a format break — so a test asserts
     * counts for a substance known to have effects and warnings, because "wrong format" and "no data" are
     * indistinguishable at this call site.
     */
    fun spectrumLevels(substanceID: Long): List<SpectrumLevel> {
        if (order.isEmpty()) return emptyList()
        return db.query(
            """
            SELECT t.band_index, t.band_name, t.description, t.top_effects_json, t.warnings_json
              FROM spectrum_levels t
              JOIN sources src ON src.id = t.source_id
             WHERE t.substance_id = ?
               AND src.slug IN ($enabledSourceListSQL)
             ORDER BY t.band_index ASC
            """,
            listOf(substanceID),
        ).mapNotNull { row ->
            val name = row.string("band_name").orEmpty()
            if (name.isBlank()) return@mapNotNull null
            SpectrumLevel(
                bandIndex = row.long("band_index")?.toInt() ?: 0,
                bandName = name,
                description = row.string("description").orEmpty(),
                // Ordered here rather than left to the source: the ranked order is the reason the column is a
                // count, and a JSON array's order is whatever the pipeline wrote.
                topEffects = decodeJson<List<SpectrumEffect>>(row.string("top_effects_json"))
                    .orEmpty()
                    .filter { it.name.isNotBlank() }
                    .sortedByDescending { it.freq },
                warnings = decodeJson<List<String>>(row.string("warnings_json"))
                    .orEmpty()
                    .filter { it.isNotBlank() },
            )
        }
    }

    /**
     * Every flag the substance carries, with the note that explains it.
     *
     * `substance_flags` is 13 rows over three flags, and two of the three are harm-reduction facts rather than
     * metadata — `suppresses-serotonin-synthesis` and `missold-as-mdma`. [hasFlag] answers "does this substance
     * carry this flag", which is the right shape for a caller that knows which flag it is asking about; this
     * answers "what is flagged about this substance", which is what a section needs and what was missing.
     *
     * Ordered by flag name so the list is stable between reads, and because a reader scanning for a known flag
     * finds it in the same place on every substance.
     */
    fun substanceFlags(substanceID: Long): List<SubstanceFlagRow> =
        db.query(
            """
            SELECT f.flag, f.notes, src.slug AS source_slug, c.doi, c.pmid
              FROM substance_flags f
              LEFT JOIN sources src ON src.id = f.source_id
              LEFT JOIN citations c ON c.id = f.citation_id
             WHERE f.substance_id = ?
             ORDER BY f.flag ASC
            """,
            listOf(substanceID),
        ).mapNotNull { row ->
            val flag = row.string("flag").orEmpty()
            if (flag.isBlank()) return@mapNotNull null
            SubstanceFlagRow(
                flag = flag,
                notes = row.string("notes"),
                sourceSlug = row.string("source_slug"),
                doi = row.string("doi"),
                pmid = row.long("pmid")?.toInt(),
            )
        }

    /**
     * Every `concentration_effects` row, findings included.
     *
     * Distinct from [therapeuticRangeRows], which filters to `kind = 'therapeutic_range'` **and** `threshold > 0`
     * because it answers a modelling question. This answers "what does the literature say about this substance's
     * blood levels", so it takes everything and carries `kind` through for the card to sort out.
     *
     * Ordered findings-first, then by level: the findings are the reason a reader opens the section, and a
     * therapeutic reference is context for them rather than the headline. `NULLS LAST` on the level because a row
     * with no measured threshold cannot be ranked against one that has.
     */
    fun concentrationEffectRows(substanceID: Long): List<ConcentrationEffectHit> {
        if (order.isEmpty()) return emptyList()
        return db.query(
            """
            SELECT e.id, e.effect, e.kind, e.concentration_unit, e.threshold, e.peak_effect,
                   src.slug AS source_slug, c.doi, c.pmid
              FROM concentration_effects e
              JOIN sources src ON src.id = e.source_id
              LEFT JOIN citations c ON c.id = e.citation_id
             WHERE e.substance_id = ?
               AND src.slug IN ($enabledSourceListSQL)
             ORDER BY (e.kind IS NULL) DESC, e.threshold ASC NULLS LAST, e.id ASC
            """,
            listOf(substanceID),
        ).mapNotNull { row ->
            val effect = row.string("effect").orEmpty()
            if (effect.isBlank()) return@mapNotNull null
            ConcentrationEffectHit(
                id = row.long("id") ?: 0L,
                effect = effect,
                kind = row.string("kind"),
                concentrationUnit = row.string("concentration_unit").orEmpty(),
                thresholdValue = row.double("threshold"),
                peakValue = row.double("peak_effect"),
                sourceSlug = row.string("source_slug").orEmpty(),
                doi = row.string("doi"),
                pmid = row.long("pmid")?.toInt(),
            )
        }
    }

    /**
     * The substance's neuroimaging findings.
     *
     * 52 rows over 36 substances, with 13 distinct modality strings. Ordered by modality then rowid so the rows of
     * one study type stay together and the order is stable between reads.
     */
    fun neuroimagingRows(substanceID: Long): List<NeuroimagingHit> {
        if (order.isEmpty()) return emptyList()
        return db.query(
            """
            SELECT n.id, n.modality, n.finding,
                   src.slug AS source_slug, c.doi, c.pmid
              FROM neuroimaging n
              JOIN sources src ON src.id = n.source_id
              LEFT JOIN citations c ON c.id = n.citation_id
             WHERE n.substance_id = ?
               AND src.slug IN ($enabledSourceListSQL)
             ORDER BY n.modality ASC, n.id ASC
            """,
            listOf(substanceID),
        ).mapNotNull { row ->
            val finding = row.string("finding").orEmpty()
            if (finding.isBlank()) return@mapNotNull null
            NeuroimagingHit(
                id = row.long("id") ?: 0L,
                modality = row.string("modality").orEmpty(),
                finding = finding,
                sourceSlug = row.string("source_slug").orEmpty(),
                doi = row.string("doi"),
                pmid = row.long("pmid")?.toInt(),
            )
        }
    }

    /** The substance's `pk_reference` pointer, or null when it carries none. */
    fun pkReference(substanceID: Long): PKReference? {
        val row = db.queryOne(
            "SELECT pk_reference_name, pk_reference_fields, pk_reference_confidence FROM substances WHERE id = ?",
            listOf(substanceID),
        ) ?: return null
        val name = row.string("pk_reference_name")
        if (name.isNullOrEmpty()) return null
        val fields = row.string("pk_reference_fields").orEmpty()
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        return PKReference(name, fields, ConfidenceTier.fromGrade(row.string("pk_reference_confidence")))
    }

    /** The substance's molar mass, from the same `substances` column the full detail read returns. */
    fun molarMass(substanceID: Long): Double? =
        db.queryOne("SELECT molecular_weight FROM substances WHERE id = ?", listOf(substanceID))
            ?.double("molecular_weight")

    /**
     * Whether a substance carries one `substance_flags` flag.
     *
     * No source-priority resolution, matching upstream: a flag is a model gate
     * rather than display vocabulary — the app never renders one — so it is either
     * present or it is not.
     */
    fun hasFlag(flag: String, substanceID: Long): Boolean =
        db.queryOne(
            "SELECT 1 AS present FROM substance_flags WHERE substance_id = ? AND flag = ? LIMIT 1",
            listOf(substanceID, flag),
        ) != null

    /**
     * The tolerance classes inferred from a substance's **categories**, with no
     * binding data consulted.
     *
     * Every category row is read, not just the priority-winning one: the inference
     * asks "could this substance be of this class", and a substance filed under
     * two categories that map to the same class should still yield it.
     */
    fun toleranceCategoryClasses(substanceID: Long): Set<ReceptorClasses.ReceptorClass> {
        val classes = mutableSetOf<ReceptorClasses.ReceptorClass>()
        for (row in db.query("SELECT category FROM categories WHERE substance_id = ?", listOf(substanceID))) {
            val raw = row.string("category") ?: continue
            val category = SubstanceCategory.fromWire(raw) ?: continue
            ReceptorClasses.ReceptorClass.toleranceClassFor(category)?.let { classes += it }
        }
        return classes
    }

    /** The tolerance classes each representative substance stands in for — the whole `class_representatives` table in one read. */
    fun classRepresentatives(): Map<Long, Set<ReceptorClasses.ReceptorClass>> {
        val out = mutableMapOf<Long, MutableSet<ReceptorClasses.ReceptorClass>>()
        for (row in db.query("SELECT receptor_class, substance_id FROM class_representatives")) {
            val cls = ReceptorClasses.ReceptorClass.fromWire(row.string("receptor_class")) ?: continue
            val id = row.long("substance_id") ?: continue
            out.getOrPut(id) { mutableSetOf() } += cls
        }
        return out
    }

    /**
     * The names of the substances that stand in for a tolerance class.
     *
     * A replay must resolve these *even when the user has never logged them*: the
     * missing-PK fallback models a PK-less substance as its class representative, so
     * a log containing only the PK-less member still needs the surrogate's
     * pharmacology in hand. Names rather than ids, because that is how the replay
     * keys its parameter map.
     */
    fun classRepresentativeNames(): List<String> =
        db.query(
            """
            SELECT DISTINCT s.canonical_name AS name
              FROM class_representatives cr
              JOIN substances s ON s.id = cr.substance_id
             ORDER BY s.canonical_name COLLATE NOCASE
            """,
        ).mapNotNull { it.string("name") }

    /**
     * Diazepam-mg per 1 mg of this substance, from the highest-priority enabled row
     * **that carries a citation**.
     *
     * The citation gate is the point rather than a nicety. This ratio is what lets
     * the tolerance engine model a PK-less benzodiazepine *as* diazepam and raise
     * the result's confidence floor from unverified to low — an upgrade only a
     * validated clinical equivalence earns. The upstream dataset carries no
     * per-value source, so before this gate the upgrade applied to numbers of
     * unknown origin. Five rows sit uncited today — brotizolam, etizolam,
     * flutoprazepam, midazolam, phenazepam — absent from the reference table they
     * are attributed to, and none the worse for saying so.
     */
    fun diazepamPerMg(substanceID: Long): Double? {
        val row = db.queryOne(
            """
            SELECT dose_mg, equivalent_diazepam_mg
              FROM diazepam_equivalents d
              JOIN sources src ON src.id = d.source_id
             WHERE d.substance_id = ?
               AND src.slug IN ($enabledSourceListSQL)
               AND d.citation_id IS NOT NULL
             ORDER BY $priorityCaseSQL ASC
             LIMIT 1
            """,
            listOf(substanceID),
        ) ?: return null
        val doseMg = row.double("dose_mg") ?: return null
        val equivalent = row.double("equivalent_diazepam_mg") ?: return null
        if (doseMg <= 0) return null
        return equivalent / doseMg
    }

    /**
     * The morphine-milligram-equivalent factor, or null when the row is not
     * [OpioidConvertibility.LINEAR].
     *
     * Methadone, transdermal fentanyl and buprenorphine must fall through to the
     * generic dose-fraction proxy rather than borrow a factor that does not exist
     * for them — the convertibility column is what says so.
     */
    fun opioidMme(substanceID: Long): OpioidMmeRow? {
        val row = db.queryOne(
            """
            SELECT mme_per_mg, convertibility
              FROM opioid_mme o
              JOIN sources src ON src.id = o.source_id
             WHERE o.substance_id = ?
               AND src.slug IN ($enabledSourceListSQL)
             ORDER BY $priorityCaseSQL ASC
             LIMIT 1
            """,
            listOf(substanceID),
        ) ?: return null
        val convertibility = OpioidConvertibility.fromWire(row.string("convertibility")) ?: return null
        if (convertibility != OpioidConvertibility.LINEAR) return null
        val mmePerMg = row.double("mme_per_mg") ?: return null
        return OpioidMmeRow(mmePerMg, convertibility)
    }

    /** Intrinsic efficacy relative to a full agonist, or null when the catalog carries no row — the common case, meaning the full-agonist default rather than "unknown". */
    fun intrinsicEfficacy(substanceID: Long): Double? =
        db.queryOne(
            """
            SELECT efficacy
              FROM intrinsic_efficacy e
              JOIN sources src ON src.id = e.source_id
             WHERE e.substance_id = ?
               AND src.slug IN ($enabledSourceListSQL)
             ORDER BY $priorityCaseSQL ASC
             LIMIT 1
            """,
            listOf(substanceID),
        )?.double("efficacy")

    /**
     * The dose-ladder rows [PharmacologyAssembly.referenceDoseMg] selects from:
     * the highest-priority enabled source per `(route, salt, isomer)`.
     *
     * The same `doseContextLast` ranking the ladder reads use, so the reference
     * matches the ladder the detail view actually shows.
     */
    fun referenceDoseRows(substanceID: Long): List<ReferenceDoseRow> {
        if (order.isEmpty()) return emptyList()
        val rank = priority.fieldRankSQL(field = "doses", alias = "d", doseContextLast = true)
        return db.query(
            """
            SELECT route, isomer, common_upper, strong_upper, heavy
              FROM (
                SELECT d.*, ROW_NUMBER() OVER (
                    PARTITION BY d.substance_id, d.route, d.salt_form, d.isomer
                    ORDER BY ${rank.orderBy}, d.id) AS rn
                  FROM dose_ranges d
                  JOIN sources src ON src.id = d.source_id
                  ${rank.join}
                 WHERE d.substance_id = ?
                   AND src.slug IN ($enabledSourceListSQL)
            ) WHERE rn = 1
            """,
            listOf(substanceID),
        ).map { row ->
            ReferenceDoseRow(
                route = row.string("route").orEmpty(),
                isomer = row.string("isomer"),
                commonUpper = row.double("common_upper"),
                strongUpper = row.double("strong_upper"),
                heavy = row.double("heavy"),
            )
        }
    }

    // MARK: - Capability indexes

    /**
     * Which substances accept a volume-and-concentration input, keyed by lowercased
     * canonical name **and** by every alias, so a dose logged as "Booze" finds the
     * same capability "Alcohol" does.
     *
     * ## A source-priority divergence, reproduced rather than corrected
     * This read neither filters to the user's enabled sources nor ranks by their
     * order — it ranks by `sources.default_priority`, the shipped column, and takes
     * a row from a source the user may have turned off. Every other value resolver
     * here is fail-closed, and this one feeds a conversion (`mL → g`), so by the
     * rule stated at the top of this file it should be closed too.
     *
     * It is reproduced because the shipped catalog has **two** `by_volume_dosing`
     * rows, both from `piru-curated`, so the difference is currently unobservable —
     * and a silent divergence from iOS on a conversion is worse than a documented
     * one. It becomes real the day a second source ships a row.
     */
    fun byVolumeCapabilities(): Map<String, ByVolumeDosing> {
        val presets = mutableMapOf<Long, MutableList<DrinkPreset>>()
        for (row in db.query(
            """
            SELECT p.substance_id AS sid, p.kind, p.volume_ml, p.default_strength
              FROM drink_presets p
              JOIN sources src ON src.id = p.source_id
             ORDER BY src.default_priority ASC, p.rank ASC
            """,
        )) {
            val id = row.long("sid") ?: continue
            // A kind this build has no label or symbol for is dropped: the presets
            // are emoji-and-label chips, and a raw `kind` string is not one.
            val kind = DrinkPreset.Kind.fromWire(row.string("kind")) ?: continue
            val volume = row.double("volume_ml") ?: continue
            val strength = row.double("default_strength") ?: continue
            presets.getOrPut(id) { mutableListOf() } += DrinkPreset(
                kind = kind,
                defaultABV = strength,
                volumeML = volume,
            )
        }

        val byID = mutableMapOf<Long, ByVolumeDosing>()
        val out = mutableMapOf<String, ByVolumeDosing>()
        for (row in db.query(
            """
            SELECT b.substance_id AS sid, s.canonical_name AS name, b.concentration_kind,
                   b.canonical_unit, b.density_g_per_ml, b.standard_unit_mass, b.standard_unit_label
              FROM (
                SELECT bv.*, ROW_NUMBER() OVER (
                    PARTITION BY bv.substance_id
                    ORDER BY src.default_priority ASC) AS rn
                  FROM by_volume_dosing bv
                  JOIN sources src ON src.id = bv.source_id
              ) b
              JOIN substances s ON s.id = b.substance_id
             WHERE b.rn = 1
            """,
        )) {
            val id = row.long("sid") ?: continue
            val name = row.string("name") ?: continue
            val unit = row.string("canonical_unit") ?: continue
            val concentration = when (row.string("concentration_kind")) {
                // Alcohol: the density is required, because the conversion is
                // undefined without it. A row missing it is dropped rather than
                // defaulted to ethanol's, which would silently dose a different
                // liquid as if it were alcohol.
                "percent_by_volume" -> {
                    val density = row.double("density_g_per_ml") ?: continue
                    if (density <= 0) continue
                    ByVolumeDosing.Concentration.PercentByVolume(density)
                }
                // Injectable esters: mg = volume × concentration, no density term.
                "mass_per_volume" -> ByVolumeDosing.Concentration.MassPerVolume
                else -> continue
            }
            val capability = ByVolumeDosing(
                concentration = concentration,
                canonicalUnit = unit,
                standardUnitMass = row.double("standard_unit_mass") ?: 0.0,
                standardUnitLabel = row.string("standard_unit_label").orEmpty(),
                drinkPresets = presets[id].orEmpty(),
            )
            byID[id] = capability
            out[name.lowercase()] = capability
        }

        if (byID.isEmpty()) return out
        // Aliases are added here rather than in the query above because the map has
        // to be keyed by name, and an alias only knows its substance's id.
        val idList = byID.keys.joinToString(", ")
        for (row in db.query("SELECT substance_id AS sid, alias FROM aliases WHERE substance_id IN ($idList)")) {
            val id = row.long("sid") ?: continue
            val alias = row.string("alias") ?: continue
            val capability = byID[id] ?: continue
            out[alias.lowercase()] = capability
        }
        return out
    }

    /**
     * A substance's stored zero-order parameters, **before** weight scaling — the
     * `Vmax` a reference body clears at, and the first-order absorption rate.
     *
     * Deliberately carries no bioavailability. F is the same quantity the
     * pharmacology resolve already picks a coherent `pk_routes` row for, and the
     * caller must pair each row with *that* number rather than read a second one
     * here — otherwise the timeline's curve and the occupancy math could disagree
     * about F for the same dose.
     */
    data class ZeroOrderRow(
        val canonicalName: String,
        val vmaxMgPerMin: Double,
        val referenceWeightKg: Double,
        val kaPerMin: Double,
    )

    /**
     * The whole `zero_order_kinetics` table, keyed by lowercased canonical name and
     * by every alias — the answer to "does this substance get the dose-scaled
     * linear-decline curve, and with what parameters".
     *
     * One row in the shipped catalog (alcohol). Fail-closed, unlike
     * [byVolumeCapabilities]: a row from a disabled source is absent, and the
     * caller then draws the ordinary phase bell.
     */
    fun zeroOrderRows(): Map<String, ZeroOrderRow> {
        if (order.isEmpty()) return emptyMap()
        val rows = db.query(
            """
            SELECT z.substance_id AS sid, s.canonical_name AS name, z.vmax_mg_per_min,
                   z.vmax_reference_weight_kg, z.ka_per_min
              FROM (
                SELECT zo.*, ROW_NUMBER() OVER (
                    PARTITION BY zo.substance_id
                    ORDER BY $priorityCaseSQL ASC, zo.rowid) AS rn
                  FROM zero_order_kinetics zo
                  JOIN sources src ON src.id = zo.source_id
                 WHERE src.slug IN ($enabledSourceListSQL)
              ) z
              JOIN substances s ON s.id = z.substance_id
             WHERE z.rn = 1
            """,
        )

        val byID = mutableMapOf<Long, ZeroOrderRow>()
        val out = mutableMapOf<String, ZeroOrderRow>()
        for (row in rows) {
            val id = row.long("sid") ?: continue
            val name = row.string("name") ?: continue
            val vmax = row.double("vmax_mg_per_min") ?: continue
            val referenceWeight = row.double("vmax_reference_weight_kg") ?: continue
            val ka = row.double("ka_per_min") ?: continue
            // A non-positive parameter is a row the model cannot run on: `Vmax` of
            // zero never clears and `ka` of zero never absorbs, and both would
            // produce a flat or infinitely-rising curve rather than an error.
            if (vmax <= 0 || referenceWeight <= 0 || ka <= 0) continue
            val entry = ZeroOrderRow(name, vmax, referenceWeight, ka)
            byID[id] = entry
            out[name.lowercase()] = entry
        }

        if (byID.isEmpty()) return out
        val idList = byID.keys.joinToString(", ")
        for (row in db.query("SELECT substance_id AS sid, alias FROM aliases WHERE substance_id IN ($idList)")) {
            val id = row.long("sid") ?: continue
            val alias = row.string("alias") ?: continue
            out[alias.lowercase()] = byID[id] ?: continue
        }
        return out
    }

    // MARK: - Full record (detail path)

    /**
     * The fully resolved record for one substance: mechanism, bindings, chemistry,
     * prose effects, citations, curated misconceptions and combinations.
     *
     * **Detail screens only.** This is the expensive read — the mechanism
     * bindings alone are a windowed query over every assay row — and nothing here
     * is cached. Everything that reads a name, category, dose ladder, duration or
     * half-life belongs on the batch path ([glass.kagerou.piru.engine.SubstanceCatalog]).
     *
     * Upstream probes the open database for the curated editorial columns because
     * a fixture database may lack them. Here the schema is fixed by the APK, so
     * they are read unconditionally; see [localizedNames] for why that difference
     * is real rather than careless.
     */
    fun fullSubstance(substanceID: Long): Substance? {
        val core = db.queryOne(
            """
            SELECT canonical_name, display_name, display_class, regulatory_status,
                   duration_implausible, substance_uid, cas, inchikey, formula,
                   pubchem_cid, molecular_weight, popularity, is_stub,
                   drug_community_slug, freeodwiki_slug, dosewiki_slug, smiles, iupac_name,
                   logp, tpsa, hba, hbd, ld50_oral_mg_per_kg, ld50_dermal_mg_per_kg,
                   melting_point_c, boiling_point_c,
                   popular_aliases, misconceptions, combinations, water_heat
              FROM substances WHERE id = ?
            """,
            listOf(substanceID),
        ) ?: return null

        val name = core.string("canonical_name") ?: return null
        val idList = setOf(substanceID)

        // `detailRoutes`, not `routes`: this is the one-row path a detail screen reads, and
        // only `detailRoutes` attaches protocol dosing and release windows and brings in the
        // routes that exist *only* as one of those. Using `routes` here meant
        // `SubstanceRoute.protocolDosing` and `.durationOfAction` were null for every
        // substance in the app, so the release-window line and the peptide protocol cards
        // could not render even where the data exists — and 45 `(substance, route)` pairs
        // that live only in `protocol_dosing` (35) or `durations_of_action` (10) showed no
        // route at all.
        val routes = detailRoutes(substanceID)
            .sortedBy { it.route.ordinal }
        val tags = tags(substanceID)
        val physicochemical = Physicochemical(
            logP = core.double("logp"),
            tpsa = core.double("tpsa"),
            hba = core.long("hba")?.toInt(),
            hbd = core.long("hbd")?.toInt(),
            ld50OralMgPerKg = core.double("ld50_oral_mg_per_kg"),
            ld50DermalMgPerKg = core.double("ld50_dermal_mg_per_kg"),
            meltingPointC = core.double("melting_point_c"),
            boilingPointC = core.double("boiling_point_c"),
        )

        return Substance(
            name = name,
            displayName = core.string("display_name"),
            // The title fields are left for the caller: `localizedName` needs the
            // whole `localized_names` table and the user's English-names
            // preference, neither of which belongs to a per-substance SQL read.
            // `DbSubstanceCatalog.resolveFull` stamps them from its warmed tables.
            aliases = displayAliases(idList)[substanceID].orEmpty(),
            category = category(substanceID)?.let { SubstanceCategory.fromWire(it) }
                ?: SubstanceCategory.OTHER,
            defaultRoute = routes.firstOrNull()?.route
                ?: RouteOfAdministration.from(if ("inhalation" in tags) "inhalation" else "oral"),
            routes = routes,
            effects = effects(substanceID),
            subjectiveEffects = subjectiveEffects(substanceID),
            toleranceInfo = tolerance(substanceID),
            halfLifeMinutes = halfLives(idList)[substanceID],
            sources = citedSources(substanceID),
            mechanismOfAction = mechanism(substanceID),
            displayClass = CompoundDisplayClass.fromWire(core.string("display_class")),
            regulatoryStatus = core.string("regulatory_status"),
            durationImplausible = (core.long("duration_implausible") ?: 0L) != 0L,
            diazepamEquivalent = diazepamEquivalent(substanceID),
            substanceUID = core.string("substance_uid"),
            cas = core.string("cas"),
            inchikey = core.string("inchikey"),
            formula = core.string("formula"),
            pubchemCID = core.long("pubchem_cid")?.toInt(),
            popularity = core.double("popularity") ?: 0.0,
            isStub = (core.long("is_stub") ?: 0L) != 0L,
            tags = tags,
            molarMass = core.double("molecular_weight"),
            peptideProfile = peptideProfile(substanceID),
            references = references(substanceID),
            drugCommunitySlug = core.string("drug_community_slug"),
            freeodwikiSlug = core.string("freeodwiki_slug"),
            dosewikiSlug = core.string("dosewiki_slug"),
            overview = overview(substanceID),
            smiles = core.string("smiles"),
            iupacName = core.string("iupac_name"),
            physicochemical = physicochemical.takeIf { it.hasAnyValue },
            popularAliases = decodeJson(core.string("popular_aliases")) ?: emptyList(),
            misconceptions = decodeJson(core.string("misconceptions")) ?: emptyList(),
            combinations = decodeJson(core.string("combinations")) ?: emptyList(),
            waterHeat = decodeJson<WaterHeatGuidance>(core.string("water_heat")),
        )
    }

    /**
     * The SQL scalar resolving a row of `effects` (aliased `e`) to its localized
     * label through the controlled vocabulary.
     *
     * For Chinese it returns the label for the exact variant, then any broader zh
     * label, then the raw English `e.text` — so a zh reader sees translated
     * effects on *every* substance, even ones whose source data was English-only,
     * because the label was translated once at the vocabulary level. For English
     * it is simply `e.text`, already the canonical name.
     *
     * [language] is an enum constant and the `zh%` pattern is a literal, so
     * interpolating either carries no injection surface.
     */
    private fun localizedEffectLabelSQL(): String {
        if (!language.isChinese) return "e.text"
        return """
        COALESCE(
            (SELECT lbl.label FROM effect_vocab_labels lbl
              WHERE lbl.vocab_id = e.vocab_id AND lbl.language = '${language.wireValue}'),
            (SELECT lbl.label FROM effect_vocab_labels lbl
              WHERE lbl.vocab_id = e.vocab_id AND lbl.language LIKE 'zh%' LIMIT 1),
            e.text)
        """
    }

    /** The union of effect labels across enabled sources, deduplicated and sorted. */
    fun effects(substanceID: Long): List<String> = db.query(
        """
        SELECT DISTINCT ${localizedEffectLabelSQL()} AS text
          FROM effects e
          JOIN sources src ON src.id = e.source_id
         WHERE e.substance_id = ?
           AND src.slug IN ($enabledSourceListSQL)
         ORDER BY text
        """,
        listOf(substanceID),
    ).mapNotNull { it.string("text") }

    /**
     * The SQL scalar resolving a `subjective_effects` row (aliased `se`) to its
     * label in [language], through the controlled vocabulary and falling back to
     * the raw stored name.
     *
     * Rows are stored once per language, so a substance whose only effect source
     * writes Chinese has nothing an English reader can be shown until the
     * vocabulary bridges it. This is why it pairs with [subjectiveLanguageFilterSQL]:
     * on its own the COALESCE would fall through to a Han `se.name`.
     */
    private fun subjectiveLabelSQL(): String {
        if (!language.isChinese) {
            return """
            COALESCE(
                (SELECT lbl.label FROM effect_vocab_labels lbl
                  WHERE lbl.vocab_id = se.vocab_id AND lbl.language = 'en'),
                se.name)
            """
        }
        return """
        COALESCE(
            (SELECT lbl.label FROM effect_vocab_labels lbl
              WHERE lbl.vocab_id = se.vocab_id AND lbl.language = '${language.wireValue}'),
            (SELECT lbl.label FROM effect_vocab_labels lbl
              WHERE lbl.vocab_id = se.vocab_id AND lbl.language LIKE 'zh%' LIMIT 1),
            se.name)
        """
    }

    /** Which rows can be *rendered* in [language]: the ones written in it, plus the ones the vocabulary can translate into it. */
    private fun subjectiveLanguageFilterSQL(): String {
        if (!language.isChinese) {
            // Every vocab id carries an English label by construction, so a vocab
            // hit is enough on its own.
            return "AND (se.language IN ('en', 'und') OR se.vocab_id IS NOT NULL)"
        }
        return """
        AND (se.language LIKE 'zh%'
             OR EXISTS (SELECT 1 FROM effect_vocab_labels l2
                         WHERE l2.vocab_id = se.vocab_id AND l2.language LIKE 'zh%'))
        """
    }

    /**
     * The substance's subjective effects, one entry per effect.
     *
     * A description is carried only when the row was written in the language
     * being rendered — a bridged row's prose is still in its own language, and a
     * Han paragraph under an English effect name is worse than none.
     */
    fun subjectiveEffects(substanceID: Long): List<SubjectiveEffect> {
        val ownLanguage =
            if (language.isChinese) "se.language LIKE 'zh%'" else "se.language IN ('en', 'und')"
        return db.query(
            """
            SELECT ${subjectiveLabelSQL()} AS effect_name,
                   COALESCE(MAX(CASE WHEN $ownLanguage THEN se.description END), '') AS effect_description
              FROM subjective_effects se
              JOIN sources src ON src.id = se.source_id
             WHERE se.substance_id = ?
               AND src.slug IN ($enabledSourceListSQL)
               ${subjectiveLanguageFilterSQL()}
             GROUP BY effect_name
             ORDER BY effect_name
            """,
            listOf(substanceID),
        ).mapNotNull { row ->
            val name = row.string("effect_name") ?: return@mapNotNull null
            SubjectiveEffect(name = name, description = row.string("effect_description").orEmpty())
        }
    }

    /**
     * The substance's effects grouped by PsychonautWiki category, ordered for
     * display — the "All effects" screen, which is the only caller. The flat
     * [effects] union drives browse and search.
     */
    fun effectGroups(substanceID: Long): List<EffectGroup> {
        val byCategory = mutableMapOf<String, MutableList<String>>()
        for (row in db.query(
            """
            SELECT DISTINCT ${localizedEffectLabelSQL()} AS text,
                            COALESCE(e.effect_category, '') AS category
              FROM effects e
              JOIN sources src ON src.id = e.source_id
             WHERE e.substance_id = ?
               AND src.slug IN ($enabledSourceListSQL)
             ORDER BY text COLLATE NOCASE
            """,
            listOf(substanceID),
        )) {
            val text = row.string("text") ?: continue
            val raw = row.string("category").orEmpty()
            byCategory.getOrPut(raw.ifEmpty { "Other" }) { mutableListOf() }.add(text)
        }
        return byCategory.keys
            .sortedWith(
                compareBy(
                    { EFFECT_CATEGORY_ORDER.indexOf(it).takeIf { i -> i >= 0 } ?: EFFECT_CATEGORY_ORDER.size },
                    { it },
                ),
            )
            .map { EffectGroup(category = it, effects = byCategory[it].orEmpty()) }
    }

    /**
     * The substance's overview prose, resolved locale-first.
     *
     * Consults `source_field_priority`: a dose.wiki summary was written for an
     * expert-reviewed article, where PsychonautWiki's is a wiki lead copied whole
     * and FreeOD's English is machine-translated — so the catalog ranks dose.wiki
     * directly beneath the curated layer here, above the published literature.
     */
    fun overview(substanceID: Long): SubstanceOverview? {
        val row = textRow(
            table = "descriptions",
            selecting = "t.text",
            substanceID = substanceID,
            fieldPriority = "descriptions",
        ) ?: return null
        val text = row.row.string("text").orEmpty()
        if (text.isEmpty()) return null
        return SubstanceOverview(
            text = text,
            machineTranslated = row.machineTranslated,
            sourceSlug = row.sourceSlug.ifEmpty { "freeodwiki" },
        )
    }

    /**
     * The mechanism card's content: summary prose plus the receptor bindings.
     *
     * ## A curated affinity tier outranks the derived band
     * The derived band is absolute — Kᵢ under 100 nM strong, EC₅₀ under 1 µM
     * strong — and an absolute band cannot say which of *this* compound's targets
     * is the weak one. Methamphetamine is the case that proves it: SERT release
     * EC₅₀ 736 nM lands under the 1 µM cutoff and dots as "strong", beside DAT
     * 24.5 and NET 12.3 nM on the same card and a ternary reading SERT 1 %. The
     * curator had already written the answer as `affinity_tier = 1`; the query was
     * discarding it. Do not "restore" measured-wins: a hand-set tier is a claim
     * about this drug's own balance, which is the question the dots ask.
     *
     * The derived band still does all the work where nothing is curated, and its
     * cutoffs here must stay identical to [ReceptorStrength]'s.
     */
    fun mechanism(substanceID: Long): MechanismOfAction? {
        val row = textRow(
            table = "mechanisms_summary",
            selecting = "t.summary, t.description",
            substanceID = substanceID,
        )

        val rawHits = mutableListOf<RawHit>()
        for (r in db.query(
            """
            SELECT target, action,
                   COALESCE(MAX(curated_tier), MAX(derived_tier), 1) AS affinity,
                   MAX(measured) AS measured, MIN(ki_nm) AS ki_nm FROM (
                SELECT b.target, b.action, b.ki_nm, b.affinity_tier AS curated_tier,
                       CASE WHEN b.ki_nm IS NOT NULL OR b.ec50_nm IS NOT NULL OR b.ic50_nm IS NOT NULL
                            THEN 1 ELSE 0 END AS measured,
                       CASE WHEN b.ki_nm   IS NOT NULL AND b.ki_nm   <   100 THEN 3
                            WHEN b.ki_nm   IS NOT NULL AND b.ki_nm   <  1000 THEN 2
                            WHEN b.ki_nm   IS NOT NULL                        THEN 1
                            WHEN b.ec50_nm IS NOT NULL AND b.ec50_nm <  1000 THEN 3
                            WHEN b.ec50_nm IS NOT NULL AND b.ec50_nm < 10000 THEN 2
                            WHEN b.ec50_nm IS NOT NULL                        THEN 1
                            WHEN b.ic50_nm IS NOT NULL AND b.ic50_nm <  1000 THEN 3
                            WHEN b.ic50_nm IS NOT NULL AND b.ic50_nm < 10000 THEN 2
                            WHEN b.ic50_nm IS NOT NULL                        THEN 1
                            ELSE NULL END AS derived_tier
                  FROM bindings b
                  JOIN sources src ON src.id = b.source_id
                 WHERE b.substance_id = ?
                   AND src.slug IN ($enabledSourceListSQL)
            )
             GROUP BY target, action
             ORDER BY affinity DESC, ki_nm ASC NULLS LAST, LENGTH(target) ASC
             LIMIT 40
            """,
            listOf(substanceID),
        )) {
            val target = r.string("target") ?: continue
            val action = BindingAction.fromWire(r.string("action")) ?: continue
            rawHits += RawHit(
                target = target,
                action = action,
                tier = r.long("affinity")?.toInt() ?: 1,
                measured = (r.long("measured") ?: 0L) == 1L,
            )
        }

        // Collapse to one row per receptor for the summary table: a measured row
        // often restates a curated target under a wordier name ("NMDA (MK-801
        // site, S-enantiomer)" against the curated "NMDA"). The cleanest name and
        // the curated action label win. Tier precedence is already settled per
        // (target, action) by the query above, so this pass only chooses between
        // differently-*named* rows for the same receptor, preferring measured ones
        // when any exist. The full per-assay detail lives in the receptor
        // literature disclosure.
        val groupOrder = mutableListOf<String>()
        val groups = mutableMapOf<String, MutableList<RawHit>>()
        for (hit in rawHits) {
            val key = ReceptorTargetKey.fold(hit.target)
            if (groups[key] == null) groupOrder += key
            groups.getOrPut(key) { mutableListOf() }.add(hit)
        }
        val bindings = groupOrder.mapNotNull { key ->
            val hits = groups[key] ?: return@mapNotNull null
            if (hits.isEmpty()) return@mapNotNull null
            val measured = hits.filter { it.measured }
            val tier = (measured.ifEmpty { hits }).maxOf { it.tier }
            val action = hits.firstOrNull { !it.measured }?.action
                ?: measured.maxByOrNull { it.tier }?.action
                ?: hits[0].action
            val target = hits.minByOrNull { it.target.length }?.target ?: hits[0].target
            ReceptorBinding(target, action, BindingAffinity.fromTier(tier))
        }.sortedByDescending { it.affinity }

        // Surface measured bindings even when no curated summary row exists: the
        // detail view fills missing summary text from a category fallback, so a
        // substance with real receptor data no longer falls through to a generic
        // "Modulator" placeholder. Null only when there is neither.
        if (row == null && bindings.isEmpty()) return null

        return MechanismOfAction(
            summary = row?.row?.string("summary").orEmpty(),
            description = row?.row?.string("description").orEmpty(),
            primaryTargets = bindings.map { it.target },
            bindings = bindings,
            summaryLanguage = row?.language,
        )
    }

    /** One assay row as the mechanism query returns it, before collapsing. */
    private data class RawHit(
        val target: String,
        val action: BindingAction,
        val tier: Int,
        val measured: Boolean,
    )

    /**
     * Dose-equivalency rows (benzodiazepines only), highest-priority source.
     *
     * The citation flag is not read from the row: the upstream dataset carries no
     * per-value source, so the pipeline attaches a citation only where the shipped
     * number agrees with the reference table it is attributed to. That decision was
     * made at build time and is not recoverable from the database, so [DiazepamEquivalent.isCited]
     * keeps its default here rather than being guessed at.
     */
    fun diazepamEquivalent(substanceID: Long): DiazepamEquivalent? {
        val row = db.queryOne(
            """
            SELECT dose_mg, equivalent_diazepam_mg, display_text
              FROM diazepam_equivalents d
              JOIN sources src ON src.id = d.source_id
             WHERE d.substance_id = ?
               AND src.slug IN ($enabledSourceListSQL)
             ORDER BY $priorityCaseSQL ASC
             LIMIT 1
            """,
            listOf(substanceID),
        ) ?: return null
        return DiazepamEquivalent(
            doseMg = row.double("dose_mg"),
            equivalentDiazepamMg = row.double("equivalent_diazepam_mg"),
            displayText = row.string("display_text"),
        )
    }

    /**
     * Tolerance build and reset, highest-priority source — and only from a row
     * that carries all three numbers.
     *
     * The all-or-nothing filter is upstream's and is deliberate: a half-life with
     * no reset figure would let the UI claim a decay it cannot compute.
     */
    fun tolerance(substanceID: Long): ToleranceInfo? {
        val row = db.queryOne(
            """
            SELECT t.half_life_days, t.full_reset_days, t.build_rate
              FROM tolerance t
              JOIN sources src ON src.id = t.source_id
             WHERE t.substance_id = ?
               AND src.slug IN ($enabledSourceListSQL)
               AND t.half_life_days IS NOT NULL
               AND t.full_reset_days IS NOT NULL
               AND t.build_rate IS NOT NULL
             ORDER BY $priorityCaseSQL ASC
             LIMIT 1
            """,
            listOf(substanceID),
        ) ?: return null
        return ToleranceInfo(
            halfLife = row.double("half_life_days") ?: return null,
            fullResetDays = row.double("full_reset_days") ?: return null,
            buildRate = row.string("build_rate") ?: return null,
        )
    }

    /**
     * Distinct primary references: the substance-level curated citations plus the
     * ones attached to its dose, duration, half-life, mechanism and protocol
     * facts.
     *
     * Binding citations are excluded — they have a dedicated receptor literature
     * card and would swamp the list.
     */
    fun references(substanceID: Long): List<Citation> = db.query(
        """
        SELECT DISTINCT c.doi, c.pmid, c.url, c.title FROM citations c
         WHERE c.id IN (
            SELECT citation_id FROM substance_citations WHERE substance_id = ?
            UNION SELECT citation_id FROM dose_ranges        WHERE substance_id = ?
            UNION SELECT citation_id FROM durations          WHERE substance_id = ?
            UNION SELECT citation_id FROM half_lives         WHERE substance_id = ?
            UNION SELECT citation_id FROM mechanisms_summary WHERE substance_id = ?
            UNION SELECT citation_id FROM protocol_dosing    WHERE substance_id = ?
         )
         ORDER BY c.title, c.url, c.doi
         LIMIT 60
        """,
        List(6) { substanceID },
    ).map { row ->
        Citation(
            doi = row.string("doi"),
            pmid = row.long("pmid")?.toInt(),
            url = row.string("url"),
            title = row.string("title"),
        )
    }

    /**
     * Peptide and biologic handling data, or null when the substance has none or
     * the row carries nothing usable.
     *
     * A row whose temperature code this build does not know still yields a profile
     * — the sequence and vial size are independent of the storage requirement — so
     * the unknown value is dropped rather than the whole record.
     */
    fun peptideProfile(substanceID: Long): PeptideProfile? {
        val row = db.queryOne(
            """
            SELECT sequence, supplied_form, typical_vial_mg, reconstitution_solvent,
                   storage_temperature, storage_light_sensitive,
                   reconstituted_stability_days, iu_per_mg
              FROM peptide_profiles WHERE substance_id = ?
            """,
            listOf(substanceID),
        ) ?: return null
        val temperature = StorageRequirement.Temperature.fromWire(row.string("storage_temperature"))
        val storage = temperature?.let {
            StorageRequirement(
                temperature = it,
                lightSensitive = (row.long("storage_light_sensitive") ?: 0L) != 0L,
                reconstitutedStabilityDays = row.double("reconstituted_stability_days"),
            )
        }
        val profile = PeptideProfile(
            sequence = row.string("sequence"),
            suppliedForm = SuppliedForm.fromWire(row.string("supplied_form")),
            typicalVialMg = row.double("typical_vial_mg"),
            reconstitutionSolvent = row.string("reconstitution_solvent"),
            storage = storage,
            iuPerMg = row.double("iu_per_mg"),
        )
        return profile.takeIf { it.hasAnyValue }
    }

    /** Slugs of the sources that contributed any fact for this substance — the attribution row. */
    fun citedSources(substanceID: Long): List<String> = db.query(
        """
        SELECT DISTINCT src.slug AS slug FROM (
            SELECT source_id FROM categories         WHERE substance_id = ?
            UNION SELECT source_id FROM dose_ranges  WHERE substance_id = ?
            UNION SELECT source_id FROM durations    WHERE substance_id = ?
            UNION SELECT source_id FROM half_lives   WHERE substance_id = ?
            UNION SELECT source_id FROM mechanisms_summary WHERE substance_id = ?
            UNION SELECT source_id FROM bindings     WHERE substance_id = ?
        ) AS uses
        JOIN sources src ON src.id = uses.source_id
        WHERE src.slug IN ($enabledSourceListSQL)
        ORDER BY src.slug
        """,
        List(6) { substanceID },
    ).mapNotNull { it.string("slug") }

    /**
     * Decode a curated editorial JSON blob, degrading to null rather than throwing.
     *
     * A malformed blob loses one card, not the whole detail screen — and these
     * are hand-authored, so a typo in one substance's combinations must not take
     * the mechanism and chemistry sections down with it.
     *
     * The cost of that tolerance is that a **wire-format break is silent**:
     * renaming a field on the pipeline side empties the card with no error
     * anywhere. Upstream logs the failure; there is no logger in this module, so
     * the guard is a test instead — `CuratedBlobTest` in `:core:model` decodes
     * fixtures copied from the shipped database, which is where a renamed key
     * fails loudly. Assert a decode's *count* for the same reason: `?: emptyList()`
     * turns "wrong format" into "no data", and only a count tells them apart.
     */
    private inline fun <reified T> decodeJson(raw: String?): T? {
        if (raw.isNullOrEmpty()) return null
        return runCatching { json.decodeFromString<T>(raw) }.getOrNull()
    }

    /**
     * Canonical display order for PsychonautWiki effect categories — mirrors
     * `CATEGORY_ORDER` in `pipeline/build/pw_effect_categories.py`. An unknown
     * category, including the `Other` bucket for uncategorized survivors, sorts
     * last by name.
     */
    private val EFFECT_CATEGORY_ORDER = listOf(
        "Physical", "Cognitive", "Visual", "Auditory", "Tactile",
        "Multisensory", "Sensory", "Smell and taste", "Transpersonal", "Disconnective",
    )

    /**
     * Tolerant on purpose: the curated JSON is hand-authored and grows fields
     * faster than this build can be recompiled. A key this build has never heard
     * of is a newer pipeline's addition, not a malformed blob.
     */
    private val json = Json { ignoreUnknownKeys = true }

    // MARK: - Routes

    /**
     * Dose, duration and salt/isomer-bearing routes for a set of substances.
     *
     * One windowed query per table for any number of substances, then grouped and
     * folded in memory — the shape upstream's `resolveRoutes` uses, which
     * collapsed the detail path's N+1 into a constant number of statements.
     *
     * Fail-closed throughout: a route whose only sources are disabled does not
     * appear at all, and a caller that then draws nothing is correct.
     *
     * Returned lists are not route-rank sorted; that is the caller's ordering
     * decision, not this layer's.
     */
    fun routes(substanceIDs: Set<Long>): Map<Long, List<SubstanceRoute>> {
        if (substanceIDs.isEmpty() || order.isEmpty()) return emptyMap()

        val ladders = doseLadders(substanceIDs)
        if (ladders.isEmpty()) return emptyMap()
        val durations = durations(substanceIDs)

        // Group variants by substance, then by route string — a per-salt variant
        // of one route folds into a single SubstanceRoute.
        val bySubstance = mutableMapOf<Long, MutableMap<String, MutableList<RouteVariant>>>()
        for ((key, dose) in ladders) {
            val variant = RouteVariant(
                salt = key.saltForm,
                isomer = key.isomer,
                isomerDisplayName = dose.isomerDisplayName,
                unit = dose.unit,
                doses = dose.doses,
                duration = durations[key],
                doseContext = DoseContext.fromWire(dose.doseContext),
                rank = dose.saltRank,
                elementalFraction = dose.elementalFraction,
            )
            bySubstance
                .getOrPut(key.substanceID) { mutableMapOf() }
                .getOrPut(key.route) { mutableListOf() }
                .add(variant)
        }

        return bySubstance.mapValues { (_, byRoute) ->
            byRoute.map { (routeString, variants) ->
                makeRoute(RouteOfAdministration.from(routeString), variants)
            }
        }
    }

    // MARK: - Auxiliary routes

    /** Clinical-protocol dosing for one route, and the unit its amounts are in. */
    data class ResolvedProtocol(val unit: String, val dosing: ProtocolDosing)

    /**
     * Clinical-protocol dosing, highest-priority source per route.
     *
     * Keyed by the parsed route enum rather than the raw string, so it lines up
     * with the dose routes — the database writes `intranasal` and `oral_er` where
     * the enum says `insufflation` and `oral`.
     */
    fun protocolDosing(substanceID: Long): Map<RouteOfAdministration, ResolvedProtocol> {
        if (order.isEmpty()) return emptyMap()
        return db.query(
            """
            SELECT route, unit, low_amount, high_amount, frequency,
                   titration_json, course_duration, notes
              FROM (
                SELECT p.*, ROW_NUMBER() OVER (
                    PARTITION BY p.route
                    ORDER BY $priorityCaseSQL, p.id) AS rn
                  FROM protocol_dosing p
                  JOIN sources src ON src.id = p.source_id
                 WHERE p.substance_id = ?
                   AND src.slug IN ($enabledSourceListSQL)
            ) WHERE rn = 1
            """,
            listOf(substanceID),
        ).mapNotNull { row ->
            val frequency = row.string("frequency") ?: return@mapNotNull null
            val titration = row.string("titration_json")?.let { raw ->
                runCatching { json.decodeFromString<List<TitrationStep>>(raw) }.getOrNull()
            }
            RouteOfAdministration.from(row.string("route") ?: "") to ResolvedProtocol(
                unit = row.string("unit") ?: "mg",
                dosing = ProtocolDosing(
                    lowAmount = row.double("low_amount"),
                    highAmount = row.double("high_amount"),
                    frequency = frequency,
                    titration = titration,
                    courseDuration = row.string("course_duration"),
                    notes = row.string("notes"),
                ),
            )
        }.toMap()
    }

    /**
     * Release or duration-of-action windows, highest-priority source per route.
     *
     * A depot injection or a patch acts over days to weeks; the window is shown
     * in the drug card and never drawn as an acute timeline curve.
     */
    fun durationsOfAction(substanceID: Long): Map<RouteOfAdministration, DurationOfAction> {
        if (order.isEmpty()) return emptyMap()
        return db.query(
            """
            SELECT route, min_minutes, max_minutes
              FROM (
                SELECT da.route, da.min_minutes, da.max_minutes,
                       ROW_NUMBER() OVER (
                           PARTITION BY da.route
                           ORDER BY $priorityCaseSQL, da.id) AS rn
                  FROM durations_of_action da
                  JOIN sources src ON src.id = da.source_id
                 WHERE da.substance_id = ?
                   AND src.slug IN ($enabledSourceListSQL)
            ) WHERE rn = 1
            """,
            listOf(substanceID),
        ).mapNotNull { row ->
            val min = row.double("min_minutes") ?: return@mapNotNull null
            val max = row.double("max_minutes") ?: return@mapNotNull null
            RouteOfAdministration.from(row.string("route") ?: "") to
                DurationOfAction(minMinutes = min, maxMinutes = max)
        }.toMap()
    }

    /**
     * Add protocol dosing and release windows to a substance's dose routes, and
     * append routes that exist only as one of those.
     *
     * Three steps, matching upstream's `attachAuxiliaryRoutes`:
     * 1. Re-fold the routes that have a ladder, so the protocol and window ride
     *    along on the same `SubstanceRoute`.
     * 2. Append **duration-only** routes — a release window with no ladder, e.g. a
     *    weekly peptide whose only number is a schedule.
     * 3. Append **protocol-only** routes — a clinical schedule with neither a
     *    ladder nor phases.
     *
     * ## A faithful reproduction of upstream's behaviour, not an oversight
     * The re-fold in step 1 rebuilds each variant **without** its `doseContext`,
     * so a route that has protocol dosing or a release window loses that field —
     * it reverts to `unknown`. This is upstream's code path exactly:
     * `RouteVariant`'s `doseContext` defaults to `.unknown`, and that initializer
     * does not pass it.
     *
     * It is reachable and visible. On the shipped catalog, 18 substances are
     * `medical_rx` — the class for which the dose card *shows* the regime label —
     * and have a recreational ladder on a route that also carries protocol dosing
     * or a release window. Those cards lose a label that exists precisely to stop
     * a research-chem figure being read as a prescribed one.
     *
     * Carrying the field through would be a one-line change here, and the Android
     * side would then label those 18 cards where iOS does not. That divergence is
     * not this port's to make: it is reported upstream instead, and this method
     * matches the behaviour until that report is answered.
     */
    fun attachAuxiliaryRoutes(
        substanceID: Long,
        routes: List<SubstanceRoute>,
    ): List<SubstanceRoute> {
        val protocols = protocolDosing(substanceID)
        val windows = durationsOfAction(substanceID)

        val resolved = routes.map { route ->
            val protocol = protocols[route.route]?.dosing
            val window = windows[route.route]
            if (protocol == null && window == null) return@map route

            val variants = route.saltForms?.mapIndexed { index, variant ->
                // The incoming list is already in curated order, so the index is
                // fed back as the rank: re-folding is then order-preserving.
                RouteVariant(
                    salt = variant.saltForm,
                    isomer = variant.isomer,
                    isomerDisplayName = variant.isomerDisplayName,
                    unit = variant.unit,
                    doses = variant.doses,
                    duration = variant.duration,
                    rank = index,
                    elementalFraction = variant.elementalFraction,
                )
            } ?: listOf(
                RouteVariant(
                    salt = null,
                    isomer = null,
                    isomerDisplayName = null,
                    unit = route.unit,
                    doses = route.doses,
                    duration = route.duration,
                ),
            )

            makeRoute(route.route, variants).copy(
                protocolDosing = protocol,
                durationOfAction = window,
            )
        }.toMutableList()

        val haveRoutes = resolved.map { it.route }.toMutableSet()

        // Duration-only routes: a release window or an acute duration with no ladder.
        //
        // The union of both key sets, not `durations()` alone. Iterating only the acute
        // profiles skipped exactly the routes a depot has: a long-acting injectable carries
        // `durations_of_action` and nothing else — no ladder and no acute phases, because
        // the whole point of it is that it does not act acutely — so the single most useful
        // fact about it, its release window, never reached the card. Ten such
        // `(substance, route)` pairs ship in the catalogue (aripiprazole, fluphenazine,
        // paliperidone and risperidone depots; dulaglutide, liraglutide and two semaglutide
        // routes; epitalon) and every one of them was invisible.
        //
        // The two keys are different types addressing the same route strings, so the union
        // is taken over the route values and each source is consulted for its own half:
        // `duration` comes from the acute profile when there is one, and the window from
        // `durationsOfAction`. A route with both gets both, which is the common case for an
        // oral that also has a long tail.
        val acute = durations(setOf(substanceID))
        val windowRoutes = windows.keys + acute.keys.map { it.route }.map(RouteOfAdministration::from)
        for (route in windowRoutes.sortedBy { it.ordinal }) {
            if (!haveRoutes.add(route)) continue
            val acuteProfile = acute.entries
                .firstOrNull { RouteOfAdministration.from(it.key.route) == route }
            resolved += SubstanceRoute(
                route = route,
                // "mg" is the placeholder a ladderless route has always reported; there is no
                // dose to carry a unit, and the window's own minutes live in
                // `DurationOfAction` rather than in this field.
                unit = "mg",
                doses = DoseRange(),
                duration = acuteProfile?.value,
                protocolDosing = protocols[route]?.dosing,
                durationOfAction = windows[route],
            )
        }

        // Protocol-only routes: a clinical schedule with neither.
        for ((route, value) in protocols) {
            if (!haveRoutes.add(route)) continue
            resolved += SubstanceRoute(
                route = route,
                unit = value.unit,
                doses = DoseRange(),
                duration = null,
                protocolDosing = value.dosing,
                durationOfAction = windows[route],
            )
        }

        return resolved
    }

    /**
     * The routes a detail screen shows: the dose-bearing ones with protocol
     * dosing and release windows attached, plus the routes that exist only as
     * one of those.
     *
     * The browse path deliberately uses [routes] alone — a list does not need a
     * peptide's titration schedule, and resolving it costs two more queries per
     * substance.
     */
    fun detailRoutes(substanceID: Long): List<SubstanceRoute> =
        attachAuxiliaryRoutes(substanceID, routes(setOf(substanceID))[substanceID].orEmpty())

    /** One resolved form of a route, before folding into a [SubstanceRoute]. */
    internal data class RouteVariant(
        /** Null is the unspecified or base form, which the vast majority of substances use. */
        val salt: String?,
        val isomer: String?,
        val isomerDisplayName: String?,
        val unit: String,
        val doses: DoseRange,
        val duration: DurationProfile?,
        /**
         * Which regime this ladder describes.
         *
         * Defaulted for the same reason it is upstream, and with the same
         * consequence: [attachAuxiliaryRoutes] rebuilds variants without naming
         * this, so a route that goes through that path reverts to `unknown`. See
         * that method's note — the default is load-bearing, not convenience.
         */
        val doseContext: DoseContext = DoseContext.UNKNOWN,
        /** Curated ordering rank: 0 is the default salt, and null sorts last. */
        val rank: Int? = null,
        val elementalFraction: Double? = null,
    )

    /**
     * Fold the per-salt and per-isomer variants of one route into a single
     * [SubstanceRoute].
     *
     * When salt-tagged variants exist they become [SubstanceRoute.saltForms],
     * ordered with the default first, and the route's top-level unit, doses and
     * duration mirror that default so salt-unaware code transparently gets the
     * default form. With no salt tags the single base variant populates the route
     * directly, and `saltForms` stays null.
     *
     * Ported from `makeRoute`, which is the single place a variant is mirrored
     * into the top-level route — the alternative, mirroring at each call site, is
     * how a route's headline numbers and its default form drift apart.
     *
     * `protocolDosing` and `durationOfAction` are left unset. Upstream attaches
     * them afterwards, on the detail path only, because their model initializers
     * are main-actor isolated and they never appear in the browse path — porting
     * that split is the next piece of this layer, not something this method
     * should guess at.
     */
    internal fun makeRoute(
        route: RouteOfAdministration,
        variants: List<RouteVariant>,
    ): SubstanceRoute {
        fun baseRoute() = SubstanceRoute(
            route = route,
            unit = variants.firstOrNull()?.unit ?: "mg",
            doses = variants.firstOrNull()?.doses ?: DoseRange(),
            doseContext = variants.firstOrNull()?.doseContext ?: DoseContext.UNKNOWN,
            duration = variants.firstOrNull()?.duration,
        )

        val hasSalt = variants.any { it.salt != null }
        val hasIsomer = variants.any { it.isomer != null }
        if (!hasSalt && !hasIsomer) return baseRoute()

        // Selectable forms. An isomer family keeps ALL variants, because the
        // racemic parent coexists with its enantiomers and each has its own
        // ladder; a salt-only substance keeps just the salt-tagged variants.
        val forms = if (hasIsomer) variants else variants.filter { it.salt != null }

        // Racemic first, which is the sensible default, then the curated
        // salt_rank, then the label — a data-driven rather than alphabetical
        // default.
        val ordered = forms.sortedWith(
            compareBy(
                { if (it.isomer == null) 0 else 1 },
                { it.rank ?: Int.MAX_VALUE },
                { it.salt ?: "" },
                { it.isomer ?: "" },
            ),
        )
        val first = ordered.firstOrNull() ?: return baseRoute()

        return SubstanceRoute(
            route = route,
            unit = first.unit,
            doses = first.doses,
            doseContext = first.doseContext,
            duration = first.duration,
            saltForms = ordered.map {
                DoseVariant(
                    saltForm = it.salt,
                    isomer = it.isomer,
                    isomerDisplayName = it.isomerDisplayName,
                    unit = it.unit,
                    doses = it.doses,
                    duration = it.duration,
                    elementalFraction = it.elementalFraction,
                )
            },
        )
    }

    // MARK: - Interaction reads

    /*
     * Ported from `SubstanceReadModel+DeepPharmacology.swift` (the two override
     * tables and the class-pair rules), `SubstanceReadModel+PKInteractions.swift`
     * (the pharmacokinetic rows) and `SubstanceReadModel+MetabolicModulation.swift`
     * (the modulators and the tag-derived edges). They are grouped here rather
     * than in a fourth file for the same reason the checker keeps its halves
     * together: the six reads exist for one consumer, and a change to any of them
     * is a change to what the checker can say.
     *
     * Three of the six are whole-table reads with no source filter, which is
     * unusual for this layer — see each method for why.
     */

    /**
     * Every `interaction_rules` row — the severity ladder's only source.
     *
     * **Deliberately not filtered by enabled sources**, matching upstream. The
     * checker is the one consumer, and it needs the whole matrix: the table's two
     * sources (`piru-curated` for the 87 adjudicated pairs, `tripsit` for the 12
     * the curation does not reach) are not two opinions about the same pairs —
     * they are disjoint coverage. Dropping one would not hide a row behind a
     * ranking, it would remove the only rule a pair has.
     *
     * Rows that cannot be read are dropped rather than defaulted. A pair whose
     * severity is an unknown word is one the checker cannot rank, and filing it
     * under `caution` would turn an unread word into "mild".
     */
    fun classInteractionRules(): List<ClassInteractionRule> = db.query(
        """
        SELECT class_a, class_b, severity, note
          FROM interaction_rules
        """,
    ).mapNotNull { row ->
        val classA = row.string("class_a") ?: return@mapNotNull null
        val classB = row.string("class_b") ?: return@mapNotNull null
        ClassInteractionRule(
            classA = classA,
            classB = classB,
            severity = row.string("severity").orEmpty(),
            note = row.string("note").orEmpty(),
        )
    }

    /**
     * `substance_interaction_classes` as name → classes, expanded across aliases.
     *
     * A row linked to a catalog substance applies to that substance's canonical
     * name and to every alias, so a dose logged as "Nembutal" or "Xanax" carries
     * the same class as the spelling the override was written under. A row the
     * catalog has no substance for (15 of the 213) still answers under its own
     * spelling: a person can log a name the library does not have, and the
     * overrides that matter most — xylazine, the rarer barbiturates and
     * beta-blockers — are exactly those.
     *
     * An explicit row always beats an alias expansion, so an alias shared between
     * two overridden substances cannot decide by row order.
     *
     * The expanded list can carry a class twice when two override rows link to the
     * same substance and it is reached through an alias — Citalopram, whose
     * `citalopram` and `escitalopram` rows both point at it, so "Lexapro" resolves
     * to `[ssri, ssri]`. That is upstream's behaviour, kept: the checker takes the
     * worst rule across the list and a detail screen prints it as written.
     *
     * The per-class `rank` decides the order within one name's list (0 is the
     * dominant class); the checker takes the worst rule across all of them, so
     * the order is presentation only — but it is the order upstream emits, and
     * the list is what a detail screen prints.
     */
    fun substanceInteractionClasses(): Map<String, List<DrugClass>> {
        val explicit = mutableMapOf<String, MutableList<DrugClass>>()
        val classesBySubstance = mutableMapOf<Long, MutableList<DrugClass>>()

        for (row in db.query(
            """
            SELECT name, drug_class, substance_id
              FROM substance_interaction_classes
             ORDER BY name, rank
            """,
        )) {
            val name = row.string("name") ?: continue
            val drugClass = DrugClass.fromRaw(row.string("drug_class").orEmpty()) ?: continue
            explicit.getOrPut(name) { mutableListOf() } += drugClass
            row.long("substance_id")?.let { id ->
                classesBySubstance.getOrPut(id) { mutableListOf() } += drugClass
            }
        }
        if (classesBySubstance.isEmpty()) return explicit

        // Row ids from our own Int64 column, never user input.
        val ids = classesBySubstance.keys.joinToString(", ")
        val expanded = mutableMapOf<String, List<DrugClass>>()
        for (row in db.query(
            """
            SELECT id AS substance_id, canonical_name AS name
              FROM substances WHERE id IN ($ids)
            UNION ALL
            SELECT substance_id, alias AS name
              FROM aliases WHERE substance_id IN ($ids)
            UNION ALL
            SELECT substance_id, alias_normalized AS name
              FROM aliases WHERE substance_id IN ($ids)
            """,
        )) {
            val id = row.long("substance_id") ?: continue
            val name = row.string("name") ?: continue
            val classes = classesBySubstance[id] ?: continue
            // A later spelling of the same substance overwrites an earlier one, so
            // the alias tables' order decides, not the query planner's.
            expanded[name.lowercase()] = classes
        }
        return expanded + explicit
    }

    /**
     * `category_interaction_classes` as the category's raw value → the class it
     * falls back to.
     *
     * 29 rows, and no source column: a category is a build-level classification,
     * not a sourced claim. A category this table does not name is left out, and
     * the checker's own `.other` default takes over — that default lives there
     * because it is what "unmapped" means, not a value someone chose.
     */
    fun categoryInteractionClasses(): Map<String, DrugClass> {
        val out = mutableMapOf<String, DrugClass>()
        for (row in db.query("SELECT category, drug_class FROM category_interaction_classes")) {
            val category = row.string("category") ?: continue
            val drugClass = DrugClass.fromRaw(row.string("drug_class").orEmpty()) ?: continue
            out[category] = drugClass
        }
        return out
    }

    /**
     * The `drug_interactions_pk` rows for one substance, highest-evidence source
     * first.
     *
     * **Fail closed**, unlike the prose resolvers: these rows feed the checker's
     * finding list, so a source the user disabled must not leak a claim about
     * their exposure in.
     *
     * The ordering ends at `with_substance` rather than at the source rank, which
     * is upstream's order and is deliberate: within one source the counterpart
     * name is the stable key, so two rows from the same paper do not swap places
     * between runs. `with_substance` is free text and not unique, so this does not
     * fully pin the order — but it pins everything the rank alone would leave to
     * the scan.
     *
     * `priorityCaseSQL("src")` is passed the alias explicitly: this statement
     * joins `sources` once, as `src`, and a fragment that hard-codes its alias is
     * the bug that made `metabolismRows` die only on device.
     */
    fun pkInteractionRows(substanceID: Long): List<PKInteractionHit> = db.query(
        """
        SELECT d.id, d.with_substance, d.mechanism, d.ki_um, d.clinical_effect,
               src.slug AS source_slug, c.doi, c.pmid
          FROM drug_interactions_pk d
          JOIN sources src ON src.id = d.source_id
          LEFT JOIN citations c ON c.id = d.citation_id
         WHERE d.substance_id = ?
           AND src.slug IN ($enabledSourceListSQL)
         ORDER BY ${priority.priorityCaseSQL("src")} ASC, d.with_substance ASC
        """,
        listOf(substanceID),
    ).mapNotNull { row ->
        val id = row.long("id") ?: return@mapNotNull null
        val withSubstance = row.string("with_substance") ?: return@mapNotNull null
        PKInteractionHit(
            id = id,
            withSubstance = withSubstance,
            mechanism = row.string("mechanism"),
            kiMicromolar = row.double("ki_um"),
            labeledEffect = row.string("clinical_effect"),
            sourceSlug = row.string("source_slug").orEmpty(),
            doi = row.string("doi"),
            pmid = row.long("pmid")?.toInt(),
        )
    }

    /**
     * The whole `tag_enzyme_interactions` table with both names resolved to their
     * canonical spellings.
     *
     * 285 rows, no source column — the pipeline materializes them from the CYP
     * tags at build time, so there is no second source's value to rank against.
     * The join is on substance ids and the returned names are the *canonical*
     * ones, which is what the checker indexes on: a dose logged as "Wellbutrin"
     * must find the same victims "Bupropion" does.
     */
    fun tagEnzymeRows(): List<TagEnzymeInteraction> = db.query(
        """
        SELECT p.canonical_name AS perpetrator_name,
               v.canonical_name AS victim_name,
               tei.enzyme, tei.direction, tei.strength, tei.victim_type
          FROM tag_enzyme_interactions tei
          JOIN substances p ON p.id = tei.perpetrator_id
          JOIN substances v ON v.id = tei.victim_id
        """,
    ).mapNotNull { row ->
        val perpetrator = row.string("perpetrator_name") ?: return@mapNotNull null
        val victim = row.string("victim_name") ?: return@mapNotNull null
        val enzyme = row.string("enzyme") ?: return@mapNotNull null
        val direction = row.string("direction") ?: return@mapNotNull null
        val victimType = row.string("victim_type") ?: return@mapNotNull null
        TagEnzymeInteraction(
            perpetratorName = perpetrator,
            victimName = victim,
            enzyme = enzyme,
            direction = direction,
            strength = row.string("strength"),
            victimType = victimType,
        )
    }

    /**
     * The whole `enzyme_modulators` table (11 rows) in curated rank order, with
     * each rule's matcher names attached.
     *
     * A row whose origin, enzyme, direction or strength does not decode is
     * skipped: the readout renders every one of those as a word in a sentence, so
     * a value the app cannot name has nothing to render and must not reach the
     * screen half-formed. Rows without a `display_name` or `user_note` are also
     * skipped — the readout IS a sentence, and a rule with no sentence has
     * nothing to show.
     *
     * The matchers come from `pharmacology_matchers` under the `enzyme-modulator`
     * relation. The relation is always part of the query rather than a filter the
     * caller is trusted to apply, because that table also holds
     * `combination-precursor` rows whose names must never be mixed in here.
     */
    fun enzymeModulatorRows(): List<EnzymeModulator> {
        val matchers = enzymeModulatorMatchers()
        return db.query(
            """
            SELECT modulator_id, origin, enzyme, direction, strength, confidence,
                   display_name, user_note
              FROM enzyme_modulators
             ORDER BY rank ASC
            """,
        ).mapNotNull { row ->
            val id = row.string("modulator_id") ?: return@mapNotNull null
            val origin = ModulatorOrigin.fromToken(row.string("origin").orEmpty()) ?: return@mapNotNull null
            val enzyme = Enzyme.fromToken(row.string("enzyme").orEmpty()) ?: return@mapNotNull null
            val direction = ModulationDirection.fromToken(row.string("direction").orEmpty())
                ?: return@mapNotNull null
            val strength = ModulationStrength.fromToken(row.string("strength").orEmpty())
                ?: return@mapNotNull null
            val displayName = row.string("display_name")
            if (displayName.isNullOrEmpty()) return@mapNotNull null
            val userNote = row.string("user_note")
            if (userNote.isNullOrEmpty()) return@mapNotNull null
            EnzymeModulator(
                id = id,
                origin = origin,
                enzyme = enzyme,
                direction = direction,
                strength = strength,
                confidence = row.string("confidence")?.let { ConfidenceTier.fromGrade(it) }
                    ?: ConfidenceTier.UNVERIFIED,
                // Slot 0 for every curated row; the column exists because the
                // combination-precursor relation uses higher slots.
                matchers = matchers[id].orEmpty(),
                displayName = displayName,
                userNote = userNote,
            )
        }
    }

    /** One relation's matcher names as owner id → the names, in curated order. */
    private fun enzymeModulatorMatchers(): Map<String, List<String>> {
        val out = mutableMapOf<String, MutableList<String>>()
        for (row in db.query(
            """
            SELECT owner_id, matcher
              FROM pharmacology_matchers
             WHERE relation = ?
             ORDER BY owner_id ASC, slot ASC, matcher ASC
            """,
            listOf("enzyme-modulator"),
        )) {
            val owner = row.string("owner_id") ?: continue
            val matcher = row.string("matcher") ?: continue
            out.getOrPut(owner) { mutableListOf() } += matcher
        }
        return out
    }

    // MARK: - Equivalence tables (the converters)

    /**
     * One `opioid_mme` row, with the name the converter lists it under.
     *
     * [name] is lowercased to match upstream's identifier, which the converter's
     * picker persists; [displayName] is what a reader sees.
     */
    data class OpioidMmeRowEntry(
        val name: String,
        val displayName: String,
        val convertibility: OpioidConvertibility,
        /**
         * Morphine-mg per 1 mg, or null for every row that is not
         * [OpioidConvertibility.LINEAR]. Read only for a linear row, so a stray
         * number on an un-convertible one can never reach the arithmetic.
         */
        val mmePerMg: Double?,
        /** The catalog's own note on the row, which is where the not-convertible reasons are written out. */
        val notes: String?,
    )

    /**
     * Every opioid carrying an MME row, in curated rank order — morphine, the
     * reference standard, leads.
     *
     * ## The un-convertible rows are kept, deliberately
     * This is the **display** read, and it is the opposite of [opioidMme]'s
     * discipline. [opioidMme] returns null for anything not linear because a
     * factor that does not exist must never reach a dose conversion. Here the
     * three un-convertible opioids — methadone, transdermal fentanyl,
     * buprenorphine — are the rows a reader most needs an answer about, and the
     * answer is *why* no figure is shown. Dropping them would leave a table that
     * silently omits them.
     *
     * ## Why methadone is refused rather than converted
     * CDC 2022 does publish a single factor for methadone (4.7). Piru declines to
     * use it: methadone's half-life is long and variable, and its peak
     * respiratory-depressant effect arrives later and lasts longer than its peak
     * analgesia, so a converted dose can read as adequate while the danger is
     * still accumulating. The refusal is the safety property; do not "restore"
     * the factor.
     */
    fun opioidMmeTable(): List<OpioidMmeRowEntry> {
        if (order.isEmpty()) return emptyList()
        return db.query(
            """
            SELECT s.canonical_name AS name, s.display_name AS display_name,
                   o.mme_per_mg AS mme_per_mg, o.convertibility AS convertibility,
                   o.notes AS notes
              FROM (
                SELECT om.*, ROW_NUMBER() OVER (
                    PARTITION BY om.substance_id
                    ORDER BY $priorityCaseSQL ASC, om.rowid) AS rn
                  FROM opioid_mme om
                  JOIN sources src ON src.id = om.source_id
                 WHERE src.slug IN ($enabledSourceListSQL)
              ) o
              JOIN substances s ON s.id = o.substance_id
             WHERE o.rn = 1
             ORDER BY o.rank ASC
            """,
        ).mapNotNull { row ->
            val name = row.string("name") ?: return@mapNotNull null
            val convertibility =
                OpioidConvertibility.fromWire(row.string("convertibility")) ?: return@mapNotNull null
            OpioidMmeRowEntry(
                name = name.lowercase(),
                displayName = row.string("display_name") ?: name,
                convertibility = convertibility,
                mmePerMg = if (convertibility == OpioidConvertibility.LINEAR) {
                    row.double("mme_per_mg")
                } else {
                    null
                },
                notes = row.string("notes"),
            )
        }
    }

    /** One benzodiazepine beside the `diazepam_equivalents` row the converter shows. */
    data class BenzoEquivalentEntry(
        val name: String,
        val displayName: String,
        val equivalent: DiazepamEquivalent,
    ) {
        /**
         * Milligrams of diazepam equivalent to **1 mg** of this benzodiazepine,
         * or null when the cited prose did not parse to two usable numbers.
         */
        val diazepamPerMg: Double?
            get() {
                val dose = equivalent.doseMg ?: return null
                val diazepam = equivalent.equivalentDiazepamMg ?: return null
                if (dose <= 0 || diazepam <= 0) return null
                return diazepam / dose
            }
    }

    /**
     * Every diazepam-equivalence row, name-sorted, highest-priority source per
     * substance.
     *
     * The citation flag on each row is read from `citation_id`, which is the
     * pipeline's record of whether the shipped number agrees with the reference
     * table it is attributed to. Five benzodiazepines Ashton Table 1 omits —
     * brotizolam, etizolam, flutoprazepam, midazolam, phenazepam — therefore
     * arrive **uncited**, and the converter filters on that rather than showing a
     * number with no table behind it.
     *
     * Note the difference from [diazepamEquivalent], which resolves one substance
     * and leaves the flag at its default: the flag is not recoverable from a
     * single row's columns without the id, which is why this read selects it.
     */
    fun diazepamEquivalents(): List<BenzoEquivalentEntry> {
        if (order.isEmpty()) return emptyList()
        return db.query(
            """
            SELECT s.canonical_name AS name, s.display_name AS display_name,
                   d.dose_mg AS dose_mg, d.equivalent_diazepam_mg AS eq_mg,
                   d.display_text AS display_text, d.citation_id AS citation_id
              FROM (
                SELECT de.*, ROW_NUMBER() OVER (
                    PARTITION BY de.substance_id
                    ORDER BY $priorityCaseSQL ASC, de.rowid) AS rn
                  FROM diazepam_equivalents de
                  JOIN sources src ON src.id = de.source_id
                 WHERE src.slug IN ($enabledSourceListSQL)
              ) d
              JOIN substances s ON s.id = d.substance_id
             WHERE d.rn = 1
             ORDER BY s.canonical_name COLLATE NOCASE
            """,
        ).mapNotNull { row ->
            val name = row.string("name") ?: return@mapNotNull null
            BenzoEquivalentEntry(
                name = name,
                displayName = row.string("display_name") ?: name,
                equivalent = DiazepamEquivalent(
                    doseMg = row.double("dose_mg"),
                    equivalentDiazepamMg = row.double("eq_mg"),
                    displayText = row.string("display_text"),
                    isCited = row.long("citation_id") != null,
                ),
            )
        }
    }

    // MARK: - Class contexts (the drug-class browse)

    /** One citation behind a class's write-up. */
    data class ClassReference(
        val id: Long,
        val title: String?,
        val doi: String?,
        val pmid: Int?,
    )

    /**
     * One shared write-up over a group of substances — a class context.
     *
     * [sharedMechanism], [sharedPharmacokinetics] (`shared_pk`),
     * [sharedSafety] and [sarSummary] are authored prose, present or absent per
     * class. [siblings] are the members, popularity-ordered so the recognizable
     * ones lead; they are **not** capped, because a cap would silently shorten a
     * class and make its member count wrong besides.
     */
    data class ClassContext(
        val slug: String,
        val title: String,
        val category: SubstanceCategory?,
        val subtitle: String?,
        val sharedMechanism: String?,
        val sharedPharmacokinetics: String?,
        val sharedSafety: String?,
        val sarSummary: String?,
        val siblings: List<String>,
        val references: List<ClassReference>,
    )

    /**
     * One class's write-up, resolved by slug **or** by its display name.
     *
     * Both, rather than the slug alone, because the identifier a caller holds is
     * whatever the screen it came from had: the browse list carries the slug,
     * while a name read off a substance's page is the title. Resolving either
     * here keeps that difference out of the UI, and a caller cannot get a blank
     * screen for passing the name it could see.
     *
     * No source filter and no enabled-source gate: this is authored reference
     * prose with its own citation list, not a structured value that drives a
     * calculation. The posture is the one the prose resolvers take.
     *
     * A class whose four shared fields are all null yields nothing to read, and
     * upstream excludes those from the browse list for that reason — but this
     * resolves one class a caller already named, so it is returned rather than
     * treated as missing.
     */
    fun classContext(className: String): ClassContext? {
        val row = db.queryOne(
            """
            SELECT c.id, c.slug, c.display_name, c.subtitle, c.category,
                   c.shared_mechanism, c.shared_pk, c.shared_safety, c.sar_summary
              FROM class_contexts c
             WHERE c.slug = ? OR c.display_name = ? COLLATE NOCASE
             LIMIT 1
            """,
            listOf(className, className),
        ) ?: return null
        val classID = row.long("id") ?: return null
        val slug = row.string("slug") ?: return null
        val categoryRaw = row.string("category")

        val siblings = db.query(
            """
            SELECT s.canonical_name AS name
              FROM substance_classes sc
              JOIN substances s ON s.id = sc.substance_id
             WHERE sc.class_context_id = ?
             ORDER BY s.popularity DESC, s.canonical_name
            """,
            listOf(classID),
        ).mapNotNull { it.string("name") }

        val references = db.query(
            """
            SELECT ci.id, ci.title, ci.doi, ci.pmid
              FROM class_citations cc
              JOIN citations ci ON ci.id = cc.citation_id
             WHERE cc.class_context_id = ?
             ORDER BY ci.year DESC NULLS LAST
            """,
            listOf(classID),
        ).mapNotNull { ref ->
            val id = ref.long("id") ?: return@mapNotNull null
            ClassReference(
                id = id,
                title = ref.string("title"),
                doi = ref.string("doi"),
                pmid = ref.long("pmid")?.toInt(),
            )
        }

        return ClassContext(
            slug = slug,
            title = row.string("display_name") ?: slug,
            category = categoryRaw?.let { SubstanceCategory.fromWire(it) },
            subtitle = row.string("subtitle"),
            sharedMechanism = row.string("shared_mechanism"),
            sharedPharmacokinetics = row.string("shared_pk"),
            sharedSafety = row.string("shared_safety"),
            sarSummary = row.string("sar_summary"),
            siblings = siblings,
            references = references,
        )
    }

    /**
     * Every class write-up in the catalog, ordered by display name.
     *
     * The browse read: what a reader scrolls when they do not yet know which
     * class they want. `COLLATE NOCASE` on `display_name`, so the list is
     * alphabetical rather than by member count — a reader looking for
     * "Benzodiazepines" knows the name, not how many substances sit under it.
     *
     * ## Built on [classContext], not on a second copy of its SQL
     * Three small statements run per class. Over the shipped catalog that is one
     * hundred and fifty statements against tables holding 680 members and 267
     * citations, all of it a local file — milliseconds. The alternative is a
     * second query text that can drift from the detail screen's, which is how a
     * browse row and the screen it opens start disagreeing about which slug is
     * which class.
     *
     * Slugs are read here and bound back into [classContext]'s predicate, so
     * nothing is interpolated into SQL.
     */
    fun classContexts(): List<ClassContext> =
        db.query("SELECT slug AS slug FROM class_contexts ORDER BY display_name COLLATE NOCASE")
            .mapNotNull { it.string("slug") }
            .mapNotNull { classContext(it) }
}
