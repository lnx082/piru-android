package glass.kagerou.piru.notifications

import android.content.Context
import androidx.annotation.StringRes
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.data.RoutineOccurrenceService
import glass.kagerou.piru.data.entity.DailyDoseItemEntity
import glass.kagerou.piru.engine.AdherenceCalculator
import glass.kagerou.piru.model.DoseFrequency
import glass.kagerou.piru.model.doseFormatted
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The routine reminders: one per (medication x reminder time), plus the
 * snooze-style re-ask that follows one that has not been logged.
 *
 * Ported from `DoseNotificationManager`'s `syncMedReminders(items:satisfiedSlots:askAgainDefault:)`
 * and its helpers.
 *
 * ## Reconciliation, not a queue
 * Nothing here adds a reminder to a list. [reconcile] sweeps every routine
 * notification that is armed, works out from the store what *should* be armed
 * right now, and arms exactly that. Running it twice changes nothing, running it
 * after a med is deleted or retimed is the whole mechanism by which the reminder
 * follows, and there is no state that can drift out of step with the store
 * because there is no state: the daily items table is the record.
 *
 * ## Two Android substitutions, both deliberate
 * - **A repeating daily trigger has no equivalent here.** iOS gives a daily med a
 *   `UNCalendarNotificationTrigger(repeats: true)` that fires forever. Android's
 *   alarm is one-shot — `setRepeating` is inexact, batched, and cannot be exact
 *   on API 19+ — so the next occurrence is materialized instead, and
 *   [DUE_PRIMARIES_PER_SLOT] due days are kept armed so a med survives a day the
 *   app is not opened. [MedReminderReconcileWorker] rolls that window forward
 *   once a day, and [enqueueReconcile] refreshes it at every launch and
 *   foreground, which is exactly the event-driven rebuild the iOS side does.
 * - **The re-ask cadence is capped by count, not by a platform budget.** iOS
 *   compresses the follow-up tail to fit the shared 64-pending-request cap;
 *   Android has no such cap, and the cap is kept anyway
 *   ([MAX_FOLLOW_UP_REQUESTS]) because "the nearest thirty, nearest-first" is a
 *   behaviour, not a platform limit, and nothing here should quietly become a
 *   different feature on the other platform.
 *
 * ## The quiet tier
 * Meds marked quiet collapse into **one** grouped reminder per time of day —
 * "Morning supplements (4)" firing at the group's earliest time, silent, with a
 * low priority — rather than one notification each. That is the tier's whole
 * point: a shelf of supplements should not be a shelf of buzzes. Only a med on a
 * daily cadence can ride the grouped reminder (a repeating trigger cannot express
 * weekly), so a non-daily quiet med falls back to its own due-day one-shots.
 *
 * ## What it reads, and what it does not
 * The meds come from `daily_dose_items` through `DailyDoseItemDao`, as entities:
 * the scheduler used to carry its own SQL projection with its own copy of the
 * column names, and the entity already derives every field it wants.
 *
 * The re-ask gate reads **the occurrence record**, never a scan of today's doses
 * — a dose logged at 09:40 does not answer whether the 08:00 slot was taken, and
 * the table exists so that question has a real answer. `RoutineOccurrenceService`
 * is its only writer, and this scheduler asks it for the satisfied set rather
 * than re-deriving one. Both halves of that are load-bearing: an occurrence table
 * nobody writes suppresses nothing, which is exactly the bug this wiring closes.
 *
 * ## Skip Today is a function, not a notification action
 * On iOS the routine reminder carries a **Skip Today** action that runs headless:
 * it marks the remaining due slots skipped and cancels their re-asks without ever
 * launching the UI. Android delivers that with a broadcast receiver, and this
 * app's manifest declares exactly one receiver — [BootCompletedReceiver] — so
 * there is nothing for the action's `PendingIntent` to aim at. [skipToday] is
 * therefore implemented and callable from the app's own surfaces, and the action
 * button is not offered: a notification action that silently did nothing would
 * be worse than the missing button. A second receiver in the manifest is the
 * whole of the fix.
 */
object MedReminderScheduler {

    /** How many days of one-shot re-asks to keep materialized. */
    const val FOLLOW_UP_HORIZON_DAYS: Int = 3

    /** How many upcoming due days a med materializes a primary for. */
    const val DUE_PRIMARIES_PER_SLOT: Int = 2

    /** The nearest cap on materialized re-asks, nearest-first. */
    const val MAX_FOLLOW_UP_REQUESTS: Int = 30

    /** The subscription name for the launch/foreground reconcile. */
    private const val RECONCILE_WORK = "piru.medReminders.reconcile"

    /** The subscription name for the daily window roll-forward. */
    private const val ROLL_FORWARD_WORK = "piru.medReminders.rollForward"

    /** How far ahead the roll-forward pass is queued. */
    private val ROLL_FORWARD_DELAY: Duration = Duration.ofHours(24)

    /**
     * The time-of-day buckets the hub and the grouped reminders share.
     *
     * Ported from `MedTimeGroup`. The boundaries are the iOS ones: before 12:00,
     * 12:00-17:00, 17:00-21:00, after 21:00. A med slots itself in from its
     * reminder times — there are no named containers to create.
     *
     * [slug] is the wire value: it rides the `piru://quicklog?routine=<slug>`
     * deep link and the `group|<slug>` skip target, so it stays English and
     * stays put. [labelRes] is the word the user reads, which is why the two are
     * no longer one field.
     */
    enum class TimeGroup(val slug: String, @StringRes val labelRes: Int) {
        MORNING("morning", R.string.notif_med_group_morning),
        AFTERNOON("afternoon", R.string.notif_med_group_afternoon),
        EVENING("evening", R.string.notif_med_group_evening),
        NIGHT("night", R.string.notif_med_group_night),
        ;

        companion object {
            /** The group a reminder time falls in. Minutes from midnight. */
            fun of(minutes: Int): TimeGroup = when {
                minutes < 720 -> MORNING
                minutes < 1_020 -> AFTERNOON
                minutes < 1_260 -> EVENING
                else -> NIGHT
            }

            fun fromSlug(slug: String): TimeGroup? = entries.firstOrNull { it.slug == slug }
        }
    }

    /** One materialized re-ask: which day it belongs to, which offset, and when it fires. */
    data class FollowUpSlot(val dayKey: String, val ordinal: Int, val fireDate: Instant)

    /**
     * The one-shot fire slots for a routine's re-asks over the rolling horizon:
     * `timeMinutes + offset` on each of the next [days] days, dropping times
     * already past and, when [skipToday], all of today's.
     *
     * Pure, and takes its zone, so the arithmetic can be tested against a pinned
     * clock — which it needs, because the interesting cases are all at the edges
     * of a day.
     */
    fun followUpFireDates(
        timeMinutes: Int,
        offsets: List<Int>,
        days: Int,
        skipToday: Boolean,
        now: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<FollowUpSlot> {
        val today = now.atZone(zone).toLocalDate()
        val slots = ArrayList<FollowUpSlot>()
        for (day in 0 until maxOf(0, days)) {
            if (day == 0 && skipToday) continue
            val date = today.plusDays(day.toLong())
            val dayStart = date.atStartOfDay(zone).toInstant()
            for ((ordinal, offset) in offsets.withIndex()) {
                val fireDate = dayStart.plusSeconds((timeMinutes + offset) * 60L)
                if (fireDate > now) slots += FollowUpSlot(dayKey(date), ordinal, fireDate)
            }
        }
        return slots
    }

    /**
     * Reconcile the routine reminders against the store.
     *
     * With the type disabled this degrades to a sweep: everything armed is
     * cleared and nothing is scheduled. The per-med `remind` flags are left
     * untouched, so re-enabling the type re-arms them without the user having to
     * find each switch again. The occurrence record is re-derived either way —
     * that record is the app's, not a notification system's.
     */
    suspend fun reconcile(context: Context, zone: ZoneId = ZoneId.systemDefault()) {
        val app = context.applicationContext as? PiruApplication ?: return

        // The occurrence record first, before anything reads the satisfied set.
        // This is upstream's `syncMedReminders` order, and it is the reason the
        // re-ask gate below is reading a record re-derived from the doses that
        // were actually logged. It runs even when the reminder type is off: the
        // record belongs to the app, not to the notification system.
        app.routineOccurrences().reconcile(zone = zone)

        // Sweep first, and always: the current prefixes plus the legacy sets, so a
        // request armed by an older build migrates on the first pass rather than
        // lingering as an un-cancellable ghost.
        val sweep = NotificationType.ROUTINE.identifierPrefixes +
            NotificationType.ROUTINE_FOLLOW_UP.identifierPrefixes +
            NotificationType.DAILY_DOSE_REMINDER_LEGACY_PREFIX
        PiruNotifications.cancelPending(context, sweep)

        val remindersAllowed = NotificationPreferencesStore.allows(context, NotificationType.ROUTINE)
        if (!remindersAllowed) return
        val followUpsAllowed = NotificationPreferencesStore.allows(context, NotificationType.ROUTINE_FOLLOW_UP)

        val now = Instant.now()
        val meds = app.database.dailyDoseItemDao().all()
        val satisfied = app.routineOccurrences().satisfiedSlotKeys(now = now, zone = zone)
        val askAgainDefault = NotificationPreferencesStore(context).load().askAgainDefaultMinutes

        // The global reminder offset, read once per sync rather than once per med. From `AppSettingsStore` rather than
        // the notification preferences, because that is where the settings screen writes it — one decision with two
        // homes is how the two come to disagree.
        val reminderOffsetMinutes = glass.kagerou.piru.data.AppSettingsStore(context)
            .adherenceReminderOffsetMinutes()

        val scheduled = meds.filter { !it.isAsNeeded && it.remind && it.reminderTimesMinutes.isNotEmpty() }

        val followUps = ArrayList<PlannedFollowUp>()

        /**
         * One med's (x reminder time) primaries and re-asks.
         *
         * `sortOrder` is part of the anchor because two meds can share an identity,
         * a route and a time — without it their identifiers collide and the second
         * silently wins.
         */
        fun schedulePerMed(med: DailyDoseItemEntity) {
            val name = med.productName ?: med.substance
            val doseText = "${doseFormatted(med.amount)} ${med.unit}"
            val cadence = med.askAgainOverrideMinutes ?: askAgainDefault
            for (time in med.reminderTimesMinutes.sorted()) {
                val anchor = "${anchorSlug(med)}.${med.sortOrder}.$time"
                val slotKey = RoutineOccurrenceService.slotKey(med.substance, med.substanceUID, med.route, time)
                val isSatisfied = satisfied.contains(slotKey)
                val threadId = medThreadIdentifier(anchorSlug(med))
                val deepLink = groupDeepLink(TimeGroup.of(time).slug)

                for ((index, fireAt) in primaryFireDates(med, time, now, zone, reminderOffsetMinutes).withIndex()) {
                    val isToday = fireAt < now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
                    if (isToday && isSatisfied) continue
                    // A daily med's first upcoming slot keeps the un-suffixed
                    // identifier, which is the one iOS mints for its repeating
                    // trigger; everything after it is day-keyed, because Android's
                    // occurrence is a single alarm rather than a standing rule.
                    val ordinal = if (index == 0 && med.frequency == DoseFrequency.DAILY) {
                        null
                    } else {
                        dayKey(fireAt.atZone(zone).toLocalDate())
                    }
                    armPrimary(
                        context = context,
                        identifier = NotificationType.ROUTINE.identifier(anchor, ordinal),
                        title = name,
                        body = context.getString(R.string.notif_med_reminder_body, name, doseText),
                        fireAt = fireAt,
                        threadId = threadId,
                        deepLink = deepLink,
                        silent = med.isQuiet,
                        // One slot, so skipping silences this medication at this
                        // time and leaves the user's other meds alone.
                        skipTarget = "slot|$slotKey",
                    )
                    // The re-ask belongs to this primary, so it is only materialized
                    // where the primary is.
                    if (!followUpsAllowed || cadence.isEmpty()) continue
                    val followUpBody = context.getString(R.string.notif_med_follow_up_body, name)
                    for (slot in followUpFireDates(time, cadence, FOLLOW_UP_HORIZON_DAYS, isSatisfied, now, zone)) {
                        followUps += PlannedFollowUp(
                            identifier = NotificationType.ROUTINE_FOLLOW_UP.identifier(
                                anchor,
                                "${slot.dayKey}.${slot.ordinal}",
                            ),
                            title = name,
                            body = followUpBody,
                            threadId = threadId,
                            deepLink = deepLink,
                            fireDate = slot.fireDate,
                            silent = med.isQuiet,
                        )
                    }
                }
            }
        }

        // Regular meds: one primary per (med x time).
        for (med in scheduled.filter { !it.isQuiet }) schedulePerMed(med)

        // Quiet meds: one grouped primary per time-of-day, at the group's earliest
        // time. Only the daily cadence can ride the group; a non-daily quiet med
        // falls back to its own due-day one-shots, which at a weekly cadence is one
        // silent notification a week rather than a problem.
        val quiet = scheduled.filter { it.isQuiet }
        for (med in quiet.filter { it.frequency != DoseFrequency.DAILY }) schedulePerMed(med)

        for (group in TimeGroup.entries) {
            val members = quiet
                .filter { it.frequency == DoseFrequency.DAILY }
                .flatMap { med -> med.reminderTimesMinutes.filter { TimeGroup.of(it) == group }.map { med to it } }
            val earliest = members.minOfOrNull { it.second } ?: continue
            val names = members.map { (med, _) -> med.productName ?: med.substance }.distinct()
            val anchor = "group.${group.slug}"
            val threadId = medThreadIdentifier(anchor)
            val deepLink = groupDeepLink(group.slug)
            // Resolved once here because it is used twice — bare in the title,
            // lowercased mid-sentence in the re-ask. English needs the case
            // change; Chinese has no case, so the call is a no-op there rather
            // than a second string to keep in step.
            val groupLabel = context.getString(group.labelRes)

            for (fireAt in dailyFireDates(earliest, now, zone)) {
                val allSatisfied = members.all { (med, time) ->
                    satisfied.contains(RoutineOccurrenceService.slotKey(med.substance, med.substanceUID, med.route, time))
                }
                if (allSatisfied && fireAt < startOfTomorrow(now, zone)) continue
                armPrimary(
                    context = context,
                    identifier = NotificationType.ROUTINE.identifier(
                        anchor,
                        if (fireAt < startOfTomorrow(now, zone)) null else dayKey(fireAt.atZone(zone).toLocalDate()),
                    ),
                    title = context.getString(R.string.notif_med_group_title, groupLabel, names.size),
                    body = names.joinToString(", "),
                    fireAt = fireAt,
                    threadId = threadId,
                    deepLink = deepLink,
                    // The whole group, because the notification is a group: it
                    // names several supplements at once, so skipping one of them
                    // would leave the button meaning something the title does not.
                    skipTarget = "group|${group.slug}",
                    // The quiet tier is the grouped reminder's entire reason to
                    // exist: one silent line for the shelf, not one per bottle.
                    silent = true,
                )
            }

            if (!followUpsAllowed || askAgainDefault.isEmpty()) continue
            val allSatisfied = members.all { (med, time) ->
                satisfied.contains(RoutineOccurrenceService.slotKey(med.substance, med.substanceUID, med.route, time))
            }
            for (slot in followUpFireDates(earliest, askAgainDefault, FOLLOW_UP_HORIZON_DAYS, allSatisfied, now, zone)) {
                followUps += PlannedFollowUp(
                    identifier = NotificationType.ROUTINE_FOLLOW_UP.identifier(
                        anchor,
                        "${slot.dayKey}.${slot.ordinal}",
                    ),
                    title = context.getString(R.string.notif_med_group_title, groupLabel, names.size),
                    body = context.getString(
                        R.string.notif_med_group_follow_up_body,
                        groupLabel.lowercase(Locale.ROOT),
                    ),
                    threadId = threadId,
                    deepLink = deepLink,
                    fireDate = slot.fireDate,
                    silent = true,
                )
            }
        }

        // Re-asks are the compressible tail: keep the nearest, because the horizon
        // rolls forward on every pass and a trimmed one reappears as its day nears.
        followUps.sortBy { it.fireDate }
        if (followUps.size > MAX_FOLLOW_UP_REQUESTS) {
            followUps.subList(MAX_FOLLOW_UP_REQUESTS, followUps.size).clear()
        }

        for (followUp in followUps) {
            // Re-asks are silenced inside the quiet window; the primaries above are
            // not, because an exact time the user chose is honoured as chosen.
            if (NotificationPreferencesStore.isInQuietHours(context, followUp.fireDate, zone)) continue
            PiruNotifications.scheduleExact(
                context = context,
                payload = PlannedNotification(
                    identifier = followUp.identifier,
                    channelId = NotificationType.ROUTINE_FOLLOW_UP.channelId,
                    title = followUp.title,
                    body = followUp.body,
                    threadKey = followUp.threadId,
                    deepLink = followUp.deepLink,
                    silent = followUp.silent,
                ),
                fireAt = followUp.fireDate,
            )
        }
    }

    /**
     * Queue a reconcile pass for launch and foreground.
     *
     * A one-time request, replaced rather than queued: two passes racing each
     * other would each sweep the other's work, and the last one to finish would
     * decide. Launch and foreground are the events the iOS side rebuilds on, so
     * they are the events this rebuilds on.
     */
    fun enqueueReconcile(context: Context) {
        val request = OneTimeWorkRequestBuilder<MedReminderReconcileWorker>().build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(RECONCILE_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Queue tomorrow's roll-forward.
     *
     * A one-time request that queues its own successor, not a
     * `PeriodicWorkRequest`: the pass it performs is a re-derivation from the
     * store, and a periodic request would promise a cadence Android does not
     * guarantee anyway. This exists because a med's primaries cover
     * [DUE_PRIMARIES_PER_SLOT] days and, without a nudge, an app nobody opens for
     * a week would stop reminding — which is the one failure a reminder app
     * cannot have. `KEEP` so a second queueing does not push the horizon out.
     */
    fun scheduleRollForward(context: Context) {
        val request = OneTimeWorkRequestBuilder<MedReminderReconcileWorker>()
            .setInitialDelay(ROLL_FORWARD_DELAY.toHours(), TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(ROLL_FORWARD_WORK, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Apply the Skip Today action.
     *
     * [target] is the request's skip payload: `slot|<slotKey>` for one med's
     * reminder, or `group|<groupSlug>` for a grouped quiet-med reminder. A group
     * is expanded to its members' slots **at action time** rather than at
     * scheduling time, so a med added or retimed since the reminder was armed
     * still resolves to what the user is looking at.
     *
     * Marks the still-pending occurrences for today as skipped and reconciles, so
     * the remaining re-asks for those slots cancel immediately — the point of the
     * action is that dismissing the nag dismisses it, not that it comes back in
     * ten minutes.
     */
    suspend fun skipToday(context: Context, target: String, zone: ZoneId = ZoneId.systemDefault()) {
        val app = context.applicationContext as? PiruApplication ?: return

        val keys: Set<String> = when {
            target.startsWith("slot|") -> setOf(target.removePrefix("slot|"))

            target.startsWith("group|") -> {
                val group = TimeGroup.fromSlug(target.removePrefix("group|")) ?: return
                app.database.dailyDoseItemDao().all()
                    .filter { it.isQuiet && !it.isAsNeeded }
                    .flatMap { med ->
                        med.reminderTimesMinutes
                            .filter { TimeGroup.of(it) == group }
                            .map { RoutineOccurrenceService.slotKey(med.substance, med.substanceUID, med.route, it) }
                    }
                    .toSet()
            }

            else -> return
        }
        if (keys.isEmpty()) return

        // The record, through its one writer — this class does not write an
        // occurrence state itself.
        app.routineOccurrences().skipToday(keys, zone = zone)

        reconcile(context, zone)
    }

    // MARK: - Internals

    private class PlannedFollowUp(
        val identifier: String,
        val title: String,
        val body: String,
        val threadId: String,
        val deepLink: String?,
        val fireDate: Instant,
        val silent: Boolean,
    )

    /**
     * @param skipTarget what the notification's "Skip today" button silences —
     *   `"slot|<slotKey>"` for one medication, `"group|<slug>"` for a whole
     *   time-of-day group. Null when there is nothing to skip, in which case the
     *   notification carries no button: a button that does not know what it acts
     *   on is worse than no button.
     */
    private fun armPrimary(
        context: Context,
        identifier: String,
        title: String,
        body: String,
        fireAt: Instant,
        threadId: String,
        deepLink: String?,
        silent: Boolean,
        skipTarget: String?,
    ) {
        if (fireAt <= Instant.now()) return
        // A primary is the time the user chose, so quiet hours do not silence it —
        // the same rule the iOS side states: an exact time someone set is honoured
        // as set. Only the re-asks are gated, above.
        PiruNotifications.scheduleExact(
            context = context,
            payload = PlannedNotification(
                identifier = identifier,
                channelId = NotificationType.ROUTINE.channelId,
                title = title,
                body = body,
                threadKey = threadId,
                deepLink = deepLink,
                silent = silent,
                // The label is resolved here, not at post time: the payload —
                // label included — is encoded into the alarm's intent and read
                // back by a receiver that has no scheduler around it.
                actions = listOfNotNull(
                    skipTarget?.let { PlannedAction(context.getString(R.string.notif_action_skip_today), it) },
                ),
            ),
            fireAt = fireAt,
        )
    }

    private fun primaryFireDates(
        med: DailyDoseItemEntity,
        timeMinutes: Int,
        now: Instant,
        zone: ZoneId,
        offsetMinutes: Int = 0,
    ): List<Instant> =
        dueDays(med, DUE_PRIMARIES_PER_SLOT, now, zone)
            .map { day ->
                val fireMinute = ReminderOffset.apply(scheduledMinutes = timeMinutes, offsetMinutes = offsetMinutes)
                day.atStartOfDay(zone).toInstant().plusSeconds(fireMinute * 60L)
            }
            .filter { it > now }

    private fun dailyFireDates(timeMinutes: Int, now: Instant, zone: ZoneId): List<Instant> {
        val today = now.atZone(zone).toLocalDate()
        return (0..DUE_PRIMARIES_PER_SLOT).mapNotNull { offset ->
            val date = today.plusDays(offset.toLong())
            val fireAt = date.atStartOfDay(zone).toInstant().plusSeconds(timeMinutes * 60L)
            if (fireAt > now) fireAt else null
        }.take(DUE_PRIMARIES_PER_SLOT)
    }

    /**
     * The next [limit] days a med is due on, scanned far enough ahead to cover a
     * monthly cadence without the app being opened in between.
     */
    private fun dueDays(med: DailyDoseItemEntity, limit: Int, now: Instant, zone: ZoneId): List<LocalDate> {
        val today = now.atZone(zone).toLocalDate()
        val result = ArrayList<LocalDate>(limit)
        for (offset in 0 until 62) {
            val date = today.plusDays(offset.toLong())
            val due = AdherenceCalculator.isDue(
                startDate = med.startDate.toInstant(),
                frequency = med.frequency,
                frequencyDays = med.frequencyDays,
                on = date.atStartOfDay(zone).toInstant(),
                zone = zone,
            )
            if (!due) continue
            result += date
            if (result.size == limit) break
        }
        return result
    }

    private fun startOfTomorrow(now: Instant, zone: ZoneId): Instant =
        now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()

    private fun dayKey(date: LocalDate): String =
        "%04d%02d%02d".format(java.util.Locale.ROOT, date.year, date.monthValue, date.dayOfMonth)

    /**
     * A med's stable identifier fragment: its identity key plus route, sanitized
     * to the identifier grammar's alphabet. Every character the grammar does not
     * name becomes a dash, so a substance called "Methylphenidate (XR)" cannot put
     * a bracket in a request identifier.
     */
    private fun anchorSlug(med: DailyDoseItemEntity): String =
        "${med.identityKey}-${med.route.wireValue}".lowercase().map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")

    private fun medThreadIdentifier(anchor: String) = "piru.notif.thread.med.$anchor"

    /** `piru://quicklog?routine=<slug>` — the query key stays `routine` for deep-link compatibility. */
    private fun groupDeepLink(slug: String) =
        "${DoseNotificationScheduler.DEEP_LINK_SCHEME}://quicklog?routine=$slug"

}

/**
 * The reconcile pass as a piece of work, so it can outlive the process that
 * queued it.
 *
 * A `CoroutineWorker` rather than an `AlarmManager` alarm: the pass is
 * re-derivation from the store, not a delivery at a moment, so it wants the
 * guarantee WorkManager gives — it will run, eventually, even if the app is not
 * open — rather than the precision an alarm gives, which a rebuild does not need.
 */
class MedReminderReconcileWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        MedReminderScheduler.reconcile(applicationContext)
        // Queue tomorrow's pass. The primaries cover a couple of days, so this is a
        // top-up rather than the mechanism — but without it an app nobody opens for
        // a week stops reminding, and a reminder that quietly stops is worse than
        // one that never existed.
        MedReminderScheduler.scheduleRollForward(applicationContext)
    }.fold(
        onSuccess = { Result.success() },
        // Not `failure`: a store that could not be read this pass may read fine on
        // the next, and a reminder schedule that gives up permanently on one bad
        // read is the failure mode this whole type exists to avoid.
        onFailure = { Result.retry() },
    )
}

/**
 * When a reminder actually fires, given its scheduled time and the user's global delay.
 *
 * ## The clamp is not optional
 * A plain `scheduled + offset` can push a 23:50 reminder into the **next day**, which breaks two things at once: the
 * satisfaction check compares a fire date against the slot key of the *scheduled* time, so a reminder arriving after
 * midnight is checked against a slot that has already expired; and the "first upcoming slot" arithmetic, which the
 * un-suffixed notification identifier depends on, would see a date belonging to tomorrow.
 *
 * So the delay is clamped to the minutes left in the day. A 23:50 reminder with a 30-minute delay fires at 23:59 —
 * later than asked and on the right day, which is the better of the two failures. The setting says "minutes after the
 * scheduled time", and this is the one case where it cannot be exactly that.
 *
 * Extracted from the scheduler's private arithmetic so the rule has a test: the failure it prevents is invisible on
 * screen and only shows as a reminder that never appears.
 */
internal object ReminderOffset {

    /** Minutes in a day, the bound the clamp works against. */
    const val MINUTES_PER_DAY: Int = 24 * 60

    /**
     * The minute of the day this reminder fires at.
     *
     * A negative offset is treated as zero rather than moving the reminder **earlier** than the dose is due: the store
     * clamps what it holds, so a negative here is a bug rather than a preference, and firing before the dose is due is
     * the worse of the two ways to be wrong about it.
     */
    fun apply(scheduledMinutes: Int, offsetMinutes: Int): Int {
        val scheduled = scheduledMinutes.coerceIn(0, MINUTES_PER_DAY - 1)
        val untilMidnight = MINUTES_PER_DAY - 1 - scheduled
        val applied = offsetMinutes.coerceIn(0, untilMidnight)
        return scheduled + applied
    }
}
