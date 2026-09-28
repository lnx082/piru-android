package glass.kagerou.piru.engine.report

import glass.kagerou.piru.engine.ActiveSubstanceState
import glass.kagerou.piru.engine.InteractionSeverity
import glass.kagerou.piru.engine.TEST_TINT
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import org.junit.jupiter.api.Test

/**
 * The small, language-neutral pieces both renderers share — the rating ladders,
 * the phase ladder, and the plain-text helpers. These are pinned exactly (not
 * just "contains") because the reports are the portable interchange form: a
 * rating or a phase must spell the same on every device regardless of the screen
 * language, so a drift here is a silent format break, not a cosmetic one.
 */
class ReportSupportTest {

    // MARK: - Rating scales

    @Test
    fun `ShulginScale maps the full ladder`() {
        ShulginScale.glyph(0) shouldBe "±"
        ShulginScale.glyph(1) shouldBe "+"
        ShulginScale.glyph(2) shouldBe "++"
        ShulginScale.glyph(3) shouldBe "+++"
        ShulginScale.glyph(4) shouldBe "++++"
    }

    @Test
    fun `ShulginScale rejects values outside the ladder`() {
        ShulginScale.glyph(-1).shouldBeNull()
        ShulginScale.glyph(5).shouldBeNull()
    }

    @Test
    fun `WorkedScale maps its three words`() {
        WorkedScale.exportWord(-1) shouldBe "less than usual"
        WorkedScale.exportWord(0) shouldBe "about right"
        WorkedScale.exportWord(1) shouldBe "more than usual"
    }

    @Test
    fun `WorkedScale rejects values outside the scale`() {
        WorkedScale.exportWord(-2).shouldBeNull()
        WorkedScale.exportWord(2).shouldBeNull()
    }

    // MARK: - Phase

    @Test
    fun `phases carry the portable English names`() {
        SessionPhase.ONSET.englishName shouldBe "Onset"
        SessionPhase.COMEUP.englishName shouldBe "Come-up"
        SessionPhase.PEAK.englishName shouldBe "Peak"
        SessionPhase.OFFSET.englishName shouldBe "Offset"
        SessionPhase.AFTER.englishName shouldBe "Afterglow"
    }

    @Test
    fun `phase selects the right window at each boundary`() {
        // onsetEnd 15, comeupEnd 30, peakEnd 120, offsetEnd 180.
        val state = state(onsetEnd = 15.0, comeupEnd = 30.0, peakEnd = 120.0, offsetEnd = 180.0)
        phase(state, 0.0) shouldBe SessionPhase.ONSET
        phase(state, 15.0) shouldBe SessionPhase.ONSET // the boundary itself is still onset
        phase(state, 15.5) shouldBe SessionPhase.COMEUP
        phase(state, 30.0) shouldBe SessionPhase.COMEUP
        phase(state, 120.0) shouldBe SessionPhase.PEAK
        phase(state, 180.0) shouldBe SessionPhase.OFFSET
        phase(state, 180.5) shouldBe SessionPhase.AFTER
    }

    @Test
    fun `interaction severities carry the on-screen glyphs`() {
        InteractionSeverity.CAUTION.exportSymbol shouldBe "⚠️"
        InteractionSeverity.UNSAFE.exportSymbol shouldBe "🔶"
        InteractionSeverity.DANGEROUS.exportSymbol shouldBe "🛑"
    }

    // MARK: - Plain-text helpers

    @Test
    fun `durationHM formats hours minutes and zero`() {
        durationHM(45.0 * 60) shouldBe "45m"
        durationHM(3.0 * 3600) shouldBe "3h"
        durationHM(2.0 * 3600 + 8 * 60) shouldBe "2h 8m"
        durationHM(0.0) shouldBe "0m"
    }

    @Test
    fun `durationHM clamps negatives and sub-minute values`() {
        durationHM(-100.0) shouldBe "0m"
        durationHM(59.9) shouldBe "0m"
    }

    @Test
    fun `tPlus formats a T-plus offset with zero-padded minutes`() {
        val start = Instant.ofEpochSecond(0)
        tPlus(start, start) shouldBe "T+0:00"
        tPlus(start, start.plusSeconds(60)) shouldBe "T+0:01"
        tPlus(start, start.plusSeconds(80 * 60)) shouldBe "T+1:20"
    }

    @Test
    fun `tPlus prefixes the U+2212 minus for a pre-session note`() {
        // The format string is "T%s+%d:%02d", so the minus rides before the
        // literal plus — faithful to upstream's `String(format: "T%@+…")`.
        val start = Instant.ofEpochSecond(0)
        tPlus(start, start.minusSeconds(60)) shouldBe "T−+0:01"
    }

    @Test
    fun `tPlus keeps a note under one minute before the session at zero`() {
        // A note 10 s before the first dose has no whole minute of lead, so it
        // reads as the session start rather than gaining a spurious minus.
        val start = Instant.ofEpochSecond(0)
        tPlus(start, start.minusSeconds(10)) shouldBe "T+0:00"
    }

    @Test
    fun `signed prefixes plus and the U+2212 minus`() {
        signed(2) shouldBe "+2"
        signed(0) shouldBe "0"
        signed(-1) shouldBe "−1"
        signed(-5) shouldBe "−5"
    }

    @Test
    fun `structureLine joins every present piece in order`() {
        structureLine(
            shulgin = 2, mood = 2, energy = -1, social = 3, worked = 0, heartRate = 84,
        ) shouldBe "++ · about right · mood +2 · energy −1 · social +3 · ♥ 84"
    }

    @Test
    fun `structureLine is empty when nothing is captured`() {
        structureLine(
            shulgin = null, mood = null, energy = null, social = null, worked = null, heartRate = null,
        ) shouldBe ""
    }

    @Test
    fun `structureLine drops absent scales and keeps the heart rate`() {
        structureLine(shulgin = null, mood = null, energy = null, heartRate = 84) shouldBe "♥ 84"
        // A null Shulgin simply omits the glyph; a non-null one is not replaced.
        structureLine(shulgin = null, mood = 1, energy = null, heartRate = null) shouldBe "mood +1"
    }

    @Test
    fun `moodEnergyCell shows an en dash for a side not captured`() {
        moodEnergyCell(mood = 2, energy = 0) shouldBe "+2 / 0"
        moodEnergyCell(mood = null, energy = -1) shouldBe "– / −1"
        moodEnergyCell(mood = null, energy = null) shouldBe ""
        moodEnergyCell(mood = null, energy = null, heartRate = 84) shouldBe "♥ 84"
        moodEnergyCell(mood = 2, energy = 0, heartRate = 84) shouldBe "+2 / 0 · ♥ 84"
    }

    @Test
    fun `cell escapes pipes and folds line breaks into one row`() {
        cell("plain") shouldBe "plain"
        cell("a|b") shouldBe "a\\|b"
        cell("  padded  ") shouldBe "padded"
        cell("line1\nline2") shouldBe "line1<br>line2"
        cell("a\r\nb") shouldBe "a<br>b"
        cell("para1\n\npara2") shouldBe "para1<br>para2"
        cell("") shouldBe ""
    }

    // MARK: - Fixture

    private fun state(
        onsetEnd: Double,
        comeupEnd: Double,
        peakEnd: Double,
        offsetEnd: Double,
    ): ActiveSubstanceState = ActiveSubstanceState(
        substanceName = "Test",
        tint = TEST_TINT,
        doseTimestamp = Instant.ofEpochSecond(0),
        amount = 100.0,
        unit = "mg",
        route = "oral",
        onsetEndMinutes = onsetEnd,
        comeupEndMinutes = comeupEnd,
        peakEndMinutes = peakEnd,
        offsetEndMinutes = offsetEnd,
        afterglowEndMinutes = null,
        totalMinutes = offsetEnd,
    )
}
