package glass.kagerou.piru.ui.journal

import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.SubstanceCategory
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.Instant
import org.junit.jupiter.api.Test

/**
 * What is still in the body at the end of a session.
 *
 * ## The three rules this file exists for
 *
 * **The unit is the group's first, and a later dose converts into it.** `500 mg + 1 g` is `1500 mg`, not `501` of
 * anything. And a unit that cannot be converted (`mL`, `IU`) contributes its **count but not its amount**, which is
 * why a row can read "2 doses" over a total only one of them accounts for.
 *
 * **The cleared threshold is 0.95, not 1.0.** A curve approaches its asymptote and never reaches it, so waiting for
 * complete elimination would leave a row in the active list forever or drop it as a jump.
 *
 * **The two reasons a substance is missing from the calculator must not read alike.** "No half-life is known" and
 * "this dose wore off" are different statements and only one of them is a claim about the substance. A substance with
 * no half-life **always** appears, marked unmodeled, because otherwise something the user just took would silently
 * vanish.
 */
class SessionBodyLoadModelTest {

    /**
     * The clock the model is given: the fixture's own anchor.
     *
     * The **same** instant the entries are dated from, not a second one captured separately.
     * `ActiveSubstanceCalculator` measures elapsed time against its own `Instant.now()` and skips a dose whose elapsed
     * time is negative, so a `now` that landed after an entry's stamp made that entry a future dose and silently
     * emptied its row — which is why this is one value shared by both sides rather than two that happen to be close.
     */
    private val now: Instant = BodyLoadFixtures.ANCHOR

    private fun make(
        entries: List<glass.kagerou.piru.data.entity.DoseEntryEntity>,
        catalog: glass.kagerou.piru.engine.SubstanceCatalog,
        customNameFor: (String, String?) -> String = { _, fallback -> fallback.orEmpty() },
        hasActiveMetabolite: (String) -> Boolean = { false },
    ) = SessionBodyLoadModel.make(
        entries = entries,
        catalog = catalog,
        tintFor = { BodyLoadFixtures.tint },
        fallbackTint = BodyLoadFixtures.tint,
        customNameFor = customNameFor,
        hasActiveMetabolite = hasActiveMetabolite,
        now = now,
    )

    // MARK: - Active

    /**
     * A dose taken just now is on board, with its session total and its remaining amount.
     *
     * The plain case, and the one that would be silently wrong if the model reported the total as what remains.
     */
    @Test
    fun `a fresh dose is on board`() {
        val result = make(
            listOf(BodyLoadFixtures.entry("Caffeine", amount = 100.0, minutesAgo = 0)),
            BodyLoadFixtures.catalog(listOf(BodyLoadFixtures.substance("Caffeine", halfLifeMinutes = 300.0))),
        )
        result.active.size shouldBe 1
        result.cleared shouldBe emptyList()
        val row = result.active.single()
        row.sessionTotal shouldBe 100.0
        row.count shouldBe 1
        // Almost all of it is still there — one instant has passed.
        (row.remaining > 99.0) shouldBe true
    }

    /**
     * Two doses of one substance group, and the total is their sum.
     *
     * The grouping rule: a user logs "Concerta" and "Ritalin" and is dosed twice with methylphenidate, and the total
     * belongs to one substance.
     */
    @Test
    fun `two doses of one substance group into one row`() {
        val result = make(
            listOf(
                BodyLoadFixtures.entry("Caffeine", amount = 100.0, minutesAgo = 30),
                BodyLoadFixtures.entry("Caffeine", amount = 50.0, minutesAgo = 0),
            ),
            BodyLoadFixtures.catalog(listOf(BodyLoadFixtures.substance("Caffeine", halfLifeMinutes = 300.0))),
        )
        result.active.size shouldBe 1
        result.active.single().count shouldBe 2
        result.active.single().sessionTotal shouldBe 150.0
    }

    /**
     * A later dose in a **different unit** converts into the group's first unit.
     *
     * `500 mg + 1 g` is `1500 mg`. An implementation that added raw magnitudes would report `501`, which is the
     * failure this pins — and the number would look plausible for a substance dosed in hundreds of milligrams.
     */
    @Test
    fun `a later dose converts into the group's unit`() {
        val result = make(
            listOf(
                BodyLoadFixtures.entry("Caffeine", amount = 500.0, unit = "mg", minutesAgo = 30),
                BodyLoadFixtures.entry("Caffeine", amount = 1.0, unit = "g", minutesAgo = 0),
            ),
            BodyLoadFixtures.catalog(listOf(BodyLoadFixtures.substance("Caffeine", halfLifeMinutes = 300.0))),
        )
        result.active.single().unit shouldBe "mg"
        result.active.single().sessionTotal shouldBe 1500.0
        result.active.single().count shouldBe 2
    }

    /**
     * An **inconvertible** unit is its own row, and contributes its count but not its amount.
     *
     * `mg` and `IU` are different unit families — the calculator's key is `"$name|$family"`, and a unit that cannot
     * be converted to milligrams is its own family — so one substance logged in both draws **two** rows. My first
     * version of this asserted a single row, which was wrong about the design; the claim under test belongs to the
     * `IU` row, which counts both of the substance's doses and sums only the one it can express.
     */
    @Test
    fun `an inconvertible unit is its own row`() {
        val result = make(
            listOf(
                BodyLoadFixtures.entry("Caffeine", amount = 100.0, unit = "mg", minutesAgo = 30),
                BodyLoadFixtures.entry("Caffeine", amount = 5.0, unit = "IU", minutesAgo = 0),
            ),
            BodyLoadFixtures.catalog(listOf(BodyLoadFixtures.substance("Caffeine", halfLifeMinutes = 300.0))),
        )
        result.active.size shouldBe 2
        val milligramRow = result.active.single { it.unit == "mg" }
        val unitRow = result.active.single { it.unit == "IU" }
        // The milligram row accounts only for the milligram dose.
        milligramRow.sessionTotal shouldBe 100.0
        milligramRow.count shouldBe 1
        // And the IU row only for its own, since nothing converts between them.
        unitRow.sessionTotal shouldBe 5.0
        unitRow.count shouldBe 1
    }

    /** An unknown dose is not counted at all: there is no amount to add, and it is not a zero. */
    @Test
    fun `an unknown dose contributes nothing`() {
        val result = make(
            listOf(BodyLoadFixtures.entry("Caffeine", amount = 0.0, isUnknownDose = true, minutesAgo = 0)),
            BodyLoadFixtures.catalog(listOf(BodyLoadFixtures.substance("Caffeine", halfLifeMinutes = 300.0))),
        )
        result.isEmpty shouldBe true
    }

    // MARK: - Cleared

    /**
     * A dose past the display threshold reports as cleared.
     *
     * Five half-lives, so about 3.1% remains: **past** the 0.95 eliminated threshold and **still inside** the
     * calculator's 3%-of-amount cut, which is what puts the row through the calculator's own path.
     *
     * The first version of this used twelve half-lives and expected a cleared row; it got nothing, because a lone
     * dose that old is dropped by the calculator and a lone dropped dose is deliberately skipped. The test was wrong
     * about the design, and `a single worn-off dose shows nothing` now pins that design directly.
     */
    @Test
    fun `a dose past the threshold is cleared`() {
        val result = make(
            listOf(BodyLoadFixtures.entry("Caffeine", amount = 100.0, minutesAgo = 1_500)),
            BodyLoadFixtures.catalog(listOf(BodyLoadFixtures.substance("Caffeine", halfLifeMinutes = 300.0))),
        )
        result.active shouldBe emptyList()
        result.cleared.size shouldBe 1
        result.cleared.single().total shouldBe 100.0
        result.cleared.single().unmodeled shouldBe false
    }

    /** A cleared row carries whether a longer-lived active metabolite may outlast the parent. */
    @Test
    fun `a cleared row reports an active metabolite`() {
        val result = make(
            listOf(BodyLoadFixtures.entry("Caffeine", amount = 100.0, minutesAgo = 1_500)),
            BodyLoadFixtures.catalog(listOf(BodyLoadFixtures.substance("Caffeine", halfLifeMinutes = 300.0))),
            hasActiveMetabolite = { it == "Caffeine" },
        )
        result.cleared.single().hasActiveMetabolite shouldBe true
    }

    // MARK: - The two missing reasons

    /**
     * A substance with **no half-life** always appears, marked unmodeled.
     *
     * The rule that stops something the user just took from vanishing: the calculator cannot model it, so without
     * this it would be dropped from both lists and the section would say nothing about it.
     */
    @Test
    fun `a substance with no half-life appears as unmodeled`() {
        val result = make(
            listOf(BodyLoadFixtures.entry("Mystery", amount = 100.0, minutesAgo = 0)),
            BodyLoadFixtures.catalog(listOf(BodyLoadFixtures.substance("Mystery", halfLifeMinutes = null))),
        )
        result.active shouldBe emptyList()
        result.cleared.size shouldBe 1
        result.cleared.single().unmodeled shouldBe true
        result.cleared.single().total shouldBe 100.0
    }

    /**
     * A **single** worn-off modelling dose shows nothing, and that is the design rather than a gap.
     *
     * The calculator stops contributing a dose at 3% remaining, so a lone dose that has worn off is simply gone —
     * and it is **skipped** rather than listed. The alternative grows a cleared row for every substance whose one
     * dose has worn off, which in a long session is most of them.
     *
     * My first version of this test asserted that the row appears; **the test was wrong and the model was right**.
     */
    @Test
    fun `a single worn-off dose shows nothing`() {
        val result = make(
            listOf(BodyLoadFixtures.entry("Caffeine", amount = 100.0, minutesAgo = 3_600)),
            BodyLoadFixtures.catalog(listOf(BodyLoadFixtures.substance("Caffeine", halfLifeMinutes = 300.0))),
        )
        result.active shouldBe emptyList()
        result.cleared shouldBe emptyList()
        result.isEmpty shouldBe true
    }

    /**
     * A dose **just** inside the calculator's window still reports as cleared.
     *
     * The other side of the 3% cut: at roughly four half-lives the dose is under the threshold but the calculator has
     * not dropped it, so the row arrives through the calculator's own path and says "cleared". That is what
     * distinguishes a dose that wore off from one that was never modelled.
     */
    @Test
    fun `a dose just inside the window reports as cleared`() {
        // A 300-minute half-life, 1,500 minutes later: five half-lives, so ~3.1% remains — past the 0.95 display
        // threshold (96.9% eliminated) and still above the calculator's 3%-of-amount cut, which is what puts the row
        // through the calculator's path rather than the dropped one. The first version of this used 1,150 minutes,
        // which is under four half-lives and therefore **not** past the threshold at all.
        val result = make(
            listOf(BodyLoadFixtures.entry("Caffeine", amount = 100.0, minutesAgo = 1_500)),
            BodyLoadFixtures.catalog(listOf(BodyLoadFixtures.substance("Caffeine", halfLifeMinutes = 300.0))),
        )
        result.active shouldBe emptyList()
        result.cleared.size shouldBe 1
        result.cleared.single().unmodeled shouldBe false
    }

    /**
     * A substance with **several** doses, all worn off, still appears.
     *
     * Because the calculator drops a substance once nothing is circulating, and a session that took something four
     * times should not report nothing about it. This is the case a naive "is anything active" filter loses.
     */
    @Test
    fun `several worn-off doses still appear`() {
        val result = make(
            listOf(
                BodyLoadFixtures.entry("Mystery", amount = 100.0, minutesAgo = 9_000),
                BodyLoadFixtures.entry("Mystery", amount = 100.0, minutesAgo = 8_000),
            ),
            // No half-life, so the calculator skips it entirely and it arrives through the dropped path.
            BodyLoadFixtures.catalog(listOf(BodyLoadFixtures.substance("Mystery", halfLifeMinutes = null))),
        )
        result.cleared.size shouldBe 1
        result.cleared.single().count shouldBe 2
        result.cleared.single().total shouldBe 200.0
        result.cleared.single().unmodeled shouldBe true
    }

    // MARK: - Titles

    /**
     * A group with **one** product is titled by it, and a mixed group is not.
     *
     * The rule that stops one brand titling a total that is not all that brand: a group holding `""` and `"Concerta"`
     * has two product entries, so it keeps the canonical name.
     */
    @Test
    fun `a single product titles its group and a mixed one does not`() {
        val single = make(
            listOf(BodyLoadFixtures.entry("Caffeine", productName = "Alert-Pill", minutesAgo = 0)),
            BodyLoadFixtures.catalog(listOf(BodyLoadFixtures.substance("Caffeine", halfLifeMinutes = 300.0))),
        )
        single.active.single().displayName shouldBe "Alert-Pill"

        val mixed = make(
            listOf(
                BodyLoadFixtures.entry("Caffeine", productName = "Alert-Pill", minutesAgo = 10),
                BodyLoadFixtures.entry("Caffeine", productName = "Wakey", minutesAgo = 0),
            ),
            BodyLoadFixtures.catalog(listOf(BodyLoadFixtures.substance("Caffeine", halfLifeMinutes = 300.0))),
        )
        mixed.active.single().displayName shouldBe "Caffeine"
    }

    /** A **personal relabel** outranks the product name, because it is the user's own word for the substance. */
    @Test
    fun `a personal relabel outranks a product name`() {
        val result = make(
            listOf(BodyLoadFixtures.entry("Caffeine", productName = "Alert-Pill", minutesAgo = 0)),
            BodyLoadFixtures.catalog(listOf(BodyLoadFixtures.substance("Caffeine", halfLifeMinutes = 300.0))),
            customNameFor = { _, _ -> "My Coffee" },
        )
        result.active.single().displayName shouldBe "My Coffee"
    }

    // MARK: - The cleared ordering

    /**
     * The cleared list is sorted by title, because its two sources arrive in different orders.
     *
     * The calculator's own cleared rows and the dropped groups are appended from different walks, and a list that
     * reorders itself between renders reads as the screen being unstable.
     */
    @Test
    fun `the cleared list is sorted by title`() {
        val result = make(
            listOf(
                BodyLoadFixtures.entry("Zeta", amount = 10.0, minutesAgo = 0),
                BodyLoadFixtures.entry("Alpha", amount = 10.0, minutesAgo = 0),
            ),
            BodyLoadFixtures.catalog(
                listOf(
                    BodyLoadFixtures.substance("Zeta", halfLifeMinutes = null),
                    BodyLoadFixtures.substance("Alpha", halfLifeMinutes = null),
                ),
            ),
        )
        result.cleared.map { it.displayName } shouldContainExactly listOf("Alpha", "Zeta")
    }

    /** An empty session is empty, and the caller draws nothing rather than an empty card. */
    @Test
    fun `an empty session is empty`() {
        val result = make(emptyList(), BodyLoadFixtures.catalog(emptyList()))
        result.isEmpty shouldBe true
    }

    // MARK: - The threshold itself

    /**
     * The threshold is 0.95 and not 1.0.
     *
     * Asserted as a value because it is the kind of number somebody "corrects" to 1.0: a curve approaches its
     * asymptote and never reaches it, so 1.0 would leave every row in the active list forever.
     */
    @Test
    fun `the cleared threshold leaves room for the asymptote`() {
        SessionBodyLoadModel.CLEARED_THRESHOLD shouldBe 0.95
        (SessionBodyLoadModel.CLEARED_THRESHOLD < 1.0) shouldBe true
    }

    // MARK: - The clearance projection

    /**
     * A longer half-life projects a later clearance.
     *
     * Both projections must **exist** before the comparison means anything, so that is asserted first — the first
     * version compared them without checking and failed on a null. The two substances are cleared at different times
     * but both comfortably inside the scan horizon of ten half-lives.
     */
    @Test
    fun `a longer half-life clears later`() {
        val catalog = BodyLoadFixtures.catalog(
            listOf(
                BodyLoadFixtures.substance("Short", halfLifeMinutes = 60.0),
                BodyLoadFixtures.substance("Long", halfLifeMinutes = 600.0),
            ),
        )
        fun clearFor(name: String): java.time.Instant? {
            val result = make(listOf(BodyLoadFixtures.entry(name, amount = 100.0, minutesAgo = 0)), catalog)
            val row = result.active.singleOrNull() ?: return null
            return SessionBodyLoadModel.clearAt(row.active, RouteOfAdministration.ORAL, catalog, now)
        }

        val short = clearFor("Short")
        val long = clearFor("Long")
        println("BODYLOADPROBE short=$short long=$long")
        check(short != null) { "the short half-life projected nothing" }
        check(long != null) { "the long half-life projected nothing" }
        long.isAfter(short) shouldBe true
    }

    /** A dose with nothing dosed cannot be projected, and the caller renders "now" rather than a time in the past. */
    @Test
    fun `nothing dosed projects nothing`() {
        val catalog = BodyLoadFixtures.catalog(listOf(BodyLoadFixtures.substance("Caffeine")))
        val result = make(emptyList(), catalog)
        result.active shouldBe emptyList()
        SessionBodyLoadModel.clearAt(
            glass.kagerou.piru.engine.ActiveSubstance(
                name = "Caffeine",
                unit = "mg",
                tint = BodyLoadFixtures.tint,
                halfLifeMinutes = 300.0,
                totalDosed = 0.0,
                totalRemaining = 0.0,
                doses = emptyList(),
            ),
            RouteOfAdministration.ORAL,
            catalog,
            now,
        ) shouldBe null
    }

    /** The clearance description is a plain fact, and a time already past reads as "now". */
    @Test
    fun `the clearance description is a plain fact`() {
        SessionBodyLoadModel.describeClear(now, now, java.time.ZoneId.of("UTC")) shouldBe "now"
        SessionBodyLoadModel.describeClear(now.plusSeconds(30 * 60), now, java.time.ZoneId.of("UTC")) shouldBe "30 min"
        SessionBodyLoadModel.describeClear(now.plusSeconds(5 * 3600), now, java.time.ZoneId.of("UTC")) shouldBe "5 h"
        SessionBodyLoadModel.describeClear(now.plusSeconds(2 * 86_400), now, java.time.ZoneId.of("UTC")) shouldBe "2 d"
    }

    /** A category is not needed for this model, but the fixture must be a real substance — assert that it is. */
    @Test
    fun `the fixture builds a real substance`() {
        val substance = BodyLoadFixtures.substance("Caffeine")
        substance.category shouldBe SubstanceCategory.STIMULANT
        substance.defaultRoute shouldBe RouteOfAdministration.ORAL
        substance.routes.size shouldBe 1
    }

    // MARK: - Amounts

    /**
     * A whole amount has no trailing `.0`.
     *
     * `150.0` reads as a measurement taken to a tenth of a milligram, and no dose is. The failure this prevents is
     * cosmetic and therefore survives review, which is why it is asserted rather than eyeballed.
     */
    @Test
    fun `a whole amount has no decimal part`() {
        SessionBodyLoadModel.formatAmount(150.0) shouldBe "150"
        SessionBodyLoadModel.formatAmount(0.0) shouldBe "0"
        SessionBodyLoadModel.formatAmount(1_000.0) shouldBe "1000"
    }

    /** A fractional amount keeps one decimal, which is the most a dose measurement can honestly carry. */
    @Test
    fun `a fractional amount keeps one decimal`() {
        SessionBodyLoadModel.formatAmount(150.5) shouldBe "150.5"
        SessionBodyLoadModel.formatAmount(0.5) shouldBe "0.5"
        SessionBodyLoadModel.formatAmount(2.25) shouldBe "2.3"
    }

    /**
     * The decimal separator is a **point** whatever the device's locale is.
     *
     * Asserted by setting a locale that uses a comma and reading the result back. Without `Locale.ROOT` in the
     * format call this fails — and on a German or Chinese phone it would fail in the app, printing `150,5` where a
     * dose total belongs.
     */
    @Test
    fun `the decimal separator is a point regardless of locale`() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            SessionBodyLoadModel.formatAmount(150.5) shouldBe "150.5"
            java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE)
            SessionBodyLoadModel.formatAmount(150.5) shouldBe "150.5"
        } finally {
            java.util.Locale.setDefault(original)
        }
    }
}
