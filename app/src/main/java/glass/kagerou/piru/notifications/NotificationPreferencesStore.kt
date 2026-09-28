package glass.kagerou.piru.notifications

import android.content.Context
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.data.JsonLists
import glass.kagerou.piru.data.entity.NotificationPreferencesEntity
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The single home for notification enablement: which of the app's types the user
 * has turned on, the master pause, quiet hours, and the per-type Time Sensitive
 * choices.
 *
 * Ported from `Piru/Data/Services/NotificationPreferencesStore.swift`.
 *
 * ## Two tiers, for one reason
 * The durable source of truth is the single [NotificationPreferencesEntity] row,
 * so the user's setup rides the existing backup and restore path with everything
 * else they own. A write-through mirror in `SharedPreferences` — [allows] and
 * [isInQuietHours] read it — is what lets a scheduler gate **synchronously**,
 * from a receiver or a worker, without opening the database. Upstream splits the
 * same way (a SwiftData record plus a `UserDefaults` mirror) and for the same
 * reason. The mirror is never the truth: if the two ever disagree, the row wins
 * and the next write repairs the mirror.
 *
 * ## The row is a singleton, by convention
 * iOS carries no key and simply takes the first `NotificationPreferences`. Room
 * needs a primary key, so [NotificationPreferencesEntity] has an auto-generated
 * `row_id` and inherits the same convention: **read the lowest `row_id`, update
 * it, never insert a second.** [load] is the only reader and it seeds the row on
 * first use; every mutation goes through [mutate], which loads first, so a
 * second row cannot be created by a write path that never saw the first.
 *
 * ## Why the writes go through the DAO
 * The row is read and written through `NotificationPreferencesDao`, whose [edit]
 * is the one shape every mutation here takes: read the row, apply a change,
 * write it back, never insert a second. That is the same load-transform-write
 * this store did by hand against Room's own connection, with the column names
 * and the insert-or-update decision now living in `:core:data` next to the
 * entity that defines them — and the seeding of a first row is the DAO's, so a
 * change there cannot leave this store writing a second row.
 *
 * ## Legacy flags
 * When the record is first seeded, iOS adopts two pre-existing `UserDefaults`
 * flags (`wellnessNotificationsEnabled` gating hydration, sleep and cumulative;
 * `phaseNotificationsEnabled` gating phase) and then abandons them, so a user who
 * had turned phase alerts off stays off. Those flags were iOS-only and no Android
 * build ever wrote them, so the seed here is simply the model's own defaults. A
 * store restored from an iOS backup carries the *record*, which is the durable
 * half, so the one-time adoption is not needed to preserve a choice.
 */
class NotificationPreferencesStore(private val context: Context) {

    private val application: PiruApplication?
        get() = context.applicationContext as? PiruApplication

    // MARK: - Reads

    /**
     * The row, seeded with defaults the first time it is asked for.
     *
     * Also refreshes the mirror, so calling this at launch is what makes [allows]
     * correct for the rest of the process — the role upstream's
     * `configure(container:)` plays.
     */
    suspend fun load(): NotificationPreferencesEntity = withContext(Dispatchers.IO) {
        val database = application?.database ?: return@withContext DEFAULTS
        // An identity change is the read-and-seed: on an empty table the DAO
        // inserts the defaults and hands them back, which is the row iOS creates
        // the first time the screen asks for one — on the first read rather than
        // at install, because a user who never opens the screen never needs it.
        val row = database.notificationPreferencesDao().edit { it }
        mirror(row)
        row
    }

    // MARK: - Mutations

    /**
     * Persist a per-type choice.
     *
     * Disabling cancels the type's pending notifications immediately, and turning
     * a routine reminder off also cancels the already-materialized re-asks — they
     * exist only in service of it, so leaving them armed would mean the user turns
     * a reminder off and keeps being asked. Re-enabling re-arms only what repeats;
     * the dose-anchored types re-arm on future doses and are never retroactive.
     */
    suspend fun setEnabled(type: NotificationType, value: Boolean) {
        val current = load()
        if (value == current.isEnabled(type)) return
        mutate(current) { it.withEnabled(type, value) }

        if (value) {
            if (type == NotificationType.ROUTINE || type == NotificationType.ROUTINE_FOLLOW_UP) {
                MedReminderScheduler.reconcile(context)
            }
        } else {
            var prefixes = type.identifierPrefixes
            if (type == NotificationType.ROUTINE) {
                prefixes = prefixes + NotificationType.ROUTINE_FOLLOW_UP.identifierPrefixes
            }
            PiruNotifications.cancelPending(context, prefixes)
        }
    }

    /**
     * Persist the master posture. Pausing cancels everything pending; resuming
     * re-arms the repeating family.
     */
    suspend fun setMasterEnabled(value: Boolean) {
        val current = load()
        if (value == current.masterEnabled) return
        val next = mutate(current) { it.copy(masterEnabled = value) }

        for (type in NotificationType.entries) {
            if (!(value && next.isEnabled(type))) {
                PiruNotifications.cancelPending(context, type.identifierPrefixes)
            }
        }
        if (value && (next.isEnabled(NotificationType.ROUTINE) || next.isEnabled(NotificationType.ROUTINE_FOLLOW_UP))) {
            MedReminderScheduler.reconcile(context)
        }
    }

    /**
     * Persist a per-type Time Sensitive choice.
     *
     * Stored and shown, and it changes nothing at delivery on this platform — see
     * [PiruNotifications] for why. The routine family still resyncs, exactly as
     * upstream does, because that resync is what keeps the stored value and the
     * pending set from drifting apart if the platform ever grows an equivalent.
     */
    suspend fun setTimeSensitive(type: NotificationType, value: Boolean) {
        if (!type.supportsTimeSensitive) return
        val current = load()
        if (value == current.isTimeSensitiveEnabled(type)) return
        mutate(current) { it.withTimeSensitive(type, value) }
        if (type == NotificationType.ROUTINE || type == NotificationType.ROUTINE_FOLLOW_UP) {
            MedReminderScheduler.reconcile(context)
        }
    }

    /**
     * Persist the quiet-hours window.
     *
     * Affects future scheduling only: already-pending reminders are not
     * retroactively silenced, and they re-derive on the next dose or sync anyway.
     * The one repeating family is resynced so its pending re-asks respect the new
     * window immediately, which is the case a user would notice.
     */
    suspend fun setQuietHours(enabled: Boolean, startMinutes: Int? = null, endMinutes: Int? = null) {
        val current = load()
        mutate(current) {
            it.copy(
                quietHoursEnabled = enabled,
                quietHoursStartMinutes = startMinutes ?: it.quietHoursStartMinutes,
                quietHoursEndMinutes = endMinutes ?: it.quietHoursEndMinutes,
            )
        }
        MedReminderScheduler.reconcile(context)
    }

    /**
     * Persist the global "ask again" cadence — the minutes after a routine time
     * at which each re-ask fires.
     *
     * **An empty list is not the same as never having written one.** Null reads as
     * the ten-minute default; an explicit empty list is a deliberate "no re-asks"
     * and round-trips as empty. Collapsing the two would silently opt every user
     * who never opened the screen out of re-asks — the single most consequential
     * detail in this store.
     */
    suspend fun setAskAgainDefault(minutes: List<Int>) {
        val current = load()
        mutate(current) { it.copy(askAgainDefaultJson = JsonLists.encode(minutes)) }
        MedReminderScheduler.reconcile(context)
    }

    /**
     * Drop the row so the next [load] seeds a fresh one — the notification half
     * of "delete everything", where a setup belonging to a journal that no longer
     * exists must not survive into the new one.
     */
    suspend fun resetAfterDeletion() {
        withContext(Dispatchers.IO) {
            application?.database?.notificationPreferencesDao()?.deleteAll()
        }
        mirror(DEFAULTS)
    }

    // MARK: - Internals

    /** Load, transform, write, re-mirror — the one shape every mutation takes. */
    private suspend fun mutate(
        current: NotificationPreferencesEntity,
        transform: (NotificationPreferencesEntity) -> NotificationPreferencesEntity,
    ): NotificationPreferencesEntity {
        // The transform is a pure function of the row, so the DAO's transaction
        // can supply the row: it re-reads inside the transaction rather than
        // trusting the copy loaded a moment ago, which is what keeps two
        // near-simultaneous writes from both starting at the older row.
        val database = application?.database
        val next = if (database == null) {
            transform(current)
        } else {
            withContext(Dispatchers.IO) { database.notificationPreferencesDao().edit(transform) }
        }
        mirror(next)
        return next
    }

    /**
     * Write the mirror the synchronous gates read.
     *
     * Deliberately writes the *effective* per-type value, so a type the user never
     * touched lands in the mirror as its default rather than as an absent key that
     * every reader would then have to re-default.
     */
    private fun mirror(row: NotificationPreferencesEntity) {
        val editor = PiruNotifications.mirrorPrefs(context).edit()
            .putBoolean(KEY_MASTER, row.masterEnabled)
            .putBoolean(KEY_QUIET_ENABLED, row.quietHoursEnabled)
            .putInt(KEY_QUIET_START, row.quietHoursStartMinutes)
            .putInt(KEY_QUIET_END, row.quietHoursEndMinutes)
        for (type in NotificationType.entries) {
            editor.putBoolean(mirrorKey(type), row.isEnabled(type))
            if (type.supportsTimeSensitive) {
                editor.putBoolean(timeSensitiveMirrorKey(type), row.isTimeSensitiveEnabled(type))
            }
        }
        editor.apply()
    }

    companion object {
        /**
         * The row an app that has never opened the settings holds — the model's
         * own defaults, which are the shipped behaviour rather than a neutral
         * placeholder: routine, follow-up, inventory and check-in on; the
         * flag-gated session types off.
         */
        val DEFAULTS = NotificationPreferencesEntity()

        // Mirror keys, byte-for-byte the iOS ones. A bug report that names a key
        // then reads the same on both platforms, which is what makes an iOS
        // `UserDefaults` dump and an Android preferences dump comparable.
        private const val KEY_MASTER = "notificationMasterEnabled"
        private const val KEY_QUIET_ENABLED = "notificationQuietHoursEnabled"
        private const val KEY_QUIET_START = "notificationQuietHoursStart"
        private const val KEY_QUIET_END = "notificationQuietHoursEnd"

        private fun mirrorKey(type: NotificationType) =
            "notificationTypeEnabled_${type.wireValue}"

        private fun timeSensitiveMirrorKey(type: NotificationType) =
            "notificationTimeSensitive_${type.wireValue}"

        /**
         * The synchronous gate a scheduler reads from any context.
         *
         * Backed by the mirror, so it agrees with the row once [load] has run once
         * in this process — and falls back to the type's own default when the
         * mirror has never been written, which is exactly upstream's
         * `object(forKey:) as? Bool ?? type.defaultEnabled`.
         */
        fun allows(context: Context, type: NotificationType): Boolean {
            val prefs = PiruNotifications.mirrorPrefs(context)
            val master = if (prefs.contains(KEY_MASTER)) prefs.getBoolean(KEY_MASTER, true) else true
            val key = mirrorKey(type)
            val enabled = if (prefs.contains(key)) prefs.getBoolean(key, type.defaultEnabled) else type.defaultEnabled
            return master && enabled
        }

        /** Whether the user has left this type's break-through choice on. */
        fun isTimeSensitiveEnabled(context: Context, type: NotificationType): Boolean {
            if (!type.supportsTimeSensitive) return false
            val prefs = PiruNotifications.mirrorPrefs(context)
            val key = timeSensitiveMirrorKey(type)
            return if (prefs.contains(key)) prefs.getBoolean(key, true) else true
        }

        /**
         * Whether [at] falls inside the user's quiet window.
         *
         * The window is local clock time and may wrap past midnight, so 23:00 →
         * 07:00 is two half-ranges rather than an inverted one. A window whose two
         * ends are equal is empty, not all-day: an all-day quiet window would
         * silently mean "no notifications at all", which is not a thing anyone
         * sets on purpose and not a thing a stray tap should be able to produce.
         *
         * The zone is a parameter rather than read from the system inside the
         * calculation, so a test can pin it and a caller that means "the user's
         * own clock" says so by passing `ZoneId.systemDefault()`.
         *
         * **Evaluated against the fire time, never against now.** A reminder
         * scheduled at 22:50 for 23:30 is inside the window even though the moment
         * of scheduling is not, and the reverse case — logging a dose at 07:30 for
         * a reminder that fires at 06:00 — is why the schedulers pass a fire date
         * they computed from the dose rather than the clock they happen to hold.
         */
        fun isInQuietHours(
            context: Context,
            at: Instant,
            zone: ZoneId = ZoneId.systemDefault(),
        ): Boolean {
            val prefs = PiruNotifications.mirrorPrefs(context)
            if (!prefs.getBoolean(KEY_QUIET_ENABLED, false)) return false
            val start = prefs.getInt(KEY_QUIET_START, DEFAULT_QUIET_START)
            val end = prefs.getInt(KEY_QUIET_END, DEFAULT_QUIET_END)
            val time = at.atZone(zone).toLocalTime()
            val minutes = time.hour * 60 + time.minute
            if (start == end) return false
            return if (start < end) minutes >= start && minutes < end else minutes >= start || minutes < end
        }

        private const val DEFAULT_QUIET_START = 23 * 60
        private const val DEFAULT_QUIET_END = 7 * 60
    }
}

// MARK: - Type ↔ row mapping

/** The row's value for [type] — the same mapping as the iOS `recordKeyPath`. */
fun NotificationPreferencesEntity.isEnabled(type: NotificationType): Boolean = when (type) {
    NotificationType.HYDRATION -> hydrationEnabled
    NotificationType.SLEEP -> sleepEnabled
    NotificationType.PHASE -> phaseEnabled
    NotificationType.CUMULATIVE -> cumulativeEnabled
    NotificationType.ROUTINE -> routineEnabled
    NotificationType.ROUTINE_FOLLOW_UP -> routineFollowUpEnabled
    NotificationType.INVENTORY -> inventoryEnabled
    NotificationType.CHECK_IN -> checkInEnabled
}

/** The row's Time Sensitive choice for [type]; false for types that never deliver that way. */
fun NotificationPreferencesEntity.isTimeSensitiveEnabled(type: NotificationType): Boolean = when (type) {
    NotificationType.ROUTINE -> routineTimeSensitive
    NotificationType.ROUTINE_FOLLOW_UP -> routineFollowUpTimeSensitive
    NotificationType.CUMULATIVE -> cumulativeTimeSensitive
    NotificationType.HYDRATION, NotificationType.SLEEP, NotificationType.PHASE,
    NotificationType.INVENTORY, NotificationType.CHECK_IN,
    -> false
}

/** [this] with [type]'s column set to [value]. */
fun NotificationPreferencesEntity.withEnabled(
    type: NotificationType,
    value: Boolean,
): NotificationPreferencesEntity = when (type) {
    NotificationType.HYDRATION -> copy(hydrationEnabled = value)
    NotificationType.SLEEP -> copy(sleepEnabled = value)
    NotificationType.PHASE -> copy(phaseEnabled = value)
    NotificationType.CUMULATIVE -> copy(cumulativeEnabled = value)
    NotificationType.ROUTINE -> copy(routineEnabled = value)
    NotificationType.ROUTINE_FOLLOW_UP -> copy(routineFollowUpEnabled = value)
    NotificationType.INVENTORY -> copy(inventoryEnabled = value)
    NotificationType.CHECK_IN -> copy(checkInEnabled = value)
}

/** [this] with [type]'s Time Sensitive column set to [value]. */
fun NotificationPreferencesEntity.withTimeSensitive(
    type: NotificationType,
    value: Boolean,
): NotificationPreferencesEntity = when (type) {
    NotificationType.ROUTINE -> copy(routineTimeSensitive = value)
    NotificationType.ROUTINE_FOLLOW_UP -> copy(routineFollowUpTimeSensitive = value)
    NotificationType.CUMULATIVE -> copy(cumulativeTimeSensitive = value)
    NotificationType.HYDRATION, NotificationType.SLEEP, NotificationType.PHASE,
    NotificationType.INVENTORY, NotificationType.CHECK_IN,
    -> this
}
