package glass.kagerou.piru.ui.meds

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.R
import glass.kagerou.piru.ui.theme.PiruTheme

// The My Meds card's trailing info lines, one composable per fact so each
// re-evaluates on its own inputs. All three share [MedsInfoLineLayout] —
// a glyph, up to two caption lines, and a trailing control.
//
// Ported from `Piru/Views/Journal/DailyDose/MyMedsInfoLines.swift` (136 lines).

/**
 * "Memantine · 6 days left" — tap opens the restock form.
 *
 * The trailing chevron is drawn rather than iconed, like every other glyph on
 * this card; the tint is [PiruTheme.colors.caution], which is where upstream
 * puts `.orange`.
 */
@Composable
fun RestockInfoLine(
    name: String,
    daysLeft: Int,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MedsInfoLineLayout(
        glyph = MedsGlyphKind.BOX,
        tint = PiruTheme.colors.caution,
        onTap = onTap,
        modifier = modifier,
        trailing = {
            MedsGlyph(
                kind = MedsGlyphKind.CHEVRON_RIGHT,
                tint = PiruTheme.colors.tertiaryLabel,
                size = 14.dp,
            )
        },
    ) {
        Text(
            stringResource(R.string.meds_restock_line, name, daysLeft),
            style = captionSecondaryStyle,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "Next: Memantine at 9:00 PM" — tap opens My Meds. */
@Composable
fun NextDueInfoLine(
    name: String,
    timeText: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MedsInfoLineLayout(
        glyph = MedsGlyphKind.CLOCK,
        tint = PiruTheme.colors.secondaryLabel,
        onTap = onTap,
        modifier = modifier,
        trailing = {
            MedsGlyph(
                kind = MedsGlyphKind.CHEVRON_RIGHT,
                tint = PiruTheme.colors.tertiaryLabel,
                size = 14.dp,
            )
        },
    ) {
        Text(
            stringResource(R.string.meds_next_due_line, name, timeText),
            style = captionSecondaryStyle,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * "Yesterday's evening dose of Memantine wasn't logged" — states the gap,
 * nothing more. Tap opens My Meds; the ✕ hides the notice for that day.
 */
@Composable
fun MissedYesterdayInfoLine(
    notice: MissedYesterdayNotice,
    onTap: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MedsInfoLineLayout(
        glyph = MedsGlyphKind.CALENDAR_MINUS,
        tint = PiruTheme.colors.secondaryLabel,
        modifier = modifier,
        trailing = {
            Row(
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MedsGlyph(
                    kind = MedsGlyphKind.CLOSE,
                    tint = PiruTheme.colors.tertiaryLabel,
                    size = 12.dp,
                )
            }
        },
        onTap = onTap,
    ) {
        Text(missedText(notice), style = captionSecondaryStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The sentence a missed notice states.
 *
 * Three shapes, in upstream's order: several names collapse to "weren't
 * logged" with no hour, a single notice with no slot time names the med alone,
 * and a single timed notice names the end of the day it went missing from —
 * which is the difference between "you missed a dose" and "the evening one".
 *
 * `@Composable` because the names have to be joined with the language's own
 * list conjunction, and the slot's name may be a med the user typed.
 */
@Composable
private fun missedText(notice: MissedYesterdayNotice): String {
    if (notice.count > 1) {
        return stringResource(R.string.meds_missed_multi, andList(notice.names))
    }
    val minutes = notice.slotMinutes
        ?: return stringResource(R.string.meds_missed_single, notice.name)
    return when (MedTimeGroup.groupForMinutes(minutes)) {
        MedTimeGroup.MORNING -> stringResource(R.string.meds_missed_morning, notice.name)
        MedTimeGroup.AFTERNOON -> stringResource(R.string.meds_missed_afternoon, notice.name)
        MedTimeGroup.EVENING -> stringResource(R.string.meds_missed_evening, notice.name)
        else -> stringResource(R.string.meds_missed_night, notice.name)
    }
}

/**
 * Glyph · caption text · trailing control, at the slot rows' leading inset so
 * the lines read as part of the checklist rather than a footer.
 *
 * ## One divergence from the Swift source
 * Upstream aligns the glyph to the text's first baseline
 * (`HStack(alignment: .firstTextBaseline)`). Compose cannot: `alignByBaseline`
 * needs the child to expose a baseline, and a `Canvas` — which is how every
 * glyph in this build is drawn — exposes none. The row centres instead, which
 * lands the glyph in the same place for the one- and two-line cases these lines
 * actually take.
 */
@Composable
internal fun MedsInfoLineLayout(
    glyph: MedsGlyphKind,
    tint: Color,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MedsGlyph(kind = glyph, tint = tint, size = 14.dp)
        Box(modifier = Modifier.weight(1f)) { content() }
        trailing()
    }
}
