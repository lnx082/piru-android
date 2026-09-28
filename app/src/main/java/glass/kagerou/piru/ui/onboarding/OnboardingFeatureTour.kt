package glass.kagerou.piru.ui.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import glass.kagerou.piru.R
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme

/**
 * The feature tour: four faux-app screenshots rendered in Compose, each
 * captioned with what the tab does.
 *
 * Ported from `OnboardingFeatureTour.swift` (460 lines).
 *
 * ## The mocks are drawn, not captured, and none of them reads the store
 * Upstream builds its Journal and Library mocks out of the **real** components —
 * `TimelineGraphView`, `FamilyGradientCard`, `MoleculeView` — so the preview can
 * never go stale. This port draws a simplified stand-in instead, for the journal
 * page because the real `TimelineGraph` builds a curve model per substance and
 * framing a fixed 420-minute window through it costs more than a picture of one
 * is worth. **The proportions are the source's**, though: the same three
 * substances, the same timestamps relative to now, the same phase boundaries and
 * heights, so the picture shows what the app actually draws — three overlapping
 * curves at three different heights, on one day, with the now-line through them.
 *
 * ## What the page cannot show
 * `MoleculeView` — the structural formula art on the library cards — is not
 * ported, so the cards keep the gradient, the family icon, the count and the
 * sample names but wear a translucent disc where the molecule goes.
 *
 * ## The pages are browsable, not a gate
 * One "Continue" advances the whole flow; the pager is for looking.
 */
@Composable
fun OnboardingFeatureTour(nav: OnboardingNav) {
    val pages = FeatureTourPage.ALL
    val pagerState = rememberPagerState(pageCount = { pages.size })

    Column(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { index ->
            // Scrollable vertically at every size, not only at accessibility
            // sizes as the source does: the mock is a fixed 460dp tall and
            // Android's shortest phones are shorter than the phone it is a
            // picture of.
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                PhoneMock { TourMock(pages[index]) }
                Spacer(Modifier.height(16.dp))
                TourCaption(
                    title = stringResource(pages[index].title),
                    caption = stringResource(pages[index].caption),
                )
            }
        }

        PageDots(count = pages.size, current = pagerState.currentPage)

        OnboardingPillButton(
            title = stringResource(R.string.shell_continue),
            onClick = nav.advance,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp),
        )
    }
}

/** A page's title and caption, under its mock. */
@Composable
private fun TourCaption(title: String, caption: String) {
    val colors = PiruTheme.colors
    Column(
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            caption,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.secondaryLabel,
            textAlign = TextAlign.Center,
        )
    }
}

/** One dot per page, the current one filled. */
@Composable
private fun PageDots(count: Int, current: Int) {
    val colors = PiruTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (index in 0 until count) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.5.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (index == current) colors.accent else colors.accent.copy(alpha = 0.2f)),
            )
        }
    }
}

// MARK: - Page model

/**
 * One tour page: a title, a caption, and which mock goes in the phone frame.
 *
 * The mock is selected by [id] in [TourMock] rather than held as a
 * `@Composable` lambda in the value: the source stores an `AnyView` here, which
 * is the SwiftUI escape hatch for exactly this, and Compose's equivalent
 * (a `@Composable` function-typed property, invoked through the phone frame's
 * own scope) reads worse than one `when` at the single call site.
 */
private data class FeatureTourPage(
    val id: String,
    @StringRes val title: Int,
    @StringRes val caption: Int,
) {
    companion object {
        val ALL: List<FeatureTourPage> = listOf(
            FeatureTourPage(
                id = "journal",
                title = R.string.shell_tour_journal_title,
                caption = R.string.shell_tour_journal_caption,
            ),
            FeatureTourPage(
                id = "library",
                title = R.string.shell_tour_library_title,
                caption = R.string.shell_tour_library_caption,
            ),
            FeatureTourPage(
                id = "tools",
                title = R.string.shell_tour_tools_title,
                caption = R.string.shell_tour_tools_caption,
            ),
            FeatureTourPage(
                id = "insights",
                title = R.string.shell_tour_insights_title,
                caption = R.string.shell_tour_insights_caption,
            ),
        )
    }
}

/** The mock for a page, inside the phone frame's column. */
@Composable
private fun TourMock(page: FeatureTourPage) {
    when (page.id) {
        "journal" -> JournalMock()
        "library" -> LibraryMock()
        "tools" -> ToolsMock()
        "insights" -> InsightsMock()
    }
}

// MARK: - Phone frame

/**
 * A device-ish frame so each mock reads as a screenshot: rounded bezel, soft
 * shadow, a faux status bar, and a fixed 244 × 460 shape (taller than it is
 * wide) so every page is the same shape rather than a squat square.
 */
@Composable
private fun PhoneMock(content: @Composable ColumnScope.() -> Unit) {
    val colors = PiruTheme.colors
    val shape = RoundedCornerShape(36.dp)
    Column(
        modifier = Modifier
            .size(width = 244.dp, height = 460.dp)
            .shadow(12.dp, shape)
            .clip(shape)
            .background(colors.background)
            .border(1.dp, colors.secondaryLabel.copy(alpha = 0.15f), shape),
    ) {
        MockStatusBar()
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/**
 * The faux status bar: the source's "9:41" and its three indicators.
 *
 * `cellularbars`, `wifi` and `battery.75` have no counterpart in the core icon
 * set, and unlike a decorative glyph these three are the thing that makes a mock
 * read as a screenshot — so they are drawn instead, at the size they occupy in
 * the frame.
 */
@Composable
private fun MockStatusBar() {
    val colors = PiruTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "9:41",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (colors.isDark) Color.White else Color.Black,
        )
        Canvas(modifier = Modifier.size(width = 36.dp, height = 10.dp)) {
            val tint = Color.Gray
            // Signal: four bars, ascending.
            for (index in 0 until 4) {
                val barHeight = 3f + index * 2f
                drawRect(
                    color = tint,
                    topLeft = Offset(index * 3.5f, size.height - barHeight),
                    size = Size(2.2f, barHeight),
                )
            }
            // Wi-Fi: two nested arcs.
            val wifiCentre = Offset(20f, size.height - 1f)
            for (radius in listOf(6f, 3.5f)) {
                drawArc(
                    color = tint,
                    startAngle = 200f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(wifiCentre.x - radius, wifiCentre.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = 1.4f),
                )
            }
            // Battery: an outline with a nub.
            drawRoundRect(
                color = tint,
                topLeft = Offset(28f, 2f),
                size = Size(7f, size.height - 4f),
                cornerRadius = CornerRadius(1.5f),
                style = Stroke(width = 1f),
            )
            drawRoundRect(
                color = tint,
                topLeft = Offset(29.5f, 4f),
                size = Size(4f, size.height - 8f),
                cornerRadius = CornerRadius(1f),
            )
        }
    }
}

// MARK: - Mock palette

/** The source's `MockPalette`, for the journal legend. */
private object MockPalette {
    val pink = Color(red = 0.94f, green = 0.35f, blue = 0.55f)
    val blue = Color(red = 0.30f, green = 0.55f, blue = 0.95f)
    val green = Color(red = 0.30f, green = 0.72f, blue = 0.52f)
    val orange = Color(red = 0.96f, green = 0.62f, blue = 0.26f)
    val purple = Color(red = 0.60f, green = 0.45f, blue = 0.90f)
    val teal = Color(red = 0.18f, green = 0.66f, blue = 0.66f)
    val yellow = Color(red = 0.96f, green = 0.80f, blue = 0.25f)
}

@Composable
private fun MockTitle(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth(),
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
    )
}

// MARK: - Journal mock

/**
 * One substance on the mock timeline, with the source's own numbers.
 *
 * @param doseMinutes the dose's timestamp relative to now, negative for the past.
 * @param tachyphylaxis the acute-tolerance term, which shortens the descent —
 *   the felt effect returns to baseline before the plasma curve does.
 */
private data class MockSubstance(
    val name: String,
    val color: Color,
    val doseMinutes: Double,
    val comeupEnd: Double,
    val peakEnd: Double,
    val total: Double,
    val intensity: Double,
    val tachyphylaxis: Double = 0.0,
) {
    /** The last minute the curve is non-zero, in the same frame as [doseMinutes]. */
    val endMinutes: Double get() = doseMinutes + total
}

/**
 * The real Ibuprofen / Caffeine / Alcohol states the source builds, at the same
 * offsets from now. The tints are the substance identity colours the source
 * passes explicitly — not the legend palette, which is a separate thing and also
 * reproduced.
 */
private val mockSubstances = listOf(
    MockSubstance(
        name = "Ibuprofen",
        color = Color(red = 0.359f, green = 0.543f, blue = 0.921f),
        doseMinutes = -160.0,
        comeupEnd = 75.0,
        peakEnd = 120.0,
        total = 420.0,
        intensity = 0.55,
    ),
    MockSubstance(
        name = "Caffeine",
        color = Color(red = 0.873f, green = 0.389f, blue = 0.548f),
        doseMinutes = -80.0,
        comeupEnd = 45.0,
        peakEnd = 80.0,
        total = 300.0,
        intensity = 0.8,
    ),
    MockSubstance(
        name = "Alcohol",
        color = Color(red = 0.912f, green = 0.635f, blue = 0.332f),
        doseMinutes = -15.0,
        comeupEnd = 30.0,
        peakEnd = 55.0,
        total = 210.0,
        intensity = 0.7,
        tachyphylaxis = 0.3,
    ),
)

/**
 * The journal page: three overlapping curves on one day.
 *
 * The curve shape is a schematic rather than the engine's — a smooth rise to the
 * full-effect anchor, a crest, and a smooth landing at the substance's total
 * duration — but every one of those boundaries is the number the source passed
 * to the real renderer, so the picture keeps the thing the page is making a claim
 * about: which curve is tallest, which lands first, and how they overlap.
 */
@Composable
private fun JournalMock() {
    val colors = PiruTheme.colors
    val accent = colors.accent
    val secondary = colors.secondaryLabel
    val cardBackground = colors.cardBackground

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MockTitle(stringResource(R.string.shell_mock_journal))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(cardBackground)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.shell_mock_today), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendDot(MockPalette.pink)
                LegendDot(MockPalette.orange)
                LegendDot(MockPalette.blue)
                Text(
                    "Caffeine · Alcohol · Ibuprofen",
                    fontSize = 12.sp,
                    color = secondary,
                    maxLines = 1,
                )
            }

            Canvas(modifier = Modifier.fillMaxWidth().height(180.dp)) {
                // The window is the data's, not the clock's: from a little before
                // the earliest dose to a little after the last one fades.
                val startMinutes = mockSubstances.minOf { it.doseMinutes } - 10.0
                val endMinutes = mockSubstances.maxOf { it.endMinutes } + 10.0
                val span = endMinutes - startMinutes
                val baseline = size.height - 6f

                fun xFor(minutes: Double) = ((minutes - startMinutes) / span).toFloat() * size.width
                fun yFor(value: Double) = baseline - value.toFloat() * (baseline - 8f)

                drawLine(
                    color = secondary.copy(alpha = 0.25f),
                    start = Offset(0f, baseline),
                    end = Offset(size.width, baseline),
                    strokeWidth = 1f,
                )

                val samples = 120
                for (substance in mockSubstances) {
                    val path = Path()
                    var started = false
                    for (index in 0 until samples) {
                        val minutes = startMinutes + span * index / (samples - 1)
                        val value = mockAmplitude(minutes - substance.doseMinutes, substance) * substance.intensity
                        val x = xFor(minutes)
                        val y = yFor(value)
                        if (!started) {
                            path.moveTo(x, baseline)
                            path.lineTo(x, y)
                            started = true
                        } else {
                            path.lineTo(x, y)
                        }
                    }
                    path.lineTo(size.width, baseline)
                    drawPath(path, color = substance.color, style = Stroke(width = 2f))
                }

                // The now-line, which is the middle of the picture's story: the
                // doses are behind it and the curves are still running past it.
                val nowX = xFor(0.0)
                drawLine(
                    color = accent,
                    start = Offset(nowX, 0f),
                    end = Offset(nowX, baseline),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f)),
                )
            }
        }
    }
}

/**
 * The schematic curve: rise to the full-effect anchor, crest, land at the total
 * duration. Zero outside that span so the flat parts of the window stay flat.
 */
private fun mockAmplitude(minutesFromDose: Double, substance: MockSubstance): Double {
    if (minutesFromDose <= 0) return 0.0
    if (minutesFromDose >= substance.total) return 0.0
    return when {
        minutesFromDose < substance.comeupEnd ->
            smoothstep(minutesFromDose / substance.comeupEnd)
        minutesFromDose < substance.peakEnd -> 1.0
        else -> {
            // Acute tolerance lands the curve early rather than trailing off on
            // the elimination tail, so the descent is compressed by it.
            val descent = (substance.total - substance.peakEnd) * (1 - 0.5 * substance.tachyphylaxis)
            val fallEnd = substance.peakEnd + descent
            if (minutesFromDose >= fallEnd) 0.0
            else 1.0 - smoothstep((minutesFromDose - substance.peakEnd) / descent)
        }
    }
}

/** The usual 3t² − 2t³ ease, clamped. */
private fun smoothstep(t: Double): Double {
    val clamped = t.coerceIn(0.0, 1.0)
    return clamped * clamped * (3 - 2 * clamped)
}

@Composable
private fun LegendDot(color: Color) {
    Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(color))
}

// MARK: - Library mock

private data class MockFamily(
    val color: Color,
    val icon: ImageVector,
    @StringRes val title: Int,
    val samples: String,
    val count: String,
)

private val mockFamilies = listOf(
    MockFamily(
        color = Color(red = 0.28f, green = 0.46f, blue = 0.74f),
        icon = Icons.Filled.Star,
        title = R.string.shell_family_common,
        samples = "Caffeine · Alcohol · Nicotine",
        count = "20",
    ),
    MockFamily(
        color = Color(red = 0.98f, green = 0.58f, blue = 0.10f),
        // `bolt.fill` has no core counterpart; a plain up-arrow marks the family
        // without the app appearing to flag it.
        icon = Icons.Filled.KeyboardArrowUp,
        title = R.string.shell_family_stimulants,
        samples = "Amphetamine · Methylphenidate · Modafinil",
        count = "237",
    ),
    MockFamily(
        color = MockPalette.pink,
        icon = Icons.Filled.Favorite,
        title = R.string.shell_family_empathogens,
        samples = "MDMA · Mephedrone · 3-MMC",
        count = "68",
    ),
    MockFamily(
        color = MockPalette.green,
        icon = Icons.AutoMirrored.Filled.List,
        title = R.string.shell_family_cannabinoids,
        samples = "Cannabis · HHC · Delta-8",
        count = "34",
    ),
)

/**
 * The library page: the family cards, with their gradient, count and samples.
 *
 * A translucent disc stands where `MoleculeView` puts the structure art — the
 * cards keep their layout, and the gap is visible rather than filled with
 * something that is not a molecule.
 */
@Composable
private fun LibraryMock() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MockTitle(stringResource(R.string.shell_mock_library))
        for (family in mockFamilies) {
            val shape = RoundedCornerShape(18.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .clip(shape)
                    .background(
                        Brush.linearGradient(
                            listOf(family.color.copy(alpha = 0.85f), family.color),
                        ),
                    ),
            ) {
                // The molecule stand-in, at the card's trailing edge.
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 6.dp)
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.12f)),
                )
                Column(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            family.icon,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(family.count, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(family.title),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        family.samples,
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// MARK: - Tools mock

private data class MockTool(val icon: ImageVector, @StringRes val label: Int, val color: Color)

private val mockTools = listOf(
    MockTool(Icons.Filled.Warning, R.string.shell_tool_interactions, MockPalette.pink),
    MockTool(Icons.Filled.Refresh, R.string.shell_tool_tolerance, MockPalette.blue),
    MockTool(Icons.AutoMirrored.Filled.List, R.string.shell_tool_inventory, MockPalette.purple),
    MockTool(Icons.Filled.DateRange, R.string.shell_tool_half_life, MockPalette.green),
    MockTool(Icons.Filled.Create, R.string.shell_tool_solutions, MockPalette.orange),
    MockTool(Icons.Filled.Add, R.string.shell_tool_recovery, MockPalette.teal),
)

/** The tools page: six tiles in a two-column grid. */
@Composable
private fun ToolsMock() {
    val colors = PiruTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MockTitle(stringResource(R.string.shell_mock_tools))
        for (row in mockTools.chunked(2)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (tool in row) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.cardBackground)
                            .padding(vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            tool.icon,
                            contentDescription = null,
                            tint = tool.color,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(stringResource(tool.label), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                // A lone tile keeps its half-width rather than stretching.
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// MARK: - Insights mock

private data class MockBar(@StringRes val label: Int, val count: Int, val color: Color)

private val mockBars = listOf(
    MockBar(R.string.shell_mock_morning, 66, MockPalette.orange),
    MockBar(R.string.shell_mock_afternoon, 27, MockPalette.yellow),
    MockBar(R.string.shell_mock_evening, 69, MockPalette.purple),
    MockBar(R.string.shell_mock_night, 6, MockPalette.blue),
)

/** The insights page: the time-of-day bars and the entries-per-day card. */
@Composable
private fun InsightsMock() {
    val colors = PiruTheme.colors
    val secondary = colors.secondaryLabel
    val maxCount = mockBars.maxOf { it.count }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MockTitle(stringResource(R.string.shell_mock_insights))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.cardBackground)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.shell_mock_time_of_day),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = secondary,
            )
            Row(
                modifier = Modifier.fillMaxWidth().height(184.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                for (bar in mockBars) {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("${bar.count}", fontSize = 10.sp, color = secondary)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((150 * bar.count / maxCount).dp.coerceAtLeast(6.dp))
                                .clip(RoundedCornerShape(5.dp))
                                .background(bar.color),
                        )
                        Text(
                            stringResource(bar.label),
                            fontSize = 8.5.sp,
                            color = secondary,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        PiruCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = colors.accent, modifier = Modifier.size(12.dp))
                Text(
                    stringResource(R.string.shell_mock_insights_summary),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
