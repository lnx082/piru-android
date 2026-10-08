package glass.kagerou.piru.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.model.OklchPickerModel
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.model.SubstanceColorGenerator
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme
import glass.kagerou.piru.ui.theme.toComposeColor
import kotlinx.coroutines.launch
import glass.kagerou.piru.data.SubstanceColorStore
import glass.kagerou.piru.engine.SubstanceCatalog
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction

/**
 * The user's substance colours.
 *
 * Ported from `Views/Settings/SubstanceColorsListView.swift`.
 *
 * ## Which substances are listed
 * The ones **in the user's own log**. Upstream lists the substances that have a
 * colour row and offers the library as the way to add more; here the log is the
 * source, because a colour matters exactly for what the user actually draws — and
 * a picker over 1,689 substances would be a second search interface to build and
 * to keep in step with the first.
 *
 * ## What "reset" means
 * Clearing a colour writes `usesDefault = true`, which is the user declining to
 * choose rather than choosing black. The row survives, so the palette resolver
 * falls through to the generated class colour — and if the generated palette is
 * ever retuned, a reset substance moves with it while a customised one does not.
 */
@Composable
fun SubstanceColorsScreen(
    onChanged: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val scope = rememberCoroutineScope()

    var rows by remember { mutableStateOf<List<ColourRow>>(emptyList()) }
    var editing by remember { mutableStateOf<ColourRow?>(null) }
    var reload by remember { mutableStateOf(0) }
    // The search query. Over the user's own log rather than the whole catalogue, because the rows are the names
    // they typed — see `ColourSearch`'s note on why aliases are not matched.
    var query by remember { mutableStateOf("") }
    // Held outside the effect because the reset action below needs the same instance: a colour
    // store resolves a substance's class colour through the catalogue, so it cannot be built
    // without one.
    var catalog by remember { mutableStateOf<SubstanceCatalog?>(null) }

    LaunchedEffect(reload) {
        val loaded = app.catalog()
        catalog = loaded
        val palette = app.palette()
        // The substances in the log, each with the colour it is drawn in now.
        val names = app.database.doseEntryDao().all().map { it.substance }.distinct()
        val tints = palette.tintsFor(names)
        val stored = app.database.substanceColorDao().all().associateBy { it.substance.lowercase() }
        rows = names.sortedBy { it.lowercase() }.map { name ->
            val entry = stored[name.lowercase()]
            ColourRow(
                name = name,
                tint = tints[name.lowercase()] ?: P3Color.NEUTRAL,
                usesDefault = entry?.usesDefault ?: true,
                defaultTint = loaded.lookup(name)?.let { substance ->
                    SubstanceColorGenerator.displayP3(
                        substance.category,
                        substance.substanceUID ?: substance.name.lowercase(),
                    )
                } ?: P3Color.NEUTRAL,
            )
        }
    }

    // Filtered once, here, rather than inside the `items` call: the empty-state decision below needs the
    // same filtered list, and computing it twice is how the two would disagree.
    val shown = remember(rows, query) { ColourSearch.filter(rows.map { it.name }, query) }
    val shownRows = shown.mapNotNull { name -> rows.firstOrNull { it.name == name } }

    val editingRow = editing
    if (editingRow != null) {
        var model by remember(editingRow.name) {
            mutableStateOf(
                OklchPickerModel.from(
                    current = editingRow.tint,
                    usesDefault = editingRow.usesDefault,
                    defaultTint = editingRow.defaultTint,
                ),
            )
        }
        Column(
            modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                editingRow.name,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
            SubstanceColorPicker(model = model, onChange = { model = it })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { editing = null }) { Text(stringResource(R.string.shell_cancel)) }
                TextButton(onClick = {
                    scope.launch {
                        // `model.tint` is right in both branches: after
                        // `restoreDefault()` the model's colour *is* the class
                        // colour, so a reset stores the generated value alongside
                        // the flag rather than leaving a reader that ignores the
                        // flag looking at the old custom one.
                        val tint = model.tint
                        app.database.substanceColorDao().setColor(
                            name = editingRow.name,
                            red = tint.red,
                            green = tint.green,
                            blue = tint.blue,
                            usesDefault = model.usesDefault,
                        )
                        editing = null
                        // Tell every screen that cached a palette. Without this the
                        // journal kept drawing the old colour until it happened to be
                        // recreated.
                        onChanged()
                        reload++
                    }
                }) { Text(stringResource(R.string.shell_save)) }
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Text(
                stringResource(R.string.shell_settings_substance_colours),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        item {
            Text(
                stringResource(R.string.shell_substance_colours_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = PiruTheme.colors.secondaryLabel,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        // The search, above the list and below the intro. A field rather than a filter chip row, because the
        // list is a few dozen names and a user arrives knowing which one they want.
        if (rows.isNotEmpty()) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.shell_substance_colours_search)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Reset every colour to its class default.
        //
        // `SubstanceColorStore.resetAll` has existed since the store was written with no caller, so
        // a colour the user picked could be changed one at a time and never undone — the only way
        // back was to find each substance and set it again. This is the caller.
        if (rows.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                catalog?.let { SubstanceColorStore(app.database, it).resetAll() }
                                reload++
                            }
                        },
                    ) {
                        Text(stringResource(R.string.shell_substance_colours_reset_all))
                    }
                }
            }
        }
        if (shownRows.isEmpty()) {
            item {
                Text(
                    // Two different facts about the user's own data, and telling a user who searched that they
                    // have logged nothing would be a lie about it.
                    stringResource(
                        if (ColourSearch.emptyStateIsSearchResult(rows.size, query)) {
                            R.string.shell_substance_colours_no_match
                        } else {
                            R.string.shell_substance_colours_empty
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }
        items(shownRows, key = { it.name }) { row ->
            PiruCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { editing = row },
            ) {
                Row(
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(modifier = Modifier.size(28.dp)) {
                        Canvas(Modifier.fillMaxSize()) {
                            // Through the shared helper rather than built inline: the swatch
                            // has to be the same colour as the chart it is choosing for, and
                            // an inline sRGB `Color(...)` here is how that stopped being true.
                            drawCircle(color = row.tint.toComposeColor())
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(row.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (row.usesDefault) {
                                stringResource(R.string.shell_substance_colour_class)
                            } else {
                                stringResource(R.string.shell_substance_colour_custom)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                }
            }
        }
    }
}

/** One substance, the colour it is drawn in, and the class colour it would otherwise have. */
private data class ColourRow(
    val name: String,
    val tint: P3Color,
    val usesDefault: Boolean,
    val defaultTint: P3Color,
)
