package glass.kagerou.piru.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.R
import glass.kagerou.piru.engine.SaturationKinetics
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme
import java.util.Locale

/**
 * The saturation-kinetics tool: how much of a capacity a concentration occupies.
 *
 * ## What it answers
 * Two questions, and the arithmetic is the same for both:
 *
 * 1. **"Why did doubling the dose not double the effect?"** — because the process was already near saturation. The
 *    fraction of Vmax is that answer, and the `foldIncrease` line says how much more it would take to move.
 * 2. **"Is this dose in the linear range?"** — the regime names it, and the linear range is the only one where dose and
 *    effect scale together predictably.
 *
 * ## Where the arithmetic is, and why not here
 * In `SaturationKinetics`, in `:core:engine`, with nine tests. Its failure mode is a number that looks plausible and is
 * wrong — a zero Km would report "fully saturated at every dose" — and that is not a thing a screen can assert about
 * itself.
 *
 * ## All numbers are formatted with `Locale.ROOT`
 * The same rule the solution calculator follows: these are figures a reader copies into a note or another app, and a
 * decimal comma on a German or Chinese phone is a **thousands separator** to whatever receives it.
 *
 * ## What it does not model
 * No two-substrate case, no inhibition, no cooperativity. Each is a different equation with constants the catalogue
 * does not carry, and offering a control for one while calling it "saturation kinetics" would be the over-claiming this
 * project keeps finding.
 */
@Composable
fun SaturationScreen(modifier: Modifier = Modifier) {
    var concentration by remember { mutableStateOf("") }
    var km by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }

    val concentrationValue = concentration.toDoubleOrNull()
    val kmValue = km.toDoubleOrNull()
    val targetValue = target.toDoubleOrNull()
    // Solved on every recomposition rather than on a button: the figuies are cheap, and a reader adjusting a digit wants
    // to see the fraction move rather than press "calculate" again.
    val solved = remember(concentrationValue, kmValue, targetValue) {
        SaturationKinetics.solve(concentration = concentrationValue, km = kmValue, targetFraction = targetValue)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Text(
                stringResource(R.string.saturation_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        item {
            Text(
                stringResource(R.string.saturation_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = PiruTheme.colors.secondaryLabel,
            )
        }

        item {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField(
                        value = concentration,
                        onValueChange = { concentration = it },
                        label = stringResource(R.string.saturation_concentration),
                    )
                    NumberField(
                        value = km,
                        onValueChange = { km = it },
                        label = stringResource(R.string.saturation_km),
                    )
                }
            }
        }

        item {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val result = solved.getOrNull()
                    if (result == null) {
                        // One message for every refusal. The rule refuses four different inputs, but a reader who has
                        // typed two numbers wants to know what to type, not which branch declined.
                        Text(
                            stringResource(R.string.saturation_missing),
                            style = MaterialTheme.typography.bodyMedium,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    } else {
                        Labelled(
                            label = stringResource(R.string.saturation_fraction),
                            value = percent(result.fractionOfVmax),
                        )
                        Labelled(
                            label = stringResource(R.string.saturation_regime),
                            value = stringResource(
                                when (result.regime) {
                                    SaturationKinetics.Regime.LINEAR -> R.string.saturation_regime_linear
                                    SaturationKinetics.Regime.TRANSITIONAL ->
                                        R.string.saturation_regime_transitional
                                    SaturationKinetics.Regime.SATURATED -> R.string.saturation_regime_saturated
                                },
                            ),
                        )
                    }
                }
            }
        }

        item {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField(
                        value = target,
                        onValueChange = { target = it },
                        label = stringResource(R.string.saturation_target),
                    )
                    val targetConcentration = solved.getOrNull()?.concentrationForTarget
                    if (targetValue == null || targetValue <= 0.0 || targetValue >= 1.0) {
                        // Named rather than blank: full saturation is approached asymptotically and **never reached**,
                        // so there is no figure to show — and showing a huge number instead would be one a reader could
                        // act on.
                        Text(
                            stringResource(R.string.saturation_target_unreachable),
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    } else if (targetConcentration != null) {
                        Text(
                            stringResource(R.string.saturation_target_result, number(targetConcentration)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

/** A labelled figure, both left-aligned, so a long regime sentence wraps rather than squeezing the label. */
@Composable
private fun Labelled(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = PiruTheme.colors.secondaryLabel)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A numeric field whose text is held as typed, so a half-typed number is never parsed into a figure. */
@Composable
private fun NumberField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A percentage with one decimal, in `Locale.ROOT` so the separator is a dot wherever the phone is. */
private fun percent(fraction: Double): String = String.format(Locale.ROOT, "%.1f%%", fraction * 100.0)

/** A plain figure with three decimals, in `Locale.ROOT`, for the same reason as [percent]. */
private fun number(value: Double): String = String.format(Locale.ROOT, "%.3f", value)
