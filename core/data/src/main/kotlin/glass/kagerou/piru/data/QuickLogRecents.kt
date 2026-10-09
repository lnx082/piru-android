package glass.kagerou.piru.data

import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.data.entity.QuickLogDoseEntity
import glass.kagerou.piru.model.RouteOfAdministration

/**
 * The quick log's dock: the chips a user reaches for, kept as they log.
 *
 * Ported from `QuickLogManager`. `quick_log_doses` has had a table, a DAO, an export and an import since the port
 * began and **nothing ever wrote a row** — so the dock was whatever a seeded file contained, and logging a dose left
 * no trace. That is BUG #44's shape again: the persistence complete and no producer.
 *
 * ## The rules, each of which is a decision rather than a mechanic
 *
 * **Grouped by identity, not by name.** A chip's group is `identityKey` + route, so a Concerta dose floats its own
 * chip rather than merging into Ritalin's — the same name, a different product, and a user who logged one did not
 * ask for the other. This is also why the suppression list is keyed by identity.
 *
 * **Logging a dose again is the clearest statement that it belongs**, so it un-suppresses the identity. Without
 * that, "remove from recents" would be permanent for a chip the user went on using.
 *
 * **Ordering depends on one preference.** With "fixed order" off (the default) a logged dose moves to the **front of
 * its group**; with it on, the order only changes when the user reorders by hand. A dock's whole point is that what
 * you reached for last is where your thumb already is.
 *
 * **Each group is capped, and the cap evicts by least-recent use** — not by position, because position is what the
 * user arranged and staleness is what should go.
 *
 * ## Why the batch takes a snapshot
 * Upstream folds every dose of a batch into an in-memory `all` list and saves once, for a reason its own comment
 * gives: a save re-runs each live query, and a batch that saved per dose would pay the whole invalidation storm per
 * dose. The same shape is used here — one read, N folds, one write.
 */
object QuickLogRecents {

    /** One dose to fold into the dock. */
    data class LoggedDose(
        val substance: String,
        val route: RouteOfAdministration,
        val amount: Double,
        val unit: String,
        val substanceUID: String? = null,
        val isomer: String? = null,
        val releaseForm: String? = null,
        val saltForm: String? = null,
        val productName: String? = null,
        val volumeML: Double? = null,
        val abv: Double? = null,
        val drinkName: String? = null,
        val emoji: String? = null,
    )

    /**
     * The chips to write, given what is already stored.
     *
     * Pure: it reads the current rows and the suppression list and returns the **complete** new list, so the caller
     * performs one delete-and-insert rather than a sequence of updates whose intermediate states are visible to a
     * concurrent reader. It is also what makes the whole rule set testable without a database.
     */
    fun fold(
        doses: List<LoggedDose>,
        existing: List<QuickLogDoseEntity>,
        fixedOrder: Boolean,
        suppressed: Set<String>,
        /**
         * The clock. A parameter rather than `Date()` so a test can assert an ordering that depends on time without
         * sleeping — the trap the port has already hit once with a hardcoded `Date()`.
         */
        now: java.util.Date = java.util.Date(),
    ): Result {
        if (doses.isEmpty()) return Result(existing, suppressed)

        val all = existing.toMutableList()
        val live = suppressed.toMutableSet()

        for (dose in doses) {
            val identity = SubstanceIdentity.identityKey(
                substanceUID = dose.substanceUID,
                substance = dose.substance,
                isomer = dose.isomer,
                releaseForm = dose.releaseForm,
                saltForm = dose.saltForm,
            )
            // Logging it again says it belongs, so an earlier removal stops applying.
            live.remove(identity)

            val group = all.filter { it.identityKey == identity && it.route == dose.route }
            val key = SubstanceIdentity.makeKey(
                substance = dose.substance,
                route = dose.route,
                amount = dose.amount,
                unit = dose.unit,
                substanceUID = dose.substanceUID,
                isomer = dose.isomer,
                releaseForm = dose.releaseForm,
                saltForm = dose.saltForm,
                volumeML = dose.volumeML,
                abv = dose.abv,
                drinkName = dose.drinkName,
            )

            val match = group.firstOrNull { it.key == key }
            if (match != null) {
                // Already a chip: it was used now, and with a floating order it moves to the front of its group.
                val index = all.indexOf(match)
                all[index] = match.copy(
                    lastUsedAt = now,
                    sortOrder = if (fixedOrder) match.sortOrder else (group.minOf { it.sortOrder } - 1),
                )
                continue
            }

            val sortOrder = if (fixedOrder) {
                (group.maxOfOrNull { it.sortOrder } ?: -1.0) + 1
            } else {
                (group.minOfOrNull { it.sortOrder } ?: 0.0) - 1
            }
            all += QuickLogDoseEntity(
                substance = dose.substance,
                route = dose.route,
                amount = dose.amount,
                unit = dose.unit,
                sortOrder = sortOrder,
                lastUsedAt = now,
                volumeML = dose.volumeML,
                abv = dose.abv,
                drinkName = dose.drinkName,
                emoji = dose.emoji,
                substanceUID = dose.substanceUID,
                isomer = dose.isomer,
                releaseForm = dose.releaseForm,
                saltForm = dose.saltForm,
                productName = dose.productName,
            )
        }

        return Result(evict(all), live)
    }

    /** The folded list and the suppression list after the batch. */
    data class Result(val rows: List<QuickLogDoseEntity>, val suppressed: Set<String>)

    /**
     * Applies the per-group cap, dropping the **least recently used**.
     *
     * Eviction by `lastUsedAt` rather than by position: position is what the user arranged, and staleness is what
     * should go. A group at the cap whose members were all used recently keeps all of them, which is correct — the
     * cap is a bound on growth, not a target size.
     */
    private fun evict(rows: List<QuickLogDoseEntity>): List<QuickLogDoseEntity> {
        val keep = mutableSetOf<Long>()
        val groups = rows.groupBy { it.identityKey to it.route }
        for ((_, members) in groups) {
            if (members.size <= QuickLogDoseEntity.PER_GROUP_LIMIT) {
                keep += members.map { it.rowId }
                continue
            }
            // Newest first; rowId breaks a tie so an eviction is deterministic rather than dependent on input order.
            val survivors = members
                .sortedWith(compareByDescending<QuickLogDoseEntity> { it.lastUsedAt.time }.thenByDescending { it.rowId })
                .take(QuickLogDoseEntity.PER_GROUP_LIMIT)
            keep += survivors.map { it.rowId }
        }
        return rows.filter { it.rowId in keep }
    }

    /**
     * The chips a fresh install starts with, from the log's own history.
     *
     * Upstream seeds once from the most recent doses so a new user is not looking at an empty dock while their
     * journal has a hundred rows in it. Only used when the table **is empty**, which is what "once" means here: a
     * user who removed every chip has said something, and re-seeding would overrule it.
     */
    fun seed(history: List<DoseEntryEntity>, limitPerGroup: Int = QuickLogDoseEntity.PER_GROUP_LIMIT): List<LoggedDose> {
        if (history.isEmpty()) return emptyList()
        // Most recent first, then the first `limitPerGroup` distinct measurements per (identity, route).
        val seen = mutableMapOf<Pair<String, RouteOfAdministration>, MutableSet<String>>()
        val out = mutableListOf<LoggedDose>()
        for (entry in history.sortedByDescending { it.timestamp.time }) {
            val identity = SubstanceIdentity.identityKey(
                substanceUID = entry.substanceUID,
                substance = entry.substance,
                isomer = entry.isomer,
                releaseForm = entry.releaseForm,
                saltForm = entry.saltForm,
            )
            val groupKey = identity to entry.route
            val keys = seen.getOrPut(groupKey) { mutableSetOf() }
            val measureKey = "${entry.amount}/${entry.unit}"
            if (measureKey in keys) continue
            if (keys.size >= limitPerGroup) continue
            keys += measureKey
            out += LoggedDose(
                substance = entry.substance,
                route = entry.route,
                amount = entry.amount,
                unit = entry.unit,
                substanceUID = entry.substanceUID,
                isomer = entry.isomer,
                releaseForm = entry.releaseForm,
                saltForm = entry.saltForm,
                productName = entry.productName,
            )
        }
        return out
    }
}
