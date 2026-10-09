package glass.kagerou.piru.ui.settings

import glass.kagerou.piru.data.AppSettingsStore
import glass.kagerou.piru.data.export.PiruSettingsData
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The `settings` section as this build reads and writes it.
 *
 * Ported from the key list in iOS's `DataExportImport+Settings.swift`.
 *
 * ## Twelve keys, and how the number got that far
 * It was **two** — the day boundary and the redose-stacking preference — and the comment that stood here said
 * "narrowed to the keys this port actually carries". That was true when it was written and stopped being true every
 * time this port gained a preference: ten more arrived (the timeline's five, the quick-log dock's two, the source
 * order, and the tab bar's two) and none of them were added here. So a backup could not restore a timeline layout, a
 * dock arrangement or a tab bar, and **nothing said so** — the export reported success while dropping them.
 *
 * That is the failure mode worth naming: the omission was honest when it was a decision and became a silent one when
 * it was merely neglect. This file now carries every key [AppSettingsStore] has, and `SettingsSectionCoverageTest`
 * reads the store's own `KEY_` constants rather than a hand-written list — so a thirteenth key cannot be added
 * without this section failing. **That test was watched to fail** by dropping a key from [KEYS], which is the only
 * reason to trust it.
 *
 * ## Narrow where upstream is not
 * Upstream writes around thirty-three keys. The ones this build has no setting for are still left out, and for the
 * original reason: a `liveActivityEnabled` in a file claims a value for a feature that does not exist here, and every
 * export-after-import would carry one device's defaults forward as though they were the user's choices. An absent key
 * is already defined upstream as "something the exporting build did not know, which an import leaves alone", so
 * omitting is the honest form of "this build has no such setting".
 *
 * ## The port-only keys
 * `sourceOrder` and `visibleTabs`/`tabLabels` have no upstream slot — the reference keeps source order inside its
 * `SubstanceStore` and its dock labels as a `data` blob, so there is nothing to match and nothing to collide with.
 * They are written under this build's own names, which means a round trip through this build restores them and a file
 * from iOS simply does not carry them. That is what an absent key already means, so it needs no special case.
 *
 * ## The values are upstream's tagged shape
 * `{"bool":true}`, `{"int":4}`, `{"double":1.5}`, `{"string":"x"}`, `{"strings":[…]}`,
 * `{"data":"<base64>"}`. Written in that shape and read back tolerantly — [PiruSettingsData]
 * understands both the tagged form and a bare primitive — so a file this build writes restores on
 * iOS and a file iOS writes restores here.
 */
internal object SettingsSection {

    /**
     * **Every** key this build stores, and the list the coverage test asserts against.
     *
     * Here rather than derived by reflection: this is a wire contract, and a contract spelled out is one a reader can
     * check against the other side. The test is what keeps it from going stale.
     */
    val KEYS: List<String> = listOf(
        PiruSettingsData.KEY_DAY_BOUNDARY_HOUR,
        PiruSettingsData.KEY_STACK_REDOSES,
        PiruSettingsData.KEY_TIMELINE_ZOOM,
        PiruSettingsData.KEY_TIMELINE_COMPRESSION,
        PiruSettingsData.KEY_TIMELINE_PK_CURVES,
        PiruSettingsData.KEY_TIMELINE_SHOWS_AXIS,
        PiruSettingsData.KEY_TIMELINE_BUBBLE_STYLE,
        PiruSettingsData.KEY_QUICK_LOG_FIXED_ORDER,
        PiruSettingsData.KEY_QUICK_LOG_SUPPRESSED,
        PiruSettingsData.KEY_SOURCE_ORDER,
        PiruSettingsData.KEY_VISIBLE_TABS,
        PiruSettingsData.KEY_TAB_LABELS,
        PiruSettingsData.KEY_SHOW_QUICK_LOG_DOCK,
        PiruSettingsData.KEY_ADHERENCE_REMINDERS_ENABLED,
        PiruSettingsData.KEY_ADHERENCE_REMINDER_OFFSET,
        PiruSettingsData.KEY_JOURNAL_GROUPING,
        PiruSettingsData.KEY_TIMELINE_VITALS,
        PiruSettingsData.KEY_RECENT_SEARCHES,
    )

    /** Build the section from what this build stores. */
    fun read(settings: AppSettingsStore): PiruSettingsData {
        val standard = buildJsonObject {
            // The quick-log dock's preferences live in the standard domain upstream, which is why they are split out
            // rather than simply appended to one object.
            put(PiruSettingsData.KEY_QUICK_LOG_FIXED_ORDER, JsonPrimitive(settings.quickLogFixedOrder()))
            put(
                PiruSettingsData.KEY_QUICK_LOG_SUPPRESSED,
                buildJsonArray {
                    for (entry in settings.quickLogSuppressedRecents().sorted()) add(JsonPrimitive(entry))
                },
            )
        }
        val appGroup = buildJsonObject {
            // A null here means "never set on this install", which is not the same as "set to 4":
            // writing 4 for an unset value would look like an answer and would then outrank the
            // file on somebody else's import.
            put(
                PiruSettingsData.KEY_DAY_BOUNDARY_HOUR,
                settings.storedDayBoundaryHour()?.let { JsonPrimitive(it) } ?: JsonNull,
            )
            put(PiruSettingsData.KEY_STACK_REDOSES, JsonPrimitive(settings.stackRedoses()))
            put(PiruSettingsData.KEY_TIMELINE_ZOOM, JsonPrimitive(settings.timelineZoom()))
            put(PiruSettingsData.KEY_TIMELINE_COMPRESSION, JsonPrimitive(settings.timelineCompressGaps()))
            put(PiruSettingsData.KEY_TIMELINE_PK_CURVES, JsonPrimitive(settings.timelinePKCurves()))
            put(PiruSettingsData.KEY_TIMELINE_SHOWS_AXIS, JsonPrimitive(settings.timelineShowsAxis()))
            put(PiruSettingsData.KEY_TIMELINE_VITALS, JsonPrimitive(settings.timelineVitalsShown()))
            put(PiruSettingsData.KEY_JOURNAL_GROUPING, JsonPrimitive(settings.journalGrouping()))
            put(PiruSettingsData.KEY_TIMELINE_BUBBLE_STYLE, JsonPrimitive(settings.timelineBubbleStyle()))

            // The two list-valued preferences. An unset one is written as **null**, not as an empty array: for these
            // two the store defines empty as "no preference" (it removes the key), so an empty array would be a claim
            // the store cannot make and would clear a reader's arrangement on import.
            put(
                PiruSettingsData.KEY_SOURCE_ORDER,
                settings.sourceOrder()?.let { list ->
                    buildJsonArray { for (slug in list) add(JsonPrimitive(slug)) }
                } ?: JsonNull,
            )
            put(
                PiruSettingsData.KEY_VISIBLE_TABS,
                settings.hiddenTabs()?.let { list ->
                    buildJsonArray { for (wire in list) add(JsonPrimitive(wire)) }
                } ?: JsonNull,
            )
            put(PiruSettingsData.KEY_TAB_LABELS, JsonPrimitive(settings.tabLabelsShown()))
            put(PiruSettingsData.KEY_SHOW_QUICK_LOG_DOCK, JsonPrimitive(settings.showQuickLogDock()))
            put(
                PiruSettingsData.KEY_ADHERENCE_REMINDERS_ENABLED,
                JsonPrimitive(settings.adherenceRemindersEnabled()),
            )
            put(
                PiruSettingsData.KEY_ADHERENCE_REMINDER_OFFSET,
                JsonPrimitive(settings.adherenceReminderOffsetMinutes()),
            )
            // A list, so it is written as an array rather than a joined string: the separator the store uses is an
            // implementation detail, and a file another build reads should not have to know it.
            put(
                PiruSettingsData.KEY_RECENT_SEARCHES,
                buildJsonArray { for (term in settings.recentSearches()) add(JsonPrimitive(term)) },
            )
        }
        return PiruSettingsData(standard = standard, appGroup = appGroup)
    }

    /**
     * Apply a section from a file, and say what it changed.
     *
     * Keys this build does not carry are skipped silently: that is what an absent key means
     * upstream, and reporting "settings" as an unsupported section was what the import used to do
     * with the whole thing. A key that is present but explicitly null is **not** applied — it
     * means the exporting install had no value, and writing a default over the local one would
     * turn "I never set this" into "I set it to 4 AM".
     */
    fun apply(section: PiruSettingsData?, settings: AppSettingsStore): List<String> {
        if (section == null) return emptyList()
        val applied = mutableListOf<String>()

        /**
         * Reads [key] from whichever domain carries it.
         *
         * Upstream files put some keys in the standard domain and some in the app group, and a key that is in the file
         * is a key the file means. Looking in both is what lets one reader handle either arrangement without a
         * per-key table that would go stale exactly like [KEYS] did.
         */
        fun raw(key: String) = section.appGroupValue(key) ?: section.standardValue(key)

        fun int(key: String, write: (Int) -> Unit) {
            PiruSettingsData.intValue(raw(key))?.let {
                write(it)
                applied += key
            }
        }

        fun bool(key: String, write: (Boolean) -> Unit) {
            PiruSettingsData.booleanValue(raw(key))?.let {
                write(it)
                applied += key
            }
        }

        fun double(key: String, write: (Double) -> Unit) {
            PiruSettingsData.doubleValue(raw(key))?.let {
                write(it)
                applied += key
            }
        }

        fun string(key: String, write: (String) -> Unit) {
            PiruSettingsData.stringValue(raw(key))?.let {
                write(it)
                applied += key
            }
        }

        fun strings(key: String, write: (List<String>) -> Unit) {
            PiruSettingsData.stringListValue(raw(key))?.let {
                write(it)
                applied += key
            }
        }

        // `intValue` already returns null for an explicit null, which is the behaviour the doc above describes, so the
        // day boundary needs no arm of its own.
        int(PiruSettingsData.KEY_DAY_BOUNDARY_HOUR) { settings.setDayBoundaryHour(it) }
        bool(PiruSettingsData.KEY_STACK_REDOSES) { settings.setStackRedoses(it) }
        double(PiruSettingsData.KEY_TIMELINE_ZOOM) { settings.setTimelineZoom(it) }
        bool(PiruSettingsData.KEY_TIMELINE_COMPRESSION) { settings.setTimelineCompressGaps(it) }
        bool(PiruSettingsData.KEY_TIMELINE_PK_CURVES) { settings.setTimelinePKCurves(it) }
        bool(PiruSettingsData.KEY_TIMELINE_SHOWS_AXIS) { settings.setTimelineShowsAxis(it) }
        bool(PiruSettingsData.KEY_TIMELINE_VITALS) { settings.setTimelineVitalsShown(it) }
        string(PiruSettingsData.KEY_JOURNAL_GROUPING) { settings.setJournalGrouping(it) }
        string(PiruSettingsData.KEY_TIMELINE_BUBBLE_STYLE) { settings.setTimelineBubbleStyle(it) }
        bool(PiruSettingsData.KEY_QUICK_LOG_FIXED_ORDER) { settings.setQuickLogFixedOrder(it) }
        strings(PiruSettingsData.KEY_QUICK_LOG_SUPPRESSED) { settings.setQuickLogSuppressedRecents(it.toSet()) }
        strings(PiruSettingsData.KEY_SOURCE_ORDER) { settings.setSourceOrder(it) }
        // `hiddenTabs`, not `visibleTabs`: the store's accessor is named for what it holds — the tabs the user has
        // hidden — while the wire key is this build's own name for the same idea. Reading the two names as
        // interchangeable is what would make this line look wrong and be "fixed" into a bug.
        strings(PiruSettingsData.KEY_VISIBLE_TABS) { settings.setHiddenTabs(it) }
        bool(PiruSettingsData.KEY_TAB_LABELS) { settings.setTabLabelsShown(it) }
        bool(PiruSettingsData.KEY_SHOW_QUICK_LOG_DOCK) { settings.setShowQuickLogDock(it) }
        bool(PiruSettingsData.KEY_ADHERENCE_REMINDERS_ENABLED) { settings.setAdherenceRemindersEnabled(it) }
        int(PiruSettingsData.KEY_ADHERENCE_REMINDER_OFFSET) { settings.setAdherenceReminderOffsetMinutes(it) }
        // Each term recorded through the public API rather than a bulk setter, so an import obeys the same
        // de-duplication and cap a user's own searching does. Oldest first, because `recordSearch` moves each new term
        // to the front — so replaying the file's order ends with the file's own order.
        strings(PiruSettingsData.KEY_RECENT_SEARCHES) { terms ->
            settings.clearRecentSearches()
            for (term in terms.asReversed()) settings.recordSearch(term)
        }

        return applied
    }
}
