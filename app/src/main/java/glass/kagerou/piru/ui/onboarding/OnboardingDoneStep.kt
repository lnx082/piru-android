package glass.kagerou.piru.ui.onboarding

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.R

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
        title = stringResource(R.string.shell_onboarding_done_title),
        subtitle = stringResource(R.string.shell_onboarding_done_subtitle),
        hero = { OnboardingIconHero(Icons.Filled.CheckCircle, size = 108.dp) },
        mid = {
            OnboardingGroupedCard(
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp),
            ) {
                OnboardingBulletRow(
                    icon = Icons.Filled.DateRange,
                    title = stringResource(R.string.shell_onboarding_done_timeline_title),
                    detail = stringResource(R.string.shell_onboarding_done_timeline_detail),
                )
                OnboardingBulletRow(
                    icon = Icons.Filled.Search,
                    title = stringResource(R.string.shell_onboarding_done_lookup_title),
                    detail = stringResource(R.string.shell_onboarding_done_lookup_detail),
                )
                OnboardingBulletRow(
                    icon = Icons.Filled.Lock,
                    title = stringResource(R.string.shell_onboarding_done_private_title),
                    detail = stringResource(R.string.shell_onboarding_done_private_detail),
                )
            }
        },
    ) {
        OnboardingPillButton(title = stringResource(R.string.shell_onboarding_start_using), onClick = nav.finish)
    }
}
