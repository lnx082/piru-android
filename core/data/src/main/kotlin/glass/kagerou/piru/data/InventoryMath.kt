package glass.kagerou.piru.data

import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.data.entity.InventoryItemEntity
import glass.kagerou.piru.engine.SubstanceCatalog
import glass.kagerou.piru.model.DoseUnit
import java.time.Instant
import java.time.ZoneId

/**
 * The arithmetic behind a stock level — replay, conversion, and when it runs out.
 *
 * Ported from `InventoryMath` in `Piru/Data/Services/InventoryService.swift`.
 *
 * ## Everything here is a function of the log
 * Nothing in this file reads or writes a total. A stock level is the sum of what
 * was put in (the manual events) minus what was taken out (the doses), and the
 * loop that computes it is short enough to run on demand. That is the design
 * worth preserving: it means a user who forgot to log a restock can log it late,
 * backdated, and every number downstream is right again.
 */
object InventoryMath {

    /**
     * How fast a supply is going, and how long it lasts at that rate.
     *
     * Both figures are deliberately conservative: [dailyAvg] is over a fixed seven
     * days rather than the days that happen to have doses, so a burst of use reads
     * as a burst rather than being averaged away into a reassuring trend.
     */
    data class RunOut(val dailyAvg: Double, val daysLeft: Double)

    /** A dose as the replay sees it — just enough to subtract. */
    data class DoseSnapshot(val amount: Double, val unit: String, val timestamp: Instant)

    /**
     * Count units. Compared after trimming and lowercasing, because these strings
     * come from a free-text form field and "Tabs" is the same tablet.
     */
    private val COUNT_UNITS = setOf("tabs", "caps")

    fun isCountUnit(unit: String): Boolean = COUNT_UNITS.contains(unit.trim().lowercase())

    /**
     * The key a dose and a stock item meet on: the substance's PSID family when it
     * resolves, else the lowercased name.
     *
     * Resolving through the family is what makes "Vyvanse" subtract from a stock
     * tracked as "Lisdexamfetamine", and falling back to the bare name keeps a
     * substance the catalog does not carry — a user's own blend — still trackable.
     */
    fun matchKey(substance: String, substanceUID: String?, catalog: SubstanceCatalog): String =
        substanceUID ?: catalog.substanceUID(substance) ?: substance.lowercase()

    /**
     * Convert between units, including between a mass and a count of tablets.
     *
     * Mass-to-mass goes through [DoseUnit], which handles the µg/mg/g family. A
     * count conversion needs [unitStrengthMG] — the mass of one unit — and returns
     * null without it, which is the honest answer: "how many tablets is 40 mg" has
     * no answer until someone says how big a tablet is.
     */
    fun convert(
        amount: Double,
        from: String,
        to: String,
        unitStrengthMG: Double? = null,
    ): Double? {
        DoseUnit.convert(amount, from, to)?.let { return it }
        val strength = unitStrengthMG ?: return null
        if (strength <= 0) return null
        if (isCountUnit(to)) DoseUnit.convert(amount, from, "mg")?.let { return it / strength }
        if (isCountUnit(from)) return DoseUnit.convert(amount * strength, from = "mg", to = to)
        return null
    }

    /**
     * The quantity on hand: every manual event applied, every dose subtracted.
     *
     * ## Only doses and adjustments floor at zero
     * Two kinds of tick can overwrite a running balance: a dose, and an
     * adjustment. Both floor at zero, so a user who logs a 50 mg dose against a
     * stock that says 30 ends at zero rather than at −20 — the log is a record of
     * what happened, and going negative would then make the *next* restock read as
     * smaller than it was.
     *
     * A restock or an initial count does **not** floor, so it can pass through
     * negative on its way to correcting an over-count. That asymmetry is the whole
     * point: a dose is a fact, a correction is a user's best guess, and only the
     * guess is allowed to be wrong in the meantime.
     *
     * ## A dose that cannot be converted is skipped, not fatal
     * `convert` returns null for a millilitre against a milligram stock with no
     * strength, or an IU, or a spray. Those doses are dropped and the replay
     * continues, because the alternative — refusing to produce a number — leaves
     * the user with no stock readout at all over one un-convertible row.
     *
     * ## Sort order is explicit
     * Swift's `sorted(by:)` is not stable, so two events at the same instant (a
     * restock and a dose logged together) had undefined order upstream. Here the
     * comparison is total: by date, then flooring ticks before non-flooring ones,
     * then by amount. A deterministic order is the only way the same log produces
     * the same number twice.
     */
    fun replayQuantity(
        unit: String,
        unitStrengthMG: Double?,
        events: List<ManualEvent>,
        doses: List<DoseSnapshot>,
    ): Double {
        val ticks = ArrayList<Tick>(events.size + doses.size)
        for (event in events) {
            ticks += Tick(event.date, event.amount, event.kind == ManualEvent.Kind.ADJUSTMENT)
        }
        for (dose in doses) {
            val converted = convert(dose.amount, dose.unit, unit, unitStrengthMG) ?: continue
            ticks += Tick(dose.timestamp, -converted, floors = true)
        }

        var balance = 0.0
        for (tick in ticks.sortedWith(TICK_ORDER)) {
            balance = if (tick.floors) maxOf(0.0, balance + tick.delta) else balance + tick.delta
        }
        return balance
    }

    /** One replay step: a signed change, at an instant, and whether it floors at zero. */
    private data class Tick(val date: Instant, val delta: Double, val floors: Boolean)

    /**
     * The order the replay applies ticks in.
     *
     * Date first — that is the whole semantics — then a total tiebreak, because
     * Swift's `sorted(by:)` is not stable and two events at the same instant had
     * undefined order upstream. Determinism is the first requirement: the same log
     * must produce the same number twice.
     *
     * ## Within an instant, additions land before corrections
     * `it.floors` sorts ascending, so the ticks that **cannot** go negative — a
     * restock, an opening count — are applied before the ones that can. The
     * ordering matters and is observable: against a balance of zero, a 50 mg dose
     * and a 100 mg restock at the same millisecond leave **50** this way and
     * **100** the other, because applying the dose first clamps it against a
     * balance the restock had not yet topped up — silently discarding the dose.
     *
     * The same reasoning decides the other shape: an opening count of 100 and an
     * adjustment of −250 at one instant settle at **0**, not 100. The other order
     * floors the adjustment against a zero balance and then adds the count, which
     * means the user's correction never happened.
     *
     * Neither order can know which event the user meant first — a shared timestamp
     * carries no information — so the tiebreak goes to the one that does not throw
     * away a fact. A dose that was logged is a fact; a correction is a statement
     * about the balance; and the balance is what the addition produces.
     */
    private val TICK_ORDER: Comparator<Tick> =
        compareBy({ it.date }, { it.floors }, { it.delta })

    /**
     * The doses that count against [item], taken from the doses already bucketed
     * by identity.
     *
     * Three filters, and dropping any one changes the number:
     * - **identity bucket** — the family key, so a brand name still subtracts;
     * - **salt form strictly equal**, null matching only null;
     * - **[InventoryItemEntity.trackingStart]** — dosing that predates tracking is
     *   not consumption of a stock that did not exist yet.
     *
     * Unknown doses (`isUnknownDose`) carry no amount, so there is nothing to
     * subtract and they are excluded rather than counted as zero.
     */
    fun dosesFor(
        item: InventoryItemEntity,
        dosesByMatchKey: Map<String, List<DoseEntryEntity>>,
        catalog: SubstanceCatalog,
    ): List<DoseEntryEntity> {
        val bucket = dosesByMatchKey[matchKey(item.substance, null, catalog)] ?: return emptyList()
        return bucket.filter {
            it.timestamp.toInstant() >= item.trackingStart &&
                it.saltForm == item.saltForm &&
                !it.isUnknownDose
        }
    }

    /** Bucket doses by their match key once, so a list of items does not re-derive it per row. */
    fun bucketDoses(
        doses: List<DoseEntryEntity>,
        catalog: SubstanceCatalog,
    ): Map<String, List<DoseEntryEntity>> = doses.groupBy { matchKey(it.substance, it.substanceUID, catalog) }

    /** The quantity on hand, replayed from the row's own history and the log. */
    fun quantity(
        item: InventoryItemEntity,
        dosesByMatchKey: Map<String, List<DoseEntryEntity>>,
        catalog: SubstanceCatalog,
    ): Double = replayQuantity(
        unit = item.unit,
        unitStrengthMG = item.unitStrengthMG,
        events = item.manualEvents,
        doses = dosesFor(item, dosesByMatchKey, catalog).map {
            DoseSnapshot(it.amount, it.unit, it.timestamp.toInstant())
        },
    )

    /**
     * The dose size to divide by: the user's own setting when they gave one, else
     * the catalog's common dose.
     *
     * Falling back to the catalog is what lets "~12 doses left" appear on a supply
     * that was never given a dose size — and it is a *reference*, not a claim
     * about how this user doses, which is why the user's own number always wins.
     */
    fun effectiveDoseSize(
        item: InventoryItemEntity,
        catalog: SubstanceCatalog,
    ): Double? = item.doseSize?.takeIf { it > 0 }
        ?: referenceDose(item.substance, item.saltForm, item.unit, catalog)

    /** Whole doses remaining, rounded down — a partial dose is not a dose. Null when no size is known. */
    fun dosesLeft(item: InventoryItemEntity, catalog: SubstanceCatalog): Int? {
        val size = effectiveDoseSize(item, catalog) ?: return null
        if (size <= 0) return null
        return kotlin.math.floor(item.currentQuantity / size).toInt()
    }

    /**
     * The catalog's idea of one dose: the common band's low end, else the light
     * band's top, else the strong band's floor, else the threshold, else the heavy
     * line.
     *
     * The cascade walks from the most ordinary figure to the least, and the unit
     * has to match the stock's own — a catalog entry dosed in µg says nothing
     * about a supply counted in mg, so it is treated as no answer rather than
     * silently converted through an assumption about which salt is in the jar.
     */
    fun referenceDose(
        substance: String,
        saltForm: String?,
        unit: String,
        catalog: SubstanceCatalog,
    ): Double? {
        val entry = catalog.lookup(substance) ?: return null
        val route = entry.defaultRoute
        val range = entry.doseRange(route, saltForm) ?: return null
        if (entry.unit(route, saltForm) != unit) return null
        return range.common?.start
            ?: range.light?.endInclusive
            ?: range.strong?.start
            ?: range.threshold
            ?: range.heavy
    }

    /**
     * A strong dose, for seeding a supply's first count.
     *
     * Deliberately the *strong* end rather than the common one: this number only
     * ever becomes a form field's starting value, and a starting value that is too
     * low makes the user step up through a hundred taps, while one that is too
     * high is a single edit.
     */
    fun representativeStrongDose(
        substance: String,
        saltForm: String?,
        unit: String,
        catalog: SubstanceCatalog,
    ): Double? {
        val entry = catalog.lookup(substance) ?: return null
        val route = entry.defaultRoute
        val range = entry.doseRange(route, saltForm) ?: return null
        if (entry.unit(route, saltForm) != unit) return null
        range.strong?.let { return (it.start + it.endInclusive) / 2 }
        range.heavy?.let { return it }
        range.common?.let { return it.endInclusive }
        range.light?.let { return it.endInclusive }
        range.threshold?.let { return it * 3 }
        return null
    }

    /**
     * How long the supply lasts at the current rate, or null when there is not
     * enough history to say.
     *
     * ## The two guards are the point
     * A run-out estimate built from one dose on one day is a rumour. So this needs
     * **at least five distinct days** with a dose in the last seven, and it divides
     * the week's consumption by **seven, not by the number of days used**. Dividing
     * by the days that had doses would report an occasional user's rate as if they
     * used daily, and every supply would read as running out sooner than it does.
     */
    fun runOut(
        item: InventoryItemEntity,
        dosesByMatchKey: Map<String, List<DoseEntryEntity>>,
        catalog: SubstanceCatalog,
        now: Instant,
        zone: ZoneId,
    ): RunOut? {
        val weekAgo = now.minusSeconds(7 * 24 * 60 * 60L)
        val recent = dosesFor(item, dosesByMatchKey, catalog)
            .filter { val t = it.timestamp.toInstant(); t >= weekAgo && t <= now }

        val daysWithADose = recent
            .map { it.timestamp.toInstant().atZone(zone).toLocalDate() }
            .toSet()
            .size
        if (daysWithADose < 5) return null

        val consumed = recent.sumOf {
            convert(it.amount, it.unit, item.unit, item.unitStrengthMG) ?: 0.0
        }
        val dailyAvg = consumed / 7.0
        if (dailyAvg <= 0) return null
        return RunOut(dailyAvg, item.currentQuantity / dailyAvg)
    }
}
