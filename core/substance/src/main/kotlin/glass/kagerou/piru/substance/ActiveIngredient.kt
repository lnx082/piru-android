package glass.kagerou.piru.substance

/**
 * Preparations whose pharmacology belongs to a molecule the library carries separately.
 *
 * ## The problem this solves
 * A plant or a preparation has no Ki. Cannabis is dosed in grams of flower and logged as its own thing, which is the
 * right identity for a **journal** — but every receptor number ever measured "for cannabis" was measured on **THC**.
 * Filing those rows under the preparation produces two failures at once:
 *
 * 1. **A false attribution.** The catalogue's cannabis rows carry a CB1 Ki of 40.7 nM and 25 % intrinsic activity whose
 *    note reads "Original Felder/Showalter measurement" — and Felder 1995 and Showalter 1996 assayed **THC**. The
 *    citation on those rows resolves to a *nursing-ethics bibliography*, not a receptor study.
 * 2. **A duplicate on every comparison.** With THC's own efficacy value loaded, the CB1 ladder draws one molecule twice
 *    — 25 % labelled Cannabis and 36.1 % labelled THC, adjacent and nearly overlapping, reading as two compounds that
 *    disagree.
 *
 * So a preparation keeps its own entry for **dose, duration, effects and logging**, and **borrows** its pharmacology.
 * One molecule, one set of numbers, named as what it is.
 *
 * ## Why the map is installed rather than embedded
 * The catalogue already carries `substances.active_ingredient_substance_id`. Writing `"cannabis" to "THC"` here would
 * make **this file** the authority for a fact the data holds, and the two would drift the moment the column changed —
 * the same reason `BenzoEquivalence` takes its ratio from the row.
 *
 * Deliberately short, and the reference states why: a preparation earns an entry only when its psychoactivity is
 * carried by **one** molecule the library holds separately. Ayahuasca does not qualify, because its effect is the
 * DMT x beta-carboline MAOI **interaction** and no single row could stand for it.
 */
object ActiveIngredient {

    /**
     * The installed lowercased-name -> name map, replaced wholesale by [load].
     *
     * `@Volatile` rather than a lock: every read is a single map lookup on an immutable snapshot, and the writer swaps
     * the reference. A read that races a load sees either the old map or the new one, never a half-built one — which is
     * the only guarantee the callers need, because before the load lands **every substance speaks for itself**.
     */
    @Volatile
    private var mapping: Map<String, String> = emptyMap()

    /** Installs the mapping read from `substances.active_ingredient_substance_id`, keyed by lowercased name. */
    fun load(loaded: Map<String, String>) {
        mapping = loaded.mapKeys { (name, _) -> name.lowercase() }.toMap()
    }

    /**
     * The molecule to read pharmacology from, or null when the substance speaks for itself — which is almost everything.
     *
     * Lowercases the argument rather than trusting the caller, because the map is keyed that way and a display title
     * arrives with the catalogue's own capitalisation.
     */
    fun resolve(substanceName: String?): String? {
        val name = substanceName?.trim().orEmpty()
        if (name.isEmpty()) return null
        return mapping[name.lowercase()]
    }

    /**
     * The name to query pharmacology under: the active ingredient when there is one, otherwise the substance itself.
     *
     * **The single call every pharmacology read should go through.** A caller that reads the catalogue directly with
     * the preparation's own name reintroduces exactly the false attribution above, and does it silently.
     */
    fun pharmacologyName(substanceName: String?): String = resolve(substanceName) ?: substanceName.orEmpty()

    /** Whether the preparation borrows at all, for a screen that wants to say so. */
    fun borrows(substanceName: String?): Boolean = resolve(substanceName) != null
}
