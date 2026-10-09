package glass.kagerou.piru.ui.tools

import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.engine.SubstanceCatalog
import glass.kagerou.piru.model.SubstanceCategory
import java.time.Duration
import java.time.Instant

/**
 * Which recovery classes a set of doses calls for.
 *
 * Extracted from the comedown guide, where the list and the rule were both private — and the session's recovery
 * section needs **the same rule**, not a copy of it. A session that scoped its rows by a second implementation would
 * eventually disagree with the guide it links to: the guide would show a class the session had not counted, or the
 * session would offer a row the guide has nothing to say about.
 *
 * ## Two scopes, one list
 * - [recent] is the guide's own: the classes logged in the last 48 hours, newest first.
 * - [of] is the session's: the classes this session's doses belong to, in the order they were first logged.
 *
 * Both are built on the same [GUIDED] list and the same first-appearance dedup, because "which classes does this set
 * of doses call for" is one question asked over two windows.
 */
internal object ComedownCategories {

    /**
     * The eight classes the guide covers, in the order they are shown.
     *
     * Declaration order **is** the display order and is upstream's; it runs from the classes with the most written
     * about them to the ones with a shorter note.
     */
    val GUIDED: List<SubstanceCategory> = listOf(
        SubstanceCategory.STIMULANT,
        SubstanceCategory.EMPATHOGEN,
        SubstanceCategory.PSYCHEDELIC,
        SubstanceCategory.DISSOCIATIVE,
        SubstanceCategory.OPIOID,
        SubstanceCategory.BENZODIAZEPINE,
        SubstanceCategory.DEPRESSANT,
        SubstanceCategory.CANNABINOID,
    )

    /** How far back the guide's own list reaches. */
    val RECENT_WINDOW: Duration = Duration.ofHours(48)

    /**
     * The guided classes present in [entries] since 48 hours before [now], newest-first and de-duplicated by first
     * appearance.
     *
     * The cutoff is applied here rather than in a query so this is a pure function of its inputs — which is what makes
     * it testable, and why it belongs in an effect at the call site: it resolves one substance per dose through the
     * catalogue.
     */
    fun recent(
        entries: List<DoseEntryEntity>,
        now: Instant,
        catalog: SubstanceCatalog,
    ): List<SubstanceCategory> {
        val cutoff = now.minus(RECENT_WINDOW)
        return collect(
            // Newest first: the caller passes the log in the order a query would return it, and the first class seen
            // is the most recent one.
            entries.sortedByDescending { it.timestamp.toInstant() },
            catalog,
        ) { it.isBefore(cutoff) }
    }

    /**
     * The guided classes **this session's** doses belong to, in the order they were first logged.
     *
     * Ascending rather than descending, which is the one deliberate difference from [recent]: a session is read
     * forwards, so the class it opened with is the one a reader expects to see first. Upstream orders it the same way,
     * and the two scopes are separate functions precisely so this difference is visible rather than a parameter.
     */
    fun of(entries: List<DoseEntryEntity>, catalog: SubstanceCatalog): List<SubstanceCategory> =
        collect(entries.sortedBy { it.timestamp.toInstant() }, catalog) { false }

    /** The shared walk: guided classes in encounter order, de-duplicated, with [skip] deciding what to pass over. */
    private fun collect(
        ordered: List<DoseEntryEntity>,
        catalog: SubstanceCatalog,
        skip: (Instant) -> Boolean,
    ): List<SubstanceCategory> {
        val guided = GUIDED.toSet()
        val seen = mutableSetOf<SubstanceCategory>()
        val out = mutableListOf<SubstanceCategory>()
        for (entry in ordered) {
            if (skip(entry.timestamp.toInstant())) continue
            // An unresolvable name cannot contribute a class: the honest answer for "what class is this" when the
            // reference has never heard of the substance is nothing, rather than a guess. The same rule the journal's
            // category filter follows.
            val category = catalog.lookup(entry.substance)?.category ?: continue
            if (category in guided && seen.add(category)) out += category
        }
        return out
    }
}
