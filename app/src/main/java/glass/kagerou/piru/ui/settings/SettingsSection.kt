package glass.kagerou.piru.ui.settings

import glass.kagerou.piru.data.AppSettingsStore
import glass.kagerou.piru.data.export.PiruSettingsData
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The `settings` section as this build reads and writes it.
 *
 * Ported from the key list in iOS's `DataExportImport+Settings.swift`, narrowed to the keys this
 * port actually carries. Two, today: the hour a session day turns over and whether repeated doses
 * of one substance are stacked into a single curve.
 *
 * ## Narrow on purpose
 * Upstream writes around forty-five keys across two domains. Writing the ones this build cannot
 * honour would be worse than omitting them: a `timelineZoom` in the file claims a value for a
 * screen that does not exist, and every export-after-import would carry one device's defaults
 * forward as though they were the user's choices. An absent key is already defined by upstream as
 * "something the exporting build did not know, which an import leaves alone", so omitting is the
 * honest form of "this build has no such setting".
 *
 * ## The values are upstream's tagged shape
 * `{"bool":true}`, `{"int":4}`, `{"double":1.5}`, `{"string":"x"}`, `{"strings":[…]}`,
 * `{"data":"<base64>"}`. Written in that shape and read back tolerantly — [PiruSettingsData]
 * understands both the tagged form and a bare primitive — so a file this build writes restores on
 * iOS and a file iOS writes restores here.
 */
internal object SettingsSection {

    /** Build the section from what this build stores. */
    fun read(settings: AppSettingsStore): PiruSettingsData {
        val standard = buildJsonObject { }
        val appGroup = buildJsonObject {
            // A null here means "never set on this install", which is not the same as "set to 4":
            // writing 4 for an unset value would look like an answer and would then outrank the
            // file on somebody else's import.
            put(
                PiruSettingsData.KEY_DAY_BOUNDARY_HOUR,
                settings.storedDayBoundaryHour()?.let { JsonPrimitive(it) } ?: JsonNull,
            )
            put(PiruSettingsData.KEY_STACK_REDOSES, JsonPrimitive(settings.stackRedoses()))
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

        PiruSettingsData.intValue(section.appGroupValue(PiruSettingsData.KEY_DAY_BOUNDARY_HOUR))
            ?.let {
                settings.setDayBoundaryHour(it)
                applied += PiruSettingsData.KEY_DAY_BOUNDARY_HOUR
            }

        PiruSettingsData.booleanValue(section.appGroupValue(PiruSettingsData.KEY_STACK_REDOSES))
            ?.let {
                settings.setStackRedoses(it)
                applied += PiruSettingsData.KEY_STACK_REDOSES
            }

        return applied
    }
}
