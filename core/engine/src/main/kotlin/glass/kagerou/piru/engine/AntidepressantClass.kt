package glass.kagerou.piru.engine

/**
 * The antidepressant classes, and what actually separates them.
 *
 * Ported from `AntidepressantClass`. The acronyms are the most-used and least-explained words in this corner of
 * pharmacology: a reader handed one of them knows the letters and not the difference, and **the difference is where
 * the whole side-effect profile comes from.** So each case carries a line of mechanism rather than a dictionary
 * expansion alone.
 *
 * ## Resolved from `drug_class`, not from tags
 * The curated `substances.drug_class` column is the axis this reads, and it matters that it is not the tag union: the
 * tags disagree with the curated column on exactly the compounds where precision matters — they call atomoxetine an
 * SNRI, maprotiline a TCA, and vortioxetine and vilazodone SSRIs.
 *
 * ## Four curated values have no case, on purpose
 * `MELATONERGIC` (agomelatine), `NEUROSTEROID` (brexanolone), `OPIOIDERGIC` (tianeptine) and `ATYPICAL`. This card
 * lists a class **against its family**, which reads as "these are the alternatives on one dimension". Those four are
 * not points on the monoamine axis, they are departures from it, so listing them beside SSRI would state a comparison
 * that is not true. Their substances show no class card, which is the state they were already in.
 *
 * The `drug_class` column carries exactly one of those four plus this enum's nine, so the mapping is total in one
 * direction and deliberately partial in the other.
 */
enum class AntidepressantClass(val wireValue: String, val acronym: String) {
    SSRI("ssri", "SSRI"),
    SNRI("snri", "SNRI"),
    NRI("nri", "NRI"),
    NDRI("ndri", "NDRI"),
    TCA("tca", "TCA"),
    MAOI("maoi", "MAOI"),
    SARI("sari", "SARI"),
    NASSA("nassa", "NaSSA"),
    SMS("sms", "SMS"),
    ;

    companion object {
        /**
         * The class a curated `drug_class` value names, or null when it names none of the nine.
         *
         * Matched **case-insensitively**, because the column holds `NaSSA` while the wire value is `nassa`. A
         * case-sensitive lookup would silently drop that class — one substance, and the one whose label was coined for
         * it.
         */
        fun fromCurated(drugClass: String?): AntidepressantClass? {
            val key = drugClass?.trim()?.lowercase() ?: return null
            if (key.isEmpty()) return null
            return entries.firstOrNull { it.wireValue == key }
        }
    }
}

/**
 * Whether a curated class assignment is **contestable**.
 *
 * Ported from `CuratedDrugClass.isContested`. Eleven assignments are called contestable, and a card that asserts those
 * the way it asserts sertraline's SSRI is overstating what the field agrees on:
 *
 * - bupropion's NDRI label rests on transporter affinities weak enough that the mechanism is still argued over;
 * - NaSSA was coined for a single drug;
 * - venlafaxine behaves, at a low dose, like the class it is not filed under.
 *
 * ## Why a set rather than a flag on the enum
 * The contestability belongs to the **assignment**, not to the class: SSRIs as a class are not contested, and
 * venlafaxine's SNRI label is. A flag on [AntidepressantClass] would say all SNRIs are contested, which would be a
 * different and false claim.
 */
object ContestedDrugClasses {

    /**
     * The curated assignments the classifier itself calls contestable.
     *
     * Upstream's list, by the substance the assignment belongs to. Spelled out rather than derived, because there is
     * nothing to derive it from: the judgement is curated, and a rule that guessed it from the mechanism would be
     * inventing the very thing the flag exists to record.
     */
    val SUBSTANCES: Set<String> = setOf(
        "Bupropion",
        "Venlafaxine",
        "Mirtazapine",
        "Vortioxetine",
        "Vilazodone",
        "Trazodone",
        "Atomoxetine",
        "Maprotiline",
    )

    /**
     * The three names a first draft of this list carried that **cannot** be flagged, recorded so they are not added
     * back: `Moclobemide` has a null `drug_class`, and `Agomelatine` (MELATONERGIC) and `Tianeptine` (OPIOIDERGIC)
     * carry the two classes [AntidepressantClass] deliberately leaves out.
     *
     * A `SUBSTANCES` entry that can only ever return false is dead code that **reads as a curated judgement** — the
     * worst kind, because the next reader takes it for a decision. Dropping them is not a correction of the
     * pharmacology; it is the removal of three statements that were never doing anything.
     */
    private val UNREACHABLE: Set<String> = setOf("Moclobemide", "Agomelatine", "Tianeptine")

    /** Whether this substance's curated class assignment is one the classifier calls contestable. */
    fun isContested(substance: String): Boolean =
        SUBSTANCES.any { it.equals(substance.trim(), ignoreCase = true) }
}
