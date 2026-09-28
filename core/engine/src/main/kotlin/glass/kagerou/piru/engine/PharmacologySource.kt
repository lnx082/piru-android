package glass.kagerou.piru.engine

/**
 * Where a tolerance replay gets its pharmacology.
 *
 * The second port the read layer implements, alongside [SubstanceCatalog]. The two
 * are separate on purpose: the catalog answers "what does this dose draw on a
 * timeline", which every screen needs, while this answers "what does it occupy",
 * which only the tolerance tool and the body-load readout ask. A caller that only
 * draws curves should not have to provide molar masses.
 */
interface PharmacologySource {

    /**
     * The resolved pharmacology for one substance, by canonical name or alias.
     *
     * A name the catalog does not carry yields an empty record with a null molar
     * mass and no targets — which the engine reads as *uncomputable* rather than as
     * a zero. That distinction is what lets a PK-less substance fall back to its
     * class representative instead of silently contributing nothing.
     */
    fun pharmacologyParameters(nameOrAlias: String): PharmacologyParameters

    /**
     * The names of the substances that stand in for a tolerance class.
     *
     * A replay resolves these **alongside** the names in the log, not instead of
     * them: the missing-PK fallback models a PK-less substance as its class
     * representative, and that representative has usually never been logged.
     */
    fun classRepresentativeNames(): List<String>

    /**
     * The parameter map a replay consumes, keyed by each name **as given**.
     *
     * ## The keys are not normalised, and that is the whole contract
     * `ToleranceReplay` looks a dose's substance up by the exact string stored on the
     * dose and drops the dose when the lookup misses. A map keyed any other way — by
     * lowercase, say — makes every mixed-case substance silently contribute nothing,
     * and the only symptom is a card showing less tolerance than the log implies.
     *
     * Duplicates collapse case-insensitively, first spelling wins: a log holding both
     * "MDMA" and "mdma" resolves the catalog lookup once while both spellings stay
     * reachable. The catalog matches names case-insensitively anyway, so this saves a
     * read rather than changing an answer.
     */
    fun pharmacologyForLog(names: Collection<String>): Map<String, PharmacologyParameters> {
        val out = LinkedHashMap<String, PharmacologyParameters>()
        val seen = mutableSetOf<String>()
        for (name in names) {
            if (!seen.add(name.lowercase())) continue
            out[name] = pharmacologyParameters(name)
        }
        return out
    }
}
