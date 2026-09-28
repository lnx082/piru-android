package glass.kagerou.piru

import android.app.Application
import glass.kagerou.piru.data.PiruDatabase
import glass.kagerou.piru.data.RoutineOccurrenceService
import glass.kagerou.piru.data.SessionRepository
import glass.kagerou.piru.data.SubstancePalette
import glass.kagerou.piru.data.ToleranceRepository
import glass.kagerou.piru.data.UserProfileStore
import glass.kagerou.piru.data.catalog.AndroidSubstanceDb
import glass.kagerou.piru.data.catalog.SubstanceCatalogInstaller
import glass.kagerou.piru.notifications.MedReminderScheduler
import glass.kagerou.piru.notifications.NotificationPreferencesStore
import glass.kagerou.piru.notifications.PiruNotifications
import glass.kagerou.piru.substance.ContentLanguage
import glass.kagerou.piru.substance.DbSubstanceCatalog
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
class PiruApplication : Application() {

    /** The user's own store. Opening it is cheap; Room connects on the first query. */
    val database: PiruDatabase by lazy { PiruDatabase.open(this) }

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
            profile().load()
            notificationPreferences().load()
            MedReminderScheduler.enqueueReconcile(this@PiruApplication)
            MedReminderScheduler.scheduleRollForward(this@PiruApplication)
        }
    }

    /**
     * The bundled substance catalog, installed and opened on first use.
     *
     * Memoized behind a mutex rather than a lazy `val` because the install is
     * suspending and two screens can ask for it at once — the journal and the
     * library both do, on the first frame after launch.
     */
    suspend fun catalog(): DbSubstanceCatalog = catalogMutex.withLock {
        catalog ?: run {
            val file: File = SubstanceCatalogInstaller.install(this)
            val db = AndroidSubstanceDb.open(file)
            catalogHandle = db
            val order = db.query("SELECT slug FROM sources ORDER BY default_priority, slug")
                .mapNotNull { it.string("slug") }
            DbSubstanceCatalog.open(
                db = db,
                order = order,
                language = ContentLanguage.EN,
            ).also { catalog = it }
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
}
