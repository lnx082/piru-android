package glass.kagerou.piru.ui.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.engine.ActiveSubstance
import glass.kagerou.piru.engine.ActiveSubstanceCalculator
import glass.kagerou.piru.engine.PKModel
import glass.kagerou.piru.engine.PKResolver
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Instant

/**
 * What is still in the body.
 *
 * Ported from `Views/Insights/InYourBodyView.swift` and `BodyLoadChart.swift`.
 *
 * The readout answers "how much is left", which is a different question from the
 * one the timeline answers ("what does the effect curve look like") and the two
 * diverge hardest exactly where a guess would do the most damage — amphetamine's
 * ten-hour half-life far outlasts its subjective effects, and fluoxetine's
 * sixteen-day one outlasts any graph. That is why this screen can show a substance
 * the timeline draws no curve for at all.
 *
 * Supplements are absent by the engine's own decision, not by an oversight here:
 * they clear over days to weeks, so "0 % eliminated, clears in five months" is
 * noise rather than a session insight.
 */
@Composable
fun BodyLoadScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    var active by remember { mutableStateOf<List<ActiveSubstance>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val catalog = app.catalog()
        val entries = app.database.doseEntryDao().all().mapNotNull { it.toDoseRecordIfReplayable() }
        val tints = app.palette().tintsFor(entries.map { it.substance }.toSet())
        active = ActiveSubstanceCalculator.compute(
            entries = entries,
            colorMap = tints,
            catalog = catalog,
            // A substance with no colour of its own and none stored: the neutral
            // stand-in rather than the accent, so an uncoloured row does not read as
            // deliberately branded.
            fallbackTint = glass.kagerou.piru.model.P3Color.NEUTRAL,
            now = Instant.now(),
        )
        loaded = true
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("In your body", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "How much of each dose is still on board. This is elimination, not " +
                        "effect — a substance can be gone from the curve and still in you, " +
                        "and the other way round.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        if (loaded && active.isEmpty()) {
            item {
                Text(
                    "Nothing with a known half-life is still on board.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        items(active, key = { it.id }) { substance ->
            // Hoisted: a `@Composable` theme read cannot happen inside `Canvas { … }`.
            val track = PiruTheme.colors.secondaryLabel.copy(alpha = 0.18f)
            val fill = PiruTheme.colors.accent
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(substance.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${trim(substance.totalRemaining)} ${substance.unit} left",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    // The bar is the fraction *eliminated*, so a full bar means
                    // cleared — the same direction as the tolerance gauge, and the
                    // opposite of "how much is left", which is why both numbers are
                    // written out beside it.
                    Box(modifier = Modifier.fillMaxWidth().height(10.dp)) {
                        Canvas(Modifier.fillMaxSize()) {
                            val fraction = substance.eliminatedFraction.coerceIn(0.0, 1.0).toFloat()
                            drawRoundRect(
                                color = track,
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2),
                            )
                            drawRoundRect(
                                color = fill,
                                size = androidx.compose.ui.geometry.Size(size.width * fraction, size.height),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2),
                            )
                        }
                    }
                    Text(
                        "${(substance.eliminatedFraction * 100).toInt()}% eliminated · " +
                            "${substance.doses.size} dose${if (substance.doses.size == 1) "" else "s"} · " +
                            "half-life ${hours(substance.halfLifeMinutes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }

        item {
            Text(
                "Predicted from a model, not measured. Not medical advice.",
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

/**
 * How one dose decays.
 *
 * Ported from `Views/Insights/HalfLifeCalculatorView.swift`.
 *
 * The curve is `PKModel.fractionRemainingInBody`, which is the same function the
 * body-load readout integrates — so the shape here is the shape behind every
 * number on that screen rather than a second, illustrative one.
 *
 * ## A substance with no half-life says so
 * The catalog carries none for a great many compounds, and the honest answer is
 * that the app cannot say — not a curve drawn from a plausible default. The
 * screen names the substances it *can* do this for, so an empty list is
 * information rather than a dead end.
 */
@Composable
fun HalfLifeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    var choices by remember { mutableStateOf<List<Pair<String, PKResolver.Params>>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val catalog = app.catalog()
        val names = app.database.doseEntryDao().all().map { it.substance }.distinct()
        // Only the substances a half-life can be resolved for. A row that resolves
        // to nothing is left out rather than shown with an empty chart.
        choices = names.mapNotNull { name ->
            val substance = catalog.lookup(name) ?: return@mapNotNull null
            val params = PKResolver.params(substance, route = substance.defaultRoute) ?: return@mapNotNull null
            name to params
        }.sortedBy { it.first.lowercase() }
        selected = choices.firstOrNull()?.first
        loaded = true
    }

    val current = choices.firstOrNull { it.first == selected }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Half-life", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "How long a dose takes to fall by half, and the decay from there. " +
                        "Elimination, not the felt effect.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        if (loaded && choices.isEmpty()) {
            item {
                Text(
                    "Nothing in your log has a half-life in the catalog, so there is " +
                        "nothing to plot.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        if (choices.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (batch in choices.chunked(3)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for ((name, _) in batch) {
                                FilterChip(
                                    selected = name == selected,
                                    onClick = { selected = name },
                                    label = { Text(name) },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (current != null) {
            item {
                val (name, params) = current
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Half-life ${hours(params.halfLifeMinutes)} · " +
                                "elimination rate ${"%.5f".format(params.ke)} /min",
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                        DecayCurve(params)
                        // The four-and-a-bit half-lives figure, because that is the one
                        // people mean by "how long until it is out of me" — and saying
                        // "five half-lives ≈ 97 %" is more honest than a hard zero.
                        Text(
                            "About ${hours(params.halfLifeMinutes * 5)} to 97 % eliminated, " +
                                "and ${hours(params.halfLifeMinutes * 7)} to 99 %.",
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                }
            }
        }

        item {
            Text(
                "Predicted from a model, not measured. Not medical advice.",
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

/** The elimination curve over seven half-lives, drawn from the same function the body-load readout uses. */
@Composable
private fun DecayCurve(params: PKResolver.Params) {
    val accent = PiruTheme.colors.accent
    val mark = PiruTheme.colors.secondaryLabel.copy(alpha = 0.3f)
    Box(modifier = Modifier.fillMaxWidth().height(120.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val spanMinutes = params.halfLifeMinutes * 7
            val steps = 120
            var previous = Offset.Zero
            for (index in 0..steps) {
                val minutes = spanMinutes * index / steps
                val fraction = PKModel.fractionRemainingInBody(minutes, params.ke, params.ka)
                val point = Offset(size.width * index / steps, size.height * (1 - fraction.toFloat()))
                if (index > 0) {
                    drawLine(color = accent, start = previous, end = point, strokeWidth = 2.5f)
                }
                previous = point
            }
            // The half-life marks, so the shape is readable without a scale.
            var half = 0
            while (half < 3) {
                val minutes = params.halfLifeMinutes * (half + 1)
                val x = size.width * (minutes / spanMinutes).toFloat()
                drawLine(
                    color = mark,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f,
                )
                half++
            }
        }
    }
}

private fun hours(minutes: Double): String = when {
    minutes < 90 -> "${minutes.toInt()} min"
    minutes < 60 * 48 -> "%.1f h".format(minutes / 60)
    else -> "%.1f d".format(minutes / 1_440)
}

private fun trim(value: Double): String =
    if (value >= 100) value.toInt().toString()
    else "%.2f".format(value).trimEnd('0').trimEnd('.')

private fun glass.kagerou.piru.data.entity.DoseEntryEntity.toDoseRecordIfReplayable() =
    if (isUnknownDose) {
        null
    } else {
        glass.kagerou.piru.engine.DoseRecord(
            substance = substance,
            amount = amount,
            unit = unit,
            route = route,
            timestamp = timestamp.toInstant(),
            isUnknownDose = false,
            releaseForm = releaseForm,
            productName = productName,
            saltForm = saltForm,
            isomer = isomer,
            substanceUID = substanceUID,
        )
    }
