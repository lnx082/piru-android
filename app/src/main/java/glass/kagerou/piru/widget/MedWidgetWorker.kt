package glass.kagerou.piru.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Refreshes the medication widget at the two moments it has to be right at.
 *
 * ## Why not the platform's own timer
 * `appwidget-provider` can ask the system to update a widget every N milliseconds. N's
 * floor is thirty minutes, the clock starts whenever the widget was added rather than at
 * midnight, and the system batches the wake-ups with whatever else the device is doing.
 * None of that can express this widget's two moments:
 *
 * - **The minute a slot falls due.** A 4:00pm slot has to stop looking "upcoming" and
 *   start looking "due" at 4:00pm, and the difference is the whole reason the widget
 *   exists. Half an hour late is a widget that lies about what is late.
 * - **Midnight.** The list is today's, so it is wrong the instant the date changes, and
 *   it stays wrong until something refreshes it.
 *
 * So this schedules itself: one one-shot request delayed to whichever comes first, and
 * when it runs it refreshes and schedules the next. A `PeriodicWorkRequest` cannot do
 * this either — its period is fixed, and these are two different instants.
 *
 * ## What it does not promise
 * WorkManager's delay is a floor, not an alarm: under Doze the run can be pushed out.
 * That is acceptable here in a way it would not be for a notification — the widget is a
 * convenience display, and the app itself is the thing that must be right. The
 * alternative is an exact alarm, which would cost a permission and a battery exemption
 * for a home-screen tile.
 */
class MedWidgetWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        MedWidgetRefresh.refreshAll(context)
        // Schedule the next moment from *now* rather than from the last one, so a run
        // that was pushed out by an hour does not queue up an hour of catch-up work.
        MedWidgetRefresh.scheduleNext(context, Instant.now())
        return Result.success()
    }
}

/**
 * What both the worker and every write path call.
 *
 * Kept as one object so "refresh the widget" means one thing: rewriting the snapshot for
 * every placed instance, then arming the next timer. A write path that only did the
 * first would leave the widget correct now and stale at the next boundary.
 */
internal object MedWidgetRefresh {

    /**
     * Bring the widget up to date and arm the next boundary, in one call.
     *
     * This is what a write path calls. Both halves matter and they are easy to separate
     * by accident: refreshing alone leaves the widget correct now and stale at the next
     * slot time, arming alone leaves it drawing the state from before the write.
     *
     * Cheap when no widget is placed — the id list comes back empty and neither half
     * does work — so a caller can call it unconditionally after a dose rather than
     * checking whether the user has one.
     */
    suspend fun afterWrite(context: Context, now: Instant = Instant.now()) {
        refreshAll(context)
        scheduleNext(context, now)
    }

    /**
     * Rewrite every placed instance.
     *
     * `getGlanceIds` returns one id per instance, so a user with the widget on two home
     * screens gets both, and a user with none gets an empty list and no work done.
     *
     * Each `update` re-runs the widget's own `provideGlance`, which reads the store
     * again. That is one extra Room query per instance on a boundary rather than a
     * serialized snapshot kept in the widget's state: the read is two small table scans
     * and the snapshot would be a second copy of the schedule to keep in step.
     */
    suspend fun refreshAll(context: Context) {
        val manager = GlanceAppWidgetManager(context)
        val ids = runCatching { manager.getGlanceIds(TodayMedsWidget::class.java) }.getOrDefault(emptyList())
        if (ids.isEmpty()) return
        val widget = TodayMedsWidget()
        for (id in ids) {
            runCatching { widget.update(context, id) }
        }
    }

    /**
     * Arm the next refresh, replacing any pending one.
     *
     * `ExistingWorkPolicy.REPLACE` because there is exactly one correct next time, and a
     * write path firing while a timer is already armed must move it rather than add a
     * second. Unique name, so the replacement is deterministic.
     */
    suspend fun scheduleNext(context: Context, now: Instant, zone: ZoneId = ZoneId.systemDefault()) {
        val delayMinutes = minutesUntilNextBoundary(context, now, zone)
        val request = OneTimeWorkRequestBuilder<MedWidgetWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Minutes until the next moment the display changes: a slot's time, or midnight.
     *
     * The I/O half only — the store read — with the arithmetic in
     * [nextBoundaryMinutes] so it can be tested without a device.
     */
    internal suspend fun minutesUntilNextBoundary(context: Context, now: Instant, zone: ZoneId): Long {
        val items = runCatching {
            // Opens the store directly rather than reaching through the application
            // object. A worker can run before any activity has ever started — a widget
            // resized, or a boundary reached after a reboot — and `PiruApplication`'s
            // database is a lazy property that only that process's ordinary start-up
            // would have touched. `PiruDatabase.open` is the same door either way.
            glass.kagerou.piru.data.PiruDatabase.open(context.applicationContext)
                .dailyDoseItemDao().all()
        }.getOrDefault(emptyList())
        return nextBoundaryMinutes(
            now = now,
            zone = zone,
            reminderMinutes = items.flatMap { it.reminderTimesMinutes },
        )
    }

    /**
     * The arithmetic: minutes until the next slot time, or until midnight, whichever is
     * first.
     *
     * Zero is never returned, deliberately. WorkManager treats a zero delay as "run now",
     * so a boundary that has just passed would re-run immediately and then again — a spin
     * rather than a schedule. One minute is the smallest honest answer.
     */
    internal fun nextBoundaryMinutes(now: Instant, zone: ZoneId, reminderMinutes: List<Int>): Long {
        val local = now.atZone(zone)
        val midnight = local.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        val nowMinutes = local.hour * 60 + local.minute

        val upcoming = reminderMinutes.filter { it > nowMinutes }.minOrNull()

        val untilMidnight = ChronoUnit.MINUTES.between(now, midnight)
        val untilSlot = upcoming?.let { (it - nowMinutes).toLong() } ?: Long.MAX_VALUE
        return maxOf(1L, minOf(untilSlot, untilMidnight))
    }

    /** One name, so a re-arm replaces rather than stacks. */
    private const val WORK_NAME = "piru-med-widget-refresh"
}
