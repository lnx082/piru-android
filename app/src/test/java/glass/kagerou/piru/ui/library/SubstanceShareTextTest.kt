package glass.kagerou.piru.ui.library

import glass.kagerou.piru.model.DoseRange
import glass.kagerou.piru.model.DurationProfile
import glass.kagerou.piru.model.DurationRange
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.Substance
import glass.kagerou.piru.model.SubstanceCategory
import glass.kagerou.piru.model.SubstanceRoute
import glass.kagerou.piru.model.SubjectiveEffect
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * The text a shared substance card carries.
 *
 * ## The claim the levels make
 * `MINIMAL` is the identity and the dose ladder — what somebody who received this needs in order to act on it.
 * `STANDARD` adds the effects and the duration, which is what makes the card useful rather than merely correct.
 * `RICH` adds pharmacology.
 *
 * So the tests are mostly about **what each level leaves out**: a MINIMAL share that quietly carried the receptor
 * table would put pharmacology in a group chat, and a level whose output equals the one below it is a level that
 * promises something it does not deliver.
 *
 * ## The one string that leaves the app
 * Every amount is formatted with `Locale.ROOT`. This string is read in **another** application, where a decimal comma
 * is a thousands separator — so a German-locale `2,5 mg` would be read as two hundred and five milligrams. That is
 * asserted directly, because it is the only formatting decision here whose failure is dangerous rather than untidy.
 */
class SubstanceShareTextTest {

    private fun substance(
        name: String = "Ketamine",
        halfLifeMinutes: Double? = 180.0,
        effects: List<String> = listOf("Dissociation", "Analgesia"),
        category: SubstanceCategory = SubstanceCategory.DISSOCIATIVE,
    ) = Substance(
        name = name,
        category = category,
        defaultRoute = RouteOfAdministration.INSUFFLATION,
        routes = emptyList(),
        effects = effects,
        subjectiveEffects = effects.map { SubjectiveEffect(name = it, description = "") },
        halfLifeMinutes = halfLifeMinutes,
    )

    private fun route(
        unit: String = "mg",
        doses: DoseRange = DoseRange(
            threshold = 10.0,
            light = 15.0..30.0,
            common = 30.0..75.0,
            strong = 75.0..150.0,
            heavy = 150.0,
        ),
        duration: DurationProfile? = null,
    ) = SubstanceRoute(
        route = RouteOfAdministration.INSUFFLATION,
        unit = unit,
        doses = doses,
        duration = duration,
    )

    // MARK: - The identity is always there

    /** Every level names the substance and its class: a share that omits what it is cannot be acted on. */
    @Test
    fun `every level names the substance and its class`() {
        for (level in ShareDetailLevel.entries) {
            val text = SubstanceShareText.build(substance(), route(), RouteOfAdministration.INSUFFLATION, level)
            text shouldContain "Ketamine"
            text shouldContain "Dissociative"
        }
    }

    // MARK: - The ladder

    /** The ladder prints each tier with its unit, and the heavy tier as a threshold rather than a range. */
    @Test
    fun `the ladder prints every tier`() {
        val text = SubstanceShareText.build(
            substance(), route(), RouteOfAdministration.INSUFFLATION, ShareDetailLevel.MINIMAL,
        )
        println("SHAREPROBE minimal=\n" + text)
        text shouldContain "Threshold 10 mg"
        text shouldContain "Light 15–30 mg"
        text shouldContain "Common 30–75 mg"
        text shouldContain "Strong 75–150 mg"
        // `150+`, not `150–150`: a heavy tier is a floor.
        text shouldContain "Heavy 150+ mg"
        // And the route the ladder applies to, so a receiver knows how it is taken. The enum's own word lower-cased:
        // `insufflation`, not the verb form I first assumed.
        text shouldContain "insufflation"
    }

    /**
     * A tier the catalogue does not carry is **omitted**, not printed as a zero.
     *
     * The same rule the duration card's em dash follows: an absent phase is not a zero, and `Light 0–0 mg` would be a
     * claim about a dose nobody wrote down.
     */
    @Test
    fun `a missing tier is omitted rather than zeroed`() {
        val sparse = DoseRange(common = 100.0..200.0)
        val text = SubstanceShareText.build(
            substance(), route(doses = sparse), RouteOfAdministration.INSUFFLATION, ShareDetailLevel.MINIMAL,
        )
        text shouldContain "Common 100–200 mg"
        text shouldNotContain "Threshold"
        text shouldNotContain "Light"
        text shouldNotContain "Strong"
        text shouldNotContain "Heavy"
        // A bare `0` would also match inside `100`, which is how the first version of this passed a string that
        // contained no zero tier at all. The claim is the specific absent forms.
        text shouldNotContain "Threshold 0"
        text shouldNotContain "Light 0"
        text shouldNotContain "Strong 0"
        text shouldNotContain "Heavy 0"
    }

    /** With no ladder at all the dose line is omitted, rather than printed empty. */
    @Test
    fun `an empty ladder is omitted`() {
        val text = SubstanceShareText.build(
            substance(),
            route(doses = DoseRange()),
            RouteOfAdministration.INSUFFLATION,
            ShareDetailLevel.MINIMAL,
        )
        text shouldNotContain "Dosage"
    }

    /** With no route selected the ladder is omitted, which is the honest result. */
    @Test
    fun `no route means no ladder`() {
        val text = SubstanceShareText.build(substance(), null, null, ShareDetailLevel.MINIMAL)
        text shouldNotContain "Dosage"
        text shouldContain "Ketamine"
    }

    // MARK: - The levels differ

    /** `MINIMAL` stops at the ladder: no duration, no effects, no half-life. */
    @Test
    fun `minimal stops at the ladder`() {
        val text = SubstanceShareText.build(
            substance(),
            route(duration = DurationProfile(total = DurationRange(60.0, 120.0))),
            RouteOfAdministration.INSUFFLATION,
            ShareDetailLevel.MINIMAL,
        )
        text shouldNotContain "Duration"
        text shouldNotContain "Reported effects"
        text shouldNotContain "Half-life"
    }

    /** `STANDARD` adds the duration, the effects and the half-life. */
    @Test
    fun `standard adds duration effects and half-life`() {
        val text = SubstanceShareText.build(
            substance(),
            route(duration = DurationProfile(total = DurationRange(60.0, 120.0))),
            RouteOfAdministration.INSUFFLATION,
            ShareDetailLevel.STANDARD,
        )
        println("SHAREPROBE standard=\n" + text)
        text shouldContain "Duration: about"
        text shouldContain "Reported effects: Dissociation, Analgesia"
        text shouldContain "Half-life: about 3 h"
        // And it still carries everything MINIMAL did.
        text shouldContain "Common 30–75 mg"
    }

    /** `RICH` is not merely equal to `STANDARD`: it says so where its extra content would be. */
    @Test
    fun `rich differs from standard`() {
        val standard = SubstanceShareText.build(
            substance(), route(), RouteOfAdministration.INSUFFLATION, ShareDetailLevel.STANDARD,
        )
        val rich = SubstanceShareText.build(
            substance(), route(), RouteOfAdministration.INSUFFLATION, ShareDetailLevel.RICH,
        )
        println("SHAREPROBE rich tail=" + rich.takeLast(60).trim())
        (rich == standard) shouldBe false
        rich shouldContain "Open Piru for the full pharmacology"
        // A level that silently equalled the one below it would promise something it does not deliver.
        rich shouldContain "Common 30–75 mg"
    }

    /** At most six effects, because more turns a share into a wall. */
    @Test
    fun `at most six effects`() {
        val many = (1..12).map { "Effect $it" }
        val text = SubstanceShareText.build(
            substance(effects = many), route(), RouteOfAdministration.INSUFFLATION, ShareDetailLevel.STANDARD,
        )
        text shouldContain "Effect 1"
        text shouldContain "Effect 6"
        text shouldNotContain "Effect 7"
    }

    // MARK: - The formatting that leaves the app

    /**
     * Amounts use a **point** whatever the device's locale is.
     *
     * The only formatting decision here whose failure is dangerous: the string is read in another app, where `2,5 mg`
     * with a decimal comma is two hundred and five milligrams.
     */
    @Test
    fun `amounts use a point in every locale`() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            SubstanceShareText.formatAmount(2.5) shouldBe "2.5"
            SubstanceShareText.formatAmount(100.0) shouldBe "100"
            SubstanceShareText.formatAmount(0.25) shouldBe "0.25"
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    /** Minutes read as a reader reads them: `45 min`, `2 h`, `2 h 30`. */
    @Test
    fun `minutes read as a duration`() {
        SubstanceShareText.formatMinutes(45.0) shouldBe "45 min"
        SubstanceShareText.formatMinutes(120.0) shouldBe "2 h"
        SubstanceShareText.formatMinutes(150.0) shouldBe "2 h 30"
        SubstanceShareText.formatMinutes(59.0) shouldBe "59 min"
    }

    /** The share ends with exactly one newline, so the receiving app does not get a trailing blank line. */
    @Test
    fun `the share ends with one newline`() {
        for (level in ShareDetailLevel.entries) {
            val text = SubstanceShareText.build(substance(), route(), RouteOfAdministration.INSUFFLATION, level)
            text.endsWith("\n") shouldBe true
            text.endsWith("\n\n") shouldBe false
        }
    }
}
