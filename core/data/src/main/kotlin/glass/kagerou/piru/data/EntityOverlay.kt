package glass.kagerou.piru.data

import glass.kagerou.piru.data.entity.CustomSubstanceRecordEntity
import glass.kagerou.piru.model.DurationProfile
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.Substance
import glass.kagerou.piru.model.SubstanceCategory
import glass.kagerou.piru.model.SubstanceRoute
import glass.kagerou.piru.model.DoseRange

/**
 * The user's own substance edits, laid over the bundled catalogue.
 *
 * Ported from `SubstanceLibrary`'s **user-defined overlay**, and the contract is upstream's own, stated in its doc:
 *
 * > Single-substance lookups consult the store and overlay any user-defined entry on top of the library result.
 * > The collection-level APIs deliberately stay **library-only**.
 *
 * That split is the whole design and worth keeping verbatim in reasoning:
 *
 * - A **lookup** returns what the app should *use*. If the user corrected 2-MMC's duration — the case upstream
 *   names, where neither the curated source nor TripSit ships one — then the timeline, the PK curve and the dose
 *   ladder should all use the corrected value. That only works if the correction is attached to the substance
 *   rather than re-resolved at each call site, because a call site can forget.
 * - A **browse list** returns what the catalogue *contains*. Folding customs into the library grid would surprise a
 *   reader who expects that grid to be the reference, so they keep their own section.
 *
 * ## The four things an entry can change
 * Each is optional, and an absent one inherits the bundled value:
 *
 * 1. **[displayName]** — a personal *label*. `name` stays the canonical identity, so a user can log "THC" and see
 *    it labeled "joint" without breaking a single row of dose history. This is the field that makes relabelling
 *    safe, and the reason it is a separate field from `name`.
 * 2. **[CustomSubstanceRecordEntity.doses]** — a personal ladder for the default route.
 * 3. **[CustomSubstanceRecordEntity.duration]** — a PK profile, which is what lets a net-new substance draw a curve
 *    at all.
 * 4. **[CustomSubstanceRecordEntity.halfLifeMinutes]** — a half-life override.
 *
 * ## Why this is a pure function
 * It is the one piece of the feature whose correctness is invisible: an overlay that dropped the bundled routes, or
 * that replaced `name` instead of `displayName`, produces a substance that looks fine and has lost the catalogue's
 * data or the user's history key. Both are testable here and nowhere else.
 */
object EntityOverlay {

    /**
     * The entry's value for [substance], or a net-new substance when the catalogue has none.
     *
     * [bundled] is what the catalogue returned — null for a substance the user invented.
     */
    fun apply(bundled: Substance?, entry: CustomSubstanceRecordEntity): Substance {
        val route = entry.defaultRoute
        val base = bundled ?: netNew(entry, route)

        // The label, never the identity: `name` has to keep matching every logged row and every export.
        val relabelled = entry.displayName?.takeIf { it.isNotBlank() }?.let { base.copy(displayName = it) } ?: base
        val withHalfLife = entry.halfLifeMinutes?.let { relabelled.copy(halfLifeMinutes = it) } ?: relabelled
        val withCategory = entry.category?.let { withHalfLife.copy(category = it) } ?: withHalfLife

        val personalDoses = entry.doses
        val personalDuration = entry.duration
        // Nothing per-route to do: the rest of the entry is already applied.
        if (personalDoses == null && personalDuration == null) return withCategory

        // The personal ladder belongs to the entry's own default route, so it **replaces that route's** values and
        // leaves the others alone. Dropping the other routes would lose the catalogue's data for every route the
        // user did not edit; replacing the whole list would too.
        val routes = withCategory.routes.map { existing ->
            if (existing.route == route) {
                existing.copy(
                    doses = personalDoses ?: existing.doses,
                    duration = personalDuration ?: existing.duration,
                )
            } else {
                existing
            }
        }
        // And if the catalogue carries no row for that route — a net-new substance, or one whose only row is
        // another route — the entry's values need a row of their own rather than being dropped.
        val withRoute = if (routes.any { it.route == route }) {
            routes
        } else {
            routes + SubstanceRoute(
                route = route,
                // The entry's own unit, which is what its ladder is written in.
                unit = entry.unit,
                // Every tier null, and `hasAnyValue` false: the user gave no ladder for a route the catalogue does
                // not carry either, which is genuinely "no dose guidance" rather than a zero.
                doses = personalDoses ?: DoseRange(),
                duration = personalDuration,
            )
        }
        return withCategory.copy(routes = withRoute)
    }

    /**
     * A substance the user invented: nothing from the catalogue but the entry's own fields.
     *
     * Every catalogue-shaped field is left at its default, and that is deliberate rather than lazy — the reference
     * sections on the page (bindings, metabolism, class background, structure) all read the bundled database by
     * **name**, find nothing, and hide. A fabricated value in any of them would be worse than an absent section.
     */
    private fun netNew(entry: CustomSubstanceRecordEntity, route: RouteOfAdministration): Substance = Substance(
        name = entry.name,
        // The entry's own `name` is the identity; `displayName` is the label. A net-new entry with a label set
        // therefore shows the label and logs under the name, exactly like an override.
        displayName = entry.displayName?.takeIf { it.isNotBlank() },
        // `OTHER` rather than the wire value's fallback: an unrecognised category means the enum has changed and
        // `OTHER` is the honest answer, whereas guessing a family would file the substance wrongly.
        category = entry.category ?: SubstanceCategory.OTHER,
        defaultRoute = route,
        routes = listOf(
            SubstanceRoute(
                route = route,
                unit = entry.unit,
                doses = entry.doses ?: DoseRange(),
                duration = entry.duration,
            ),
        ),
        halfLifeMinutes = entry.halfLifeMinutes,
        // The page's own prose, which is the one free-text field the entry carries.
        overview = null,
        isStub = false,
    )

    /**
     * Applies every entry to a list, matching by **name**.
     *
     * Matching by name rather than by row id is what makes an override work: the entry's `name` is the identity, and
     * a logged dose carries the name and nothing else. Case-insensitively, because a user who typed "thc" means the
     * "THC" the catalogue has — and a case-sensitive match would silently produce a duplicate substance instead of
     * an override.
     */
    fun applyAll(bundled: List<Substance>, entries: List<CustomSubstanceRecordEntity>): List<Substance> {
        // The overwhelming case is no customs at all, and the early return skips a map over the whole catalogue.
        if (entries.isEmpty()) return bundled
        val byName = entries.associateBy { it.name.lowercase() }
        val overlaid = bundled.map { substance ->
            byName[substance.name.lowercase()]?.let { apply(substance, it) } ?: substance
        }
        // Net-new entries first, then the overlaid catalogue. A net-new entry is one whose name the catalogue does
        // not carry; a name it does carry is an override, and appending it would duplicate the substance.
        val catalogueNames = bundled.mapTo(mutableSetOf()) { it.name.lowercase() }
        val invented = entries.filterNot { it.name.lowercase() in catalogueNames }.map { apply(null, it) }
        return invented + overlaid
    }

    /**
     * The label to show for a logged name, falling back to the name itself.
     *
     * The single accessor a display surface needs, so no call site has to know whether an entry exists. Upstream has
     * the same function for the same reason, and its doc records the bug it fixes: the library list and search
     * showed "Mephedrone" while the detail screen showed "4-MMC", because only the detail screen re-resolved the
     * personal name.
     */
    fun displayName(entries: List<CustomSubstanceRecordEntity>, name: String, fallback: String): String {
        val entry = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return fallback
        return entry.displayName?.takeIf { it.isNotBlank() } ?: fallback
    }
}
