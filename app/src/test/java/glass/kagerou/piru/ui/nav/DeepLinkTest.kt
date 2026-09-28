package glass.kagerou.piru.ui.nav

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The `piru://` links the notification schedulers produce.
 *
 * These strings are written in one module and read in another, and nothing but
 * this test connects them: a scheduler that changes its format and a parser that
 * does not agree produces no error, no log line and no crash — it produces a
 * notification that opens the journal instead of the session it is about. Each
 * case below is a literal copied from the call site that builds it.
 */
class DeepLinkTest {

    @Test
    fun `a check-in nudge opens its session`() {
        // CheckInScheduler: "$DEEP_LINK_SCHEME://session/${session.id}?note=checkIn"
        parseDeepLink("piru://session/8B1F0A2C-0000-4000-8000-000000000001?note=checkIn") shouldBe
            DeepLinkTarget.Session("8B1F0A2C-0000-4000-8000-000000000001")
    }

    @Test
    fun `a low-stock alert opens its item`() {
        // InventoryNotifier: "$SCHEME://inventory/$itemId"
        parseDeepLink("piru://inventory/8B1F0A2C-0000-4000-8000-000000000002") shouldBe
            DeepLinkTarget.InventoryItem("8B1F0A2C-0000-4000-8000-000000000002")
    }

    @Test
    fun `a med reminder opens the log sheet`() {
        // MedReminderScheduler: "$SCHEME://quicklog?routine=$slug"
        parseDeepLink("piru://quicklog?routine=morning") shouldBe DeepLinkTarget.QuickLog("morning")
    }

    @Test
    fun `a quicklog link with no routine still opens the sheet`() {
        parseDeepLink("piru://quicklog") shouldBe DeepLinkTarget.QuickLog(null)
        parseDeepLink("piru://quicklog?routine=") shouldBe DeepLinkTarget.QuickLog(null)
    }

    @Test
    fun `an entry link carries its timestamp in milliseconds`() {
        parseDeepLink("piru://entry/1790000000000") shouldBe DeepLinkTarget.Entry(1_790_000_000_000L)
    }

    @Test
    fun `an entry timestamp that is not a number is not a link to the epoch`() {
        // `toLongOrNull` rather than a default: opening 1970 is a worse answer than
        // opening nothing, because it looks like the link worked.
        parseDeepLink("piru://entry/not-a-number").shouldBeNull()
        parseDeepLink("piru://entry/").shouldBeNull()
    }

    @Test
    fun `a link this build does not know is dropped`() {
        parseDeepLink("piru://somethingelse/1").shouldBeNull()
        parseDeepLink("piru://").shouldBeNull()
        parseDeepLink("piru:///").shouldBeNull()
    }

    @Test
    fun `a link that is not ours is dropped`() {
        // A universal link, a web URL, or another app's scheme must not be read as
        // `piru://` by prefix accident.
        parseDeepLink("https://example.com/session/1").shouldBeNull()
        parseDeepLink("pirux://session/1").shouldBeNull()
        parseDeepLink("").shouldBeNull()
        parseDeepLink(null).shouldBeNull()
    }

    @Test
    fun `a link with no identifier is dropped rather than opened empty`() {
        parseDeepLink("piru://session/").shouldBeNull()
        parseDeepLink("piru://session").shouldBeNull()
        parseDeepLink("piru://inventory/").shouldBeNull()
    }

    @Test
    fun `extra query parameters do not confuse the routine`() {
        parseDeepLink("piru://quicklog?foo=bar&routine=evening&baz=1") shouldBe DeepLinkTarget.QuickLog("evening")
    }

    @Test
    fun `a routine parameter that is not first is still found`() {
        parseDeepLink("piru://quicklog?a=1&routine=night") shouldBe DeepLinkTarget.QuickLog("night")
    }

    @Test
    fun `a value that merely starts with the routine key is not the routine`() {
        // Guards against a `startsWith("routine")` shortcut, which would read
        // `routineId=9` as a routine named "Id=9".
        parseDeepLink("piru://quicklog?routineId=9") shouldBe DeepLinkTarget.QuickLog(null)
    }
}
