package glass.kagerou.piru.ui.tools

import android.content.Context
import glass.kagerou.piru.R
import androidx.annotation.StringRes
import glass.kagerou.piru.data.catalog.AndroidSubstanceDb
import glass.kagerou.piru.data.catalog.SubstanceCatalogInstaller
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.engine.PKModelDepot
import glass.kagerou.piru.engine.SubstanceCatalog
import glass.kagerou.piru.model.DoseUnit
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.substance.ContentLanguage
import glass.kagerou.piru.substance.SubstanceReader
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The depot-esters shared by the Injection Levels tool and the Hormone Levels
 * insight.
 *
 * Ported from `Piru/Views/Tools/InjectionLevels/` — `Analyte.swift`,
 * `DepotCalibration.swift`, `HormoneLevelsLog.swift` — plus the `EsterPKRecord`
 * that `SubstanceStore+EsterPK.swift` builds out of the `ester_pk` table.
 *
 * ## Why this lives in `:app` and not in `:core:engine`
 * The three-compartment curve itself is already in the engine
 * ([PKModelDepot]) because it is pure maths. What is here is the *curated ester
 * table* and the two fitting procedures that read it — and the engine
 * deliberately carries only the two fields the depot heuristic needs
 * (`EsterRecord.label`, `.terminalRatePerDay`). Everything below is what the
 * screens need on top of that, and the port keeps the engine's seam narrow rather
 * than widening a value type its own tests construct by hand.
 *
 * ## The unit trap, repeated here because this is where it bites
 * `k1`/`k2`/`k3` are **per day**, because the depot literature reports that way
 * and the whole curve is on a days-to-weeks axis. `PKResolver`'s `ke`/`ka` are
 * **per minute**. Nothing in this file converts between them, and nothing should:
 * a rate that crossed the boundary would be off by 1440× and would still draw a
 * smooth, plausible curve.
 */

/**
 * Seconds in a day — the axis every depot number is expressed on.
 *
 * Deliberately does not reuse the engine's constant by import: it is the same
 * number, and naming it here keeps the conversion sites (`Instant` differences,
 * day counts) readable without a qualifier on every one.
 */
internal const val SECONDS_PER_DAY: Double = 86_400.0

/**
 * The hormone a set of esters belongs to.
 *
 * Ported from `Analyte.swift`. The depot maths is hormone-agnostic — only the
 * ester parameters and the canonical unit differ — so this is a table of units
 * and reference bands, not a branch anywhere in the curve.
 */
internal enum class Analyte(
    /** The `ester_pk.analyte` key, and the `analyteKey` a lab measurement is stored under. */
    val key: String,
    /**
     * The hormone's name as a reader sees it.
     *
     * A resource rather than a literal because it is not a catalog value: this is
     * a hard-coded table in app code, and it lands inside a translated sentence
     * ("Estimated %s level" → 「预计%s水平」). Upstream translates both names.
     */
    @StringRes val displayNameRes: Int,
    /** The unit levels are stored, fit and drawn in. */
    val canonicalUnit: String,
    /** The molar (SI) unit labs outside the US commonly report in. */
    val molarUnit: String,
    /** Molar mass, g/mol — a physical constant, not substance-keyed pharmacology. */
    private val molarMass: Double,
) {
    ESTRADIOL("estradiol", R.string.toolsb_analyte_estradiol, "pg/mL", "pmol/L", 272.38),
    TESTOSTERONE("testosterone", R.string.toolsb_analyte_testosterone, "ng/dL", "nmol/L", 288.42),
    ;

    /** Both units the lab-entry sheet accepts, canonical first. */
    val acceptedUnits: List<String> get() = listOf(canonicalUnit, molarUnit)

    /**
     * Molar-per-canonical: `1 canonicalUnit = factor · molarUnit`.
     *
     * Derived from the molar mass so the two cannot drift: estradiol
     * 1 pg/mL = 3.671 pmol/L, testosterone 1 ng/dL = 0.03467 nmol/L.
     */
    val molarPerCanonical: Double
        get() = when (this) {
            // pg/mL = 1e-9 g/L → mol/L = 1e-9 / MW → pmol/L = 1e3 · that.
            ESTRADIOL -> 1e-9 / molarMass * 1e12
            // ng/dL = 1e-8 g/L → mol/L = 1e-8 / MW → nmol/L = 1e9 · that.
            TESTOSTERONE -> 1e-8 / molarMass * 1e9
        }

    /** Convert a value the user typed in [unit] into the canonical unit. */
    fun toCanonical(value: Double, unit: String): Double =
        if (unit == molarUnit) value / molarPerCanonical else value

    /** Convert a canonical-unit value into [unit] for display. */
    fun fromCanonical(value: Double, unit: String): Double =
        if (unit == molarUnit) value * molarPerCanonical else value

    /**
     * A citable laboratory **reference region** in the canonical unit — a shaded
     * band, never a target.
     *
     * Testosterone ships the male range 300–1000 ng/dL (FDA Aveed label + Wang
     * 2010, PMID 20133964). Estradiol ships none: transfem serum-estradiol goals
     * vary widely and are the user's to set, so the app asserts no band there.
     */
    val referenceRegion: ClosedRange<Double>?
        get() = when (this) {
            ESTRADIOL -> null
            TESTOSTERONE -> 300.0..1000.0
        }

    /**
     * A clinical monitoring **goal** the user may opt into as their own reference
     * lines — labelled "a common clinical goal", never an app-set target.
     * Testosterone: 400–700 ng/dL, the Endocrine Society / WPATH SOC8 monitoring
     * goal. Estradiol: none shipped.
     */
    val labeledGoal: ClosedRange<Double>?
        get() = when (this) {
            ESTRADIOL -> null
            TESTOSTERONE -> 400.0..700.0
        }

    companion object {
        fun fromKey(key: String?): Analyte? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Depot PK for one injectable hormone ester, from the bundled `ester_pk` table.
 *
 * Ported from `EsterPKRecord` (`SubstanceStore+EsterPK.swift`). The amplitude `d`
 * is a population value the screen then calibrates to the user's own lab results.
 */
internal data class EsterPKRecord(
    /** Stable key, e.g. `"estradiol_cypionate"`. */
    val esterID: String,
    /** Analyte key: `"estradiol"` or `"testosterone"`. */
    val analyte: String,
    /** Canonical parent substance name (e.g. `"Estradiol"`). */
    val parent: String,
    /** PSID FAMILY of the parent, for matching a logged IM/SC dose to its ester. */
    val parentUID: String?,
    /** User-facing ester name (`"Cypionate"`, `"Enanthate"`, …). */
    val label: String,
    /**
     * The depot rate constants and amplitude (rates per day), or null for a
     * catalog-only ester that ships no validated curve — real and loggable, but
     * the screen declines to draw it rather than guess one.
     */
    val parameters: PKModelDepot.DepotParameters?,
    /** Provenance confidence: `"high"`, `"medium"`, `"low"`, or `"none"` (no curve). */
    val confidence: String,
    /** Attribution string shown on the sources card. */
    val provenance: String,
    /** Routes the parameters apply to (`["IM"]` or `["IM","SC"]`). */
    val routes: List<String>,
    /** A safety caution to surface when this ester is logged; null for most. */
    val caution: String?,
) {
    /** Whether this ester carries a validated curve the screen can draw. */
    val isModelable: Boolean get() = parameters != null

    companion object {
        /**
         * Build a record from the read layer's row, applying upstream's
         * all-or-nothing rule: `modelable` **and** all four PK columns present,
         * or no parameters at all.
         *
         * The conjunction rather than a `d != null` test, because a row that
         * carried an amplitude and no rates would otherwise produce a curve with a
         * division by zero in it.
         */
        fun from(row: SubstanceReader.EsterPKRow): EsterPKRecord {
            val d = row.d
            val k1 = row.k1
            val k2 = row.k2
            val k3 = row.k3
            val parameters = if (row.modelable && d != null && k1 != null && k2 != null && k3 != null) {
                PKModelDepot.DepotParameters(d = d, k1 = k1, k2 = k2, k3 = k3)
            } else {
                null
            }
            return EsterPKRecord(
                esterID = row.esterID,
                analyte = row.analyte,
                parent = row.parent,
                parentUID = row.parentUID,
                label = row.label,
                parameters = parameters,
                confidence = row.confidence,
                provenance = row.provenance,
                routes = row.routes,
                caution = row.caution,
            )
        }
    }
}

/**
 * The `ester_pk` index, keyed by ester id.
 *
 * Ported from `SubstanceStore`'s `esterPKIndex` and the four accessors over it.
 * The three orderings are load-bearing and all ascending by `esterID`: the ester
 * picker opens on the same one every launch, the per-ester chart colours are
 * assigned in the same order, and the log's grouping walks them in the same
 * sequence.
 */
internal class EsterPKIndex(private val byID: Map<String, EsterPKRecord>) {

    /** Depot PK for one ester by its stable id, or null when the DB carries no such ester. */
    fun esterPK(esterID: String?): EsterPKRecord? = esterID?.let { byID[it] }

    /**
     * Every **modelable** ester the DB carries for an analyte, ester-id ordered.
     * A catalog-only ester (no curve) is excluded so the picker never offers one
     * it cannot project.
     */
    fun forAnalyte(analyte: String): List<EsterPKRecord> =
        byID.values.filter { it.analyte == analyte && it.isModelable }.sortedBy { it.esterID }

    /** The analytes that have any modelable ester PK data, alphabetized. */
    fun analytesWithData(): List<String> =
        byID.values.filter { it.isModelable }.map { it.analyte }.distinct().sorted()

    /**
     * Every ester whose parent PSID FAMILY matches [uid] — modelable or not — so a
     * logged estradiol IM/SC dose finds the esters it could be, including the
     * catalog-only ones it can be logged as but not drawn.
     */
    fun forParentUID(uid: String?): List<EsterPKRecord> {
        if (uid == null) return emptyList()
        return byID.values.filter { it.parentUID == uid }.sortedBy { it.esterID }
    }

    companion object {
        val EMPTY = EsterPKIndex(emptyMap())

        fun of(rows: List<SubstanceReader.EsterPKRow>): EsterPKIndex =
            EsterPKIndex(rows.associate { it.esterID to EsterPKRecord.from(it) })
    }
}

/**
 * Loads the full ester table, once per process.
 *
 * ## A second read of the same file, on purpose
 * The app's catalog ([glass.kagerou.piru.substance.DbSubstanceCatalog]) holds the
 * ester index the *engine* needs, and that index is a two-field projection —
 * label and terminal rate. The rows here carry the amplitude, both remaining
 * rates, the confidence tier and the caution text, which the engine has no use
 * for. Rather than widen a value type the engine's own tests construct by hand,
 * this opens the installed catalog through the read layer directly, takes the
 * eight rows, and closes the handle.
 *
 * ## Why it is memoized behind a mutex
 * Two screens ask for it — the tool and the insight — and each was going to
 * verify the 18 MB catalog's hash before opening it. One load per process.
 */
internal object EsterPKIndexLoader {

    private val mutex = Mutex()
    private var cached: EsterPKIndex? = null

    suspend fun load(context: Context): EsterPKIndex = mutex.withLock {
        cached ?: run {
            val file = SubstanceCatalogInstaller.install(context)
            val db = AndroidSubstanceDb.open(file)
            val index = try {
                val order = db.query("SELECT slug FROM sources ORDER BY default_priority, slug")
                    .mapNotNull { it.string("slug") }
                EsterPKIndex.of(SubstanceReader(db, order, ContentLanguage.EN).esterPKRows())
            } finally {
                db.close()
            }
            cached = index
            index
        }
    }
}

/**
 * The curve the screens draw, plus the metrics read off it.
 *
 * Ported from `DepotCurveResult` (`InjectionLevelsModel.swift`).
 */
internal data class DepotCurveResult(
    val points: List<Point>,
    val injectionDates: List<Instant>,
    val range: ClosedRange<Instant>,
    /** Estimated trough and peak over the last modelled cycle, with band edges. */
    val trough: Double,
    val troughLow: Double,
    val troughHigh: Double,
    val peak: Double,
    val peakLow: Double,
    val peakHigh: Double,
    /** Fraction of the last cycle between the user's reference lines, or null when they set fewer than two. */
    val timeInRange: Double?,
) {
    internal data class Point(
        val date: Instant,
        /** Calibrated (or population) predicted level, in the analyte's canonical unit. */
        val level: Double,
        /** Typical-range band around the level. */
        val bandLow: Double,
        val bandHigh: Double,
    )
}

/**
 * The typical-range band as a fraction of the level.
 *
 * Ported verbatim from `InjectionLevelsModel.bandFraction(confidence:calibrated:)`.
 * Pre-calibration this reflects inter-individual variation — wide on purpose,
 * because the width is the invitation to calibrate — and post-calibration only
 * shape, assay and within-individual noise. Mapped from the ester's confidence
 * tier, so a `low`-confidence testosterone ester opens at ±50 % and a
 * `high`-confidence estradiol one at ±25 %.
 *
 * Six branches, all of them constants: do not collapse the unknown tier into
 * `medium`, which would understate the band on exactly the rows nobody measured.
 */
internal fun bandFraction(confidence: String, calibrated: Boolean): Double = when {
    confidence == "high" && !calibrated -> 0.25
    confidence == "high" && calibrated -> 0.18
    confidence == "medium" && !calibrated -> 0.45
    confidence == "medium" && calibrated -> 0.22
    !calibrated -> 0.50
    else -> 0.25
}

/**
 * Confidence as an ordinal, worst-first — the mix's band is the worst ester's.
 *
 * Ported from `HormoneLevelsModel.confidenceRank`. `none` ranks 0 rather than
 * being excluded: a catalog-only ester has no curve to contribute a band, so it
 * never reaches this, but an unrecognised tier must not silently outrank `low`.
 */
internal fun confidenceRank(confidence: String): Int = when (confidence) {
    "high" -> 3
    "medium" -> 2
    "low" -> 1
    else -> 0
}

/**
 * Fits the depot model to the user's own lab results.
 *
 * Ported from `DepotCalibration.swift`. Two tiers, chosen by how many
 * measurements the user has:
 *
 * - **Amplitude-only** (one measurement, or rate-fit off): the population
 *   parameters give the curve *shape*, the measurement scales its *y-axis*.
 *   Individual variation in `d` — bioavailability × volume of distribution — is
 *   the dominant between-person difference, so amplitude alone captures most of
 *   the personalisation available from a single point.
 * - **Rate-fit** (two or more): also fits `k1`, the slow terminal release rate,
 *   within `[0.5, 2]×` its population value. One point can only move the y-axis;
 *   two or more constrain the curve's shape in time, which is what an
 *   amplitude-only fit structurally cannot do. `k2`/`k3` stay put so the fitted
 *   `k1` never crosses them.
 *
 * This is regression against the user's own numbers, not pharmacology: it says
 * how their measurements sit against the model, never what level to aim for.
 */
internal object DepotCalibration {

    /** The `k1` search bounds for the rate-fit, matching the invariant on `depotConcentration`. */
    val K1_SCALE_RANGE: ClosedRange<Double> = 0.5..2.0

    /** One measurement the fit consumes: draw time and observed level, canonical unit. */
    internal data class Measurement(val date: Instant, val value: Double)

    internal data class Result(
        /** The fitted amplitude `d_cal` (weighted least squares at the fitted `k1`). */
        val calibratedAmplitude: Double,
        /** `d_cal / d_pop` — how far the user sits from the population amplitude. */
        val scale: Double,
        /** The fitted `k1` multiplier; 1.0 for an amplitude-only fit. */
        val k1Scale: Double,
        /** RMS of the residuals in canonical units, or null with fewer than two included measurements. */
        val residualRMS: Double?,
        /** How many measurements the fit used. */
        val usedCount: Int,
    ) {
        /** Whether the fit moved `k1` — a rate-fit ran — rather than just the amplitude. */
        val didFitRate: Boolean get() = k1Scale != 1.0
    }

    /**
     * Calibrate the depot model to [measurements]. Null when no measurement lands
     * on a non-zero predicted level, which is "nothing to fit" rather than a zero
     * fit.
     */
    fun calibrate(
        population: PKModelDepot.DepotParameters,
        injections: List<PKModelDepot.Injection>,
        measurements: List<Measurement>,
        fitRate: Boolean = true,
    ): Result? {
        if (population.d <= 0) return null
        val usable = measurements.filter { m ->
            PKModelDepot.depotConcentrationMultiDose(injections, m.date, population.withAmplitude(1.0)) > 0
        }
        if (usable.isEmpty()) return null

        // A rate-fit only earns its second parameter with two points to constrain it.
        if (!fitRate || usable.size < 2) {
            return amplitudeFit(population, injections, usable, k1Scale = 1.0)?.result
        }

        // Golden-section search over k1Scale for the minimum residual sum of
        // squares, amplitude re-fitted linearly at each candidate. Unimodal enough
        // in practice; the bracket keeps k1 inside its documented [0.5, 2]× envelope.
        val bestScale = goldenSectionMinimum(K1_SCALE_RANGE) { s ->
            amplitudeFit(population, injections, usable, k1Scale = s)?.sumOfSquares ?: Double.MAX_VALUE
        }
        val fit = amplitudeFit(population, injections, usable, k1Scale = bestScale)
            ?: return amplitudeFit(population, injections, usable, k1Scale = 1.0)?.result
        return fit.result
    }

    /**
     * Calibrate a **multi-ester** summed serum curve.
     *
     * Ported from `calibrateSummed`. The person-wide amplitude `scale` and — with
     * two or more points and rate-fit on — the terminal-rate `k1Scale` are fitted
     * **globally**, applied uniformly to every ester's own population parameters.
     * Labs cannot resolve per-ester rates from a mixed log, so one scale and one
     * rate is the honest fit.
     *
     * `calibratedAmplitude` carries that same `scale`: across esters there is no
     * single population `d` to divide by.
     */
    fun calibrateSummed(
        contributions: List<PKModelDepot.DepotContribution>,
        measurements: List<Measurement>,
        fitRate: Boolean = true,
    ): Result? {
        if (contributions.isEmpty()) return null

        fun unitSum(date: Instant, k1Scale: Double): Double = contributions.sumOf { c ->
            PKModelDepot.depotConcentrationMultiDose(c.injections, date, c.parameters.withK1Scale(k1Scale))
        }

        val usable = measurements.filter { unitSum(it.date, 1.0) > 0 }
        if (usable.isEmpty()) return null

        fun fit(k1Scale: Double): Fit? {
            var num = 0.0
            var den = 0.0
            val pairs = mutableListOf<Pair<Double, Double>>()
            for (m in usable) {
                val predicted = unitSum(m.date, k1Scale)
                if (predicted <= 0) continue
                num += m.value * predicted
                den += predicted * predicted
                pairs += m.value to predicted
            }
            if (den <= 0 || pairs.isEmpty()) return null
            val scale = num / den
            val ss = pairs.sumOf { (observed, predicted) ->
                val r = observed - scale * predicted
                r * r
            }
            return Fit(
                result = Result(
                    calibratedAmplitude = scale,
                    scale = scale,
                    k1Scale = k1Scale,
                    residualRMS = if (pairs.size >= 2) sqrt(ss / pairs.size) else null,
                    usedCount = pairs.size,
                ),
                sumOfSquares = ss,
            )
        }

        if (!fitRate || usable.size < 2) return fit(1.0)?.result
        val bestScale = goldenSectionMinimum(K1_SCALE_RANGE) { s ->
            fit(s)?.sumOfSquares ?: Double.MAX_VALUE
        }
        return fit(bestScale)?.result ?: fit(1.0)?.result
    }

    /**
     * Whether a newly observed level departs from the calibrated prediction by
     * more than 2·σ_residual — a formulation change, injection-depth variance, or
     * an assay switch.
     *
     * Only meaningful once the calibration set has three or more points, so it
     * never flags off a two-point fit whose residual is not an uncertainty at all.
     */
    fun isOutlier(
        observed: Double,
        predicted: Double,
        residualRMS: Double?,
        calibrationCount: Int,
    ): Boolean {
        if (calibrationCount < 3) return false
        val rms = residualRMS ?: return false
        if (rms <= 0) return false
        return abs(observed - predicted) > 2 * rms
    }

    /** The weighted least-squares amplitude at a fixed `k1Scale`, with its residuals. */
    private fun amplitudeFit(
        population: PKModelDepot.DepotParameters,
        injections: List<PKModelDepot.Injection>,
        measurements: List<Measurement>,
        k1Scale: Double,
    ): Fit? {
        val unit = population.withK1Scale(k1Scale).withAmplitude(1.0)
        var num = 0.0
        var den = 0.0
        val pairs = mutableListOf<Pair<Double, Double>>()
        for (m in measurements) {
            val predicted = PKModelDepot.depotConcentrationMultiDose(injections, m.date, unit)
            if (predicted <= 0) continue
            num += m.value * predicted
            den += predicted * predicted
            pairs += m.value to predicted
        }
        if (den <= 0 || pairs.isEmpty()) return null
        // d_cal = Σ(E₂ⱼ·pⱼ) / Σ(pⱼ²) where pⱼ is the unit-amplitude prediction at
        // the scaled k1; with one measurement this is the ratio E₂_obs / p_obs.
        val dCal = num / den
        val ss = pairs.sumOf { (observed, predicted) ->
            val r = observed - dCal * predicted
            r * r
        }
        return Fit(
            result = Result(
                calibratedAmplitude = dCal,
                scale = dCal / population.d,
                k1Scale = k1Scale,
                residualRMS = if (pairs.size >= 2) sqrt(ss / pairs.size) else null,
                usedCount = pairs.size,
            ),
            sumOfSquares = ss,
        )
    }

    private data class Fit(val result: Result, val sumOfSquares: Double)

    /**
     * Golden-section minimum of a unimodal-enough function on a closed interval.
     *
     * Ported verbatim: `phi = 0.6180339887`, at most 60 iterations, converging at
     * `1e-6`. About 40 evaluations to ≈1e-4 in the scale, which is cheap for the
     * handful of measurements a user has and — the reason for a derivative-free
     * method at all — the objective is a superposition sum with no closed-form
     * derivative in the amplitude.
     */
    private fun goldenSectionMinimum(range: ClosedRange<Double>, f: (Double) -> Double): Double {
        val phi = (sqrt(5.0) - 1) / 2
        var a = range.start
        var b = range.endInclusive
        var c = b - phi * (b - a)
        var d = a + phi * (b - a)
        var fc = f(c)
        var fd = f(d)
        for (i in 0 until 60) {
            if (fc < fd) {
                b = d; d = c; fd = fc
                c = b - phi * (b - a); fc = f(c)
            } else {
                a = c; c = d; fc = fd
                d = a + phi * (b - a); fd = f(d)
            }
            if (b - a < 1e-6) break
        }
        return (a + b) / 2
    }
}

// MARK: - Reading the dose log

/**
 * What the dose log holds for an analyte: the injections the curve can use, how
 * many more were logged in mL and await a vial strength, and the concentration
 * the user most recently logged a volumetric dose at.
 *
 * Ported from `InjectionLevelsView.LogInjections`.
 */
internal data class LogInjections(
    val injections: List<PKModelDepot.Injection> = emptyList(),
    val volumeLoggedCount: Int = 0,
    val latestLoggedConcentration: Double? = null,
)

/**
 * The injectable mass one logged dose contributes (canonical mg), and whether it
 * was logged in a volume unit.
 *
 * Ported from `InjectionLevelsView.doseMassMg`. A dose in mg/µg/g converts by its
 * unit scale; a dose in mL joins at [volumeConcentrationMgPerML], and its `mg` is
 * null until a vial strength is known — but it is still counted as volume-logged,
 * which is what makes the "enter the vial strength" prompt appear. Anything else
 * (a non-mass, non-volume unit) contributes nothing.
 */
internal fun doseMassMg(
    entry: DoseEntryEntity,
    volumeConcentrationMgPerML: Double?,
): Pair<Double?, Boolean> {
    // The scale of one unit in mg — null for anything that is not a bare mass
    // unit, which is exactly the test the ladder conversion uses.
    val scale = DoseUnit.convert(1.0, from = entry.unit, to = "mg")
    if (scale != null) {
        val mg = entry.amount * scale
        return (if (mg > 0) mg else null) to false
    }
    if (isVolumeUnit(entry.unit)) {
        val concentration = volumeConcentrationMgPerML
        if (concentration == null || concentration <= 0) return null to true
        val mg = entry.amount * concentration
        return (if (mg > 0) mg else null) to true
    }
    return null to false
}

/** Whether [unit] is a volume the user could have measured out of a vial. */
internal fun isVolumeUnit(unit: String): Boolean {
    val normalized = unit.trim().lowercase()
    return normalized == "ml" || normalized == "cc"
}

/**
 * Pull qualifying injections from the dose log for [analyte]: IM/SC route and a
 * substance in the analyte's PSID family.
 *
 * Ported from `InjectionLevelsView.injections(from:analyte:volumeConcentrationMgPerML:)`.
 *
 * ## The one fallback this port cannot make
 * Upstream resolves a dose's ester from `entry.saltForm` and, when that is null,
 * from the *name* it was logged under ("Estradiol Enanthate") through
 * `SubstanceStore.saltForm(forNameOrAlias:)`. That index is built from a
 * `substance_forms` facet join the read layer does not expose; the shipped
 * `substances` table has no salt column to fall back to. So a legacy dose that
 * names its ester only in the substance string lands on the analyte's default
 * ester rather than its own — the same place upstream puts a dose that names no
 * ester at all. Logging through quick log writes `saltForm`, so this is a
 * migration tail rather than the common path.
 */
internal fun logInjections(
    entries: List<DoseEntryEntity>,
    analyte: Analyte,
    volumeConcentrationMgPerML: Double?,
    esters: EsterPKIndex,
    catalog: SubstanceCatalog,
): LogInjections {
    // The families that ship ester data — empty means the analyte has no curves
    // at all, and nothing here is worth looking at.
    val familyUIDs = esters.forAnalyte(analyte.key).mapNotNull { it.parentUID }.toSet()
    if (familyUIDs.isEmpty()) return LogInjections()

    val injections = mutableListOf<PKModelDepot.Injection>()
    var volumeLogged = 0
    var latestConcentration: Double? = null

    for (entry in entries) {
        if (entry.route != RouteOfAdministration.INTRAMUSCULAR &&
            entry.route != RouteOfAdministration.SUBCUTANEOUS
        ) {
            continue
        }
        val uid = entry.substanceUID ?: catalog.substanceUID(entry.substance) ?: continue
        if (uid !in familyUIDs) continue

        // A dose logged by volume × concentration keeps its mass in `amount`; its
        // concentration is the best default for the mL-only doses.
        val logged = entry.abv
        if (entry.volumeML != null && logged != null && logged > 0) latestConcentration = logged

        val (mg, isVolume) = doseMassMg(entry, volumeConcentrationMgPerML)
        if (isVolume) volumeLogged++
        if (mg == null) continue
        injections += PKModelDepot.Injection(entry.timestamp.toInstant(), mg)
    }

    return LogInjections(
        injections = injections.sortedBy { it.date },
        volumeLoggedCount = volumeLogged,
        latestLoggedConcentration = latestConcentration,
    )
}

/**
 * The modelable ester the user logs most for [analyte], from the ester named on
 * their qualifying doses' `saltForm` — the log-first default ester.
 *
 * Null when no logged dose names a modelable ester, which falls the picker back
 * to the first ester rather than to nothing. Ties break on ester id so the
 * default is stable rather than a function of dictionary iteration order.
 */
internal fun dominantEsterID(
    entries: List<DoseEntryEntity>,
    analyte: Analyte,
    esters: EsterPKIndex,
    catalog: SubstanceCatalog,
): String? {
    val modelable = esters.forAnalyte(analyte.key)
    val familyUIDs = modelable.mapNotNull { it.parentUID }.toSet()
    if (familyUIDs.isEmpty()) return null

    val counts = mutableMapOf<String, Int>()
    for (entry in entries) {
        if (entry.route != RouteOfAdministration.INTRAMUSCULAR &&
            entry.route != RouteOfAdministration.SUBCUTANEOUS
        ) {
            continue
        }
        val uid = entry.substanceUID ?: catalog.substanceUID(entry.substance) ?: continue
        if (uid !in familyUIDs) continue
        val label = entry.saltForm ?: continue
        val esterID = modelable.firstOrNull { it.label == label && it.parentUID == uid }?.esterID ?: continue
        counts[esterID] = (counts[esterID] ?: 0) + 1
    }
    // Most-logged wins; ties break on ester id for a stable default.
    return counts.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .firstOrNull()?.key
}

// MARK: - The retrospective, per-ester log

/**
 * The dose log bucketed **per ester** — the Hormone Levels insight's input.
 *
 * Ported from `HormoneLevelsLog`. Where the prediction tool collapses the log to
 * one dominant ester, this keeps each ester distinct: a valerate → cypionate
 * switch, or a mix of two esters at different concentrations, is grouped by the
 * ester named on each dose so the serum total can sum each ester's own depot
 * curve instead of flattening everything to one shape.
 */
internal object HormoneLevelsLog {

    internal data class EsterGroup(
        val ester: EsterPKRecord,
        val injections: List<PKModelDepot.Injection>,
    )

    /** A catalog-only ester's dose: drawn as a labelled marker, never a curve. */
    internal data class Marker(val ester: EsterPKRecord, val date: Instant)

    internal data class Grouped(
        /** One entry per modelable ester the user logged, each with its own doses. */
        val esterGroups: List<EsterGroup> = emptyList(),
        /** Doses of a catalog-only ester (no validated curve). */
        val catalogOnlyMarkers: List<Marker> = emptyList(),
        /** Injections logged in mL that await a vial strength before they can join. */
        val volumeLoggedCount: Int = 0,
    ) {
        /** Whether any modelable ester has a dose to draw. */
        val hasModelableInjections: Boolean get() = esterGroups.any { it.injections.isNotEmpty() }

        /**
         * Every modelable injection, flattened — the calibration set and the
         * log-only span both read the whole history regardless of which ester each
         * dose was.
         */
        val allInjections: List<PKModelDepot.Injection>
            get() = esterGroups.flatMap { it.injections }.sortedBy { it.date }
    }

    /**
     * Bucket the log's qualifying IM/SC doses for [analyte] by their own ester. A
     * dose that names no ester falls back to the analyte's default (the ester the
     * user logs most, else the first modelable one) — the same default the tool
     * opens on.
     */
    fun grouped(
        entries: List<DoseEntryEntity>,
        analyte: Analyte,
        volumeConcentrationMgPerML: Double?,
        esters: EsterPKIndex,
        catalog: SubstanceCatalog,
    ): Grouped {
        val modelable = esters.forAnalyte(analyte.key)
        val familyUIDs = modelable.mapNotNull { it.parentUID }.toSet()
        if (familyUIDs.isEmpty()) return Grouped()

        val defaultEsterID = dominantEsterID(entries, analyte, esters, catalog)
            ?: modelable.firstOrNull()?.esterID

        val buckets = mutableMapOf<String, MutableList<PKModelDepot.Injection>>()
        val markers = mutableListOf<Marker>()
        var volumeLogged = 0

        for (entry in entries) {
            if (entry.route != RouteOfAdministration.INTRAMUSCULAR &&
                entry.route != RouteOfAdministration.SUBCUTANEOUS
            ) {
                continue
            }
            val uid = entry.substanceUID ?: catalog.substanceUID(entry.substance) ?: continue
            if (uid !in familyUIDs) continue

            // Resolve this dose's ester from its own `saltForm`, falling back to
            // the analyte default. See `logInjections` for why the legacy
            // name-derived fallback is not available here.
            val label = entry.saltForm
            val record = if (label != null) {
                esters.forParentUID(uid).firstOrNull { it.label == label }
                    ?: esters.esterPK(defaultEsterID)
            } else {
                esters.esterPK(defaultEsterID)
            } ?: continue

            val (mg, isVolume) = doseMassMg(entry, volumeConcentrationMgPerML)
            if (isVolume) volumeLogged++
            if (record.isModelable) {
                if (mg == null) continue
                buckets.getOrPut(record.esterID) { mutableListOf() }
                    .add(PKModelDepot.Injection(entry.timestamp.toInstant(), mg))
            } else {
                markers += Marker(record, entry.timestamp.toInstant())
            }
        }

        return Grouped(
            esterGroups = buckets.entries.sortedBy { it.key }.mapNotNull { (id, doses) ->
                esters.esterPK(id)?.let { EsterGroup(it, doses.sortedBy { dose -> dose.date }) }
            },
            catalogOnlyMarkers = markers.sortedBy { it.date },
            volumeLoggedCount = volumeLogged,
        )
    }
}

// MARK: - The visible window

/**
 * The median gap in days between consecutive injections, or null with fewer than
 * two dates.
 *
 * The upper median (`sorted[size / 2]()`), matching upstream rather than
 * averaging the middle pair: with an even count the later of the two middle gaps
 * is used. That is deliberate and it matters for a two-element list, where the
 * median is the single gap rather than half of it.
 */
internal fun medianIntervalDays(dates: List<Instant>): Double? {
    if (dates.size < 2) return null
    val gaps = dates.sorted().zipWithNext { earlier, later ->
        Duration.between(earlier, later).toMillis() / (1000.0 * SECONDS_PER_DAY)
    }.filter { it > 0 }.sorted()
    if (gaps.isEmpty()) return null
    return gaps[gaps.size / 2]
}

/**
 * The date span to draw and the length of one modelled cycle (days).
 *
 * Ported from both models' `window(dates:)` — they are identical apart from the
 * tool's schedule-projection branch, which is why this takes the retrospective
 * branch only and the tool computes its own.
 *
 * Retrospective: the span runs from the visible window before the last injection
 * to one cycle past it (or now), so a long history zooms to recent detail while
 * earlier injections still contribute to the curve off-screen.
 */
internal fun cycleWindow(
    dates: List<Instant>,
    effectiveVisibleDays: Double?,
    now: Instant,
): Triple<Instant, Instant, Double> {
    val sorted = dates.sorted()
    val first = sorted.firstOrNull() ?: now
    val last = sorted.lastOrNull() ?: now
    val cycleDays = medianIntervalDays(sorted) ?: 14.0
    val end = maxOf(last.plusMillis((cycleDays * SECONDS_PER_DAY * 1000).toLong()), now)
    val start = effectiveVisibleDays?.let { days ->
        maxOf(first, end.minusMillis((days * SECONDS_PER_DAY * 1000).toLong()))
    } ?: first
    return Triple(start, end, cycleDays)
}
