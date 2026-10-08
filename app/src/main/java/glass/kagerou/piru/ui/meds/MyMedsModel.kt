package glass.kagerou.piru.ui.meds

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.data.RoutineOccurrenceService
import glass.kagerou.piru.data.entity.DailyDoseItemEntity
import glass.kagerou.piru.data.entity.RoutineOccurrenceEntity
import glass.kagerou.piru.engine.AdherenceCalculator
import glass.kagerou.piru.engine.DoseRecord
import glass.kagerou.piru.engine.InteractionChecker
import glass.kagerou.piru.engine.InteractionPolicy
import glass.kagerou.piru.engine.InteractionProminence
import glass.kagerou.piru.engine.InteractionResult
import glass.kagerou.piru.engine.admitted
import java.time.Instant
import java.time.ZoneId

/**
 * The My Meds card's derived and fetched state: today's checkable slots, the
 * adherence streak, and the interaction warnings a tap has to clear before it
 * logs.
 *
 * Ported from `Piru/Views/Journal/DailyDose/MyMedsModel.swift` (138 lines,
 * including the `MyMedsInfoModel` at its foot).
 *
 * ## Why it is held apart from the card
 * Upstream keeps this in a `@State` on the card so a streak landing or a warning
 * check does not re-evaluate every row, and so the card's own `@State` stays
 * limited to UI toggles. The same split holds here: [MyMedsCard] owns the
 * toggles and this owns the fetched facts, and Compose's snapshot system gives
 * the row-level isolation upstream gets from `@Observable` — a row that reads
 * only its own [MedSlot] does not recompose when [streak] changes.
 */

/** One checkable dose slot's state, as the occurrence row records it. */
internal enum class SlotState { PENDING, TAKEN, SKIPPED }

/**
 * One checkable dose slot: a due med × one of its reminder times (or a single
 * "anytime" slot).
 *
 * Upstream nests this in `MyMedsCard`; it is top level here because
 * [MyMedsModel] and the card both name it and two Kotlin files cannot share a
 * nested type.
 */
internal data class MedSlot(
    val item: DailyDoseItemEntity,
    /** Minutes from midnight, or null for a med with no set time. */
    val time: Int?,
    /** Which of the item's slots this is — part of the row's stable identity. */
    val index: Int,
    val state: SlotState,
) {
    val taken: Boolean get() = state == SlotState.TAKEN

    /**
     * The row's stable id. Identity-keyed rather than name-keyed so two meds
     * that share a display name — a brand and its generic, kept apart by their
     * facets — do not collide in the checklist.
     */
    val id: String get() = "${item.identityKey}+${item.sortOrder}#$index"

    /**
     * Whether this slot is due right now: an untimed slot always is, a timed one
     * only once its minute has passed. A settled slot is never due — the "due"
     * chip must not sit on a row that is already answered.
     */
    fun isDueNow(nowMinutes: Int): Boolean {
        if (state != SlotState.PENDING) return false
        val time = time ?: return true
        return time <= nowMinutes
    }
}

/**
 * The card's fetched state.
 *
 * Upstream is `@Observable @MainActor`; this is a `@Stable` class whose fields
 * are snapshot state, which is the same contract — a write re-reads whoever
 * reads it, and nothing else.
 */
@Stable
internal class MyMedsModel {

    /** The past-year adherence streak — null until fetched. */
    var streak: Int? by mutableStateOf(null)
        private set

    /** The warnings blocking [pendingSlots], shown in the confirmation sheet. */
    var interactionWarnings: List<InteractionResult> by mutableStateOf(emptyList())
        private set

    /** The slots a blocked tap wants to log once the sheet is cleared. */
    var pendingSlots: List<MedSlot> by mutableStateOf(emptyList())
        private set

    /**
     * The past-year streak, walked rather than cached — upstream's
     * `AdherenceStreakStore` has no port. See [MedsStore.streak] for why a year
     * is affordable here.
     */
    suspend fun refreshStreak(
        app: PiruApplication,
        items: List<DailyDoseItemEntity>,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        if (items.isEmpty()) return
        streak = MedsStore.streak(app, items, now, zone)
    }

    /**
     * Whether the tapped slots may log straight away. When they may not, the
     * blocking warnings and the slots are held for the confirmation sheet.
     *
     * Only findings that have earned an interruption stop the log. A `caution`
     * pair — two stimulants, cannabis with a benzo — is true and belongs in the
     * session review, not in front of the button; a sheet that appears for those
     * is a sheet people learn to dismiss unread. That is the same floor as
     * `LogMedicationsView.attemptLog` uses, and it is why both call sites ask for
     * [InteractionPolicy.WARN] rather than the explorer's `EXPLORE`.
     */
    fun mayLog(
        checker: InteractionChecker,
        activeEntries: List<DoseRecord>,
        slots: List<MedSlot>,
        now: Instant = Instant.now(),
    ): Boolean {
        val names = slots.map { it.item.substance }
        val warnings = checker
            .checkBatch(names, against = activeEntries, policy = InteractionPolicy.WARN, now = now)
            .admitted(InteractionProminence.NOTABLE)
        if (warnings.isEmpty()) return true
        pendingSlots = slots
        interactionWarnings = warnings
        return false
    }

    fun clearPending() {
        pendingSlots = emptyList()
        interactionWarnings = emptyList()
    }

    companion object {

        /**
         * Today's checkable dose slots: every due, non-PRN med × one of its
         * reminder times (or a single "anytime" slot), carrying the state its
         * occurrence row records. Earliest slots first.
         *
         * The occurrence index is rebuilt per call rather than passed in as a
         * map, because the caller holds the rows and the key derivation is the
         * service's — a second key implementation is exactly how a logged dose
         * would stop suppressing the re-ask.
         */
        fun slots(
            items: List<DailyDoseItemEntity>,
            occurrences: List<RoutineOccurrenceEntity>,
            now: Instant = Instant.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): List<MedSlot> {
            val byKey = occurrences.associateBy { RoutineOccurrenceService.slotKey(it) }
            val out = ArrayList<MedSlot>()
            for (item in items) {
                if (item.isAsNeeded) continue
                val due = AdherenceCalculator.isDue(
                    startDate = item.startDate.toInstant(),
                    frequency = item.frequency,
                    frequencyDays = item.frequencyDays,
                    on = now,
                    zone = zone,
                )
                if (!due) continue

                val times = item.reminderTimesMinutes.sorted()
                val expected = maxOf(1, times.size)
                for (index in 0 until expected) {
                    // A med with no set times still expects one dose a day; it
                    // just has no time to expect it at, which is the null slot.
                    val slotMinutes = times.getOrNull(index)
                    val key = RoutineOccurrenceService.slotKey(
                        substance = item.substance,
                        substanceUID = item.substanceUID,
                        route = item.route,
                        slotMinutes = slotMinutes,
                    )
                    val state = when (byKey[key]?.state) {
                        RoutineOccurrenceEntity.State.LOGGED -> SlotState.TAKEN
                        RoutineOccurrenceEntity.State.SKIPPED -> SlotState.SKIPPED
                        else -> SlotState.PENDING
                    }
                    out += MedSlot(item = item, time = slotMinutes, index = index, state = state)
                }
            }
            // Untimed slots last, and a stable sort so two slotless meds keep
            // their own order rather than swapping between passes.
            return out.sortedBy { it.time ?: Int.MAX_VALUE }
        }

        /** Minutes from local midnight — the coordinate "due" is measured at. */
        fun nowMinutes(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Int {
            val local = now.atZone(zone)
            return local.hour * 60 + local.minute
        }
    }
}

/**
 * The info lines' fetched facts: each tracked med's supply projection — a dose
 * fetch, so refreshed on the same pass as the streak rather than per body pass —
 * and the missed-notice dismissals.
 *
 * ## The dismissal is read, not mirrored
 * Upstream caches `dismissedDayKeys` in memory and reloads them on refresh.
 * Here the store is a `SharedPreferences` read of a handful of short strings, so
 * [isDismissed] asks it directly — a mirror would be a second copy to keep true
 * for no saved work.
 */
@Stable
internal class MyMedsInfoModel(private val preferences: android.content.SharedPreferences) {

    /** The soonest-to-run-out supply line, or null when nothing is close. */
    var restock: MedsInfoLine? by mutableStateOf(null)
        private set

    /** The schedule the projections were built from, so a repeat pass is free. */
    private var refreshedKey: Int? = null

    suspend fun refresh(
        app: PiruApplication,
        items: List<DailyDoseItemEntity>,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        // The key is the items' own content, not a hand-picked subset of it.
        //
        // It used to be `31*acc + rowId.hashCode() + (isAsNeeded ? 1 : 0)`, on the
        // reasoning that "the schedule is the dominant input and it changes rarely". The
        // problem is that `MedsStore.save` re-inserts an edit under the *same* rowId, and
        // `supplyProjections` reads the substance, salt form and amount — none of which
        // were in the key. So editing a med from Magnesium to Ibuprofen left the restock
        // line reading Magnesium's answer indefinitely, because as far as the cache was
        // concerned nothing had changed.
        //
        // `DailyDoseItemEntity` is a data class, so its `hashCode` already covers every
        // column including the ones the projection reads. Keying on the list is both
        // shorter and correct by construction: a field that starts mattering is included
        // without anyone remembering to add it here.
        val key = items.hashCode()
        if (refreshedKey == key) return
        refreshedKey = key
        restock = MyMedsInfo.restock(MedsStore.supplyProjections(app, items, now, zone))
    }

    fun isDismissed(dayKey: String): Boolean =
        MissedNoticeDismissals.isDismissed(dayKey, preferences)

    fun dismiss(dayKey: String) {
        MissedNoticeDismissals.dismiss(dayKey, preferences)
    }
}
