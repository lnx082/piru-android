package glass.kagerou.piru.ui.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme
import androidx.compose.foundation.layout.padding

/**
 * The GABA card's two decisions, as functions.
 *
 * Separate from the composable so they can be called: whether a curve is worth drawing at all, and whether the
 * caption has to mention alcohol. The second is the one that matters — the caption is where the card says
 * alcohol loads GABA-A at a **different site**, and a reader who does not see that sentence could take the
 * summed curve for a benzodiazepine-equivalent dose.
 */
internal object GabaLoad {

    /**
     * Whether a trail is worth a card.
     *
     * Below the floor there is no reading, only a line at the bottom of the axis — and a card that always draws
     * teaches the reader to scroll past it, which is the wrong lesson for the one card that answers "is this
     * receptor loaded".
     *
     * An **empty** trail is also not worth a card, and for a different reason: it means the class is not driven
     * by anything in the window, which `LoadTrail` documents as distinct from a load of zero.
     */
    fun worthShowing(trail: List<LoadPoint>): Boolean =
        trail.isNotEmpty() && (trail.maxOfOrNull { it.load } ?: 0.0) >= GABA_VISIBILITY_FLOOR

    /**
     * Whether the log names alcohol or ethanol.
     *
     * Case-insensitive, because the catalogue's substance names are not all one casing, and a substring test
     * rather than equality, because the log holds whatever the user or an import wrote — "Alcohol (ethanol)",
     * "Ethanol", "alcohol".
     */
    fun includesAlcohol(substanceNames: List<String>): Boolean = substanceNames.any { name ->
        name.contains("alcohol", ignoreCase = true) || name.contains("ethanol", ignoreCase = true)
    }
}

/**
 * The combined GABA-A load, relative to the user's own recent peak.
 *
 * Ported from `GABALoadingCard`. It shows the thing the per-session and per-substance views cannot: that several
 * benzodiazepines — plus alcohol, plus a lingering metabolite — load **one** receptor, and how loaded it is
 * right now.
 *
 * ## Why the curve is relative and not occupancy
 * Upstream's own note, and the reason the y axis is a percentage of the user's peak rather than an occupancy:
 * benzodiazepine occupancy **saturates**, so an absolute curve would be a flat plateau near 100% for most of the
 * window. Normalising against the user's own recent peak makes the curve clear as the drug leaves, which is
 * what makes it readable as "how much is still loading this".
 *
 * ## Why it hides itself
 * Below a five-percent peak there is nothing to read — the curve is a line at the floor — and a card that always
 * draws teaches the reader to scroll past it, which is the wrong lesson for this one.
 *
 * ## What the caption has to say
 * Alcohol loads GABA-A **at a different site**. Summing it into one curve is still the right reading of total
 * load, but a reader who does not know that could conclude the curve is a benzodiazepine-equivalent dose. So the
 * caption says which case it is, from the contributors actually present in the log.
 */
@Composable
fun GabaLoadingCard(entries: List<DoseEntryEntity>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication

    var trail by remember { mutableStateOf<List<LoadPoint>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    // Keyed on the log itself rather than on its size: editing an existing dose leaves the count unchanged and
    // must still re-resolve, which is upstream's own note about its revision counter.
    LaunchedEffect(entries) {
        trail = runCatching { buildGabaLoadTrail(app, entries) }.getOrDefault(emptyList())
        loaded = true
    }

    if (loaded && !GabaLoad.worthShowing(trail)) return

    val includesAlcohol = GabaLoad.includesAlcohol(entries.map { it.substance })

    val accent = PiruTheme.colors.accent

    PiruCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.tolerance_gaba_load_title),
                style = MaterialTheme.typography.titleSmall,
            )
            if (trail.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(72.dp)) {
                    Canvas(Modifier.fillMaxSize()) {
                        // A fixed 0-100% axis, not autoscaled to the curve's own maximum: the normalisation is
                        // against the user's peak, so a curve scaled to itself would always look full and would
                        // hide exactly the comparison the card exists to make.
                        val points = trail.mapIndexed { index, point ->
                            Offset(
                                x = size.width * index / (trail.size - 1).coerceAtLeast(1),
                                y = size.height * (1f - (point.load).toFloat().coerceIn(0f, 1f)),
                            )
                        }
                        if (points.size >= 2) {
                            // The area under the line, so the shape reads as a load rather than a trend.
                            val area = Path().apply {
                                moveTo(points.first().x, size.height)
                                for (point in points) lineTo(point.x, point.y)
                                lineTo(points.last().x, size.height)
                                close()
                            }
                            drawPath(area, color = accent.copy(alpha = 0.18f), style = Fill)
                            val line = Path().apply {
                                moveTo(points.first().x, points.first().y)
                                for (point in points.drop(1)) lineTo(point.x, point.y)
                            }
                            drawPath(line, color = accent, style = Stroke(width = 2.5f))
                        }
                    }
                }
                Text(
                    stringResource(
                        R.string.tolerance_gaba_load_now,
                        (trail.minByOrNull { kotlin.math.abs(it.date.toEpochMilli() - System.currentTimeMillis()) }
                            ?.load ?: 0.0) * 100,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                if (includesAlcohol) {
                    stringResource(R.string.tolerance_gaba_load_caption_alcohol)
                } else {
                    stringResource(R.string.tolerance_gaba_load_caption)
                },
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
    }
}
