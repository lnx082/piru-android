package glass.kagerou.piru.data

import android.content.Context
import glass.kagerou.piru.engine.SessionDay

/**
 * The display and journal preferences that live outside the database.
 *
 * Ported from the `appGroup` half of iOS's `ExportedSettings` — the keys that are
 * `@AppStorage` on that side rather than SwiftData rows. Android has no app group, so they share
 * one `SharedPreferences` file with the notification mirror, under the keys upstream already
 * uses: a file exported from iOS lands on the same slots, which is the whole reason to keep the
 * spellings rather than invent tidier ones.
 *
 * ## Why this exists rather than a read at the call site
 * The day boundary is the case that forced it. `SessionDay.DAY_BOUNDARY_HOUR_KEY` was **read** by
 * the usage screen and **written by nothing** — no setting, no export, no import — so the value
 * was permanently the engine's 4 AM default and the screen's three-line fallback dance around it
 * could never take its other branch. Three other calendar call sites did not even read it and
 * passed `null`. A preference with a reader and no writer is a preference that does not exist.
 *
 * ## The bounds are the engine's, not this class's
 * [SessionDay.boundaryHour] clamps, and the model's own test pins what it does with an
 * out-of-range value. Storing the user's answer unclamped and clamping on read would mean two
 * places deciding what a valid hour is; storing it already clamped means the file and the screen
 * agree. Neither happens here: [setDayBoundaryHour] writes the clamped value the setter was
 * given, and the setter is what clamps.
 */
class AppSettingsStore(private val context: Context) {

    private fun prefs() = context.applicationContext
        .getSharedPreferences(MIRROR_FILE, Context.MODE_PRIVATE)

    /**
     * The hour a session day turns over, as the user set it.
     *
     * Null when it has never been set, which is not the same as 4: a null lets the engine's
     * default stand and lets a later import fill it in, whereas writing 4 explicitly would look
     * like an answer and would then outrank the file.
     */
    fun storedDayBoundaryHour(): Int? =
        if (prefs().contains(SessionDay.DAY_BOUNDARY_HOUR_KEY)) {
            prefs().getInt(SessionDay.DAY_BOUNDARY_HOUR_KEY, SessionDay.DEFAULT_BOUNDARY_HOUR)
        } else {
            null
        }

    /** The hour to compute with: the user's, or the engine's default. */
    fun dayBoundaryHour(): Int = SessionDay.boundaryHour(storedDayBoundaryHour())

    fun setDayBoundaryHour(hour: Int) {
        prefs().edit()
            .putInt(SessionDay.DAY_BOUNDARY_HOUR_KEY, SessionDay.boundaryHour(hour))
            .apply()
    }

    /**
     * Whether the day view stacks repeated doses of one substance.
     *
     * Defaults to true, which is what the two screens that draw the graph hard-coded before this
     * existed — so the default preserves the shipped behaviour and the setting only ever takes
     * something away.
     */
    fun stackRedoses(): Boolean = prefs().getBoolean(KEY_STACK_REDOSES, true)

    fun setStackRedoses(value: Boolean) {
        prefs().edit().putBoolean(KEY_STACK_REDOSES, value).apply()
    }
    // MARK: - The vertical timeline's display options

    /**
     * Points-per-hour multiplier for the strip.
     *
     * Clamped on read as well as on write: the pinch bounds are `[0.5, 5.0]`, and a value outside them that got into
     * the store another way would lay the strip out at a scale no gesture could return from.
     */
    /**
     * Whether logging a chip leaves the dock's order alone.
     *
     * Off by default, which is upstream's default and the useful one: a dock's whole point is that what you reached
     * for last is where your thumb already is.
     */
    fun quickLogFixedOrder(): Boolean = prefs().getBoolean(KEY_QUICK_LOG_FIXED_ORDER, false)

    fun setQuickLogFixedOrder(value: Boolean) {
        prefs().edit().putBoolean(KEY_QUICK_LOG_FIXED_ORDER, value).apply()
    }

    /**
     * The identities a user has removed from the dock's recents.
     *
     * Stored as a separator-joined string because the store is a preferences file, and held **per identity** rather
     * than per name: a user who removes a Concerta chip has not asked for Ritalin to go too, and those share a
     * substance name.
     */
    fun quickLogSuppressedRecents(): Set<String> =
        prefs().getString(KEY_QUICK_LOG_SUPPRESSED, null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

    fun setQuickLogSuppressedRecents(values: Set<String>) {
        // `commit` rather than `apply`: the suppression list is read back inside the same logging pass that writes
        // it, and an async write would be racing that read.
        prefs().edit()
            .putString(KEY_QUICK_LOG_SUPPRESSED, values.joinToString(SEPARATOR))
            .commit()
    }

    fun timelineZoom(): Double = prefs().getFloat(KEY_TIMELINE_ZOOM, 1.0f).toDouble().coerceIn(0.5, 5.0)

    fun setTimelineZoom(value: Double) {
        prefs().edit().putFloat(KEY_TIMELINE_ZOOM, value.coerceIn(0.5, 5.0).toFloat()).apply()
    }

    /** Collapse the empty stretches between clusters. */
    fun timelineCompressGaps(): Boolean = prefs().getBoolean(KEY_TIMELINE_COMPRESSION, true)

    fun setTimelineCompressGaps(value: Boolean) {
        prefs().edit().putBoolean(KEY_TIMELINE_COMPRESSION, value).apply()
    }

    /** Draw the modeled concentration curves behind the bubbles. */
    fun timelinePKCurves(): Boolean = prefs().getBoolean(KEY_TIMELINE_PK_CURVES, false)

    fun setTimelinePKCurves(value: Boolean) {
        prefs().edit().putBoolean(KEY_TIMELINE_PK_CURVES, value).apply()
    }

    /** Show the hour axis down the left edge. */
    fun timelineShowsAxis(): Boolean = prefs().getBoolean(KEY_TIMELINE_SHOWS_AXIS, true)

    /** Whether the session chart draws the cardio lane. */
    fun timelineVitalsShown(): Boolean = prefs().getBoolean(KEY_TIMELINE_VITALS, true)

    fun setTimelineVitalsShown(value: Boolean) {
        prefs().edit().putBoolean(KEY_TIMELINE_VITALS, value).apply()
    }

    fun setTimelineShowsAxis(value: Boolean) {
        prefs().edit().putBoolean(KEY_TIMELINE_SHOWS_AXIS, value).apply()
    }

    /** How much of a dose each bubble spells out. Stored as its wire value. */
    fun timelineBubbleStyle(): String =
        prefs().getString(KEY_TIMELINE_BUBBLE_STYLE, "full") ?: "full"

    fun setTimelineBubbleStyle(value: String) {
        prefs().edit().putString(KEY_TIMELINE_BUBBLE_STYLE, value).apply()
    }

    /**
     * The options the timeline layout is keyed on.
     *
     * A display change re-lays the strip; nothing else does. Upstream keeps the same string for the same reason, and
     * its shape matters: two surfaces draw the strip — the pushed timeline screen and the journal's own grouping —
     * and a shared signature is what makes a change on one show on the other.
     */
    fun timelineLayoutSignature(): String = listOf(
        timelineZoom().toString(),
        timelineCompressGaps().toString(),
        timelinePKCurves().toString(),
        timelineShowsAxis().toString(),
        timelineBubbleStyle(),
    ).joinToString("|")


    /**
     * The user's source ranking, or null when they have never reordered.
     *
     * Null is not the same as empty: the catalogue falls back to the shipped `default_priority`
     * column, and an empty list would rank every source equally — which is a real behaviour
     * (`SourcePriority` yields the constant 999 so every row ties) and one nobody asked for.
     *
     * Stored as one comma-joined string of slugs. The slugs are the catalogue's own identifiers and
     * contain no commas, so a separator is safe; a slug the catalogue no longer carries is still kept
     * in the list rather than pruned, because dropping it would silently discard part of the user's
     * ordering the next time they look at the screen.
     */
    fun sourceOrder(): List<String>? = prefs()
        .getString(KEY_SOURCE_ORDER, null)
        ?.split(',')
        ?.filter { it.isNotBlank() }
        ?.takeIf { it.isNotEmpty() }

    /** Record a source ranking. See [sourceOrder] for why an empty list clears rather than stores. */
    fun setSourceOrder(slugs: List<String>) {
        prefs().edit().apply {
            if (slugs.isEmpty()) {
                remove(KEY_SOURCE_ORDER)
            } else {
                putString(KEY_SOURCE_ORDER, slugs.joinToString(","))
            }
        }.apply()
    }

    /**
     * The tabs the user has chosen to **hide**, as wire values, or null when they have never chosen.
     *
     * The hidden set rather than the visible one, and the reason is a bug a test found: a list of *visible* tabs
     * cannot distinguish "deliberately hidden" from "never mentioned", and the bar appends anything never
     * mentioned — so hiding a tab was undone on the next read. Storing what is hidden is always a complete
     * statement, and a tab a later build adds is visible by default.
     *
     * Null rather than an empty list: an empty list means "hide nothing", which is a preference; null means "no
     * preference", and the bar falls back to every tab in its declared order.
     */
    fun hiddenTabs(): List<String>? = prefs()
        .getString(KEY_VISIBLE_TABS, null)
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }

    /** Stores the hidden tabs. An empty list clears the preference rather than storing "hide nothing". */
    fun setHiddenTabs(wireValues: List<String>) {
        prefs().edit().apply {
            if (wireValues.isEmpty()) {
                // An empty hidden set is the same behaviour as no preference, so store the absence instead.
                remove(KEY_VISIBLE_TABS)
            } else {
                putString(KEY_VISIBLE_TABS, wireValues.joinToString(","))
            }
        }.apply()
    }

    /** Whether the bottom bar shows labels. Defaults to true, which is what a five-tab bar has always done. */
    fun tabLabelsShown(): Boolean = prefs().getBoolean(KEY_TAB_LABELS, true)

    fun setTabLabelsShown(value: Boolean) {
        prefs().edit().putBoolean(KEY_TAB_LABELS, value).apply()
    }

    /**
     * Whether the journal offers the quick-log chip dock.
     *
     * **Nothing reads this yet, and that is recorded rather than left to be discovered.** The journal has no chip row:
     * `QuickLogSheet` is presented from a `FloatingActionButton`, and the port's own note says the dock's *collapsed
     * presentation* was not ported. A switch on the settings screen for it was removed for that reason — an inert
     * toggle says "this is controllable" about something that is not.
     *
     * The accessor stays, and stays exported, because the missing half is a UI feature and this is the data half: when
     * the dock is drawn, wiring it is a one-line read rather than a preference-file migration. The same reasoning as
     * [adherenceReminderOffsetMinutes].
     */
    fun showQuickLogDock(): Boolean = prefs().getBoolean(KEY_SHOW_QUICK_LOG_DOCK, true)

    /**
     * The library's recent searches, most recent first.
     *
     * An explicit empty list and "never searched" are the **same** state here, unlike the quick-log suppression list
     * where the difference matters: nothing can be removed from a history one term at a time, so the absent key and the
     * empty list mean the same thing and both read as empty.
     */
    fun recentSearches(): List<String> = prefs()
        .getString(KEY_RECENT_SEARCHES, null)
        ?.split(SEPARATOR)
        ?.filter { it.isNotBlank() }
        .orEmpty()

    /**
     * Records a search, moving a repeat to the front and dropping the oldest past [RECENT_SEARCH_LIMIT].
     *
     * Case-insensitive de-duplication: "caffeine" and "Caffeine" are one search, and storing both would put two chips
     * in the row that do the same thing.
     *
     * `commit()` rather than `apply()`: the caller has just left the field, and a write landing after the process dies
     * loses the term the user just searched for. The suppression list does the same for the same reason.
     */
    fun recordSearch(term: String) {
        val trimmed = term.trim()
        if (trimmed.isEmpty()) return
        val updated = (listOf(trimmed) + recentSearches().filterNot { it.equals(trimmed, ignoreCase = true) })
            .take(RECENT_SEARCH_LIMIT)
        prefs().edit().putString(KEY_RECENT_SEARCHES, updated.joinToString(SEPARATOR)).commit()
    }

    /** Forgets the search history. */
    fun clearRecentSearches() {
        prefs().edit().remove(KEY_RECENT_SEARCHES).commit()
    }

    fun setShowQuickLogDock(value: Boolean) {
        prefs().edit().putBoolean(KEY_SHOW_QUICK_LOG_DOCK, value).apply()
    }

    /**
     * Whether scheduled-med reminders are delivered.
     *
     * This one **is** read: `MedReminderScheduler` calls `NotificationPreferencesStore.allows(…
     * NotificationType.ROUTINE)`, which is the store that owns the decision, and the settings screen writes this
     * alongside it so a reader looking under "logging" finds the switch rather than having to know it lives under
     * notifications. Two surfaces over one decision, which is why the screen's own doc says so.
     */
    fun adherenceRemindersEnabled(): Boolean = prefs().getBoolean(KEY_ADHERENCE_REMINDERS_ENABLED, true)

    fun setAdherenceRemindersEnabled(value: Boolean) {
        prefs().edit().putBoolean(KEY_ADHERENCE_REMINDERS_ENABLED, value).apply()
    }

    /**
     * Minutes after a scheduled time a reminder fires, clamped to a day.
     *
     * Clamped rather than trusted: a negative offset would fire *before* the dose is due, and more than a day would
     * fire after the next one. Both are arithmetic the reminder scheduler should not have to defend against, so the
     * store refuses to hold them — the same rule [timelineZoom] follows for its ladder.
     *
     * **Nothing reads this yet.** `DailyDoseItemEntity.reminderTimesJson` is read by `MedReminderScheduler` and written
     * by **nothing in the UI** — only by an import — so there is no way for a user to set a time for this to offset. The
     * settings field for it was removed for that reason; the accessor stays for the same one as
     * [showQuickLogDock]: the missing half is a per-item time editor, and this is the data half.
     */
    fun adherenceReminderOffsetMinutes(): Int =
        prefs().getInt(KEY_ADHERENCE_REMINDER_OFFSET, 0).coerceIn(0, 24 * 60)

    fun setAdherenceReminderOffsetMinutes(value: Int) {
        prefs().edit().putInt(KEY_ADHERENCE_REMINDER_OFFSET, value.coerceIn(0, 24 * 60)).apply()
    }

    /** An empty preferences file is the fresh-install state; nothing else needs clearing. */
    /** An empty preferences file is the fresh-install state; nothing else needs clearing. */
    fun clearForImport() {
        prefs().edit()
            .remove(SessionDay.DAY_BOUNDARY_HOUR_KEY)
            .remove(KEY_STACK_REDOSES)
            .remove(KEY_SOURCE_ORDER)
            .remove(KEY_VISIBLE_TABS)
            .remove(KEY_TIMELINE_ZOOM)
            .remove(KEY_TIMELINE_COMPRESSION)
            .remove(KEY_TIMELINE_PK_CURVES)
            .remove(KEY_TIMELINE_SHOWS_AXIS)
            .remove(KEY_TIMELINE_VITALS)
            .remove(KEY_TIMELINE_BUBBLE_STYLE)
            .remove(KEY_QUICK_LOG_FIXED_ORDER)
            .remove(KEY_QUICK_LOG_SUPPRESSED)
            .remove(KEY_TAB_LABELS)
            // The three added after this chain was written. A reset that forgot one would leave the user
            .remove(KEY_SHOW_QUICK_LOG_DOCK)
            // with a preference they cannot see and cannot clear — the failure the chain exists to prevent.
            .remove(KEY_ADHERENCE_REMINDERS_ENABLED)
            .remove(KEY_ADHERENCE_REMINDER_OFFSET)
            .remove(KEY_RECENT_SEARCHES)
            .apply()
    }

    companion object {
        /**
         * The same file the notification mirror uses — `PiruNotifications.MIRROR_PREFS`,
         * `"piru.notifications"`.
         *
         * Not a new file, and not a coincidence: that mirror exists so a scheduler can gate
         * synchronously from a receiver without opening the database, which is the same reason
         * these preference-shaped values have a home here at all. One small file of scalars
         * rather than one per concern, because the alternative is a list of file names that
         * grows every time a preference starts mattering.
         */
        const val MIRROR_FILE: String = "piru.notifications"

        /** Upstream's spelling, so a file from iOS lands here. */
        const val KEY_STACK_REDOSES: String = "stackRedoses"

        /**
         * The source ranking, in this port's own spelling.
         *
         * `ExportedSettings.sourcePreferences` exists on the iOS side as a list of objects — id,
         * enabled, priority per source — and this port's export reads it as raw JSON for the settings
         * section. Flattening it to a slug order here rather than round-tripping the objects keeps one
         * representation of "which source wins"; the import path is where the two shapes meet, and it
         * is deliberately not this key's business.
         */
        const val KEY_SOURCE_ORDER: String = "sourceOrder"

    /** Which tabs the bottom bar shows, comma-joined wire values. Absent means "no preference". */
    /**
     * The timeline's display options. The names are upstream's own, because a settings export from iOS carries
     * them and the two platforms have to agree about what `timelineZoom` means.
     */
    /**
     * The quick log's two dock preferences, named as upstream names them so an imported settings file means the same
     * thing on both platforms.
     */
    const val KEY_QUICK_LOG_FIXED_ORDER: String = "quickLogFixedOrder"
    const val KEY_QUICK_LOG_SUPPRESSED: String = "quickLogSuppressedRecents"

    const val KEY_TIMELINE_ZOOM: String = "timelineZoom"
    const val KEY_TIMELINE_COMPRESSION: String = "timelineCompression"
    const val KEY_TIMELINE_PK_CURVES: String = "timelinePKCurves"
    const val KEY_TIMELINE_SHOWS_AXIS: String = "timelineShowsAxis"

    /**
     * Whether the session chart draws the cardio lane — heart rate and blood pressure from Health Connect.
     *
     * Defaults to true. The lane draws only when vitals exist, and vitals exist only because the reader granted Health
     * Connect, so it is **already opt-in by permission**; defaulting this to off would make granting the permission
     * draw nothing, which is the worse surprise. This switch is for a reader who has the permission for other reasons
     * and does not want heart rate over their timeline.
     */
    const val KEY_TIMELINE_VITALS: String = "timelineVitalsShown"
    const val KEY_TIMELINE_BUBBLE_STYLE: String = "timelineBubbleStyle"

    const val KEY_VISIBLE_TABS: String = "visibleTabs"

    /** Whether the bottom bar draws its labels. */
    const val KEY_TAB_LABELS: String = "tabLabels"

    /** Whether the journal offers the quick-log chip dock. Defaults to true. */
    const val KEY_SHOW_QUICK_LOG_DOCK: String = "showQuickLogDock"

    /** Whether scheduled-med reminders are delivered at all. Defaults to true. */
    const val KEY_ADHERENCE_REMINDERS_ENABLED: String = "adherenceRemindersEnabled"

    /**
     * How many minutes after a scheduled time a reminder fires. Defaults to `0` — at the scheduled time.
     *
     * Stored as an **offset** rather than a clock time, because the item's own `reminderTimesJson` already carries the
     * schedule: a second absolute time would be a second source of truth for the same thing.
     */
    const val KEY_ADHERENCE_REMINDER_OFFSET: String = "adherenceReminderOffsetMinutes"

    /**
     * The library's recent searches, most recent first.
     *
     * Joined with the same separator the suppression list uses, because a search term can contain any printable
     * character — including the commas a simpler encoding would split on.
     */
    const val KEY_RECENT_SEARCHES: String = "recentSearches"

    /**
     * How the suppressed-recents list is joined.
     *
     * A unit separator rather than a comma or a colon: an identity key is built from a substance name, and a name can
     * contain any printable character a user or the catalogue chose. `\u001F` cannot appear in one.
     */
    private const val SEPARATOR: String = "\u001F"
    /**
     * How many searches the history keeps.
     *
     * Eight: enough to cover a session's searching, few enough that the chip row stays one row. The cap
     * is not a nicety — an unbounded list grows forever and turns a row of chips into a wall.
     */
    private const val RECENT_SEARCH_LIMIT: Int = 8
    }
}
