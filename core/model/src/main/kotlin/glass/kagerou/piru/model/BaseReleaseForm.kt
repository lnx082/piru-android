package glass.kagerou.piru.model

/**
 * The release forms a substance's dose ladder and duration profile describe.
 *
 * Ported from `Piru/Domain/DoseLadder.swift`.
 *
 * Every ladder and duration the catalog carries is measured on the base form: the
 * unspecified product (the PSID `0` sentinel) or immediate release (`IR`), which
 * *is* the base form's kinetics. Every other code — `XR`, `DEP`, and any future
 * extended or delayed system — names a formulation none of those numbers were
 * written for.
 */
object BaseReleaseForm {

    /**
     * Release-form codes the base ladder describes.
     *
     * `0` is compared literally rather than through [PSID]'s unspecified-facet
     * accessor, matching upstream: this is read from a stored string on a path
     * that must not depend on the identity layer.
     */
    val codes: Set<String> = setOf("0", "IR")

    /** Whether [releaseForm] is one the base ladder describes. A missing or empty form is the unspecified product and counts. */
    fun contains(releaseForm: String?): Boolean {
        if (releaseForm.isNullOrEmpty()) return true
        return releaseForm.uppercase() in codes
    }
}
