package glass.kagerou.piru.ui.tools

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.model.SubstanceCategory
import glass.kagerou.piru.substance.DbSubstanceCatalog
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.labels.CoreLabels
import glass.kagerou.piru.ui.library.formatHalfLife
import glass.kagerou.piru.ui.theme.PiruTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * A spreadsheet over the library's pharmacology: one row per substance, one column per fact.
 *
 * Ported from `PharmaTableView`. The point of it is comparison — "which of these lasts longest", "which is
 * absorbed best" — which is a question no per-substance page can answer and the reason a table is worth
 * building at all.
 *
 * ## What this port does differently, and why
 * Upstream freezes the name column and scrolls the rest horizontally in sync, with a `@State` box tracking
 * the body's content offset so the header follows. That is a lot of machinery for a phone. Here the whole
 * table scrolls horizontally as one — header and rows share a single `ScrollState`, so they cannot desync by
 * construction — and the name column is **not** frozen. On a phone the frozen column would occupy most of
 * the viewport before any data appeared, which is the opposite of the point.
 *
 * ## Scoping, and why it opens on "Common"
 * Upstream's own performance note: defaulting to the full library means rendering ~1,100 rows on open. The
 * default scope here is the substances with a **half-life**, which is the column the table leads with, and
 * the full library is one tap away.
 */
@Composable
fun PharmaTableScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication

    var rows by remember { mutableStateOf<List<DbSubstanceCatalog.PharmaTableRow>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(PharmaScope.HALF_LIFE) }
    var sort by remember { mutableStateOf(PharmaSort.NAME) }
    var ascending by remember { mutableStateOf(true) }
    var showPkColumns by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        rows = runCatching {
            withContext(Dispatchers.Default) { app.catalog().pharmaTableRows() }
        }.getOrDefault(emptyList())
        loaded = true
    }

    val scoped = remember(rows, scope) {
        when (scope) {
            PharmaScope.HALF_LIFE -> rows.filter { it.halfLifeMin != null }
            PharmaScope.ALL -> rows
        }
    }
    val visible = remember(scoped, query, sort, ascending) {
        val trimmed = query.trim()
        val searched = if (trimmed.isEmpty()) {
            scoped
        } else {
            scoped.filter { it.name.contains(trimmed, ignoreCase = true) }
        }
        val sorted = when (sort) {
            PharmaSort.NAME -> searched.sortedBy { it.displayName.lowercase() }
            PharmaSort.HALF_LIFE -> searched.sortedBy { it.halfLifeMin ?: Double.MAX_VALUE }
            PharmaSort.BIOAVAILABILITY -> searched.sortedBy { it.bioavailabilityPct ?: Double.MAX_VALUE }
            PharmaSort.TMAX -> searched.sortedBy { it.tmaxMin ?: Double.MAX_VALUE }
            PharmaSort.VD -> searched.sortedBy { it.vdLPerKg ?: Double.MAX_VALUE }
        }
        if (ascending) sorted else sorted.reversed()
    }

    val horizontal = rememberScrollState()

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(
                modifier = Modifier.padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.tools_pharma_table_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    if (loaded) {
                        stringResource(R.string.tools_pharma_table_count, visible.size)
                    } else {
                        stringResource(R.string.tools_pharma_table_loading)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.tools_pharma_table_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (candidate in PharmaScope.entries) {
                    FilterChip(
                        selected = scope == candidate,
                        onClick = { scope = candidate },
                        label = { Text(stringResource(candidate.labelRes)) },
                    )
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.tools_pharma_table_sort),
                    style = MaterialTheme.typography.labelLarge,
                    color = PiruTheme.colors.secondaryLabel,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (candidate in PharmaSort.entries) {
                        FilterChip(
                            selected = sort == candidate,
                            onClick = {
                                // Tapping the active column flips the direction, which is what a table
                                // header does and what a reader expects from a second tap.
                                if (sort == candidate) ascending = !ascending else sort = candidate
                            },
                            label = { Text(stringResource(candidate.labelRes)) },
                        )
                    }
                }
                TextButton(onClick = { showPkColumns = !showPkColumns }) {
                    Text(
                        stringResource(
                            if (showPkColumns) {
                                R.string.tools_pharma_table_hide_pk
                            } else {
                                R.string.tools_pharma_table_show_pk
                            },
                        ),
                    )
                }
            }
        }

        // The header and every row share one scroll state, so they cannot drift apart — which is the
        // problem upstream's header-offset box exists to solve.
        item {
            Row(modifier = Modifier.horizontalScroll(horizontal)) {
                HeaderCell(stringResource(R.string.tools_pharma_col_substance), 168.dp)
                HeaderCell(stringResource(R.string.tools_pharma_col_class), 132.dp)
                HeaderCell(stringResource(R.string.tools_pharma_col_half_life), 96.dp)
                if (showPkColumns) {
                    HeaderCell(stringResource(R.string.tools_pharma_col_tmax), 96.dp)
                    HeaderCell(stringResource(R.string.tools_pharma_col_bioavailability), 96.dp)
                    HeaderCell(stringResource(R.string.tools_pharma_col_cmax), 104.dp)
                    HeaderCell(stringResource(R.string.tools_pharma_col_protein_binding), 96.dp)
                    HeaderCell(stringResource(R.string.tools_pharma_col_vd), 96.dp)
                    HeaderCell(stringResource(R.string.tools_pharma_col_clearance), 112.dp)
                }
            }
        }

        if (loaded && visible.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.tools_pharma_table_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        items(visible, key = { it.name }) { row ->
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.horizontalScroll(horizontal)) {
                    BodyCell(row.displayName, 168.dp, emphasis = true)
                    BodyCell(row.classTitle ?: CoreLabels.category(row.category), 132.dp)
                    BodyCell(row.halfLifeMin?.let { formatHalfLife(it) }, 96.dp)
                    if (showPkColumns) {
                        BodyCell(row.tmaxMin?.let { formatMinutes(it) }, 96.dp)
                        BodyCell(row.bioavailabilityPct?.let { formatPercentValue(it) }, 96.dp)
                        BodyCell(row.cmaxNgPerMl?.let { formatNumber(it) }, 104.dp)
                        BodyCell(row.proteinBindingPct?.let { formatPercentValue(it) }, 96.dp)
                        BodyCell(row.vdLPerKg?.let { formatNumber(it) }, 96.dp)
                        BodyCell(row.clearanceMlPerMinPerKg?.let { formatNumber(it) }, 112.dp)
                    }
                }
            }
        }
    }
}

/** Which substances the table shows. */
private enum class PharmaScope(val labelRes: Int) {
    /** Substances with a curated half-life — the column the table leads with. */
    HALF_LIFE(R.string.tools_pharma_scope_half_life),

    /** Everything with any PK signal. ~500 rows, so opt-in. */
    ALL(R.string.tools_pharma_scope_all),
}

/** Which column the table is ordered by. */
private enum class PharmaSort(val labelRes: Int) {
    NAME(R.string.tools_pharma_col_substance),
    HALF_LIFE(R.string.tools_pharma_col_half_life),
    TMAX(R.string.tools_pharma_col_tmax),
    BIOAVAILABILITY(R.string.tools_pharma_col_bioavailability),
    VD(R.string.tools_pharma_col_vd),
}

@Composable
private fun HeaderCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = PiruTheme.colors.secondaryLabel,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width).padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

/**
 * One data cell.
 *
 * A null prints an em dash rather than a blank: "not measured" is a fact about the catalogue and a blank
 * cell reads as a rendering failure — which matters most in a column a reader is comparing across.
 */
@Composable
private fun BodyCell(value: String?, width: androidx.compose.ui.unit.Dp, emphasis: Boolean = false) {
    Text(
        value ?: "—",
        style = if (emphasis) {
            MaterialTheme.typography.bodyMedium
        } else {
            MaterialTheme.typography.bodySmall
        },
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width).padding(horizontal = 6.dp, vertical = 6.dp),
    )
}

/** A percentage from the `_pct` columns, which are already percentages. */
private fun formatPercentValue(value: Double): String {
    val rounded = Math.round(value * 10.0) / 10.0
    return if (rounded == rounded.toLong().toDouble()) {
        "${rounded.toLong()}%"
    } else {
        String.format(Locale.ROOT, "%.1f%%", rounded)
    }
}

/** A bare number to at most two decimals, without trailing zeros. */
private fun formatNumber(value: Double): String {
    val rounded = Math.round(value * 100.0) / 100.0
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        String.format(Locale.ROOT, "%.2f", rounded).trimEnd('0').trimEnd('.')
    }
}

/** A Tmax in minutes, in the unit a person would say it in. */
private fun formatMinutes(value: Double): String =
    if (value < 60) {
        String.format(Locale.ROOT, "%.0f min", value)
    } else {
        String.format(Locale.ROOT, "%.1f h", value / 60.0)
    }
