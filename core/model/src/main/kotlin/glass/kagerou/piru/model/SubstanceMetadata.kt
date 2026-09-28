package glass.kagerou.piru.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Predicted and forensic physicochemical descriptors, decoded from the
 * `substances` table's chemistry columns.
 *
 * Ported from `Piru/Domain/SubstanceMetadata.swift`.
 *
 * **Not clinical values.** logP/TPSA/HBA/HBD are computed (PubChem XLogP3 /
 * NPS-DataHub) and the LD50 figures are *rodent* order-of-magnitude toxicity,
 * never a human "safe dose". The detail card surfaces them behind an explicit
 * honesty footnote, and [hasLd50] is what gates it.
 */
data class Physicochemical(
    /** Octanol/water partition coefficient (lipophilicity), computed. */
    val logP: Double? = null,
    /** Topological polar surface area, Å². */
    val tpsa: Double? = null,
    /** Hydrogen-bond acceptor count. */
    val hba: Int? = null,
    /** Hydrogen-bond donor count. */
    val hbd: Int? = null,
    /** Rodent oral LD50, mg/kg — order-of-magnitude toxicity, not a safe dose. */
    val ld50OralMgPerKg: Double? = null,
    /** Rodent dermal LD50, mg/kg — order-of-magnitude toxicity, not a safe dose. */
    val ld50DermalMgPerKg: Double? = null,
    /** Melting point, °C. */
    val meltingPointC: Double? = null,
    /** Boiling point, °C. */
    val boilingPointC: Double? = null,
) {
    /** True when at least one descriptor is populated — the card only renders when there is something to show. */
    val hasAnyValue: Boolean
        get() = logP != null || tpsa != null || hba != null ||
            hbd != null || ld50OralMgPerKg != null || ld50DermalMgPerKg != null ||
            meltingPointC != null || boilingPointC != null

    /** True when either LD50 figure is present — gates the rodent-toxicity footnote so it only shows when an LD50 is displayed. */
    val hasLd50: Boolean
        get() = ld50OralMgPerKg != null || ld50DermalMgPerKg != null
}

/**
 * How a compound is surfaced under the display policy. Baked at build time into
 * `substances.display_class`.
 *
 * Ported from `Piru/Domain/SubstanceMetadata.swift`.
 *
 * This gates **dose and duration visibility** and whether a compound appears in
 * recreational category browsing. It is the closest thing the catalog has to a
 * safety rule, which is why the three predicates below live on the type rather
 * than being re-derived at each call site.
 */
enum class CompoundDisplayClass(val wireValue: String) {
    /** Recreational use is the primary frame — full dose ladder and duration. */
    RECREATIONAL("recreational"),

    /**
     * A medical drug that PsychonautWiki/TripSit also document recreationally
     * (mirtazapine, DXM, gabapentin, …). Shown like recreational.
     */
    DUAL_USE("dual_use"),

    /**
     * Over-the-counter — the dose is on the package, so a dose may be shown
     * without a recreational signal. Duration is still suppressed when
     * implausible (over 24 h); see `Substance.durationImplausible`.
     */
    OTC("otc"),

    /** Prescription medication with no recreational value — mechanism and warnings, but **never** dose or duration. */
    MEDICAL_RX("medical_rx"),

    /** No recreational value at all (antibiotics, …). Trackable and recognisable, hidden from recreational browsing, no dose or duration. */
    NON_RECREATIONAL("non_recreational"),
    ;

    /**
     * Whether "Limited data" / "Limited human data" may be said about this
     * compound at all — before any row count is consulted.
     *
     * The label reads as a statement about the **molecule's evidence base**, not
     * about what the app happens to hold. That makes it false for anything
     * approved: atorvastatin, omeprazole, testosterone and calcium have vast
     * human literature whatever this database contains, so no row count can
     * license the phrase for them. It belongs to research chemicals with little
     * or no human data — which is exactly the recreational and dual-use half of
     * the catalog.
     */
    val mayReportLimitedData: Boolean
        get() = this == RECREATIONAL || this == DUAL_USE

    /** Dose ladder visible. Suppressed for medical and non-recreational compounds. */
    val showsDoseLadder: Boolean
        get() = this == RECREATIONAL || this == DUAL_USE || this == OTC

    /** Duration profile visible. OTC additionally requires a plausible duration — gate on `Substance.durationImplausible` at the call site. */
    val showsDuration: Boolean
        get() = this == RECREATIONAL || this == DUAL_USE || this == OTC

    /**
     * Whether this compound appears in recreational category browsing.
     * Non-recreational compounds stay searchable, for medication tracking, but
     * are not surfaced in the browse grid.
     */
    val surfacesInBrowse: Boolean
        get() = this != NON_RECREATIONAL

    companion object {
        /** Resolve a stored class name, defaulting to [RECREATIONAL] as the read layer does. */
        fun fromWire(value: String?): CompoundDisplayClass =
            entries.firstOrNull { it.wireValue == value } ?: RECREATIONAL
    }
}

/**
 * Cross-benzodiazepine dose equivalency, relative to 10 mg diazepam. Sourced
 * from the TripSit benzo dataset — the only such data the catalog carries.
 */
data class DiazepamEquivalent(
    val doseMg: Double? = null,
    val equivalentDiazepamMg: Double? = null,
    val displayText: String? = null,
    /**
     * Whether this row's number matches the reference table it is attributed to.
     *
     * The upstream dataset carries no per-value source, so the pipeline attaches
     * a citation only where the shipped value agrees with Ashton Table 1 —
     * leaving the five benzodiazepines Ashton omits (brotizolam, etizolam,
     * flutoprazepam, midazolam, phenazepam) honestly unsourced.
     */
    val isCited: Boolean = false,
)

/**
 * A primary reference for a compound — a DOI, PubMed ID, URL, or free-text
 * label ("Egrifta SmPC"). Surfaced in the detail "References" section so every
 * curated claim is traceable to its source.
 */
@Serializable
data class Citation(
    val doi: String? = null,
    val pmid: Int? = null,
    val url: String? = null,
    val title: String? = null,
) {
    /**
     * A tappable link, when the reference resolves to one. Free-text labels —
     * stored in [url] without an http scheme — return null, and the UI renders
     * them as plain text.
     */
    val resolvedUrl: String?
        get() = when {
            !doi.isNullOrEmpty() -> "https://doi.org/$doi"
            pmid != null -> "https://pubmed.ncbi.nlm.nih.gov/$pmid/"
            !url.isNullOrEmpty() && url.startsWith("http") -> url
            else -> null
        }

    /** Human-facing label. Falls back to a localized "Reference" the UI substitutes. */
    val label: String
        get() = when {
            !title.isNullOrEmpty() -> title
            !doi.isNullOrEmpty() -> "DOI $doi"
            pmid != null -> "PMID $pmid"
            !url.isNullOrEmpty() -> url
            else -> ""
        }
}

/**
 * A citation attached to a [MythBust], carrying the *role* it plays in the
 * correction — so the UI can style it, and so the source of a myth is never
 * shown as if it supported the myth.
 */
@Serializable
data class MythCitation(
    val citation: Citation,
    val role: Role,
    /** Optional one-line gloss shown beside the chip ("null in abstinent users"). */
    val note: String? = null,
) {
    /** How a reference relates to the misconception it accompanies. */
    @Serializable
    enum class Role(val wireValue: String) {
        /** Evidence that refutes the claim — the default, accent-styled chip. */
        @SerialName("refutes")
        REFUTES("refutes"),

        /**
         * The (usually retracted) source the myth originally came from, cited
         * only to discredit it. Must never be presented as supporting evidence:
         * the UI marks it "retracted" and may link the retraction notice rather
         * than the paper.
         */
        @SerialName("retractedSource")
        RETRACTED_SOURCE("retractedSource"),

        /** A dataset or registry used as evidence — a pharmacovigilance database showing zero sole-agent cases. */
        @SerialName("dataset")
        DATASET("dataset"),
        ;

        companion object {
            fun fromWire(value: String?): Role? = entries.firstOrNull { it.wireValue == value }
        }
    }
}

/** A short attributed quotation surfaced beneath a [MythBust]. Rare — reserved for flagship substances. */
@Serializable
data class PullQuote(
    val text: String,
    val attribution: String,
)

/**
 * One evidence-checked correction to a common claim about a substance.
 *
 * An uncited myth-bust is just a counter-assertion, so every one carries at
 * least one [citations] entry — enforced by `validate_curated.py` in the
 * pipeline. Curated and deliberately popular-substances-only; absent for the
 * long tail, which is correct rather than a gap.
 */
@Serializable
data class MythBust(
    /** The claim as people actually state it — "It burns holes in your brain". */
    val claim: String,
    /** The evidence-based correction. May contain Markdown `**bold**` for the load-bearing phrase. */
    val correction: String,
    /** Sources substantiating the correction. Non-empty by contract. */
    val citations: List<MythCitation> = emptyList(),
    /** A rare flagship-only pull-quote; null for the overwhelming majority. */
    val pullQuote: PullQuote? = null,
)

/**
 * One hand-curated notable combination — a row in the detail page's
 * "Combinations" section.
 *
 * Editorial content ranked by evidence rather than reputation: what to know
 * *before* taking it, complementing the interaction checker, which fires on
 * doses already logged.
 */
@Serializable
data class Combination(
    val severity: Severity,
    /** Substance or class name (e.g. "MAOIs", "Alcohol"). */
    val name: String,
    /** Plain-language explanation naming the direction of risk and why. May contain Markdown `**bold**`. */
    val description: String,
    /** Optional qualifier tag (e.g. "blunts"). */
    val note: String? = null,
) {
    /** Evidence-ranked severity tier for a combination row. */
    @Serializable
    enum class Severity(val wireValue: String) {
        /** Life-threatening; avoid entirely (e.g. MDMA + MAOIs). */
        @SerialName("danger")
        DANGER("danger"),

        /** Real risk; be careful (e.g. MDMA + alcohol). */
        @SerialName("caution")
        CAUTION("caution"),

        /** Worth knowing; not dangerous (e.g. SSRIs mostly blunt MDMA). */
        @SerialName("note")
        NOTE("note"),
        ;

        companion object {
            fun fromWire(value: String?): Severity? = entries.firstOrNull { it.wireValue == value }
        }
    }
}

/**
 * Curated thermoregulation and hydration guidance — the detail page's "Water &
 * heat" card.
 *
 * Bounded on both sides: a rate while active **and** the warning that
 * over-drinking causes hyponatremia. Only for substances that raise body
 * temperature or alter fluid balance; null for the long tail.
 */
@Serializable
data class WaterHeatGuidance(
    /** Big-number display (e.g. "≈ 1 glass / hour"). */
    val headline: String,
    /** Explanation of why, and the upper bound. May contain Markdown `**bold**`. */
    val body: String,
)

/** How a peptide or biologic is supplied — determines whether reconstitution applies and how the substance is handled. */
enum class SuppliedForm(val wireValue: String) {
    /** Freeze-dried powder in an mg vial — must be reconstituted before use. */
    LYOPHILIZED_VIAL("lyophilized_vial"),

    /** Ready-to-inject solution (prefilled pen or vial). */
    SOLUTION("solution"),

    /** Topical serum or cream (cosmetic peptides) — dosed as a formulation percentage. */
    TOPICAL("topical"),

    /** Slow-release implant (e.g. Scenesse). */
    IMPLANT("implant"),

    /** Orally administered capsule or tablet. */
    ORAL_CAPSULE("oral_capsule"),
    ;

    /** Whether the reconstitution calculator is meaningful for this form. */
    val isReconstituted: Boolean get() = this == LYOPHILIZED_VIAL

    companion object {
        fun fromWire(value: String?): SuppliedForm? = entries.firstOrNull { it.wireValue == value }
    }
}

/** Cold-chain or handling requirement for a peptide or biologic. */
data class StorageRequirement(
    val temperature: Temperature,
    val lightSensitive: Boolean = false,
    /** Days the product stays stable once reconstituted, refrigerated. Null = unknown. */
    val reconstitutedStabilityDays: Double? = null,
) {
    enum class Temperature(val wireValue: String) {
        ROOM_TEMP("room_temp"),
        REFRIGERATE("refrigerate"),
        FREEZE("freeze"),
        ;

        companion object {
            fun fromWire(value: String?): Temperature? = entries.firstOrNull { it.wireValue == value }
        }
    }
}

/**
 * Peptide- and biologic-specific reference data.
 *
 * Presence switches the detail UI to a peptide presentation — amino-acid
 * sequence, handling, reconstitution — instead of the psychoactive trip-arc
 * model.
 */
data class PeptideProfile(
    /** Amino-acid sequence, one-letter with modification notes. Null = not published. */
    val sequence: String? = null,
    val suppliedForm: SuppliedForm? = null,
    /** Typical vial size in mg, which seeds the reconstitution calculator. */
    val typicalVialMg: Double? = null,
    /** Recommended reconstitution solvent (e.g. "Bacteriostatic water"). */
    val reconstitutionSolvent: String? = null,
    val storage: StorageRequirement? = null,
    /** IU↔mg bridge for hormones dosed in international units (GH, HCG, …). */
    val iuPerMg: Double? = null,
) {
    /** True when at least one field carries usable information. */
    val hasAnyValue: Boolean
        get() = sequence != null || suppliedForm != null || typicalVialMg != null ||
            reconstitutionSolvent != null || storage != null || iuPerMg != null
}

/** A group of effects sharing one PsychonautWiki category, for the "All effects" screen. */
@Serializable
data class EffectGroup(
    val category: String,
    val effects: List<String>,
)
