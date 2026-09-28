package glass.kagerou.piru.notifications

import android.content.Context
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.data.entity.SessionEntity
import glass.kagerou.piru.engine.CheckInOffsets
import glass.kagerou.piru.model.SubstanceCategory
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * The opt-in per-session "How is it going?" prompts.
 *
 * Ported from `Piru/Utilities/CheckInScheduler.swift`.
 *
 * ## Off unless the session asks
 * Nothing here fires for a session that has not turned check-ins on. The switch
 * is the session's own cadence ([SessionEntity.checkInIntervalMinutes]), not an
 * app-wide setting, because a prompt every hour is right for a six-hour
 * psychedelic session and absurd for a morning antibiotic. The app-wide
 * [NotificationType.CHECK_IN] switch is a mute, not an enabler.
 *
 * ## Two cadences, and only two
 * An hourly run, or the session's own times. The hourly run goes for eight hours
 * — long enough for a whole session, short enough that a forgotten toggle stops
 * on its own. The session's own times are where `CheckInLadder`'s suggestion
 * lands, which is why "custom" is not a third kind of thing but the same list a
 * suggestion produces.
 *
 * ## The stored sentinel is `-1` or `60`, and nothing else
 * [Cadence.fromStoredMinutes] recognizes exactly those two, and a session holding
 * anything else runs no schedule at all. Note this reads *differently* from
 * [SessionEntity.checkInIntervalMinutes]'s own documentation, which describes a
 * positive value as an interval and `0` as a fixed ladder — a shape the iOS
 * scheduler no longer has and which no writer produces any more. The source is
 * `CheckInScheduler.Cadence`, so the source's rule is the one implemented; a
 * stray `0` therefore means "off", not "the old ladder".
 */
object CheckInScheduler {

    /**
     * The identifier the iOS side uses for this family's notification category.
     * Android has no action categories, but the string is kept: it rides the
     * notification's group tag, so a bug report can name where a notification came
     * from.
     */
    const val CATEGORY_ID = "checkIn"

    /** The two schedules a session can run. */
    enum class Cadence(val storedMinutes: Double) {
        /** T+1 h, T+2 h … T+8 h. */
        EVERY_HOUR(60.0),

        /** The times on the session itself. */
        CUSTOM(-1.0),
        ;

        /**
         * The cadence a stored value names, or null when it names none.
         *
         * Only `-1` and `60` are recognized. Anything else — including `0` and
         * any other positive — is a value this build does not know, and runs
         * nothing rather than being guessed at.
         */
        companion object {
            fun fromStoredMinutes(storedMinutes: Double?): Cadence? = when (storedMinutes) {
                null -> null
                -1.0 -> CUSTOM
                60.0 -> EVERY_HOUR
                else -> null
            }
        }

        /**
         * The offsets this cadence carries of its own, in minutes from the
         * anchor dose. [CUSTOM] has none — its times live on the session.
         */
        val fixedOffsetMinutes: List<Double>
            get() = when (this) {
                CUSTOM -> emptyList()
                EVERY_HOUR -> (1..8).map { it * 60.0 }
            }
    }

    /** Why a row of the session's schedule reads the way it does. */
    enum class State {
        /** Coming, and it will arrive. */
        SCHEDULED,

        /** Inside the quiet window, so it is never scheduled at all. */
        QUIET_HOURS,

        /** Already gone. Not "coming" and not "missed" — just past. */
        PASSED,
    }

    /** One prompt in a session's schedule, as the session screen shows it. */
    data class Planned(val offsetMinutes: Int, val date: Instant, val state: State)

    /**
     * The classes where a timed prompt has something to learn: a psychoactive
     * dose with a course someone can report on. A supplement or an antibiotic is
     * logged, not experienced, so asking about it hourly would be noise.
     */
    val OFFERABLE_CATEGORIES: Set<SubstanceCategory> = setOf(
        SubstanceCategory.PSYCHEDELIC,
        SubstanceCategory.DISSOCIATIVE,
        SubstanceCategory.DYSDELIC,
        SubstanceCategory.DELIRIANT,
        SubstanceCategory.EMPATHOGEN,
        SubstanceCategory.STIMULANT,
        SubstanceCategory.EUGEROIC,
        SubstanceCategory.CANNABINOID,
        SubstanceCategory.OPIOID,
        SubstanceCategory.BENZODIAZEPINE,
        SubstanceCategory.DEPRESSANT,
        SubstanceCategory.GABAPENTINOID,
    )

    /**
     * How many times the offer may be turned down before it stops appearing.
     *
     * The offer is per-session, and someone who takes a medication daily starts a
     * session a day — without this, declining once would mean declining every
     * morning forever. Two says it clearly enough.
     */
    const val MAXIMUM_DECLINES: Int = 2

    // MARK: - The schedule

    /** The offsets a session actually runs, in minutes: the cadence's own, or the session's list. */
    fun offsetMinutes(cadence: Cadence, custom: List<Int>): List<Double> =
        if (cadence == Cadence.CUSTOM) CheckInOffsets.normalized(custom).map { it.toDouble() }
        else cadence.fixedOffsetMinutes

    /**
     * Anchor time for a session's check-ins: its latest dose.
     *
     * The latest rather than the first, because a re-dose restarts the clock the
     * prompts are asking about — "how is it going" means the session as it stands
     * now.
     */
    fun anchor(session: SessionEntity): Instant =
        (session.lastDoseDate ?: session.startDate).toInstant()

    /**
     * The fire times a schedule yields from [anchor], dropping any already past
     * [now] by more than five seconds. Pure, for tests.
     *
     * The five seconds are slack, not politeness: a prompt computed to fire at the
     * instant the sync runs is one the user would receive while still holding the
     * phone that logged the dose.
     */
    fun fireDates(
        cadence: Cadence,
        custom: List<Int> = emptyList(),
        anchor: Instant,
        now: Instant = Instant.now(),
    ): List<Instant> =
        offsetMinutes(cadence, custom)
            .map { anchor.plusSeconds((it * 60).toLong()) }
            .filter { it.isAfter(now.plusSeconds(5)) }

    /**
     * The whole schedule a session runs, past prompts included — what
     * [fireDates] computes without dropping what has already gone.
     *
     * A schedule that listed every time as "coming" would be wrong twice over: a
     * prompt whose hour has gone is not coming, and one inside quiet hours is
     * never scheduled at all. Pure, for tests.
     */
    fun plan(
        cadence: Cadence,
        custom: List<Int> = emptyList(),
        anchor: Instant,
        now: Instant = Instant.now(),
        inQuietHours: (Instant) -> Boolean = { false },
    ): List<Planned> = offsetMinutes(cadence, custom).map { minutes ->
        val date = anchor.plusSeconds((minutes * 60).toLong())
        val state = when {
            !date.isAfter(now) -> State.PASSED
            inQuietHours(date) -> State.QUIET_HOURS
            else -> State.SCHEDULED
        }
        Planned(offsetMinutes = minutes.toInt(), date = date, state = state)
    }

    /** The session's schedule, or null when it runs none. */
    fun plan(
        context: Context,
        session: SessionEntity,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Planned>? {
        val cadence = Cadence.fromStoredMinutes(session.checkInIntervalMinutes) ?: return null
        return plan(
            cadence = cadence,
            custom = session.checkInOffsetMinutes,
            anchor = anchor(session),
            now = now,
            inQuietHours = { NotificationPreferencesStore.isInQuietHours(context, it, zone) },
        )
    }

    /**
     * Whether check-in notifications are off app-wide, so a session's schedule
     * exists but nothing it lists will arrive.
     */
    fun isMutedByPreferences(context: Context): Boolean =
        !NotificationPreferencesStore.allows(context, NotificationType.CHECK_IN)

    // MARK: - Applying the schedule

    /**
     * Apply the session's stored cadence: cancel what is armed and schedule afresh
     * from the latest dose.
     *
     * Call after the cadence changes and after a dose is added to the session,
     * because the anchor moved and every offset is measured from it. The cancel
     * comes first and unconditionally: a schedule that shrank would otherwise
     * leave the prompts it no longer lists still armed.
     */
    suspend fun sync(
        context: Context,
        session: SessionEntity,
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        cancel(context, session.id)

        val cadence = Cadence.fromStoredMinutes(session.checkInIntervalMinutes) ?: return
        if (isMutedByPreferences(context)) return

        val now = Instant.now()
        val dates = fireDates(cadence, session.checkInOffsetMinutes, anchor(session), now)
        if (dates.isEmpty()) return

        val thread = DoseNotificationScheduler.sessionIdentifier(session.startDate.toInstant(), zone)
        val deepLink = "$DEEP_LINK_SCHEME://session/${session.id}?note=checkIn"

        // A session that only carries medication is asked the medication question,
        // in the words its own control uses.
        val medication = asksWorkedOnly(context, session)
        val title = if (medication) "Is it working?" else "How is it going?"
        val body = if (medication) {
            "One tap records how this dose is going — less than usual, about right, or more."
        } else {
            "Add a note to your session — what you notice, at this moment."
        }

        for ((index, date) in dates.withIndex()) {
            if (NotificationPreferencesStore.isInQuietHours(context, date, zone)) continue
            PiruNotifications.scheduleExact(
                context = context,
                payload = PlannedNotification(
                    identifier = NotificationType.CHECK_IN.identifier(session.id.toString(), index.toString()),
                    channelId = NotificationType.CHECK_IN.channelId,
                    title = title,
                    body = body,
                    threadKey = thread,
                    deepLink = deepLink,
                ),
                fireAt = date,
            )
        }
    }

    /**
     * Cancel a session's prompts.
     *
     * By prefix over the session's id, so every ordinal goes at once — the count
     * is whatever the cadence produced, and cancelling by a remembered list would
     * leave behind exactly the prompt a shrinking schedule dropped.
     */
    fun cancel(context: Context, sessionId: UUID) {
        PiruNotifications.cancelPending(
            context,
            listOf(NotificationType.CHECK_IN.identifierPrefix + sessionId.toString()),
        )
    }

    /**
     * Re-apply every session whose schedule is still running — the restart pass.
     *
     * [sync] is the only thing that arms a check-in, and it is idempotent: it
     * cancels the session's prompts and re-derives them from the anchor, so
     * running it for a session that already had everything armed is a rewrite
     * rather than a duplicate. That is what makes this safe to call unconditionally
     * after a reboot, when nothing is armed at all.
     *
     * Only sessions within a two-day window are considered. A check-in's last
     * offset is eight hours past its anchor, so a session older than that has
     * nothing left in the future to arm; the window is generous because it costs
     * one indexed query and being wrong in the other direction loses a reminder.
     */
    suspend fun rearmAll(context: Context, zone: ZoneId = ZoneId.systemDefault()) {
        val app = context.applicationContext as? PiruApplication ?: return
        if (isMutedByPreferences(context)) return

        val now = Instant.now()
        val sessions = app.database.sessionDao().inWindow(
            now.minusSeconds(2 * 24 * 3_600).toEpochMilli(),
            now.plusSeconds(2 * 24 * 3_600).toEpochMilli(),
        )
        for (session in sessions) {
            if (Cadence.fromStoredMinutes(session.checkInIntervalMinutes) == null) continue
            sync(context, session, zone)
        }
    }

    // MARK: - The offer

    /**
     * Whether a session should be *offered* check-ins: once per session, while a
     * dose of an [OFFERABLE_CATEGORIES] class is still in its effect window, and
     * only until the offer has been turned down [MAXIMUM_DECLINES] times.
     *
     * [hasOngoingDose] is the caller's answer because only the caller knows what
     * "still active" means for the screen it is drawing.
     */
    suspend fun shouldOffer(context: Context, session: SessionEntity, hasOngoingDose: Boolean): Boolean {
        if (!hasOngoingDose) return false
        if (session.checkInOffered) return false
        if (session.checkInIntervalMinutes != null) return false
        if (isOfferMuted(context)) return false

        val app = context.applicationContext as? PiruApplication ?: return false
        val catalog = runCatching { app.catalog() }.getOrNull() ?: return false
        return app.database.doseEntryDao().dosesFor(session.id).any { dose ->
            catalog.lookup(dose.substance)?.category?.let { it in OFFERABLE_CATEGORIES } == true
        }
    }

    /** Record that the banner was dismissed rather than accepted. */
    fun recordOfferDeclined(context: Context) {
        val prefs = PiruNotifications.mirrorPrefs(context)
        prefs.edit().putInt(KEY_DECLINES, prefs.getInt(KEY_DECLINES, 0) + 1).apply()
    }

    /** Whether the offer has been declined enough times to stop appearing. */
    fun isOfferMuted(context: Context): Boolean =
        PiruNotifications.mirrorPrefs(context).getInt(KEY_DECLINES, 0) >= MAXIMUM_DECLINES

    // MARK: - Copy selection

    /**
     * Whether the session's doses are all of the classes that ask "did it work?"
     * and none of the classes that ask "how intense is it?" — the test that picks
     * the medication wording.
     *
     * Re-derived from the classes rather than ported wholesale, because the iOS
     * answer is `CheckInForm.build(for:)`: a view-layer type that also carries the
     * descriptor chips, the mood and energy flags and the highlight lists the note
     * sheet renders. A notification layer must not depend on that, and of its
     * twenty fields exactly two decide the copy.
     *
     * A session whose doses resolve nothing keeps the wider question — a custom
     * substance or a typo should widen the questions, never narrow them to one
     * about a medication nobody took.
     */
    private suspend fun asksWorkedOnly(context: Context, session: SessionEntity): Boolean {
        val app = context.applicationContext as? PiruApplication ?: return false
        val catalog = runCatching { app.catalog() }.getOrNull() ?: return false

        var resolved = false
        var worked = false
        var intensity = false
        val seen = HashSet<String>()

        for (dose in app.database.doseEntryDao().dosesFor(session.id)) {
            val substance = catalog.lookup(dose.substance) ?: continue
            if (!seen.add(substance.name.lowercase())) continue
            resolved = true
            if (lensAsksIntensity(substance.category)) intensity = true else worked = true
        }

        return resolved && worked && !intensity
    }

    /**
     * The two question axes of `CheckInLenses.lenses(for:)`, and only those two.
     *
     * Defaulting to "did it work?" rather than to neither is deliberate and
     * matches the source: the classes it names for the intensity axis are a closed
     * set of the ones someone reports a level on, and everything else — the
     * medication-shaped classes above all — is asked the worked question.
     */
    private fun lensAsksIntensity(category: SubstanceCategory): Boolean = when (category) {
        SubstanceCategory.EMPATHOGEN,
        SubstanceCategory.PSYCHEDELIC,
        SubstanceCategory.DELIRIANT,
        SubstanceCategory.DYSDELIC,
        SubstanceCategory.DISSOCIATIVE,
        SubstanceCategory.CANNABINOID,
        -> true

        else -> false
    }

    /** Where the decline count lives. Same preferences file the notification mirror uses — it is notification state either way. */
    private const val KEY_DECLINES = "checkInOfferDeclines"

    private const val DEEP_LINK_SCHEME = DoseNotificationScheduler.DEEP_LINK_SCHEME
}
