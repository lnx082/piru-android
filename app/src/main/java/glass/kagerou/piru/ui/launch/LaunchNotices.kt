package glass.kagerou.piru.ui.launch

import android.content.Context
import glass.kagerou.piru.BuildConfig
import glass.kagerou.piru.data.AppSettingsStore

/**
 * Something a new build has to tell the people already using the app.
 *
 * Ported from `Views/Launch/UpdateNotice.swift`. Its own doc gives the reason the system exists at all, and it is
 * worth restating because it is not obvious: **the store has no TestFlight-style "what to test" sheet and its
 * release notes go unread**, so a change that needs a decision has to be raised by the app itself — once, at
 * launch, and only for the installs it actually affects.
 *
 * ## What carries over, and what does not
 * Upstream declares three notices: `appMoved` and `journalArrived` are about iOS installs of a *previous app*, and
 * there is no such thing here — an Android install has no legacy build to be told about. `classColors` is the real
 * one, and it is the anchor of BUG #44: `SubstanceColorStore.resolveLegacy` is the decision it asks for, and
 * nothing called it.
 *
 * ## The two gates, which have to be told apart
 * **`isNewToInstall`** is about the *app*: a fresh install has nothing to be told about a change that shipped
 * before it existed. Its build number is recorded at first run, and a notice introduced at or before that build is
 * skipped.
 *
 * **`isOwed`** is about the *person*: a notice is owed again after a dismissal if it has a resurface interval.
 * Ours do not — a colour migration is answered once — so this is a single-purpose check, but it is kept separate
 * because the two gates failing for different reasons is what makes a missing notice diagnosable.
 */
internal enum class UpdateNotice(val wireValue: String) {
    /**
     * Substance colours now follow the substance's class, and colours a user picked by hand can either move with
     * the new palette or stay exactly as they were.
     *
     * [substanceColorStore.resolveLegacy] is the decision. Introduced at build 53 upstream; this port has no build
     * 53 history, so the constant here is the build that first shipped the requirement — which is what makes an
     * install that predates it owed the notice and a fresh one not.
     */
    CLASS_COLORS("classColors"),
    ;

    /** The first build carrying the change, or null for a notice every install is owed. */
    val introducedInBuild: Int? get() = when (this) {
        CLASS_COLORS -> CLASS_COLORS_BUILD
    }

    companion object {
        /**
         * The build that introduced the colour migration.
         *
         * A literal rather than a `BuildConfig` read: it is a **historical** fact, and reading the current build
         * number would make the gate compare a build against itself and never fire.
         */
        const val CLASS_COLORS_BUILD: Int = 53

        fun fromWire(value: String?): UpdateNotice? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * The build this install first ran, so a fresh install is never walked through changes to an app it has only just
 * met.
 *
 * Ported from `InstallRecord`. The subtle part is the fallback: an install that had **already finished onboarding**
 * before this record existed cannot know its own first build, and upstream records `0` — which is the safe
 * direction, because it means "this install is old" and therefore eligible for every notice rather than none. A
 * user who has been using the app for months getting a notice about a change they lived through is a small
 * annoyance; the opposite is a migration they never got asked about.
 */
/**
 * The same SharedPreferences file AppSettingsStore writes, opened here because its prefs() is private.
 *
 * Using the shared file rather than a new one is deliberate: the install record, the notice flags and the launch
 * count are the same kind of small scalar as the day boundary, and the file exists so a scheduler can read
 * preference-shaped values without opening the database. A second file would be a second thing to keep in an
 * export.
 */
/** Package-visible so the host reads the same file through the same accessor. */
internal fun launchPrefs(context: Context) =
    context.applicationContext.getSharedPreferences(AppSettingsStore.MIRROR_FILE, Context.MODE_PRIVATE)

internal object InstallRecord {

    const val KEY_FIRST_BUILD: String = "install.firstBuild"

    /**
     * Records the first build when none is on file.
     *
     * [hasCompletedOnboarding] is the fallback signal: someone who has already finished onboarding has used the app
     * before this record was written, so they are recorded as build `0`.
     */
    fun recordIfNeeded(context: Context, hasCompletedOnboarding: Boolean, currentBuild: Int = BuildConfig.VERSION_CODE) {
        val prefs = launchPrefs(context)
        if (prefs.contains(KEY_FIRST_BUILD)) return
        prefs.edit().putInt(KEY_FIRST_BUILD, if (hasCompletedOnboarding) 0 else currentBuild).apply()
    }

    /** The recorded first build, or `0` when nothing is on file — the "this install is old" direction. */
    fun firstBuild(context: Context): Int = launchPrefs(context).getInt(KEY_FIRST_BUILD, 0)
}

/**
 * Which launch sheet, if any, this launch owes — and the rules for each.
 *
 * Ported from `LaunchSheetModifier`. At most one sheet per launch, tried in priority order, so they never stack or
 * race each other's presentation. Nothing rises until onboarding is finished.
 *
 * ## Every rule is a pure function here
 * The gate has four inputs — the first build, the count of legacy colour rows, whether the Discord invite has been
 * shown or dismissed, and the launch count — and each can independently suppress a sheet. A single `if` chain in a
 * composable would make "why did that not appear" unanswerable, which is exactly the shape of BUG #44 itself.
 */
internal object LaunchNotices {

    /** Launches before the Discord invite is considered. Upstream's `appLaunchCount >= 3`. */
    const val DISCORD_MINIMUM_LAUNCHES: Int = 3

    const val KEY_LAUNCH_COUNT: String = "appLaunchCount"
    const val KEY_DISCORD_SHOWN: String = "discordPromptShown"
    const val KEY_DISCORD_DISMISSED: String = "discordPromptDismissedForever"

    /** The community invite. Upstream's own link, kept verbatim so it goes to the same place. */
    const val DISCORD_URL: String = "https://discord.gg/hbpMZhPSdx"

    /**
     * Whether the install is new enough that a notice has nothing to say.
     *
     * The `app` gate. `introducedInBuild` null means every install is owed it; otherwise the install's first build
     * has to **predate** the change.
     */
    fun isNewToInstall(notice: UpdateNotice, firstBuild: Int): Boolean =
        notice.introducedInBuild?.let { firstBuild < it } ?: true

    /**
     * Whether the notice's own condition holds.
     *
     * For the colour notice that is "legacy rows exist to decide about" — the count comes from
     * `SubstanceColorStore.legacyRowCount()`, which is the same query `resolveLegacy` acts on, so the notice cannot
     * offer a decision that would then have nothing to do.
     */
    fun isEligible(notice: UpdateNotice, legacyColorRows: Int): Boolean = when (notice) {
        UpdateNotice.CLASS_COLORS -> legacyColorRows > 0
    }

    /**
     * The notice this install is owed, or null.
     *
     * [seen] is the set of notices already dismissed, so a resolved migration does not ask again. Re-checked here
     * rather than trusted to the caller, because asking someone a question they have already answered is the
     * failure this whole system is built to avoid.
     */
    fun nextNotice(
        firstBuild: Int,
        legacyColorRows: Int,
        seen: Set<UpdateNotice>,
    ): UpdateNotice? = UpdateNotice.entries.firstOrNull { notice ->
        notice !in seen && isNewToInstall(notice, firstBuild) && isEligible(notice, legacyColorRows)
    }

    /**
     * Whether the Discord invite is due.
     *
     * Upstream's bar, and each clause is a different reason not to ask: **three launches** so it is not the first
     * session, **at least one dose logged** so the user is actually using the app rather than having opened it
     * three times, and **neither shown before nor dismissed forever** so it is a single invitation rather than a
     * nag. Closing it and joining both retire it.
     */
    fun discordInviteIsDue(
        launchCount: Int,
        doseCount: Int,
        alreadyShown: Boolean,
        dismissedForever: Boolean,
    ): Boolean {
        if (alreadyShown || dismissedForever) return false
        if (launchCount < DISCORD_MINIMUM_LAUNCHES) return false
        return doseCount > 0
    }

    /**
     * Which sheet to raise, in priority order — an update notice first, then the invite.
     *
     * One function so the priority is stated once rather than implied by the order two callers happen to check in.
     */
    fun nextSheet(
        onboardingComplete: Boolean,
        firstBuild: Int,
        legacyColorRows: Int,
        seenNotices: Set<UpdateNotice>,
        launchCount: Int,
        doseCount: Int,
        discordShown: Boolean,
        discordDismissed: Boolean,
    ): LaunchSheet? {
        // Nothing rises until onboarding is done: a sheet over the onboarding flow is a modal on top of a modal.
        if (!onboardingComplete) return null

        nextNotice(firstBuild, legacyColorRows, seenNotices)?.let { return LaunchSheet.UpdateNotice(it) }

        if (discordInviteIsDue(launchCount, doseCount, discordShown, discordDismissed)) {
            return LaunchSheet.DiscordInvite
        }
        return null
    }
}

/** A sheet the app raises on its own at launch. */
internal sealed interface LaunchSheet {
    data class UpdateNotice(val notice: glass.kagerou.piru.ui.launch.UpdateNotice) : LaunchSheet
    data object DiscordInvite : LaunchSheet
}

/** Whether a notice has been dismissed, and when. */
internal object LaunchNoticeRecord {

    private fun seenKey(notice: UpdateNotice) = "updateNotice.${notice.wireValue}.seen"

    fun hasBeenSeen(context: Context, notice: UpdateNotice): Boolean =
        launchPrefs(context).getBoolean(seenKey(notice), false)

    fun markSeen(context: Context, notice: UpdateNotice) {
        launchPrefs(context).edit().putBoolean(seenKey(notice), true).apply()
    }

    fun seenNotices(context: Context): Set<UpdateNotice> =
        UpdateNotice.entries.filterTo(mutableSetOf()) { hasBeenSeen(context, it) }
}
