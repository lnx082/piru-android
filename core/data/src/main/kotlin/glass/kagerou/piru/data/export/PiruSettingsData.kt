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
    }
}
