package glass.kagerou.piru.ui.onboarding

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.R

/**
 * Pick a look — the one step that mentions money, and the one this build has the
 * least of.
 *
 * Ported from `OnboardingSkinsStep.swift` (38 lines).
 *
 * ## No hero, and no purchase anywhere on the screen
 * The source deliberately pairs `EmptyView` as the hero with a single "Continue",
 * and its comment states the rule the whole step exists to serve: *"Continue is
 * never held back and never smaller than the purchase."* That rule survives here
 * **vacuously**, because the MVP ships no skin store and no billing — every skin
 * is unlocked, so there is no purchase for Continue to be smaller than. What is
 * left is the step's placement in the flow and its one button.
 *
 * ## What replaces the wardrobe and the shop
 * `SkinWardrobe()` and `SkinShopOffers()` do not exist in this port: the theme
 * carries one skin, and `SettingsScreen` already says so in the app's own words
 * ("Piru ships one skin in this build. The alternate skins and the light/dark
 * override arrive with the appearance screen."). The step says the same thing
 * here rather than rendering an empty wardrobe, and it does not keep the
 * source's subtitle, which promises that skins pay for development — there is
 * nothing in this build for that sentence to be true about.
 *
 * `SkinStore.settleTryOn()` is likewise absent from the flow's finish, which is
 * the other half of the same fact: there is no try-on to settle.
 */
@Composable
fun OnboardingSkinsStep(nav: OnboardingNav) {
    OnboardingLayout(
        title = stringResource(R.string.shell_onboarding_skins_title),
        subtitle = stringResource(R.string.shell_onboarding_skins_subtitle),
        mid = {
            OnboardingGroupedCard(
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp),
            ) {
                OnboardingBulletRow(
                    icon = Icons.Filled.Star,
                    title = stringResource(R.string.shell_onboarding_skins_one_look_title),
                    detail = stringResource(R.string.shell_onboarding_skins_one_look_detail),
                )
                OnboardingBulletRow(
                    icon = Icons.Filled.Lock,
                    title = stringResource(R.string.shell_onboarding_skins_nothing_to_buy_title),
                    detail = stringResource(R.string.shell_onboarding_skins_nothing_to_buy_detail),
                )
            }
        },
    ) {
        OnboardingPillButton(title = stringResource(R.string.shell_continue), onClick = nav.advance)
    }
}
