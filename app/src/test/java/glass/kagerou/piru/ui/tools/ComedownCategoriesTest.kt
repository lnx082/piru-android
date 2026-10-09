package glass.kagerou.piru.ui.tools

import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.engine.SubstanceCatalog
import glass.kagerou.piru.model.DoseRange
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.Substance
import glass.kagerou.piru.model.SubstanceCategory
import glass.kagerou.piru.model.SubstanceRoute
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.Date
import java.util.UUID
import org.junit.jupiter.api.Test

/**
 * Which recovery classes a set of doses calls for.
 *
 * ## Why one rule rather than two
 * The list and the dedup were private to the comedown guide. The session's recovery section needs **the same rule** —
 * a session scoping its rows by a second implementation would eventually disagree with the guide it links to: the
 * guide would show a class the session had not counted, or the session would offer a row the guide has nothing to say
 * about. So this covers both scopes.
 *
 * ## The two scopes, and the one difference between them
 * Both use the same list and the same first-appearance dedup; they differ only in window and in **order**. `recent` is
 * newest-first because the first class seen is the most recent one; `of` is oldest-first because a session is read
 * forwards and the class it opened with is the one a reader expects first. That difference is why they are two
 * functions rather than one with a flag.
 */
class ComedownCategoriesTest {

    private fun substance(name: String, category: SubstanceCategory) = Substance(
        name = name,
        category = category,
        defaultRoute = RouteOfAdministration.ORAL,
        routes = listOf(
            SubstanceRoute(
                route = RouteOfAdministration.ORAL,
                unit = "mg",
                doses = DoseRange(common = 1.0..10.0),
            ),
        ),
        halfLifeMinutes = 300.0,
    )

    private fun catalog() = catalogOf(
        substance("Caffeine", SubstanceCategory.STIMULANT),
        substance("MDMA", SubstanceCategory.EMPATHOGEN),
        substance("LSD", SubstanceCategory.PSYCHEDELIC),
        substance("Ketamine", SubstanceCategory.DISSOCIATIVE),
        // Not one of the guide's eight, so it must never contribute a row.
        substance("Multivitamin", SubstanceCategory.SUPPLEMENT),
        substance("Creatine", SubstanceCategory.NOOTROPIC),
    )

    private fun catalogOf(vararg substances: Substance): SubstanceCatalog = object : SubstanceCatalog {
        private val byName = substances.associateBy { it.name.lowercase() }
        override fun lookup(name: String): Substance? = byName[name.lowercase()]
        override fun substanceUID(name: String): String? = null
        override fun esters(parentUID: String): List<glass.kagerou.piru.engine.EsterRecord> = emptyList()
        override fun productDuration(productName: String): glass.kagerou.piru.model.DurationProfile? = null
    }

    private fun entry(
        substance: String,
        minutesAgo: Long,
        now: Instant = Instant.now(),
    ) = DoseEntryEntity(
        id = UUID.randomUUID(),
        timestamp = Date(now.minusSeconds(minutesAgo * 60L).toEpochMilli()),
        substance = substance,
        amount = 100.0,
        unit = "mg",
        route = RouteOfAdministration.ORAL,
    )

    // MARK: - The list itself

    /**
     * The guide covers exactly eight classes, and the list is the contract with it.
     *
     * Asserted as a set rather than as an order here, because the order is a display decision with its own case below.
     * What matters for correctness is that these eight and no others are guided: a ninth added by accident would give
     * a session a recovery row the guide cannot render.
     */
    @Test
    fun `the guide covers eight classes`() {
        ComedownCategories.GUIDED.size shouldBe 8
        ComedownCategories.GUIDED.toSet() shouldBe setOf(
            SubstanceCategory.STIMULANT,
            SubstanceCategory.EMPATHOGEN,
            SubstanceCategory.PSYCHEDELIC,
            SubstanceCategory.DISSOCIATIVE,
            SubstanceCategory.OPIOID,
            SubstanceCategory.BENZODIAZEPINE,
            SubstanceCategory.DEPRESSANT,
            SubstanceCategory.CANNABINOID,
        )
    }

    /** The window is 48 hours, which is what `recent` is scoped to. */
    @Test
    fun `the recent window is forty-eight hours`() {
        ComedownCategories.RECENT_WINDOW.toHours() shouldBe 48
    }

    // MARK: - The session's scope

    /**
     * A session's classes come back in the order they were **first logged**.
     *
     * The one deliberate difference from `recent`: a session is read forwards, so the class it opened with is what a
     * reader expects first.
     */
    @Test
    fun `the session's classes are in first-logged order`() {
        val now = Instant.now()
        val entries = listOf(
            entry("MDMA", minutesAgo = 120, now = now),
            entry("Caffeine", minutesAgo = 60, now = now),
            entry("MDMA", minutesAgo = 30, now = now),
        )
        ComedownCategories.of(entries, catalog()) shouldContainExactly listOf(
            SubstanceCategory.EMPATHOGEN,
            SubstanceCategory.STIMULANT,
        )
    }

    /**
     * A class logged twice contributes **one** row.
     *
     * The dedup, and it is what stops a session of four MDMA doses drawing four identical recovery rows.
     */
    @Test
    fun `a repeated class contributes one row`() {
        val now = Instant.now()
        val entries = (0 until 4).map { entry("MDMA", minutesAgo = (it * 30).toLong(), now = now) }
        ComedownCategories.of(entries, catalog()) shouldContainExactly listOf(SubstanceCategory.EMPATHOGEN)
    }

    /**
     * A class the guide does not cover contributes nothing.
     *
     * A session of a supplement and a nootropic gets no recovery card at all, rather than a heading over rows the guide
     * cannot render. Asserted with **both** unguided categories, so an implementation that filtered on one by accident
     * would still fail.
     */
    @Test
    fun `an unguided class contributes nothing`() {
        val now = Instant.now()
        val entries = listOf(
            entry("Multivitamin", minutesAgo = 10, now = now),
            entry("Creatine", minutesAgo = 5, now = now),
        )
        ComedownCategories.of(entries, catalog()) shouldBe emptyList()
    }

    /**
     * A name the catalogue does not know contributes nothing.
     *
     * The honest answer for "what class is this" when the reference has never heard of the substance is nothing rather
     * than a guess — the same rule the journal's category filter follows, and the case an implementation that defaulted
     * a category would get wrong while looking like it merely worked.
     */
    @Test
    fun `an unresolvable name contributes nothing`() {
        val now = Instant.now()
        ComedownCategories.of(
            listOf(entry("Something Unlisted", minutesAgo = 1, now = now)),
            catalog(),
        ) shouldBe emptyList()
    }

    /** A session with no doses has no classes, so the card is not drawn. */
    @Test
    fun `an empty session has no classes`() {
        ComedownCategories.of(emptyList(), catalog()) shouldBe emptyList()
    }

    // MARK: - The guide's own scope

    /**
     * `recent` is newest-first, which is the opposite of `of`.
     *
     * The difference the two functions exist for. Asserted directly, because a single function with a flag would make
     * this order an argument at the call site rather than a decision in one place.
     */
    @Test
    fun `the recent list is newest first`() {
        val now = Instant.now()
        val entries = listOf(
            entry("Caffeine", minutesAgo = 120, now = now),
            entry("MDMA", minutesAgo = 60, now = now),
        )
        ComedownCategories.recent(entries, now, catalog()) shouldContainExactly listOf(
            SubstanceCategory.EMPATHOGEN,
            SubstanceCategory.STIMULANT,
        )
        // And the session scope over the same rows reads the other way.
        ComedownCategories.of(entries, catalog()) shouldContainExactly listOf(
            SubstanceCategory.STIMULANT,
            SubstanceCategory.EMPATHOGEN,
        )
    }

    /**
     * `recent` drops anything older than the window.
     *
     * The cutoff is applied in the function rather than in a query, which is what makes it testable. Both sides of the
     * boundary are asserted: 47 hours is in, 49 is out.
     */
    @Test
    fun `the recent list respects its window`() {
        val now = Instant.now()
        val inside = entry("Caffeine", minutesAgo = 47 * 60, now = now)
        val outside = entry("MDMA", minutesAgo = 49 * 60, now = now)
        ComedownCategories.recent(listOf(inside, outside), now, catalog()) shouldContainExactly
            listOf(SubstanceCategory.STIMULANT)
    }

    /**
     * The session scope has **no** window.
     *
     * A session older than 48 hours is still a session, and its recovery rows are still the classes it involved.
     * Sharing the window would silently empty the section for any session read back a few days later — which is
     * exactly when someone reads one.
     */
    @Test
    fun `the session scope has no window`() {
        val now = Instant.now()
        val old = entry("MDMA", minutesAgo = 7 * 24 * 60, now = now)
        ComedownCategories.of(listOf(old), catalog()) shouldContainExactly listOf(SubstanceCategory.EMPATHOGEN)
        // And `recent` over the same row finds nothing.
        ComedownCategories.recent(listOf(old), now, catalog()) shouldBe emptyList()
    }
}
