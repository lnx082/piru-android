package glass.kagerou.piru.ui.tools

import glass.kagerou.piru.ui.nav.PushRoute
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import glass.kagerou.piru.ui.nav.key

/**
 * The advanced search's own gate, which is a rule and not a query.
 *
 * ## Why this is testable without the catalogue
 * The screen's two decisions are "is any filter active" and "which tools does the hub show", and both are
 * pure functions of their inputs. The catalogue half — the actual binding query — is a device concern and
 * has its own spec; what a JVM test can pin is the rule that stops an unfiltered scan from running at all.
 *
 * Upstream's reasoning, kept: "Every measured binding in the catalogue" is 1,462 rows and is not an answer
 * to anything, so an empty filter set must not produce a query. Getting that wrong is a screen that opens
 * with a thousand rows and looks broken.
 */
class AdvancedSearchGateTest {

    /**
     * The screen's activity rule, mirrored here because the screen derives it inline.
     *
     * A target, a Ki ceiling or a name fragment — and whitespace does not count as a fragment, because a
     * space typed into the field is not a search.
     */
    private fun isActive(target: String?, kiAtMost: Double?, fragment: String): Boolean =
        target != null || kiAtMost != null || fragment.trim().isNotEmpty()

    @Test
    fun `no filter means no query`() {
        isActive(null, null, "") shouldBe false
        // Whitespace is not a fragment. This is the case that would make the screen run a full scan
        // because someone tapped the field and pressed space.
        isActive(null, null, "   ") shouldBe false
    }

    @Test
    fun `any one filter activates the query`() {
        isActive("alpha-2-delta-1", null, "") shouldBe true
        isActive(null, 1000.0, "") shouldBe true
        isActive(null, null, "methyl") shouldBe true
    }

    /**
     * A Ki ceiling of zero is still a ceiling.
     *
     * `0.0` is falsy to nobody in Kotlin, but the screen passes it as a nullable `Double` and a
     * truthiness-style check would drop it. Asserted because "Ki <= 0" is a legitimate — if useless —
     * query, and treating it as absent is the kind of coercion a nullable number invites.
     */
    @Test
    fun `a zero ceiling is an active filter`() {
        isActive(null, 0.0, "") shouldBe true
    }

    /**
     * The hub's own gate: a gated entry is absent for a casual reader and present otherwise.
     *
     * This mirrors `ToolEntry.gated` and the filter the hub applies. It is worth a test because the failure
     * is silent in the other direction: a gated entry shown to everyone would put a Ki table in front of a
     * reader who asked for less detail, and nothing would error.
     */
    @Test
    fun `a gated entry is only listed for a reader who asked for detail`() {
        val entries = listOf(
            Entry("body load", gated = false),
            Entry("advanced search", gated = true),
        )

        fun visible(showAdvanced: Boolean) = entries.filter { showAdvanced || !it.gated }.map { it.title }

        visible(false) shouldBe listOf("body load")
        visible(true) shouldBe listOf("body load", "advanced search")
    }

    /**
     * The route carries no arguments, which is what makes it deep-linkable.
     *
     * Upstream's `DeepLink.swift` lists `.advancedSearch` among the routes it can open, and a route with
     * required arguments could not be. `key()` is the only thing that has to be stable.
     */
    @Test
    fun `the route has a stable key and no arguments`() {
        PushRoute.AdvancedSearch.key() shouldBe "advanced-search"
    }

    private data class Entry(val title: String, val gated: Boolean)
}
