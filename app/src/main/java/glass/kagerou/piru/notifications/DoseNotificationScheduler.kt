package glass.kagerou.piru.notifications

import android.content.Context
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.engine.ActiveSubstanceState
import glass.kagerou.piru.engine.DoseRecord
import glass.kagerou.piru.engine.PKModel
import glass.kagerou.piru.engine.SubstanceCatalog
import glass.kagerou.piru.engine.from
import glass.kagerou.piru.model.BaseReleaseForm
import glass.kagerou.piru.model.DoseUnit
import glass.kagerou.piru.model.DurationRange
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.SubstanceCategory
import glass.kagerou.piru.model.doseFormatted
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.math.min

/**
 * The notification layer's channels, permission and delivery live in
 * [PiruNotifications]; the *timing* of every alert that hangs off a logged dose
 * lives here.
 *
 * Ported from `Piru/Utilities/SessionNotificationScheduler.swift`, and driven by
 * `DoseNotificationManager`'s lifecycle hooks (`doseLogged`,
 * `doseRescheduled`, `doseDeleted`).
 *
 * ## What a dose schedules
 * Three families, and only these three:
 * - **Wellness** — two hydration nudges and, for the wake-promoting classes, a
 *   wind-down reminder. Timed off the dose's own modelled phase boundaries, so a
 *   reminder lands where the curve says the come-up ends rather than at a fixed
 *   hour after the pill.
 * - **Phase** — onset, come-up and peak, read off the same boundaries. The
 *   onset alert is unconditional (it is the "tracking started" confirmation);
 *   the other two need real phase data and are skipped silently without it.
 * - **Cumulative** — the one safety net. Fires only when a substance's rolling
 *   12-hour total for the route's native unit reaches the published heavy range.
 *
 * ## The schedules are absolute, and a past fire time schedules nothing
 * Every fire time is computed from the dose's timestamp, not from now, so
 * backfilling last night's entry does not buzz the phone about hydration for a
 * session that is over. The iOS build learned that the hard way: an old
 * `max(5, interval)` floor made a retroactive log fire a reminder five seconds
 * later, and the fix — skip anything already past — is kept here in
 * [scheduleSimple] and in `PiruNotifications.scheduleExact` behind it.
 *
 * ## Dedup is against what is armed, not what was decided
 * [claimSlot] refuses to arm a second wellness reminder of the same kind within
 * 90 minutes of one already armed. That is what stops a multi-dose evening from
 * stacking six identical "Stay hydrated" notifications, and it reads the
 * delivery ledger rather than a decision log because the ledger is the thing the
 * user actually receives.
 */
object DoseNotificationScheduler {

    // MARK: - Constants

    /** 6-hour grouping window for session-aware notification threading. */
    const val SESSION_WINDOW_SECONDS: Long = 6 * 3_600

    /** Default window for treating two wellness reminders of the same kind as duplicates. */
    const val PENDING_DEDUP_WINDOW_SECONDS: Long = 90 * 60

    /** Hydration delay when the dose has no modelled duration. */
    const val HYDRATION_INITIAL_DELAY_SECONDS: Long = 3_600

    /** Upper bound on the hydration trigger, so a twelve-hour profile does not defer water by nine hours. */
    const val PEAK_START_CAP_SECONDS: Long = 3_600

    /** Minimum spacing between the first and second hydration reminders. */
    const val HYDRATION_REMINDER_SPACING_SECONDS: Long = 1_800

    /** Sleep-reminder delay when the user is already 10+ hours into a stimulant session at log time. */
    const val EXTENDED_STIM_SLEEP_DELAY_SECONDS: Long = 2 * 3_600

    /** Default delay before the wind-down reminder. */
    const val STIMULANT_SLEEP_DELAY_SECONDS: Long = 12 * 3_600

    /** Don't arm the default wind-down reminder unless it is at least this far out. */
    const val MIN_SLEEP_REMINDER_LEAD_SECONDS: Long = 3_600

    /** Sliding window for the cumulative-dose total. */
    const val CUMULATIVE_DOSE_WINDOW_SECONDS: Long = 12 * 3_600

    /** The URL scheme every deep link uses. */
    const val DEEP_LINK_SCHEME = "piru"

    /**
     * The classes whose sessions run long enough to need a wind-down reminder.
     *
     * Ported from `SubstanceCategory.wakePromoting`, which the Android model does
     * not carry — it is the one classification on the iOS type that only this
     * scheduler reads, so it lives beside its only caller rather than widening a
     * model enum with a value nothing else asks for.
     */
    private val WAKE_PROMOTING = setOf(
        SubstanceCategory.STIMULANT,
        SubstanceCategory.EMPATHOGEN,
        SubstanceCategory.EUGEROIC,
    )

    /**
     * The pharmacokinetic phases that get their own alert.
     *
     * Declaration order is the order they fire in and the order the identifiers
     * were minted in, so it stays as it is.
     */
    enum class Phase(val wireValue: String, val displayName: String) {
        ONSET("onset", "Onset"),
        COMEUP("comeup", "Come-up"),
        PEAK("peak", "Peak"),
    }

    /** The cumulative check's answer: the rolling total, its unit, and whether it warrants an alert. */
    data class CumulativeCheck(val total: Double, val unit: String, val shouldAlert: Boolean)

    // MARK: - Session thread

    /**
     * The notification group a dose belongs to: the local day, cut into four
     * six-hour buckets.
     *
     * The bucket is derived from local midnight rather than from an absolute
     * epoch, so an evening that runs past midnight stays in the bucket it
     * started in on the same *day* rather than jumping — and two doses six hours
     * apart across a day boundary still thread together, which is what makes the
     * shade read as one session.
     */
    fun sessionIdentifier(forDoseAt: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val startOfDay = forDoseAt.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val elapsed = forDoseAt.epochSecond - startOfDay.epochSecond
        return "session_${startOfDay.epochSecond}_${(elapsed / SESSION_WINDOW_SECONDS).toInt()}"
    }

    // MARK: - Dose lifecycle

    /**
     * Schedule everything a freshly logged dose warrants, and run the cumulative
     * check.
     *
     * The caller reports what happened; it does not decide what to schedule. That
     * split is the point of the type: a view that pairs a schedule call with its
     * matching cancel by hand is how a stale reminder survives a retime.
     */
    suspend fun doseLogged(
        context: Context,
        entry: DoseEntryEntity,
        recent: List<DoseEntryEntity> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        val resolved = scheduleTimingReminders(context, entry, recent, zone)

        // A dose of unknown amount adds nothing to a running total, and a total
        // it is part of is not one worth alerting on.
        if (entry.isUnknownDose) return

        val check = checkCumulativeDose(
            context = context,
            substanceName = entry.substance,
            newAmount = entry.amount,
            unit = entry.unit,
            route = entry.route,
            releaseForm = entry.releaseForm,
            doseTime = entry.timestamp.toInstant(),
            existingEntries = recent,
            zone = zone,
        )
        if (!check.shouldAlert) return

        scheduleCumulative(
            context = context,
            entryId = entry.id,
            substanceName = entry.substance,
            check = check,
            category = resolved.category,
            doseTime = entry.timestamp.toInstant(),
            displayName = resolved.displayName,
            zone = zone,
        )
    }

    /**
     * The dose moved in time. Wellness and phase reminders are keyed to the old
     * timestamp, so those are cancelled and the set is re-derived from the new
     * one — which is also the fix for a dose moved into the past still pinging
     * "Stay hydrated" at its original fire times.
     */
    suspend fun doseRescheduled(
        context: Context,
        entry: DoseEntryEntity,
        previousTimestamp: Instant,
        recent: List<DoseEntryEntity> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        if (previousTimestamp == entry.timestamp.toInstant()) return
        cancelDoseNotifications(context, entry.id, previousTimestamp)
        scheduleTimingReminders(context, entry, recent, zone)
    }

    /** The dose is gone, so are its pending reminders. */
    suspend fun doseDeleted(context: Context, entryId: UUID, timestamp: Instant) {
        cancelDoseNotifications(context, entryId, timestamp)
    }

    /**
     * Re-derive every dose-anchored notification still in the future, from the
     * store.
     *
     * This is the restart pass. An `AlarmManager` alarm does not survive a
     * reboot, so a hydration reminder armed at 21:00 for 22:30 simply ceases to
     * exist when the phone restarts — and the failure is invisible, because an
     * alarm that never fires is indistinguishable from one that was never set.
     * The delivery ledger is cleared first (it describes a queue that no longer
     * exists) and everything future is rebuilt from the dose log, which is the
     * durable record; the alarm is only how it gets delivered.
     *
     * Doses older than a day are skipped: every family here is timed within
     * twelve hours of its dose at the outside, so an older one has nothing left
     * in the future to re-arm and scanning further would be work for nothing.
     */
    suspend fun rearmPending(
        context: Context,
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        val app = context.applicationContext as? PiruApplication ?: return
        PiruNotifications.registerChannels(context)
        PiruNotifications.clearDeliveryLedger(context)

        val now = Instant.now()
        val doses = app.database.doseEntryDao().inRange(
            java.util.Date.from(now.minus(Duration.ofDays(1))),
            java.util.Date.from(now.plus(Duration.ofHours(1))),
        )
        // One window's worth of context for every dose in the pass, rather than a
        // query per dose: what `recent` feeds is the stimulant-session clock, which
        // reads today's earliest stimulant dose and is therefore the same answer for
        // every row here.
        val recent = app.database.doseEntryDao().inRange(
            java.util.Date.from(now.minus(Duration.ofHours(48))),
            java.util.Date.from(now),
        )

        for (dose in doses) {
            scheduleTimingReminders(context, dose, recent, zone)
        }
    }

    // MARK: - Cumulative dose check

    /**
     * Whether the last twelve hours of this substance add up to something worth
     * saying.
     *
     * The total is summed in the **route's native unit**, so mixed-unit logs of
     * one substance (200 mg then 0.3 g) compare truthfully against the dose
     * ladder; a logged unit with no conversion path (mL, sprays, IU) contributes
     * nothing rather than summing as a raw number in the wrong unit, which would
     * be a false total rather than an incomplete one.
     *
     * Two ways to not alert, both load-bearing:
     * - A dose outside the window — backdated past it, or dated ahead of now —
     *   never alerts. The alert fires seconds after logging and reads "today", so
     *   a dose taken a year ago must not produce a claim about today.
     * - The ladder describes **base-form** doses only. A dose in another release
     *   form (a Concerta tablet) never alerts, and earlier doses in one stay out
     *   of the total: the numbers were not written for that formulation.
     */
    suspend fun checkCumulativeDose(
        context: Context,
        substanceName: String,
        newAmount: Double,
        unit: String,
        route: RouteOfAdministration,
        releaseForm: String? = null,
        doseTime: Instant,
        existingEntries: List<DoseEntryEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): CumulativeCheck {
        val now = Instant.now()
        val windowStart = now.minusSeconds(CUMULATIVE_DOSE_WINDOW_SECONDS)
        val newDoseIsCurrent = doseTime >= windowStart && doseTime <= now
        val recentSame = existingEntries.filter {
            it.substance.lowercase() == substanceName.lowercase() &&
                it.timestamp.toInstant() >= windowStart &&
                !it.toDoseRecord().namesUnmodeledForm
        }

        val substance = catalog(context)?.lookup(substanceName)
        val doseRange = substance?.doseRange(route)
        if (substance == null || doseRange == null) {
            // No dose data, so no judgment to make: report the plain sum in the
            // caller's own unit and stay quiet.
            val raw = recentSame.sumOf { it.amount } + newAmount
            return CumulativeCheck(raw, unit, false)
        }

        val routeUnit = substance.unit(route)
        val priorTotal = recentSame.sumOf { DoseUnit.convert(it.amount, it.unit, routeUnit) ?: 0.0 }
        val total = priorTotal + (DoseUnit.convert(newAmount, unit, routeUnit) ?: newAmount)
        if (!newDoseIsCurrent || !BaseReleaseForm.contains(releaseForm)) {
            return CumulativeCheck(total, routeUnit, false)
        }

        // At or above the published heavy bound, or well past the top of the
        // strong range — the second catches a substance whose ladder stops short
        // of a heavy tier without pretending it has one.
        doseRange.heavy?.let { if (total >= it) return CumulativeCheck(total, routeUnit, true) }
        doseRange.strong?.let { if (total >= it.endInclusive * 1.5) return CumulativeCheck(total, routeUnit, true) }

        return CumulativeCheck(total, routeUnit, false)
    }

    // MARK: - Internals

    /** What the resolve produced, so a caller can layer its own alert without repeating the lookups. */
    private data class Resolved(
        val category: SubstanceCategory?,
        val displayName: String?,
    )

    /**
     * Resolve the dose against the catalog, then arm wellness and phase.
     *
     * The envelope the reminders are timed from is the dose's own modelled curve
     * ([ActiveSubstanceState.from]), which already applies the same precedence
     * the timeline draws with — a named extended-release product's authored
     * profile first, the base route profile otherwise. Nothing here re-derives a
     * duration by hand; a second answer to "when does the come-up end" is how a
     * phase alert ends up somewhere the graph disagrees with.
     */
    private suspend fun scheduleTimingReminders(
        context: Context,
        entry: DoseEntryEntity,
        recent: List<DoseEntryEntity>,
        zone: ZoneId,
    ): Resolved {
        val catalog = catalog(context)
        val substance = catalog?.lookup(entry.substance)
        val weightKg = PKModel.REFERENCE_BODY_WEIGHT_KG
        val state = catalog?.let {
            ActiveSubstanceState.from(entry.toDoseRecord(), NEUTRAL_TINT, it, weightKg)
        }

        scheduleWellness(
            context = context,
            entryId = entry.id,
            category = substance?.category,
            doseTime = entry.timestamp.toInstant(),
            state = state,
            recentStimHours = stimulantSessionHours(recent, catalog, zone),
            zone = zone,
        )
        schedulePhase(
            context = context,
            entryId = entry.id,
            substanceName = entry.substance,
            doseTime = entry.timestamp.toInstant(),
            state = state,
            durationOnset = substance?.duration(entry.route)?.onset,
            displayName = displayNameOf(entry),
            zone = zone,
        )

        return Resolved(category = substance?.category, displayName = displayNameOf(entry))
    }

    /**
     * Two hydration nudges and, for the wake-promoting classes, a wind-down
     * reminder.
     *
     * Hydration #1 lands at `min(come-up end, one hour)` — the earlier of "the
     * dose is coming up" and "an hour has passed" — and #2 at the start of the
     * offset phase, but only when it is at least thirty minutes after the first
     * one. The wind-down reminder is twelve hours out by default, brought forward
     * to two when the user is already ten hours into a stimulant session, because
     * a reminder that arrives after the session has ended is a reminder about
     * nothing.
     */
    private fun scheduleWellness(
        context: Context,
        entryId: UUID,
        category: SubstanceCategory?,
        doseTime: Instant,
        state: ActiveSubstanceState?,
        recentStimHours: Double?,
        zone: ZoneId,
    ) {
        val hydrationAllowed = NotificationPreferencesStore.allows(context, NotificationType.HYDRATION)
        val sleepAllowed = NotificationPreferencesStore.allows(context, NotificationType.SLEEP)
        if (!hydrationAllowed && !sleepAllowed) return

        val threadId = sessionIdentifier(doseTime, zone)
        val anchor = entryId.toString()
        val hydrationPrefix = NotificationType.HYDRATION.identifierPrefix

        val hydrationDelaySeconds = if (state != null) {
            min((state.comeupEndMinutes * 60).toLong(), PEAK_START_CAP_SECONDS)
        } else {
            HYDRATION_INITIAL_DELAY_SECONDS
        }
        val hydrationFireAt = doseTime.plusSeconds(hydrationDelaySeconds)
        // Ten seconds, not zero: a dose logged just as its own reminder came due
        // would otherwise fire a "drink some water" over the user's thumb as they
        // put the phone down.
        val hydrationLead = Duration.between(Instant.now(), hydrationFireAt).seconds

        if (hydrationAllowed && hydrationLead > 10 && claimSlot(context, hydrationPrefix, hydrationFireAt)) {
            scheduleSimple(
                context = context,
                identifier = NotificationType.HYDRATION.identifier(anchor, "1"),
                title = "Stay hydrated",
                body = hydrationMessage(category),
                fireAt = hydrationFireAt,
                threadKey = threadId,
                zone = zone,
            )
        }

        // The second nudge is skipped outright when the offset phase starts less
        // than half an hour after the first would have fired — two reminders a
        // minute apart are one reminder with extra steps.
        if (state != null) {
            val secondFireAt = doseTime.plusSeconds((state.peakEndMinutes * 60).toLong())
            val spaced = Duration.between(hydrationFireAt, secondFireAt).seconds > HYDRATION_REMINDER_SPACING_SECONDS
            if (hydrationAllowed && spaced && claimSlot(context, hydrationPrefix, secondFireAt)) {
                scheduleSimple(
                    context = context,
                    identifier = NotificationType.HYDRATION.identifier(anchor, "2"),
                    title = "Hydration check",
                    body = "Have some water and a snack if you haven't recently.",
                    fireAt = secondFireAt,
                    threadKey = threadId,
                    zone = zone,
                )
            }
        }

        if (!sleepAllowed || category == null || category !in WAKE_PROMOTING) return
        val sleepPrefix = NotificationType.SLEEP.identifierPrefix

        val stimHours = recentStimHours
        if (stimHours != null && stimHours >= 10) {
            val fireAt = Instant.now().plusSeconds(EXTENDED_STIM_SLEEP_DELAY_SECONDS)
            if (claimSlot(context, sleepPrefix, fireAt)) {
                scheduleSimple(
                    context = context,
                    identifier = NotificationType.SLEEP.identifier(anchor),
                    title = "Time to rest",
                    body = "You've been going for over ${stimHours.toInt()} hours. Try to wind down — " +
                        "dim the lights, put the phone away, and let yourself sleep.",
                    fireAt = fireAt,
                    threadKey = threadId,
                    zone = zone,
                )
            }
            return
        }

        val fireAt = doseTime.plusSeconds(STIMULANT_SLEEP_DELAY_SECONDS)
        val lead = Duration.between(Instant.now(), fireAt).seconds
        if (lead > MIN_SLEEP_REMINDER_LEAD_SECONDS && claimSlot(context, sleepPrefix, fireAt)) {
            scheduleSimple(
                context = context,
                identifier = NotificationType.SLEEP.identifier(anchor),
                title = "Time to rest",
                body = "It's been a long session. Your body and brain need sleep to recover. Try to wind down.",
                fireAt = fireAt,
                threadKey = threadId,
                zone = zone,
            )
        }
    }

    /**
     * Onset, come-up and peak.
     *
     * The onset alert is unconditional and fires a minute after the dose: it is
     * the confirmation that tracking has begun, and the one place the reference
     * onset window for the route is worth stating. The other two need real phase
     * data — a profile that says nothing about where the come-up ends cannot
     * time an alert to it, and inventing one would put a notification where the
     * curve does not agree.
     */
    private fun schedulePhase(
        context: Context,
        entryId: UUID,
        substanceName: String,
        doseTime: Instant,
        state: ActiveSubstanceState?,
        durationOnset: DurationRange?,
        displayName: String?,
        zone: ZoneId,
    ) {
        if (!NotificationPreferencesStore.allows(context, NotificationType.PHASE)) return

        val shownName = displayName ?: substanceName
        val threadId = sessionIdentifier(doseTime, zone)
        val anchor = entryId.toString()

        val onsetBody = durationOnset?.let {
            "Reference onset for this route: ${it.min.toInt()}-${it.max.toInt()} minutes."
        } ?: "Tracking started."
        schedulePhaseAlert(context, Phase.ONSET, shownName, anchor, doseTime.plusSeconds(60), onsetBody, threadId, zone)

        if (state == null) return
        val onsetEndSeconds = (state.onsetEndMinutes * 60).toLong()
        val comeupEndSeconds = (state.comeupEndMinutes * 60).toLong()

        if (onsetEndSeconds > 0) {
            schedulePhaseAlert(
                context, Phase.COMEUP, shownName, anchor,
                doseTime.plusSeconds(onsetEndSeconds),
                "Estimated onset from reference data. How are you feeling?", threadId, zone,
            )
        }
        if (comeupEndSeconds > 0 && comeupEndSeconds > onsetEndSeconds) {
            schedulePhaseAlert(
                context, Phase.PEAK, shownName, anchor,
                doseTime.plusSeconds(comeupEndSeconds),
                "Estimated peak window from reference data. How are you feeling?", threadId, zone,
            )
        }
    }

    private fun schedulePhaseAlert(
        context: Context,
        phase: Phase,
        substance: String,
        anchor: String,
        fireAt: Instant,
        body: String,
        threadId: String,
        zone: ZoneId,
    ) {
        // A phase that has already passed (a backfilled entry) or that is too
        // imminent for anyone to react to is not scheduled at all.
        if (Duration.between(Instant.now(), fireAt).seconds <= 5) return
        scheduleSimple(
            context = context,
            identifier = NotificationType.PHASE.identifier(anchor, phase.wireValue),
            title = "$substance — ${phase.displayName}",
            body = body,
            fireAt = fireAt,
            threadKey = threadId,
            zone = zone,
        )
    }

    /** The one safety-net alert. Never silenced — see [scheduleSimple]'s note. */
    private fun scheduleCumulative(
        context: Context,
        entryId: UUID,
        substanceName: String,
        check: CumulativeCheck,
        category: SubstanceCategory?,
        doseTime: Instant,
        displayName: String?,
        zone: ZoneId,
    ) {
        if (!NotificationPreferencesStore.allows(context, NotificationType.CUMULATIVE)) return
        val shownName = displayName ?: substanceName
        scheduleSimple(
            context = context,
            identifier = NotificationType.CUMULATIVE.identifier(entryId.toString()),
            title = "Heads up — ${doseFormatted(check.total)}${check.unit} $shownName today",
            body = "That's a high cumulative dose. ${cumulativeTip(category)}",
            fireAt = Instant.now().plusSeconds(5),
            threadKey = sessionIdentifier(doseTime, zone),
            zone = zone,
            // The one type that opts out of quiet hours: a safety net silenced by
            // accident is worse than no safety net, because the user believes it is
            // watching.
            respectsQuietHours = false,
        )
    }

    /**
     * Arm one notification, or decline to.
     *
     * Quiet hours are evaluated against the **fire time**, never against now:
     * a reminder for 23:30 logged at 22:50 is inside the window even though the
     * moment of scheduling is not.
     */
    private fun scheduleSimple(
        context: Context,
        identifier: String,
        title: String,
        body: String,
        fireAt: Instant,
        threadKey: String?,
        zone: ZoneId,
        respectsQuietHours: Boolean = true,
        silent: Boolean = false,
        deepLink: String? = null,
    ) {
        if (fireAt <= Instant.now()) return
        if (respectsQuietHours && NotificationPreferencesStore.isInQuietHours(context, fireAt, zone)) return
        PiruNotifications.scheduleExact(
            context = context,
            payload = PlannedNotification(
                identifier = identifier,
                channelId = channelFor(identifier),
                title = title,
                body = body,
                threadKey = threadKey,
                deepLink = deepLink,
                silent = silent,
            ),
            fireAt = fireAt,
        )
    }

    /**
     * Claim a wellness slot: false when a reminder of the same kind is already
     * armed within the dedup window of [fireAt].
     *
     * Checked against the delivery ledger rather than a process-local list. On
     * iOS both had to be consulted — the pending queue plus an in-memory record,
     * because back-to-back doses could all read the queue before any write landed
     * — but there is one writer here and it records synchronously as it arms, so
     * one check is the same answer with one fewer thing to keep in step.
     */
    private fun claimSlot(context: Context, prefix: String, fireAt: Instant): Boolean =
        PiruNotifications.scheduledIdentifiers(context)
            .filter { it.startsWith(prefix) }
            .none { identifier ->
                val armed = PiruNotifications.pendingFireAt(context, identifier) ?: return@none false
                Duration.between(armed, fireAt).abs().seconds < PENDING_DEDUP_WINDOW_SECONDS
            }

    /** Cancel a dose's wellness and phase notifications, in both grammars. */
    private suspend fun cancelDoseNotifications(context: Context, entryId: UUID, timestamp: Instant) {
        val anchor = entryId.toString()
        val seconds = timestamp.epochSecond

        val hydrationIds = listOf(
            NotificationType.HYDRATION.identifier(anchor, "1"),
            NotificationType.HYDRATION.identifier(anchor, "2"),
            // Pre-grammar hydration keys were the category id plus the dose's
            // epoch seconds — no underscore on the second, which is exactly the
            // quirk `identifierPrefixes` documents.
            "${NotificationType.HYDRATION_CATEGORY_ID}_$seconds",
            "${NotificationType.HYDRATION_CATEGORY_ID}2_$seconds",
        )
        val sleepIds = listOf(
            NotificationType.SLEEP.identifier(anchor),
            "${NotificationType.SLEEP_CATEGORY_ID}_$seconds",
        )
        val phaseIds = Phase.entries.map { NotificationType.PHASE.identifier(anchor, it.wireValue) } +
            Phase.entries.map { "${NotificationType.PHASE_CATEGORY_ID}_${it.wireValue}_$seconds" }

        PiruNotifications.cancel(context, hydrationIds + sleepIds + phaseIds)
    }

    /**
     * How many hours the user has been using stimulants today, or null when they
     * have not.
     *
     * Measured to the *earliest* stimulant dose of the local day, not to the
     * latest: this answers "how long has this been going on", and the answer to
     * that only grows.
     */
    private fun stimulantSessionHours(
        entries: List<DoseEntryEntity>,
        catalog: SubstanceCatalog?,
        zone: ZoneId,
    ): Double? {
        if (catalog == null) return null
        val today = Instant.now().atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val earliest = entries
            .filter { it.timestamp.toInstant() >= today && catalog.lookup(it.substance)?.category == SubstanceCategory.STIMULANT }
            .minOfOrNull { it.timestamp.toInstant() }
            ?: return null
        return Duration.between(earliest, Instant.now()).seconds / 3_600.0
    }

    /**
     * The bundled catalog, opened on first use.
     *
     * `suspend`, and not only for tidiness: [PiruApplication.catalog] installs and
     * opens an 18 MB asset on first call, which is disk work, and every entry
     * point above it is suspending for that reason. A scheduler that could not
     * reach the catalog falls back rather than failing — the reminder families
     * that need no substance data (a phase alert still fires from the curve, and
     * the sleep reminder from the class) keep working.
     */
    private suspend fun catalog(context: Context): SubstanceCatalog? {
        val app = context.applicationContext as? PiruApplication ?: return null
        return runCatching { app.catalog() }.getOrNull()
    }

    /**
     * The name to *show*, in the one precedence that keeps a notification from
     * reverting to the generic name the user did not type: the title captured at
     * resolve time, then the brand they picked, then the logged name.
     *
     * Stands in for the iOS `DoseTitle.resolve(for:)`, which is not ported — it
     * composes a title from the release form, salt, isomer and a relabel table,
     * all of which live in the view-layer derive path this port does not have
     * yet. The three fields below cover the case a notification actually hits
     * ("the dose was logged as Concerta, do not print Methylphenidate"), and
     * nothing here invents a form the user did not name.
     */
    private fun displayNameOf(entry: DoseEntryEntity): String? =
        entry.displayNameSnapshot ?: entry.productName

    /** The medication-specific hydration line, by class. */
    private fun hydrationMessage(category: SubstanceCategory?): String = when (category) {
        SubstanceCategory.STIMULANT -> "A reminder to drink some water. Stimulants can mask thirst."
        SubstanceCategory.EMPATHOGEN ->
            "A reminder to sip, and to favor electrolytes. With this class more water is not safer."
        SubstanceCategory.DISSOCIATIVE -> "A reminder to have some water if you can."
        else -> "A reminder to drink some water."
    }

    /** What to say alongside a high cumulative total, by class. */
    private fun cumulativeTip(category: SubstanceCategory?): String = when (category) {
        SubstanceCategory.STIMULANT ->
            "Remember to hydrate, eat, and try to get some sleep. Your heart has been working hard."
        SubstanceCategory.EMPATHOGEN ->
            "That total is in the heavy range of the sources. Overheating, confusion or rigid muscles need emergency help."
        SubstanceCategory.OPIOID ->
            "Don't use alone and don't mix with other downers. An overdose is a sudden blackout with no warning — " +
                "you can't naloxone yourself, so someone with you needs it and should call emergency services."
        SubstanceCategory.BENZODIAZEPINE ->
            "High cumulative benzo doses impair memory and coordination. Stay somewhere safe."
        SubstanceCategory.DISSOCIATIVE ->
            "Stay somewhere safe. Don't drive. Your coordination and judgment are affected."
        else -> "Take it easy. Hydrate, eat, and rest."
    }

    /**
     * The tint handed to a duration resolve.
     *
     * Never drawn — the resolve only reads the phases — so it carries no meaning
     * and must not be mistaken for a real identity colour.
     */
    private val NEUTRAL_TINT = P3Color(red = 0.5, green = 0.5, blue = 0.5)

    /** The channel an identifier belongs to, from the type its grammar names. */
    private fun channelFor(identifier: String): String = NotificationType.entries
        .firstOrNull { identifier.startsWith(it.identifierPrefix) }
        ?.channelId
        ?: PiruNotifications.CHANNEL_SESSION_ALERTS
}

/**
 * The dose's row as the engine reads it.
 *
 * A sibling of the private mapping in `SessionRepository`, which does the same
 * job for the clustering heuristic; it is not shared because the two live in
 * different modules and the mapping is four lines of field renaming rather than
 * a rule anyone could get wrong.
 */
internal fun DoseEntryEntity.toDoseRecord(): DoseRecord = DoseRecord(
    substance = substance,
    amount = amount,
    unit = unit,
    route = route,
    timestamp = timestamp.toInstant(),
    isUnknownDose = isUnknownDose,
    releaseForm = releaseForm,
    productName = productName,
    saltForm = saltForm,
    isomer = isomer,
    substanceUID = substanceUID,
)
