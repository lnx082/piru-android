package glass.kagerou.piru.health

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import glass.kagerou.piru.engine.BloodPressureReading
import glass.kagerou.piru.engine.HeartRateSample
import glass.kagerou.piru.engine.SessionVitals
import java.time.Instant

/**
 * Reading the phone's health data, from **Health Connect**.
 *
 * Ported from `Piru/Utilities/HealthKitVitals.swift` and `HealthKitBodyMass.swift`.
 *
 * ## What Health Connect actually is, since it decides the whole shape of this file
 * It is not a link to a band manufacturer's app. It is a **store inside the
 * operating system** that any app may read or write with per-type permission, so
 * a reading only appears here if something else put it there — a watch app, a
 * scale, a fitness app that chose to write. Xiaomi Health's numbers reach Piru
 * only if Xiaomi Health writes to Health Connect, and nothing in this file can
 * change that.
 *
 * Two consequences worth stating plainly:
 * - **It is read-only here.** Piru never writes a vital. The permission set below
 *   contains no write permission, and the request passes no write types.
 * - **Empty is a legitimate answer.** No permission, no data, and "you own no
 *   device that measures this" are indistinguishable once the read returns
 *   nothing, and the UI shows nothing in all three cases rather than an error the
 *   user cannot act on. That is also what the iOS build does.
 *
 * ## Availability, and why it is not a boolean
 * On Android 14 and up Health Connect is part of the system. Below that it is an
 * app the user may not have installed. [Availability] carries the difference,
 * because the two need different words: one is "install this", the other is
 * "your phone does not have it".
 *
 * ## One prompt, and only one
 * [requiredPermissions] is requested in a single pass, matching the iOS build's
 * single Health sheet. Splitting it into a prompt per data type would ask the
 * same question six times.
 *
 * ## The trap the iOS build documents, and why it is absent here
 * HealthKit refuses an authorization request that names the blood-pressure
 * *correlation* type rather than its two component types, and the iOS header
 * records a TestFlight crash from exactly that. Health Connect has no
 * correlation/component split — a blood-pressure reading is one
 * [BloodPressureRecord] carrying both numbers — so the trap has no equivalent
 * here. It is noted so nobody "restores" the workaround.
 */
class HealthConnectVitals(private val context: Context) {

    /** What the platform can do, rather than whether it can. */
    enum class Availability {
        /** Present and usable — the OS ships it (API 34+) or the app is installed. */
        AVAILABLE,

        /** The user has an old Health Connect app that needs updating before this API works. */
        PROVIDER_UPDATE_REQUIRED,

        /** Not present. Below API 34 with no Health Connect app installed. */
        NOT_INSTALLED,
    }

    fun availability(): Availability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> Availability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.PROVIDER_UPDATE_REQUIRED
        else -> Availability.NOT_INSTALLED
    }

    private fun clientOrNull(): HealthConnectClient? =
        if (availability() == Availability.AVAILABLE) HealthConnectClient.getOrCreate(context) else null

    /**
     * The read permissions this app asks for.
     *
     * Exercise sessions are in the set because heart rate during a workout is
     * marked rather than excluded — see [read]. Weight is here rather than in the
     * onboarding flow alone because the same grant covers the profile's body
     * weight, which every PK calculation is scaled by.
     */
    val requiredPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(BloodPressureRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
    )

    /**
     * The contract that opens Health Connect's own permission screen.
     *
     * Note that this is Health Connect's UI, not a system dialog — the user sees
     * the app's name and every data type in one list, and can revoke any of them
     * there afterwards.
     */
    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    /** Which of [requiredPermissions] are currently granted. Empty when Health Connect is absent. */
    suspend fun grantedPermissions(): Set<String> {
        val client = clientOrNull() ?: return emptySet()
        return runCatching { client.permissionController.getGrantedPermissions() }
            .getOrDefault(emptySet())
            .intersect(requiredPermissions)
    }

    /**
     * The vitals in a window, for one session.
     *
     * ## Training is marked, not removed
     * A heart rate sample taken mid-run is still the user's heart rate, so it
     * stays in the series and is flagged instead. The engine is what decides to
     * exclude flagged samples, and it does so in only one place — the per-dose
     * response — because a dose's effect is what is being measured there, and a
     * workout is a competing explanation. The session summary keeps them, because
     * the session *is* the workout as much as the dose.
     *
     * ## An exception is an empty result
     * Every read is wrapped. A revoked permission mid-session, a provider that
     * dies, a malformed record: all of them surface as "nothing to show", which
     * is the same thing the user sees when there is genuinely nothing. Inventing
     * an error state here would put a red box on the timeline for something the
     * user can neither diagnose nor fix.
     */
    suspend fun read(from: Instant, to: Instant): SessionVitals {
        val client = clientOrNull() ?: return EMPTY_VITALS
        if (!to.isAfter(from)) return EMPTY_VITALS

        return runCatching {
            val heartRate = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                ),
            ).records
                .flatMap { record -> record.samples.map { it.time to it.beatsPerMinute.toDouble() } }
                .sortedBy { it.first }

            val workouts = client.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    // No strict-start filter, deliberately, matching the iOS
                    // build: a run that began before the window still covers
                    // samples inside it, and excluding it would mark those
                    // samples as resting.
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                ),
            ).records.map { it.startTime to it.endTime }

            val bloodPressure = client.readRecords(
                ReadRecordsRequest(
                    recordType = BloodPressureRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                ),
            ).records
                .sortedBy { it.time }
                // Health Connect carries a blood pressure as a `Pressure` with its
                // own unit, unlike HealthKit's bare numbers. Reading the mmHg value
                // rather than the raw magnitude is what keeps a record written in
                // another unit from being plotted as though it were millimetres of
                // mercury.
                .map {
                    BloodPressureReading(
                        date = it.time,
                        systolic = it.systolic.inMillimetersOfMercury,
                        diastolic = it.diastolic.inMillimetersOfMercury,
                    )
                }

            SessionVitals(
                heartRate = heartRate.map { (time, bpm) ->
                    HeartRateSample(
                        date = time,
                        bpm = bpm,
                        isWorkout = workouts.any { (start, end) -> time >= start && time <= end },
                    )
                },
                bloodPressure = bloodPressure,
                restingHeartRate = latestRestingHeartRate(client),
            )
        }.getOrDefault(EMPTY_VITALS)
    }

    /**
     * The most recent resting heart rate in the whole store — not in the window.
     *
     * Resting heart rate is a slow baseline, measured in the morning and often
     * not at all on a given day, so restricting it to a session would mostly
     * return null. The iOS build reads it the same way, with no predicate.
     */
    private suspend fun latestRestingHeartRate(client: HealthConnectClient): Double? = runCatching {
        client.readRecords(
            ReadRecordsRequest(
                recordType = RestingHeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(Instant.EPOCH, Instant.now()),
                ascendingOrder = false,
                pageSize = 1,
            ),
        ).records.firstOrNull()?.beatsPerMinute?.toDouble()
    }.getOrNull()

    /**
     * The latest recorded body weight in kilograms, or null.
     *
     * A weight is a population-scale number that moves over weeks, so the newest
     * reading is the right one to scale a PK model by — and it is the only one
     * this reads.
     */
    suspend fun latestBodyMassKg(): Double? {
        val client = clientOrNull() ?: return null
        return runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = WeightRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(Instant.EPOCH, Instant.now()),
                    ascendingOrder = false,
                    pageSize = 1,
                ),
            ).records.firstOrNull()?.weight?.inKilograms
        }.getOrNull()
    }

    private companion object {
        val EMPTY_VITALS = SessionVitals(heartRate = emptyList(), bloodPressure = emptyList(), restingHeartRate = null)
    }
}
