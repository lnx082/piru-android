package glass.kagerou.piru.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import glass.kagerou.piru.model.P3Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's colour tokens.
 *
 * Ported from `Piru/Theme.swift` and the colour sets under
 * `Shared/Assets.xcassets/`.
 *
 * ## Tokens, not a Material scheme
 * Upstream resolves five surfaces and a handful of semantics through a *skin*
 * (`SkinStore.current`), and the ~1,000 `Theme.*` call sites read them as
 * computed properties so a skin change re-renders without touching any of them.
 * The MVP ships the one default skin, so this is a plain immutable value rather
 * than a store — but it is still a `CompositionLocal` and not a `ColorScheme`,
 * because the two are not the same vocabulary: Material has no `tertiaryLabel`
 * and no paired `caution`/`cautionText`, and forcing these into its slots is how
 * a port starts drifting from the screen it is copying.
 *
 * ## The values are display-P3, and that is not decoration
 * Every colour set in the asset catalog declares `"color-space": "display-p3"`.
 * Handing those components to `Color(red, green, blue)` would read them as sRGB —
 * the accent would land visibly duller than the iOS build renders it, and the
 * *substance* identity colours worse still, since that pipeline was designed
 * around P3 gamut. So the space travels with the value.
 *
 * ## What the MVP does not carry
 * The 16 alternate skins, the `.glass`/`.edged` surface treatments (translucency
 * and inset dashes), and the Increases-Contrast variants — [tertiaryLabel] is
 * therefore the plain tertiary rather than the raised one upstream substitutes
 * when that setting is on.
 */
@Immutable
data class PiruColors(
    val accent: Color,
    /** De-emphasized body text. Never the framework's own secondary — see the note below. */
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val background: Color,
    val cardBackground: Color,
    val inputBackground: Color,
    val caution: Color,
    val cautionText: Color,
    val danger: Color,
    val dangerText: Color,
    val info: Color,
    val infoText: Color,
    val success: Color,
    val successText: Color,
    val isDark: Boolean,
) {
    /** The standard card corner. `22` matches the system grouped-list rounding upstream copies. */
    val cardCornerRadius: Dp get() = 22.dp

    /** The standard card shape. */
    val cardShape: Shape get() = RoundedCornerShape(cardCornerRadius)
}

/**
 * The five tokens and the four semantic pairs, read through the active skin.
 *
 * `staticCompositionLocalOf` rather than `compositionLocalOf`: a skin does not
 * change within a run in the MVP, so readers need not be tracked individually.
 */
val LocalPiruColors = staticCompositionLocalOf<PiruColors> {
    error("No PiruColors provided — wrap the tree in PiruTheme.")
}

/** The active tokens. */
object PiruTheme {
    val colors: PiruColors
        @Composable @ReadOnlyComposable get() = LocalPiruColors.current
}

// MARK: - The default skin

/**
 * Soft pink in light, hot pink in dark — the brand accent from
 * `AccentColor.colorset`.
 */
/**
 * A Display-P3 colour as a Compose one.
 *
 * **The only definition in the app.** This was four — in the journal's graph, the
 * inventory card, the colour picker and a tools helper — each of which had to
 * choose the colour space and each of which documented that the choice mattered.
 * One of them getting it wrong is a screen that renders visibly duller than its
 * neighbours, with nothing to point at as the cause.
 *
 * ## The colour space is the point
 * Compose's `Color(red, green, blue)` constructor takes **sRGB**. The substance
 * and class colours are authored in Display-P3 (see `SubstanceColorGenerator`),
 * so handing their components to that constructor reinterprets them rather than
 * converting them — the same numbers through a narrower window, which comes out
 * muted. Carrying the space through is what keeps a substance one colour on every
 * screen and the same colour it is on iOS.
 */
internal fun P3Color.toComposeColor(): Color =
    Color(red.toFloat(), green.toFloat(), blue.toFloat(), 1f, ColorSpaces.DisplayP3)

private val AccentLight = p3(0.898, 0.497, 0.591)
private val AccentDark = p3(0.920, 0.268, 0.441)

/**
 * A colour from the asset catalog's display-P3 components.
 *
 * Written once here rather than at each constant so the colour space cannot be
 * dropped by accident at one of them.
 */
private fun p3(red: Double, green: Double, blue: Double): Color =
    Color(red.toFloat(), green.toFloat(), blue.toFloat(), 1f, ColorSpaces.DisplayP3)

private val LightColors = PiruColors(
    accent = AccentLight,
    secondaryLabel = p3(0.431, 0.431, 0.449),
    tertiaryLabel = p3(0.431, 0.431, 0.449).copy(alpha = 0.6f),
    // True white, and true black in dark — upstream keeps the backdrop pure so an
    // OLED panel spends nothing drawing it.
    background = p3(1.000, 1.000, 1.000),
    cardBackground = p3(0.949, 0.949, 0.967),
    inputBackground = p3(0.949, 0.949, 0.967),
    caution = p3(0.656, 0.525, 0.000),
    cautionText = p3(0.513, 0.408, 0.000),
    danger = p3(0.982, 0.007, 0.001),
    dangerText = p3(0.812, 0.013, 0.005),
    info = p3(0.000, 0.560, 0.973),
    infoText = p3(0.001, 0.419, 0.738),
    success = p3(0.000, 0.648, 0.158),
    successText = p3(0.000, 0.494, 0.112),
    isDark = false,
)

private val DarkColors = PiruColors(
    accent = AccentDark,
    secondaryLabel = p3(0.828, 0.828, 0.859),
    tertiaryLabel = p3(0.828, 0.828, 0.859).copy(alpha = 0.6f),
    background = p3(0.000, 0.000, 0.000),
    cardBackground = p3(0.067, 0.067, 0.067),
    inputBackground = p3(0.110, 0.110, 0.121),
    caution = p3(0.992, 0.799, 0.000),
    cautionText = p3(0.992, 0.799, 0.000),
    danger = p3(0.982, 0.007, 0.001),
    dangerText = p3(0.996, 0.354, 0.278),
    info = p3(0.000, 0.560, 0.973),
    infoText = p3(0.198, 0.613, 0.999),
    success = p3(0.000, 0.998, 0.265),
    successText = p3(0.000, 0.998, 0.265),
    isDark = true,
)

/**
 * The app's theme.
 *
 * The Material scheme is derived from the tokens rather than the other way round,
 * so a Material component dropped into the tree lands on the same accent and
 * surfaces as the hand-drawn ones. It is a courtesy to the framework, not the
 * source of truth.
 *
 * ## One token deliberately not delegated
 * [PiruColors.secondaryLabel] is **not** Material's `onSurfaceVariant`.
 * Upstream's comment on it is a warning worth carrying across: swapping it for
 * the system secondary measured 2.17:1 on the light card and failed WCAG AA. The
 * value in the table above is the one that passes.
 */
@Composable
fun PiruTheme(
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            background = colors.background,
            surface = colors.cardBackground,
            surfaceVariant = colors.inputBackground,
            error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            background = colors.background,
            surface = colors.cardBackground,
            surfaceVariant = colors.inputBackground,
            error = colors.danger,
        )
    }
    CompositionLocalProvider(LocalPiruColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
