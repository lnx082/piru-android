package glass.kagerou.piru.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.R
import glass.kagerou.piru.engine.SpectrumLevel
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput

/**
 * The strength ladder: what each rung of the dose scale actually feels like.
 *
 * Ported from the strength-dial section upstream draws over `spectrum_levels`, which covers 162 substances with
 * **exactly six bands each** — the same six rungs in the same order for every substance. That uniformity is what
 * makes a dial the right shape: the reader already knows the rungs from every other substance, so the section
 * answers "what is *this* one like at Strong" rather than teaching a new scale each time.
 *
 * ## The two things that make it more than a list
 * - **The effects are ranked.** `top_effects_json` is `{name, freq}` pairs and the counts run from 1 to 45, so the
 *   band's effects are ordered by how often they were reported rather than listed as a bag of words.
 * - **The upper two rungs are marked.** Heavy and Overdose are the ones a reader needs told apart from the rest,
 *   and the warnings column is non-empty for most of them (346 of 972 rows).
 *
 * ## Why a dial rather than six cards
 * Six cards per substance would make this the tallest section on the screen, and the reader is comparing rungs
 * rather than reading each one. The dial shows all six at once and expands the one that is tapped.
 */
@Composable
fun StrengthDialCard(levels: List<SpectrumLevel>) {
    if (levels.isEmpty()) return
    // Ordered by the band index, because that is what the ladder means — a source's row order is not the ladder.
    val ordered = remember(levels) { levels.sortedBy { it.bandIndex } }
    // The rung the reader is reading. Null before any tap, which shows the dial alone; the ladder is legible
    // without a selection, and defaulting to a middle rung would put words in the reader's mouth.
    var selected by remember(ordered) { mutableStateOf<SpectrumLevel?>(null) }

    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.shell_section_strength), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.shell_strength_blurb),
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
            )

            Dial(ordered = ordered, selected = selected, onSelect = { selected = it })

            // The rung labels under the dial, so it reads without a legend.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    ordered.first().bandName,
                    style = MaterialTheme.typography.labelSmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
                Text(
                    ordered.last().bandName,
                    style = MaterialTheme.typography.labelSmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }

            val shown = selected
            if (shown != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(shown.bandName, style = MaterialTheme.typography.titleSmall)
                    Text(shown.description, style = MaterialTheme.typography.bodyMedium)
                    if (shown.topEffects.isNotEmpty()) {
                        Text(
                            stringResource(R.string.shell_strength_effects),
                            style = MaterialTheme.typography.labelSmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                        Text(
                            // By frequency, which is the ranking: the most-reported effect first.
                            shown.topEffects.joinToString(", ") { it.name },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    // The warnings, which is the reason the upper rungs are marked at all.
                    for (warning in shown.warnings) {
                        Text(
                            warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.accent,
                        )
                    }
                }
            } else {
                Text(
                    stringResource(R.string.shell_strength_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }
    }
}

/**
 * Six rungs as ascending bars, the upper two in the accent colour.
 *
 * Hand-drawn: the app has no charting dependency, and this is six rectangles whose heights are their own index.
 * The bars ascend because the ladder does — a flat row of equal chips would lose the only thing the picture adds.
 */
@Composable
private fun Dial(
    ordered: List<SpectrumLevel>,
    selected: SpectrumLevel?,
    onSelect: (SpectrumLevel) -> Unit,
) {
    val accent = PiruTheme.colors.accent
    val quiet = PiruTheme.colors.secondaryLabel.copy(alpha = 0.35f)
    val count = ordered.size

    Box(modifier = Modifier.fillMaxWidth().height(64.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .strengthTapTarget(count, onSelect = { index -> onSelect(ordered[index]) }),
        ) {
            val slot = size.width / count
            val gap = (slot * 0.28f).coerceAtLeast(2f)
            for ((index, level) in ordered.withIndex()) {
                // Height from the rung's own position in the ladder, with a floor so the threshold rung is still
                // a bar rather than nothing.
                val fraction = (index + 1).toFloat() / count
                val height = size.height * fraction
                // The upper rungs carry the accent; the lower ones stay quiet, because the dial's message is
                // "these two are different" and colouring all six would say nothing.
                val isSelected = selected?.bandIndex == level.bandIndex
                val colour = when {
                    isSelected -> accent
                    level.isUpperRung -> accent.copy(alpha = 0.55f)
                    else -> quiet
                }
                drawRect(
                    color = colour,
                    topLeft = Offset(index * slot + gap / 2f, size.height - height),
                    size = Size(slot - gap, height),
                )
            }
        }
    }
}

/**
 * A tap target that maps an x position to a rung.
 *
 * The same shape the port's other hand-drawn charts use for their scrub gestures. Without it the dial would be a
 * picture with a selected state and no way to select — which is a control that looks interactive and is not.
 */
private fun Modifier.strengthTapTarget(
    count: Int,
    onSelect: (Int) -> Unit,
): Modifier = this.then(
    pointerInput(count) {
        detectTapGestures { offset ->
            if (size.width <= 0) return@detectTapGestures
            val index = ((offset.x / size.width) * count).toInt().coerceIn(0, count - 1)
            onSelect(index)
        }
    },
)
