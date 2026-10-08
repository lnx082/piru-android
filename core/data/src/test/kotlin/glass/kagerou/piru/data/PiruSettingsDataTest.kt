package glass.kagerou.piru.data

import glass.kagerou.piru.data.export.PiruSettingsData
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

/**
 * The shapes the settings section reads.
 *
 * These are the readers that decide whether a file from iOS restores here, and they are written to
 * be tolerant on purpose: the day boundary arrived as an unread key for the entire life of the
 * port, so the failure mode to guard against is a value that is present and silently ignored.
 */
class PiruSettingsDataTest {

    @Test
    fun `the tagged shapes upstream writes are read`() {
        // `{"bool":true}`, `{"int":4}`, `{"double":1.5}` — the tag names the type, which is how
        // Foundation's `PiruSettingValue` encodes it.
        PiruSettingsData.booleanValue(buildJsonObject { put("bool", true) }) shouldBe true
        PiruSettingsData.booleanValue(buildJsonObject { put("bool", false) }) shouldBe false
        PiruSettingsData.intValue(buildJsonObject { put("int", 4) }) shouldBe 4
        PiruSettingsData.intValue(buildJsonObject { put("double", 1.5) }) shouldBe 1
    }

    @Test
    fun `a bare primitive is read too`() {
        // Not what upstream writes, but a hand-edited file or a later simplification must not
        // produce a silently-ignored setting.
        PiruSettingsData.booleanValue(JsonPrimitive(true)) shouldBe true
        PiruSettingsData.intValue(JsonPrimitive(6)) shouldBe 6
    }

    @Test
    fun `absent and explicitly unset are different`() {
        // Absent means "the exporting build did not know this key" — leave the local value alone.
        PiruSettingsData.booleanValue(null) shouldBe null
        PiruSettingsData.intValue(null) shouldBe null
        // Explicitly null means "unknown on the exporting install", which an import must also not
        // apply: writing the default over the local value would turn "I never set this" into
        // "I set it to 4 AM".
        PiruSettingsData.booleanValue(JsonNull) shouldBe null
        PiruSettingsData.intValue(JsonNull) shouldBe null
        PiruSettingsData.isExplicitlyUnset(JsonNull) shouldBe true
        PiruSettingsData.isExplicitlyUnset(null) shouldBe false
    }

    @Test
    fun `a value of the wrong kind is null rather than a guess`() {
        // A `string` where an `int` is expected must not coerce: a setting read as the wrong type
        // is worse than a setting left alone.
        PiruSettingsData.intValue(buildJsonObject { put("string", "4") }) shouldBe null
        PiruSettingsData.booleanValue(buildJsonObject { put("int", 1) }) shouldBe null
        // And the `data` a future key might carry does not make the reader throw. The real shape is
        // an object rather than a primitive, and the cast to `JsonPrimitive` is what refuses it.
        PiruSettingsData.intValue(buildJsonObject { put("data", "AAAA") }) shouldBe null
    }

    @Test
    fun `the wire keys are upstream's spellings`() {
        // These are wire names: renaming either orphans every file that carries it, on both sides.
        PiruSettingsData.KEY_DAY_BOUNDARY_HOUR shouldBe "dayBoundaryHour"
        PiruSettingsData.KEY_STACK_REDOSES shouldBe "stackRedoses"
        // And the day boundary's spelling is the engine's own key, which is what makes an import
        // land on the slot the calendar screens read.
        PiruSettingsData.KEY_DAY_BOUNDARY_HOUR shouldBe glass.kagerou.piru.engine.SessionDay.DAY_BOUNDARY_HOUR_KEY
    }

    @Test
    fun `a section is addressed by domain`() {
        val section = PiruSettingsData(
            standard = mapOf("a" to JsonPrimitive(1)),
            appGroup = mapOf("b" to JsonPrimitive(2)),
        )
        section.standardValue("a") shouldBe JsonPrimitive(1)
        section.appGroupValue("b") shouldBe JsonPrimitive(2)
        // The two domains do not see each other's keys, which is the point of keying by domain.
        section.appGroupValue("a") shouldBe null
        section.standardValue("b") shouldBe null
    }
}
