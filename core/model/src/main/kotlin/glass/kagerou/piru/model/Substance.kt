package glass.kagerou.piru.model

import java.security.MessageDigest
import java.util.UUID

/**
 * A substance, resolved from the bundled catalog.
 *
 * Ported from `Piru/Domain/Substance.swift`.
 *
 * ## One type, two read paths
 * The full record carries mechanism of action, receptor bindings, chemistry,
 * prose effects, citations and curated misconceptions — detail-page material the
 * timeline never touches. It arrives through the heavy `resolveFull` read, while
 * the dose and timeline paths build the same type from the batch projection and
 * leave those fields at their defaults. So a `Substance` from the batch path is
 * **not** the whole record, and the fields it omits are not null because the
 * catalog lacks them.
 *
 * That is the same split upstream has — `SubstanceLibrary.lookup` against
 * `resolveFull` — and the reason the two are separate reads there is cost:
 * `resolveFull` runs about twenty SQL statements per substance and is uncached.
 *
 * ## The identity is derived, not random
 * [id] is a hash of the lowercased name, so the *same* substance gets the *same*
 * id across every construction — a decode, an overlay merge, a search
 * re-resolve. A list can then reuse a row when a search narrows ("caffe" to
 * "caffei") instead of tearing down and rebuilding every row, which a fresh
 * `UUID` per construction would force. Canonical names are unique in the catalog,
 * so this stays collision-free, and equality keys on it — meaning "same
 * substance by name".
 */
data class Substance(
    val name: String,

    /**
     * A human-facing title override (e.g. "2,5-DMBZP" for the compound whose
     * canonical name is "1-(2,5-Dimethoxybenzyl) piperazine"). When set, the UI
     * shows this and demotes [name] to the subtitle; [name] stays canonical for
     * search, dedup and logging.
     */
    val displayName: String? = null,

    /**
     * The substance's title in the app's language (Ketamina, 氯胺酮, 愷他命),
     * stamped on by the catalog reader from `localized_names`.
     *
     * A field rather than a process-wide lookup, which is how the iOS side has it.
     * That is a deliberate divergence: the global there is installed once at launch
     * because a `nonisolated` computed property has no store to reach, and the same
     * reason does not apply to a Kotlin data class. Keeping it on the value also
     * means a test can set it without installing anything.
     */
    val localizedName: String? = null,

    /** The regional spelling, when the catalog carries one. Same reasoning as [localizedName]. */
    val regionalName: String? = null,

    val aliases: List<String> = emptyList(),

    /**
     * User-defined per-substance units ("1 capsule = 30 mg"), merged ahead of the
     * catalog's own colloquial units. A `var` amid the surrounding values for the
     * same reason as upstream: it is applied after construction, at the single
     * overlay choke point, and is empty for a substance built outside it.
     */
    val customUnitAliases: List<UnitAlias> = emptyList(),

    val category: SubstanceCategory,

    /** Additional browse homes for a curated multi-class compound (an empathogen that is also a stimulant). Empty for most. */
    val extraBrowseCategories: List<SubstanceCategory> = emptyList(),

    /**
     * The drug-class write-up this substance belongs to, or null when it belongs to none.
     *
     * The slug rather than the title: a slug cannot be mistranslated, and `PushRoute.DrugClass`
     * carries this value. The catalogue's `substance_classes` table already held the mapping —
     * nothing read it, so a substance page could name its class and had no way to open it.
     */
    val classContextSlug: String? = null,

    val defaultRoute: RouteOfAdministration,
    val routes: List<SubstanceRoute>,

    /**
     * The union of PsychonautWiki effect terms across enabled sources, localized
     * through the controlled vocabulary. The flat list drives browse and search;
     * the grouped view is a separate read for the "All effects" screen.
     */
    val effects: List<String> = emptyList(),

    /** Richer per-effect prose, one entry per effect the catalog documents. */
    val subjectiveEffects: List<SubjectiveEffect> = emptyList(),

    /** How tolerance builds and clears, or null when the catalog carries no complete row. */
    val toleranceInfo: ToleranceInfo? = null,

    /** Elimination half-life in minutes, or null when the catalog carries none. */
    val halfLifeMinutes: Double? = null,

    /** Slugs of the sources that contributed any fact for this substance — the attribution row. */
    val sources: List<String> = emptyList(),

    /**
     * The mechanism card's content — summary prose and the receptor bindings
     * behind it. Lazily resolved on the detail screen, because the bindings query
     * pulls a 40-row ranked list that no list view needs.
     */
    val mechanismOfAction: MechanismOfAction? = null,

    /**
     * How the compound is surfaced under the display policy.
     *
     * This is what decides whether a dose ladder or a duration profile may be
     * shown at all — see [CompoundDisplayClass.showsDoseLadder]. It defaults to
     * [CompoundDisplayClass.RECREATIONAL], matching the read layer's own default
     * for a row the column does not fill in.
     */
    val displayClass: CompoundDisplayClass = CompoundDisplayClass.RECREATIONAL,

    /** Regulatory status string as the catalog stores it, or null. */
    val regulatoryStatus: String? = null,

    /**
     * Whether the catalog flagged this substance's duration data as implausible
     * (over 24 hours, from a source that clearly meant something else).
     *
     * Gates the duration row for over-the-counter compounds, where the ladder is
     * shown but a wrong duration would not be.
     */
    val durationImplausible: Boolean = false,

    /** Cross-benzodiazepine equivalency. Present only for benzodiazepines; the only such data in the catalog. */
    val diazepamEquivalent: DiazepamEquivalent? = null,

    /**
     * The PSID family — the stable identity anchor. Fold-family siblings (a
     * racemate and its enantiomers, immediate and extended release) share it, so
     * it is **not** unique per row; the full form identity is this plus the facet
     * scalars.
     */
    val substanceUID: String? = null,

    // MARK: - Chemical identity

    val cas: String? = null,
    val inchikey: String? = null,
    val formula: String? = null,
    val pubchemCID: Int? = null,

    /** Curated popularity score, which drives the category-browse sort. Zero for the long tail. */
    val popularity: Double = 0.0,

    /**
     * Whether the build flagged this row as a stub — reachable by name, with no
     * dose, duration, PK or half-life data behind it.
     *
     * 415 of the shipped catalog's 1,689 substances are stubs, and name resolution
     * demotes them so a bare stub does not outrank a substance that has content.
     */
    val isStub: Boolean = false,

    /** Metadata tags across enabled sources, with the engine-consumed ones hidden. */
    val tags: List<String> = emptyList(),

    /** Molar mass, g/mol. */
    val molarMass: Double? = null,

    /** Peptide- and biologic-specific reference data. Presence switches the detail UI to a peptide presentation. */
    val peptideProfile: PeptideProfile? = null,

    /** Distinct primary references for the compound, deduplicated and capped. */
    val references: List<Citation> = emptyList(),

    val drugCommunitySlug: String? = null,
    val freeodwikiSlug: String? = null,
    val dosewikiSlug: String? = null,

    /** Long-form overview prose, resolved locale-first. */
    val overview: SubstanceOverview? = null,

    val smiles: String? = null,
    val iupacName: String? = null,

    /** Predicted and forensic descriptors. Never clinical values — see the type's own note. */
    val physicochemical: Physicochemical? = null,

    /** Curated aliases worth surfacing ahead of the rest, popular substances only. */
    val popularAliases: List<String> = emptyList(),

    /** Evidence-checked corrections to common claims. Curated, popular substances only. */
    val misconceptions: List<MythBust> = emptyList(),

    /** Hand-curated notable combinations — what to know *before* taking it. */
    val combinations: List<Combination> = emptyList(),

    /** Thermoregulation and hydration guidance. Present only for substances that raise body temperature or alter fluid balance. */
    val waterHeat: WaterHeatGuidance? = null,
) {
    /** This substance's deterministic identity; see the class note. */
    val id: UUID = deterministicID(name)

    /**
     * What to title this substance.
     *
     * The user's own relabel outranks every default: naming a medication the way
     * you say it is a stronger signal than the name your language or region
     * prefers, and the substances carrying such rows are exactly the ones someone
     * relabels.
     *
     * A leading emoji is split off, so a curated "🍰 Cake" titles as "Cake".
     */
    val displayTitle: String
        get() = displayName
            ?: localizedName
            ?: regionalName
            ?: name

    /** [displayTitle] with any leading pictograph removed, and the pictograph itself. */
    val titleAndPictograph: Pair<String, String?>
        get() = strippingLeadingPictograph(displayTitle)

    // MARK: - Route accessors

    /**
     * The route's **default-form** ladder — the top-level fields, which mirror the
     * first salt form.
     *
     * These route-only accessors exist so form-unaware code stays correct without
     * threading a salt through every call site. Any surface that displays or
     * computes for a *specific* logged or selected form must use the form
     * overloads below instead, or it will silently show the default form's numbers
     * for a different one.
     */
    fun doseRange(route: RouteOfAdministration): DoseRange? =
        routes.firstOrNull { it.route == route }?.doses

    fun unit(route: RouteOfAdministration): String =
        routes.firstOrNull { it.route == route }?.unit ?: defaultUnit

    fun duration(route: RouteOfAdministration): DurationProfile? =
        routes.firstOrNull { it.route == route }?.duration

    val defaultUnit: String
        get() = routes.firstOrNull { it.route == defaultRoute }?.unit
            ?: routes.firstOrNull()?.unit
            ?: "mg"

    /** True when no route carries any usable dose data at all. */
    val hasNoDoseData: Boolean
        get() = routes.isEmpty() || routes.all { !it.doses.hasAnyValue }

    // MARK: - Form resolution

    /**
     * The dose-bearing variant of a route matching **both** form axes — the salt
     * counter-ion and the stereoisomer. Null when the route has no variant list or
     * no exact match, so callers fall back to the route's default-form fields.
     *
     * A salt-only substance carries a null isomer on every variant, so passing a
     * null isomer reduces to a salt match, and the other way round: the two axes
     * stay independent.
     */
    private fun doseVariant(
        route: RouteOfAdministration,
        saltForm: String?,
        isomer: String?,
    ): DoseVariant? = routes.firstOrNull { it.route == route }
        ?.saltForms
        ?.firstOrNull { it.saltForm == saltForm && it.isomer == isomer }

    /**
     * The ladder for a route, narrowed to a specific form when given and present.
     * Falls back to the route's default ladder when the form is unspecified or not
     * found, so form-unaware callers stay correct.
     */
    fun doseRange(
        route: RouteOfAdministration,
        saltForm: String?,
        isomer: String? = null,
    ): DoseRange? {
        val r = routes.firstOrNull { it.route == route } ?: return null
        return doseVariant(route, saltForm, isomer)?.doses ?: r.doses
    }

    /** The unit for a route, narrowed to a specific form when present. Forms may differ, e.g. elemental mg against compound mg. */
    fun unit(
        route: RouteOfAdministration,
        saltForm: String?,
        isomer: String? = null,
    ): String {
        val r = routes.firstOrNull { it.route == route } ?: return defaultUnit
        return doseVariant(route, saltForm, isomer)?.unit ?: r.unit
    }

    /** The duration for a route, narrowed to a specific form when present. */
    fun duration(
        route: RouteOfAdministration,
        saltForm: String?,
        isomer: String? = null,
    ): DurationProfile? {
        val r = routes.firstOrNull { it.route == route } ?: return null
        return doseVariant(route, saltForm, isomer)?.duration ?: r.duration
    }

    /**
     * Mass fraction of the elemental active for a salt form on a route — 0.14 for
     * magnesium glycinate. Null when unknown, and null whenever no salt is named,
     * since the fraction only means something about a specific salt.
     */
    fun elementalFraction(
        route: RouteOfAdministration,
        saltForm: String?,
        isomer: String? = null,
    ): Double? {
        if (saltForm == null) return null
        return doseVariant(route, saltForm, isomer)?.elementalFraction
    }

    /**
     * The amount of *elemental* active in [amount] of the given form, or null when
     * the salt has no known elemental fraction — the common case, so the UI shows
     * the breakdown only where it is meaningful.
     */
    fun elementalAmount(
        amount: Double,
        route: RouteOfAdministration,
        saltForm: String?,
        isomer: String? = null,
    ): Double? = elementalFraction(route, saltForm, isomer)?.let { amount * it }

    /** Distinct salt forms across all routes, default first. Empty for the vast majority. */
    val availableSaltForms: List<String>
        get() {
            val seen = mutableSetOf<String>()
            val ordered = mutableListOf<String>()
            for (route in routes) {
                for (variant in route.saltForms.orEmpty()) {
                    val salt = variant.saltForm ?: continue
                    if (seen.add(salt)) ordered += salt
                }
            }
            return ordered
        }

    /** Salt forms available for one route, in stored order. The picker shows only when this has more than one entry. */
    fun saltForms(route: RouteOfAdministration): List<String> {
        val seen = mutableSetOf<String>()
        val ordered = mutableListOf<String>()
        for (variant in routes.firstOrNull { it.route == route }?.saltForms.orEmpty()) {
            val salt = variant.saltForm ?: continue
            if (seen.add(salt)) ordered += salt
        }
        return ordered
    }

    /** The default route's first salt form, else any route's. Null when the substance has no salt dimension. */
    val defaultSaltForm: String?
        get() = (routes.firstOrNull { it.route == defaultRoute } ?: routes.firstOrNull())
            ?.saltForms
            ?.mapNotNull { it.saltForm }
            ?.firstOrNull()

    /** Distinct isomer codes across all routes, excluding the racemic default. Empty for the overwhelming majority. */
    val availableIsomers: List<String>
        get() {
            val seen = mutableSetOf<String>()
            val ordered = mutableListOf<String>()
            for (route in routes) {
                for (variant in route.saltForms.orEmpty()) {
                    val isomer = variant.isomer ?: continue
                    if (seen.add(isomer)) ordered += isomer
                }
            }
            return ordered
        }

    /** The recognized name titling an isomer code ("Dexmethylphenidate", "Esketamine"), or null. */
    fun isomerDisplayName(code: String): String? = routes
        .flatMap { it.saltForms.orEmpty() }
        .firstOrNull { it.isomer == code }
        ?.isomerDisplayName

    /** The default isomer for a route: the sole isomer when the route carries exactly one and no racemic form. */
    fun defaultIsomer(route: RouteOfAdministration): String? {
        val variants = routes.firstOrNull { it.route == route }?.saltForms ?: return null
        // A racemic variant means racemic is the default, so there is none to pick.
        if (variants.any { it.isomer == null }) return null
        return variants.firstOrNull()?.isomer
    }

    // MARK: - Duration resolution

    /**
     * Best available duration: the exact form, then the route, then a single
     * route's data when only one route carries any (implying it is generic).
     * Null when several routes have distinct durations — guessing there is worse
     * than answering nothing.
     */
    fun resolveDuration(
        route: RouteOfAdministration,
        saltForm: String?,
        isomer: String?,
    ): DurationProfile? =
        duration(route, saltForm, isomer) ?: resolveDuration(route)

    fun resolveDuration(route: RouteOfAdministration): DurationProfile? {
        duration(route)?.let { return it }
        val withDuration = routes.filter { it.duration != null }
        // A single route with data is likely generic, so it is safe to use for any route.
        return if (withDuration.size == 1) withDuration.first().duration else null
    }

    /**
     * The longest total duration any route claims, or null when no route carries a
     * profile at all — the chronic medications, which have a half-life and no
     * acute table.
     *
     * The **maximum** across routes rather than one resolved route, because a
     * reader looking at a table of every route would compare against its longest
     * row. Taking the max is also the conservative choice, since it makes a
     * "lasts beyond what is shown" claim harder to make rather than easier.
     */
    val longestRouteDurationMinutes: Double?
        get() = routes.mapNotNull { it.duration?.estimatedTotalMinutes }.maxOrNull()

    /**
     * The duration to use when **drawing a dose on a timeline**. Null means do not
     * draw a curve — render a point-in-time marker instead.
     *
     * Null for long-acting and maintenance compounds whose modeled effect exceeds
     * [MAX_ACUTE_TIMELINE_MINUTES]: their acute curve would be a flat line
     * stretching the shared x-axis and crushing every real curve beside it. The
     * decision is taken from the profile that *would* be drawn, so it is correct
     * for custom substances and route-specific profiles that a precomputed flag
     * can miss.
     *
     * This is deliberately **not** `resolveDuration`: that returns the raw profile
     * regardless, which is what a detail table wants and what a graph must not use.
     */
    fun timelineDuration(
        route: RouteOfAdministration,
        saltForm: String? = null,
        isomer: String? = null,
    ): DurationProfile? {
        val profile = resolveDuration(route, saltForm, isomer)
        if (profile != null && profile.estimatedTotalMinutes > 0 &&
            profile.estimatedTotalMinutes <= MAX_ACUTE_TIMELINE_MINUTES
        ) {
            return profile
        }
        // The requested route has no usable acute profile, but the substance may
        // have one on another route. Borrowing it is far closer to reality than
        // falling through to a half-life synthesis, whose elimination half-life can
        // vastly outlast subjective effects — logging amphetamine rectally would
        // otherwise synthesize a ~45 h curve from a ~10 h half-life, when the felt
        // effect is the ~6–8 h oral curve.
        return representativeAcuteDuration()
    }

    /**
     * A stand-in acute profile for routes that lack their own: the default route's
     * when it is a sane acute curve, otherwise the **shortest** across all routes
     * — the most conservative, and so the least likely to overstate how long
     * effects last. Null when no route has an acute curve.
     */
    private fun representativeAcuteDuration(): DurationProfile? {
        fun acute(profile: DurationProfile?): DurationProfile? {
            if (profile == null) return null
            val total = profile.estimatedTotalMinutes
            return if (total > 0 && total <= MAX_ACUTE_TIMELINE_MINUTES) profile else null
        }
        acute(duration(defaultRoute))?.let { return it }
        return routes.mapNotNull { acute(it.duration) }
            .minByOrNull { it.estimatedTotalMinutes }
    }

    // MARK: - Search and ordering

    /**
     * True when the query appears in the name or any alias, case-insensitively.
     *
     * An empty query is **false**, and the guard is load-bearing rather than a
     * nicety: Kotlin's `contains("")` is true for every string, so without it
     * every substance would match an empty search box. Swift's `contains` answers
     * false there — a search for an empty range finds nothing — which is the
     * behaviour the upstream spec pins, so the two platforms would otherwise
     * disagree on the single most common input a search field sees.
     */
    fun matches(query: String): Boolean {
        if (query.isEmpty()) return false
        val q = query.lowercase()
        return name.lowercase().contains(q) || aliases.any { it.lowercase().contains(q) }
    }

    /** All routes ordered: this substance's own first, then the remaining system routes. */
    val orderedRoutes: List<RouteOfAdministration>
        get() {
            val own = routes.map { it.route }
            return own + RouteOfAdministration.entries.filter { it !in own }
        }

    companion object {
        /**
         * The longest total effect a dose can have and still be drawn as a curve.
         * Beyond this an acute onset-to-offset shape is the wrong model — the
         * effect outlasts any sane graph window — so the dose becomes a marker.
         *
         * Matches the data pipeline's `duration_implausible` threshold.
         */
        const val MAX_ACUTE_TIMELINE_MINUTES: Double = 24 * 60.0

        /**
         * A deterministic identity from the canonical name, stamped as a
         * well-formed RFC-4122 version-4 UUID so it is a UUID in more than name.
         */
        fun deterministicID(name: String): UUID {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest(name.lowercase().toByteArray(Charsets.UTF_8))
            bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
            var msb = 0L
            var lsb = 0L
            for (i in 0 until 8) msb = (msb shl 8) or (bytes[i].toLong() and 0xFF)
            for (i in 8 until 16) lsb = (lsb shl 8) or (bytes[i].toLong() and 0xFF)
            return UUID(msb, lsb)
        }

        /**
         * Split a leading emoji off a title: "🍰 Cake" gives ("Cake", "🍰"). An
         * ordinary name passes through unchanged.
         *
         * Works in **code points**, not chars. Every emoji above U+FFFF — which is
         * most of them — is a surrogate pair in a Kotlin string, so a `Char`-based
         * test reads the high surrogate (0xD83C, and so on) and reports that no
         * emoji is present. The check would then never fire for exactly the
         * titles it exists for.
         */
        fun strippingLeadingPictograph(s: String): Pair<String, String?> {
            if (s.isEmpty()) return s to null
            val codePoint = s.codePointAt(0)
            if (!isEmojiPresentation(codePoint)) return s to null
            val charCount = Character.charCount(codePoint)
            val pictograph = s.substring(0, charCount)
            return s.substring(charCount).trimStart() to pictograph
        }

        /**
         * True for a code point that defaults to emoji presentation.
         *
         * A deliberately coarse test, and coarser than the iOS one: the full emoji
         * property table is not in the JDK, and the only caller is a title
         * cosmetic. It covers the pictographic blocks a curated title would
         * actually use, while leaving ASCII, Latin and CJK text alone — which is
         * what matters.
         *
         * One consequence worth naming: a bare U+2600-27BF symbol that a font
         * renders as text by default (a check mark, an arrow) is stripped too. The
         * iOS test uses the Unicode property and would not strip it.
         */
        private fun isEmojiPresentation(codePoint: Int): Boolean = when (codePoint) {
            in 0x2600..0x27BF -> true // misc symbols and dingbats
            in 0x2B00..0x2BFF -> true // misc symbols and arrows
            in 0x1F000..0x1FFFF -> true // the emoji planes
            0x203C, 0x2049 -> true // double exclamation, interrobang
            else -> false
        }
    }
}
