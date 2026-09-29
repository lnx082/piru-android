package glass.kagerou.piru.ui.insights

import glass.kagerou.piru.engine.DrugClass
import glass.kagerou.piru.engine.InteractionSeverity
import glass.kagerou.piru.model.P3Color
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Instant
import org.junit.jupiter.api.Test

/**
 * The thresholds the PDF report leads with.
 *
 * Ported from `PiruTests/SummaryFindingsTests.swift`, assertion for assertion. These
 * are the tests worth having: a finding is a claim about someone's opioid load or the
 * direction of their use, and every one of them is a number on a line that a clinician
 * reads without re-deriving it. The thresholds sit on round values that a real report
 * lands near (50 and 90 MME; a 14-day window), so being off by one comparison is the
 * failure this file is here to catch.
 *
 * The unit label is injected rather than read from resources, which is what lets this
 * run as a plain JVM test with no Android runtime 鈥?see `UnitLabel`.
 */
class SummaryFindingsTest {

    private val unitLabel = UnitLabel { _ -> "mg" }

    private fun substance(
        name: String,
        currency: ExposureCurrency = ExposureCurrency.MILLIGRAMS,
    ) = SummarySubstance(name = name, displayName = name, tint = TINT, currency = currency)

    private fun report(
        substances: List<SummarySubstance> = emptyList(),
        totalDays: Int = 0,
        daysUsed: Int = 0,
        longestBreakDays: Int = 0,
        currentBreakDays: Int = 0,
        exposure: List<ExposureStat> = emptyList(),
        escalation: List<EscalationStat> = emptyList(),
        overlaps: List<OverlapStat> = emptyList(),
    ) = JournalSummary(
        substances = substances,
        holidays = HolidayStats(totalDays, daysUsed, longestBreakDays, currentBreakDays),
        exposure = exposure,
        escalation = escalation,
        overlaps = overlaps,
    )

    private fun exposure(
        index: Int,
        currency: ExposureCurrency,
        peakDay: Double,
        dailyMean: Double = peakDay,
    ) = ExposureStat(
        substanceIndex = index,
        currency = currency,
        total = peakDay * 10,
        peakDay = peakDay,
        dailyMean = dailyMean,
        cumulative = emptyList(),
    )

    // MARK: - Empty report

    @Test
    fun `an empty report yields no findings`() {
        findings(report(), emptyList(), unitLabel).shouldBeEmpty()
    }

    // MARK: - Escalation

    @Test
    fun `a rising substance produces an escalation finding`() {
        val summary = report(
            substances = listOf(substance("Oxycodone", ExposureCurrency.MME)),
            totalDays = 90,
            daysUsed = 45,
            escalation = listOf(
                EscalationStat(
                    substanceIndex = 0,
                    direction = EscalationDirection.RISING,
                    change = 0.4,
                    earlyMedian = 10.0,
                    lateMedian = 14.0,
                ),
            ),
        )

        val escalation = findings(summary, emptyList(), unitLabel).first { it.kind == Finding.Kind.ESCALATION }
        escalation.severity shouldBe Finding.Severity.WARNING
        escalation.summary shouldContain "Oxycodone"
        escalation.summary shouldContain "+40%"
        escalation.summary shouldContain "10"
        escalation.summary shouldContain "14"
    }

    /**
     * A rise the aggregation called a rise but that moved less than the reportable
     * fraction is dropped: the direction is a sign and this is a magnitude, and a 5%
     * rise is not worth a line in a clinical summary.
     */
    @Test
    fun `a rise under the reportable fraction is dropped`() {
        val summary = report(
            substances = listOf(substance("Oxycodone", ExposureCurrency.MME)),
            totalDays = 90,
            daysUsed = 45,
            escalation = listOf(
                EscalationStat(0, EscalationDirection.RISING, change = 0.05, earlyMedian = 10.0, lateMedian = 10.5),
            ),
        )

        findings(summary, emptyList(), unitLabel)
            .none { it.kind == Finding.Kind.ESCALATION } shouldBe true
    }

    @Test
    fun `a falling substance produces no escalation finding`() {
        val summary = report(
            substances = listOf(substance("Oxycodone", ExposureCurrency.MME)),
            escalation = listOf(
                EscalationStat(0, EscalationDirection.FALLING, change = -0.5, earlyMedian = 20.0, lateMedian = 10.0),
            ),
        )

        findings(summary, emptyList(), unitLabel)
            .none { it.kind == Finding.Kind.ESCALATION } shouldBe true
    }

    // MARK: - Opioid load

    @Test
    fun `peak MME at or above 50 produces an opioid load finding`() {
        val summary = report(
            substances = listOf(substance("Oxycodone", ExposureCurrency.MME)),
            totalDays = 30,
            daysUsed = 15,
            exposure = listOf(exposure(0, ExposureCurrency.MME, peakDay = 75.0)),
        )

        val opioid = findings(summary, emptyList(), unitLabel).first { it.kind == Finding.Kind.OPIOID_LOAD }
        opioid.severity shouldBe Finding.Severity.WARNING
        opioid.summary shouldContain "75 MME/day"
        opioid.summary shouldContain "50 MME"
    }

    @Test
    fun `peak MME at or above 90 references the CDC 90 band`() {
        val summary = report(
            substances = listOf(substance("Oxycodone", ExposureCurrency.MME)),
            totalDays = 30,
            daysUsed = 15,
            exposure = listOf(exposure(0, ExposureCurrency.MME, peakDay = 100.0)),
        )

        findings(summary, emptyList(), unitLabel)
            .first { it.kind == Finding.Kind.OPIOID_LOAD }
            .summary shouldContain "90 MME"
    }

    /**
     * The boundary itself. 50 is the reference, so 50 fires 鈥?`>= 50`, not `> 50`. A
     * report that stayed silent at exactly the threshold would be silent at the one
     * number the guideline names.
     */
    @Test
    fun `peak MME of exactly 50 fires`() {
        val summary = report(
            substances = listOf(substance("Oxycodone", ExposureCurrency.MME)),
            exposure = listOf(exposure(0, ExposureCurrency.MME, peakDay = 50.0)),
        )

        findings(summary, emptyList(), unitLabel)
            .any { it.kind == Finding.Kind.OPIOID_LOAD } shouldBe true
    }

    @Test
    fun `peak MME below 50 produces no opioid finding`() {
        val summary = report(
            substances = listOf(substance("Codeine", ExposureCurrency.MME)),
            totalDays = 30,
            daysUsed = 10,
            exposure = listOf(exposure(0, ExposureCurrency.MME, peakDay = 30.0)),
        )

        findings(summary, emptyList(), unitLabel)
            .none { it.kind == Finding.Kind.OPIOID_LOAD } shouldBe true
    }

    // MARK: - Co-exposure

    @Test
    fun `co-exposure with a dangerous interaction produces a finding`() {
        val summary = report(
            substances = listOf(
                substance("Oxycodone", ExposureCurrency.MME),
                substance("Alprazolam", ExposureCurrency.DIAZEPAM),
            ),
            totalDays = 30,
            daysUsed = 10,
            overlaps = listOf(OverlapStat(a = 0, b = 1, hours = 6.5)),
        )
        val interactions = listOf(
            CompressedInteraction(
                id = "BENZODIAZEPINE|OPIOID",
                severity = InteractionSeverity.DANGEROUS,
                classA = DrugClass.BENZODIAZEPINE,
                classB = DrugClass.OPIOID,
                substancesA = listOf("Alprazolam"),
                substancesB = listOf("Oxycodone"),
            ),
        )

        val coExposure = findings(summary, interactions, unitLabel)
            .first { it.kind == Finding.Kind.CO_EXPOSURE }
        coExposure.severity shouldBe Finding.Severity.WARNING
        coExposure.summary shouldContain "Oxycodone"
        coExposure.summary shouldContain "~7h"
    }

    /**
     * A dangerous class pair the user never actually took together is **not** a finding.
     * This is the rule that stops the report from frightening someone over a month in
     * which the two substances never met 鈥?the interaction table can say the pair is
     * dangerous from the classes alone, and the overlap pass is what says whether it
     * happened.
     */
    @Test
    fun `a dangerous pair with no overlap produces no finding`() {
        val summary = report(
            substances = listOf(
                substance("Oxycodone", ExposureCurrency.MME),
                substance("Alprazolam", ExposureCurrency.DIAZEPAM),
            ),
            totalDays = 30,
            daysUsed = 10,
            overlaps = emptyList(),
        )
        val interactions = listOf(
            CompressedInteraction(
                id = "BENZODIAZEPINE|OPIOID",
                severity = InteractionSeverity.DANGEROUS,
                classA = DrugClass.BENZODIAZEPINE,
                classB = DrugClass.OPIOID,
                substancesA = listOf("Alprazolam"),
                substancesB = listOf("Oxycodone"),
            ),
        )

        findings(summary, interactions, unitLabel)
            .none { it.kind == Finding.Kind.CO_EXPOSURE } shouldBe true
    }

    @Test
    fun `a caution pair produces no co-exposure finding`() {
        val summary = report(
            substances = listOf(substance("Amphetamine"), substance("MDMA")),
            totalDays = 30,
            daysUsed = 10,
            overlaps = listOf(OverlapStat(a = 0, b = 1, hours = 4.0)),
        )
        val interactions = listOf(
            CompressedInteraction(
                id = "STIMULANT|EMPATHOGEN",
                severity = InteractionSeverity.CAUTION,
                classA = DrugClass.EMPATHOGEN,
                classB = DrugClass.STIMULANT,
                substancesA = listOf("MDMA"),
                substancesB = listOf("Amphetamine"),
            ),
        )

        findings(summary, interactions, unitLabel)
            .none { it.kind == Finding.Kind.CO_EXPOSURE } shouldBe true
    }

    // MARK: - Cadence

    @Test
    fun `a high usage fraction produces a cadence finding`() {
        val summary = report(
            substances = listOf(substance("X")),
            totalDays = 30,
            daysUsed = 28,
            longestBreakDays = 1,
        )

        val cadence = findings(summary, emptyList(), unitLabel).first { it.kind == Finding.Kind.CADENCE }
        cadence.severity shouldBe Finding.Severity.INFO
        cadence.summary shouldContain "28 of 30"
        cadence.summary shouldContain "93%"
    }

    @Test
    fun `a low usage fraction reports the longest break`() {
        val summary = report(
            substances = listOf(substance("X")),
            totalDays = 90,
            daysUsed = 5,
            longestBreakDays = 40,
        )

        val cadence = findings(summary, emptyList(), unitLabel).first { it.kind == Finding.Kind.CADENCE }
        cadence.summary shouldContain "5 of 90"
        cadence.summary shouldContain "longest break 40 days"
    }

    /** A window shorter than a fortnight says nothing about a cadence, either way. */
    @Test
    fun `a window under 14 days produces no cadence finding`() {
        val summary = report(substances = listOf(substance("X")), totalDays = 10, daysUsed = 10)

        findings(summary, emptyList(), unitLabel)
            .none { it.kind == Finding.Kind.CADENCE } shouldBe true
    }

    /** A middling fraction is neither sentence: most of the window in neither direction. */
    @Test
    fun `a middling usage fraction produces no cadence finding`() {
        val summary = report(substances = listOf(substance("X")), totalDays = 30, daysUsed = 15)

        findings(summary, emptyList(), unitLabel)
            .none { it.kind == Finding.Kind.CADENCE } shouldBe true
    }

    // MARK: - Sorting

    @Test
    fun `findings sort warnings first, then by kind order`() {
        val summary = report(
            substances = listOf(substance("Oxycodone", ExposureCurrency.MME)),
            totalDays = 30,
            daysUsed = 29,
            longestBreakDays = 1,
            exposure = listOf(exposure(0, ExposureCurrency.MME, peakDay = 100.0)),
            escalation = listOf(
                EscalationStat(0, EscalationDirection.RISING, change = 0.5, earlyMedian = 10.0, lateMedian = 15.0),
            ),
        )

        val results = findings(summary, emptyList(), unitLabel)
        results.shouldHaveAtLeastSize(3)
        val lastWarning = results.indexOfLast { it.severity == Finding.Severity.WARNING }
        val firstInfo = results.indexOfFirst { it.severity == Finding.Severity.INFO }
        (lastWarning < firstInfo) shouldBe true
        val opioid = results.indexOfFirst { it.kind == Finding.Kind.OPIOID_LOAD }
        val escalation = results.indexOfFirst { it.kind == Finding.Kind.ESCALATION }
        (opioid < escalation) shouldBe true
    }

    // MARK: - Interaction compression

    @Test
    fun `compression deduplicates by class pair and keeps the highest severity`() {
        val raw = listOf(
            InteractionRow(
                InteractionSeverity.DANGEROUS, "Oxycodone", "Alprazolam", "Respiratory depression",
                listOf(DrugClass.OPIOID), listOf(DrugClass.BENZODIAZEPINE),
            ),
            InteractionRow(
                InteractionSeverity.DANGEROUS, "Morphine", "Diazepam", "Respiratory depression",
                listOf(DrugClass.OPIOID), listOf(DrugClass.BENZODIAZEPINE),
            ),
            InteractionRow(
                InteractionSeverity.DANGEROUS, "Oxycodone", "Diazepam", "Respiratory depression",
                listOf(DrugClass.OPIOID), listOf(DrugClass.BENZODIAZEPINE),
            ),
            InteractionRow(
                InteractionSeverity.CAUTION, "Amphetamine", "MDMA", "Cardiovascular strain",
                listOf(DrugClass.STIMULANT), listOf(DrugClass.EMPATHOGEN),
            ),
            InteractionRow(
                InteractionSeverity.CAUTION, "Methylphenidate", "MDMA", "Cardiovascular strain",
                listOf(DrugClass.STIMULANT), listOf(DrugClass.EMPATHOGEN),
            ),
            InteractionRow(
                InteractionSeverity.UNSAFE, "Cocaine", "MDA", "Cardiovascular strain and serotonergic",
                listOf(DrugClass.STIMULANT), listOf(DrugClass.EMPATHOGEN),
            ),
        )

        val compressed = compressInteractions(raw)
        compressed.size shouldBe 2

        val opioidBenzo = compressed.first { it.id == "OPIOID|BENZODIAZEPINE" }
        opioidBenzo.severity shouldBe InteractionSeverity.DANGEROUS
        // Every participating substance survives, on its own class's side of the pair.
        opioidBenzo.substancesA shouldBe listOf("Morphine", "Oxycodone")
        opioidBenzo.substancesB shouldBe listOf("Alprazolam", "Diazepam")

        val stimEmpathogen = compressed.first { it.id == "STIMULANT|EMPATHOGEN" }
        // The worst of the collapsed rows wins: caution and unsafe become unsafe.
        stimEmpathogen.severity shouldBe InteractionSeverity.UNSAFE
    }

    /**
     * Two classes that arrive in the opposite order land in the same bucket, on the
     * sides their *class* puts them — not the sides their row happened to name.
     */
    @Test
    fun `compression folds the pair regardless of which side it arrived on`() {
        val raw = listOf(
            InteractionRow(
                InteractionSeverity.DANGEROUS, "Oxycodone", "Alprazolam", "",
                listOf(DrugClass.OPIOID), listOf(DrugClass.BENZODIAZEPINE),
            ),
            InteractionRow(
                InteractionSeverity.UNSAFE, "Diazepam", "Morphine", "",
                listOf(DrugClass.BENZODIAZEPINE), listOf(DrugClass.OPIOID),
            ),
        )

        val compressed = compressInteractions(raw)
        compressed.size shouldBe 1
        val row = compressed.single()
        row.severity shouldBe InteractionSeverity.DANGEROUS
        // Side A is the pair's lower-ordinal class, so `OPIOID` holds the opioids and
        // `BENZODIAZEPINE` the benzodiazepines — including for the row that arrived with
        // its classes the other way round, whose substances must follow their class.
        row.classA shouldBe DrugClass.OPIOID
        row.classB shouldBe DrugClass.BENZODIAZEPINE
        row.substancesA shouldBe listOf("Morphine", "Oxycodone")
        row.substancesB shouldBe listOf("Alprazolam", "Diazepam")
    }

    private companion object {
        /** Any tint: nothing in this file reads it, but `SummarySubstance` requires one. */
        val TINT = P3Color(red = 0.917, green = 0.200, blue = 0.139)

        @Suppress("unused")
        val BASE: Instant = Instant.ofEpochSecond(1_699_920_000)
    }
}
