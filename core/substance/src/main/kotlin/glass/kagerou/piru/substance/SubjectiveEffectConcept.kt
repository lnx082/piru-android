package glass.kagerou.piru.substance

/**
 * One row of the SubFxOnEx descriptor vocabulary — the `subjective_effect_concepts`
 * table a session note's descriptors reference by id.
 *
 * A note stores a concept **id**, never a name; this is the lookup that resolves
 * it to its canonical English name and its domain for the report tables. English
 * on purpose — the reports are the portable form, and this vocabulary is the fixed,
 * shared set they spell out ("Vocabulary: SubFxOnEx."), so a descriptor is never
 * localized the way an on-screen effect label is.
 */
data class SubjectiveEffectConcept(
    val id: String,
    val name: String,
    val domain: String,
)
