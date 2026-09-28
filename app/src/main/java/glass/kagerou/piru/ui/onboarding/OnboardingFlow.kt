package glass.kagerou.piru.ui.onboarding

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import glass.kagerou.piru.ui.theme.PiruTheme

/**
 * First-run onboarding: a paged, progressive-commitment flow, welcome through
 * done.
 *
 * Ported from `Piru/Views/Onboarding/OnboardingView.swift` (241 lines).
 *
 * ## The design, since it explains the shapes of all seven files
 * The health/wellness pattern: the earliest screens ask for **nothing** — the
 * value proposition, a privacy reassurance, a feature tour — so the user is
 * invested before any permission is raised, and every step after the intro is
 * skippable so nothing blocks the way into the app. Permissions are asked
 * just-in-time, each beside the value it unlocks, rather than batched into a wall
 * of toggles.
 *
 * ## Nine steps, seven of them counted, and no step you cannot leave
 * `welcome` (0) through `done` (8) run in the order [OnboardingStep] declares.
 * `welcome` and `done` carry **no progress bar and no back** — they are the
 * bookends, deliberately chromeless. Every step has a forward button, none is
 * gated on a permission, and the only way to leave the whole flow early is the
 * "Skip" in welcome's top bar.
 *
 * ## Navigation, without a navigation library
 * The source gets its push/pop and system back button from a `NavigationStack`,
 * and its progress bar rides in the nav bar's principal slot. This port keeps the
 * stack as a plain `List<OnboardingStep>` — the same "value stack" shape
 * `AppNavigator` uses — because `navigation-compose` is not on this module's
 * classpath and adopting it here would mean two navigation models in one app.
 * The chrome, the back gesture and the transitions are drawn rather than
 * inherited; see [OnboardingStepChrome].
 *
 * ## What finishing does
 * Marks [OnboardingState] complete and calls [onFinished]. Two things the source
 * does that this deliberately does not:
 * - **No `SkinStore.settleTryOn()`.** There is no skin store in the MVP and
 *   nothing to settle — see [OnboardingSkinsStep].
 * - **No `OnboardingTips.markOnboardingComplete()`.** The tips system is not
 *   ported, which is also why the done step's subtitle does not promise tips.
 *
 * The source puts both in `finish()` rather than on the skins step for a reason
 * worth keeping in view: choosing a skin re-creates the app's root, so anything
 * that must happen after the last step belongs where the flow ends.
 *
 * @param onFinished called once, after the completion flag is written. The caller
 *   decides what replaces the flow; this composable renders nothing else.
 */
@Composable
fun OnboardingFlow(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    /** The pushed steps after the welcome root. Empty means the root is showing. */
    var path by remember { mutableStateOf(emptyList<OnboardingStep>()) }
    val current = path.lastOrNull() ?: OnboardingStep.WELCOME

    fun finishFlow() {
        OnboardingState.markCompleted(context)
        onFinished()
    }

    // Rebuilt per composition rather than remembered, so `advance` closes over the
    // step that is on screen right now — a remembered lambda would still be
    // pointing at whichever step was current when it was first captured.
    val nav = OnboardingNav(
        advance = {
            val next = current.next
            if (next == null) finishFlow() else path = path + next
        },
        finish = { finishFlow() },
    )

    // Back pops one step, and does nothing on the welcome root. The source
    // disables interactive dismissal entirely (`interactiveDismissDisabled`), so
    // there is no gesture that leaves the flow sideways; on Android that gesture
    // is the system back button, and swallowing it at the root is what matches.
    // The flow's own exits — "Skip", and "Start Using Piru" — are the only ones.
    BackHandler(enabled = true) {
        if (path.isNotEmpty()) path = path.dropLast(1)
    }

    OnboardingStepChrome(
        step = current,
        canGoBack = path.isNotEmpty(),
        onBack = { path = path.dropLast(1) },
        onSkip = { finishFlow() },
        modifier = modifier,
    ) {
        // A fade rather than a directional slide: the direction of travel is not
        // always forward here, and the shell's own tab changes fade for the same
        // reason.
        AnimatedContent(
            targetState = current,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "onboarding-step",
        ) { step ->
            when (step) {
                OnboardingStep.WELCOME -> OnboardingWelcomeStep(nav)
                OnboardingStep.PRIVACY -> OnboardingPrivacyStep(nav)
                OnboardingStep.TOUR -> OnboardingFeatureTour(nav)
                OnboardingStep.DEPTH -> OnboardingDepthStep(nav)
                OnboardingStep.HEALTH -> OnboardingHealthStep(nav)
                OnboardingStep.REMINDERS -> OnboardingRemindersStep(nav)
                OnboardingStep.IMPORT_DATA -> OnboardingImportStep(nav)
                OnboardingStep.SKINS -> OnboardingSkinsStep(nav)
                OnboardingStep.DONE -> OnboardingDoneStep(nav)
            }
        }
    }
}

/**
 * Whether the flow has been finished, and the one write that says so.
 *
 * ## One flag, and it is the flow's only durable output
 * Everything else a step collects — the notification groups, the disclosure
 * tier, the body weight — has its own key in [OnboardingPrefs], because a step
 * the user revisits should read back what they chose. Whether onboarding ran is
 * a different kind of fact: it is the shell's gate, read before any step
 * composes, and it is written in exactly one place.
 *
 * ## Where the shell calls it
 * ```
 * if (!OnboardingState.hasCompleted(context)) OnboardingFlow(onFinished = { … })
 * ```
 * A `PiruApp` that renders the flow while this is false and the tabs once it is
 * true needs no other state: the flag is written before `onFinished` runs, so a
 * caller that just flips its own `remember` in the callback is already correct.
 */
object OnboardingState {

    /** False until the flow is finished, and never reset — onboarding runs once. */
    fun hasCompleted(context: Context): Boolean = OnboardingPrefs.hasCompleted(context)

    /**
     * Marks the flow complete.
     *
     * Written with `apply()`, which is asynchronous but ordered: a caller that
     * reads [hasCompleted] from the same process after this call sees `true`,
     * because reads go through the same in-memory map the pending write updated.
     */
    fun markCompleted(context: Context) = OnboardingPrefs.markCompleted(context)
}

@Preview
@Composable
private fun OnboardingFlowPreview() {
    PiruTheme {
        OnboardingFlow(onFinished = {})
    }
}
