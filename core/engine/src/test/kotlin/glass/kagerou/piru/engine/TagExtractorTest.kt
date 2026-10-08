package glass.kagerou.piru.engine

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Hashtags out of a note, against upstream's own cases.
 *
 * Translated from `PiruTests/TagExtractorTests.swift`, which pins the same behaviour. The
 * expectations are upstream's, so a failure here means the port is wrong rather than the assertion.
 */
class TagExtractorTest {

    @Test
    fun `extracts a single hashtag`() {
        TagExtractor.extractTags("Feeling okay #headache") shouldBe listOf("headache")
    }

    @Test
    fun `extracts several in the order they were written`() {
        // Order is the user's, not sorted: a note's tags are shown where they were put.
        TagExtractor.extractTags("#headache #sleep #nausea") shouldBe
            listOf("headache", "sleep", "nausea")
    }

    @Test
    fun `lowercases`() {
        TagExtractor.extractTags("#Headache #SLEEP") shouldBe listOf("headache", "sleep")
    }

    @Test
    fun `deduplicates case-insensitively`() {
        // A tag is a key. Two spellings of one tag would be two tags in every list that groups by
        // them, which is how a filter stops matching what the user typed.
        TagExtractor.extractTags("#sleep #Sleep #SLEEP") shouldBe listOf("sleep")
    }

    @Test
    fun `a hash with no word after it is not a tag`() {
        // `\w+` requires at least one word character, so a bare `#` and a `#-` are left alone.
        TagExtractor.extractTags("a bare # and a #- dash").shouldBeEmpty()
    }

    @Test
    fun `no note, no tags`() {
        TagExtractor.extractTags(null).shouldBeEmpty()
        TagExtractor.extractTags("").shouldBeEmpty()
        TagExtractor.extractTags("   ").shouldBeEmpty()
    }

    @Test
    fun `a note without a hash has no tags`() {
        TagExtractor.extractTags("Slept badly, headache all morning.").shouldBeEmpty()
    }

    @Test
    fun `a hash inside a word still reads as a tag`() {
        // `\w` does not need a word boundary before the `#`, which is upstream's behaviour: the
        // pattern is what it is, and inventing a stricter rule here would extract fewer tags than
        // iOS does from the same note.
        TagExtractor.extractTags("see#later") shouldBe listOf("later")
    }

    @Test
    fun `the suggestions are upstream's list, in its order`() {
        TagExtractor.suggestions shouldBe listOf(
            "headache", "anxiety", "sleep", "pain", "nausea",
            "mood", "energy", "focus", "relax", "appetite",
        )
        // Every suggestion is itself a well-formed tag, so a chip the user taps round-trips.
        for (suggestion in TagExtractor.suggestions) {
            TagExtractor.extractTags("#$suggestion") shouldBe listOf(suggestion)
        }
    }
}
