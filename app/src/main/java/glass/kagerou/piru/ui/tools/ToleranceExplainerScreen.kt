package glass.kagerou.piru.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.R
import glass.kagerou.piru.engine.ReceptorClasses
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme

/**
 * What the tolerance screen's numbers mean.
 *
 * Ported from `ToleranceExplainerView`. The tolerance tool shows a bar, a ladder and a recovery curve, and
 * every one of those is a reading of a model — a reader who does not know what the model is can only take the
 * numbers on faith or ignore them. This is the page that makes them legible.
 *
 * ## The frame is the important part
 * Upstream's copy is deliberately mechanism-first and rejects the "receptors used up" picture in favour of the
 * allostatic one: the brain adapts to a repeated input and pushes back. That is not a stylistic choice — it is
 * the picture the engine implements, and a reader holding the wrong one will misread a descending curve as a
 * refilling tank.
 *
 * ## The boundary section is why the page is honest
 * The model is **pharmacodynamic**: it knows the doses and the clock. A large part of real tolerance is
 * associative and context-specific, and the app records neither where the user was nor what they were doing.
 * Saying so is the difference between an estimate and a claim.
 */
@Composable
fun ToleranceExplainerScreen(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(
                modifier = Modifier.padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.tolerance_explainer_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(R.string.tolerance_explainer_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        for (section in ToleranceExplainer.sections) {
            item {
                Text(
                    stringResource(section.titleRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = PiruTheme.colors.secondaryLabel,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            for (concept in section.concepts) {
                item {
                    PiruCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                stringResource(concept.titleRes),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                stringResource(concept.bodyRes),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }

        item {
            Text(
                stringResource(R.string.tolerance_explainer_mechanisms),
                style = MaterialTheme.typography.labelLarge,
                color = PiruTheme.colors.secondaryLabel,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        for (receptorClass in ToleranceExplainer.orderedClasses) {
            item {
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            receptorClass.displayName,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        // The recovery timescale, from the engine's own parameters — so the number here and
                        // the curve on the card come from one source.
                        Text(
                            recoveryDescriptor(receptorClass),
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                        Text(
                            stringResource(ToleranceExplainer.meaningRes(receptorClass)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        item {
            Text(
                stringResource(R.string.tolerance_explainer_sources),
                style = MaterialTheme.typography.labelLarge,
                color = PiruTheme.colors.secondaryLabel,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        for (source in ToleranceExplainer.sources) {
            item {
                Text(
                    source,
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }
    }
}

/**
 * A class's recovery timescale in words, from the engine's own tau.
 *
 * The **adaptive** layer's tau, because that is the days-to-weeks shift the cards draw and the one a reader
 * means by "how long until it comes back". The acute layer refills overnight and the deep layer is months, so
 * naming which one this is matters — and it is named rather than left implicit.
 */
fun recoveryDescriptor(receptorClass: ReceptorClasses.ReceptorClass): String {
    val days = ReceptorClasses.parametersFor(receptorClass).tauAdaptiveMinutes / 1_440.0
    return when {
        days < 1 -> "Adaptive shift recovers in under a day"
        days < 2 -> "Adaptive shift recovers in about a day"
        days < 10 -> "Adaptive shift recovers in about ${days.toInt()} days"
        days < 60 -> "Adaptive shift recovers in about ${(days / 7).toInt()} weeks"
        else -> "Adaptive shift recovers over months"
    }
}
