package glass.kagerou.piru.model

/**
 * One named subjective effect with its prose description, as the catalog stores
 * it per language.
 *
 * The description is only carried when the row was written in the language being
 * rendered: a bridged row's prose is still in its own language, and a paragraph
 * of Han text under an English effect name is worse than none.
 */
data class SubjectiveEffect(
    val name: String,
    val description: String,
)

/**
 * How a substance's tolerance builds and clears.
 *
 * [buildRate] stays a `String` rather than an enum because the catalog writes
 * free-form values ("rapid" / "moderate" / "slow") that the UI renders as text;
 * a closed set here would silently drop a value the pipeline adds later.
 */
data class ToleranceInfo(
    /** Days for tolerance to halve. */
    val halfLife: Double,
    /** Days for a full tolerance reset. */
    val fullResetDays: Double,
    /** "rapid", "moderate" or "slow". */
    val buildRate: String,
)

/** What a binding does at its target. */
enum class BindingAction(val wireValue: String) {
    AGONIST("agonist"),
    PARTIAL_AGONIST("partialAgonist"),
    ANTAGONIST("antagonist"),
    INVERSE_AGONIST("inverseAgonist"),
    POSITIVE_ALLOSTERIC_MODULATOR("positiveAllostericModulator"),
    NEGATIVE_ALLOSTERIC_MODULATOR("negativeAllostericModulator"),
    REUPTAKE_INHIBITOR("reuptakeInhibitor"),
    RELEASING_AGENT("releasingAgent"),
    ENZYME_INHIBITOR("enzymeInhibitor"),
    CHANNEL_BLOCKER("channelBlocker"),
    MODULATOR("modulator"),
    ;

    companion object {
        fun fromWire(value: String?): BindingAction? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * The three-tier strength a binding is drawn with.
 *
 * Ordered by declaration, so `compareTo` ranks weak < significant < primary —
 * which is what the mechanism query's `ORDER BY affinity DESC` and the
 * summary's own sort both rely on.
 *
 * Ordinal 1/2/3 rather than 0/1/2 on purpose: the tier numbers come out of SQL
 * as 1–3 and an off-by-one here would invert the dots on every mechanism card.
 */
enum class BindingAffinity(val tier: Int) {
    WEAK(1),
    SIGNIFICANT(2),
    PRIMARY(3),
    ;

    companion object {
        /** Resolve a tier from SQL, falling back to [SIGNIFICANT] as upstream does. */
        fun fromTier(tier: Int?): BindingAffinity =
            entries.firstOrNull { it.tier == tier } ?: SIGNIFICANT
    }
}

/**
 * The single, systematic source of truth for the three-tier receptor "strength"
 * dots — shared by the mechanism card and the receptor literature card, so a
 * substance never shows one strength in one place and another elsewhere.
 *
 * ## Measurement-aware bands
 * Binding affinity (Kᵢ/Kd) and functional potency (EC₅₀/IC₅₀) live on different
 * concentration scales — a releaser's EC₅₀ runs about 10× higher than a
 * blocker's Kᵢ for the same "strong" — so each measurement type gets its own
 * thresholds. Lower concentration means more potent means more dots. Tier 3 is
 * strong, 2 moderate, 1 weak, *at that target*, releaser or blocker alike.
 *
 * ## Keep in lock-step with the SQL
 * The bindings query in [glass.kagerou.piru.substance] hardcodes these same
 * cutoffs as a `CASE` expression, because the tier has to be computed in the
 * database for the `GROUP BY` to rank targets. Two copies of a threshold is a
 * real hazard: the doc comment there says so, and `ReceptorStrengthTest` pins
 * this side while `SubstanceReaderTest` pins the other against the real rows.
 */
object ReceptorStrength {

    /**
     * Resolve a binding's tier from whichever measurement it carries, preferring
     * binding affinity (Kᵢ) over functional potency (EC₅₀, then IC₅₀). Null when
     * the row has no measured value at all.
     */
    fun tier(kiNm: Double? = null, ec50Nm: Double? = null, ic50Nm: Double? = null): Int? = when {
        kiNm != null -> bindingTier(kiNm)
        ec50Nm != null -> functionalTier(ec50Nm)
        ic50Nm != null -> functionalTier(ic50Nm)
        else -> null
    }

    /** Binding affinity (Kᵢ/Kd) bands: under 100 nM strong, 100–1000 moderate, 1000 and up weak. */
    fun bindingTier(nm: Double): Int = when {
        nm < 100 -> 3
        nm < 1_000 -> 2
        else -> 1
    }

    /**
     * Functional potency (EC₅₀/IC₅₀) bands, shifted about 10× from binding: under
     * 1 µM strong, 1–10 µM moderate, 10 µM and up weak — so a potent releaser
     * (MDMA's NET EC₅₀ ≈ 77 nM) reads strong rather than weak.
     */
    fun functionalTier(nm: Double): Int = when {
        nm < 1_000 -> 3
        nm < 10_000 -> 2
        else -> 1
    }
}

/** One receptor target the substance acts on, with the action and how strong it is. */
data class ReceptorBinding(
    val target: String,
    val action: BindingAction,
    val affinity: BindingAffinity,
) {
    /** Identity carries the action: one target can be both agonised and blocked. */
    val id: String get() = "$target-${action.wireValue}"
}

/**
 * Long-form substance overview prose, resolved locale-first.
 *
 * [machineTranslated] flags text auto-translated into the app's language so the
 * UI can label it, and [sourceSlug] drives the attribution row and deep link —
 * which is why it defaults to the machine-translation source rather than to
 * nothing: an unattributed paragraph reads as the app's own voice.
 */
data class SubstanceOverview(
    val text: String,
    val machineTranslated: Boolean,
    val sourceSlug: String = "freeodwiki",
)

/** The mechanism card's content: summary prose, prose detail, and the receptor bindings behind them. */
data class MechanismOfAction(
    val summary: String,
    val description: String,
    val primaryTargets: List<String> = emptyList(),
    val bindings: List<ReceptorBinding> = emptyList(),
    /**
     * The language code of the row the summary came from ("en", "zh-Hans"), or
     * null when the text is from the category floor, which is already localized
     * through the app's own string resources.
     */
    val summaryLanguage: String? = null,
) {
    /**
     * [primaryTargets] with the bindings' own targets standing in when it is
     * empty.
     *
     * Upstream does this in the initializer, so a caller that supplies bindings
     * but no target list still gets one. A `val` constructor parameter cannot be
     * reassigned in Kotlin, so the same invariant is expressed as the accessor
     * callers should read — and the raw field stays what was passed in.
     */
    val effectivePrimaryTargets: List<String>
        get() = primaryTargets.ifEmpty { bindings.map { it.target } }
}
