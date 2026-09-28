package glass.kagerou.piru.ui.onboarding

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The last screen: what to do next, and what the app is.
 *
 * Ported from `OnboardingDoneStep` in `OnboardingStepViews.swift`.
 *
 * ## The three bullets are not the source's three, and here is the accounting
 * This step has no progress bar and one button, and its bullets are the app's
 * parting claims — which makes it the worst place to keep a sentence that is not
 * true of the build. The source's three:
 *
 * | Source bullet | Here |
 * |---|---|
 * | `bolt.heart` "Live Activity, when you want it" | replaced — see below |
 * | `archivebox.fill` "Track your stock" | replaced |
 * | `lock.shield` "Back up anytime" | replaced |
 *
 * **Live Activity is out of the MVP deliberately** — downgraded to nothing rather
 * than reimplemented as a foreground service — so the source's sentence ("watch
 * it on your Lock Screen") describes a thing this app cannot do, and there is no
 * honest Android equivalent to swap in that is not simply a different feature.
 *
 * **Inventory and backup** are the same class of problem from the other
 * direction: the entities exist in `:core:data` and `ToolsScreen` lists both as
 * unported with what they need, so neither has a screen behind it. "Track your
 * stock" and "Back up anytime, from Tools" would each send the reader to a place
 * that is not there.
 *
 * So the three bullets describe what this build does — the timeline, the
 * reference, and the fact that none of it leaves the device. Each replacement
 * keeps the slot's intent: what you do next, what you can look up, and what you
 * do not have to worry about. The originals belong back here the moment their
 * screens land, and this comment is the record of why they are gone.
 *
 * ## The subtitle is adapted for the same reason
 * Upstream points at a tips system (`OnboardingTips.markOnboardingComplete()` is
 * called when the flow finishes). That is not ported, so the sentence explaining
 * that tips will appear is not true yet either.
 */
@Composable
fun OnboardingDoneStep(nav: OnboardingNav) {
    OnboardingLayout(
        title = "You're all set",
        subtitle = "Tap the + button to record your first entry. Everything else is a tab away.",
        hero = { OnboardingIconHero(Icons.Filled.CheckCircle, size = 108.dp) },
        mid = {
            OnboardingGroupedCard(
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp),
            ) {
                OnboardingBulletRow(
                    icon = Icons.Filled.DateRange,
                    title = "Watch a session take shape",
                    detail = "Log a dose and it becomes a curve on the timeline, with the " +
                        "overlaps and the fade-out drawn in.",
                )
                OnboardingBulletRow(
                    icon = Icons.Filled.Search,
                    title = "Look anything up",
                    detail = "Dosing, duration, effects and interactions for 1,500+ substances, " +
                        "each with its sources named.",
                )
                OnboardingBulletRow(
                    icon = Icons.Filled.Lock,
                    title = "Yours alone",
                    detail = "The journal lives on this device. No account, no server, no ads.",
                )
            }
        },
    ) {
        OnboardingPillButton(title = "Start Using Piru", onClick = nav.finish)
    }
}
