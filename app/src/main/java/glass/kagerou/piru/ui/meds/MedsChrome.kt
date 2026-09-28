package glass.kagerou.piru.ui.meds

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.model.DoseFrequency
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The meds screens' shared chrome: the glyph set, the capsule chip, the
 * check circle, and the two clock formatters.
 *
 * Upstream has none of this in one file — it reaches for SF Symbols
 * (`Image(systemName:)`), `capsuleChip`, and `Date.formatted(...)` directly.
 * None of those exist in this build, so this file is the single place each is
 * spelled out, for the same reason the port keeps one `FAB_CLEARANCE`: so three
 * screens cannot drift apart on what a "due" chip looks like.
 *
 * ## Glyphs are drawn, not iconed
 * The build depends on `compose-material-icons-core` only — the ~40-glyph
 * default set — which carries no moon, clock, pill, leaf or shipping box. The
 * port's established answer is a `Canvas` (see `InsightsChrome.DisclosureTriangle`,
 * "drawn rather than iconed"), and that is what [MedsGlyph] does. Every draw
 * takes its colour from the `tint` **parameter** rather than reading the theme
 * inside the `Canvas` lambda, which Compose forbids.
 */

// MARK: - Clock formatting

/**
 * "9:00 PM" for minutes from midnight.
 *
 * Reads the ambient locale, deliberately: this is a display preference, the one
 * category of formatting the port does **not** pin to `Locale.ROOT` (see
 * `AdherenceScreen.firstWeekdayOf` for the same reasoning). `Locale.ROOT` is for
 * numbers the model computed, not for a clock the user set.
 */
internal fun timeText(minutes: Int, zone: ZoneId = ZoneId.systemDefault()): String =
    LocalTime.of(minutes / 60, minutes % 60)
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

/** "9:00 PM" for an instant, in [zone]. The [timeText] counterpart. */
internal fun clockText(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    instant.atZone(zone).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

/**
 * "a", "a and b", "a, b and c" — Swift's `formatted(.list(type: .and))`.
 *
 * Kotlin has no list formatter, so this is the one English join the missed-dose
 * notice needs. It is English-only on purpose: the build has no localization
 * layer, and a hand-rolled joiner that pretended otherwise would be worse than
 * one that admits it.
 */
internal fun andList(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    2 -> "${names[0]} and ${names[1]}"
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
}

/** The caption style the meds screens share. */
internal val captionSecondaryStyle: TextStyle
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.typography.bodySmall.copy(color = PiruTheme.colors.secondaryLabel)

/**
 * The cadence a med is on, in the short form upstream's `DoseFrequency.shortLabel`
 * carries.
 *
 * `DoseFrequency` in this port holds only the wire value, and the long name
 * `displayName` belongs to the form's picker rather than to a row subtitle. When
 * the model gains `shortLabel` this is where it plugs in; `AdherenceScreen` keeps
 * its own copy of the same ladder for the same reason.
 */
internal fun frequencyShortLabel(frequency: DoseFrequency): String = when (frequency) {
    DoseFrequency.DAILY -> "Daily"
    DoseFrequency.EVERY_OTHER_DAY -> "Every 2 days"
    DoseFrequency.WEEKLY -> "Weekly"
    DoseFrequency.BIWEEKLY -> "Biweekly"
    DoseFrequency.MONTHLY -> "Monthly"
    DoseFrequency.SPECIFIC_DAYS -> "Custom days"
}

// MARK: - Capsule chip

/**
 * The small rounded label upstream calls `capsuleChip`.
 *
 * The app's only other implementation is `private fun Chip` in
 * `ui/tools/InteractionsScreen.kt`; copying it is the pattern the other two
 * screens already followed, so this is that copy with the meds screens' needs
 * (`text` + `tint`, no onClick) rather than a widened visibility on a file that
 * is about a different feature.
 */
@Composable
internal fun MedsCapsuleChip(
    text: String,
    tint: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (filled) tint else tint.copy(alpha = 0.10f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (filled) Color.White else tint,
        )
    }
}

// MARK: - Check circle

/** The three states a checklist circle can be in. Shared by rows and the fold. */
internal enum class MedCheckState { PENDING, TAKEN, SKIPPED }

/**
 * The checked / unchecked / skipped circle shared by slot rows and the
 * collapsed Supplements row.
 *
 * [due] only colours the pending state: a slot whose time has not come is
 * outlined in the quiet grey, one that is due wears the accent.
 */
@Composable
internal fun MedCheckCircle(
    state: MedCheckState,
    due: Boolean,
    modifier: Modifier = Modifier,
    diameter: Dp = 18.dp,
) {
    val accent = PiruTheme.colors.accent
    val muted = PiruTheme.colors.tertiaryLabel
    Canvas(modifier.size(diameter)) {
        val radius = size.minDimension / 2
        when (state) {
            MedCheckState.TAKEN -> drawCircle(color = accent, radius = radius)
            MedCheckState.SKIPPED -> {
                drawCircle(color = muted, radius = radius - 1f, style = Stroke(width = 2f))
                // "minus.circle": a dash across the middle rather than a tick.
                drawLine(
                    color = muted,
                    start = Offset(size.width * 0.26f, size.height * 0.5f),
                    end = Offset(size.width * 0.74f, size.height * 0.5f),
                    strokeWidth = 2f,
                    cap = StrokeCap.Round,
                )
            }

            MedCheckState.PENDING ->
                drawCircle(
                    color = if (due) accent else muted,
                    radius = radius - 1f,
                    style = Stroke(width = 2f),
                )
        }
    }
}

// MARK: - Glyphs

/** The meds screens' glyph vocabulary. */
internal enum class MedsGlyphKind {
    CLOCK,
    MOON,
    BOX,
    CHEVRON_RIGHT,
    CLOSE,
    CHECK,
    CALENDAR_MINUS,
    LEAF,
    BELL,
    BELL_OFF,
    PILL,
    SUNRISE,
    SUN,
    SUNSET,
    VIAL,
}

/**
 * One drawn glyph at [size] in [tint].
 *
 * [tint] is a parameter, not a theme read, because a `@Composable` theme read
 * cannot happen inside `Canvas { }` — the caller hoists it.
 */
@Composable
internal fun MedsGlyph(
    kind: MedsGlyphKind,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
) {
    Canvas(modifier.size(size)) { drawGlyph(kind, tint) }
}

private fun DrawScope.drawGlyph(kind: MedsGlyphKind, tint: Color) {
    val w = size.width
    val h = size.height
    val stroke = 1.6f
    when (kind) {
        MedsGlyphKind.CLOCK -> {
            drawCircle(color = tint, radius = minOf(w, h) / 2 - stroke, style = Stroke(width = stroke))
            drawLine(tint, Offset(w / 2, h / 2), Offset(w / 2, h * 0.28f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(tint, Offset(w / 2, h / 2), Offset(w * 0.72f, h / 2), strokeWidth = stroke, cap = StrokeCap.Round)
        }

        MedsGlyphKind.MOON -> {
            // A crescent: a filled disc with a second disc punched out of it,
            // drawn as a stroked arc so the shape survives at 12 dp.
            val path = Path().apply {
                moveTo(w * 0.72f, h * 0.12f)
                cubicTo(w * 0.12f, h * 0.20f, w * 0.12f, h * 0.80f, w * 0.72f, h * 0.88f)
                cubicTo(w * 0.40f, h * 0.70f, w * 0.40f, h * 0.30f, w * 0.72f, h * 0.12f)
                close()
            }
            drawPath(path, tint)
        }

        MedsGlyphKind.BOX -> {
            val bodyTop = h * 0.34f
            drawRect(
                color = tint,
                topLeft = Offset(w * 0.10f, bodyTop),
                size = Size(w * 0.80f, h * 0.56f),
                style = Stroke(width = stroke),
            )
            // The lid, wider than the body, as a shipping box's flaps.
            drawRect(
                color = tint,
                topLeft = Offset(w * 0.02f, h * 0.14f),
                size = Size(w * 0.96f, h * 0.20f),
                style = Stroke(width = stroke),
            )
            drawLine(
                tint,
                Offset(w * 0.50f, h * 0.34f),
                Offset(w * 0.50f, h * 0.90f),
                strokeWidth = stroke * 0.7f,
            )
        }

        MedsGlyphKind.CHEVRON_RIGHT -> {
            drawLine(tint, Offset(w * 0.34f, h * 0.18f), Offset(w * 0.68f, h * 0.5f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(tint, Offset(w * 0.68f, h * 0.5f), Offset(w * 0.34f, h * 0.82f), strokeWidth = stroke, cap = StrokeCap.Round)
        }

        MedsGlyphKind.CLOSE -> {
            drawLine(tint, Offset(w * 0.22f, h * 0.22f), Offset(w * 0.78f, h * 0.78f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(tint, Offset(w * 0.78f, h * 0.22f), Offset(w * 0.22f, h * 0.78f), strokeWidth = stroke, cap = StrokeCap.Round)
        }

        MedsGlyphKind.CHECK -> {
            drawLine(tint, Offset(w * 0.16f, h * 0.54f), Offset(w * 0.40f, h * 0.78f), strokeWidth = stroke * 1.3f, cap = StrokeCap.Round)
            drawLine(tint, Offset(w * 0.40f, h * 0.78f), Offset(w * 0.86f, h * 0.22f), strokeWidth = stroke * 1.3f, cap = StrokeCap.Round)
        }

        MedsGlyphKind.CALENDAR_MINUS -> {
            // Upstream's `calendar.badge.minus`: a calendar card with two
            // binding ticks above it and a dash where a date would be.
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * 0.12f, h * 0.24f),
                size = Size(w * 0.76f, h * 0.66f),
                cornerRadius = CornerRadius(w * 0.10f),
                style = Stroke(width = stroke),
            )
            drawLine(tint, Offset(w * 0.12f, h * 0.42f), Offset(w * 0.88f, h * 0.42f), strokeWidth = stroke * 0.8f)
            drawLine(tint, Offset(w * 0.30f, h * 0.10f), Offset(w * 0.30f, h * 0.30f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(tint, Offset(w * 0.70f, h * 0.10f), Offset(w * 0.70f, h * 0.30f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(
                tint,
                Offset(w * 0.32f, h * 0.68f),
                Offset(w * 0.68f, h * 0.68f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }

        MedsGlyphKind.LEAF -> {
            val path = Path().apply {
                moveTo(w * 0.14f, h * 0.86f)
                cubicTo(w * 0.10f, h * 0.24f, w * 0.52f, h * 0.06f, w * 0.90f, h * 0.12f)
                cubicTo(w * 0.94f, h * 0.56f, w * 0.66f, h * 0.90f, w * 0.14f, h * 0.86f)
                close()
            }
            drawPath(path, tint, style = Stroke(width = stroke))
            drawLine(
                tint,
                Offset(w * 0.20f, h * 0.80f),
                Offset(w * 0.74f, h * 0.26f),
                strokeWidth = stroke * 0.7f,
                cap = StrokeCap.Round,
            )
        }

        MedsGlyphKind.BELL, MedsGlyphKind.BELL_OFF -> {
            val path = Path().apply {
                moveTo(w * 0.22f, h * 0.74f)
                lineTo(w * 0.30f, h * 0.58f)
                lineTo(w * 0.30f, h * 0.42f)
                cubicTo(w * 0.30f, h * 0.16f, w * 0.70f, h * 0.16f, w * 0.70f, h * 0.42f)
                lineTo(w * 0.70f, h * 0.58f)
                lineTo(w * 0.78f, h * 0.74f)
                close()
            }
            drawPath(path, tint)
            drawCircle(tint, radius = w * 0.07f, center = Offset(w * 0.50f, h * 0.82f))
            if (kind == MedsGlyphKind.BELL_OFF) {
                // The slash is what distinguishes "off" from "on" at a glance;
                // upstream gets it from `bell.slash`.
                drawLine(
                    Color.White,
                    Offset(w * 0.10f, h * 0.90f),
                    Offset(w * 0.90f, h * 0.10f),
                    strokeWidth = stroke * 1.4f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    tint,
                    Offset(w * 0.10f, h * 0.90f),
                    Offset(w * 0.90f, h * 0.10f),
                    strokeWidth = stroke * 0.7f,
                    cap = StrokeCap.Round,
                )
            }
        }

        MedsGlyphKind.PILL, MedsGlyphKind.VIAL -> {
            // A capsule on the diagonal — the avatar and the hub's row mark.
            val left = w * 0.18f
            val top = h * 0.34f
            drawRoundRect(
                color = tint,
                topLeft = Offset(left, top),
                size = Size(w * 0.64f, h * 0.32f),
                cornerRadius = CornerRadius(h * 0.16f),
                style = Stroke(width = stroke),
            )
            drawLine(
                tint,
                Offset(w * 0.50f, top),
                Offset(w * 0.50f, top + h * 0.32f),
                strokeWidth = stroke * 0.8f,
            )
            if (kind == MedsGlyphKind.VIAL) {
                // The PRN mark: a small cross, upstream's `cross.vial`.
                drawLine(tint, Offset(w * 0.50f, h * 0.06f), Offset(w * 0.50f, h * 0.24f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.40f, h * 0.15f), Offset(w * 0.60f, h * 0.15f), strokeWidth = stroke, cap = StrokeCap.Round)
            }
        }

        MedsGlyphKind.SUNRISE, MedsGlyphKind.SUN, MedsGlyphKind.SUNSET -> {
            val centreX = w * 0.5f
            val horizon = when (kind) {
                MedsGlyphKind.SUNRISE -> h * 0.72f
                MedsGlyphKind.SUNSET -> h * 0.42f
                else -> h * 0.62f
            }
            val radius = w * 0.24f
            val centreY = when (kind) {
                MedsGlyphKind.SUNRISE -> horizon
                MedsGlyphKind.SUNSET -> horizon
                else -> h * 0.42f
            }
            drawCircle(tint, radius = radius, center = Offset(centreX, centreY), style = Stroke(width = stroke))
            // Rays. A rising or setting sun only shows the ones above the
            // horizon, which is what tells the two apart from a plain sun —
            // Compose's y axis grows downward, so 270 degrees is "up".
            val rayLength = w * 0.14f
            val rayAngles = when (kind) {
                MedsGlyphKind.SUNRISE -> listOf(270f, 215f, 325f)
                MedsGlyphKind.SUNSET -> listOf(270f, 215f, 325f)
                else -> listOf(270f, 90f, 180f, 0f)
            }
            for (angle in rayAngles) {
                val radians = Math.toRadians(angle.toDouble())
                val cos = kotlin.math.cos(radians).toFloat()
                val sin = kotlin.math.sin(radians).toFloat()
                drawLine(
                    tint,
                    Offset(centreX + cos * (radius + 1f), centreY + sin * (radius + 1f)),
                    Offset(centreX + cos * (radius + rayLength), centreY + sin * (radius + rayLength)),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
            // The horizon line itself, which is what makes the glyph read as a
            // sunrise rather than a small sun.
            drawLine(
                tint,
                Offset(w * 0.06f, horizon),
                Offset(w * 0.94f, horizon),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

// MARK: - Med avatar

/**
 * The round accent mark beside a med's name in the hub and the detail header.
 *
 * Upstream is `Image(systemName: "pill")` in an accent circle; the glyph here
 * is white on the accent fill so it reads the same at 30 and 44 dp.
 */
@Composable
internal fun MedAvatar(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(PiruTheme.colors.accent),
        contentAlignment = Alignment.Center,
    ) {
        MedsGlyph(kind = MedsGlyphKind.PILL, tint = Color.White, size = size * 0.55f)
    }
}

// MARK: - Row scaffolding

/**
 * The uniform hit target the port applies to a control that must stay tappable
 * regardless of how small its glyph is — upstream's `minimumHitTarget()`.
 */
internal fun Modifier.medsHitTarget(): Modifier = this.padding(6.dp)
