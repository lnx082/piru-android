package glass.kagerou.piru.ui.journal

import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.engine.ActiveSubstance
import glass.kagerou.piru.engine.ActiveSubstanceCalculator
import glass.kagerou.piru.engine.PKModel
import glass.kagerou.piru.engine.PKResolver
import glass.kagerou.piru.model.DoseUnit
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.engine.SubstanceCatalog
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * What is still in the body at the end of a session, and what has left it.
 *
 * Ported from `SessionBodyLoadModel`. The session page had no such block, so a session showed what was taken and never
 * what remained — which is the question a session's last dose actually raises.
 *
 * ## Grouping is by **canonical** substance, and the unit is the group's first
 * A user logs "Concerta" and "Ritalin" and is dosed with methylphenidate twice; the total belongs to one substance.
 * Upstream groups by the catalogue's canonical name and converts each later dose **into the unit the group already
 * established**, because `500 mg + 1 g` is `1500 mg` and not `501` of anything. A unit that cannot be converted
 * (`mL`, `IU`) contributes its **count but not its amount** — which is why the row can read "2 doses" with a total
 * that only one of them accounts for.
 *
 * ## The two reasons a group can be missing from the calculator, which must not read alike
 * `ActiveSubstanceCalculator` skips a dose it cannot model. So:
 *
 * - a substance with **no half-life** has nothing to say about its clearance — it always appears, marked unmodeled;
 * - a substance whose doses have worn off **between** them would otherwise vanish, so a group with more than one dose
 *   appears even when nothing is circulating.
 *
 * A single worn-off modelling dose shows **nothing**, which is correct: it is gone. A substance you just took must
 * never silently vanish from the section, which is what the unmodeled case prevents.
 *
 * ## Why the lookups are parameters
 * `nameFor`, `tintFor` and `hasActiveMetabolite` all need the catalogue, and a catalogue is an 18 MB asset — so the
 * whole of this is testable only if they are handed in. That is what lets `SessionBodyLoadModelTest` assert the unit
 * arithmetic and the two missing reasons without a device.
 */
internal object SessionBodyLoadModel {

    /**
     * The eliminated fraction at or above which this screen calls a substance **cleared**.
     *
     * Display-only, and deliberately not 1.0: a curve approaches its asymptote and never reaches it, so waiting for
     * complete elimination would leave a row in the active list forever, or drop it as a jump. 0.95 is upstream's.
     */
    const val CLEARED_THRESHOLD: Double = 0.95

    /** A substance still circulating at the session's end. */
    data class Active(
        val active: ActiveSubstance,
        val displayName: String,
        /** How many doses the session contains for this substance, which includes ones no longer circulating. */
        val count: Int,
        /** Everything dosed this session, in [unit]. */
        val sessionTotal: Double,
        val unit: String,
        /** What is still on board, converted into [unit]. */
        val remaining: Double,
    ) {
        /** Identity carries the unit, as `ActiveSubstance` does: two units are not addable and must draw separately. */
        val id: String get() = active.id
    }

    /** A substance the session's end finds nothing left of. */
    data class Cleared(
        val displayName: String,
        val colour: P3Color,
        val total: Double,
        val unit: String,
        val count: Int,
        /**
         * True when this row is here because **no half-life is known**, not because the dose wore off.
         *
         * The distinction the section cannot do without: "this has cleared" and "this cannot be modelled" are
         * different statements, and only one of them is a claim about the substance.
         */
        val unmodeled: Boolean = false,
        /** The parent has cleared, but a longer-lived active metabolite may persist. */
        val hasActiveMetabolite: Boolean = false,
    ) {
        val id: String get() = displayName
    }

    data class Result(
        val active: List<Active> = emptyList(),
        val cleared: List<Cleared> = emptyList(),
    ) {
        val isEmpty: Boolean get() = active.isEmpty() && cleared.isEmpty()
    }

    /**
     * Builds the model for a session.
     *
     * [nameFor] resolves a logged name to the catalogue's canonical name (and its half-life), [tintFor] its colour,
     * [customNameFor] the user's own relabel, and [hasActiveMetabolite] whether a longer-lived metabolite outlasts it.
     */
    fun make(
        entries: List<DoseEntryEntity>,
        catalog: SubstanceCatalog,
        tintFor: (String) -> P3Color,
        fallbackTint: P3Color,
        customNameFor: (String, String?) -> String = { _, fallback -> fallback.orEmpty() },
        hasActiveMetabolite: (String) -> Boolean = { false },
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Result {
        val records = entries.map { it.toDoseRecordForSession() }
        val resolved = ActiveSubstanceCalculator.compute(
            entries = records,
            colorMap = emptyMap(),
            catalog = catalog,
            fallbackTint = fallbackTint,
            now = now,
        )

        // One group per canonical substance **and unit family**, in the unit its first dose used.
        //
        // The family is the calculator's own rule: a unit that can be converted to milligrams is `"mass"`, and
        // anything else (`mL`, `IU`) is its own family. So `mg` and `g` share a group — `500 mg + 1 g` is `1500 mg` —
        // while `mg` and `IU` do not, because there is no conversion between them and adding them would be inventing
        // one. Grouping by substance alone put one substance in two rows, which is what the first test run caught.
        data class Group(
            var name: String,
            var total: Double = 0.0,
            var unit: String,
            var count: Int = 0,
            /**
             * Every product name the group's doses were logged under.
             *
             * The **empty string** stands for "logged under the canonical name", and it is kept rather than dropped
             * so a mixed group cannot be titled by one product: a group holding `""` and `"Concerta"` has two
             * entries and is titled canonically.
             */
            var products: MutableSet<String> = mutableSetOf(),
        )

        val groups = linkedMapOf<String, Group>()
        for (entry in entries) {
            if (entry.isUnknownDose) continue
            val canonical = catalog.lookup(entry.substance)?.name ?: entry.substance
            val key = "${canonical.lowercase()}|${unitFamily(entry.unit)}"
            val product = entry.productName?.trim().orEmpty()
            val existing = groups[key]
            if (existing != null) {
                // Convert into the unit this group already established. An inconvertible unit contributes its count
                // but not its amount.
                DoseUnit.convert(entry.amount, entry.unit, existing.unit)?.let { existing.total += it }
                existing.count += 1
                existing.products += product
            } else {
                groups[key] = Group(
                    name = canonical,
                    total = entry.amount,
                    unit = entry.unit,
                    count = 1,
                    products = mutableSetOf(product),
                )
            }
        }

        /**
         * Titles a group the way the dose rows above it do.
         *
         * A group with **one** distinct product takes that name — the user's own word for the substance. A group
         * mixing products keeps the canonical name, because one brand cannot title a total that is not all that
         * brand. A personal relabel outranks both.
         */
        fun title(canonical: String, products: Set<String>?): String {
            val shared = products?.takeIf { it.size == 1 }?.firstOrNull()?.takeIf { it.isNotEmpty() }
            return customNameFor(canonical, shared) .ifEmpty { shared ?: canonical }
        }

        /** Whether anything can be said about this substance's clearance. */
        fun hasHalfLife(name: String): Boolean =
            PKResolver.halfLifeMinutes(catalog.lookup(name)) != null

        val active = mutableListOf<Active>()
        val cleared = mutableListOf<Cleared>()
        val covered = mutableSetOf<String>()

        for (substance in resolved) {
            // The calculator reports the unit it grouped under, so the same family key finds the same group.
            val key = "${substance.name.lowercase()}|${unitFamily(substance.unit)}"
            covered += key
            val group = groups[key]
            if (substance.eliminatedFraction >= CLEARED_THRESHOLD) {
                cleared += Cleared(
                    displayName = title(substance.name, group?.products),
                    colour = substance.tint,
                    total = group?.total ?: substance.totalDosed,
                    unit = group?.unit ?: substance.unit,
                    count = group?.count ?: substance.doses.size,
                    hasActiveMetabolite = hasActiveMetabolite(substance.name),
                )
            } else {
                // `group` counts every dose in the session while `substance` counts only those still circulating, so
                // the two can settle on different units when the session's first dose was skipped. Convert into the
                // displayed one.
                val unit = group?.unit ?: substance.unit
                val remaining = DoseUnit.convert(substance.totalRemaining, substance.unit, unit)
                    ?: substance.totalRemaining
                active += Active(
                    active = substance,
                    displayName = title(substance.name, group?.products),
                    count = group?.count ?: substance.doses.size,
                    sessionTotal = group?.total ?: substance.totalDosed,
                    unit = unit,
                    remaining = remaining,
                )
            }
        }

        // The substances the calculator dropped, for the two reasons that must not read alike.
        for ((key, group) in groups) {
            if (key in covered) continue
            val unmodeled = !hasHalfLife(group.name)
            // A single worn-off dose is genuinely gone and shows nothing; an unmodelable one always shows, or a
            // substance the user just took would silently vanish.
            if (!unmodeled && group.count <= 1) continue
            cleared += Cleared(
                displayName = title(group.name, group.products),
                colour = tintFor(group.name),
                total = group.total,
                unit = group.unit,
                count = group.count,
                unmodeled = unmodeled,
            )
        }

        return Result(
            active = active,
            // Sorted by title, because the two sources — the calculator's own cleared list and the dropped groups —
            // arrive in different orders and a list that reorders between renders reads as unstable.
            cleared = cleared.sortedBy { it.displayName.lowercase() },
        )
    }

    /**
     * A unit's family, so units that can be added share a group and units that cannot do not.
     *
     * The calculator's own rule, and it must be **the same rule**: the model looks a group up by the family the
     * calculator reported, so a different definition here would simply fail to find it.
     */
    private fun unitFamily(unit: String): String =
        if (DoseUnit.convert(1.0, from = unit, to = "mg") == null) unit else "mass"

    /**
     * How long until the summed curve drops to roughly 3% of what was dosed.
     *
     * Ported from the section's own `clearText`. A **forward scan** rather than a closed form, because the curve is a
     * superposition of every dose in the session, each with its own absorption: one exponential cannot describe it.
     * The horizon is ten half-lives, which is where ~0.1% remains.
     *
     * Null when nothing is left to project — a substance already at zero — which the caller renders as "now" rather
     * than as a time in the past.
     */
    fun clearAt(
        active: ActiveSubstance,
        route: glass.kagerou.piru.model.RouteOfAdministration,
        catalog: SubstanceCatalog,
        now: Instant,
    ): Instant? {
        if (active.totalDosed <= 0.0) return null
        val target = active.totalDosed * 0.03
        val halfLife = active.halfLifeMinutes
        if (halfLife <= 0.0) return null
        val (ke, ka) = PKResolver.rateConstants(
            halfLife,
            catalog.lookup(active.name)?.routes
                ?.firstOrNull { it.route == route }
                ?.duration,
        )
        val horizonMinutes = halfLife * 10
        val stepMinutes = maxOf(1.0, halfLife / 200)
        var minutesAhead = 0.0
        while (minutesAhead <= horizonMinutes) {
            // Not `when` — that is a keyword, and naming a value after it is a syntax error rather than a shadow.
            val at = now.plusSeconds((minutesAhead * 60).toLong())
            var remaining = 0.0
            for (dose in active.doses) {
                val elapsed = Duration.between(dose.timestamp, at).toMinutes().toDouble()
                // A dose in the future has not been absorbed yet and contributes nothing.
                if (elapsed < 0) continue
                remaining += dose.amount * PKModel.fractionRemainingInBody(elapsed, ke, ka)
            }
            if (remaining <= target) return at
            minutesAhead += stepMinutes
        }
        return null
    }

    /** Where in the future a clearance time falls, as a plain fact the caller localises. */
    fun describeClear(at: Instant, now: Instant, zone: ZoneId): String {
        val minutes = Duration.between(now, at).toMinutes()
        return when {
            minutes <= 0 -> "now"
            minutes < 60 -> "$minutes min"
            minutes < 60 * 24 -> "${minutes / 60} h"
            else -> "${minutes / (60 * 24)} d"
        }
    }
}
