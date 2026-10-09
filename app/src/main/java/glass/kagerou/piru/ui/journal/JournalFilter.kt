package glass.kagerou.piru.ui.journal

import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.SubstanceCategory

/**
 * Narrowing the journal: a search field and three filter facets.
 *
 * Ported from `JournalModel.filteredEntries`. The journal had **no** way to narrow itself — the day list showed
 * everything, and on a long log the only way to find a dose was to scroll.
 *
 * ## The combination rule, which is the whole reason this is tested
 * **Within a facet, the selected values OR: across facets they AND.** A user who picks two tags wants entries
 * carrying *either*; a user who picks a tag and a route wants entries with that tag *and* that route. Getting this
 * backwards is the classic filter bug and it is not visible in a screenshot — the list simply has the wrong rows in
 * it, and both readings look plausible.
 *
 * ## The search field has a second mode
 * A query beginning with `#` searches **tags only**. Without it, a search looks in the substance name, the note and
 * the tags, because those are the three things a person remembers about a dose. The `#` form exists so a search for
 * "sleep" cannot be drowned by a note that happens to mention it.
 *
 * ## Why the category lookup is a parameter
 * A dose carries the name it was logged under, not a category — the category belongs to the **catalogue row** for
 * that name. So the caller supplies a lookup, and an entry whose name resolves to nothing **cannot match a category
 * filter**: the honest answer for "is this a stimulant" when the reference has never heard of it is no, rather than
 * a guess. Upstream reaches the same place through its `derived` map, which is keyed by entry and simply has no
 * entry for an unresolvable name.
 */
internal class JournalFilter(
    searchText: String = "",
    tags: Set<String> = emptySet(),
    categories: Set<SubstanceCategory> = emptySet(),
    routes: Set<RouteOfAdministration> = emptySet(),
) {

    /**
     * The four facets.
     *
     * `var` with a **private setter**: inside this class they are ordinary properties, and outside it they read as
     * immutable — which is what a call site needs, and it keeps the named-argument constructor rather than a
     * positional one where `tags` and `routes` could be swapped without a compile error.
     */
    var searchText: String = searchText
        private set
    var tags: Set<String> = tags
        private set
    var categories: Set<SubstanceCategory> = categories
        private set
    var routes: Set<RouteOfAdministration> = routes
        private set

    /** The same filter with the search field replaced. */
    fun copySearch(value: String): JournalFilter =
        JournalFilter(value, tags, categories, routes)

    fun copyTags(value: Set<String>): JournalFilter =
        JournalFilter(searchText, value, categories, routes)

    fun copyCategories(value: Set<SubstanceCategory>): JournalFilter =
        JournalFilter(searchText, tags, value, routes)

    fun copyRoutes(value: Set<RouteOfAdministration>): JournalFilter =
        JournalFilter(searchText, tags, categories, value)


    /** Whether any facet is on. The active-filter strip only draws when one is. */
    val isActive: Boolean get() = tags.isNotEmpty() || categories.isNotEmpty() || routes.isNotEmpty()

    /**
     * Whether anything narrows the list at all, including the search field.
     *
     * Distinct from [isActive] because the **strip** shows facets only: a chip for a search term would be a chip a
     * user cannot reason about, since the field they typed it into is on screen already.
     */
    val narrows: Boolean get() = isActive || searchText.isNotEmpty()

    /**
     * The entries that pass, in the order they arrived.
     *
     * [categoryOf] answers the catalogue's own category for a logged name, or null when the reference does not know
     * it.
     */
    fun apply(
        entries: List<DoseEntryEntity>,
        categoryOf: (String) -> SubstanceCategory?,
    ): List<DoseEntryEntity> {
        var result = entries

        if (tags.isNotEmpty()) {
            result = result.filter { entry -> entry.tags.any { it in tags } }
        }

        if (routes.isNotEmpty()) {
            result = result.filter { it.route in routes }
        }

        if (searchText.isNotEmpty()) {
            val query = searchText.removePrefix("#")
            if (searchText.startsWith("#")) {
                // Tags only. An empty `#` narrows nothing rather than matching nothing: a user who has typed the
                // hash and not yet a letter is mid-word, not asking for a filter.
                if (query.isNotEmpty()) {
                    result = result.filter { entry ->
                        entry.tags.any { it.contains(query, ignoreCase = true) }
                    }
                }
            } else {
                result = result.filter { entry ->
                    entry.substance.contains(query, ignoreCase = true) ||
                        entry.notes?.contains(query, ignoreCase = true) == true ||
                        entry.tags.any { it.contains(query, ignoreCase = true) }
                }
            }
        }

        if (categories.isNotEmpty()) {
            result = result.filter { entry ->
                val category = categoryOf(entry.substance)
                category != null && category in categories
            }
        }

        return result
    }

    /** One value removed, for the active-filter strip's chips. */
    fun withoutTag(tag: String): JournalFilter = JournalFilter(searchText, tags - tag, categories, routes)

    fun withoutCategory(category: SubstanceCategory): JournalFilter =
        JournalFilter(searchText, tags, categories - category, routes)

    fun withoutRoute(route: RouteOfAdministration): JournalFilter =
        JournalFilter(searchText, tags, categories, routes - route)

    /** Everything cleared except the search field, which has its own control. */
    fun cleared(): JournalFilter = JournalFilter(searchText)
}
