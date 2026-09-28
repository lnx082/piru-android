package glass.kagerou.piru.ui.tools

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.R
import glass.kagerou.piru.model.SubstanceCategory
import glass.kagerou.piru.model.SubstanceColorGenerator
import glass.kagerou.piru.substance.SubstanceReader
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.labels.CoreLabels
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.nav.PushRoute
import glass.kagerou.piru.ui.theme.PiruTheme
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.ui.theme.toComposeColor

/**
 * The drug classes, from either end: the whole catalog as a list, or one class.
 *
 * Ported from `Views/Tools/DrugClassView.swift` (230 lines): its
 * `DrugClassListView` is the browse half and its `DrugClassDetailView` the
 * detail half, and this is both, because the two are one tap apart and a route
 * that had to name a class could not open the list.
 *
 * ## `className` empty means "browse"
 * The route that reaches this screen (`PushRoute.Tool(DRUG_CLASS)`) carries no
 * payload, so the hub's own entry point arrives as an empty string and gets the
 * list of every class. A non-empty value — a slug, or a display name — is a
 * specific class and goes straight to its detail. `SubstanceReader.classContext`
 * resolves either spelling, so a caller cannot land on a blank screen for
 * passing the name it could see.
 *
 * ## Choosing from the list is state, not a route
 * Tapping a row opens that class **without** pushing: the browse-to-detail step
 * would otherwise need a route carrying a payload, and the two screens are one
 * screen. Back is wired to undo the step in place, so the system gesture returns
 * to the list rather than leaving the tool — which is what it would do if the
 * detail were a separate entry on the stack and the list were behind it.
 *
 * ## The detail is laid out like a substance screen, not a wall of prose
 * A title block carrying the family the class belongs to, then each shared
 * property under its own heading. The alternative — one long paragraph — is what
 * the curated data literally is, and it is unreadable: the four fields answer
 * four different questions.
 */
@Composable
fun DrugClassScreen(className: String, navigator: AppNavigator, modifier: Modifier = Modifier) {
    val browsable = className.isBlank()
    var chosen by remember(className) { mutableStateOf<String?>(null) }

    if (browsable && chosen == null) {
        DrugClassBrowse(onOpen = { chosen = it }, modifier = modifier)
    } else {
        if (browsable) {
            // Registered only in the browse-to-detail step: a caller that named a
            // class directly did not come from the list, and back there should
            // leave the tool as usual.
            BackHandler { chosen = null }
        }
        DrugClassDetail(
            className = if (browsable) chosen.orEmpty() else className,
            navigator = navigator,
            modifier = modifier,
        )
    }
}

/**
 * Every class write-up, name first and subtitle under it.
 *
 * No member count and no category chip: the row's job is to be recognisable at a
 * glance while scrolling, and the detail screen one tap away carries both.
 */
@Composable
private fun DrugClassBrowse(onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as PiruApplication }
    var classes by remember { mutableStateOf<List<SubstanceReader.ClassContext>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        classes = app.catalog().classContexts()
        loaded = true
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.drug_class_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.drug_class_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        if (loaded && classes.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.drug_class_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        items(classes, key = { it.slug }) { entry ->
            PiruCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onOpen(entry.slug) },
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // Verbatim from the catalog rather than routed through the
                    // string table: a class name is data read from the research
                    // write-up, not a key this build can translate.
                    Text(entry.title, style = MaterialTheme.typography.bodyLarge)
                    entry.subtitle?.let { subtitle ->
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                }
            }
        }

        item {
            // The disclaimer stays English — see the note in IdentifyScreen.
            Text(
                stringResource(R.string.drug_class_footer) + " Not medical advice.",
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
        }
    }
}

/** One class: its shared write-ups, its members, and the studies behind them. */
@Composable
private fun DrugClassDetail(className: String, navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as PiruApplication }
    var resolved by remember(className) { mutableStateOf<SubstanceReader.ClassContext?>(null) }
    var loaded by remember(className) { mutableStateOf(false) }

    LaunchedEffect(className) {
        resolved = app.catalog().classContext(className)
        loaded = true
    }

    val resolvedClass = resolved

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp)) {
                Text(
                    resolvedClass?.title ?: stringResource(R.string.drug_class_fallback_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        }

        if (loaded && resolvedClass == null) {
            item {
                Text(
                    // An empty state is information: it says what would fill it,
                    // which for a route payload is the identifier that did not
                    // resolve and where a working one comes from.
                    stringResource(R.string.drug_class_not_found, className),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
            return@LazyColumn
        }

        val entry = resolvedClass ?: return@LazyColumn

        item {
            ClassHeader(entry)
        }

        Paragraph(R.string.drug_class_shared_mechanism, entry.sharedMechanism)
        Paragraph(R.string.drug_class_shared_kinetics, entry.sharedPharmacokinetics)
        Paragraph(R.string.drug_class_shared_safety, entry.sharedSafety)
        Paragraph(R.string.drug_class_shared_sar, entry.sarSummary)

        if (entry.siblings.isNotEmpty()) {
            item {
                Text(
                    memberHeading(entry.siblings.size),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            items(entry.siblings, key = { it }) { name ->
                PiruCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { navigator.push(PushRoute.Substance(name)) },
                ) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }

        if (entry.references.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.drug_class_references),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            items(entry.references, key = { it.id }) { reference ->
                ReferenceRow(reference)
            }
        }

        item {
            // The disclaimer stays English — see the note in IdentifyScreen.
            Text(
                stringResource(R.string.drug_class_footer) + " Not medical advice.",
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
        }
    }
}

/**
 * The title block: the class's name, the family it belongs to, and what it covers.
 *
 * The family chip is tinted with the catalogue's own colour for that category —
 * the same generator the library's browse grid uses, seeded by the category
 * itself so it is stable and needs no per-class table.
 */
@Composable
private fun ClassHeader(entry: SubstanceReader.ClassContext) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            entry.category?.let { category ->
                // Hoisted out of the composition-local read for the same reason
                // the charts hoist their colours: one read, not one per access.
                val tint = SubstanceColorGenerator.displayP3(category, category.wireValue).toComposeColor()
                Text(
                    browseTitle(category).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = tint,
                )
            }
            entry.subtitle?.let { subtitle ->
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }
    }
}

/**
 * One authored shared-write-up section, rendered only when the class carries it.
 *
 * The heading arrives as a resource id rather than a sentence: this runs in the
 * `LazyListScope` builder, which is not a composable scope, so the read has to
 * happen inside the `item`.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.Paragraph(
    @StringRes title: Int,
    text: String?,
) {
    if (text.isNullOrEmpty()) return
    item {
        PiruCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }
    }
}

/** One citation: its title, and whichever identifier the catalog carries for it. */
@Composable
private fun ReferenceRow(reference: SubstanceReader.ClassReference) {
    val uriHandler = LocalUriHandler.current
    val doi = reference.doi
    val pmid = reference.pmid

    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                reference.title ?: stringResource(R.string.drug_class_untitled_reference),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (doi != null) {
                    Text(
                        "doi:$doi",
                        style = MaterialTheme.typography.bodySmall,
                        color = PiruTheme.colors.accent,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable { uriHandler.openUri("https://doi.org/$doi") },
                    )
                }
                if (pmid != null) {
                    Text(
                        "PMID $pmid",
                        style = MaterialTheme.typography.bodySmall,
                        color = PiruTheme.colors.accent,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable {
                            uriHandler.openUri("https://pubmed.ncbi.nlm.nih.gov/$pmid/")
                        },
                    )
                }
            }
        }
    }
}

/**
 * The member heading. Hand-written pluralisation replaced by a `<plurals>`
 * resource: the counts here run to a hundred, so "1 substance" has to be right,
 * and Chinese has no plural form to get wrong.
 */
@Composable
private fun memberHeading(count: Int): String =
    pluralStringResource(R.plurals.drug_class_member_count, count, count)

/**
 * The category's browse label.
 *
 * Two categories read better under a different name in a browse context, which
 * is the whole reason the source carries a browse title alongside the plain
 * category name. Everything else is the category name itself, so it comes from
 * [CoreLabels.category] — the wire value spells that name in English, and
 * printing it put "Stimulant" on a Chinese screen. (It also spells it wrong for
 * one category: the wire value is `OrexinAntagonist`, the name is "Orexin
 * Antagonist".)
 */
@Composable
private fun browseTitle(category: SubstanceCategory): String = when (category) {
    SubstanceCategory.DEPRESSANT -> stringResource(R.string.drug_class_browse_depressant)
    SubstanceCategory.OTHER -> stringResource(R.string.drug_class_browse_other)
    else -> CoreLabels.category(category)
}
