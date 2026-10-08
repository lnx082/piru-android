package glass.kagerou.piru.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.R
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme

/**
 * Work out a solution's concentration, or the solvent a target concentration needs.
 *
 * Ported from `SolutionMathView`. Two modes over one form, because a person with a powder and a vial has
 * one of two questions and which one they have decides which field they are trying to fill in.
 *
 * ## Why the copy button exists
 * The number is going to end up written on a label, and a number that has to be retyped from a screen is a
 * number that gets typed wrong. Upstream has the same button for the same reason.
 *
 * ## Why the safety card is not optional
 * Everything this screen computes is arithmetic the user then acts on with a real substance. The four
 * points are upstream's, and the second one — "check the arithmetic independently" — is the honest one: an
 * app that offers a volumetric calculator is not thereby a witness to the result.
 */
@Composable
fun SolutionMathScreen(modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current

    var mode by remember { mutableStateOf(SolutionMath.Mode.SOLVENT_NEEDED) }
    // All three fields are held as text in both modes, because a half-typed number is a draft rather than
    // a parse failure, and switching modes should not clear what the user already typed.
    var amountText by remember { mutableStateOf("") }
    var volumeText by remember { mutableStateOf("") }
    var concentrationText by remember { mutableStateOf("") }

    val amount = amountText.trim().toDoubleOrNull()
    val volume = volumeText.trim().toDoubleOrNull()
    val concentration = concentrationText.trim().toDoubleOrNull()

    val result = when (mode) {
        SolutionMath.Mode.SOLVENT_NEEDED ->
            SolutionMath.solventNeededMl(amount, concentration)
        SolutionMath.Mode.CONCENTRATION ->
            SolutionMath.concentrationMgPerMl(amount, volume)
    }
    val resultUnit = when (mode) {
        SolutionMath.Mode.SOLVENT_NEEDED -> stringResource(R.string.tools_solution_unit_ml)
        SolutionMath.Mode.CONCENTRATION -> stringResource(R.string.tools_solution_unit_mg_per_ml)
    }

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
                    stringResource(R.string.tools_solution_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(R.string.tools_solution_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == SolutionMath.Mode.SOLVENT_NEEDED,
                    onClick = { mode = SolutionMath.Mode.SOLVENT_NEEDED },
                    label = { Text(stringResource(R.string.tools_solution_mode_solvent)) },
                )
                FilterChip(
                    selected = mode == SolutionMath.Mode.CONCENTRATION,
                    onClick = { mode = SolutionMath.Mode.CONCENTRATION },
                    label = { Text(stringResource(R.string.tools_solution_mode_concentration)) },
                )
            }
        }

        item {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when (mode) {
                        SolutionMath.Mode.SOLVENT_NEEDED -> {
                            NumericField(
                                value = concentrationText,
                                onValueChange = { concentrationText = it },
                                label = stringResource(R.string.tools_solution_desired_concentration),
                                unit = stringResource(R.string.tools_solution_unit_mg_per_ml),
                            )
                            NumericField(
                                value = amountText,
                                onValueChange = { amountText = it },
                                label = stringResource(R.string.tools_solution_amount),
                                unit = stringResource(R.string.tools_solution_unit_mg),
                            )
                        }
                        SolutionMath.Mode.CONCENTRATION -> {
                            NumericField(
                                value = amountText,
                                onValueChange = { amountText = it },
                                label = stringResource(R.string.tools_solution_amount),
                                unit = stringResource(R.string.tools_solution_unit_mg),
                            )
                            NumericField(
                                value = volumeText,
                                onValueChange = { volumeText = it },
                                label = stringResource(R.string.tools_solution_volume),
                                unit = stringResource(R.string.tools_solution_unit_ml),
                            )
                        }
                    }
                }
            }
        }

        item {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        when (mode) {
                            SolutionMath.Mode.SOLVENT_NEEDED ->
                                stringResource(R.string.tools_solution_result_solvent)
                            SolutionMath.Mode.CONCENTRATION ->
                                stringResource(R.string.tools_solution_result_concentration)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                    if (result != null) {
                        Text(
                            "${
                                SolutionMath.formatResult(result)
                            } $resultUnit",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        TextButton(onClick = {
                            // The formatted number, not the raw one: the label gets what is on screen.
                            clipboard.setText(AnnotatedString(SolutionMath.formatResult(result)))
                        }) {
                            Text(stringResource(R.string.common_copy))
                        }
                    } else {
                        // An em dash rather than "0" or "— ml with a unit": an incomplete form has no
                        // answer, and a zero would read as one.
                        Text("—", style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }

        item {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        stringResource(R.string.tools_solution_safety),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    for (point in listOf(
                        R.string.tools_solution_safety_label,
                        R.string.tools_solution_safety_check,
                        R.string.tools_solution_safety_scale,
                        R.string.tools_solution_safety_store,
                    )) {
                        Text(
                            stringResource(point),
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                }
            }
        }
    }
}

/** One numeric field with its unit shown as a suffix rather than baked into the label. */
@Composable
private fun NumericField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    unit: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        suffix = { Text(unit, color = PiruTheme.colors.secondaryLabel) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            // A decimal keypad, because every field here takes a fraction of a unit and a text keyboard
            // makes the point the hardest character to reach.
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
