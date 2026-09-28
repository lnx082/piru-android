package glass.kagerou.piru.ui.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.engine.ActiveSubstance
import glass.kagerou.piru.engine.ActiveSubstanceCalculator
import glass.kagerou.piru.engine.DoseRecord
import glass.kagerou.piru.engine.SubstanceCatalog
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.model.SubstanceCategory
import glass.kagerou.piru.model.SubstanceColorGenerator
import glass.kagerou.piru.model.doseFormatted
import glass.kagerou.piru.model.unitDisplay
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.labels.CoreLabels
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.nav.PushRoute
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.delay
import glass.kagerou.piru.ui.theme.toComposeColor

/**
 * The three static-content screens: the comedown guide, Get Help, and the
 * education index.
 *
 * Ported from `Views/Tools/ComedownGuideView.swift` (420 lines),
 * `Views/Tools/HelpView.swift` (721 lines) and `Views/Tools/EducationCard.swift`
 * (100 lines).
 *
 * ## The copy is the product here, and it is verbatim
 * Every sentence in the guide tables and the emergency list is the original's,
 * carried across unchanged. These screens do not model anything — they are the
 * app telling someone what sources report about a class, and which number to
 * call. Rewriting a line to read better is how a warning quietly loses its
 * qualification; the two screens that hold a sentence like *"a dose you handled
 * before is the documented cause of many overdoses"* are exactly the ones where
 * that matters.
 *
 * ## What the port could not carry
 * The originals lead each row with an SF Symbol. This build ships the small
 * Material icon set, which has no equivalent for `drop.fill`, `figure.walk` or
 * `heart.text.clipboard`, so a row carries its sentence and, where the original
 * colour-coded it, a bullet in the same secondary label the guide's own `•`
 * lists use. Nothing is claimed by the omission — the mapping belongs with the
 * rest of the icon set — and no row is left looking tappable that is not.
 */

// MARK: - Comedown guide

/**
 * What sources report about the hours after each class wears off.
 *
 * ## Why the recent classes come first
 * The guide is ordered by *your* last 48 hours when there is anything to order:
 * the class somebody is coming down from right now is the one they opened this
 * for, and making them find it in an alphabetical list is the failure mode. The
 * per-class lookup runs once in the effect, not per frame — the original says so
 * explicitly, because it resolves a substance per dose through the catalog.
 */
@Composable
fun ComedownGuideScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    var recent by remember { mutableStateOf<List<SubstanceCategory>>(emptyList()) }

    LaunchedEffect(navigator.dataVersion) {
        val catalog = app.catalog()
        val entries = app.database.doseEntryDao().all()
        recent = recentGuidedCategories(entries, Instant.now(), catalog)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            // No subtitle: the card immediately below says what the screen is,
            // and saying it twice puts two identical paragraphs a swipe apart.
            Text(
                stringResource(R.string.comedown_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        item {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.comedown_what_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.comedown_what_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                    Text(
                        stringResource(R.string.comedown_what_emergency),
                        style = MaterialTheme.typography.bodySmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }

        if (recent.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.comedown_recent_heading), Modifier.padding(top = 6.dp)) }
            items(recent, key = { it.wireValue }) { category ->
                CategoryDisclosure(category)
            }
        }

        item { SectionLabel(stringResource(R.string.comedown_all_heading), Modifier.padding(top = 6.dp)) }
        items(GUIDED_CATEGORIES.filter { it !in recent }, key = { it.wireValue }) { category ->
            CategoryDisclosure(category)
        }

        item { SectionLabel(stringResource(R.string.comedown_basics_heading), Modifier.padding(top = 6.dp)) }
        item {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (tip in UNIVERSAL_BASICS) {
                        BulletText(stringResource(tip))
                    }
                }
            }
        }

        item {
            // The disclaimer stays English — see the note in IdentifyScreen.
            Text(
                stringResource(R.string.comedown_footer) + " Not medical advice.",
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
        }
    }
}

/**
 * The eight classes the guide covers, in the order they are shown.
 *
 * Declaration order *is* the display order and is upstream's; it runs from the
 * classes with the most written about them to the ones with a shorter note.
 */
private val GUIDED_CATEGORIES: List<SubstanceCategory> = listOf(
    SubstanceCategory.STIMULANT,
    SubstanceCategory.EMPATHOGEN,
    SubstanceCategory.PSYCHEDELIC,
    SubstanceCategory.DISSOCIATIVE,
    SubstanceCategory.OPIOID,
    SubstanceCategory.BENZODIAZEPINE,
    SubstanceCategory.DEPRESSANT,
    SubstanceCategory.CANNABINOID,
)

/** The seven general tips, in the original's fixed order. */
private val UNIVERSAL_BASICS: List<Int> = listOf(
    R.string.comedown_basics_1,
    R.string.comedown_basics_2,
    R.string.comedown_basics_3,
    R.string.comedown_basics_4,
    R.string.comedown_basics_5,
    R.string.comedown_basics_6,
    R.string.comedown_basics_7,
)

/**
 * The guided classes present in [entries] since 48 hours before [now],
 * newest-first and de-duplicated by first appearance.
 *
 * The cutoff is applied here rather than in a query so this is a pure function of
 * its inputs, which is what makes it testable and what the session detail's
 * recovery section shares. Running it over the log directly is why it belongs in
 * an effect: it resolves one substance per dose through the catalog.
 */
private fun recentGuidedCategories(
    entries: List<DoseEntryEntity>,
    now: Instant,
    catalog: SubstanceCatalog,
): List<SubstanceCategory> {
    val cutoff = now.minus(Duration.ofHours(48))
    val guided = GUIDED_CATEGORIES.toSet()
    val seen = mutableSetOf<SubstanceCategory>()
    val out = mutableListOf<SubstanceCategory>()
    // Newest first: the caller passes the log in the order a query would return
    // it, and the first class seen is the most recent one.
    for (entry in entries.sortedByDescending { it.timestamp.toInstant() }) {
        val stamp = entry.timestamp.toInstant()
        if (stamp.isBefore(cutoff)) continue
        val category = catalog.lookup(entry.substance)?.category ?: continue
        if (category in guided && seen.add(category)) out += category
    }
    return out
}

/**
 * One class's four tip groups.
 *
 * Resource ids rather than sentences: the table is read by a `Text` two levels
 * down, and keeping the copy out of the table is what lets the same English be
 * one translation wherever it repeats.
 */
private data class CategoryGuide(
    val whatsHappening: List<Int>,
    val rightNow: List<Int>,
    val nextHours: List<Int>,
    val avoid: List<Int>,
)

/**
 * The guide for one class.
 *
 * Verbatim from `ComedownGuideView.guide(for:)`. The default case is upstream's
 * and is reached by every class the guide does not name — including the
 * categories a reader reached this screen from a substance page with. The
 * "Piru doesn't measure any of this" line is present on every class the source
 * gives it to, and is absent on the two where the source omits it; that
 * inconsistency is the original's and is kept.
 */
@Suppress("CyclomaticComplexMethod")
private fun guide(category: SubstanceCategory): CategoryGuide = when (category) {
    SubstanceCategory.STIMULANT -> CategoryGuide(
        whatsHappening = listOf(
            R.string.comedown_stimulant_happening_1,
            R.string.comedown_stimulant_happening_2,
            R.string.comedown_not_measured,
        ),
        rightNow = listOf(
            R.string.comedown_stimulant_now_1,
            R.string.comedown_sip_electrolytes,
            R.string.comedown_stimulant_now_2,
            R.string.comedown_stimulant_now_3,
        ),
        nextHours = listOf(
            R.string.comedown_stimulant_hours_1,
            R.string.comedown_stimulant_hours_2,
            R.string.comedown_stimulant_hours_3,
            R.string.comedown_stimulant_hours_4,
        ),
        avoid = listOf(
            R.string.comedown_stimulant_avoid_1,
            R.string.comedown_stimulant_avoid_2,
            R.string.comedown_stimulant_avoid_3,
            R.string.comedown_stimulant_avoid_4,
        ),
    )

    SubstanceCategory.EMPATHOGEN -> CategoryGuide(
        whatsHappening = listOf(
            R.string.comedown_empathogen_happening_1,
            R.string.comedown_empathogen_happening_2,
            R.string.comedown_not_measured,
        ),
        rightNow = listOf(
            R.string.comedown_empathogen_now_1,
            R.string.comedown_empathogen_now_2,
            R.string.comedown_empathogen_now_3,
            R.string.comedown_empathogen_now_4,
        ),
        nextHours = listOf(
            R.string.comedown_empathogen_hours_1,
            R.string.comedown_empathogen_hours_2,
            R.string.comedown_empathogen_hours_3,
            R.string.comedown_empathogen_hours_4,
        ),
        avoid = listOf(
            R.string.comedown_empathogen_avoid_1,
            R.string.comedown_empathogen_avoid_2,
            R.string.comedown_empathogen_avoid_3,
            R.string.comedown_empathogen_avoid_4,
        ),
    )

    SubstanceCategory.PSYCHEDELIC -> CategoryGuide(
        whatsHappening = listOf(
            R.string.comedown_psychedelic_happening_1,
            R.string.comedown_psychedelic_happening_2,
            R.string.comedown_not_measured,
        ),
        rightNow = listOf(
            R.string.comedown_psychedelic_now_1,
            R.string.comedown_psychedelic_now_2,
            R.string.comedown_psychedelic_now_3,
            R.string.comedown_psychedelic_now_4,
        ),
        nextHours = listOf(
            R.string.comedown_psychedelic_hours_1,
            R.string.comedown_psychedelic_hours_2,
            R.string.comedown_psychedelic_hours_3,
            R.string.comedown_psychedelic_hours_4,
        ),
        avoid = listOf(
            R.string.comedown_psychedelic_avoid_1,
            R.string.comedown_psychedelic_avoid_2,
            R.string.comedown_psychedelic_avoid_3,
            R.string.comedown_psychedelic_avoid_4,
        ),
    )

    SubstanceCategory.DISSOCIATIVE -> CategoryGuide(
        whatsHappening = listOf(
            R.string.comedown_dissociative_happening_1,
            R.string.comedown_dissociative_happening_2,
            R.string.comedown_not_measured,
        ),
        rightNow = listOf(
            R.string.comedown_dissociative_now_1,
            R.string.comedown_dissociative_now_2,
            R.string.comedown_dissociative_now_3,
            R.string.comedown_dissociative_now_4,
        ),
        nextHours = listOf(
            R.string.comedown_rest_with_someone,
            R.string.comedown_dissociative_hours_1,
            R.string.comedown_dissociative_hours_2,
            R.string.comedown_dissociative_hours_3,
        ),
        avoid = listOf(
            R.string.comedown_dissociative_avoid_1,
            R.string.comedown_dissociative_avoid_2,
            R.string.comedown_dissociative_avoid_3,
            R.string.comedown_dissociative_avoid_4,
        ),
    )

    SubstanceCategory.OPIOID -> CategoryGuide(
        whatsHappening = listOf(
            R.string.comedown_opioid_happening_1,
            R.string.comedown_opioid_happening_2,
            R.string.comedown_not_measured,
        ),
        rightNow = listOf(
            R.string.comedown_opioid_now_1,
            R.string.comedown_opioid_now_2,
            R.string.comedown_opioid_now_3,
            R.string.comedown_opioid_now_4,
        ),
        nextHours = listOf(
            R.string.comedown_opioid_hours_1,
            R.string.comedown_opioid_hours_2,
            R.string.comedown_opioid_hours_3,
            R.string.comedown_opioid_hours_4,
        ),
        avoid = listOf(
            R.string.comedown_opioid_avoid_1,
            R.string.comedown_opioid_avoid_2,
            R.string.comedown_opioid_avoid_3,
            R.string.comedown_drive_warning,
        ),
    )

    SubstanceCategory.BENZODIAZEPINE -> CategoryGuide(
        whatsHappening = listOf(
            R.string.comedown_benzodiazepine_happening_1,
            R.string.comedown_benzodiazepine_happening_2,
            R.string.comedown_benzodiazepine_happening_3,
        ),
        rightNow = listOf(
            R.string.comedown_benzodiazepine_now_1,
            R.string.comedown_benzodiazepine_now_2,
            R.string.comedown_benzodiazepine_now_3,
            R.string.comedown_benzodiazepine_now_4,
        ),
        nextHours = listOf(
            R.string.comedown_benzodiazepine_hours_1,
            R.string.comedown_benzodiazepine_hours_2,
            R.string.comedown_benzodiazepine_now_5,
            R.string.comedown_benzodiazepine_hours_3,
        ),
        avoid = listOf(
            R.string.comedown_benzodiazepine_avoid_1,
            R.string.comedown_benzodiazepine_avoid_2,
            R.string.comedown_benzodiazepine_avoid_3,
            R.string.comedown_benzodiazepine_avoid_4,
        ),
    )

    SubstanceCategory.DEPRESSANT -> CategoryGuide(
        whatsHappening = listOf(
            R.string.comedown_depressant_happening_1,
            R.string.comedown_depressant_happening_2,
            R.string.comedown_not_measured,
        ),
        rightNow = listOf(
            R.string.comedown_depressant_now_1,
            R.string.comedown_sip_electrolytes,
            R.string.comedown_depressant_now_2,
            R.string.comedown_depressant_now_3,
        ),
        nextHours = listOf(
            R.string.comedown_depressant_hours_1,
            R.string.comedown_depressant_hours_2,
            R.string.comedown_depressant_hours_3,
            R.string.comedown_depressant_hours_4,
        ),
        avoid = listOf(
            R.string.comedown_depressant_avoid_1,
            R.string.comedown_depressant_avoid_2,
            R.string.comedown_depressant_avoid_3,
            R.string.comedown_depressant_avoid_4,
            R.string.comedown_drive_warning,
            R.string.comedown_depressant_avoid_5,
        ),
    )

    SubstanceCategory.CANNABINOID -> CategoryGuide(
        whatsHappening = listOf(
            R.string.comedown_cannabinoid_happening_1,
            R.string.comedown_cannabinoid_happening_2,
            R.string.comedown_not_measured,
        ),
        rightNow = listOf(
            R.string.comedown_cannabinoid_now_1,
            R.string.comedown_cannabinoid_now_2,
            R.string.comedown_cannabinoid_now_3,
            R.string.comedown_cannabinoid_now_4,
        ),
        nextHours = listOf(
            R.string.comedown_cannabinoid_hours_1,
            R.string.comedown_cannabinoid_hours_2,
            R.string.comedown_cannabinoid_hours_3,
            R.string.comedown_cannabinoid_hours_4,
        ),
        avoid = listOf(
            R.string.comedown_cannabinoid_avoid_1,
            R.string.comedown_cannabinoid_avoid_2,
            R.string.comedown_cannabinoid_avoid_3,
            R.string.comedown_cannabinoid_avoid_4,
        ),
    )

    else -> CategoryGuide(
        whatsHappening = listOf(
            R.string.comedown_other_happening_1,
            R.string.comedown_other_happening_2,
        ),
        rightNow = listOf(
            R.string.comedown_other_now_1,
            R.string.comedown_other_now_2,
            R.string.comedown_other_now_3,
        ),
        nextHours = listOf(
            R.string.comedown_rest_with_someone,
            R.string.comedown_other_hours_1,
            R.string.comedown_other_hours_2,
        ),
        avoid = listOf(
            R.string.comedown_other_avoid_1,
            R.string.comedown_other_avoid_2,
            R.string.comedown_drive_warning,
        ),
    )
}

/**
 * One class's fold-open row: the class, then its four groups of tips.
 *
 * Collapsed on arrival, including in the recent list — the original's
 * `DisclosureGroup` owns its own state and starts closed in both places, and
 * eight open groups is a screen nobody can scan.
 */
@Composable
private fun CategoryDisclosure(category: SubstanceCategory) {
    var expanded by remember(category.wireValue) { mutableStateOf(false) }
    val tint = SubstanceColorGenerator
        .displayP3(category, category.wireValue)
        .toComposeColor()

    PiruCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(tint, CircleShape),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(CoreLabels.category(category), style = MaterialTheme.typography.bodyLarge)
            }
            if (expanded) {
                val guidance = guide(category)
                TipGroup(stringResource(R.string.comedown_group_happening), guidance.whatsHappening)
                TipGroup(stringResource(R.string.comedown_group_now), guidance.rightNow)
                TipGroup(stringResource(R.string.comedown_group_hours), guidance.nextHours)
                TipGroup(stringResource(R.string.comedown_group_avoid), guidance.avoid)
            }
        }
    }
}

/** One titled group of bullets. */
@Composable
private fun TipGroup(title: String, items: List<Int>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        for (item in items) BulletText(stringResource(item))
    }
}

/** A bulleted paragraph, the form both guides' bodies use. */
@Composable
private fun BulletText(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "•",
            style = MaterialTheme.typography.bodySmall,
            color = PiruTheme.colors.secondaryLabel,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = PiruTheme.colors.secondaryLabel,
        )
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = modifier)
}

// MARK: - Get Help

/**
 * The emergency screen: the numbers, what is still active, and a plain-text
 * summary to hand to someone else.
 *
 * ## The region table is the screen
 * Everything above and below it is a courtesy; the table is why somebody opened
 * this. It carries a row per country for ~60 regions and is deliberately
 * over-long — a person reading it is not in a state to look up which of two
 * numbers applies to them, and the wrong one is worse than an extra row. The
 * region is the device's, so a traveller gets the country they are standing in.
 *
 * ## Two things are computed once, not per frame
 * `ActiveSubstanceCalculator` does a catalog lookup per dose, so it runs in the
 * effect and not in composition — the original says so in a comment, and the
 * `refreshToken` it keys on is this screen's [AppNavigator.dataVersion].
 */
@Composable
fun HelpScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val uriHandler = LocalUriHandler.current

    var catalog by remember { mutableStateOf<SubstanceCatalog?>(null) }
    var recent by remember { mutableStateOf<List<DoseEntryEntity>>(emptyList()) }
    var active by remember { mutableStateOf<List<ActiveSubstance>>(emptyList()) }
    var activeCategories by remember { mutableStateOf<List<SubstanceCategory>>(emptyList()) }
    var tints by remember { mutableStateOf<Map<String, P3Color>>(emptyMap()) }
    var hasShareable by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(navigator.dataVersion) {
        val opened = app.catalog()
        val all = app.database.doseEntryDao().all().sortedByDescending { it.timestamp.toInstant() }
        val records = all.mapNotNull { it.toDoseRecord() }
        val palette = app.palette().tintsFor(records.map { it.substance }.toSet())
        catalog = opened
        recent = all
        tints = palette
        active = ActiveSubstanceCalculator.compute(
            entries = records,
            colorMap = palette,
            catalog = opened,
            fallbackTint = P3Color.NEUTRAL,
        )
        activeCategories = recentGuidedCategories(all, Instant.now(), opened)
        // Only a dose the checker still counts as active is worth handing to
        // someone else; a summary of everything ever logged is not what the
        // share sheet is for.
        hasShareable = opened.interactionChecker.activeEntries(records).isNotEmpty()
    }

    // The stringResource(R.string.help_copied) flash, cleared on a timer rather than left set: a button that
    // says Copied forever stops telling the user anything.
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }

    val last24h = recent.filter {
        it.timestamp.toInstant().isAfter(Instant.now().minus(Duration.ofHours(24)))
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.help_title), style = MaterialTheme.typography.headlineSmall)
            }
        }

        // Reassurance
        item {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.help_alone_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        stringResource(R.string.help_alone_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiruTheme.colors.secondaryLabel,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        stringResource(R.string.help_alone_breathe),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiruTheme.colors.secondaryLabel,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        item {
            // The detail tracks whether a psychedelic is active, because the
            // advice is different while one is: familiar music is a grounding
            // tool rather than a comfort.
            GroundingTip(
                title = stringResource(R.string.help_music_title),
                detail = if (SubstanceCategory.PSYCHEDELIC in activeCategories) {
                    stringResource(R.string.help_music_detail_psychedelic)
                } else {
                    stringResource(R.string.help_music_detail)
                },
            )
        }
        item {
            GroundingTip(
                title = stringResource(R.string.help_friend_title),
                detail = stringResource(R.string.help_friend_detail),
            )
        }

        // Emergency services
        item {
            val region = Locale.getDefault().country.ifEmpty { "US" }
            // The `Locale(String, String)` constructor is deprecated; the builder
            // is the supported way to ask for a country's display name.
            val regionName = Locale.Builder().setRegion(region).build().getDisplayCountry()
            SectionLabel(
                if (regionName.isNotEmpty()) {
                    stringResource(R.string.help_emergency_heading, regionName)
                } else {
                    stringResource(R.string.help_service_emergency_services)
                },
                Modifier.padding(top = 8.dp),
            )
        }
        items(servicesFor(Locale.getDefault().country), key = { it.url + it.title }) { service ->
            val uri = service.url
            PiruCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { runCatching { uriHandler.openUri(uri) } },
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(service.title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // A row is either a number to dial — data, shown as logged —
                    // or an instruction, which is copy and so a resource.
                    Text(
                        service.detailRes?.let { stringResource(it) } ?: service.number,
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }

        // Modelled as active
        if (active.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.help_modeled_active), Modifier.padding(top = 8.dp)) }
            items(active, key = { it.id }) { substance ->
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(substance.tint.toComposeColor(), CircleShape),
                        )
                        Column {
                            Text(substance.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(
                                    R.string.help_active_total,
                                    doseFormatted(substance.totalDosed),
                                    substance.unit,
                                    ((1 - substance.eliminatedFraction) * 100).toInt(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = PiruTheme.colors.secondaryLabel,
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.help_modeled_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        // Recovery, right now
        if (activeCategories.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.help_recovery_now), Modifier.padding(top = 8.dp)) }
            items(activeCategories, key = { "recovery-" + it.wireValue }) { category ->
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(
                                        SubstanceColorGenerator
                                            .displayP3(category, category.wireValue)
                                            .toComposeColor(),
                                        CircleShape,
                                    ),
                            )
                            Text(CoreLabels.category(category), style = MaterialTheme.typography.titleSmall)
                        }
                        for (tip in guide(category).rightNow) BulletText(stringResource(tip))
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = { navigator.push(PushRoute.Tool(PushRoute.ToolKind.COMEDOWN)) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.help_view_full_guide))
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.help_recovery_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        // Recent entries
        if (last24h.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.help_recent_entries), Modifier.padding(top = 8.dp)) }
            items(last24h, key = { it.rowId }) { entry ->
                RecentEntryRow(entry, tints[entry.substance.lowercase()] ?: P3Color.NEUTRAL)
            }
        }

        // Copy / share
        if (active.isNotEmpty() || last24h.isNotEmpty()) {
            item {
                Button(
                    onClick = {
                        copyToClipboard(context, summaryText(context, active, last24h, catalog))
                        copied = true
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(if (copied) stringResource(R.string.help_copied) else stringResource(R.string.help_copy_summary))
                }
            }
            if (hasShareable) {
                item {
                    OutlinedButton(
                        onClick = { shareText(context, summaryText(context, active, last24h, catalog)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.help_share_state))
                    }
                }
            }
            item {
                // The disclaimer stays English — see the note in IdentifyScreen.
                Text(
                    stringResource(R.string.help_share_note) + " Not medical advice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun GroundingTip(title: String, detail: String) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
    }
}

@Composable
private fun RecentEntryRow(entry: DoseEntryEntity, tint: P3Color) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(40.dp)
                    .background(tint.toComposeColor(), RoundedCornerShape(2.dp)),
            )
            Column {
                Text(
                    entry.displayNameSnapshot ?: entry.substance,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${entry.amountDisplay} ${unitDisplay(entry.unit, entry.amount)} · " +
                        CoreLabels.route(entry.route),
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    TIME_FORMATTER.format(entry.timestamp.toInstant()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    relativeTime(entry.timestamp.toInstant()),
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.tertiaryLabel,
                )
            }
        }
    }
}

/**
 * The emergency numbers for a region, falling back to the single generic row.
 *
 * The device's region code, as upstream reads it. A region the table does not
 * carry gets the generic 112 row rather than nothing — the one outcome that
 * must not happen here is an empty screen.
 */
private fun servicesFor(regionCode: String): List<EmergencyService> =
    EMERGENCY_SERVICES[regionCode.uppercase()] ?: EMERGENCY_SERVICES.getValue("__default")

/**
 * One emergency contact row.
 *
 * [number] is what the row dials and is data — it is the same digits in every
 * language. [detailRes] is set instead when the line is an instruction rather
 * than a number, which is copy and belongs in the string table.
 */
private data class EmergencyService(
    @StringRes val title: Int,
    val number: String,
    val url: String,
    @StringRes val detailRes: Int? = null,
)

/**
 * The emergency table, region code to rows.
 *
 * Ported whole from `HelpView.services`. The URLs are what the platform dials,
 * with one deliberate change: the source writes its text-message links as
 * `sms:741741&body=HOME`, and Android's `SmsUri` reads the body after a `?`
 * rather than an `&`. The numbers and the bodies are unchanged; only the
 * separator is, and without it the link would dial a phone number of
 * "741741&body=HOME".
 */
private val EMERGENCY_SERVICES: Map<String, List<EmergencyService>> = buildMap {
    fun region(codes: String, vararg services: EmergencyService) {
        val rows = services.toList()
        for (code in codes.split(",")) put(code.trim(), rows)
    }

    // Americas
    region(
        "US",
        EmergencyService(R.string.help_service_emergency_services, "911", "tel:911"),
        EmergencyService(R.string.help_service_suicide_crisis_lifeline, "988", "tel:988"),
        EmergencyService(R.string.help_service_poison_control, "1-800-222-1222", "tel:18002221222"),
        EmergencyService(R.string.help_service_samhsa, "1-800-662-4357", "tel:18006624357"),
        EmergencyService(
            R.string.help_service_crisis_text_line,
            "",
            "sms:741741?body=HOME",
            R.string.help_sms_home_us,
        ),
    )
    region(
        "CA",
        EmergencyService(R.string.help_service_emergency_services, "911", "tel:911"),
        EmergencyService(R.string.help_service_suicide_crisis_helpline, "988", "tel:988"),
        EmergencyService(R.string.help_service_poison_centre, "1-844-767-8187", "tel:18447678187"),
        EmergencyService(
            R.string.help_service_crisis_text_line,
            "",
            "sms:686868?body=HOME",
            R.string.help_sms_home_ca,
        ),
    )
    region(
        "CO",
        EmergencyService(R.string.help_service_linea_emergencias, "123", "tel:123"),
        EmergencyService(R.string.help_service_linea_crisis, "106", "tel:106"),
    )
    region(
        "MX",
        EmergencyService(R.string.help_service_servicios_emergencia, "911", "tel:911"),
        EmergencyService(R.string.help_service_linea_de_la_vida, "800-911-2000", "tel:8009112000"),
    )
    region(
        "BR",
        EmergencyService(R.string.help_service_samu, "192", "tel:192"),
        EmergencyService(R.string.help_service_cvv, "188", "tel:188"),
    )
    region(
        "AR",
        EmergencyService(R.string.help_service_emergencias, "107", "tel:107"),
        EmergencyService(R.string.help_service_centro_asistencia_suicida, "135", "tel:135"),
    )
    region(
        "CL",
        EmergencyService(R.string.help_service_ambulancia, "131", "tel:131"),
        EmergencyService(R.string.help_service_salud_responde, "600 360 7777", "tel:6003607777"),
    )
    region(
        "PE",
        EmergencyService(R.string.help_service_samu, "106", "tel:106"),
        EmergencyService(R.string.help_service_linea_113_salud, "113", "tel:113"),
    )
    region("EC", EmergencyService(R.string.help_service_emergencias_ecu, "911", "tel:911"))
    region("VE", EmergencyService(R.string.help_service_emergencias, "171", "tel:171"))
    region(
        "UY",
        EmergencyService(R.string.help_service_emergencias, "911", "tel:911"),
        EmergencyService(R.string.help_service_linea_prevencion_suicidio, "0800 8483", "tel:08008483"),
    )
    region("CR", EmergencyService(R.string.help_service_emergencias, "911", "tel:911"))
    region("PA,HN,SV,DO", EmergencyService(R.string.help_service_emergencias, "911", "tel:911"))

    // Europe
    region(
        "GB",
        EmergencyService(R.string.help_service_emergency_services, "999", "tel:999"),
        EmergencyService(R.string.help_service_samaritans, "116 123", "tel:116123"),
        EmergencyService(R.string.help_service_frank, "0300 123 6600", "tel:03001236600"),
    )
    region(
        "IE",
        EmergencyService(R.string.help_service_emergency_services, "112 / 999", "tel:112"),
        EmergencyService(R.string.help_service_samaritans, "116 123", "tel:116123"),
        EmergencyService(R.string.help_service_pieta_house, "1800 247 247", "tel:1800247247"),
    )
    region(
        "DE",
        EmergencyService(R.string.help_service_notruf, "112", "tel:112"),
        EmergencyService(R.string.help_service_telefonseelsorge, "0800 111 0 111", "tel:08001110111"),
        EmergencyService(R.string.help_service_giftnotruf, "030 19240", "tel:03019240"),
    )
    region(
        "AT",
        EmergencyService(R.string.help_service_notruf, "144", "tel:144"),
        EmergencyService(R.string.help_service_telefonseelsorge, "142", "tel:142"),
    )
    region(
        "CH",
        EmergencyService(R.string.help_service_sanitatsnotruf, "144", "tel:144"),
        EmergencyService(R.string.help_service_dargebotene_hand, "143", "tel:143"),
        EmergencyService(R.string.help_service_tox_info_suisse, "145", "tel:145"),
    )
    region(
        "FR",
        EmergencyService(R.string.help_service_samu, "15", "tel:15"),
        EmergencyService(R.string.help_service_sos_amitie, "09 72 39 40 50", "tel:0972394050"),
        EmergencyService(R.string.help_service_centre_antipoison, "01 40 05 48 48", "tel:0140054848"),
    )
    region(
        "ES",
        EmergencyService(R.string.help_service_emergencias, "112", "tel:112"),
        EmergencyService(R.string.help_service_telefono_esperanza, "717 003 717", "tel:717003717"),
    )
    region(
        "PT",
        EmergencyService(R.string.help_service_emergencias, "112", "tel:112"),
        EmergencyService(R.string.help_service_sos_voz_amiga, "213 544 545", "tel:213544545"),
    )
    region(
        "IT",
        EmergencyService(R.string.help_service_emergenze, "112", "tel:112"),
        EmergencyService(R.string.help_service_telefono_amico, "02 2327 2327", "tel:0223272327"),
        EmergencyService(R.string.help_service_centro_antiveleni, "02 6610 1029", "tel:0266101029"),
    )
    region(
        "NL",
        EmergencyService(R.string.help_service_alarmnummer, "112", "tel:112"),
        EmergencyService(R.string.help_service_zelfmoordpreventie, "0900 0113", "tel:09000113"),
    )
    region(
        "BE",
        EmergencyService(R.string.help_service_urgences, "112", "tel:112"),
        EmergencyService(R.string.help_service_centre_antipoisons, "070 245 245", "tel:070245245"),
    )
    region(
        "SE",
        EmergencyService(R.string.help_service_nodnummer, "112", "tel:112"),
        EmergencyService(R.string.help_service_mind_sjalvmordslinjen, "90101", "tel:90101"),
    )
    region(
        "NO",
        EmergencyService(R.string.help_service_nodnummer, "113", "tel:113"),
        EmergencyService(R.string.help_service_mental_helse, "116 123", "tel:116123"),
        EmergencyService(R.string.help_service_giftinformasjonen, "22 59 13 00", "tel:22591300"),
    )
    region(
        "DK",
        EmergencyService(R.string.help_service_nodnummer, "112", "tel:112"),
        EmergencyService(R.string.help_service_livslinien, "70 201 201", "tel:70201201"),
    )
    region(
        "FI",
        EmergencyService(R.string.help_service_hatanumero, "112", "tel:112"),
        EmergencyService(R.string.help_service_kriisipuhelin, "09 2525 0111", "tel:0925250111"),
    )
    region(
        "PL",
        EmergencyService(R.string.help_service_numer_alarmowy, "112", "tel:112"),
        EmergencyService(R.string.help_service_telefon_zaufania, "116 123", "tel:116123"),
    )
    region(
        "CZ",
        EmergencyService(R.string.help_service_tisnovka, "112", "tel:112"),
        EmergencyService(R.string.help_service_linka_bezpeci, "116 111", "tel:116111"),
    )
    region(
        "GR",
        EmergencyService(R.string.help_service_ekab, "166", "tel:166"),
        EmergencyService(R.string.help_service_klimaka, "1018", "tel:1018"),
    )
    region("RO,HU,HR,BG,SK,SI,LT,LV,EE,CY,LU,MT", EmergencyService(R.string.help_service_emergency, "112", "tel:112"))

    // Asia and Oceania
    region(
        "AU",
        EmergencyService(R.string.help_service_emergency_services, "000", "tel:000"),
        EmergencyService(R.string.help_service_lifeline, "13 11 14", "tel:131114"),
        EmergencyService(R.string.help_service_poisons_information, "13 11 26", "tel:131126"),
    )
    region(
        "NZ",
        EmergencyService(R.string.help_service_emergency_services, "111", "tel:111"),
        EmergencyService(R.string.help_service_lifeline, "0800 543 354", "tel:0800543354"),
        EmergencyService(R.string.help_service_poison_centre, "0800 764 766", "tel:0800764766"),
    )
    region(
        "JP",
        EmergencyService(R.string.help_service_emergency_ambulance, "119", "tel:119"),
        EmergencyService(R.string.help_service_yorisoi, "0120-279-338", "tel:0120279338"),
    )
    region(
        "KR",
        EmergencyService(R.string.help_service_emergency_ambulance, "119", "tel:119"),
        EmergencyService(R.string.help_service_suicide_prevention_hotline, "1393", "tel:1393"),
    )
    region(
        "CN",
        EmergencyService(R.string.help_service_emergency_ambulance, "120", "tel:120"),
        EmergencyService(R.string.help_service_crisis_hotline, "010-8295-1332", "tel:01082951332"),
    )
    region(
        "IN",
        EmergencyService(R.string.help_service_emergency_services, "112", "tel:112"),
        EmergencyService(R.string.help_service_vandrevala, "9999 666 555", "tel:9999666555"),
    )
    region(
        "PH",
        EmergencyService(R.string.help_service_emergency_services, "911", "tel:911"),
        EmergencyService(R.string.help_service_crisis_line, "0917-899-8727", "tel:09178998727"),
    )
    region(
        "SG",
        EmergencyService(R.string.help_service_emergency_ambulance, "995", "tel:995"),
        EmergencyService(R.string.help_service_samaritans_singapore, "1-767", "tel:1767"),
    )
    region(
        "MY",
        EmergencyService(R.string.help_service_emergency_services, "999", "tel:999"),
        EmergencyService(R.string.help_service_befrienders, "03-7956 8145", "tel:0379568145"),
    )
    region(
        "TH",
        EmergencyService(R.string.help_service_emergency_ambulance, "1669", "tel:1669"),
        EmergencyService(R.string.help_service_samaritans_thailand, "02-713-6793", "tel:027136793"),
    )
    region("ID", EmergencyService(R.string.help_service_emergency_ambulance, "118", "tel:118"))
    region(
        "TW",
        EmergencyService(R.string.help_service_emergency_ambulance, "119", "tel:119"),
        EmergencyService(R.string.help_service_suicide_prevention, "1925", "tel:1925"),
    )
    region(
        "HK",
        EmergencyService(R.string.help_service_emergency_services, "999", "tel:999"),
        EmergencyService(R.string.help_service_samaritans, "2389 2222", "tel:23892222"),
    )

    // Middle East and Africa
    region(
        "IL",
        EmergencyService(R.string.help_service_emergency_ambulance, "101", "tel:101"),
        EmergencyService(R.string.help_service_eran, "1201", "tel:1201"),
    )
    region(
        "TR",
        EmergencyService(R.string.help_service_acil_yardim, "112", "tel:112"),
        EmergencyService(R.string.help_service_intihar_onleme, "182", "tel:182"),
    )
    region("AE", EmergencyService(R.string.help_service_emergency_ambulance, "998", "tel:998"))
    region("SA", EmergencyService(R.string.help_service_emergency_ambulance, "997", "tel:997"))
    region(
        "ZA",
        EmergencyService(R.string.help_service_emergency_ambulance, "10177", "tel:10177"),
        EmergencyService(R.string.help_service_sadag, "0800 567 567", "tel:0800567567"),
    )
    region(
        "KE",
        EmergencyService(R.string.help_service_emergency_services, "999", "tel:999"),
        EmergencyService(R.string.help_service_befrienders_kenya, "0722 178 177", "tel:0722178177"),
    )
    region("NG", EmergencyService(R.string.help_service_emergency_services, "112", "tel:112"))
    region("EG", EmergencyService(R.string.help_service_emergency_ambulance, "123", "tel:123"))
    region(
        "RU",
        EmergencyService(R.string.help_service_emergency_services, "112", "tel:112"),
        EmergencyService(R.string.help_service_psychological_help, "8-800-2000-122", "tel:88002000122"),
    )
    region(
        "UA",
        EmergencyService(R.string.help_service_emergency_ambulance, "103", "tel:103"),
        EmergencyService(R.string.help_service_lifeline_ukraine, "7333", "tel:7333"),
    )

    put("__default", listOf(EmergencyService(R.string.help_service_emergency_services, "112", "tel:112")))
}

/**
 * The plain-text hand-off, verbatim in shape from `HelpView.generateSummaryText`.
 *
 * A responder reads this, so the canonical name leads and the user's own word
 * follows in brackets when the two differ — a brand name or a non-English alias
 * is not something anyone can act on, and the user's spelling is what they will
 * recognise in their own log.
 */
private fun summaryText(
    context: Context,
    active: List<ActiveSubstance>,
    last24h: List<DoseEntryEntity>,
    catalog: SubstanceCatalog?,
): String {
    val lines = mutableListOf<String>()
    lines += context.getString(R.string.help_summary_heading)
    lines += context.getString(R.string.help_summary_generated, STAMP_FORMATTER.format(Instant.now()))
    lines += ""

    if (active.isNotEmpty()) {
        lines += context.getString(R.string.help_summary_active_heading)
        for (substance in active) {
            val remaining = ((1 - substance.eliminatedFraction) * 100).toInt()
            lines += context.getString(
                R.string.help_summary_active_row,
                substance.name,
                doseFormatted(substance.totalDosed),
                substance.unit,
                remaining,
            )
            for (dose in substance.doses) {
                lines += context.getString(
                    R.string.help_summary_active_dose,
                    doseFormatted(dose.amount),
                    substance.unit,
                    STAMP_FORMATTER.format(dose.timestamp),
                )
            }
        }
        lines += ""
    }

    if (last24h.isNotEmpty()) {
        lines += context.getString(R.string.help_summary_recent_heading)
        for (entry in last24h) {
            val canonical = catalog?.lookup(entry.substance)?.displayTitle ?: entry.substance
            val logged = entry.displayNameSnapshot ?: entry.substance
            val name = if (logged.equals(canonical, ignoreCase = true)) {
                canonical
            } else {
                context.getString(R.string.help_summary_logged_as, canonical, logged)
            }
            var line = context.getString(
                R.string.help_summary_recent_row,
                name,
                entry.amountDisplay,
                entry.unit,
                context.getString(CoreLabels.routeRes(entry.route)).lowercase(),
                STAMP_FORMATTER.format(entry.timestamp.toInstant()),
            )
            entry.notes?.takeIf { it.isNotEmpty() }?.let { line += " ($it)" }
            lines += line
        }
    }

    if (active.isEmpty() && last24h.isEmpty()) {
        lines += context.getString(R.string.help_summary_empty)
    }

    lines += ""
    lines += context.getString(R.string.help_summary_footer)
    return lines.joinToString("\n")
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Piru summary", text))
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(
        Intent.createChooser(send, context.getString(R.string.help_share_chooser)),
    )
}

/** A dose entry as the engine reads one, or null for a record with no number. */
private fun DoseEntryEntity.toDoseRecord(): DoseRecord? {
    if (isUnknownDose) return null
    return DoseRecord(
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

/** Clock time, and the absolute stamp the summary is dated with. */
private val TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

private val STAMP_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())

/** "3h ago" — the relative stamp the recent-entries list carries beside the clock time. */
@Composable
private fun relativeTime(instant: Instant): String {
    val minutes = Duration.between(instant, Instant.now()).toMinutes()
    return when {
        minutes < 1 -> stringResource(R.string.help_relative_just_now)
        minutes < 60 -> stringResource(R.string.help_relative_minutes, minutes)
        minutes < 60 * 24 -> stringResource(R.string.help_relative_hours, minutes / 60)
        else -> stringResource(R.string.help_relative_days, minutes / (60 * 24))
    }
}

// MARK: - Education

/**
 * The education index: the four learn-oriented screens, as a list.
 *
 * Ported from `Views/Tools/EducationCard.swift` (100 lines), which upstream
 * renders as a fold-open card on the hub. Reached as a route it is the same four
 * rows without the fold — a card that expands inside a screen whose whole
 * content is that card would be a fold with nothing around it.
 *
 * ## The ceiling row has no destination in this build
 * Three of the four push a screen that exists. stringResource(R.string.education_ceiling_title) does not: this
 * build has no route for it and no screen behind one. It is rendered inert and
 * says so, because a row that looked tappable and did nothing is the failure the
 * port's rules name — and the tolerance tool it sits beside does not cover it.
 */
@Composable
fun EducationCardsScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.education_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.education_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        item {
            EducationRow(
                title = stringResource(R.string.education_ceiling_title),
                subtitle = stringResource(R.string.education_ceiling_subtitle),
                enabled = false,
                detail = stringResource(R.string.education_ceiling_detail),
                onClick = {},
            )
        }
        item {
            EducationRow(
                title = stringResource(R.string.education_tolerance_title),
                subtitle = stringResource(R.string.education_tolerance_subtitle),
                enabled = true,
                detail = null,
                onClick = { navigator.push(PushRoute.Tool(PushRoute.ToolKind.TOLERANCE)) },
            )
        }
        item {
            EducationRow(
                title = stringResource(R.string.education_recovery_title),
                subtitle = stringResource(R.string.education_recovery_subtitle),
                enabled = true,
                detail = null,
                onClick = { navigator.push(PushRoute.Tool(PushRoute.ToolKind.COMEDOWN)) },
            )
        }
        item {
            EducationRow(
                title = stringResource(R.string.education_classes_title),
                subtitle = stringResource(R.string.education_classes_subtitle),
                enabled = true,
                detail = null,
                onClick = { navigator.push(PushRoute.Tool(PushRoute.ToolKind.DRUG_CLASS)) },
            )
        }

        item {
            // The disclaimer stays English — see the note in IdentifyScreen.
            Text(
                stringResource(R.string.education_footer) + " Not medical advice.",
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
        }
    }
}

/**
 * One education row.
 *
 * [enabled] false renders the row de-emphasised and inert with [detail] naming
 * what it would need — the same shape the tools hub uses for the entries it does
 * not carry.
 */
@Composable
private fun EducationRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    detail: String?,
    onClick: () -> Unit,
) {
    PiruCard(
        modifier = Modifier.fillMaxWidth(),
        // An inert row is not a button: `onClick` of null is what keeps it from
        // lifting on touch, which is the whole difference between "not built" and
        // "broken".
        onClick = if (enabled) onClick else null,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) PiruTheme.colors.accent else PiruTheme.colors.secondaryLabel,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
            )
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }
    }
}
