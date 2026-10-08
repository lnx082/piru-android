package glass.kagerou.piru.ui.tools

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import glass.kagerou.piru.R
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.data.PiruDatabase
import glass.kagerou.piru.data.entity.LabMeasurementEntity
import glass.kagerou.piru.engine.PKModelDepot
import glass.kagerou.piru.engine.SubstanceCatalog
import java.time.Instant
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * The zoo of state behind the two depot screens: the persisted calibration
 * preferences, the lab measurements, and the two state holders that mirror
 * `InjectionLevelsModel` and `HormoneLevelsModel`.
 *
 * Ported from `InjectionLevelsModel.swift`, `HormoneLevelsModel.swift` and the
 * `@AppStorage` keys those two share.
 *
 * ## Why these are hand-rolled state holders and not view models
 * Upstream is `@Observable @MainActor final class`, and the reason is stated in
 * its own header: `refresh()` is O(samples × doses) over a superposition sum and
 * must **not** run inside `body`. Kotlin's equivalent is a stable holder whose
 * inputs are Compose state, plus a single `LaunchedEffect(key) { refresh() }` at
 * the call site — the same split, with the recompute key playing the role of
 * `onChange(of: model.recomputeKey)`.
 *
 * ## The recompute key holds the lists, not a hash
 * Upstream feeds a `Hasher` over the log and the lab set. `Swift.Hasher` is
 * seeded per process and is not reproducible across languages, so porting its
 * numbers would pin nothing. The key carries the two lists instead and compares
 * them structurally, which is what the hash stood for.
 */

// MARK: - Persisted preferences

/**
 * The calibration preferences both screens share, and the per-analyte vial
 * strength.
 *
 * Ported from the `@AppStorage` block in `InjectionLevelsView` /
 * `HormoneLevelsView`. The keys are upstream's own strings, so a future import
 * from iOS lands on the same slots; the store itself is a private
 * `SharedPreferences` file rather than the app's Room database, because these are
 * four scalars and a preference screen's worth of state — not user records with
 * an identity, a history, or a reason to be queryable.
 *
 * ## `0` means unset, not zero
 * The vial strength is stored as a plain double where `0` is the sentinel for
 * "never entered". That is upstream's encoding and it is worth keeping: the
 * field is a text input the user can clear, and a cleared field must fall back to
 * the prompt rather than becoming a strength of zero that silently drops every
 * mL-logged dose out of the curve.
 */
internal class DepotPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("piru.injectionLevels", Context.MODE_PRIVATE)

    var personalMultiplier: Double
        get() = prefs.getFloat(KEY_PERSONAL_MULTIPLIER, 1.0f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_PERSONAL_MULTIPLIER, value.toFloat()).apply()

    var autoCalibrate: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CALIBRATE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CALIBRATE, value).apply()

    var fitRates: Boolean
        get() = prefs.getBoolean(KEY_FIT_RATES, true)
        set(value) = prefs.edit().putBoolean(KEY_FIT_RATES, value).apply()

    /** The vial strength for [analyte] in mg/mL, or null when it has never been entered. */
    fun volumeConcentration(analyte: Analyte): Double? =
        prefs.getFloat(volumeKey(analyte), 0f).toDouble().takeIf { it > 0 }

    fun setVolumeConcentration(analyte: Analyte, value: Double?) {
        prefs.edit().putFloat(volumeKey(analyte), (value ?: 0.0).toFloat()).apply()
    }

    private fun volumeKey(analyte: Analyte) = "injLevelsVolumeConcentration.${analyte.key}"

    private companion object {
        const val KEY_PERSONAL_MULTIPLIER = "injLevelsPersonalMultiplier"
        const val KEY_AUTO_CALIBRATE = "injLevelsAutoCalibrate"
        const val KEY_FIT_RATES = "injLevelsFitRates"
    }
}

// MARK: - Lab measurements

/**
 * One blood draw.
 *
 * Ported from the SwiftData `@Model LabMeasurement`. `value` is always the
 * analyte's canonical unit — `inputUnit` records what the user typed so the row
 * can echo it back the way their lab reported it.
 */
internal data class LabMeasurement(
    val id: String = UUID.randomUUID().toString(),
    val date: Instant,
    /** `"estradiol"`, `"testosterone"`, or a companion's key (`"hematocrit"`, …). */
    val analyteKey: String,
    /** The value in [analyteKey]'s canonical unit. */
    val value: Double,
    val inputUnit: String,
    val esterID: String? = null,
    val excludedFromCalibration: Boolean = false,
    /** A free-text note, as iOS carries one. Null means "no note", not an empty one. */
    val note: String? = null,
    /** When the row was written, distinct from [date], which is when blood was drawn. */
    val createdAt: Instant = Instant.now(),
)

/**
 * The user's lab results, on the database.
 *
 * Ported from the SwiftData store. ## Why this stopped being a preferences file
 * It used to be a JSON blob in `piru.labMeasurements`, on the reasoning that a lab
 * result is "a small, append-mostly list … never joined against anything". Both
 * halves of that turned out to be false in a way that cost the user data:
 *
 * - **It was outside every export, import and erase path.** The export layer said
 *   these had "no table on Android" and omitted them; the importer counted them as
 *   dropped; `deleteAll` cleared thirteen Room tables and no preferences file, so
 *   "Delete Everything" left them behind; and `android:allowBackup="false"` ruled
 *   out the OS backup. Restoring from iOS discarded them, and a reinstall lost them
 *   for good.
 * - **Two screens read them** — hormone levels and injection levels — which is the
 *   condition the old comment named as the point at which this belongs "in the
 *   database beside the doses".
 *
 * So it is a Room table now ([LabMeasurementEntity], v3). [importLegacyRows] moves
 * anything already in the preferences file across, once, and clears the file so it
 * cannot be counted twice.
 *
 * Every method is `suspend` because Room's are: the callers already read on a
 * coroutine, and a blocking read behind `runBlocking` would put a disk query on
 * whichever thread happened to call.
 */
internal class LabMeasurementStore(private val database: PiruDatabase) {

    /** Every measurement, newest first. */
    suspend fun all(): List<LabMeasurement> =
        database.labMeasurementDao().all().map { it.toModel() }

    /** The rows for one analyte, oldest first — the order a series is plotted in. */
    suspend fun forAnalyte(analyteKey: String): List<LabMeasurement> =
        database.labMeasurementDao().forAnalyte(analyteKey).map { it.toModel() }

    suspend fun insert(measurement: LabMeasurement) {
        database.labMeasurementDao().insert(measurement.toEntity())
    }

    suspend fun update(measurement: LabMeasurement) {
        val existing = database.labMeasurementDao().byId(measurement.id) ?: return
        database.labMeasurementDao().update(
            measurement.toEntity().copy(rowId = existing.rowId),
        )
    }

    suspend fun delete(id: String) {
        database.labMeasurementDao().deleteById(id)
    }

    suspend fun setExcluded(id: String, excluded: Boolean) {
        database.labMeasurementDao().setExcluded(id, excluded)
    }

    suspend fun deleteAll() {
        database.labMeasurementDao().deleteAll()
    }

    /**
     * Copy any rows left in the old preferences file into the table, once.
     *
     * Runs on launch before any reader, and is idempotent by construction: the file
     * is cleared inside the same edit that reads it, so a second call finds nothing.
     * Rows are inserted only when their own `id` is not already present, so a
     * backfill interrupted after an insert but before the clear cannot duplicate
     * anything.
     *
     * Returns how many rows were moved, which is what a test can assert on.
     */
    suspend fun importLegacyRows(context: Context): Int {
        val prefs = context.applicationContext
            .getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
        val raw = prefs.getString(LEGACY_KEY, null) ?: return 0
        // Cleared before the insert loop rather than after: if a row fails to parse,
        // the blob is already gone, and the alternative — keeping it — means every
        // later launch retries the same bad blob forever.
        prefs.edit().remove(LEGACY_KEY).apply()

        val rows = runCatching { decodeLegacy(JSONArray(raw)) }.getOrDefault(emptyList())
        var moved = 0
        for (row in rows) {
            if (database.labMeasurementDao().byId(row.id) != null) continue
            database.labMeasurementDao().insert(row.toEntity())
            moved++
        }
        return moved
    }

    private fun decodeLegacy(array: JSONArray): List<LabMeasurement> = buildList {
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val analyteKey = row.optString("analyteKey").takeIf { it.isNotEmpty() } ?: continue
            val inputUnit = row.optString("inputUnit").takeIf { it.isNotEmpty() } ?: continue
            add(
                LabMeasurement(
                    id = row.optString("id").takeIf { it.isNotEmpty() }
                        ?: UUID.randomUUID().toString(),
                    date = Instant.ofEpochMilli(row.optLong("date")),
                    analyteKey = analyteKey,
                    value = row.optDouble("value", 0.0),
                    inputUnit = inputUnit,
                    esterID = if (row.isNull("esterID")) null else row.optString("esterID"),
                    excludedFromCalibration = row.optBoolean("excluded", false),
                ),
            )
        }
    }

    private fun LabMeasurement.toEntity() = LabMeasurementEntity(
        id = id,
        date = java.util.Date.from(date),
        analyteKey = analyteKey,
        value = value,
        inputUnit = inputUnit,
        esterId = esterID,
        excludedFromCalibration = excludedFromCalibration,
        note = note,
        createdAt = java.util.Date.from(createdAt),
    )

    private fun LabMeasurementEntity.toModel() = LabMeasurement(
        id = id,
        date = date.toInstant(),
        analyteKey = analyteKey,
        value = value,
        inputUnit = inputUnit,
        esterID = esterId,
        excludedFromCalibration = excludedFromCalibration,
        note = note,
        createdAt = createdAt.toInstant(),
    )

    private companion object {
        /** The v2 file and key, read once by [importLegacyRows] and never written again. */
        const val LEGACY_FILE = "piru.labMeasurements"
        const val LEGACY_KEY = "rows"
    }
}

// MARK: - Companion measured series

/**
 * A lab series shown **beside** the modelled hormone — measured points, never a
 * model.
 *
 * Ported from `CompanionSeries.swift`. Each side models its injected hormone and
 * plots the other axis as measured points, because the relationships
 * (aromatisation, HPG suppression) are person-specific with no citable conversion
 * to hard-code.
 */
internal enum class CompanionMeasurement(
    val key: String,
    @StringRes val titleRes: Int,
    val unit: String,
    @StringRes val framingRes: Int,
    val tint: Color,
) {
    /** Testosterone suppression on the estradiol (transfem) side. */
    TESTOSTERONE(
        key = "testosterone",
        titleRes = R.string.toolsb_depot_companion_testosterone_title,
        unit = "ng/dL",
        framingRes = R.string.toolsb_depot_companion_testosterone_framing,
        tint = Color(0xFF3F51B5),
    ),

    /** Aromatised estradiol on the testosterone (transmasc) side. */
    ESTRADIOL(
        key = "estradiol",
        titleRes = R.string.toolsb_depot_companion_estradiol_title,
        unit = "pg/mL",
        framingRes = R.string.toolsb_depot_companion_estradiol_framing,
        tint = Color(0xFFE91E63),
    ),

    /** Haematocrit — the T-specific monitoring axis estradiol has no analogue for. */
    HEMATOCRIT(
        key = "hematocrit",
        titleRes = R.string.toolsb_depot_companion_hematocrit_title,
        unit = "%",
        framingRes = R.string.toolsb_depot_companion_hematocrit_framing,
        tint = Color(0xFFD32F2F),
    ),

    /** Haemoglobin — the other red-cell readout, monitored on the same panel. */
    HEMOGLOBIN(
        key = "hemoglobin",
        titleRes = R.string.toolsb_depot_companion_hemoglobin_title,
        unit = "g/dL",
        framingRes = R.string.toolsb_depot_companion_hemoglobin_framing,
        tint = Color(0xFFF57C00),
    ),
    ;

    /**
     * Whether this is a hormone measurement in the hormone's own units, versus a
     * fixed-unit blood count. A blood count never calibrates a depot curve.
     */
    val isHormone: Boolean get() = this == TESTOSTERONE || this == ESTRADIOL

    companion object {
        /** The companion axes the depot detail for [analyte] shows. */
        fun companions(analyte: Analyte): List<CompanionMeasurement> = when (analyte) {
            Analyte.ESTRADIOL -> listOf(TESTOSTERONE)
            Analyte.TESTOSTERONE -> listOf(ESTRADIOL, HEMATOCRIT, HEMOGLOBIN)
        }
    }
}

// MARK: - Chart zoom

/**
 * The chart's zoom presets.
 *
 * Ported from `InjectionLevelsModel.ChartRange`. Depot cycles run days-to-weeks
 * over months of history, so a fixed all-time span buries the recent detail.
 */
internal enum class ChartRange(val days: Double?, @StringRes val labelRes: Int) {
    MONTH(30.0, R.string.toolsb_depot_chart_range_1m),
    QUARTER(91.0, R.string.toolsb_depot_chart_range_3m),
    HALF_YEAR(182.0, R.string.toolsb_depot_chart_range_6m),
    ALL(null, R.string.toolsb_depot_chart_range_all),
}

// MARK: - The prediction tool's state

/**
 * The Injection Levels tool's inputs and the curve projected from them.
 *
 * Ported from `InjectionLevelsModel.swift` (461 lines). Every input, the two
 * one-shot default guards and the whole `refresh()` are upstream's; what changes
 * is only the plumbing — Compose state instead of `@Observable`, and an injected
 * ester index and catalog instead of singletons.
 *
 * The tool predicts a concentration from doses the user enters or logged. It
 * never recommends a dose, an interval, or a level to aim for.
 */
@Stable
internal class InjectionLevelsModel(
    private val preferences: DepotPreferences,
    private val esters: EsterPKIndex,
    private val catalog: SubstanceCatalog,
) {

    // MARK: Inputs

    var analyte by mutableStateOf(Analyte.ESTRADIOL)
    var selectedEsterID by mutableStateOf<String?>(null)
    var doseMg by mutableStateOf<Double?>(5.0)
    var intervalDays by mutableStateOf<Double?>(14.0)

    /** Log-first: prefer the dose log when it has qualifying injections. */
    var useLogHistory by mutableStateOf(true)

    /** Vial strength applied to injections logged by volume with no concentration of their own. */
    var volumeConcentrationMgPerML by mutableStateOf<Double?>(null)

    /**
     * Manual schedule: begin from the level the log puts in the body today, with
     * the next dose one interval after the last logged one. Off (or with no log):
     * begin from [startingLevel] today.
     */
    var startFromLog by mutableStateOf(true)

    /** The level already in the body today, canonical unit. Null or 0 is a clean start. */
    var startingLevel by mutableStateOf<Double?>(null)

    var referenceLow by mutableStateOf<Double?>(null)
    var referenceHigh by mutableStateOf<Double?>(null)

    /** The "run high / run low" knob, applied as `d = d_pop · multiplier`. */
    var personalMultiplier by mutableStateOf(1.0)
    var autoCalibrateFromLabs by mutableStateOf(true)
    var fitRates by mutableStateOf(true)

    var chartRange by mutableStateOf(ChartRange.QUARTER)

    /** A continuous window width (days) set by pinch, overriding [chartRange] until a preset is tapped. */
    var pinchVisibleDays by mutableStateOf<Double?>(null)

    /** The visible window width in days, or null for the whole logged span. */
    val effectiveVisibleDays: Double? get() = pinchVisibleDays ?: chartRange.days

    // MARK: Synced from the log

    private var loggedInjections: List<PKModelDepot.Injection> by mutableStateOf(emptyList())
    private var volumeLoggedInjectionCount by mutableStateOf(0)
    private var preferredEsterID: String? by mutableStateOf(null)
    private var calibrationMeasurements: List<DepotCalibration.Measurement> by mutableStateOf(emptyList())

    /** Whether the log has any qualifying injections for the active analyte. */
    val hasLogHistory: Boolean get() = loggedInjections.isNotEmpty()

    /** One-shot guard: once the source has been defaulted from real data, never override the user's later choice. */
    private var sourceDefaulted = false

    /**
     * One-shot guard: the ester is defaulted before the log syncs, then upgraded
     * once to the ester the user actually logs — after which a manual pick stands.
     */
    private var esterDefaulted = false

    // MARK: Outputs

    var result: DepotCurveResult? by mutableStateOf(null)
        private set
    var calibration: DepotCalibration.Result? by mutableStateOf(null)
        private set

    /** How many injections the curve is drawn from, for the "from your log" line. */
    val injectionCount: Int get() = if (drawsLogOnly) loggedInjections.size else scheduleCount

    private var scheduleCount: Int by mutableStateOf(0)

    /** The selected ester's record, resolved live from the index. */
    val selectedEster: EsterPKRecord? get() = esters.esterPK(selectedEsterID)

    /** The esters available for the active analyte, for the picker. */
    val availableEsters: List<EsterPKRecord> get() = esters.forAnalyte(analyte.key)

    /** The analytes that ship any ester data. */
    val availableAnalytes: List<Analyte>
        get() = esters.analytesWithData().mapNotNull { Analyte.fromKey(it) }

    /** How many mL-logged injections are awaiting a vial strength. */
    val volumeLoggedCount: Int get() = volumeLoggedInjectionCount

    val hasLabs: Boolean get() = calibrationMeasurements.isNotEmpty()
    val calibrationMeasurementCount: Int get() = calibrationMeasurements.size

    /** Whether the current curve is driven by a lab fit (auto on and labs present). */
    val isLabDriven: Boolean get() = autoCalibrateFromLabs && calibration != null

    /** The amplitude multiplier in effect — the lab-fit scale when lab-driven, else the hand-set multiplier. */
    val effectiveMultiplier: Double
        get() {
            val cal = calibration
            if (cal != null && autoCalibrateFromLabs) return cal.scale
            return personalMultiplier
        }

    // MARK: Recompute key

    internal data class RecomputeKey(
        val analyte: Analyte,
        val esterID: String?,
        val doseMg: Double?,
        val intervalDays: Double?,
        val useLogHistory: Boolean,
        val volumeConcentrationMgPerML: Double?,
        val startFromLog: Boolean,
        val startingLevel: Double?,
        val referenceLow: Double?,
        val referenceHigh: Double?,
        val personalMultiplier: Double,
        val autoCalibrateFromLabs: Boolean,
        val fitRates: Boolean,
        val visibleDays: Double?,
        val injections: List<PKModelDepot.Injection>,
        val measurements: List<DepotCalibration.Measurement>,
    )

    val recomputeKey: RecomputeKey
        get() = RecomputeKey(
            analyte = analyte,
            esterID = selectedEsterID,
            doseMg = doseMg,
            intervalDays = intervalDays,
            useLogHistory = useLogHistory,
            volumeConcentrationMgPerML = volumeConcentrationMgPerML,
            startFromLog = startFromLog,
            startingLevel = startingLevel,
            referenceLow = referenceLow,
            referenceHigh = referenceHigh,
            personalMultiplier = personalMultiplier,
            autoCalibrateFromLabs = autoCalibrateFromLabs,
            fitRates = fitRates,
            visibleDays = effectiveVisibleDays,
            injections = loggedInjections,
            measurements = calibrationMeasurements,
        )

    // MARK: Sync from the log

    /**
     * Adopt the qualifying injections and lab results the screen read. A
     * suggested concentration is adopted only while none has been entered, so a
     * value the user typed is never overwritten by one the log happens to hold.
     */
    fun sync(
        injections: List<PKModelDepot.Injection>,
        volumeLoggedCount: Int = 0,
        suggestedConcentration: Double? = null,
        measurements: List<DepotCalibration.Measurement>,
        preferredEsterID: String? = null,
    ) {
        loggedInjections = injections.sortedBy { it.date }
        volumeLoggedInjectionCount = volumeLoggedCount
        if (volumeConcentrationMgPerML == null && suggestedConcentration != null && suggestedConcentration > 0) {
            volumeConcentrationMgPerML = suggestedConcentration
        }
        calibrationMeasurements = measurements
        this.preferredEsterID = preferredEsterID
    }

    /**
     * Read the dose log and the labs into the model — upstream's
     * `syncAndRefresh()`.
     *
     * One entry point rather than the four calls its parts would need, because the
     * order is not free: the stored vial strength is adopted per analyte *before*
     * the log is read (or the mL doses would be counted as unconvertible), the
     * measurements are the ones included in the fit, and the ester default runs
     * after the log is in hand (it is chosen from what the log names).
     */
    fun syncFromLog(entries: List<DoseEntryEntity>, labs: List<LabMeasurement>) {
        onAnalyteChanged()
        val log = logInjections(entries, analyte, volumeConcentrationMgPerML, esters, catalog)
        val measurements = labs
            .filter { it.analyteKey == analyte.key && !it.excludedFromCalibration }
            .map { DepotCalibration.Measurement(date = it.date, value = it.value) }
        sync(
            injections = log.injections,
            volumeLoggedCount = log.volumeLoggedCount,
            suggestedConcentration = log.latestLoggedConcentration,
            measurements = measurements,
            preferredEsterID = dominantEsterID(entries, analyte, esters, catalog),
        )
        selectDefaultsIfNeeded()
    }

    /**
     * Pick the analyte's default ester if none is selected, or the current one no
     * longer belongs to the analyte — the ester the user logs most, else the
     * first.
     *
     * Log-first source default: prefer the log the moment it has data, one-shot,
     * so a later manual toggle stands. Never offer manual-only as "log".
     */
    fun selectDefaultsIfNeeded() {
        val available = availableEsters
        val preferred = preferredEsterID?.let { id -> available.firstOrNull { it.esterID == id }?.esterID }
        val selected = selectedEsterID
        if (selected == null || available.none { it.esterID == selected }) {
            selectedEsterID = preferred ?: available.firstOrNull()?.esterID
            if (preferred != null) esterDefaulted = true
        } else if (!esterDefaulted && preferred != null && preferred != selected) {
            // The log synced after the first default — upgrade once to the ester
            // the user actually logs. A later manual pick sets it too.
            selectedEsterID = preferred
            esterDefaulted = true
        }
        if (hasLogHistory) {
            if (!sourceDefaulted) {
                useLogHistory = true
                sourceDefaulted = true
            }
        } else {
            useLogHistory = false
        }
    }

    // MARK: Compute

    /**
     * Recompute the curve. Never call this from a composable body — it is
     * O(samples × doses) and the whole reason the recompute key exists.
     */
    fun refresh(now: Instant = Instant.now()) {
        val ester = selectedEster
        val population = ester?.parameters
        if (ester == null || population == null) {
            result = null
            calibration = null
            return
        }

        val injections = injectionsForCurve(population, now)
        if (injections.isEmpty()) {
            result = null
            calibration = null
            scheduleCount = 0
            return
        }
        scheduleCount = injections.size

        // Calibrate to the user's labs when allowed; otherwise fall to the manual
        // personal multiplier. The lab fit is amplitude-only for one result, and
        // amplitude + terminal rate for two or more (when rate-fit is on).
        val cal = if (autoCalibrateFromLabs) {
            DepotCalibration.calibrate(population, injections, calibrationMeasurements, fitRates)
        } else {
            null
        }
        calibration = cal

        val params = if (cal != null) {
            population.withK1Scale(cal.k1Scale).withAmplitude(cal.calibratedAmplitude)
        } else {
            population.withAmplitude(population.d * maxOf(0.05, personalMultiplier))
        }

        val (rangeStart, rangeEnd, cycleDays) = window(injections, now)
        val curve = PKModelDepot.depotCurve(injections, rangeStart, rangeEnd, params)

        val band = bandFraction(ester.confidence, calibrated = cal != null)
        // A manual start level is a depot already in the body: it decays from
        // today at the terminal rate, under the scheduled doses.
        val baseline = if (startsFromEnteredLevel) maxOf(0.0, startingLevel ?: 0.0) else 0.0
        val points = curve.map { pt ->
            val days = maxOf(0.0, (pt.date.toEpochMilli() - rangeStart.toEpochMilli()) / 1000.0) / SECONDS_PER_DAY
            val level = pt.concentration + baseline * kotlin.math.exp(-params.k1 * days)
            DepotCurveResult.Point(
                date = pt.date,
                level = level,
                bandLow = maxOf(0.0, level * (1 - band)),
                bandHigh = level * (1 + band),
            )
        }

        // Trough and peak over the last full cycle.
        val cycleStart = rangeEnd.minusMillis((cycleDays * SECONDS_PER_DAY * 1000).toLong())
        val cyclePoints = points.filter { it.date >= cycleStart }
        val trough = cyclePoints.minOfOrNull { it.level } ?: 0.0
        val peak = cyclePoints.maxOfOrNull { it.level } ?: 0.0

        var timeInRange: Double? = null
        val low = referenceLow
        val high = referenceHigh
        if (low != null && high != null && high > low && cyclePoints.isNotEmpty()) {
            val inRange = cyclePoints.count { it.level >= low && it.level <= high }
            timeInRange = inRange.toDouble() / cyclePoints.size
        }

        result = DepotCurveResult(
            points = points,
            injectionDates = injections.map { it.date }.filter { it in rangeStart..rangeEnd },
            range = rangeStart..rangeEnd,
            trough = trough,
            troughLow = maxOf(0.0, trough * (1 - band)),
            troughHigh = trough * (1 + band),
            peak = peak,
            peakLow = maxOf(0.0, peak * (1 - band)),
            peakHigh = peak * (1 + band),
            timeInRange = timeInRange,
        )
    }

    // MARK: Curve inputs

    /** Whether the curve is the log as it stands, rather than a schedule projected forward from today. */
    private val drawsLogOnly: Boolean get() = useLogHistory && hasLogHistory

    /** Manual schedule that carries the log forward: the logged doses stay in the superposition. */
    val continuesFromLog: Boolean get() = !drawsLogOnly && startFromLog && hasLogHistory

    /** Manual schedule from a level the user typed, or from zero. */
    private val startsFromEnteredLevel: Boolean get() = !drawsLogOnly && !continuesFromLog

    private fun injectionsForCurve(
        population: PKModelDepot.DepotParameters,
        now: Instant,
    ): List<PKModelDepot.Injection> =
        if (drawsLogOnly) loggedInjections else synthesizedSchedule(population, now)

    /**
     * How far past today a manual schedule is projected: the visible window, or
     * enough cycles to reach steady state (about five terminal half-lives) for
     * "All".
     */
    private fun projectionDays(population: PKModelDepot.DepotParameters, interval: Double): Double {
        effectiveVisibleDays?.let { return maxOf(it, interval) }
        val terminalHalfLife = kotlin.math.ln(2.0) / maxOf(population.k1, 1e-6) // days
        val cycles = kotlin.math.ceil(5 * terminalHalfLife / interval).toInt().coerceIn(6, 60)
        return cycles * interval
    }

    /**
     * A regular schedule from today forward. Continuing from the log, the logged
     * injections stay in the superposition as the level the schedule starts from,
     * and the first scheduled dose lands one interval after the last logged one —
     * or today, when that is already past. Otherwise the first dose is today and
     * [startingLevel] stands in for the body's history. Nothing before today is
     * invented.
     */
    private fun synthesizedSchedule(
        population: PKModelDepot.DepotParameters,
        now: Instant,
    ): List<PKModelDepot.Injection> {
        val dose = doseMg
        val interval = intervalDays
        if (dose == null || dose <= 0 || interval == null || interval <= 0) return emptyList()
        val stepMillis = (interval * SECONDS_PER_DAY * 1000).toLong()
        val horizon = now.plusMillis(
            (projectionDays(population, interval) * SECONDS_PER_DAY * 1000).toLong(),
        )
        val history = if (continuesFromLog) loggedInjections else emptyList()
        val injections = history.toMutableList()
        var next = history.lastOrNull()?.date?.plusMillis(stepMillis) ?: now
        if (next < now) next = now
        while (next <= horizon) {
            injections += PKModelDepot.Injection(next, dose)
            next = next.plusMillis(stepMillis)
        }
        return injections
    }

    /**
     * The date span to draw and the length of one modelled cycle (days).
     *
     * Drawing the log: the span ends one cycle past the last injection (or now),
     * and starts at the visible window before that — earlier injections still
     * contribute to the curve (the superposition sums all prior doses), they are
     * simply off-screen. Projecting a schedule: the span runs from today to the
     * end of the projection, so the chart opens on the level the schedule starts
     * from.
     */
    private fun window(
        injections: List<PKModelDepot.Injection>,
        now: Instant,
    ): Triple<Instant, Instant, Double> {
        val interval = intervalDays
        if (!drawsLogOnly && interval != null && interval > 0) {
            val last = injections.maxOfOrNull { it.date } ?: now
            return Triple(now, maxOf(last, now), interval)
        }
        return cycleWindow(injections.map { it.date }, effectiveVisibleDays, now)
    }

    // MARK: Preference mirroring

    /** Adopt the persisted preferences into the live state, once, at screen entry. */
    fun adoptStoredPreferences() {
        personalMultiplier = preferences.personalMultiplier
        autoCalibrateFromLabs = preferences.autoCalibrate
        fitRates = preferences.fitRates
        volumeConcentrationMgPerML = preferences.volumeConcentration(analyte)
    }

    fun persistMultiplier(value: Double) {
        personalMultiplier = value
        preferences.personalMultiplier = value
    }

    fun persistAutoCalibrate(value: Boolean) {
        autoCalibrateFromLabs = value
        preferences.autoCalibrate = value
    }

    fun persistFitRates(value: Boolean) {
        fitRates = value
        preferences.fitRates = value
    }

    fun persistVolumeConcentration(value: Double?) {
        volumeConcentrationMgPerML = value
        preferences.setVolumeConcentration(analyte, value)
    }

    /** Adopt [analyte]'s stored vial strength, which is per-analyte. */
    fun onAnalyteChanged() {
        volumeConcentrationMgPerML = preferences.volumeConcentration(analyte)
    }
}

// MARK: - The insight's state

/**
 * The retrospective serum-level model behind the Hormone Levels insight.
 *
 * Ported from `HormoneLevelsModel.swift` (215 lines). Unlike the prediction tool
 * it never synthesises a schedule: it reads the dose log grouped per ester, sums
 * each ester's own depot curve into the serum total, keeps the per-ester
 * contributions for the "assumed depot levels" display, and calibrates the whole
 * sum to the user's labs.
 */
@Stable
internal class HormoneLevelsModel {

    var analyte by mutableStateOf(Analyte.ESTRADIOL)
    var referenceLow by mutableStateOf<Double?>(null)
    var referenceHigh by mutableStateOf<Double?>(null)

    var personalMultiplier by mutableStateOf(1.0)
    var autoCalibrateFromLabs by mutableStateOf(true)
    var fitRates by mutableStateOf(true)

    var chartRange by mutableStateOf(ChartRange.QUARTER)
    var pinchVisibleDays by mutableStateOf<Double?>(null)

    val effectiveVisibleDays: Double? get() = pinchVisibleDays ?: chartRange.days

    private var grouped by mutableStateOf(HormoneLevelsLog.Grouped())
    private var measurements by mutableStateOf<List<DepotCalibration.Measurement>>(emptyList())

    val hasLabs: Boolean get() = measurements.isNotEmpty()
    val calibrationMeasurementCount: Int get() = measurements.size
    val isLabDriven: Boolean get() = autoCalibrateFromLabs && calibration != null

    val effectiveMultiplier: Double
        get() {
            val cal = calibration
            if (cal != null && autoCalibrateFromLabs) return cal.scale
            return personalMultiplier
        }

    // MARK: Outputs

    /** The summed serum estimate, with its band. */
    var result: DepotCurveResult? by mutableStateOf(null)
        private set

    /** One contribution series per modelable ester logged — the "assumed depot levels" breakdown. */
    var perEster: List<Pair<EsterPKRecord, List<DepotCurveResult.Point>>> by mutableStateOf(emptyList())
        private set

    /** Catalog-only esters logged (no curve) — drawn as labelled notes, never fabricated. */
    var catalogMarkers: List<HormoneLevelsLog.Marker> by mutableStateOf(emptyList())
        private set

    var calibration: DepotCalibration.Result? by mutableStateOf(null)
        private set

    // MARK: Recompute key

    internal data class RecomputeKey(
        val analyte: Analyte,
        val referenceLow: Double?,
        val referenceHigh: Double?,
        val personalMultiplier: Double,
        val autoCalibrateFromLabs: Boolean,
        val fitRates: Boolean,
        val visibleDays: Double?,
        val groups: List<HormoneLevelsLog.EsterGroup>,
        val markers: List<HormoneLevelsLog.Marker>,
        val measurements: List<DepotCalibration.Measurement>,
    )

    val recomputeKey: RecomputeKey
        get() = RecomputeKey(
            analyte = analyte,
            referenceLow = referenceLow,
            referenceHigh = referenceHigh,
            personalMultiplier = personalMultiplier,
            autoCalibrateFromLabs = autoCalibrateFromLabs,
            fitRates = fitRates,
            visibleDays = effectiveVisibleDays,
            groups = grouped.esterGroups,
            markers = grouped.catalogOnlyMarkers,
            measurements = measurements,
        )

    fun sync(grouped: HormoneLevelsLog.Grouped, measurements: List<DepotCalibration.Measurement>) {
        this.grouped = grouped
        this.measurements = measurements
    }

    // MARK: Compute

    fun refresh(now: Instant = Instant.now()) {
        catalogMarkers = grouped.catalogOnlyMarkers
        val groups = grouped.esterGroups.filter { it.injections.isNotEmpty() && it.ester.parameters != null }
        if (groups.isEmpty()) {
            result = null
            perEster = emptyList()
            calibration = null
            return
        }

        val allDates = grouped.allInjections.map { it.date }
        val (rangeStart, rangeEnd, cycleDays) = cycleWindow(allDates, effectiveVisibleDays, now)

        // Population contributions, each ester keeping its own parameters.
        val populationContributions = groups.mapNotNull { group ->
            group.ester.parameters?.let { PKModelDepot.DepotContribution(group.injections, it) }
        }

        // Global amplitude + rate fit against the summed curve: labs cannot resolve
        // per-ester rates from a mixed log, so one scale is fitted and applied
        // uniformly to every ester.
        val cal = if (autoCalibrateFromLabs) {
            DepotCalibration.calibrateSummed(populationContributions, measurements, fitRates)
        } else {
            null
        }
        calibration = cal
        val scale = cal?.scale ?: maxOf(0.05, personalMultiplier)
        val k1Scale = cal?.k1Scale ?: 1.0

        val calibrated = groups.mapNotNull { group ->
            val population = group.ester.parameters ?: return@mapNotNull null
            val params = population.withK1Scale(k1Scale).withAmplitude(population.d * scale)
            group.ester to PKModelDepot.DepotContribution(group.injections, params)
        }

        val summed = PKModelDepot.depotCurveSummed(
            contributions = calibrated.map { it.second },
            from = rangeStart,
            to = rangeEnd,
        )

        // The serum total's band is the widest of the mix — an honest uncertainty
        // when esters of different confidence combine.
        val worstConfidence = calibrated.map { it.first.confidence }
            .minByOrNull { confidenceRank(it) } ?: "low"
        val totalBand = bandFraction(worstConfidence, calibrated = cal != null)
        val totalPoints = summed.total.map { point(it.date, it.concentration, totalBand) }

        perEster = calibrated.mapIndexed { index, (ester, _) ->
            val band = bandFraction(ester.confidence, calibrated = cal != null)
            ester to summed.contributions[index].map { point(it.date, it.concentration, band) }
        }

        val cycleStart = rangeEnd.minusMillis((cycleDays * SECONDS_PER_DAY * 1000).toLong())
        val cyclePoints = totalPoints.filter { it.date >= cycleStart }
        val trough = cyclePoints.minOfOrNull { it.level } ?: 0.0
        val peak = cyclePoints.maxOfOrNull { it.level } ?: 0.0

        var timeInRange: Double? = null
        val low = referenceLow
        val high = referenceHigh
        if (low != null && high != null && high > low && cyclePoints.isNotEmpty()) {
            timeInRange = cyclePoints.count { it.level >= low && it.level <= high }.toDouble() / cyclePoints.size
        }

        result = DepotCurveResult(
            points = totalPoints,
            injectionDates = allDates.filter { it in rangeStart..rangeEnd },
            range = rangeStart..rangeEnd,
            trough = trough,
            troughLow = maxOf(0.0, trough * (1 - totalBand)),
            troughHigh = trough * (1 + totalBand),
            peak = peak,
            peakLow = maxOf(0.0, peak * (1 - totalBand)),
            peakHigh = peak * (1 + totalBand),
            timeInRange = timeInRange,
        )
    }

    private fun point(date: Instant, level: Double, band: Double) = DepotCurveResult.Point(
        date = date,
        level = level,
        bandLow = maxOf(0.0, level * (1 - band)),
        bandHigh = level * (1 + band),
    )
}
