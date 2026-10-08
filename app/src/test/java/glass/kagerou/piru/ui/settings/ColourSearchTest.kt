package glass.kagerou.piru.ui.settings

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The colour list's search filter, and the two empty states it distinguishes.
 *
 * ## Why the empty-state rule is the part worth testing
 * The list is the user's own log, so an empty result has two completely different causes: they have logged nothing,
 * or their search matched nothing. Telling the second user "you have not logged anything" is a **lie about their
 * own data** — and it is exactly what an `if (list.isEmpty())` renders, which is why the decision is a function.
 *
 * The filter itself is simple on purpose: substring, case-insensitive, on the name the user typed. The screen is a
 * few dozen rows and the search narrows a list the user is scanning, which is a different job from the library's
 * alias-resolving search.
 */
class ColourSearchTest {

    private val log = listOf("MDMA", "Ketamine", "2C-B", "Caffeine", "Alprazolam")

    /**
     * A blank query shows everything.
     *
     * Including a whitespace-only one: `"   "` is not a search, and treating it as one that matches nothing would
     * make the whole list vanish the moment a user tapped space.
     */
    @Test
    fun `a blank query shows everything`() {
        ColourSearch.filter(log, "") shouldContainExactly log
        ColourSearch.filter(log, "   ") shouldContainExactly log
        ColourSearch.filter(log, "\t") shouldContainExactly log
    }

    /** A query is trimmed, so a paste from elsewhere finds the row. */
    @Test
    fun `the query is trimmed`() {
        ColourSearch.filter(log, "  MDMA  ") shouldContainExactly listOf("MDMA")
        ColourSearch.filter(log, "\tKetamine\n") shouldContainExactly listOf("Ketamine")
    }

    /**
     * The match is case-insensitive, which is the whole point for names like these.
     *
     * A log holds "MDMA" and "2C-B" — names a user is as likely to type in lower case as upper — and a
     * case-sensitive filter would find neither.
     */
    @Test
    fun `the match ignores case`() {
        ColourSearch.filter(log, "mdma") shouldContainExactly listOf("MDMA")
        ColourSearch.filter(log, "MDMA") shouldContainExactly listOf("MDMA")
        ColourSearch.filter(log, "2c-b") shouldContainExactly listOf("2C-B")
        ColourSearch.filter(log, "KETAMINE") shouldContainExactly listOf("Ketamine")
    }

    /**
     * The match is a substring, not a prefix.
     *
     * A user looking for alprazolam may type "praz", and one looking for a name they half-remember types the half
     * they know.
     */
    @Test
    fun `the match is a substring`() {
        ColourSearch.filter(log, "praz") shouldContainExactly listOf("Alprazolam")
        ColourSearch.filter(log, "amin") shouldContainExactly listOf("Ketamine")
    }

    /** Several rows can match, and they keep the list's own order — which is alphabetical. */
    @Test
    fun `several matches keep the list order`() {
        val names = listOf("Alprazolam", "Amphetamine", "Caffeine", "Clonazepam")
        ColourSearch.filter(names, "a") shouldContainExactly listOf("Alprazolam", "Amphetamine", "Caffeine", "Clonazepam")
        ColourSearch.filter(names, "am") shouldContainExactly listOf("Alprazolam", "Amphetamine", "Clonazepam")
    }

    /** No match is an empty list rather than the whole list or a crash. */
    @Test
    fun `no match returns nothing`() {
        ColourSearch.filter(log, "zzz") shouldContainExactly emptyList()
        ColourSearch.filter(emptyList(), "mdma") shouldContainExactly emptyList()
    }

    // MARK: - The two empty states

    /**
     * The case the function exists for.
     *
     * The user has logged substances and their search matched none of them: the message has to be about the
     * search, not about the log.
     */
    @Test
    fun `a search with no match is a search result`() {
        ColourSearch.emptyStateIsSearchResult(allCount = 5, query = "zzz") shouldBe true
        ColourSearch.emptyStateIsSearchResult(allCount = 1, query = "mdma") shouldBe true
    }

    /** An empty log is not a search result, whatever the query says. */
    @Test
    fun `an empty log is not a search result`() {
        ColourSearch.emptyStateIsSearchResult(allCount = 0, query = "zzz") shouldBe false
        ColourSearch.emptyStateIsSearchResult(allCount = 0, query = "") shouldBe false
    }

    /**
     * A blank query over a non-empty log is not a search result either.
     *
     * That state is unreachable in practice — a blank query shows every row — but the function is asked, and
     * `false` is the answer that would not mislead if it ever were reachable.
     */
    @Test
    fun `a blank query is not a search result`() {
        ColourSearch.emptyStateIsSearchResult(allCount = 5, query = "") shouldBe false
        ColourSearch.emptyStateIsSearchResult(allCount = 5, query = "   ") shouldBe false
        ColourSearch.emptyStateIsSearchResult(allCount = 5, query = "\t") shouldBe false
    }
}
