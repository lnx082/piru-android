package glass.kagerou.piru

import android.app.Application

/**
 * The application the JVM UI specs run under.
 *
 * ## Why not the real one
 * `PiruApplication.onCreate` does real start-up work: it registers notification channels,
 * warms the preference mirror, syncs body weight and schedules `WorkManager` requests.
 * Under Robolectric that last one throws, because `androidx.startup` — which is what
 * normally initialises `WorkManager` from the manifest — does not run, so the app's own
 * start-up fails before any composable is reached:
 *
 * ```
 * IllegalStateException: WorkManager is not initialized properly
 *   at MedReminderScheduler.enqueueReconcile
 *   at PiruApplication$onCreate$1
 * ```
 *
 * A screen spec that boots the scheduler to render a list of cards is testing the wrong
 * thing twice over: it is slower, and it fails for a reason that has nothing to do with
 * whether the screen draws. The hubs need a `Context` for `stringResource` and nothing
 * else, so that is what they get.
 *
 * Named for the app rather than for one spec, because every screen spec in this module
 * wants the same thing.
 */
class PiruTestApplication : Application()
