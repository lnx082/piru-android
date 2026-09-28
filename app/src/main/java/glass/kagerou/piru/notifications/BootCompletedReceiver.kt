package glass.kagerou.piru.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The one receiver the app declares, doing the two jobs the notification layer
 * needs a process for.
 *
 * ## Why one class and not two
 * On iOS a scheduled notification is held by the system and delivered by it, and
 * a restart re-arms nothing — the pending queue survives. Android's alarm queue
 * does not, so something has to rebuild it after a reboot, and something has to
 * *deliver* an alarm when it comes due. Both want a `BroadcastReceiver`, and this
 * manifest declares exactly one — this one, at this name and package, aimed at by
 * `android:name=".notifications.BootCompletedReceiver"`. Adding a second would
 * mean editing the manifest, which this port does not do, so the receiver
 * dispatches on the action instead: the two system actions it is registered for,
 * and [PiruNotifications.ACTION_FIRE_ALARM] for its own alarms.
 *
 * A dedicated alarm receiver would be tidier and is the one-line fix — a
 * `<receiver>` entry — if the manifest ever opens up.
 *
 * ## Delivery is synchronous; rebuilding is queued
 * `onReceive` runs on the main thread with a hard deadline of a few seconds, and
 * exceeding it is an ANR rather than a retry. So the two halves are split by
 * cost:
 * - **Firing an alarm** is a `notify()` call with a payload that arrived in the
 *   intent. Nothing is read, nothing is computed, and it is done before the
 *   method returns — which is also the only way the reminder is guaranteed to
 *   arrive, since the broadcast is not sticky and a queued hand-off could be
 *   killed with the process before it ran.
 * - **Rebuilding after a restart** needs the database, the substance catalog and
 *   the preferences store. That is minutes of work on a cold start, so it is
 *   handed to WorkManager, which is built for exactly this: it will run the pass
 *   even if this broadcast's process is torn down immediately afterwards.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext

        when (intent.action) {
            PiruNotifications.ACTION_FIRE_ALARM -> {
                // The channels are recreated here rather than trusted: a fresh
                // install posts its first notification from a cold boot with no
                // screen ever opened, and a post to a missing channel is dropped
                // silently on API 26+.
                PiruNotifications.registerChannels(app)
                val payload = PiruNotifications.decodePayload(intent) ?: return
                PiruNotifications.post(app, payload)
            }

            PiruNotifications.ACTION_SKIP_TODAY -> {
                val target = intent.getStringExtra(PiruNotifications.EXTRA_SKIP_TARGET) ?: return
                // `goAsync`, unlike the firing path above: this has to write to the
                // routine table and then rebuild the reminder set, which the
                // database will not do inside a broadcast's few seconds. The
                // pending result is what keeps the process alive until it is done,
                // and finishing it in a `finally` means a failed write still
                // releases the process rather than leaving it to be killed.
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        MedReminderScheduler.skipToday(app, target, ZoneId.systemDefault())
                    } finally {
                        pending.finish()
                    }
                }
            }

            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> NotificationRestartWorker.enqueue(app)
        }
    }
}

/**
 * The post-restart rebuild, as durable work.
 *
 * Queued by [BootCompletedReceiver] and replace-not-stack: a device that reboots,
 * then updates the app in the same minute, wants one rebuild rather than two
 * racing passes over the same tables.
 */
class NotificationRestartWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        // First, because it clears the delivery ledger and re-derives the work
        // from the store: an alarm armed before the restart no longer exists, and
        // a ledger that still claimed it did would make every pass below decline
        // to re-arm anything.
        DoseNotificationScheduler.rearmPending(applicationContext)
        CheckInScheduler.rearmAll(applicationContext)
        MedReminderScheduler.reconcile(applicationContext)
        // Roll the med horizon forward tomorrow too: the reboot may have been the
        // last time this process runs for a while.
        MedReminderScheduler.scheduleRollForward(applicationContext)
    }.fold(
        onSuccess = { Result.success() },
        onFailure = { Result.retry() },
    )

    companion object {
        private const val WORK = "piru.notifications.restart"

        /** Queue the rebuild, replacing any pass already queued. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<NotificationRestartWorker>().build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
