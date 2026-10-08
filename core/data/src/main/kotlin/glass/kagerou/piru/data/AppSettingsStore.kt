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

    /** An empty preferences file is the fresh-install state; nothing else needs clearing. */
    fun clearForImport() {
        prefs().edit()
            .remove(SessionDay.DAY_BOUNDARY_HOUR_KEY)
            .remove(KEY_STACK_REDOSES)
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
    }
}
