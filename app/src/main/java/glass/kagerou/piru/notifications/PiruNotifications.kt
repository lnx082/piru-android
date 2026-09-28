package glass.kagerou.piru.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import glass.kagerou.piru.MainActivity
import glass.kagerou.piru.R
import java.time.Instant

/**
 * One notification, fully decided, ready to be delivered.
 *
 * The schedulers build these and hand them over; nothing downstream consults a
 * preference or a clock again. That matters because the delivery path runs in a
 * boot receiver or an alarm broadcast, where the database may not be warm and
 * where a re-derivation would be a second chance to disagree with the decision
 * that scheduled it.
 *
 * The iOS equivalent is the `UNMutableNotificationContent` a request carries,
 * and the field set is the same one: title, body, the group it belongs to, and
 * the deep link a tap follows. Policy — quiet hours, quiet tier, timing —
 * already happened by the time one of these exists, which is why there is no
 * `fireAt` on it.
 */
/**
 * A button on a notification.
 *
 * Deliberately not arbitrary: [target] is what the scheduler wants acted on, and
 * the only action this build attaches is "Skip today". A general action framework
 * with one action in it would be a framework nobody had designed.
 */
data class PlannedAction(val label: String, val target: String)

data class PlannedNotification(
    /** The request identifier, from the `piru.notif.<type>.<anchor>[.<ordinal>]` grammar. */
    val identifier: String,

    /** The channel to post on. Comes from [NotificationType.channelId]. */
    val channelId: String,

    val title: String,
    val body: String,

    /**
     * The notification group — iOS's `threadIdentifier`. Doses logged inside the
     * same six-hour window share one so the shade collapses them into a single
     * conversation, and a med's re-asks stack under the reminder they follow.
     */
    val threadKey: String? = null,

    /** A `piru://` URL a tap follows. Null when the notification has nowhere to go. */
    val deepLink: String? = null,

    /**
     * Deliver silently: no sound, no heads-up, no lock-screen wake.
     *
     * This is the quiet tier's whole contract — a supplement taken every morning
     * should not buzz like a prescription — and it is the closest Android gets
     * to the iOS `.passive` interruption level. It is per-notification rather
     * than per-channel because the quiet and loud meds share the reminders
     * channel on purpose: separating them would put a "Supplements" channel in
     * system settings that means nothing to the user.
     */
    val silent: Boolean = false,

    /**
     * Buttons shown on the notification itself.
     *
     * Carried on the payload rather than attached at post time, because most of
     * these notifications are delivered by an **alarm** — the payload is encoded
     * into the alarm's intent, decoded when it fires, and posted from there. A
     * field that did not survive that round trip would mean buttons that appear
     * on the notifications posted immediately and vanish from every scheduled
     * one, which is most of them.
     */
    val actions: List<PlannedAction> = emptyList(),
)

/**
 * The notification layer's platform edge: channels, permission, and delivery.
 *
 * Ported from `Piru/Utilities/NotificationContent.swift` and the
 * `UNUserNotificationCenter` call sites scattered through
 * `SessionNotificationScheduler` and `DoseNotificationManager` — the parts of
 * the iOS design that are the operating system's job rather than the app's.
 *
 * ## What is here and what is not
 * Channels, the permission gate, immediate delivery, and the scheduling of a
 * *single* timed delivery belong here. Deciding *which* notifications should
 * exist belongs to the schedulers ([DoseNotificationScheduler],
 * [MedReminderScheduler], [CheckInScheduler]); this type never reads a
 * preference and never asks what time it is.
 *
 * ## Time Sensitive, and the honest gap
 * On iOS a notification can be marked time-sensitive and breaks through Focus.
 * Android has no equivalent: no per-notification flag, no entitlement, no API.
 * The one lever that exists is `setBypassDnd`, which needs the user to grant
 * this app notification-policy access — a blanket, app-wide grant that also lets
 * an app *read and rewrite* their Do Not Disturb rules. That is a different and
 * much larger thing than "this one reminder may interrupt", and asking for it to
 * make one safety alert louder would trade a real privacy-shaped cost for a
 * cosmetic gain.
 *
 * So: [NotificationType.supportsTimeSensitive] stays in the model and on the
 * settings screen, every notification delivers as an ordinary one, and
 * [isDndAccessGranted] exists only so the settings screen can say plainly what
 * the platform does not do rather than implying that it does. A deliberate
 * platform difference, not an unfinished port.
 *
 * ## Two clocks, one delivery
 * A timed notification is carried by an `AlarmManager` alarm whose payload
 * travels in the broadcast's extras — self-contained, so the receiver needs no
 * database and no preference read to deliver it. The alarm is exact when the
 * platform allows it and inexact when it does not ([scheduleExact]); either way
 * the *decision* was made at schedule time and the alarm only decides when it
 * arrives.
 *
 * The alarm's broadcast is aimed explicitly at [BootCompletedReceiver], which is
 * the only receiver this app's manifest declares. Adding a second one would mean
 * editing the manifest, which this port deliberately does not do; the receiver
 * therefore handles both "the device restarted" and "an alarm you scheduled has
 * come due", distinguished by action. See its own documentation.
 */
object PiruNotifications {

    // MARK: - Channels

    /**
     * Session Alerts — hydration, sleep, phase and check-in. Default importance:
     * worth a sound, not worth a heads-up banner over whatever the user is doing.
     */
    const val CHANNEL_SESSION_ALERTS = "piru.channel.sessionAlerts"

    /**
     * Med Reminders — the routine primary and its re-ask. High importance,
     * because a dose taken on a schedule is the one thing here that gets missed
     * rather than merely noticed.
     */
    const val CHANNEL_REMINDERS = "piru.channel.reminders"

    /**
     * Safety and Supplies — the cumulative-dose warning and the low-stock alert.
     * High importance: both are the app telling the user something they would
     * want to act on, and neither is silenced by the app's own quiet hours.
     */
    const val CHANNEL_SAFETY = "piru.channel.safety"

    /**
     * The action a scheduled alarm's broadcast carries. Declared here rather
     * than privately because it is the wire contract between [scheduleExact] and
     * [BootCompletedReceiver].
     */
    const val ACTION_FIRE_ALARM = "glass.kagerou.piru.notifications.action.FIRE_ALARM"

    /**
     * A notification button, delivered to [BootCompletedReceiver].
     *
     * Through the same receiver as everything else because the manifest declares
     * exactly one, and an action is a broadcast like any other. The receiver
     * dispatches on the action string, which is what that class's own note says it
     * is for.
     */
    const val ACTION_SKIP_TODAY = "glass.kagerou.piru.notifications.action.SKIP_TODAY"

    /**
     * What "Skip today" applies to — `"slot|<slotKey>"` for one medication, or
     * `"group|<groupSlug>"` for a whole time-of-day group.
     *
     * Resolved by the scheduler rather than here: which slots a group covers is a
     * question about the user's meds, and this layer only carries the string.
     */
    const val EXTRA_SKIP_TARGET = "piru.notification.skipTarget"

    /** The deep link a notification's tap carries, as an intent extra as well as a data URI. */
    const val EXTRA_DEEP_LINK = "piru.notification.deepLink"

    /**
     * Create the three channels. Idempotent, and safe to call on every launch —
     * `createNotificationChannel` updates an existing channel's name and
     * description and *cannot* touch its importance, which is the user's to set
     * once they have seen it.
     *
     * Called from the notification screens and from [BootCompletedReceiver]'s
     * restart pass, because a notification can arrive before any screen has been
     * opened on a fresh install, and a post to a missing channel is dropped
     * silently on API 26+ — the one failure mode where the user would see
     * nothing at all and have no way to tell why.
     */
    fun registerChannels(context: Context) {
        val manager = context.applicationContext
            .getSystemService(NotificationManager::class.java) ?: return

        val sessionAlerts = NotificationChannel(
            CHANNEL_SESSION_ALERTS,
            context.getString(R.string.channel_session_alerts_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.channel_session_alerts_description)
        }

        val reminders = NotificationChannel(
            CHANNEL_REMINDERS,
            context.getString(R.string.channel_reminders_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_reminders_description)
        }

        val safety = NotificationChannel(
            CHANNEL_SAFETY,
            context.getString(R.string.channel_safety_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_safety_description)
        }

        manager.createNotificationChannels(listOf(sessionAlerts, reminders, safety))
    }

    // MARK: - Permission

    /**
     * Where the notification grant stands, in the three states an Android app can
     * actually observe.
     *
     * iOS reports four (`authorized`, `provisional`, `ephemeral`, `denied`) plus
     * `notDetermined`. Android has no provisional or ephemeral grant, so those
     * fold into [AUTHORIZED]. It also cannot tell "never asked" from "denied and
     * don't ask again" without an activity's
     * `shouldShowRequestPermissionRationale`, so this tracks the asked-once fact
     * itself via [notePermissionRequest]. The distinction is worth keeping
     * because it is the difference between a button that shows a system dialog
     * and one that has to open Settings.
     */
    enum class Authorization {
        /** Notifications will be delivered. */
        AUTHORIZED,

        /** Off, and this app has never asked. A request can still prompt. */
        NOT_DETERMINED,

        /** Off, after having been asked. Only the system settings screen can turn it back on. */
        DENIED,
    }

    fun authorization(context: Context): Authorization {
        val app = context.applicationContext
        if (notificationsEnabled(app)) return Authorization.AUTHORIZED
        return if (permissionAsked(app)) Authorization.DENIED else Authorization.NOT_DETERMINED
    }

    /** Record that the permission dialog has been put in front of the user. */
    fun notePermissionRequest(context: Context) {
        mirrorPrefs(context).edit().putBoolean(KEY_PERMISSION_ASKED, true).apply()
    }

    /**
     * The launcher for the grant.
     *
     * From API 33 that is the runtime `POST_NOTIFICATIONS` dialog. Below 33 the
     * permission does not exist and the grant is implicit, so the only thing the
     * user could want is the app's notification settings screen — which is what
     * this opens instead, and whose result it reports as "are notifications on
     * now", not as "did the user press Allow". Claiming the latter would be
     * inventing an answer the platform did not give.
     *
     * Takes a context because the whole thing is a context-dependent launcher;
     * the settings screen is expected to `remember` the instance it hands to
     * `rememberLauncherForActivityResult`.
     */
    fun permissionRequestContract(context: Context): ActivityResultContract<Unit, Boolean> {
        val app = context.applicationContext
        return object : ActivityResultContract<Unit, Boolean>() {
            private val runtime = ActivityResultContracts.RequestPermission()

            override fun createIntent(context: Context, input: Unit): Intent =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    runtime.createIntent(context, Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    appNotificationSettingsIntent(context)
                }

            override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    runtime.parseResult(resultCode, intent)
                } else {
                    notificationsEnabled(app)
                }
        }
    }

    /**
     * Whether this app may rewrite Do Not Disturb rules.
     *
     * Read-only, and deliberately: nothing in this app calls `setBypassDnd`. It
     * exists so the settings screen can tell the truth about Time Sensitive
     * instead of showing a toggle that quietly does nothing. See the class note.
     */
    fun isDndAccessGranted(context: Context): Boolean {
        val manager = context.applicationContext
            .getSystemService(NotificationManager::class.java) ?: return false
        return manager.isNotificationPolicyAccessGranted
    }

    /** The system screen where a user turns this app's notifications back on. */
    fun appNotificationSettingsIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)

    private fun notificationsEnabled(context: Context): Boolean {
        // From 33 the runtime grant and the app-level toggle are two separate
        // switches and delivery needs both; below 33 only the toggle exists.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    // MARK: - Delivery

    /**
     * Post a notification now.
     *
     * No preference is consulted and no permission is requested: the iOS
     * schedulers never request the grant from a schedule path either (the
     * management screen and onboarding are the honest surfaces for that), and a
     * request posted before the user allows simply delivers once they do.
     *
     * The one guard is a delivery-time check that notifications are on at all.
     * It changes nothing the user can see — Android drops a post from a silenced
     * app anyway — but it keeps the log honest about why nothing happened, which
     * is the difference between "the app went quiet" and "the app is broken" in
     * a bug report.
     */
    fun post(context: Context, payload: PlannedNotification) {
        val app = context.applicationContext
        if (!notificationsEnabled(app)) return

        val builder = NotificationCompat.Builder(app, payload.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(payload.title)
            .setContentText(payload.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.body))
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())

        if (payload.silent) {
            // The quiet tier, expressed per notification: the channel's importance
            // still governs the shade, but this one arrives without a sound or a
            // heads-up banner.
            builder.setSilent(true)
            builder.setPriority(NotificationCompat.PRIORITY_LOW)
        }

        payload.threadKey?.let { builder.setGroup(it) }
        contentIntent(app, payload)?.let { builder.setContentIntent(it) }
        for ((index, action) in payload.actions.withIndex()) {
            // Icon 0: the actions are text-only. A vector icon per action would be
            // three more drawables for buttons that read perfectly as words.
            builder.addAction(0, action.label, actionIntent(app, payload, action, index))
        }

        // The identifier is the notification's own key, so re-posting one replaces
        // it rather than stacking — the same "a retime cancels cleanly under the
        // same key" property the scheduler's identifiers exist to give.
        NotificationManagerCompat.from(app)
            .notify(payload.identifier.hashCode(), builder.build())
    }

    // MARK: - Timed delivery

    /**
     * Arrange for [payload] to be delivered at [fireAt].
     *
     * Exact when the platform will allow it, inexact when it will not:
     * `setExactAndAllowWhileIdle` needs `SCHEDULE_EXACT_ALARM` from API 31, and
     * this app deliberately does not declare it. A dose reminder is not an alarm
     * clock — the alternative to a reminder that may land a few minutes late
     * inside Doze is a Play Store policy declaration and a permission prompt for
     * something the user would not recognise as a feature, and being early is not
     * on offer either way. So: exact where the platform grants it without asking,
     * inexact otherwise, and never a crash.
     *
     * There is no permission gate on the payload itself. Scheduling before the
     * grant is fine, exactly as it is on iOS, because the notification simply
     * delivers once the user allows notifications.
     *
     * A [fireAt] that has already passed is dropped rather than clamped to now:
     * every caller computes it from a dose or a routine time, and firing
     * immediately for something already over is how the old "logged an old entry,
     * got buzzed right away" bug happened.
     */
    fun scheduleExact(context: Context, payload: PlannedNotification, fireAt: Instant) {
        val app = context.applicationContext
        val triggerAt = fireAt.toEpochMilli()
        if (triggerAt <= System.currentTimeMillis()) return

        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        val pending = alarmIntent(app, payload, PendingIntent.FLAG_UPDATE_CURRENT) ?: return

        try {
            if (canScheduleExactAlarms(manager)) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        } catch (_: SecurityException) {
            // A platform that reported canScheduleExactAlarms() and then refused the
            // call — an OEM build, or the grant revoked between the two calls. The
            // inexact form does not throw, so the reminder degrades rather than
            // vanishing.
            runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending) }
        }

        record(app, payload.identifier, triggerAt)
    }

    /**
     * When this identifier is due, or null when nothing is scheduled under it.
     *
     * The bookkeeping exists because Android has no equivalent of
     * `getPendingNotificationRequests()`: alarms cannot be enumerated and
     * `PendingIntent` has no list call. Without a record of what was scheduled,
     * [cancelPending] could not honour a prefix at all — and cancelling by prefix
     * is the only thing that makes turning a type off mean anything.
     *
     * The record is a *delivery* ledger, not a source of truth. What should exist
     * lives in the store — the dose log, the med list, the session's own cadence
     * — and everything the app re-arms after a restart is re-derived from there.
     * Lose this file and the worst case is a batch of already-fired reminders
     * being cancelled a second time.
     */
    fun pendingFireAt(context: Context, identifier: String): Instant? {
        val at = mirrorPrefs(context).getLong(KEY_ALARM_PREFIX + identifier, 0L)
        if (at <= System.currentTimeMillis()) return null
        return Instant.ofEpochMilli(at)
    }

    /** Cancel the notifications with exactly these identifiers, and forget them. */
    fun cancel(context: Context, identifiers: Collection<String>) {
        if (identifiers.isEmpty()) return
        val app = context.applicationContext
        val manager = app.getSystemService(AlarmManager::class.java)
        val editor = mirrorPrefs(app).edit()
        val shade = NotificationManagerCompat.from(app)

        for (identifier in identifiers) {
            editor.remove(KEY_ALARM_PREFIX + identifier)
            // The payload only fills in the PendingIntent's extras, and a cancel
            // delivers nothing — an identifier-only one matches by action and
            // request code, which is what the AlarmManager and the shade key on.
            val pending = alarmIntent(app, payloadFor(identifier), PendingIntent.FLAG_NO_CREATE)
            if (manager != null && pending != null) manager.cancel(pending)
            shade.cancel(identifier.hashCode())
        }

        editor.apply()
    }

    /**
     * Cancel every scheduled notification whose identifier starts with any of
     * [prefixes]. This is the iOS `removePending(withPrefixes:)`, and it is what
     * [NotificationType.identifierPrefixes] is for: turning a switch off has to
     * silence what is already armed, not only what would be armed next.
     */
    fun cancelPending(context: Context, prefixes: Collection<String>) {
        if (prefixes.isEmpty()) return
        val app = context.applicationContext
        val matching = mirrorPrefs(app).all.keys
            .asSequence()
            .filter { it.startsWith(KEY_ALARM_PREFIX) }
            .map { it.removePrefix(KEY_ALARM_PREFIX) }
            .filter { identifier -> prefixes.any { identifier.startsWith(it) } }
            .toList()
        cancel(app, matching)
    }

    /**
     * Forget the whole delivery ledger, without touching the notification shade.
     *
     * For the restart pass only. A reboot and a package update both drop every
     * armed alarm while leaving this file intact, so the ledger describes a queue
     * that no longer exists — and leaving it in place would make the re-arm pass
     * think every reminder was already scheduled and decline all of them, which
     * is precisely the silent failure the pass exists to prevent.
     */
    fun clearDeliveryLedger(context: Context) {
        val prefs = mirrorPrefs(context)
        val editor = prefs.edit()
        for (key in prefs.all.keys) {
            if (key.startsWith(KEY_ALARM_PREFIX)) editor.remove(key)
        }
        editor.apply()
    }

    /** Every identifier with a delivery still in the future. */
    fun scheduledIdentifiers(context: Context): List<String> {
        val now = System.currentTimeMillis()
        return mirrorPrefs(context).all.entries
            .filter { (key, value) -> key.startsWith(KEY_ALARM_PREFIX) && value is Long && value > now }
            .map { it.key.removePrefix(KEY_ALARM_PREFIX) }
    }

    // MARK: - Internals

    /**
     * The one preferences file behind the notification layer: the ask-once flag,
     * the delivery ledger, and the write-through mirror of the settings row that
     * [NotificationPreferencesStore] keeps so a scheduler can gate synchronously.
     * One file because they share a lifetime and a backup story, and because the
     * alarm ledger and the mirror are read in the same passes.
     */
    internal const val MIRROR_PREFS = "piru.notifications"

    private const val KEY_PERMISSION_ASKED = "permissionAsked"
    private const val KEY_ALARM_PREFIX = "alarm."

    internal fun mirrorPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(MIRROR_PREFS, Context.MODE_PRIVATE)

    private fun permissionAsked(context: Context): Boolean =
        mirrorPrefs(context).getBoolean(KEY_PERMISSION_ASKED, false)

    private fun canScheduleExactAlarms(manager: AlarmManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) manager.canScheduleExactAlarms() else true

    /**
     * A payload carrying only an identifier, for matching a `PendingIntent` that
     * was created from a fuller one. Every other field is irrelevant to identity:
     * a `PendingIntent` matches on request code, action, component and data, none
     * of which the extras touch.
     */
    private fun payloadFor(identifier: String): PlannedNotification =
        PlannedNotification(identifier = identifier, channelId = "", title = "", body = "")

    private fun record(context: Context, identifier: String, triggerAtMillis: Long) {
        mirrorPrefs(context).edit().putLong(KEY_ALARM_PREFIX + identifier, triggerAtMillis).apply()
    }

    internal fun alarmIntent(
        context: Context,
        payload: PlannedNotification,
        flags: Int,
    ): PendingIntent? {
        val intent = Intent(context, BootCompletedReceiver::class.java)
            .setAction(ACTION_FIRE_ALARM)
        encodePayload(intent, payload)
        return PendingIntent.getBroadcast(
            context,
            payload.identifier.hashCode(),
            intent,
            flags or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * The intent a button fires.
     *
     * Explicit to [BootCompletedReceiver], so no intent filter is needed for an
     * action this app invented. The request code mixes the notification's own
     * identifier with the action's index: two buttons on one notification need
     * two distinct `PendingIntent`s, and the same button on two notifications
     * must not share one — `FLAG_UPDATE_CURRENT` would otherwise make the second
     * overwrite the first's target.
     */
    private fun actionIntent(
        context: Context,
        payload: PlannedNotification,
        action: PlannedAction,
        index: Int,
    ): PendingIntent {
        val intent = Intent(context, BootCompletedReceiver::class.java)
            .setAction(ACTION_SKIP_TODAY)
            .putExtra(EXTRA_SKIP_TARGET, action.target)
        return PendingIntent.getBroadcast(
            context,
            (payload.identifier + "#" + index).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun contentIntent(context: Context, payload: PlannedNotification): PendingIntent? {
        val link = payload.deepLink ?: return null
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(link))
            .putExtra(EXTRA_DEEP_LINK, link)
        return PendingIntent.getActivity(
            context,
            payload.identifier.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    internal fun encodePayload(intent: Intent, payload: PlannedNotification) {
        intent.putExtra(EXTRA_IDENTIFIER, payload.identifier)
        intent.putExtra(EXTRA_CHANNEL, payload.channelId)
        intent.putExtra(EXTRA_TITLE, payload.title)
        intent.putExtra(EXTRA_BODY, payload.body)
        intent.putExtra(EXTRA_THREAD, payload.threadKey)
        intent.putExtra(EXTRA_LINK, payload.deepLink)
        intent.putExtra(EXTRA_SILENT, payload.silent)
        // Two parallel arrays rather than a serialized list: the action set is one
        // entry today, and a `Parcelable` would need a `@Parcelize` plugin this
        // module does not apply for a label and a string.
        intent.putExtra(EXTRA_ACTION_LABELS, payload.actions.map { it.label }.toTypedArray())
        intent.putExtra(EXTRA_ACTION_TARGETS, payload.actions.map { it.target }.toTypedArray())
    }

    /** The payload an alarm broadcast carries, or null when the intent was not one. */
    internal fun decodePayload(intent: Intent): PlannedNotification? {
        val identifier = intent.getStringExtra(EXTRA_IDENTIFIER) ?: return null
        val channelId = intent.getStringExtra(EXTRA_CHANNEL) ?: return null
        return PlannedNotification(
            identifier = identifier,
            channelId = channelId,
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            body = intent.getStringExtra(EXTRA_BODY).orEmpty(),
            threadKey = intent.getStringExtra(EXTRA_THREAD),
            deepLink = intent.getStringExtra(EXTRA_LINK),
            silent = intent.getBooleanExtra(EXTRA_SILENT, false),
            actions = decodeActions(intent),
        )
    }

    /**
     * The actions an alarm's intent carried.
     *
     * Pairs the two arrays by index and stops at the shorter one, so a truncated
     * or hand-edited intent yields fewer buttons rather than a crash or a button
     * with a null target.
     */
    private fun decodeActions(intent: Intent): List<PlannedAction> {
        val labels = intent.getStringArrayExtra(EXTRA_ACTION_LABELS) ?: return emptyList()
        val targets = intent.getStringArrayExtra(EXTRA_ACTION_TARGETS) ?: return emptyList()
        return labels.indices
            .takeWhile { it < targets.size }
            .map { PlannedAction(labels[it], targets[it]) }
    }

    private const val EXTRA_IDENTIFIER = "piru.notification.identifier"
    private const val EXTRA_CHANNEL = "piru.notification.channel"
    private const val EXTRA_TITLE = "piru.notification.title"
    private const val EXTRA_BODY = "piru.notification.body"
    private const val EXTRA_THREAD = "piru.notification.thread"
    private const val EXTRA_LINK = "piru.notification.link"
    private const val EXTRA_SILENT = "piru.notification.silent"
    private const val EXTRA_ACTION_LABELS = "piru.notification.actionLabels"
    private const val EXTRA_ACTION_TARGETS = "piru.notification.actionTargets"
}
