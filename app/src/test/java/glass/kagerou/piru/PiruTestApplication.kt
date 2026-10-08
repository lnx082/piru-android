package glass.kagerou.piru

import androidx.room.Room
import glass.kagerou.piru.data.PiruDatabase
import glass.kagerou.piru.substance.ContentLanguage
import glass.kagerou.piru.substance.DbSubstanceCatalog

/**
 * The application the JVM screen specs run under: the real one, with a real in-memory store.
 *
 * ## Why a store rather than a stub
 * The alternative was a hand-written fake `PiruDatabase`, and it was rejected: what the
 * screens do with the store is exactly what is worth testing — the DAO's SQL, the session
 * grouping, the entity round trip — and a fake replaces all of it with an assumption. An
 * in-memory Room database is the real Room, the real DAOs and the real generated SQL, just
 * without a file. It is slower than a fake and faster than a device, and it cannot silently
 * disagree with production.
 *
 * `allowMainThreadQueries` is deliberately **not** set: the screens read from a coroutine,
 * and a spec that accidentally read on the main thread should fail here the way it would on
 * a phone.
 *
 * ## Why `onCreate` does nothing
 * `PiruApplication.onCreate` schedules `WorkManager` work, which `androidx.startup` normally
 * initialises from the manifest and Robolectric does not run:
 *
 * ```
 * IllegalStateException: WorkManager is not initialized properly
 *   at MedReminderScheduler.enqueueReconcile
 *   at PiruApplication$onCreate$1
 * ```
 *
 * A screen spec that boots the reminder scheduler to render a list is testing the wrong
 * thing twice over. Nothing here stubs product behaviour: `database` is the application's own
 * property, overridden to a different backing file, and every other accessor is the real one.
 */
class PiruTestApplication : PiruApplication() {

    /**
     * The in-memory store, opened once per JVM process.
     *
     * One instance rather than one per spec, because Room's in-memory database is dropped
     * when its last connection closes — a per-spec instance would need a per-spec close, and
     * a leaked one would leave rows visible to the next spec. Each spec is expected to write
     * only what it asserts.
     */
    override val database: PiruDatabase by lazy {
        Room.inMemoryDatabaseBuilder(this, PiruDatabase::class.java)
            .build()
    }

    /**
     * The catalogue is not available here, and deliberately so.
     *
     * It is an 18 MB asset installed and hash-verified on first use, so a screen spec cannot
     * have it. An empty catalogue is a real state the app already handles — a fresh install
     * has one until the install pass finishes — so this makes the specs exercise that path
     * rather than pretend the data is there.
     */
    override suspend fun catalog(): DbSubstanceCatalog = throw UnsupportedOperationException(
        "the JVM screen specs have no substance catalogue; a screen that needs one is not " +
            "a candidate for this harness until it takes the catalogue as a parameter",
    )

    override fun onCreate() {
        // Intentionally empty. See the class note.
    }
}
