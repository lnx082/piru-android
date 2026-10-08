package glass.kagerou.piru.ui.launch

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Which launch sheet this launch owes, and the four gates that each independently suppress one.
 *
 * ## Why every rule is a pure function
 * BUG #44 was a decision that needed a person — `SubstanceColorStore.resolveLegacy` — with **no caller**, so a
 * legacy colour row could never be settled. The same shape of bug is a gate: if the eligibility, the build check,
 * the seen check and the engagement bar were an `if` chain in a composable, then "why did that notice not appear"
 * would be unanswerable, and the notice would be as unreachable as the decision was.
 *
 * ## The two gates that are easy to conflate
 * **`isNewToInstall`** is about the *app*: a fresh install has nothing to be told about a change that shipped
 * before it existed. **`isEligible`** is about the *data*: there has to be something to decide about. Both have to
 * hold, and they fail for different reasons — which is what makes a missing notice diagnosable.
 */
class LaunchNoticesTest {

    // MARK: - The build gate

    /**
     * An install that began **at or after** the change has nothing to be told.
     *
     * The `firstBuild < introducedInBuild` comparison, asserted from both sides. An install whose first build *is*
     * the introducing build already has the new behaviour, so a notice about the change would be explaining
     * something they have never experienced otherwise.
     */
    @Test
    fun `a fresh install is not owed a notice for a change that shipped first`() {
        val notice = UpdateNotice.CLASS_COLORS
        LaunchNotices.isNewToInstall(notice, firstBuild = 53) shouldBe false
        LaunchNotices.isNewToInstall(notice, firstBuild = 54) shouldBe false
        LaunchNotices.isNewToInstall(notice, firstBuild = 100) shouldBe false
        // One build earlier, and the install predates the change.
        LaunchNotices.isNewToInstall(notice, firstBuild = 52) shouldBe true
        LaunchNotices.isNewToInstall(notice, firstBuild = 1) shouldBe true
    }

    /**
     * Build `0` — the fallback for an install that predates the record — is owed every notice.
     *
     * `InstallRecord` records `0` when onboarding was already complete and no record exists, and the direction
     * matters: someone who has used the app for months getting a notice about a change they lived through is a
     * small annoyance, and the opposite is **a migration they never got asked about**.
     */
    @Test
    fun `an install with no recorded build is owed everything`() {
        for (notice in UpdateNotice.entries) {
            LaunchNotices.isNewToInstall(notice, firstBuild = 0) shouldBe true
        }
    }

    // MARK: - The data gate

    /**
     * The colour notice needs legacy rows to decide about.
     *
     * The count comes from the same query `resolveLegacy` acts on, so the notice cannot offer a decision that would
     * then have nothing to do — a sheet whose "yes" button does nothing is worse than no sheet.
     */
    @Test
    fun `the colour notice needs legacy rows`() {
        LaunchNotices.isEligible(UpdateNotice.CLASS_COLORS, legacyColorRows = 5) shouldBe true
        LaunchNotices.isEligible(UpdateNotice.CLASS_COLORS, legacyColorRows = 0) shouldBe false
    }

    // MARK: - Owed, and the seen check

    /**
     * The whole notice gate: new to the install, eligible, and not already answered.
     *
     * Each clause walked separately, because that is the diagnosis when a notice does not appear.
     */
    @Test
    fun `the colour notice is owed to an install that predates it and has legacy rows`() {
        LaunchNotices.nextNotice(firstBuild = 1, legacyColorRows = 3, seen = emptySet()) shouldBe
            UpdateNotice.CLASS_COLORS
    }

    @Test
    fun `a fresh install is owed nothing`() {
        LaunchNotices.nextNotice(firstBuild = 60, legacyColorRows = 3, seen = emptySet()) shouldBe null
    }

    @Test
    fun `an install with no legacy rows is owed nothing`() {
        LaunchNotices.nextNotice(firstBuild = 1, legacyColorRows = 0, seen = emptySet()) shouldBe null
    }

    /**
     * A notice already answered is not asked again.
     *
     * Re-asking a question the user has answered is the failure this system exists to avoid, so the check is here
     * rather than left to the caller — a resolved migration must not raise a sheet on the next launch.
     */
    @Test
    fun `a seen notice is not owed`() {
        LaunchNotices.nextNotice(
            firstBuild = 1,
            legacyColorRows = 3,
            seen = setOf(UpdateNotice.CLASS_COLORS),
        ) shouldBe null
    }

    // MARK: - The Discord invite's bar

    /**
     * The invite needs three launches, one logged dose, and no prior answer.
     *
     * Each clause is a different reason not to ask: three launches so it is **not the first session**, a logged
     * dose so the user is actually using the app rather than having opened it three times, and neither shown nor
     * dismissed so it is an invitation rather than a nag.
     */
    @Test
    fun `the invite needs launches and a logged dose`() {
        // The bar, exactly.
        LaunchNotices.discordInviteIsDue(launchCount = 3, doseCount = 1, alreadyShown = false, dismissedForever = false) shouldBe true
        // Two launches is the first session twice.
        LaunchNotices.discordInviteIsDue(launchCount = 2, doseCount = 1, alreadyShown = false, dismissedForever = false) shouldBe false
        // Three launches with nothing logged is someone who opened the app and left.
        LaunchNotices.discordInviteIsDue(launchCount = 3, doseCount = 0, alreadyShown = false, dismissedForever = false) shouldBe false
    }

    /** Both a close and a join retire it — there is no repeat nag. */
    @Test
    fun `a shown or dismissed invite is never due again`() {
        LaunchNotices.discordInviteIsDue(launchCount = 99, doseCount = 99, alreadyShown = true, dismissedForever = false) shouldBe false
        LaunchNotices.discordInviteIsDue(launchCount = 99, doseCount = 99, alreadyShown = false, dismissedForever = true) shouldBe false
        LaunchNotices.discordInviteIsDue(launchCount = 99, doseCount = 99, alreadyShown = true, dismissedForever = true) shouldBe false
    }

    // MARK: - Priority, and nothing before onboarding

    /**
     * Nothing rises until onboarding is finished.
     *
     * A sheet over the onboarding flow is a modal on top of a modal, and the first thing a new user sees would be a
     * notice about a change they are not affected by.
     */
    @Test
    fun `nothing rises before onboarding is done`() {
        LaunchNotices.nextSheet(
            onboardingComplete = false,
            firstBuild = 0,
            legacyColorRows = 5,
            seenNotices = emptySet(),
            launchCount = 99,
            doseCount = 99,
            discordShown = false,
            discordDismissed = false,
        ) shouldBe null
    }

    /**
     * The notice comes before the invite.
     *
     * The priority, stated once. The notice is a decision that needs the user; the invite is an offer, and an offer
     * should never be the reason a decision goes unmade.
     */
    @Test
    fun `the update notice takes priority over the invite`() {
        val sheet = LaunchNotices.nextSheet(
            onboardingComplete = true,
            firstBuild = 0,
            legacyColorRows = 5,
            seenNotices = emptySet(),
            launchCount = 99,
            doseCount = 99,
            discordShown = false,
            discordDismissed = false,
        )
        sheet shouldBe LaunchSheet.UpdateNotice(UpdateNotice.CLASS_COLORS)
    }

    /** And the invite is offered once the notice is answered. */
    @Test
    fun `the invite is offered once the notice is answered`() {
        val sheet = LaunchNotices.nextSheet(
            onboardingComplete = true,
            firstBuild = 0,
            // The migration resolved, so there is nothing left to decide.
            legacyColorRows = 0,
            seenNotices = setOf(UpdateNotice.CLASS_COLORS),
            launchCount = 5,
            doseCount = 2,
            discordShown = false,
            discordDismissed = false,
        )
        sheet shouldBe LaunchSheet.DiscordInvite
    }

    /**
     * **At most one sheet per launch.**
     *
     * Upstream's rule, and the reason both gates are asked in one function: two sheets racing each other's
     * presentation is a dialog that never appears, or two stacked.
     */
    @Test
    fun `at most one sheet is ever returned`() {
        // Every combination of the four booleans and a few counts.
        var sheetsRaised = 0
        for (onboarding in listOf(true, false)) {
            for (legacy in listOf(0, 3)) {
                for (launches in listOf(0, 5)) {
                    for (doses in listOf(0, 2)) {
                        val sheet = LaunchNotices.nextSheet(
                            onboardingComplete = onboarding,
                            firstBuild = 0,
                            legacyColorRows = legacy,
                            seenNotices = emptySet(),
                            launchCount = launches,
                            doseCount = doses,
                            discordShown = false,
                            discordDismissed = false,
                        )
                        if (sheet != null) sheetsRaised++
                    }
                }
            }
        }
        // Some combinations do raise something, so the loop is not vacuous.
        (sheetsRaised > 0) shouldBe true
    }

    // MARK: - The enum

    /** The wire values round-trip, because the seen flags are keyed by them. */
    @Test
    fun `the wire values round-trip`() {
        for (notice in UpdateNotice.entries) {
            UpdateNotice.fromWire(notice.wireValue) shouldBe notice
        }
        UpdateNotice.fromWire("a-notice-that-was-removed") shouldBe null
        UpdateNotice.fromWire(null) shouldBe null
    }

    /**
     * The introducing build is a **literal**, not the current build number.
     *
     * A gate comparing a build against itself would never fire, which is the kind of change that looks like a
     * simplification and silently disables the notice for everyone.
     */
    @Test
    fun `the introducing build is a fixed historical number`() {
        UpdateNotice.CLASS_COLORS.introducedInBuild shouldBe 53
    }
}
