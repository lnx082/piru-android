package glass.kagerou.piru.data.export

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonArray

/**
 * The `settings` section of a Piru-native file.
 *
 * Ported from iOS's `PiruSettingsData` (`DataExportImport+Settings.swift`). Keyed by domain, then
 * by key, because that is how the exporter groups them and a flatter shape would need the domain
 * folded into every name.
 *
 * ## What this port writes, and why it is smaller than upstream's
 * Upstream writes every key in its `ExportedSettings` list — around forty-five, across two
 * domains — including skins, the dock, the tab layout and five timeline toggles. This build has
 * none of those, and a key it cannot honour must not be written: writing `timelineZoom` would
 * claim a value for a screen that does not exist, and writing it again on export after an import
 * would slowly turn one device's file into every device's settings.
 *
 * So this writes **only the keys it carries** — [KEY_DAY_BOUNDARY_HOUR], [KEY_STACK_REDOSES] — and
 * leaves the rest absent. That is exactly the semantic upstream already gives an absent key:
 * "A key the exporting build did not know is absent, which an import leaves alone."
 *
 * ## Values are read per key, not decoded as one union
 * Upstream types each value as `{"bool":true}` / `{"int":4}` / `{"string":"…"}` / `{"strings":[…]}`
 * / `{"data":"<base64>"}`. A Kotlin union of those would have to decode *every* setting in the
 * file to read *any* of them, so one unknown kind — a `data` blob this build has no key for —
 * would fail the whole import. [settingValue] therefore reads the shape it is asked for and
 * returns null for anything else, which is also what "this key is not one I know" means.
 */
@Serializable
data class PiruSettingsData(
    val standard: Map<String, JsonElement>? = null,
    val appGroup: Map<String, JsonElement>? = null,
    /**
     * The source-priority table, or null when none could be read.
     *
     * Carried through as raw elements rather than modelled: this build always reads its shipped
     * source order and has no screen to reorder it, so there is nothing to write them into — but
     * a file that has them must not lose them on a round trip through this app.
     */
    val sourcePreferences: List<JsonElement>? = null,
) {

    /** The `appGroup` value for [key], or null when absent. */
    fun appGroupValue(key: String): JsonElement? = appGroup?.get(key)

    /** The `standard` value for [key], or null when absent. */
    fun standardValue(key: String): JsonElement? = standard?.get(key)

    companion object {
        /**
         * Upstream's key for the hour a session day turns over.
         *
         * The same string `SessionDay.DAY_BOUNDARY_HOUR_KEY` holds, spelled out here because this
         * is a wire name: a file from iOS must land on the same slot, and this constant is what
         * pins that independently of the engine's own copy.
         */
        const val KEY_DAY_BOUNDARY_HOUR: String = "dayBoundaryHour"

        /** Upstream's key for the redose-stacking preference. */
        const val KEY_STACK_REDOSES: String = "stackRedoses"

        /**
         * Upstream's key for how far the timeline is zoomed.
         *
         * Tagged a `Double` on that side, so a whole `2.0` arrives as `{"double":2}` — [doubleValue] accepts the
         * integer tag for the same reason [intValue] accepts the double one.
         */
        const val KEY_TIMELINE_ZOOM: String = "timelineZoom"

        /** Upstream's key for whether the timeline compresses the quiet stretches between doses. */
        const val KEY_TIMELINE_COMPRESSION: String = "timelineCompression"

        /** Upstream's key for whether the timeline draws a modelled PK curve. */
        const val KEY_TIMELINE_PK_CURVES: String = "timelinePKCurves"

        /** Upstream's key for whether the timeline draws its time axis. */
        const val KEY_TIMELINE_SHOWS_AXIS: String = "timelineShowsAxis"

        /** Upstream's key for the timeline's bubble style, a string on that side. */
        const val KEY_TIMELINE_BUBBLE_STYLE: String = "timelineBubbleStyle"

        /** Upstream's key for whether the quick-log chips keep a fixed order. */
        const val KEY_QUICK_LOG_FIXED_ORDER: String = "quickLogFixedOrder"

        /** Upstream's key for the quick-log chips the user has removed. */
        const val KEY_QUICK_LOG_SUPPRESSED: String = "quickLogSuppressedRecents"

        /**
         * **This port's own** keys, which upstream has no slot for.
         *
         * The reference stores source order inside its `SubstanceStore` and its dock labels as a `data` blob, so
         * there is nothing to match on that side and nothing to collide with either. Exporting them under this
         * build's own names means a round trip through this build restores them, and a file from iOS simply does not
         * carry them — which is what an absent key already means.
         */
        const val KEY_SOURCE_ORDER: String = "sourceOrder"

        /** This port's key for the tab bar entries the user has hidden. */
        const val KEY_VISIBLE_TABS: String = "visibleTabs"

        /** This port's key for whether the tab bar carries labels. */
        const val KEY_TAB_LABELS: String = "tabLabels"

        /** This port's key for whether the journal offers the quick-log chip dock. */
        const val KEY_SHOW_QUICK_LOG_DOCK: String = "showQuickLogDock"

        /** This port's key for whether scheduled-med reminders are delivered. */
        const val KEY_ADHERENCE_REMINDERS_ENABLED: String = "adherenceRemindersEnabled"

        /**
         * This port's key for how long after a scheduled time a reminder fires.
         *
         * An **offset** in minutes rather than a clock time: the item's own `reminderTimesJson` carries the schedule,
         * and a second absolute time would be a second source of truth for the same thing.
         */
        const val KEY_ADHERENCE_REMINDER_OFFSET: String = "adherenceReminderOffsetMinutes"

        /** Whether [element] is present but explicitly unset, which is not the same as absent. */
        fun isExplicitlyUnset(element: JsonElement?): Boolean = element is JsonNull

        /**
         * The boolean [element] carries, or null when it is absent or not a boolean.
         *
         * Reads both the tagged form upstream writes (`{"bool":true}`) and a bare primitive, so a
         * hand-edited file or a future simplification still loads.
         */
        fun booleanValue(element: JsonElement?): Boolean? {
            if (element == null || element is JsonNull) return null
            val obj = element as? JsonObject
            val inner = obj?.get("bool") ?: element
            return (inner as? JsonPrimitive)?.booleanOrNull
        }

        /** The integer [element] carries, or null. Same two shapes as [booleanValue]. */
        fun intValue(element: JsonElement?): Int? {
            if (element == null || element is JsonNull) return null
            val obj = element as? JsonObject
            // `double` is accepted because the exporter tags a value by its Foundation type, and a
            // whole number stored as a `Double` on that side arrives as `{"double":4}`. Reading it
            // as absent would drop a setting that is present and unambiguous.
            val inner = obj?.get("int") ?: obj?.get("double") ?: element
            val primitive = inner as? JsonPrimitive ?: return null
            return primitive.intOrNull ?: primitive.doubleOrNull?.toInt()
        }

        /**
         * The double [element] carries, or null.
         *
         * Accepts the `int` tag as well as `double`, for the mirror of [intValue]'s reason: a whole zoom stored as an
         * integer on the other side arrives as `{"int":2}`, and reading it as absent would reset a setting that is
         * present and unambiguous.
         */
        fun doubleValue(element: JsonElement?): Double? {
            if (element == null || element is JsonNull) return null
            val obj = element as? JsonObject
            val inner = obj?.get("double") ?: obj?.get("int") ?: element
            val primitive = inner as? JsonPrimitive ?: return null
            return primitive.doubleOrNull ?: primitive.intOrNull?.toDouble()
        }

        /** The string [element] carries, or null. An empty string is a value, not an absence. */
        fun stringValue(element: JsonElement?): String? {
            if (element == null || element is JsonNull) return null
            val obj = element as? JsonObject
            val inner = obj?.get("string") ?: element
            return (inner as? JsonPrimitive)?.takeIf { it.isString }?.content
        }

        /**
         * The string list [element] carries, or null when it is absent.
         *
         * **An explicitly empty list is a value, not an absence**: "the user removed every quick-log chip" and "this
         * file has no opinion about the chips" are different statements, and collapsing them would resurrect chips the
         * user had deleted. That is why this returns a value for an empty array rather than falling through to null.
         */
        fun stringListValue(element: JsonElement?): List<String>? {
            if (element == null || element is JsonNull) return null
            val obj = element as? JsonObject
            // Upstream tags a list as `{"strings":[…]}`; a bare array is accepted for the same tolerance the scalar
            // readers show.
            val inner = obj?.get("strings") ?: element
            val array = inner as? JsonArray ?: return null
            return array.mapNotNull { primitive -> (primitive as? JsonPrimitive)?.takeIf { it.isString }?.content }
        }
    }
}
