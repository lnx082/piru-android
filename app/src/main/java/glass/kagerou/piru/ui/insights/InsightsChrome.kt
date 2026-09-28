package glass.kagerou.piru.ui.insights

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.R
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.SubstanceCategory
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.labels.CoreLabels
import glass.kagerou.piru.ui.theme.PiruTheme

/**
 * The shared chrome the Insights sections are built from, ported from
 * `Views/Insights/Usage/UsageSectionChrome.swift` (254 lines) and
 * `InsightsFilterMenu.swift`.
 *
 * ## The chart window constant
 * The time-series charts show a fixed-width slice rather than the whole range,
 * so a year does not crush into a few pixels per week. 120 days is upstream's
 * window (`usageChartWindowSeconds`) and the reason is stated there: wide enough
 * to read a season of shape at once, short enough that the newest detail is not
 * lost. The port has no pinch gesture on top of it — see the screens' own notes.
 *
 * ## Colours come from three places, and none of them is Material
 * The skin's tokens (`PiruTheme.colors`) for everything structural, the
 * generated substance palette for anything that identifies a substance, and the
 * asset tables below for the dose ladder, the substance classes and the routes.
 * The last three are the iOS asset catalog's `display-p3` values, copied across
 * so a dose tier is the same colour on both platforms — a ladder whose bands
 * changed hue between the two builds would be a different ladder to read.
 */

/**
 * Roughly a quarter of a year: the visible window for every scrollable time
 * chart on these screens.
 */
internal const val USAGE_CHART_WINDOW_SECONDS: Double = 120 * 86_400.0

// MARK: - Cards

/**
 * The standard Insights section card: a headline, an optional one-line
 * explanation of what the section answers, and its content.
 */
@Composable
internal fun InsightsSectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InsightsCardHeader(title, subtitle)
            content()
        }
    }
}

/**
 * A section that remembers whether it is open.
 *
 * The secondary insights collapse — expanded on first visit, and the choice
 * persists (upstream keeps it in `@AppStorage`; here it survives configuration
 * changes but not a process death, which is the honest limit of a screen with no
 * settings store behind it yet).
 */
@Composable
internal fun InsightsCollapsibleCard(
    title: String,
    subtitle: String? = null,
    storageKey: String,
    defaultExpanded: Boolean = true,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(storageKey) { mutableStateOf(defaultExpanded) }
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    InsightsCardHeader(title, subtitle)
                }
                DisclosureTriangle(expanded)
            }
            if (expanded) {
                Spacer(modifier = Modifier.size(10.dp))
                content()
            }
        }
    }
}

@Composable
private fun InsightsCardHeader(title: String, subtitle: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
    }
}

/**
 * The disclosure chevron, drawn rather than iconed.
 *
 * The port carries no SF Symbols, and a text glyph for "expanded" would be a
 * different shape in every font the user has installed.
 */
@Composable
private fun DisclosureTriangle(expanded: Boolean) {
    val ink = PiruTheme.colors.secondaryLabel
    Box(modifier = Modifier.size(16.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val mid = size.width / 2
            if (expanded) {
                // Pointing up: collapsed content is below.
                drawLine(ink, Offset(size.width * 0.2f, size.height * 0.62f), Offset(mid, size.height * 0.38f), 2f)
                drawLine(ink, Offset(mid, size.height * 0.38f), Offset(size.width * 0.8f, size.height * 0.62f), 2f)
            } else {
                drawLine(ink, Offset(size.width * 0.38f, size.height * 0.2f), Offset(size.width * 0.62f, mid), 2f)
                drawLine(ink, Offset(size.width * 0.62f, mid), Offset(size.width * 0.38f, size.height * 0.8f), 2f)
            }
        }
    }
}

/**
 * The empty state every Insights screen opens with when there is nothing to
 * read.
 *
 * An empty state is information, not an error: it says what would fill it, and
 * — where the screen that would fill it is not in this build — it says that too
 * rather than offering a button that goes nowhere.
 */
@Composable
internal fun InsightsEmptyPanel(title: String, detail: String) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = PiruTheme.colors.secondaryLabel)
        }
    }
}

// MARK: - Small parts

/** A dot in a substance's or a class's colour. */
@Composable
internal fun InsightsLegendDot(color: Color, size: Dp = 8.dp) {
    Box(modifier = Modifier.size(size).clip(CircleShape).background(color))
}

/** The interpunct the iOS copy sets between two secondary labels. */
@Composable
internal fun InsightsMiddot() {
    Text(
        "·",
        style = MaterialTheme.typography.bodySmall,
        color = PiruTheme.colors.secondaryLabel,
    )
}

/**
 * The outlined filter capsule the horizontal filter strips are drawn with — a
 * tint only while selected, over an outline that carries the category's or the
 * substance's own colour.
 *
 * Deliberately transparent when unselected: these strips scroll across a card's
 * own fill, so a filled plate would read as a second surface on top of it.
 */
@Composable
internal fun Modifier.usageFilterPill(isSelected: Boolean, color: Color): Modifier = this
    .padding(horizontal = 9.dp, vertical = 5.dp)
    .background(if (isSelected) color.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(50))
    .border(1.dp, if (isSelected) color else PiruTheme.colors.tertiaryLabel, RoundedCornerShape(50))

/** One filter pill: a colour dot, a label, and a tap that toggles it. */
@Composable
internal fun InsightsFilterPill(
    label: String,
    color: Color,
    isSelected: Boolean,
    showDot: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .usageFilterPill(isSelected, color)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (showDot) InsightsLegendDot(color, size = 6.dp)
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
    }
}

/** A left-aligned, horizontally scrolling strip of filter pills. */
@Composable
internal fun InsightsCategoryFilterBar(
    categories: List<UsageCategoryCount>,
    selection: Int?,
    onSelect: (Int?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InsightsFilterPill(
            label = stringResource(R.string.toolsb_insights_chrome_filter_all),
            color = PiruTheme.colors.accent,
            isSelected = selection == null,
            showDot = false,
            onClick = { onSelect(null) },
        )
        for (item in categories) {
            val category = UsageAxes.category(item.categoryIndex)
            InsightsFilterPill(
                label = CoreLabels.category(category),
                color = categoryAccent(category),
                isSelected = selection == item.categoryIndex,
                onClick = { onSelect(if (selection == item.categoryIndex) null else item.categoryIndex) },
            )
        }
    }
}

/** A wrapping row of chips — the legend layout both the trend and dose-level charts want. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InsightsChipFlow(
    spacing: Dp = 12.dp,
    content: @Composable FlowRowScope.() -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

// MARK: - Index to enum

/** Maps the plain indices the aggregation works in back to the model enums the UI draws with. */
internal object UsageAxes {

    fun category(index: Int): SubstanceCategory = SubstanceCategory.entries.getOrNull(index) ?: SubstanceCategory.OTHER

    fun route(index: Int): RouteOfAdministration =
        RouteOfAdministration.entries.getOrNull(index) ?: RouteOfAdministration.OTHER

    fun doseLevel(index: Int) = when (index) {
        0 -> glass.kagerou.piru.model.DoseLevel.SUB
        1 -> glass.kagerou.piru.model.DoseLevel.THRESHOLD
        2 -> glass.kagerou.piru.model.DoseLevel.LIGHT
        3 -> glass.kagerou.piru.model.DoseLevel.COMMON
        4 -> glass.kagerou.piru.model.DoseLevel.STRONG
        else -> glass.kagerou.piru.model.DoseLevel.HEAVY
    }

    /** Dose levels lightest to heaviest — the order the stacked chart draws them bottom-up. */
    val doseLevelOrder: List<Int> = listOf(0, 1, 2, 3, 4, 5)
}

// MARK: - The asset palette

/**
 * A colour from the iOS asset catalog's `display-p3` values.
 *
 * Written once rather than at each constant so the colour space cannot be
 * dropped by accident at one of them — handing sRGB components to a plain
 * `Color(red, green, blue)` renders the whole ladder visibly duller than the
 * build this is copying.
 */
private fun p3(red: Double, green: Double, blue: Double): Color =
    Color(red.toFloat(), green.toFloat(), blue.toFloat(), 1f, ColorSpaces.DisplayP3)

/**
 * One token's two appearances.
 *
 * Both are built at class-load time rather than per call: a `Color` is an
 * immutable value with no composition state, so constructing it once is both
 * correct and cheaper than rebuilding a Display P3 colour on every frame of a
 * chart.
 */
private class Pairing(val light: Color, val dark: Color) {
    fun of(isDark: Boolean): Color = if (isDark) dark else light
}

private fun pairing(
    lr: Double, lg: Double, lb: Double,
    dr: Double, dg: Double, db: Double,
) = Pairing(p3(lr, lg, lb), p3(dr, dg, db))

/**
 * The dose ladder's ramp, `Sub-threshold` through `Heavy`.
 *
 * The same colours the entry-detail indicator and the tier strip use, so a band
 * in the dose-level chart is the band the dose's own row is marked with.
 */
private val DOSE_ACCENTS: List<Pairing> = listOf(
    pairing(0.478, 0.559, 0.655, 0.315, 0.392, 0.482), // sub
    pairing(0.444, 0.542, 0.781, 0.289, 0.378, 0.605), // threshold
    pairing(0.565, 0.486, 0.887, 0.401, 0.313, 0.699), // light
    pairing(0.724, 0.419, 0.833, 0.545, 0.241, 0.648), // common
    pairing(0.870, 0.348, 0.634, 0.676, 0.146, 0.462), // strong
    pairing(0.872, 0.389, 0.298, 0.684, 0.208, 0.130), // heavy
)

/** A dose level's accent, by its index in the ladder. */
@Composable
internal fun doseLevelAccent(index: Int): Color {
    val dark = PiruTheme.colors.isDark
    return DOSE_ACCENTS.getOrElse(index) { DOSE_ACCENTS.last() }.of(dark)
}

private val CATEGORY_ACCENTS: Map<SubstanceCategory, Pairing> = mapOf(
    SubstanceCategory.AMPAKINE to pairing(0.396, 0.608, 0.276, 0.233, 0.434, 0.097),
    SubstanceCategory.ANALGESIC to pairing(0.621, 0.533, 0.408, 0.450, 0.367, 0.248),
    SubstanceCategory.ANTICONVULSANT to pairing(0.582, 0.504, 0.769, 0.415, 0.336, 0.588),
    SubstanceCategory.ANTIDEPRESSANT to pairing(0.661, 0.532, 0.015, 0.805, 0.649, 0.000),
    SubstanceCategory.ANTIHISTAMINE to pairing(0.698, 0.481, 0.562, 0.520, 0.317, 0.396),
    SubstanceCategory.ANTIMICROBIAL to pairing(0.329, 0.589, 0.640, 0.152, 0.419, 0.468),
    SubstanceCategory.ANTIPSYCHOTIC to pairing(0.171, 0.615, 0.592, 0.000, 0.530, 0.509),
    SubstanceCategory.BENZODIAZEPINE to pairing(0.244, 0.507, 0.997, 0.074, 0.348, 0.828),
    SubstanceCategory.CANNABINOID to pairing(0.264, 0.628, 0.277, 0.034, 0.452, 0.099),
    SubstanceCategory.CARDIOVASCULAR to pairing(0.880, 0.389, 0.343, 0.685, 0.201, 0.177),
    SubstanceCategory.DELIRIANT to pairing(0.584, 0.551, 0.417, 0.416, 0.384, 0.257),
    SubstanceCategory.DEPRESSANT to pairing(0.476, 0.549, 0.705, 0.314, 0.382, 0.528),
    SubstanceCategory.DISSOCIATIVE to pairing(0.267, 0.580, 0.783, 0.044, 0.407, 0.602),
    SubstanceCategory.DYSDELIC to pairing(0.709, 0.456, 0.683, 0.530, 0.289, 0.508),
    SubstanceCategory.EMPATHOGEN to pairing(0.944, 0.305, 0.378, 0.743, 0.020, 0.216),
    SubstanceCategory.ENDOCRINE to pairing(0.670, 0.455, 0.818, 0.496, 0.284, 0.634),
    SubstanceCategory.EUGEROIC to pairing(0.701, 0.514, 0.170, 0.596, 0.413, 0.000),
    SubstanceCategory.GABAPENTINOID to pairing(0.481, 0.494, 0.976, 0.324, 0.314, 0.782),
    SubstanceCategory.GASTROINTESTINAL to pairing(0.741, 0.498, 0.118, 0.684, 0.444, 0.002),
    SubstanceCategory.IMMUNOLOGICAL to pairing(0.315, 0.546, 0.931, 0.142, 0.368, 0.740),
    SubstanceCategory.NOOTROPIC to pairing(0.266, 0.594, 0.680, 0.034, 0.423, 0.506),
    SubstanceCategory.OPIOID to pairing(0.934, 0.324, 0.257, 0.740, 0.092, 0.065),
    SubstanceCategory.OREXIN_ANTAGONIST to pairing(0.542, 0.515, 0.799, 0.378, 0.347, 0.617),
    SubstanceCategory.OTHER to pairing(0.547, 0.547, 0.564, 0.381, 0.381, 0.397),
    SubstanceCategory.PEPTIDE to pairing(0.385, 0.568, 0.753, 0.220, 0.398, 0.574),
    SubstanceCategory.PSYCHEDELIC to pairing(0.700, 0.409, 0.904, 0.525, 0.224, 0.715),
    SubstanceCategory.RESPIRATORY to pairing(0.327, 0.580, 0.730, 0.150, 0.409, 0.552),
    SubstanceCategory.STIMULANT to pairing(0.791, 0.472, 0.020, 0.809, 0.481, 0.000),
    SubstanceCategory.SUPPLEMENT to pairing(0.306, 0.623, 0.348, 0.119, 0.449, 0.186),
)

/**
 * A substance class's accent.
 *
 * The port carries no `SubstanceCategory.color`; without one the class pills
 * would all be the skin accent and the filter bar would carry no information.
 * A class outside the table falls back to the neutral grey the catalog uses for
 * `Other`, which is the honest answer for a class this build does not know.
 */
@Composable
internal fun categoryAccent(category: SubstanceCategory): Color {
    val dark = PiruTheme.colors.isDark
    return (CATEGORY_ACCENTS[category] ?: CATEGORY_ACCENTS.getValue(SubstanceCategory.OTHER)).of(dark)
}

private val ROUTE_ACCENTS: Map<RouteOfAdministration, Pairing> = mapOf(
    RouteOfAdministration.ORAL to pairing(0.258, 0.536, 0.994, 0.049, 0.354, 0.799),
    RouteOfAdministration.SUBLINGUAL to pairing(0.266, 0.594, 0.680, 0.034, 0.423, 0.506),
    RouteOfAdministration.BUCCAL to pairing(0.171, 0.615, 0.592, 0.000, 0.530, 0.509),
    RouteOfAdministration.INSUFFLATION to pairing(0.700, 0.409, 0.904, 0.525, 0.224, 0.715),
    RouteOfAdministration.INHALATION to pairing(0.791, 0.472, 0.020, 0.809, 0.481, 0.000),
    RouteOfAdministration.INTRAVENOUS to pairing(0.934, 0.324, 0.257, 0.740, 0.092, 0.065),
    RouteOfAdministration.INTRAMUSCULAR to pairing(0.944, 0.305, 0.378, 0.743, 0.020, 0.216),
    RouteOfAdministration.SUBCUTANEOUS to pairing(0.476, 0.488, 0.997, 0.320, 0.305, 0.802),
    RouteOfAdministration.TRANSDERMAL to pairing(0.264, 0.628, 0.277, 0.034, 0.452, 0.099),
    RouteOfAdministration.RECTAL to pairing(0.621, 0.533, 0.408, 0.450, 0.367, 0.248),
    RouteOfAdministration.OTHER to pairing(0.547, 0.547, 0.564, 0.381, 0.381, 0.397),
)

/** A route's fixed tint, so every "oral" mark is the same colour whatever the substance is. */
@Composable
internal fun routeAccent(route: RouteOfAdministration): Color {
    val dark = PiruTheme.colors.isDark
    return (ROUTE_ACCENTS[route] ?: ROUTE_ACCENTS.getValue(RouteOfAdministration.OTHER)).of(dark)
}

/**
 * The colour a regularity tier is drawn in.
 *
 * The dose ladder's ramp, light through heavy, as the gaps get less even. It
 * encodes *evenness*, not virtue: a sporadic supplement and a sporadic
 * recreational dose read the same here.
 */
@Composable
internal fun regularityTierAccent(tier: UsageRegularityTier): Color = when (tier) {
    UsageRegularityTier.VERY_REGULAR -> doseLevelAccent(2)
    UsageRegularityTier.SOMEWHAT_REGULAR -> doseLevelAccent(3)
    UsageRegularityTier.IRREGULAR -> doseLevelAccent(4)
    UsageRegularityTier.SPORADIC -> doseLevelAccent(5)
}

/**
 * Where a window's doses sit on the ladder, as a three-step encoding.
 *
 * A description of the record, not a verdict on it — which is why the caption
 * beside it never reads "good" or "high".
 */
@Composable
internal fun intensityAccent(intensity: Double): Color = when {
    intensity < 0.25 -> doseLevelAccent(2)
    intensity < 0.5 -> doseLevelAccent(3)
    else -> doseLevelAccent(5)
}
