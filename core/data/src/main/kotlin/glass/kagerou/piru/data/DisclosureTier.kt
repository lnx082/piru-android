package glass.kagerou.piru.data

/**
 * How much detail the user asked for.
 *
 * Ported from iOS's `UserProfile.disclosureTier`, whose value drives what a substance page
 * composes: the same substance is a short card at one tier and a pharmacology reference at
 * another.
 *
 * ## Why this type did not exist
 * The wire value has been persisted since v1 — `UserProfileRecordEntity.disclosureTierRaw`
 * documents it, including that `"harm-reduction"` is the tier the UI calls *Curious* and must
 * not be renamed. Onboarding collected it, the profile stored it, the export carried it, the
 * import restored it, and **no code could read it back**, because the store had no getter and
 * no enum existed to return. A question asked at onboarding and then discarded is
 * indistinguishable from a bug in the screen that asks it, and that is what it was.
 *
 * ## The values are wire values
 * [wireValue] is what the profile column and the export file hold. [CASUAL]'s and [CURIOUS]'s
 * strings match `OnboardingPrefs.TIER_CASUAL` / `TIER_CURIOUS`, which the onboarding screen
 * already writes.
 *
 * ## The retired value maps forward
 * `"pharma-nerd"` was a third tier upstream has since removed. The read layer maps it forward to
 * [CURIOUS] rather than failing, so a profile or a file written by that build still resolves —
 * [fromWire] does the same, and [isRetiredValue] names it so the mapping is testable rather than
 * implied.
 */
enum class DisclosureTier(val wireValue: String) {
    /**
     * The short version: what it is, what it does, how much to take.
     *
     * Named for the person who wants the answer and not the mechanism.
     */
    CASUAL("casual"),

    /**
     * The middle tier, and the default.
     *
     * `"harm-reduction"` on the wire, which is the value the entity documents and the one
     * [UserProfileRecordEntity] defaults to. Kept as the wire spelling because renaming it would
     * orphan every stored profile and every exported file.
     */
    CURIOUS("harm-reduction"),
    ;

    /** The density this tier asks for, as a fraction of the full reference. */
    val detailFraction: Double
        get() = when (this) {
            CASUAL -> 0.5
            CURIOUS -> 1.0
        }

    /**
     * Whether a section is shown at all at this tier.
     *
     * The one rule the port can state without inventing policy: the casual tier omits the
     * pharmacology reference sections, because that is what distinguishes it from the tier the
     * UI calls "Curious". Everything finer — which receptor table, which assay — is curation
     * rather than a rule, and belongs to the same place the section order does.
     */
    fun showsReferenceSections(): Boolean = this == CURIOUS

    companion object {
        /** The tier the UI has always started from, and the column's own default. */
        val DEFAULT: DisclosureTier = CURIOUS

        /** The third tier upstream retired. Kept named so its mapping is visible. */
        const val RETIRED_PHARMA_NERD: String = "pharma-nerd"

        fun isRetiredValue(value: String?): Boolean = value == RETIRED_PHARMA_NERD

        /**
         * Resolve a stored or exported value.
         *
         * Null, empty and anything unrecognised resolve to [DEFAULT] rather than throwing: this
         * is a preference, and a profile that predates the enum (or a file from a build that
         * spelled it differently) must still open. The retired value maps forward explicitly.
         */
        fun fromWire(value: String?): DisclosureTier = when {
            value == null -> DEFAULT
            isRetiredValue(value) -> CURIOUS
            else -> entries.firstOrNull { it.wireValue == value } ?: DEFAULT
        }
    }
}
