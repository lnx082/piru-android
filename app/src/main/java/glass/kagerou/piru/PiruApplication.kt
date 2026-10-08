package glass.kagerou.piru

import android.app.Application
import android.util.Log
import glass.kagerou.piru.data.PiruDatabase
import glass.kagerou.piru.data.RoutineOccurrenceService
import glass.kagerou.piru.data.SessionRepository
import glass.kagerou.piru.data.SubstancePalette
import glass.kagerou.piru.data.ToleranceRepository
import glass.kagerou.piru.data.UserProfileStore
import glass.kagerou.piru.data.catalog.AndroidSubstanceDb
import glass.kagerou.piru.data.export.DataExportImport
import glass.kagerou.piru.data.catalog.SubstanceCatalogInstaller
import glass.kagerou.piru.health.HealthConnectVitals
import glass.kagerou.piru.notifications.MedReminderScheduler
import glass.kagerou.piru.notifications.NotificationPreferencesStore
import glass.kagerou.piru.notifications.PiruNotifications
import glass.kagerou.piru.substance.ContentLanguage
import glass.kagerou.piru.widget.MedWidgetRefresh
import glass.kagerou.piru.substance.DbSubstanceCatalog
import glass.kagerou.piru.ui.tools.LabMeasurementStore
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The app graph, such as it is.
 *
 * Two stores, lazily opened and held for the process's life. Upstream reaches both
 * through singletons (`SubstanceStore.shared`, `UserProfileStore.shared`) because
 * a `nonisolated` view has nothing else to reach for; here they are properties of
 * the application, which is the same lifetime with a visible edge — a screen asks
 * for what it needs instead of finding it.
 *
 * ## Why the catalog is a `suspend` call
 * It lives inside the APK as an 18 MB asset and is copied out on first launch,
 * then opened and verified against the packaged manifest. That is disk work, so
 * it cannot happen in a property initializer — a lazy `val` would run it on
 * whichever thread first touched it, which is the composition thread.
 */
open class PiruApplication : Application() {

    /**
     * The user's own store. Opening it is cheap; Room connects on the first query.
     *
     * `open` so a test application can hand out an in-memory store. That is the point of
     * the seam: the JVM screen specs get the real Room, the real DAOs and the real generated
     * SQL, with a different backing file and nothing else changed — see
     * `PiruTestApplication`, which is the only override.
     */
    open val database: PiruDatabase by lazy { PiruDatabase.open(this) }

    private val catalogMutex = Mutex()
    private var catalog: DbSubstanceCatalog? = null
    private var catalogHandle: AndroidSubstanceDb? = null

    private var repository: ToleranceRepository? = null
    private var sessions: SessionRepository? = null
    private var preferences: NotificationPreferencesStore? = null
    private var userProfile: UserProfileStore? = null
    private var routineOccurrences: RoutineOccurrenceService? = null

    /**
     * A scope for work that must outlive any one screen.
     *
     * `SupervisorJob` so one failed reschedule does not cancel the rest — a
     * notification pass that dies on a malformed row must not also take out the
     * next one, and there is no caller left to report the failure to.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Left `open` for the test application, which must not boot the reminder scheduler — a
     * Kotlin member is final by default, so an override needs saying, and a screen spec that
     * started `WorkManager` would fail on start-up rather than on the screen.
     */
    override fun onCreate() {
        super.onCreate()

        // Channels first, and synchronously: a notification posted before its
        // channel exists is dropped silently on API 26+, which is the worst
        // possible failure for a reminder.
        PiruNotifications.registerChannels(this)

        // Warm the preference mirror and reconcile the reminders.
        //
        // `allows(...)` reads SharedPreferences synchronously so a receiver or a
        // worker can gate without a database round-trip, and that mirror has to be
        // populated before the first one runs. This also seeds the single
        // preference row on a fresh install, which is otherwise created lazily by
        // whichever screen happens to open first — and may never be.
        appScope.launch {
            // Before anything computes: `weightKgOrDefault` answers from the cache
            // this fills, and every PK figure in the app is scaled by it.
            // The lab results that were JSON in a preferences file before v3, moved
            // into their table. Runs before any screen can read labs, and is a no-op
            // once it has run: it clears the file as it reads it.
            runCatching { LabMeasurementStore(database).importLegacyRows(this@PiruApplication) }
                .onFailure { Log.w(TAG, "Lab-measurement backfill failed", it) }
            profile().load()
            notificationPreferences().load()
            // After the profile, never before: the sync below decides whether a
            // reading may replace what is stored by asking *where* the stored
            // weight came from, and an unloaded profile reports no source at all.
            syncBodyWeightFromHealth()
            MedReminderScheduler.enqueueReconcile(this@PiruApplication)
            MedReminderScheduler.scheduleRollForward(this@PiruApplication)
            // The home-screen widget's own refresh timer is a persisted work request,
            // which a force-stop or a fresh install will not have. Re-arming it on every
            // launch — cheap, and a no-op when no widget is placed — is what keeps a
            // placed widget from sitting on a stale day after the app is reinstalled.
            MedWidgetRefresh.afterWrite(this@PiruApplication)
        }
    }

    /**
     * Adopt the phone's newest body weight, if it has one and the user has not
     * typed their own.
     *
     * Ported from `HealthKitBodyMass.syncLatest()`, which the iOS app calls on
     * launch. Same shape, same silence: this never prompts — the grant belongs to
     * the single combined Health request on the health screen and in onboarding —
     * so it is safe to call unconditionally, including for a user who has never
     * opened that screen. With no grant, no Health Connect, or no weight record the
     * read returns nothing and this does nothing.
     *
     * ## Why it is worth doing without being asked
     * Every PK curve in the app is scaled by this one number, and a bathroom scale
     * is a better source for it than a figure someone typed once and forgot. The
     * stored source is what keeps that from being rude: a weight the user entered
     * by hand is never overwritten (see `UserProfileStore.syncWeightFromHealthConnect`),
     * so the phone only ever wins against another phone reading or the default.
     *
     * @return the weight now in use, or null when nothing changed.
     */
    suspend fun syncBodyWeightFromHealth(): Double? {
        val kg = HealthConnectVitals(this).latestBodyMassKg() ?: return null
        val stored = profile().syncWeightFromHealthConnect(kg) ?: return null
        // The number changed, so every memoized replay computed against the old one
        // is stale — the same invalidation a weight typed on the health screen
        // triggers through `onChanged`.
        invalidateRepositoryCaches()
        return stored.bodyWeightKg
    }

    /**
     * The bundled substance catalog, installed and opened on first use.
     *
     * Memoized behind a mutex rather than a lazy `val` because the install is
     * suspending and two screens can ask for it at once — the journal and the
     * library both do, on the first frame after launch.
     */
    open suspend fun catalog(): DbSubstanceCatalog = catalogMutex.withLock {
        catalog ?: run {
            val file: File = SubstanceCatalogInstaller.install(this)
            val db = AndroidSubstanceDb.open(file)
            catalogHandle = db
            val order = db.query("SELECT slug FROM sources ORDER BY default_priority, slug")
                .mapNotNull { it.string("slug") }
            DbSubstanceCatalog.open(
                db = db,
                order = order,
                // Derived from the user's own language rather than pinned to English. It was
                // `ContentLanguage.EN` literally, which made the catalogue's 1,606 localized
                // names across 605 substances unreachable: `Substance.localizedName` resolved
                // through a null language and came back null for everything, so a Chinese
                // reader got English titles and no way to ask for the others.
                language = contentLanguage(),
                usesEnglishNames = usesEnglishNames,
            ).also { catalog = it }
        }
    }

    /**
     * The language the catalogue's prose resolves in.
     *
     * The app's own locale, which is what Android's per-app language picker sets — not the
     * device's. Someone reading a dose journal in Chinese should not have to switch their whole
     * phone to Chinese to get Chinese substance descriptions, and `locales_config.xml` already
     * declares both languages for that reason.
     */
    private fun contentLanguage(): ContentLanguage =
        ContentLanguage.fromLocalization(
            // `Resources` carries the per-app language override that `locales_config.xml`
            // declares; a bare `Configuration` would not.
            resources.configuration.locales[0]?.language ?: "en",
        )

    /**
     * Whether substance names are shown in English regardless of the catalogue's language.
     *
     * Held in memory only: it is a display preference, and the catalogue has to be rebuilt when
     * it changes anyway, so persisting it separately would create a second source of truth for
     * the same answer. It resets to false on a cold start, which matches the shipped default.
     */
    var usesEnglishNames: Boolean = false
        private set

    /**
     * Switch the name mode, and rebuild the catalogue.
     *
     * The rebuild is the point: `DbSubstanceCatalog` bakes the language and the name mode into
     * every `Substance` at construction — `localizedName` and the resolved `displayTitle` are
     * stamped once, deliberately, rather than looked up per render. So a change here has to
     * discard the built catalogue, and the handle with it, or the switch would appear to do
     * nothing until the next launch.
     */
    open suspend fun setUsesEnglishNames(value: Boolean) {
        if (value == usesEnglishNames) return
        usesEnglishNames = value
        reconfigureContent()
    }

    /**
     * Drop the built catalogue so the next ask rebuilds it with the current language and name
     * mode.
     *
     * Closes the SQLite handle under the same lock the builder takes, so a caller mid-`catalog()`
     * cannot have the file pulled out from under it — the same use-after-free that two handles
     * over this file caused once already.
     */
    private suspend fun reconfigureContent() {
        catalogMutex.withLock {
            catalog = null
            catalogHandle?.close()
            catalogHandle = null
        }
    }

    /**
     * The tolerance tool's coordinator.
     *
     * Built once and shared: it holds the replay's state and its cache gate, so a
     * second instance would recompute what the first already knows.
     */
    suspend fun toleranceRepository(): ToleranceRepository {
        repository?.let { return it }
        val profile = profile()
        val built = ToleranceRepository(
            database = database,
            pharmacology = catalog(),
            // Read through the profile store rather than captured: a weight the
            // user changes mid-session has to reach the next replay, and the replay
            // is keyed by a signature that includes the weight — so the change
            // invalidates the cache on its own rather than needing to be pushed.
            weightKg = { profile.weightKgOrDefault() },
        )
        repository = built
        return built
    }

    /**
     * The session grouper.
     *
     * Shares the one catalog with the tolerance repository: both resolve a dose's
     * modelled duration from it, and a second instance would mean a second 18 MB
     * read.
     */
    suspend fun sessionRepository(): SessionRepository {
        sessions?.let { return it }
        val profile = profile()
        val built = SessionRepository(
            database = database,
            catalog = catalog(),
            weightKg = { profile.weightKgOrDefault() },
        )
        sessions = built
        return built
    }

    /**
     * The substance palette: the user's own colours over the generated ones.
     *
     * Built per call rather than cached: it holds no state beyond the catalog it
     * shares, and its expensive part — the gamut search per substance — is
     * memoized inside the map it returns.
     */
    suspend fun palette(): SubstancePalette = SubstancePalette(database, catalog())

    /**
     * Which notifications the user has allowed, and the mirror the scheduler reads.
     *
     * One instance for the process, because it is the writer of a
     * `SharedPreferences` mirror that receivers read on a different thread —
     * a second instance would be a second view of the same file, and the whole
     * point of the mirror is that there is exactly one.
     */
    fun notificationPreferences(): NotificationPreferencesStore =
        preferences ?: NotificationPreferencesStore(this).also { preferences = it }

    /**
     * The profile row — the body weight every model is scaled by.
     *
     * Not `suspend` to hand out, because the object is cheap; the *load* is what
     * needs the database, and [onCreate] has already run it by the time any screen
     * exists. A screen that wants to be certain can call `load()` again.
     */
    fun profile(): UserProfileStore =
        userProfile ?: UserProfileStore(database).also { userProfile = it }

    /**
     * The routine-occurrence reconciler — the one writer of "was this slot taken".
     *
     * One instance for the process for the same reason the stores are: it holds no
     * state, but every caller that writes the record should be writing through the
     * same object, and there is exactly one place that decides what the record
     * says.
     */
    fun routineOccurrences(): RoutineOccurrenceService =
        routineOccurrences ?: RoutineOccurrenceService(database).also { routineOccurrences = it }

    /**
     * Re-derive the routine-occurrence record from the doses on file, then re-arm
     * the med reminders against it.
     *
     * Called after a dose is written, and both halves are the point. The record
     * half is what makes "still need to log X?" stop for a slot that has been
     * logged; the reminder half is what *un-arms* the re-ask — those are
     * materialized up to three days ahead, so a re-ask already sitting on the
     * alarm for 10:00 does not notice the 08:00 dose until something sweeps the
     * set. This is upstream's `DoseNotificationManager.syncMedReminders`, in its
     * order, and calling it twice is free: an unchanged record plans nothing to
     * write and the notification pass is a rebuild rather than a queue.
     *
     * Deliberately application-scoped rather than launched from the screen that
     * committed the dose, for the reason [setBodyWeight] gives: a coroutine on a
     * sheet's scope is cancelled by the sheet closing, and the sheet closes
     * immediately after a commit.
     */
    suspend fun reconcileRoutineOccurrences(zone: ZoneId = ZoneId.systemDefault()) {
        routineOccurrences().reconcile(zone = zone)
        MedReminderScheduler.reconcile(this, zone)
    }

    /**
     * Record a body weight, off the caller's back.
     *
     * Deliberately application-scoped rather than launched from the screen that
     * collected it. The onboarding step calls this and immediately advances, and a
     * coroutine on that screen's scope would be cancelled by the step leaving
     * composition — turning "the user entered their weight" into a write that
     * happens only if they linger.
     */
    fun setBodyWeight(kg: Double, source: UserProfileStore.WeightSource) {
        appScope.launch { profile().setWeight(kg, source) }
    }

    /** Record the disclosure tier. Application-scoped for the same reason as [setBodyWeight]. */
    fun setDisclosureTier(rawValue: String) {
        appScope.launch { profile().setDisclosureTier(rawValue) }
    }

    /**
     * How many substances the bundled catalog carries.
     *
     * Read straight from the catalog's own table rather than through
     * `DbSubstanceCatalog`, which answers lookups and has no count. Stubs are
     * excluded: a stub is a name the catalog has met but has no content for, and
     * counting them would inflate the figure the Data & Storage screen reports
     * against the number the library actually shows.
     */
    suspend fun substanceCount(): Int {
        catalog()
        val db = catalogHandle ?: return 0
        return db.query("SELECT count(*) AS n FROM substances WHERE is_stub = 0")
            .firstOrNull()?.long("n")?.toInt() ?: 0
    }

    /**
     * Re-read everything a bulk import or a wipe can leave stale in memory.
     *
     * The Android counterpart of `DataExportImport.refreshLiveStores`, and
     * deliberately a *shorter* list than upstream's ten singletons rather than a
     * longer one padded out to look equivalent. What upstream reconfigures:
     *
     * - the profile, the notification choices and the custom units — the first
     *   two have stores here and are reloaded below; custom units have no table
     *   in this build at all;
     * - the skin, the dock, the tab layout and the search history — none of those
     *   features exist here, so there is nothing to reload;
     * - `DoseNotificationManager.syncMedReminders` and a substance-catalog
     *   re-warm — both have direct counterparts and both are called below.
     *
     * The two repositories this class memoizes are dropped as well. They hold the
     * tolerance replay and the session grouping, and both are keyed off the rows
     * an import has just replaced.
     */
    suspend fun refreshLiveStores() {
        invalidateRepositoryCaches()
        DataExportImport.refreshLiveStores(database)
        notificationPreferences().load()
        MedReminderScheduler.enqueueReconcile(this)
        MedReminderScheduler.scheduleRollForward(this)
    }

    /**
     * Drop the memoized repositories so the next caller rebuilds them.
     *
     * Not `@Volatile` and not synchronized, matching the lazy reads in
     * [toleranceRepository] and [sessionRepository], which have the same benign
     * race today: a reader that wins the race gets the previous instance, and the
     * worst outcome is one stale reading before the next call picks up the new
     * one.
     */
    fun invalidateRepositoryCaches() {
        repository = null
        sessions = null
    }

    /**
     * Sweep any dose that no session owns into one.
     *
     * Called once at launch, and safe to call on every launch: it only ever
     * touches session-less doses, so it is a no-op once everything is grouped and
     * it can never disturb a merge or a split the user made. See
     * `SessionRepository.ensureSessionsPopulated` for why it deliberately has no
     * "already done" flag.
     */
    suspend fun ensureSessionsPopulated(): Int = sessionRepository().ensureSessionsPopulated()

    override fun onTerminate() {
        super.onTerminate()
        catalogHandle?.close()
    }

    private companion object {
        /** Logcat tag for the launch-time work that has nowhere else to report failures. */
        const val TAG = "PiruApp"
    }
}
