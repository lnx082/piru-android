package glass.kagerou.piru.ui.journal

import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.SubstanceCategory
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.Date
import org.junit.jupiter.api.Test

/**
 * Narrowing the journal.
 *
 * ## The rule this file exists for
 * **Within a facet the selected values OR; across facets they AND.** A user who picks two tags wants entries with
 * *either*; a user who picks a tag and a route wants entries with that tag *and* that route. Getting it backwards is
 * the classic filter bug, and it is **invisible in a screenshot** — the list simply holds the wrong rows, and both
 * readings look plausible. So it is asserted from both directions rather than described.
 *
 * ## And the search field's second mode
 * A query starting with `#` searches tags only. Without that mode a search for "sleep" matches a note that happens
 * to mention sleep, which is not what a user typing a tag means.
 */
class JournalFilterTest {

    private fun entry(
        substance: String,
        route: RouteOfAdministration = RouteOfAdministration.ORAL,
        tags: List<String> = emptyList(),
        notes: String? = null,
    ) = DoseEntryEntity(
        timestamp = Date(0),
        substance = substance,
        amount = 10.0,
        unit = "mg",
        route = route,
        notes = notes,
        tagsRaw = tags.joinToString(",").ifEmpty { null },
    )

    private val nothing: (String) -> SubstanceCategory? = { null }

    private fun categories(vararg pairs: Pair<String, SubstanceCategory>): (String) -> SubstanceCategory? {
        val map = pairs.toMap()
        return { name -> map[name] }
    }

    // MARK: - The combination rule

    /**
     * Two tags in one facet OR together.
     *
     * The half people expect. Asserted with an entry carrying only the second tag, so an implementation that
     * intersect-ted the facet would return nothing and fail here.
     */
    @Test
    fun `values within a facet or together`() {
        val entries = listOf(
            entry("A", tags = listOf("night")),
            entry("B", tags = listOf("work")),
            entry("C", tags = listOf("other")),
        )
        val filtered = JournalFilter(tags = setOf("night", "work")).apply(entries, nothing)
        filtered.map { it.substance } shouldContainExactly listOf("A", "B")
    }

    /**
     * A tag **and** a route must both match.
     *
     * The other half, and the one that is easy to get wrong by applying the facet rule globally: if the facets
     * combined with OR, entry `C` would come back although it has neither the tag nor the route asked for.
     */
    @Test
    fun `across facets the conditions and together`() {
        val entries = listOf(
            entry("A", route = RouteOfAdministration.ORAL, tags = listOf("night")),
            entry("B", route = RouteOfAdministration.INSUFFLATION, tags = listOf("night")),
            entry("C", route = RouteOfAdministration.ORAL, tags = listOf("work")),
        )
        val filtered = JournalFilter(
            tags = setOf("night"),
            routes = setOf(RouteOfAdministration.INSUFFLATION),
        ).apply(entries, nothing)
        // Only B has both.
        filtered.map { it.substance } shouldContainExactly listOf("B")
    }

    /** Three facets at once, so the AND rule is not accidentally a two-facet special case. */
    @Test
    fun `three facets all have to match`() {
        val entries = listOf(
            entry("A", route = RouteOfAdministration.ORAL, tags = listOf("night")),
            entry("B", route = RouteOfAdministration.INSUFFLATION, tags = listOf("night")),
        )
        val filtered = JournalFilter(
            tags = setOf("night"),
            routes = setOf(RouteOfAdministration.ORAL),
            categories = setOf(SubstanceCategory.STIMULANT),
        ).apply(entries) { name -> if (name == "A") SubstanceCategory.STIMULANT else SubstanceCategory.OPIOID }
        filtered.map { it.substance } shouldContainExactly listOf("A")
    }

    /** An empty filter passes everything through, in order. */
    @Test
    fun `an empty filter changes nothing`() {
        val entries = listOf(entry("A"), entry("B"), entry("C"))
        JournalFilter().apply(entries, nothing) shouldContainExactly entries
        JournalFilter().isActive shouldBe false
        JournalFilter().narrows shouldBe false
    }

    // MARK: - Categories, and the unresolvable name

    /**
     * A name the reference does not know **cannot match a category filter**.
     *
     * The honest answer for "is this a stimulant" when the catalogue has never heard of the substance is no rather
     * than a guess. This is the case an implementation that defaulted an unknown name to some category would get
     * wrong, and it would look like the filter simply working.
     */
    @Test
    fun `an unresolvable name does not match a category`() {
        val entries = listOf(entry("Known"), entry("Something Unlisted"))
        val filtered = JournalFilter(categories = setOf(SubstanceCategory.STIMULANT))
            .apply(entries) { name -> if (name == "Known") SubstanceCategory.STIMULANT else null }
        filtered.map { it.substance } shouldContainExactly listOf("Known")
    }

    /** Categories within the facet OR too, and a null lookup excludes everything. */
    @Test
    fun `categories or within their facet`() {
        val entries = listOf(entry("A"), entry("B"), entry("C"))
        val lookup = categories(
            "A" to SubstanceCategory.STIMULANT,
            "B" to SubstanceCategory.OPIOID,
            "C" to SubstanceCategory.PSYCHEDELIC,
        )
        JournalFilter(categories = setOf(SubstanceCategory.STIMULANT, SubstanceCategory.OPIOID))
            .apply(entries, lookup)
            .map { it.substance } shouldContainExactly listOf("A", "B")
    }

    // MARK: - The search field

    /**
     * A plain query looks in the name, the note and the tags.
     *
     * The three things a person remembers about a dose. Asserted with one match of each kind, so a search that only
     * looked at the name would return one row instead of three.
     */
    @Test
    fun `a plain query searches name, note and tags`() {
        val entries = listOf(
            entry("Ketamine"),
            entry("Something", notes = "a ketamine-like evening"),
            entry("Other", tags = listOf("ketamine")),
            entry("Unrelated"),
        )
        JournalFilter(searchText = "ketamine").apply(entries, nothing)
            .map { it.substance } shouldContainExactly listOf("Ketamine", "Something", "Other")
    }

    /** And it is case-insensitive, because nobody types a trade name with its capitals. */
    @Test
    fun `the search is case-insensitive`() {
        val entries = listOf(entry("Ketamine"))
        JournalFilter(searchText = "KETAMINE").apply(entries, nothing).size shouldBe 1
        JournalFilter(searchText = "ketamine").apply(entries, nothing).size shouldBe 1
        JournalFilter(searchText = "kEtAmInE").apply(entries, nothing).size shouldBe 1
    }

    /**
     * A query beginning with `#` searches **tags only**.
     *
     * The rule that stops a search for a tag being drowned by a note that happens to mention the same word. The
     * entry named "ketamine" must **not** come back for `#ketamine`, which is what makes this the tag mode rather
     * than a substring search that also happens to match tags.
     */
    @Test
    fun `a hash query searches tags only`() {
        val entries = listOf(
            entry("ketamine"),
            entry("Something", notes = "ketamine"),
            entry("Other", tags = listOf("ketamine")),
        )
        JournalFilter(searchText = "#ketamine").apply(entries, nothing)
            .map { it.substance } shouldContainExactly listOf("Other")
    }

    /**
     * A bare `#` narrows nothing.
     *
     * A user who has typed the hash and not yet a letter is mid-word, not asking for a filter. Matching nothing
     * would empty the list as they type the first character, which reads as the journal breaking.
     */
    @Test
    fun `a bare hash narrows nothing`() {
        val entries = listOf(entry("A"), entry("B"))
        JournalFilter(searchText = "#").apply(entries, nothing) shouldContainExactly entries
    }

    /** A hash query with a partial tag matches, because a user types tag fragments. */
    @Test
    fun `a hash query matches a tag prefix`() {
        val entries = listOf(entry("A", tags = listOf("nightshift")), entry("B", tags = listOf("work")))
        JournalFilter(searchText = "#night").apply(entries, nothing).map { it.substance } shouldContainExactly
            listOf("A")
    }

    /** The search field and a facet both apply — the field is a facet of its own, not an alternative to them. */
    @Test
    fun `the search and a facet both apply`() {
        val entries = listOf(
            entry("Ketamine", route = RouteOfAdministration.ORAL),
            entry("Ketamine", route = RouteOfAdministration.INSUFFLATION),
        )
        JournalFilter(searchText = "ket", routes = setOf(RouteOfAdministration.INSUFFLATION))
            .apply(entries, nothing)
            .map { it.route } shouldContainExactly listOf(RouteOfAdministration.INSUFFLATION)
    }

    // MARK: - The strip's own operations

    /**
     * `isActive` is about the facets, `narrows` includes the search field.
     *
     * The distinction the strip depends on: a chip for a search term would be a chip the user cannot reason about,
     * because the field they typed it into is on screen already. Asserted as a pair, because collapsing them is the
     * tempting simplification.
     */
    @Test
    fun `isActive ignores the search field but narrows does not`() {
        val searched = JournalFilter(searchText = "ket")
        searched.isActive shouldBe false
        searched.narrows shouldBe true

        val faceted = JournalFilter(tags = setOf("night"))
        faceted.isActive shouldBe true
        faceted.narrows shouldBe true
    }

    /** Removing one value from a facet leaves the others, and leaves the search alone. */
    @Test
    fun `removing one value keeps the rest`() {
        val filter = JournalFilter(
            searchText = "ket",
            tags = setOf("night", "work"),
            categories = setOf(SubstanceCategory.STIMULANT),
            routes = setOf(RouteOfAdministration.ORAL),
        )
        val fewer = filter.withoutTag("night")
        fewer.tags shouldBe setOf("work")
        fewer.searchText shouldBe "ket"
        fewer.categories shouldBe setOf(SubstanceCategory.STIMULANT)
        fewer.routes shouldBe setOf(RouteOfAdministration.ORAL)

        filter.withoutCategory(SubstanceCategory.STIMULANT).categories shouldBe emptySet()
        filter.withoutRoute(RouteOfAdministration.ORAL).routes shouldBe emptySet()
    }

    /**
     * Clearing drops every facet and **keeps the search**.
     *
     * What the strip's Clear means: it clears what it is showing. Dropping the search too would empty a field the
     * user is still typing in, from a control about chips.
     */
    @Test
    fun `cleared keeps the search field`() {
        val filter = JournalFilter(
            searchText = "ket",
            tags = setOf("night"),
            categories = setOf(SubstanceCategory.STIMULANT),
            routes = setOf(RouteOfAdministration.ORAL),
        )
        val cleared = filter.cleared()
        cleared.isActive shouldBe false
        cleared.searchText shouldBe "ket"
    }
}
