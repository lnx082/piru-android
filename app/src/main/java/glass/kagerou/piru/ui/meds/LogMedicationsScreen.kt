package glass.kagerou.piru.ui.meds

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.data.entity.DailyDoseItemEntity
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.engine.InteractionChecker
import glass.kagerou.piru.engine.InteractionPolicy
import glass.kagerou.piru.engine.InteractionProminence
import glass.kagerou.piru.engine.InteractionResult
import glass.kagerou.piru.engine.InteractionSeverity
import glass.kagerou.piru.engine.admitted
import glass.kagerou.piru.model.doseFormatted
import glass.kagerou.piru.notifications.toDoseRecord
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.labels.CoreLabels
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.nav.PushRoute
import glass.kagerou.piru.ui.theme.PiruTheme
import glass.kagerou.piru.ui.tools.InteractionSeverityPalette
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import kotlinx.coroutines.launch

/**
 * "Log my meds": every med in one category, each with a switch, and one button
 * that records them all.
 *
 * Ported from `Piru/Views/Journal/DailyDose/LogMedicationsView.swift` (212
 * lines), including the `InteractionWarningSheet` it carries at its foot —
 * upstream keeps the two in one file and so does this.
 *
 * ## Switches default to on
 * The sheet exists to log a whole group in one tap, so a med has to be *left
 * out* rather than added. The switch state is keyed by substance + sort order
 * rather than by row id, which is upstream's `itemKey`: two rows for one
 * substance — a brand and its generic — stay distinguishable, and a reload that
 * renumbers row ids does not silently reset a choice.
 *
 * ## Why `WARN`, and what "notable" means here
 * Only a finding that has earned an interruption stops the log. A `caution`
 * pair — two stimulants, cannabis with a benzo — is true and belongs in the
 * session review, not in front of the button; a sheet that appears for those is
 * a sheet people learn to dismiss unread. `InteractionPolicy.WARN` is the
 * log-time gate (the Tools explorer passes `EXPLORE`), and
 * `InteractionProminence.NOTABLE` is the same floor as upstream's
 * `admitted(.notable)`.
 *
 * ## One divergence
 * Upstream ends by calling `navigator.dismissAll()`, clearing the whole logging
 * chain back to root. This port takes [onDismissed] instead, because only the
 * host knows whether the screen was pushed or presented — and a screen that
 * popped a stack it did not own would be the wrong kind of helpful.
 */
@Composable
fun LogMedicationsScreen(
    category: String,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val zone = remember { ZoneId.systemDefault() }
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<DailyDoseItemEntity>>(emptyList()) }
    var recentEntries by remember { mutableStateOf<List<DoseEntryEntity>>(emptyList()) }
    var checker by remember { mutableStateOf<InteractionChecker?>(null) }
    var toggleStates by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var warnings by remember { mutableStateOf<List<InteractionResult>>(emptyList()) }
    var showInteractionSheet by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val now = Instant.now()
        items = app.database.dailyDoseItemDao().all().filter { it.category == category }
        recentEntries = app.database.doseEntryDao()
            .inRange(Date.from(now.minus(48, ChronoUnit.HOURS)), Date.from(now))
        checker = app.catalog().interactionChecker
    }

    val selected = items.filter { toggleStates[itemKey(it)] ?: true }

    fun logSelected() {
        if (selected.isEmpty() || saving) return
        saving = true
        scope.launch {
            MedsStore.log(app, selected, Instant.now())
            saving = false
            onDismissed()
        }
    }

    fun attemptLog() {
        val activeChecker = checker
        val active = activeChecker
            ?.activeEntries(recentEntries.map { it.toDoseRecord() })
            .orEmpty()
        val found = activeChecker
            ?.checkBatch(
                selected.map { it.substance },
                against = active,
                policy = InteractionPolicy.WARN,
                now = Instant.now(),
            )
            ?.admitted(InteractionProminence.NOTABLE)
            .orEmpty()
        if (found.isEmpty()) {
            logSelected()
        } else {
            warnings = found
            showInteractionSheet = true
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (category.isEmpty()) {
                    stringResource(R.string.meds_log_meds)
                } else {
                    stringResource(R.string.meds_log_category, category)
                },
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            MedsGlyph(
                kind = MedsGlyphKind.CLOSE,
                tint = PiruTheme.colors.secondaryLabel,
                size = 18.dp,
                modifier = Modifier.clickable(onClick = onDismissed).padding(6.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
        ) {
            item {
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.meds_log_toggle_off_hint),
                            style = captionSecondaryStyle,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        for (item in items) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(displayName(item), style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        stringResource(
                                            R.string.meds_dose_route_dash,
                                            "${doseFormatted(item.amount)} ${item.unit}",
                                            CoreLabels.route(item.route),
                                        ),
                                        style = captionSecondaryStyle,
                                    )
                                }
                                Switch(
                                    checked = toggleStates[itemKey(item)] ?: true,
                                    onCheckedChange = { on ->
                                        toggleStates = toggleStates + (itemKey(item) to on)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            item {
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = selected.isNotEmpty() && !saving, onClick = { attemptLog() })
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            if (saving) {
                                stringResource(R.string.meds_saving)
                            } else {
                                pluralStringResource(
                                    R.plurals.meds_log_items,
                                    selected.size,
                                    selected.size,
                                )
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = if (selected.isEmpty()) {
                                PiruTheme.colors.tertiaryLabel
                            } else {
                                PiruTheme.colors.accent
                            },
                        )
                    }
                }
            }
        }
    }

    if (showInteractionSheet) {
        InteractionWarningSheet(
            warnings = warnings,
            onProceed = {
                showInteractionSheet = false
                logSelected()
            },
            onCancel = {
                showInteractionSheet = false
                warnings = emptyList()
            },
        )
    }
}

/** Two rows for one substance — a brand and its generic — stay distinguishable. */
private fun itemKey(item: DailyDoseItemEntity): String = item.substance + item.sortOrder

// MARK: - Interaction Warning Sheet

/**
 * The confirmation a log has to pass when the checker found something notable.
 *
 * Ported from `InteractionWarningSheet` in `LogMedicationsView.swift` (lines
 * 159-212). Upstream presents it as a `NavigationStack` + `List` with a
 * half-height detent; this build's sheet idiom is `ModalBottomSheet` (see
 * `AdherenceScreen`), which is the same presentation with the platform's own
 * dismiss gesture.
 *
 * The warnings are rendered by [MedsWarningRow] rather than the Tools
 * explorer's private `InteractionWarningRow`, which is not importable — see
 * that row's note.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InteractionWarningSheet(
    warnings: List<InteractionResult>,
    onProceed: () -> Unit,
    onCancel: () -> Unit,
    onOpenTimeline: ((InteractionResult) -> Unit)? = null,
) {
    ModalBottomSheet(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.meds_interaction_warning),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                pluralStringResource(
                    R.plurals.meds_interactions_detected,
                    warnings.size,
                    warnings.size,
                ),
                style = captionSecondaryStyle,
            )

            for (warning in warnings) {
                MedsWarningRow(
                    warning = warning,
                    onOpen = onOpenTimeline?.let { { it(warning) } },
                )
            }

            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onProceed).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = PiruTheme.colors.danger,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.meds_log_anyway),
                        style = MaterialTheme.typography.titleSmall,
                        color = PiruTheme.colors.danger,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * One warning, as a severity chip over the pair and the mechanism sentence.
 *
 * The Tools explorer's row of the same shape is `private` to
 * `ui/tools/InteractionsScreen.kt`, and this build's components package holds
 * only `PiruCard.kt`, so the choice is a fourth copy or a widened visibility on a
 * file about a different feature. Copying is what the app already does
 * (`InsightsChrome`, `DepotSections` and `InventorySummaryCard` each keep their
 * own chip), so it is what this does.
 */
@Composable
private fun MedsWarningRow(
    warning: InteractionResult,
    onOpen: (() -> Unit)?,
) {
    val tint = severityTint(warning.severity)
    PiruCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(tint.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        CoreLabels.severity(warning.severity),
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                    )
                }
                Text(
                    "${warning.substanceA} + ${warning.substanceB}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(warning.description, style = captionSecondaryStyle)
        }
    }
}

/**
 * The severity inks.
 *
 * `InteractionSeverityPalette` is the app's one severity palette and is
 * `internal` to this module, so the meds screens read it rather than inventing a
 * second mapping — a warning that wore a different amber here than in the
 * explorer would read as a different warning.
 */
@Composable
private fun severityTint(severity: InteractionSeverity): Color =
    InteractionSeverityPalette.accent(severity)

// MARK: - Timeline

/**
 * Push the two-substance timeline for a warning.
 *
 * `PushRoute.InteractionTimeline` already exists, so this is the one navigation
 * the meds screens do themselves rather than hoisting — there is no new route to
 * invent.
 */
internal fun openWarningTimeline(navigator: AppNavigator, warning: InteractionResult) {
    navigator.push(PushRoute.InteractionTimeline(warning.substanceA, warning.substanceB))
}
