package glass.kagerou.piru.notifications

/**
 * Every kind of local notification the app can send — one case per
 * user-togglable row on the notifications screen.
 *
 * Ported from `Piru/Data/Services/NotificationPreferencesStore.swift` (the
 * `NotificationType` enum, lines 16-103).
 *
 * ## The listing *is* the contract
 * Anything a scheduler can fire must have a case here, and its schedule path
 * must gate on [NotificationPreferencesStore.allows]. There are no hidden
 * senders: the management screen shows exactly these eight and nothing else can
 * reach the notification manager.
 *
 * ## Declaration order is the iOS order, and stays that way
 * Kotlin enums take `compareTo` from declaration order and it cannot be
 * overridden, so this list is the only place the ordering is written down. It
 * matches the Swift declaration, so a list built from `entries` reads the same
 * on both platforms. Never reorder it.
 *
 * ## Android mapping: three channels, not eight
 * The eight types collapse onto three notification channels, which are the same
 * three groups the settings screen draws — Med Reminders, Session Alerts,
 * Safety and Supplies. See [channelId]. Channel importance is a *user-visible*
 * setting on Android, so it is the natural home for "these are the ones that
 * may interrupt": the safety net and the med reminders are high importance, the
 * session nudges are default, and the user can retune any of the three from
 * system settings without the app losing the distinction.
 *
 * ## Time Sensitive has no Android equivalent
 * [supportsTimeSensitive] is kept because the data model and the settings
 * screen both carry it, but it changes nothing at delivery on this platform.
 * Android has no per-notification "break through Do Not Disturb" flag, and the
 * only lever that exists — the user granting this app notification-policy
 * access so it may call `setBypassDnd` — is a different thing entirely and is
 * deliberately not used. Full reasoning on [PiruNotifications].
 */
enum class NotificationType(val wireValue: String) {

    /** Timed hydration nudges during a session. */
    HYDRATION("hydration"),

    /** Wind-down reminder after a long stimulant or empathogen session. */
    SLEEP("sleep"),

    /** Onset, come-up and peak timing alerts. */
    PHASE("phase"),

    /** Heads-up when one substance's rolling 12-hour total reaches a heavy range. */
    CUMULATIVE("cumulative"),

    /** The daily reminder at each routine time with "Remind" on. */
    ROUTINE("routine"),

    /**
     * The snooze-style re-ask a little after a routine reminder that has not
     * been logged yet — "Still need to log X?".
     */
    ROUTINE_FOLLOW_UP("routineFollowUp"),

    /** Low-stock or out-of-stock alert for a tracked supply. */
    INVENTORY("inventory"),

    /**
     * "How is it going?" — the opt-in per-session note prompts, landing on the
     * session's note sheet.
     */
    CHECK_IN("checkIn"),
    ;

    /**
     * Whether the type is on for a user who has never touched the screen.
     *
     * Mirrors shipped behaviour: routine and inventory fired with no switch (so
     * they default on); the session types were gated behind flags that defaulted
     * off until onboarding enabled them.
     */
    val defaultEnabled: Boolean
        get() = when (this) {
            // Follow-ups also default on: they fire only where the user
            // explicitly configured them (a routine's cadence), so the toggle is
            // a kill switch rather than a prompt nobody asked for.
            ROUTINE, ROUTINE_FOLLOW_UP, INVENTORY, CHECK_IN -> true
            HYDRATION, SLEEP, PHASE, CUMULATIVE -> false
        }

    // MARK: - Identifier grammar — `piru.notif.<type>.<anchor>[.<ordinal>]`

    /**
     * The one identifier grammar every scheduler builds identifiers with.
     *
     * `<anchor>` is the *stable* anchor — the dose entry's id for dose-anchored
     * types, the sanitized med identity for routine types, the session id for
     * check-ins, the inventory item id for inventory — so a retime or delete
     * cancels cleanly under the same key it scheduled with. The grammar is the
     * iOS one verbatim and not an Android-shaped reinvention: it is what a
     * restored iOS backup's pending notifications were keyed by, and what the
     * legacy prefixes below exist to sweep.
     */
    val identifierPrefix: String get() = "piru.notif.$wireValue."

    /**
     * Build an identifier for this type.
     *
     * [ordinal] distinguishes members of a series — hydration 1 and 2, the three
     * phase alerts, a follow-up's day plus index.
     */
    fun identifier(anchor: String, ordinal: String? = null): String =
        if (ordinal == null) identifierPrefix + anchor else identifierPrefix + anchor + "." + ordinal

    /**
     * Types eligible for Time Sensitive delivery (breaking through Focus and the
     * notification summary): the adherence set plus the safety net. Session
     * nudges always deliver as ordinary notifications.
     *
     * On Android this decides nothing at delivery time — see the class note. It
     * is read by the settings screen, which shows the toggle and says plainly
     * that the platform does not honour it.
     */
    val supportsTimeSensitive: Boolean
        get() = when (this) {
            ROUTINE, ROUTINE_FOLLOW_UP, CUMULATIVE -> true
            HYDRATION, SLEEP, PHASE, INVENTORY, CHECK_IN -> false
        }

    /**
     * Prefixes of the identifiers this type schedules — disabling a type cancels
     * everything matching them.
     *
     * Each list is the current grammar's prefix plus the pre-grammar legacy
     * prefixes. The legacy entries are a transition sweep: dose-anchored pending
     * items self-expire within about two days and routine repeats rebuild on the
     * first sync, so they can be dropped a release or two after the grammar
     * ships — but not before, or a pending notification scheduled by an older
     * build survives a switch being turned off.
     */
    val identifierPrefixes: List<String>
        get() = when (this) {
            // No underscore on the legacy hydration prefix, on purpose: it covers
            // both `hydration_<ts>` and `hydration2_<ts>`, which is why it is the
            // bare category word rather than `"${…}_"` like its siblings.
            HYDRATION -> listOf(identifierPrefix, HYDRATION_CATEGORY_ID)
            SLEEP -> listOf(identifierPrefix, SLEEP_CATEGORY_ID + "_")
            PHASE -> listOf(identifierPrefix, PHASE_CATEGORY_ID + "_")
            CUMULATIVE -> listOf(identifierPrefix, CUMULATIVE_CATEGORY_ID + "_")
            ROUTINE -> listOf(identifierPrefix, ROUTINE_LEGACY_PREFIX)
            ROUTINE_FOLLOW_UP -> listOf(identifierPrefix, ROUTINE_FOLLOW_UP_LEGACY_PREFIX)
            // Born under the current grammar — no legacy prefix to sweep.
            CHECK_IN -> listOf(identifierPrefix)
            INVENTORY -> listOf(identifierPrefix, INVENTORY_LEGACY_PREFIX)
        }

    /**
     * The Android notification channel this type posts on.
     *
     * The three channels are the settings screen's three groups, which is what
     * makes "the switches here match the ones in system settings" true rather
     * than approximately true.
     */
    val channelId: String
        get() = when (this) {
            HYDRATION, SLEEP, PHASE, CHECK_IN -> PiruNotifications.CHANNEL_SESSION_ALERTS
            ROUTINE, ROUTINE_FOLLOW_UP -> PiruNotifications.CHANNEL_REMINDERS
            CUMULATIVE, INVENTORY -> PiruNotifications.CHANNEL_SAFETY
        }

    companion object {
        // The iOS category IDs, kept verbatim because the identifier grammar's
        // legacy halves derive from them — the derivation is what stops the two
        // from drifting, exactly as it does upstream. On Android the same strings
        // also ride the notification's group tag, so a notification's origin is
        // readable from a bug report without guessing.
        const val HYDRATION_CATEGORY_ID = "hydration"
        const val SLEEP_CATEGORY_ID = "sleepReminder"
        const val CUMULATIVE_CATEGORY_ID = "cumulativeDose"
        const val PHASE_CATEGORY_ID = "phaseAlert"
        const val ROUTINE_CATEGORY_ID = "routine"
        const val ROUTINE_FOLLOW_UP_CATEGORY_ID = "routineFollowUp"
        const val INVENTORY_CATEGORY_ID = "inventory"
        const val CHECK_IN_CATEGORY_ID = "checkIn"

        // Pre-grammar identifier prefixes, still swept during the transition.
        const val ROUTINE_LEGACY_PREFIX = "routineReminder_"
        const val ROUTINE_FOLLOW_UP_LEGACY_PREFIX = "routineFollowUp_"
        const val INVENTORY_LEGACY_PREFIX = "inventoryLowStock_"

        /**
         * The oldest routine-era prefix. Nothing writes it any more, but a
         * restore from a backup taken before the redesign can still carry it into
         * the pending queue, so the routine sweep clears it too.
         */
        const val DAILY_DOSE_REMINDER_LEGACY_PREFIX = "dailyDoseReminder"
    }
}
