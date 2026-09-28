package glass.kagerou.piru.engine

import glass.kagerou.piru.model.ConfidenceTier
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The metabolic-modulation readout: which enzymes a `metabolism.enzyme` cell is
 * allowed to claim, and what a tag-derived edge says.
 *
 * Ported from the `MajorEnzymes` and `templateNote` cases in
 * `PiruTests/MetabolicModulationTests.swift`. Every cell below is verbatim from
 * the shipped catalog's free-text `metabolism.enzyme` column, because that column
 * is the whole reason the parsing is clause-based rather than a `contains`.
 */
class EnzymeInteractionTest {

    // MARK: - Enzyme parsing

    @Test
    fun `all finds every curated token in a cell`() {
        Enzyme.all("CYP2D6 (major)") shouldBe setOf(Enzyme.CYP2D6)
        Enzyme.all("CYP3A4, CYP1A2 (major); CYP2D6 (minor)") shouldBe
            setOf(Enzyme.CYP3A4, Enzyme.CYP1A2, Enzyme.CYP2D6)
        Enzyme.all("COMT") shouldBe emptySet()
    }

    @Test
    fun `major drops a whole clause its own prose calls minor`() {
        // The real cells. Scanning the cell whole promoted every enzyme in it, so
        // a pathway the source wrote down as negligible fanned modulator callouts
        // out as though it were dominant.
        Enzyme.major("CYP3A4, CYP1A2 (major); CYP2D6 (minor)") shouldBe
            setOf(Enzyme.CYP3A4, Enzyme.CYP1A2)
        Enzyme.major(
            "CYP2D6 (dominant; CYP2C8/CYP2E1/CYP2A6 minor — CYP1A2 contribution is negligible)",
        ) shouldBe setOf(Enzyme.CYP2D6)
        Enzyme.major("CYP2B6 / CYP1A2 / CYP3A4 (minor N-demethylation)").shouldBeEmpty()
        Enzyme.major("minor CYP-mediated oxidation").shouldBeEmpty()
    }

    @Test
    fun `a cell with no qualifier keeps every enzyme in it`() {
        Enzyme.major("CYP2D6 / CYP3A4 (N-demethylation)") shouldBe setOf(Enzyme.CYP2D6, Enzyme.CYP3A4)
        Enzyme.major("CYP2D6") shouldBe setOf(Enzyme.CYP2D6)
    }

    @Test
    fun `an unparseable cell reads as silence rather than a guess`() {
        Enzyme.major("UGT2B7") shouldBe emptySet()
        Enzyme.major("") shouldBe emptySet()
    }

    @Test
    fun `a quantified pathway below the threshold is ignored, an unquantified one counts`() {
        // Unquantified rows are listed by the curators precisely because they
        // matter, so they count; quantified minor pathways below 15% are noise.
        MetabolicModulation.majorEnzymes(
            listOf(MetabolismHit(id = 1L, enzyme = "CYP3A4", fractionOfClearancePct = 70.0)),
        ) shouldBe setOf(Enzyme.CYP3A4)

        MetabolicModulation.majorEnzymes(
            listOf(MetabolismHit(id = 1L, enzyme = "CYP2C19", fractionOfClearancePct = 10.0)),
        ).shouldBeEmpty()

        MetabolicModulation.majorEnzymes(
            listOf(MetabolismHit(id = 1L, enzyme = "CYP2D6", fractionOfClearancePct = null)),
        ) shouldBe setOf(Enzyme.CYP2D6)
        // 15% exactly is major.
        MetabolicModulation.majorEnzymes(
            listOf(MetabolismHit(id = 1L, enzyme = "CYP2C9", fractionOfClearancePct = 15.0)),
        ) shouldBe setOf(Enzyme.CYP2C9)
    }

    // MARK: - Direction and strength

    @Test
    fun `inhibition raises levels and induction lowers them`() {
        ModulationDirection.INHIBITS.raisesLevels shouldBe true
        ModulationDirection.INDUCES.raisesLevels shouldBe false
        ModulationDirection.fromToken("induces") shouldBe ModulationDirection.INDUCES
        ModulationDirection.fromToken("anything else") shouldBe null
    }

    @Test
    fun `strength is declared in ascending order, so the natural ordering is the trust ordering`() {
        ModulationStrength.WEAK shouldBe ModulationStrength.fromToken("weak")
        (ModulationStrength.WEAK < ModulationStrength.MODERATE) shouldBe true
        (ModulationStrength.MODERATE < ModulationStrength.STRONG) shouldBe true
    }

    // MARK: - The generated sentence

    @Test
    fun `a tag-derived note names the perpetrator, the enzyme and the consequence`() {
        // Inhibiting a substrate's clearing enzyme raises its levels…
        MetabolicModulation.templateNote(
            perpetrator = "Ritonavir",
            enzyme = Enzyme.CYP3A4,
            direction = ModulationDirection.INHIBITS,
            strength = "strong",
            victimType = "substrate",
        ) shouldBe "Ritonavir strongly inhibits CYP3A4, raising the levels of drugs cleared by it."

        // …inducing it lowers them…
        MetabolicModulation.templateNote(
            perpetrator = "Carbamazepine",
            enzyme = Enzyme.CYP3A4,
            direction = ModulationDirection.INDUCES,
            strength = "moderate",
            victimType = "substrate",
        ) shouldBe "Carbamazepine moderately induces CYP3A4, lowering the levels of drugs cleared by it."

        // …and a prodrug whose activation pathway is blocked reads as its own case
        // rather than as "raising the levels of the drug", which is the opposite
        // of what happens.
        MetabolicModulation.templateNote(
            perpetrator = "Bupropion",
            enzyme = Enzyme.CYP2D6,
            direction = ModulationDirection.INHIBITS,
            strength = "strong",
            victimType = "prodrug",
        ) shouldBe
            "Bupropion strongly inhibits CYP2D6, " +
            "blocking the activation pathway for prodrugs that depend on it."
    }

    @Test
    fun `a row with no strength omits the adverb rather than inventing one`() {
        // 126 of the 285 shipped tag rows carry no strength: the pipeline had none
        // to record, and "strongly" would be a claim nobody made.
        MetabolicModulation.templateNote(
            perpetrator = "Diphenhydramine",
            enzyme = Enzyme.CYP2D6,
            direction = ModulationDirection.INHIBITS,
            strength = null,
            victimType = "substrate",
        ) shouldBe "Diphenhydramine inhibits CYP2D6, raising the levels of drugs cleared by it."
    }

    @Test
    fun `an inducer pointed at a prodrug lowers the active species instead`() {
        // The prodrug clause is gated on inhibition: inducing the enzyme that
        // activates a prodrug raises the active species, but the sentence's
        // substrate clause ("lowering the levels") is still the true one for the
        // prodrug as administered.
        MetabolicModulation.templateNote(
            perpetrator = "Rifampicin",
            enzyme = Enzyme.CYP2D6,
            direction = ModulationDirection.INDUCES,
            strength = "strong",
            victimType = "prodrug",
        ) shouldBe "Rifampicin strongly induces CYP2D6, lowering the levels of drugs cleared by it."
    }

    // MARK: - Context flags and self-edges never reach the checker

    @Test
    fun `a context modulator with no matchers can never fire on a logged pair`() {
        val effects = MetabolicModulation.checkerEffects(
            among = listOf("Grapefruit", "Midazolam"),
            modulators = listOf(
                EnzymeModulator(
                    id = "grapefruit",
                    origin = ModulatorOrigin.CONTEXT,
                    enzyme = Enzyme.CYP3A4,
                    direction = ModulationDirection.INHIBITS,
                    strength = ModulationStrength.MODERATE,
                    confidence = ConfidenceTier.MEDIUM,
                    matchers = emptyList(),
                    displayName = "Grapefruit",
                    userNote = "Grapefruit juice inhibits CYP3A4.",
                ),
            ),
            tagIndex = emptyMap(),
            majorEnzymesFor = { if (it == "Midazolam") setOf(Enzyme.CYP3A4) else emptySet() },
            canonical = { it.lowercase() },
        )
        // The checker reasons about substance combinations only; a lifestyle flag
        // is surfaced on the substance's own card, not as a pair.
        effects.shouldBeEmpty()
    }

    @Test
    fun `a self-edge is excluded even when both substances are present`() {
        // MDMA genuinely inhibits the enzyme that clears it, but the checker
        // reasons about *combinations*; the self-edge belongs on MDMA's own card.
        val effects = MetabolicModulation.checkerEffects(
            among = listOf("MDMA", "Sertraline"),
            modulators = listOf(
                EnzymeModulator(
                    id = "mdma-cyp2d6",
                    origin = ModulatorOrigin.SELF,
                    enzyme = Enzyme.CYP2D6,
                    direction = ModulationDirection.INHIBITS,
                    strength = ModulationStrength.STRONG,
                    confidence = ConfidenceTier.HIGH,
                    matchers = listOf("mdma"),
                    displayName = "MDMA",
                    userNote = "MDMA inhibits the enzyme that clears it.",
                ),
            ),
            tagIndex = emptyMap(),
            majorEnzymesFor = { setOf(Enzyme.CYP2D6) },
            canonical = { it.lowercase() },
        )
        effects.shouldBeEmpty()
    }

    @Test
    fun `a curated modulator beats the tag layer on the same pair`() {
        // Both layers would fire for bupropion + codeine; the curated note is the
        // richer copy and claims the dedup key first.
        val effects = MetabolicModulation.checkerEffects(
            among = listOf("Bupropion", "Codeine"),
            modulators = listOf(
                EnzymeModulator(
                    id = "bupropion",
                    origin = ModulatorOrigin.SUBSTANCE,
                    enzyme = Enzyme.CYP2D6,
                    direction = ModulationDirection.INHIBITS,
                    strength = ModulationStrength.STRONG,
                    confidence = ConfidenceTier.HIGH,
                    matchers = listOf("bupropion", "wellbutrin"),
                    displayName = "Bupropion",
                    userNote = "Curated note.",
                ),
            ),
            tagIndex = mapOf(
                "bupropion" to mapOf(
                    "codeine" to TagEnzymeInteraction(
                        perpetratorName = "Bupropion",
                        victimName = "Codeine",
                        enzyme = "CYP2D6",
                        direction = "inhibits",
                        strength = "strong",
                        victimType = "substrate",
                    ),
                ),
            ),
            majorEnzymesFor = { if (it == "Codeine") setOf(Enzyme.CYP2D6) else emptySet() },
            canonical = { it.lowercase() },
        )

        effects.size shouldBe 1
        effects.first().userNote shouldBe "Curated note."
        effects.first().modulatorID shouldBe "bupropion"
    }

    @Test
    fun `an unrecognized enzyme on a tag row is dropped and does not re-offer`() {
        val effects = MetabolicModulation.checkerEffects(
            among = listOf("Diphenhydramine", "Codeine"),
            modulators = emptyList(),
            tagIndex = mapOf(
                "diphenhydramine" to mapOf(
                    "codeine" to TagEnzymeInteraction(
                        perpetratorName = "Diphenhydramine",
                        victimName = "Codeine",
                        enzyme = "CYP2E1",
                        direction = "inhibits",
                        strength = null,
                        victimType = "substrate",
                    ),
                ),
            ),
            majorEnzymesFor = { emptySet() },
            canonical = { it.lowercase() },
        )
        effects.shouldBeEmpty()
    }
}
