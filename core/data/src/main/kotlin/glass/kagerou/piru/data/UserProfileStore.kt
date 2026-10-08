package glass.kagerou.piru.data

import glass.kagerou.piru.data.entity.UserProfileRecordEntity
import glass.kagerou.piru.engine.PKModel

/**
 * The one profile row, and the body weight every model is scaled by.
 *
 * Ported from `UserProfileStore`.
 *
 * ## Why this is the load-bearing piece and not a settings detail
 * `bodyWeightKg` reaches `PKModel.concentrationAbsolute` (`Vd = vdPerKg · weight`),
 * the alcohol zero-order curve, and every tolerance replay. Before this existed
 * the app took the number during onboarding, wrote it to a preferences file
 * **that nothing read**, and computed every figure for a 60 kg person. Asking a
 * user their weight and then ignoring it is worse than not asking.
 *
 * ## Read synchronously, write asynchronously
 * The repositories take `weightKg: () -> Double` and call it from inside a
 * calculation, so the read has to be synchronous and cannot touch the database.
 * [weightKgOrDefault] therefore answers from a cache that [load] fills, and the
 * default is the engine's own reference weight rather than a guess.
 *
 * A first frame that beats [load] is **self-correcting**, and that is worth
 * knowing rather than engineering around: `ToleranceSignature.of` includes the
 * weight, so when the cache warms the signature changes and the next pass
 * recomputes. The cost of the race is one stale reading on a cold start, not a
 * wrong answer that sticks.
 */
class UserProfileStore(private val database: PiruDatabase) {

    /**
     * Where the number came from.
     *
     * `HEALTH_CONNECT` and `MANUAL` are kept apart because they behave
     * differently over time: a reading from the phone is refreshed when the user
     * opens the screen, while a hand-typed number is theirs and is never
     * overwritten. Storing the provenance is the only way to tell them apart
     * later — the kilogram value alone cannot say which one it was.
     */
    enum class WeightSource(val wireValue: String) {
        /** Nothing entered. The engine's reference weight stands in. */
        ESTIMATED("estimated"),

        /** Typed by the user. Never overwritten by a sync. */
        MANUAL("manual"),

        /** Read from Health Connect. Refreshed when a newer reading exists. */
        HEALTH_CONNECT("healthKit"),
        ;

        companion object {
            fun fromWire(value: String?): WeightSource =
                entries.firstOrNull { it.wireValue == value } ?: ESTIMATED
        }
    }

    /**
     * The row, cached for the synchronous read below.
     *
     * `@Volatile` because it is written on whichever thread ran [load] and read
     * from the calculation paths, which are not the same one.
     */
    @Volatile
    private var cached: UserProfileRecordEntity? = null

    /** Read the row, creating it on first call, and warm the cache. */
    suspend fun load(): UserProfileRecordEntity {
        val row = database.userProfileDao().edit { null } ?: UserProfileDao.DEFAULTS
        cached = row
        return row
    }

    /** The cached row, or the defaults. Does not touch the database. */
    fun snapshot(): UserProfileRecordEntity = cached ?: UserProfileDao.DEFAULTS

    /**
     * The weight to scale a model by — the user's own when they gave one, else
     * the engine's reference.
     *
     * Deliberately `PKModel.REFERENCE_BODY_WEIGHT_KG` rather than a number of its
     * own: the engine's calibration constants are quoted against that anchor (the
     * alcohol `Vmax` of 95 mg/min is a whole-body figure normed to 60 kg), so
     * substituting a different default here would silently recalibrate every
     * curve.
     */
    fun weightKgOrDefault(): Double =
        snapshot().bodyWeightKg?.takeIf { it > 0 } ?: PKModel.REFERENCE_BODY_WEIGHT_KG

    /** What the stored weight's provenance is, for a screen that offers to refresh it. */
    fun weightSource(): WeightSource = WeightSource.fromWire(snapshot().weightSourceRaw)

    /** Record a weight. See [WeightSource] for why the source travels with it. */
    suspend fun setWeight(kg: Double, source: WeightSource): UserProfileRecordEntity =
        edit { it.copy(bodyWeightKg = kg, weightSourceRaw = source.wireValue) }

    /**
     * Store a Health Connect reading, unless the user typed their own.
     *
     * The one asymmetry in this class, and it is deliberate: a scale is more
     * accurate than an estimate, so a reading should win — but not over a number
     * the user entered by hand, which they may have done precisely because the
     * phone's figure is wrong for them.
     */
    suspend fun syncWeightFromHealthConnect(kg: Double): UserProfileRecordEntity? {
        if (weightSource() == WeightSource.MANUAL) return null
        return setWeight(kg, WeightSource.HEALTH_CONNECT)
    }

    /** The disclosure tier wire value. See the entity — the string is persisted and must not be renamed. */
    suspend fun setDisclosureTier(rawValue: String): UserProfileRecordEntity =
        edit { it.copy(disclosureTierRaw = rawValue) }

    /**
     * How much detail the user asked for.
     *
     * The getter this class spent a while without: onboarding collected the answer, the value
     * was persisted, exported, imported and restored, and **nothing could read it back**, so
     * the question had no effect and iOS's tier-driven detail composition had no counterpart.
     * A preference with a writer and no reader is indistinguishable from a bug in the UI that
     * offers it, which is what it was.
     *
     * Unknown or absent values resolve the way the read layer resolves them — see
     * [DisclosureTier.fromWire] — so an older row or a file from a build that spelled the tiers
     * differently still yields a usable answer rather than an error.
     */
    fun disclosureTier(): DisclosureTier = DisclosureTier.fromWire(snapshot().disclosureTierRaw)

    /** Whether the user has ever answered the tier question, as opposed to holding the default. */
    fun hasDisclosureTier(): Boolean = snapshot().disclosureTierRaw != null

    suspend fun setGrapefruitLogging(enabled: Boolean): UserProfileRecordEntity =
        edit { it.copy(grapefruitLoggingEnabled = enabled) }

    suspend fun setAldh2Deficient(value: Boolean): UserProfileRecordEntity =
        edit { it.copy(aldh2Deficient = value) }

    /**
     * Clear the profile after the user deletes everything.
     *
     * The row goes entirely rather than being reset, so the next [load] recreates
     * it with the defaults. Writing defaults over the top would leave a row whose
     * `row_id` had survived a deletion, which is the kind of detail that makes a
     * "delete everything" that did not quite.
     */
    suspend fun resetAfterDeletion() {
        database.userProfileDao().deleteAll()
        cached = null
    }

    private suspend fun edit(change: (UserProfileRecordEntity) -> UserProfileRecordEntity?): UserProfileRecordEntity =
        database.userProfileDao().edit(change).also { cached = it }
}
