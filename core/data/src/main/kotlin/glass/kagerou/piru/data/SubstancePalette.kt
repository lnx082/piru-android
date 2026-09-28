package glass.kagerou.piru.data

import glass.kagerou.piru.engine.SubstanceCatalog
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.model.SubstanceColorGenerator

/**
 * The colour a substance is drawn in.
 *
 * Ported from `SubstancePalette.tint(for:tintMap:)`.
 *
 * ## The precedence, and why the user is first
 * A colour the user chose outranks the generated one, always. The generated
 * palette's promise is only that a substance keeps *a* colour — not that it keeps
 * the one the algorithm picked — so overriding it is not a deviation from the
 * design, it is the design's other half. A row marked
 * [SubstanceColorEntity.usesDefault] is the user declining to choose, and it falls
 * through to the generated colour rather than to black.
 *
 * ## Memoized, because the generator is not free
 * `displayP3ChromaCeiling` binary-searches the gamut boundary — 24 iterations of a
 * full Oklch→P3 matrix chain — and the timeline asks for a tint once per dose on
 * every rebuild. Built once per load into a map, so a day with thirty doses costs
 * a handful of searches rather than thirty.
 */
class SubstancePalette(
    private val database: PiruDatabase,
    private val catalog: SubstanceCatalog,
) {

    /**
     * Tints keyed by lowercased substance name, for the names in [names].
     *
     * Keyed by name rather than by id because that is what a dose carries: a dose
     * row stores the name the user typed, and the catalog resolves it — so the
     * colour follows the spelling the user logged.
     */
    suspend fun tintsFor(names: Collection<String>): Map<String, P3Color> {
        val overrides = database.substanceColorDao().all()
            .filterNot { it.usesDefault }
            .associateBy { it.substance.lowercase() }
        val out = LinkedHashMap<String, P3Color>()
        for (name in names) {
            val key = name.lowercase()
            if (out.containsKey(key)) continue
            val override = overrides[key]
            out[key] = if (override != null) {
                P3Color(override.red, override.green, override.blue)
            } else {
                generated(key)
            }
        }
        return out
    }

    /**
     * The generated colour for a substance the catalog carries, or the neutral
     * stand-in for one it does not.
     *
     * The seed is the **PSID family** where there is one, so a racemate and its
     * enantiomers — separate catalog rows, one family — share a colour, which is
     * what makes a family recognisable at a glance. A custom substance has no
     * family, so its lowercased name is the seed, which is stable for as long as
     * the user spells it the same way.
     */
    private fun generated(lowercasedName: String): P3Color {
        val substance = catalog.lookup(lowercasedName) ?: return P3Color.NEUTRAL
        val seed = substance.substanceUID ?: substance.name.lowercase()
        return SubstanceColorGenerator.displayP3(substance.category, seed)
    }
}
