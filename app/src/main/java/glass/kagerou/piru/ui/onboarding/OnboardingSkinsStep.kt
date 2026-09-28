package glass.kagerou.piru.ui.onboarding

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
        title = "Make it yours",
        subtitle = "This build ships one skin. The journal, the library, and every tool are free.",
        mid = {
            OnboardingGroupedCard(
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp),
            ) {
                OnboardingBulletRow(
                    icon = Icons.Filled.Star,
                    title = "One look, for now",
                    detail = "The alternate skins arrive with the appearance screen, which is " +
                        "not in this build yet.",
                )
                OnboardingBulletRow(
                    icon = Icons.Filled.Lock,
                    title = "Nothing to buy",
                    detail = "There is no payment code here, and no feature behind one.",
                )
            }
        },
    ) {
        OnboardingPillButton(title = "Continue", onClick = nav.advance)
    }
}
