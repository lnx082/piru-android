package glass.kagerou.piru.ui.journal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.data.AppSettingsStore
import glass.kagerou.piru.data.QuickLogRecents
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.nav.SheetRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The journal's quick-log dock: the substances this user has logged lately, one tap from another dose.
 *
 * ## Why it is at the top of the screen
 * The journal is read in the order **plan → state → log**: what is due, what is active, what happened. The dock is
 * none of those — it is an action, and the fastest one the screen offers, because a repeat dose is the commonest thing
 * a user logs. Putting it under the log would make the quickest action the furthest away.
 *
 * ## The collapsed presentation, which is what this is
 * The port has had the quick-log **sheet** from the beginning and its own note records that the *collapsed dock* was
 * not ported. That omission is why `AppSettingsStore.showQuickLogDock` had no consumer: there was nothing to hide.
 * This is the missing half, and the preference is now read by something.
 *
 * ## What it does not do yet, stated
 * Tapping a chip opens the sheet **without pre-filling it**. Pre-filling needs `SheetRoute.QuickLog` to carry the
 * substance, which is a route change rather than a view change, so it is deliberately left: a chip that opens the sheet
 * already saves the search, which is most of the point, and a chip that claimed to pre-fill and did not would be worse
 * than one that plainly opens the form. Named here rather than left for a user to discover.
 *
 * ## Order is the dock's own, not newest-first
 * The chips are shown in the order `QuickLogRecents` stored them — which is the order the user arranged, floating a
 * used chip to the front of its group — rather than re-sorted here. A dock that re-ordered itself on every render would
 * move the chip under the thumb that was about to press it.
 */
@Composable
internal fun JournalQuickLogDock(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    var chips by remember { mutableStateOf<List<QuickLogRecents.LoggedDose>>(emptyList()) }
    var shown by remember { mutableStateOf(false) }

    LaunchedEffect(navigator.dataVersion) {
        val settings = AppSettingsStore(context)
        shown = settings.showQuickLogDock()
        if (!shown) {
            chips = emptyList()
            return@LaunchedEffect
        }
        chips = runCatching {
            withContext(Dispatchers.IO) {
                // Seeded from the same rows the sheet writes, through the same fold, so the dock and the sheet can
                // never disagree about which substances are recent.
                // The **stored** chips, not a fresh seed: see \stored\ for why seeding would re-sort them and undo the
                // floating order the user arranged.
                QuickLogRecents.stored(app.database.quickLogDoseDao().all())
            }
        }.getOrDefault(emptyList())
    }

    if (!shown || chips.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            // Scrollable rather than wrapped: a dock is one row by definition, and a second row would push the log
            // down by a chip's height on the days a user has logged most.
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (chip in chips) {
            AssistChip(
                onClick = { navigator.present(SheetRoute.QuickLog) },
                label = {
                    // The product name when the user logged under one, because that is their word for it: a chip
                    // reading "Concerta" is findable in a way "Methylphenidate" is not.
                    Text(
                        chip.productName?.takeIf { it.isNotBlank() } ?: chip.substance,
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
            )
        }
    }
}
