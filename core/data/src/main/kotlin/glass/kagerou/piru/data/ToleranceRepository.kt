package glass.kagerou.piru.data

import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.data.entity.ToleranceStateEntity
import glass.kagerou.piru.engine.PharmacologySource
import glass.kagerou.piru.engine.ReceptorClasses
import glass.kagerou.piru.engine.ToleranceReplay
import glass.kagerou.piru.engine.ToleranceSignature
import glass.kagerou.piru.model.ConfidenceTier
import glass.kagerou.piru.model.DoseUnit
import java.time.Instant
import java.util.Date
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The tolerance tool's side of the app: read the dose log, replay it, cache the
 * result, and expose it as state.
 *
 * Ported from `ToleranceStore`. What is left here after the engine took the
 * arithmetic is the I/O — the log read, the catalog resolve, the coroutine dispatch,
 * the cache gate, and the write — which is also the part that cannot be tested
 * without a device.
 *
 * ## Where the work runs
 * Both the catalog and the store are blocking reads, so both happen on
 * [dispatcher]. The state flows are updated from there, which is safe because a
 * `MutableStateFlow` is, and it keeps a year-long integration off the main thread —
 * the failure that matters here is not a janky frame but a frozen launch.
 *
 * ## The gate, and why there is no way around it
 * [recompute] returns early when the log, the weight and the current hour are
 * unchanged, which is what makes navigating back into the tool free. There is
 * deliberately **no force flag**: the signature covers every input a user action can
 * change — logging a dose moves the log, editing the profile moves the weight — so
 * a bypass would only be a way to spend a second re-deriving identical numbers.
 */
class ToleranceRepository(
    private val database: PiruDatabase,
    private val pharmacology: PharmacologySource,
    /** The user's effective body weight, read at each recompute rather than captured. */
    private val weightKg: () -> Double,
    private val now: () -> Instant = Instant::now,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    private val _states = MutableStateFlow<Map<ReceptorClasses.ReceptorClass, ToleranceReplay.ClassTolerance>>(emptyMap())

    /** One card per driven class. Empty until the first replay or cache load. */
    val states: StateFlow<Map<ReceptorClasses.ReceptorClass, ToleranceReplay.ClassTolerance>> = _states.asStateFlow()

    private val _incompleteData = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Substances the log holds but nothing can model.
     *
     * Exposed because "no tolerance predicted" and "this substance has no tolerance"
     * look identical on a card, and only one of them is worth telling the user about.
     */
    val incompleteData: StateFlow<Set<String>> = _incompleteData.asStateFlow()

    private var lastSignature: String? = null

    /**
     * Load the persisted cache so a launch has something to draw before the first
     * replay finishes.
     *
     * The cached rows carry only the four layers and the chronicity accumulator. The
     * rest — the representative occupancy, the sub-targets, the driver names, the real
     * confidence, the safety endpoint — is recomputed on the first replay, so they are
     * warmed with neutral placeholders rather than guessed at. A caller must not treat
     * a loaded card as a computed one; [recompute] overwrites them promptly.
     */
    suspend fun loadCached() {
        val rows = database.toleranceStateDao().all()
        val loaded = LinkedHashMap<ReceptorClasses.ReceptorClass, ToleranceReplay.ClassTolerance>()
        for (row in rows) {
            val cls = ReceptorClasses.ReceptorClass.fromWire(row.target) ?: continue
            loaded[cls] = ToleranceReplay.ClassTolerance(
                receptorClass = cls,
                sAcute = row.sAcute,
                sAdaptive = row.sAdaptive,
                sDeep = row.sDeep,
                sSynthesis = row.sSynthesis,
                chronicExposure = row.chronicExposure,
                representativeOccupancy = 0.5,
                occupancyNow = 0.0,
                confidence = ConfidenceTier.UNVERIFIED,
                subTargets = emptyList(),
                contributors = emptyList(),
                safetyShiftFactor = null,
                safetyEndpointKind = null,
            )
        }
        // The signature is deliberately left unset: a loaded cache is not a computed
        // result, so the first real replay must not be skipped by a gate that has
        // never seen this process's inputs.
        _states.value = loaded
    }

    /**
     * Replay the log and refresh the cache.
     *
     * Returns whether the replay actually ran, so a caller can tell a no-op from work
     * — which is what a "refreshing…" affordance needs to avoid flashing.
     */
    suspend fun recompute(): Boolean {
        val instant = now()
        val nowMinutes = instant.toEpochMilli() / 60_000.0
        val weight = weightKg()

        val log = database.doseEntryDao().all().mapNotNull { it.toSimDose() }

        val signature = ToleranceSignature.of(log, weight, nowMinutes)
        if (signature == lastSignature) return false

        // The representatives are resolved alongside the logged names, never instead
        // of them: the missing-PK fallback models a PK-less substance as its class
        // representative, and that representative is usually not in the log at all.
        val params = withContext(dispatcher) {
            val representatives = pharmacology.classRepresentativeNames()
            pharmacology.pharmacologyForLog(log.map { it.substance }.toSet() + representatives)
        }
        val cards = withContext(dispatcher) {
            ToleranceReplay.simulate(log, params, nowMinutes, weight)
        }
        val incomplete = withContext(dispatcher) {
            ToleranceReplay.incompleteData(log, params, ToleranceReplay.representativeIndex(params))
        }

        _states.value = cards
        _incompleteData.value = incomplete
        // Recorded only once the work has actually landed.
        //
        // Setting it up front would collapse two concurrent calls into one, which
        // is tempting — but it also means a replay that *throws* has already
        // claimed its inputs, and every later call with the same log is then
        // skipped forever. The gate would be permanently shut by a single
        // transient failure, and the symptom is a tolerance screen that never
        // updates again.
        lastSignature = signature

        // The whole table is rewritten each time, so a class the log no longer drives
        // is reset to naïve rather than left behind — see `ToleranceStateDao.persist`.
        database.toleranceStateDao().persist(
            cards.values.map { it.toRow(instant) },
            instant.toEpochMilli(),
        )
        return true
    }

    /**
     * A logged dose as the replay sees it, or null when it cannot be replayed.
     *
     * Two ordinary reasons to drop one, both of which are answers rather than errors:
     * a dose of unknown amount has no concentration to compute, and a dose in a unit
     * that is not a mass — millilitres, IU — has no milligram equivalent. Upstream
     * drops both at the same point, for the same reason.
     */
    private fun DoseEntryEntity.toSimDose(): ToleranceReplay.SimDose? {
        if (isUnknownDose) return null
        val mg = DoseUnit.convert(amount, from = unit, to = "mg") ?: return null
        return ToleranceReplay.SimDose(
            substance = substance,
            amountMg = mg,
            timestampMinutes = timestamp.toInstant().toEpochMilli() / 60_000.0,
        )
    }

    private fun ToleranceReplay.ClassTolerance.toRow(instant: Instant) = ToleranceStateEntity(
        target = receptorClass.wireValue,
        sAcute = sAcute,
        sAdaptive = sAdaptive,
        sDeep = sDeep,
        sSynthesis = sSynthesis,
        chronicExposure = chronicExposure,
        // Stamped by the DAO on write; carried here so the entity is complete.
        lastUpdated = Date(instant.toEpochMilli()),
    )
}
