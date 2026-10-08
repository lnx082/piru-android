package glass.kagerou.piru.ui.journal

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * What else was going on around one dose.
 *
 * ## Why the two flags exist and are tested together
 * `overlaps` and `judged` answer different questions and only one of them can be false for two different reasons:
 *
 * - `judged = false` means **the app cannot say** — this substance has no duration, so there is no window. The card
 *   must not print "nothing overlapped" when it means "this cannot be determined", because those are different
 *   statements about the user's own data and only one of them is true.
 * - `overlaps = false` with `judged = true` means the app **looked and found nothing** in the window. The
 *   neighbour is still listed, because its being there at all is a fact.
 *
 * A single `overlaps` flag cannot carry both, which is why collapsing them is the mistake this file prevents.
 */
class EntryContextTest {

    private fun candidate(
        rowId: Long,
        substance: String = "Ketamine",
        offsetMinutes: Long,
        sameSession: Boolean = true,
    ) = EntryContext.Candidate(
        rowId = rowId,
        substance = substance,
        offsetMinutes = offsetMinutes,
        sameSession = sameSession,
    )

    // MARK: - The window

    /**
     * A neighbour inside half the window on either side overlaps.
     *
     * The symmetric reading: a six-hour dose is present three hours either way, so an earlier and a later dose are
     * treated alike. Asserted from both sides at the boundary, because an off-by-one here changes which rows the
     * card marks without changing whether it draws.
     */
    @Test
    fun `the window is half on each side`() {
        val result = EntryContext.neighbours(
            entrySubstance = "MDMA",
            windowMinutes = 360.0,
            candidates = listOf(
                candidate(1, "Ketamine", offsetMinutes = -180),
                candidate(2, "Ketamine", offsetMinutes = 180),
                candidate(3, "Ketamine", offsetMinutes = -181),
                candidate(4, "Ketamine", offsetMinutes = 181),
            ),
        )
        result.judged shouldBe true
        result.neighbours.first { it.rowId == 1L }.overlaps shouldBe true
        result.neighbours.first { it.rowId == 2L }.overlaps shouldBe true
        result.neighbours.first { it.rowId == 3L }.overlaps shouldBe false
        result.neighbours.first { it.rowId == 4L }.overlaps shouldBe false
    }

    /**
     * No duration means nothing is judged, and **every** neighbour comes back unmarked.
     *
     * The case the separate flag exists for. A fallback window would be inventing a time course for a substance the
     * catalogue has no profile for.
     */
    @Test
    fun `without a window nothing is judged`() {
        val result = EntryContext.neighbours(
            entrySubstance = "Something Unmeasured",
            windowMinutes = null,
            candidates = listOf(candidate(1, offsetMinutes = -5), candidate(2, offsetMinutes = 600)),
        )
        result.judged shouldBe false
        result.neighbours.all { !it.overlaps } shouldBe true
        // And they are still listed — a dose logged five minutes earlier is a fact whatever its profile.
        result.neighbours.size shouldBe 2
    }

    /** A zero or negative window is treated as no window rather than as an empty one. */
    @Test
    fun `a degenerate window is no window`() {
        for (window in listOf(0.0, -10.0)) {
            val result = EntryContext.neighbours("X", window, listOf(candidate(1, offsetMinutes = 0)))
            result.judged shouldBe false
        }
    }

    // MARK: - Order and membership

    /** Nearest first, whatever the direction, with the row id breaking a tie. */
    @Test
    fun `neighbours are nearest first`() {
        val result = EntryContext.neighbours(
            entrySubstance = "MDMA",
            windowMinutes = 600.0,
            candidates = listOf(
                candidate(1, "A", offsetMinutes = 300),
                candidate(2, "B", offsetMinutes = -30),
                candidate(3, "C", offsetMinutes = 90),
                candidate(4, "D", offsetMinutes = -30),
            ),
        )
        // -30 and -30 tie on distance, so the lower row id comes first.
        result.neighbours.map { it.rowId } shouldContainExactly listOf(2L, 4L, 3L, 1L)
    }

    /**
     * The dose being read is not its own neighbour.
     *
     * A caller assembles candidates from a day's log, which contains the entry. Matching on the substance **and**
     * a zero offset is what identifies it: another dose of the same substance later is a real neighbour.
     */
    @Test
    fun `the entry is not its own neighbour`() {
        val result = EntryContext.neighbours(
            entrySubstance = "MDMA",
            windowMinutes = 360.0,
            candidates = listOf(
                candidate(1, "MDMA", offsetMinutes = 0),
                candidate(2, "MDMA", offsetMinutes = 120),
            ),
        )
        result.neighbours.map { it.rowId } shouldContainExactly listOf(2L)
    }

    /** A zero-offset dose of a *different* substance is kept — that is a co-use, not the entry itself. */
    @Test
    fun `a simultaneous different substance is kept`() {
        val result = EntryContext.neighbours(
            entrySubstance = "MDMA",
            windowMinutes = 360.0,
            candidates = listOf(candidate(1, "Ketamine", offsetMinutes = 0)),
        )
        result.neighbours.map { it.rowId } shouldContainExactly listOf(1L)
        result.neighbours.single().overlaps shouldBe true
    }

    /** The list is capped, because past a handful it is a second copy of the day's log. */
    @Test
    fun `the list is capped`() {
        val many = (1..20).map { candidate(it.toLong(), "S$it", offsetMinutes = it.toLong()) }
        val result = EntryContext.neighbours("MDMA", 10_000.0, many)
        result.neighbours.size shouldBe EntryContext.MAXIMUM
        // And the cap keeps the nearest, which is the point of capping after sorting rather than before.
        result.neighbours.first().offsetMinutes shouldBe 1L
    }

    /** The same-session flag survives, because the card labels those rows. */
    @Test
    fun `same-session membership is carried`() {
        val result = EntryContext.neighbours(
            entrySubstance = "MDMA",
            windowMinutes = 360.0,
            candidates = listOf(
                candidate(1, "A", offsetMinutes = -60, sameSession = true),
                candidate(2, "B", offsetMinutes = 60, sameSession = false),
            ),
        )
        result.neighbours.first { it.rowId == 1L }.sameSession shouldBe true
        result.neighbours.first { it.rowId == 2L }.sameSession shouldBe false
    }

    // MARK: - The card's gate

    /** Nothing nearby means nothing to draw — an empty "Context" card is furniture. */
    @Test
    fun `an empty list is not worth a card`() {
        EntryContext.worthShowing(EntryContext.Result(emptyList(), judged = true)) shouldBe false
        // And a list that could not be judged but has rows still draws.
        EntryContext.worthShowing(
            EntryContext.Result(
                listOf(
                    EntryContext.Neighbour(1, "X", 30, overlaps = false, sameSession = true),
                ),
                judged = false,
            ),
        ) shouldBe true
    }

    // MARK: - The offset wording

    /**
     * The offset reads as a duration with its direction.
     *
     * The sign is the part a reader gets wrong when scanning, so it is a word rather than a minus.
     */
    @Test
    fun `the offset reads as a duration with a direction`() {
        EntryContext.describeOffset(0) shouldBe "same time"
        EntryContext.describeOffset(30) shouldBe "30m after"
        EntryContext.describeOffset(-30) shouldBe "30m before"
        EntryContext.describeOffset(60) shouldBe "1h after"
        EntryContext.describeOffset(-135) shouldBe "2h 15m before"
        EntryContext.describeOffset(120) shouldBe "2h after"
    }
}
