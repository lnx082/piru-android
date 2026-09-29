package glass.kagerou.piru.ui.onboarding

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import glass.kagerou.piru.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme

/**
 * The onboarding flow's shared scaffolding.
 *
 * Ported from `Piru/Views/Onboarding/OnboardingView.swift` (241 lines) and the
 * reusable half of `OnboardingStepViews.swift` (375 lines).
 *
 * ## Chrome, split the way the port splits screens
 * Upstream reaches the navigation closures through `@Entry var onboardingNav`
 * because a SwiftUI `ViewModifier` cannot be handed them. This port passes
 * [OnboardingNav] down as an argument instead, which is what the rest of the
 * Kotlin screens do with `AppNavigator` — one less indirection, and a reader of
 * a step can see what it is allowed to do.
 *
 * ## SF Symbols have no counterpart, and this build has 49 icons
 * The app deliberately ships `material-icons-core` alone; `extended` is a few
 * thousand vectors for the handful of glyphs the flow uses. So each symbol the
 * source names is substituted from the core set, and the substitution is
 * **recorded here rather than hidden** — the same call `PiruApp.kt` makes for
 * the tab bar. Upstream marks all of these decorative
 * (`.accessibilityHidden(true)`), so nothing is lost to a screen reader.
 *
 * | Source (SF Symbol) | Here | Note |
 * |---|---|---|
 * | `AppIconArtwork` | `Icons.Filled.Star` | no launcher artwork in this build; see [OnboardingAppIconHero] |
 * | `lock.shield` | `Icons.Filled.Lock` | |
 * | `iphone` | `Icons.Filled.Phone` | |
 * | `square.and.arrow.up` | `Icons.Filled.Share` | |
 * | `hand.raised` | `Icons.Filled.Info` | "no trackers" points at About |
 * | `slider.horizontal.3` | `Icons.Filled.Menu` | three sliders, three bars |
 * | `heart.text.square.fill` | `Icons.Filled.Favorite` | |
 * | `checkmark.circle` | `Icons.Filled.CheckCircle` | |
 * | `exclamationmark.circle` | `Icons.Filled.Warning` | |
 * | `bell.badge` | `Icons.Filled.Notifications` | |
 * | `repeat` | `Icons.Filled.Refresh` | |
 * | `drop` | `Icons.Filled.PlayArrow` | there is no droplet in core; the row is about a running session, not water alone |
 * | `exclamationmark.triangle` | `Icons.Filled.Warning` | |
 * | `square.and.arrow.down` | `Icons.Filled.KeyboardArrowDown` | |
 * | `arrow.down.doc` | `Icons.Filled.KeyboardArrowDown` | |
 * | `doc.text` | `Icons.Filled.Create` | a page with a nib on it |
 * | `checkmark.seal.fill` | `Icons.Filled.CheckCircle` | |
 * | `bolt.heart` | (gone) | the bullet it marked is replaced — see `OnboardingDoneStep` |
 * | `archivebox.fill` | `Icons.Filled.List` | |
 * | `chart.bar.fill` | `Icons.Filled.Star` | |
 *
 * ## What the source's chrome carries and this does not
 * `.skinBackdrop()` is the skin's own backdrop, and the MVP ships one skin whose
 * backdrop is [PiruTheme.colors]`.background` — so the chrome paints that
 * directly rather than routing through a skin store.
 */
// MARK: - Step model

/**
 * The nine steps, **in the order they run**.
 *
 * Kotlin's enum `compareTo` is final, so declaration order here *is* the
 * ordering semantics: `next` walks `ordinal + 1`, and reordering the entries
 * silently reorders the flow. The source declares the same nine cases in the
 * same order.
 */
enum class OnboardingStep {
    WELCOME,
    PRIVACY,
    TOUR,
    DEPTH,
    HEALTH,
    REMINDERS,
    IMPORT_DATA,
    SKINS,
    DONE,
    ;

    /** The step after this one, or null at [DONE]. */
    val next: OnboardingStep?
        get() = entries.getOrNull(ordinal + 1)

    companion object {
        /**
         * The steps that show the progress bar **and** a back affordance — seven
         * of the nine. `welcome` and `done` are deliberately chromeless for a
         * cleaner first and last impression, which is why the bar reads
         * "step 3 of 7" rather than "of 9".
         */
        val PROGRESS_STEPS: List<OnboardingStep> = listOf(
            PRIVACY,
            TOUR,
            DEPTH,
            HEALTH,
            REMINDERS,
            IMPORT_DATA,
            SKINS,
        )
    }
}

/**
 * What a step may do to the flow.
 *
 * Only forward and finish: going back is the system back gesture, and a step
 * that could jump to an arbitrary index would break the "no step can be
 * skipped" property by skipping forward.
 */
class OnboardingNav(
    val advance: () -> Unit,
    val finish: () -> Unit,
)

// MARK: - Chrome

/**
 * Wraps each step in the flow's shared frame: the backdrop, the top bar with the
 * progress indicator, and — on the welcome root alone — the one bail-out the
 * flow has.
 *
 * The back chevron appears on every pushed step, matching the system back button
 * the source gets for free from its `NavigationStack`. It is *not* limited to
 * [OnboardingStep.PROGRESS_STEPS]: `done` carries no progress bar but is still a
 * pushed step, and losing back there would strand anyone who wanted to re-read
 * the import step.
 *
 * @param canGoBack false only on the welcome root.
 * @param onSkip the source's `Button("Skip", action: nav.finish)` — the single
 *   entry point that leaves the whole flow, and it exists only on welcome.
 */
@Composable
fun OnboardingStepChrome(
    step: OnboardingStep,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = PiruTheme.colors
    Column(modifier = modifier.fillMaxSize().background(colors.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A spacer where the back button goes, so the progress bar is centred
            // on the screen rather than on whatever is left of it.
            if (canGoBack) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.shell_back),
                        tint = colors.secondaryLabel,
                    )
                }
            } else {
                Spacer(Modifier.width(48.dp))
            }

            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (step in OnboardingStep.PROGRESS_STEPS) {
                    OnboardingProgressBar(
                        current = OnboardingStep.PROGRESS_STEPS.indexOf(step) + 1,
                        total = OnboardingStep.PROGRESS_STEPS.size,
                        modifier = Modifier.width(210.dp),
                    )
                }
            }

            if (step == OnboardingStep.WELCOME) {
                TextButton(onClick = onSkip) {
                    Text(
                        stringResource(R.string.shell_skip),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.secondaryLabel,
                    )
                }
            } else {
                Spacer(Modifier.width(48.dp))
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

/**
 * The segmented progress indicator: one capsule per counted step, filled up to
 * and including the current one.
 *
 * ## One accessibility node, not seven
 * Upstream collapses the row into a single element reading "Progress, Step 3 of
 * 7", and this does the same by clearing the semantics of the children — seven
 * separate unlabelled capsules are worse than useless to a screen reader.
 */
@Composable
fun OnboardingProgressBar(current: Int, total: Int, modifier: Modifier = Modifier) {
    val colors = PiruTheme.colors
    // Hoisted above the semantics block: `stringResource` is a composable read and
    // `semantics { }` is not a composable lambda.
    val label = stringResource(R.string.shell_progress)
    val state = stringResource(R.string.shell_progress_step, current, total)
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = label
            stateDescription = state
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (index in 0 until total) {
            val filled by animateColorAsState(
                targetValue = if (index < current) colors.accent else colors.accent.copy(alpha = 0.2f),
                label = "progress",
            )
            Box(modifier = Modifier.weight(1f).height(4.dp).clip(CircleShape).background(filled))
        }
    }
}

/**
 * The step scaffold: a hero visual, a bold title and subtitle, optional mid
 * content, and a pinned footer of up to two buttons.
 *
 * ## `minHeight` is the load-bearing part
 * The middle scroller is given a minimum height of the viewport, so a step that
 * does not fill the screen is **centred rather than top-aligned**, and a step
 * that over-subscribes it scrolls instead of squeezing. The source's comment
 * explains what went wrong without it: the health step's three flexible `Text`
 * children absorbed a compressed height proposal by truncating to one line
 * ("Connect Apple Hea…"). Compose fails differently — a `Column` handed less
 * height than it needs simply clips — but the fix is the same one.
 *
 * The footer sits outside the scroller and never moves, which is what keeps
 * "Continue" in the same place on all nine steps.
 */
@Composable
fun OnboardingLayout(
    title: String,
    subtitle: String? = null,
    hero: (@Composable () -> Unit)? = null,
    mid: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    footer: @Composable () -> Unit,
) {
    val colors = PiruTheme.colors
    Column(modifier = modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val viewport = maxHeight
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(min = viewport),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Spacer(Modifier.height(8.dp))
                    if (hero != null) {
                        hero()
                        Spacer(Modifier.height(28.dp))
                    }
                    Column(
                        modifier = Modifier.padding(horizontal = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                        if (subtitle != null) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.secondaryLabel,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    if (mid != null) mid()
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { footer() }
    }
}

// MARK: - Heroes

/**
 * A rounded tile with an accent wash and a centred glyph — the hero for the
 * text-forward steps.
 */
@Composable
fun OnboardingIconHero(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 96.dp) {
    val colors = PiruTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(colors.accent.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(size * 0.46f),
        )
    }
}

/**
 * The welcome hero.
 *
 * **Degraded, and named as such.** The source shows `Image("AppIconArtwork")` —
 * the OS-rendered app icon, which is the whole point of the hero being "the
 * app's face rather than a generic SF Symbol". This build has no launcher
 * artwork to show: `app/src/main/res/` carries `strings.xml` and `themes.xml`
 * and no mipmap, so the app wears the platform's default icon. Rather than
 * inventing a mark, the hero falls back to the shared icon tile.
 *
 * Whoever adds a launcher icon should point this at it; the call site is already
 * the only thing that would change.
 */
@Composable
fun OnboardingAppIconHero(modifier: Modifier = Modifier, size: Dp = 108.dp) {
    // The launcher icon itself, not a stand-in for it: the first screen of a new
    // install shows the same mark the user just tapped, which is the one place
    // the app can confirm they opened the right thing.
    //
    // Drawn from the same two drawables the adaptive icon composes — the accent
    // as the tile, the curve tinted onto it — so there is one source rather than
    // a second copy that drifts.
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(PiruTheme.colors.accent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size),
        )
    }
}

// MARK: - Rows

/**
 * The app's grouped-card surface around a list of rows, so the "what you get"
 * lists read as one card rather than as floating text.
 */
@Composable
fun OnboardingGroupedCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    PiruCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            content = content,
        )
    }
}

/** Icon, title, supporting line — the small "what you get" rows inside steps. */
@Composable
fun OnboardingBulletRow(
    icon: ImageVector,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    val colors = PiruTheme.colors
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OnboardingRowIcon(icon)
        Column(
            modifier = Modifier.padding(start = 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.secondaryLabel)
        }
    }
}

/**
 * A [OnboardingBulletRow] with a trailing switch.
 *
 * The title and detail stay in the column beside the icon rather than inside the
 * `Switch`'s own label slot: putting them in the label makes Material measure
 * them at the switch's baseline and centre the pair, which is not the rhythm the
 * source has.
 */
@Composable
fun OnboardingToggleRow(
    icon: ImageVector,
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PiruTheme.colors
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        OnboardingRowIcon(icon)
        Column(
            modifier = Modifier.weight(1f).padding(start = 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.secondaryLabel)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = colors.accent),
        )
    }
}

// MARK: - Buttons

/**
 * The flow's one CTA shape: a full-width pill.
 *
 * [Prominence.PROMINENT] is the accent fill (the source's `.prominent`); the
 * neutral variant is the plain escape hatch for skip and not-now, and it is a
 * `TextButton` rather than a filled one on purpose — a step where the skip and
 * the primary look alike is a step where people tap skip.
 */
@Composable
fun OnboardingPillButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    prominence: Prominence = Prominence.PROMINENT,
    enabled: Boolean = true,
) {
    val colors = PiruTheme.colors
    when (prominence) {
        Prominence.PROMINENT -> Button(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().height(52.dp),
            enabled = enabled,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = Color.White,
                disabledContainerColor = colors.accent.copy(alpha = 0.4f),
                disabledContentColor = Color.White.copy(alpha = 0.7f),
            ),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }

        Prominence.NEUTRAL -> TextButton(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().height(52.dp),
            enabled = enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = colors.secondaryLabel),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * The 34dp leading gutter both row types share.
 *
 * A fixed slot rather than an intrinsic icon width, so the titles of a bullet
 * row and a toggle row in the same card line up even though their glyphs are
 * drawn at different aspect ratios.
 */
@Composable
private fun OnboardingRowIcon(icon: ImageVector) {
    Box(modifier = Modifier.width(34.dp), contentAlignment = Alignment.Center) {
        Icon(
            icon,
            contentDescription = null,
            tint = PiruTheme.colors.accent,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Which of the two weights a pill button is drawn at. */
enum class Prominence { PROMINENT, NEUTRAL }

/**
 * A one-line footnote under a control: an icon and a sentence in the secondary
 * label colour.
 *
 * Ported from the health step's `noteView`, which has three states and was the
 * element the source's height bug truncated.
 */
@Composable
fun OnboardingNote(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    val colors = PiruTheme.colors
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(
            icon,
            contentDescription = null,
            tint = colors.secondaryLabel,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text,
            modifier = Modifier.padding(start = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = colors.secondaryLabel,
        )
    }
}

// MARK: - Preferences

/**
 * Every key the flow writes, in one place.
 *
 * Upstream has two homes for this state: `@AppStorage("hasCompletedOnboarding")`
 * in the standard suite, and the app-group suite for anything the whole app
 * agrees on (`showSessionVitals`). Android has no app group, so both live here —
 * one file, named once, so the notification layer and the shell read the same
 * values the flow wrote rather than a second set of keys that look similar.
 *
 * ## Why the tier and the weight are here rather than in the store
 * The source writes both through `UserProfileStore` onto `UserProfileRecord`.
 * `UserProfileRecordEntity` exists in `:core:data` but the DAO and the store do
 * not, so the flow has nowhere durable to put them. Writing the same field names
 * here keeps the step from being decorative, and the values are the ones the
 * entity documents — including `"harm-reduction"` for the tier the UI calls
 * "Curious", which is a **wire value**: it is not consumer copy and must not be
 * renamed to match the copy rules either.
 */
object OnboardingPrefs {

    const val FILE = "piru.onboarding"

    const val KEY_COMPLETED = "hasCompletedOnboarding"
    const val KEY_DOSE_REMINDERS = "onboardingDoseReminders"
    const val KEY_SESSION_ALERTS = "onboardingSessionAlerts"
    const val KEY_SAFETY_NET = "onboardingSafetyNet"
    const val KEY_SHOW_SESSION_VITALS = "showSessionVitals"
    const val KEY_DISCLOSURE_TIER = "disclosureTier"
    /** The tier wire value the UI calls "Curious" — see `UserProfileRecordEntity`. */
    const val TIER_CURIOUS = "harm-reduction"
    const val TIER_CASUAL = "casual"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun hasCompleted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COMPLETED, false)

    fun markCompleted(context: Context) {
        prefs(context).edit().putBoolean(KEY_COMPLETED, true).apply()
    }

    /**
     * The three notification groups the reminders step asked about.
     *
     * Written **whether or not** the system granted the permission: the grant is
     * a system fact the Notifications screen reports, and the user's choice is a
     * separate fact that must survive a denial so a later re-grant does not
     * silently re-enable a group they declined.
     */
    fun writeReminderChoices(
        context: Context,
        doseReminders: Boolean,
        sessionAlerts: Boolean,
        safetyNet: Boolean,
    ) {
        prefs(context).edit()
            .putBoolean(KEY_DOSE_REMINDERS, doseReminders)
            .putBoolean(KEY_SESSION_ALERTS, sessionAlerts)
            .putBoolean(KEY_SAFETY_NET, safetyNet)
            .apply()
    }

    /** The health step's opt-in to the session vitals overlay; off-able in Settings. */
    fun writeShowSessionVitals(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_SESSION_VITALS, value).apply()
    }

    /**
     * Whether the vitals overlay is opted into.
     *
     * False until the user grants Health access on the health step, which is what
     * upstream's `showSessionVitals` defaults to as well: the overlay is the one
     * place another app's readings are drawn inside the user's own log, so it is
     * opt-in rather than on-by-default-with-a-toggle.
     */
    fun showSessionVitals(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_SESSION_VITALS, false)

    /** Kept only so a stale key from an earlier build is not left behind. See `writeDisclosureTier`'s replacement, `PiruApplication.setDisclosureTier`. */
    fun clearRetiredKeys(context: Context) {
        prefs(context).edit()
            .remove(KEY_DISCLOSURE_TIER)
            .remove("bodyWeightKg")
            .remove("bodyWeightSource")
            .apply()
    }

    // The body weight is not here. It goes into the profile row in the store —
    // `PiruApplication.setBodyWeight` — because it scales every dose model, and a
    // preferences file is not what those read. It used to be written here, to two
    // keys nothing ever read.
}
