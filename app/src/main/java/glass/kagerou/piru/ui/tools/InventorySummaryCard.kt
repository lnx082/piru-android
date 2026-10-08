package glass.kagerou.piru.ui.tools

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.data.InventoryMath
import glass.kagerou.piru.data.entity.InventoryItemEntity
import glass.kagerou.piru.engine.SubstanceCatalog
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.ui.theme.toComposeColor
import glass.kagerou.piru.model.inventoryFormatted
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

/**
 * The inventory tool's shared parts, and its entry on the Tools hub.
 *
 * Ported from `Piru/Views/Tools/Inventory/InventorySupport.swift` (337 lines),
 * plus the `InventorySummaryCard` half of `InventoryListView.swift` (lines 5-79).
 *
 * ## Colour carries meaning only for Low and Out
 * A healthy supply is drawn in the label colour, not green. The governing rule
 * upstream, inherited from Apple's own guidance, is that a hue which is always
 * present carries no information: if every row were tinted, the tinted row would
 * stop standing out. So green is not in this file's vocabulary at all, and the
 * supply bar never takes the substance's own identity colour — identity already
 * has the row's dot, which is where an L3 colour belongs.
 *
 * ## What is not here
 * The `accessibilityDifferentiateWithoutColor` hatch that upstream overlays on a
 * non-healthy bar. Compose exposes no public equivalent of that environment
 * value, so the non-colour signal for a colour-blind reader is absent rather than
 * approximated by a guess at whether it should be on. Named here so it reads as a
 * gap with a reason instead of an oversight.
 */

// MARK: - Stock status

/**
 * The health of an item's stock.
 *
 * Declaration order is the iOS `StockStatus` order (`ok`, `low`, `out`) and is
 * **not** the attention order — that is [sortIndex], and it is the one the chips
 * and the rows rank by. Kotlin makes `compareTo` final, so the two must not be
 * confused for one another.
 */
enum class StockStatus(@StringRes val labelRes: Int) {
    OK(R.string.toolsb_inventory_status_in_stock),
    LOW(R.string.toolsb_inventory_status_low),
    OUT(R.string.toolsb_inventory_status_out),
    ;

    /**
     * Attention order: out, then low, then healthy.
     *
     * "In Stock" rather than "OK" for the facet label, because a filter reads as a
     * statement about the item, not as a grade.
     */
    val sortIndex: Int
        get() = when (this) {
            OUT -> 0
            LOW -> 1
            OK -> 2
        }

    /**
     * The glyph a filter row wears while the status is unselected — the selected
     * row shows a checkmark instead.
     *
     * Drawn from the framework's core icon set: the build does not carry
     * `material-icons-extended`, so `exclamationmark.circle` becomes a bare
     * warning triangle and `xmark.circle` a bare cross. The substitution is
     * visible rather than silent, and it is a mark either way.
     */
    val icon: ImageVector
        get() = when (this) {
            OK -> Icons.Filled.CheckCircle
            LOW -> Icons.Filled.Warning
            OUT -> Icons.Filled.Clear
        }
}

/**
 * The number's colour. Neutral when healthy, so colour is reserved for the
 * states that need attention.
 */
val StockStatus.numberColor: Color
    @Composable @ReadOnlyComposable
    get() = when (this) {
        StockStatus.OK -> MaterialTheme.colorScheme.onSurface
        StockStatus.LOW -> PiruTheme.colors.cautionText
        StockStatus.OUT -> PiruTheme.colors.dangerText
    }

/** The supply bar's fill. The only tint the bar ever takes — see the file note. */
val StockStatus.barTint: Color
    @Composable @ReadOnlyComposable
    get() = when (this) {
        StockStatus.OK -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        StockStatus.LOW -> PiruTheme.colors.caution
        StockStatus.OUT -> PiruTheme.colors.danger
    }

// MARK: - Derived item state

/**
 * Healthy, low or out, from the cached quantity and the threshold.
 *
 * Null, zero and negative thresholds all mean "no warning" — a single comparison
 * rather than three, because all three arrive from a form field the user cleared.
 */
val InventoryItemEntity.stockStatus: StockStatus
    get() = when {
        currentQuantity <= 0 -> StockStatus.OUT
        lowStockThreshold != null && lowStockThreshold!! > 0 &&
            currentQuantity <= lowStockThreshold!! -> StockStatus.LOW
        else -> StockStatus.OK
    }

/** A baseline is set only when it is positive; `null` and `0` both disable the bar. */
val InventoryItemEntity.hasBaseline: Boolean
    get() = (baselineQuantity ?: 0.0) > 0.0

/** How full the supply is, clamped to `0..1`. Null when no baseline is set. */
val InventoryItemEntity.fillFraction: Double?
    get() = baselineQuantity?.takeIf { it > 0 }?.let { min(1.0, max(0.0, currentQuantity / it)) }

/** Sort priority: out first, then low, then healthy. */
val InventoryItemEntity.sortPriority: Int
    get() = sortStatusPriority(stockStatus)

/**
 * The most recent manual activity, falling back to creation — what orders two
 * items of equal status by "recently used".
 */
val InventoryItemEntity.lastActivity: java.time.Instant
    get() = manualEvents.maxOfOrNull { it.date } ?: createdAt

private fun sortStatusPriority(status: StockStatus): Int = when (status) {
    StockStatus.OUT -> 0
    StockStatus.LOW -> 1
    StockStatus.OK -> 2
}

/**
 * The substance with its salt inline, so variants do not read as duplicates.
 *
 * The stored [InventoryItemEntity.substance] is the canonical name — that is what
 * makes doses match — while display resolves it through the catalog to the
 * presentation name the journal and the quick log show. A substance the catalog
 * does not carry falls back to the stored spelling.
 */
fun InventoryItemEntity.displayTitle(catalog: SubstanceCatalog?): String {
    val resolved = catalog?.lookup(substance)?.displayTitle ?: substance
    val salt = saltForm
    return if (!salt.isNullOrEmpty()) "$resolved · $salt" else resolved
}

// MARK: - Supply copy

/**
 * The one-line supply summary shown under the amount.
 *
 * Always surfaces the doses-left estimate when one can be made — explicit or from
 * the catalog's reference dose — and appends the run-out duration only when the
 * rolling-average guard in [InventoryMath.runOut] has already passed. So:
 * `"~24 doses · ~3 weeks left"`, `"~24 doses left"`, `"~3 weeks left"`, or null
 * when nothing can be estimated at all.
 *
 * Takes [dosesLeft] rather than the item, unlike the Swift original: deriving it
 * needs the catalog and the whole row, and every caller here already has both.
 */
fun inventorySupplyLine(context: Context, dosesLeft: Int?, runOut: InventoryMath.RunOut?): String? {
    if (runOut != null) {
        val humanized = inventoryHumanizeDays(context, runOut.daysLeft)
        return if (dosesLeft != null) {
            context.getString(R.string.toolsb_inventory_doses_and_runout, dosesLeft, humanized)
        } else {
            context.getString(R.string.toolsb_inventory_runout_left, humanized)
        }
    }
    return dosesLeft?.let { context.getString(R.string.toolsb_inventory_doses_left, it) }
}

/** Days left, humanized: under a fortnight in days, under two months in weeks, else months. */
fun inventoryHumanizeDays(context: Context, days: Double): String = when {
    days < 14 -> context.getString(R.string.toolsb_inventory_approx_days, round(days).toInt())
    days < 60 -> context.getString(R.string.toolsb_inventory_approx_weeks, round(days / 7).toInt())
    else -> context.getString(R.string.toolsb_inventory_approx_months, round(days / 30).toInt())
}

/** `nil` for a non-positive value; the value otherwise. The "0 = off" convention, in one place. */
fun normalizedPositive(value: Double?): Double? = value?.takeIf { it > 0 }

/**
 * The engine's `P3Color` as a Compose colour.
 *
 * An identical `internal` extension already exists in `ui/journal/TimelineGraph.kt`.
 * The right fix is for both to move into `ui/components`; copying rather than
 * importing across features is the smaller wrong, because it keeps the journal's
 * internals out of the tools package's import list.
 */

// MARK: - Supply bar

/**
 * A thin status-tinted supply bar, drawn only when an item has a baseline.
 *
 * That gate is the whole reason the bar exists: without a baseline there is no
 * "full" to measure against, and a bar that guessed one would be inventing a
 * number the user never gave.
 *
 * The fill is never narrower than 3dp. A supply at 2% would otherwise render as
 * an empty track, which reads as "out" — the one thing it is not.
 */
@Composable
fun InventorySupplyBar(
    fraction: Double,
    tint: Color,
    modifier: Modifier = Modifier,
    thickness: Dp = 6.dp,
    status: StockStatus? = null,
) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    BoxWithConstraints(modifier = modifier.fillMaxWidth().height(thickness)) {
        val clamped = fraction.coerceIn(0.0, 1.0)
        val width = maxOf(3.dp, maxWidth * clamped.toFloat())
        Box(Modifier.fillMaxSize().clip(CircleShape).background(track)) {
            Box(Modifier.width(width).fillMaxHeight().clip(CircleShape).background(tint))
        }
    }
}

// MARK: - Amount stepper

/**
 * A "nice" step increment, about 10% of the current value snapped to a
 * 1 / 2.5 / 5 x 10^k series.
 *
 * The same heuristic the quick log's dose stepper uses, so a 200,000 IU stock
 * nudges by 25,000 while a 5 g bag nudges by 0.5 — a stepper that moved by a
 * fixed amount would be useless at one end of that range and unusable at the
 * other.
 */
object InventoryStep {
    fun nice(value: Double): Double {
        val basis = max(abs(value), 1.0)
        val raw = basis / 10
        val magnitude = 10.0.pow(floor(log10(raw)))
        val normalized = raw / magnitude
        val snapped = when {
            normalized < 1.75 -> 1.0
            normalized < 3.75 -> 2.5
            normalized < 7.5 -> 5.0
            else -> 10.0
        }
        return snapped * magnitude
    }
}

/**
 * The amount editor used across the inventory forms: two neutral circular step
 * buttons flanking a centered field with a trailing unit.
 *
 * The value is a real text field, not a read-only readout, so an exact figure can
 * be typed instead of nudged to; the field only stores what parses, and leaves a
 * half-typed "1." alone rather than rewriting it under the cursor.
 *
 * @param stepBasis a fixed dose-anchored increment — the substance's reference
 *   dose — so caffeine nudges in ~5 mg regardless of the current value. Null for
 *   an off-library substance or a still-empty field, which falls back to a
 *   value-relative step.
 * @param unitChoices when non-null the unit becomes a menu; when null it is plain
 *   text, which is what a restock wants (the item's unit is already fixed).
 */
@Composable
fun InventoryStepperRow(
    value: Double,
    onValueChange: (Double) -> Unit,
    unit: String,
    label: String,
    modifier: Modifier = Modifier,
    stepBasis: Double? = null,
    unitChoices: List<String>? = null,
    onUnitChange: ((String) -> Unit)? = null,
    focusOnAppear: Boolean = false,
) {
    val step = if (stepBasis != null && stepBasis > 0) InventoryStep.nice(stepBasis) else InventoryStep.nice(value)
    val focusRequester = remember { FocusRequester() }

    // The field's own text, kept because a number mid-typing is not a number:
    // "1." and "" are both real states the user passes through, and parsing each
    // keystroke into the value would delete them as they were written.
    var text by remember { mutableStateOf(stepperText(value)) }
    LaunchedEffect(value) {
        if (text.trim().toDoubleOrNull() != value) text = stepperText(value)
    }
    LaunchedEffect(Unit) { if (focusOnAppear) focusRequester.requestFocus() }

    fun bump(target: Double) {
        // Snap to the step grid, so ten taps of "plus" land on 100 and not on
        // 99.99999999999999.
        onValueChange(round(target / step) * step)
    }

    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton("−", stringResource(R.string.toolsb_inventory_decrease_label, label)) { bump(max(0.0, value - step)) }

        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { raw ->
                    text = raw
                    raw.trim().toDoubleOrNull()?.let(onValueChange)
                },
                modifier = Modifier.widthIn(min = 32.dp).focusRequester(focusRequester),
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Spacer(Modifier.width(5.dp))
            UnitLabel(unit = unit, choices = unitChoices, onUnitChange = onUnitChange, label = label)
        }

        StepButton("+", stringResource(R.string.toolsb_inventory_increase_label, label)) { bump(value + step) }
    }
}

@Composable
private fun StepButton(glyph: String, description: String, action: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(PiruTheme.colors.inputBackground)
            .clickable(onClick = action, onClickLabel = description),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** The unit, as a menu when the form has not fixed one yet and plain text when it has. */
@Composable
private fun UnitLabel(unit: String, choices: List<String>?, onUnitChange: ((String) -> Unit)?, label: String) {
    if (choices == null || onUnitChange == null) {
        Text(
            unit,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = PiruTheme.colors.secondaryLabel,
        )
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { open = true }
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                unit,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = PiruTheme.colors.secondaryLabel,
            )
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = stringResource(R.string.toolsb_inventory_unit_label, label),
                tint = PiruTheme.colors.secondaryLabel,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (choice in choices) {
                DropdownMenuItem(
                    text = { Text(choice) },
                    onClick = {
                        onUnitChange(choice)
                        open = false
                    },
                    leadingIcon = if (choice == unit) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/**
 * A stock figure as the field holds it while typing: integral values lose the
 * decimal point, so the field says "100" and not "100.0".
 *
 * Deliberately not [inventoryFormatted]: that groups thousands and rounds by
 * magnitude, both of which are wrong for a field the user is about to edit —
 * a grouped "50,000" does not parse back, and a rounded one silently changes the
 * number when the field is read.
 */
private fun stepperText(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

// MARK: - Amount text

/**
 * The trailing stock number with a dimmed unit, or a coloured "Out".
 *
 * Coloured only for Low and Out, per the rule the whole feature follows.
 */
@Composable
fun StockAmountText(item: InventoryItemEntity, modifier: Modifier = Modifier) {
    val status = item.stockStatus
    if (status == StockStatus.OUT) {
        Text(
            stringResource(R.string.toolsb_inventory_status_out),
            modifier = modifier,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = status.numberColor,
        )
        return
    }
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        Text(
            inventoryFormatted(item.currentQuantity),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = status.numberColor,
        )
        Spacer(Modifier.width(3.dp))
        Text(
            item.unit,
            style = MaterialTheme.typography.bodySmall,
            color = PiruTheme.colors.secondaryLabel,
        )
    }
}

// MARK: - Tools hub card

/**
 * The Inventory entry on the Tools tab: a card showing the first three items in
 * the manager's own order.
 *
 * The same model instance the manager uses, so a sort or a class arrangement
 * chosen there reorders this card too — the card is a window onto that screen,
 * and the two agreeing on "first" is the point.
 *
 * ## Rows wait for the catalog
 * The arrangement resolves a substance class per item, and a cold catalog would
 * fall through to a bulk read on the composition thread — the Tools tab's
 * cold-launch stall. Until it is warm the card shows its subtitle, which is one
 * frame in the warm case.
 *
 * @param onOpen what tapping the card does. Null renders an inert card: the hub
 *   owns the route, and an inert card that looks tappable is worse than one that
 *   does not.
 */
@Composable
fun InventorySummaryCard(
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val model = remember { InventoryListModel.shared(context) }
    val items by remember { app.database.inventoryDao().observeAll() }.collectAsState(emptyList())

    var catalog by remember { mutableStateOf<SubstanceCatalog?>(null) }
    var tints by remember { mutableStateOf<Map<String, P3Color>>(emptyMap()) }

    val names = remember(items) { items.map { it.substance }.distinct() }
    // `runCatching`, because a summary card is not worth taking a screen down for.
    //
    // `InventoryListModel.ordered` needs the catalogue, so without one the card draws its empty
    // state rather than its rows — the honest answer for "nothing to summarise", and the right
    // failure for a read that could not run. It is also what keeps this card renderable without
    // a catalogue, and therefore keeps `ToolsScreen` a pure screen: the hub specs assert it
    // draws, and their harness has no catalogue to give it.
    LaunchedEffect(Unit) { catalog = runCatching { app.catalog() }.getOrNull() }
    LaunchedEffect(names) {
        tints = runCatching { app.palette().tintsFor(names) }.getOrDefault(emptyMap())
    }

    // Ordered once per pass rather than as three separate reads of a computed
    // property — emptiness, rows and the overflow count would each re-sort the
    // inventory otherwise, which is exactly what the Swift version's header warns
    // about.
    val ordered = catalog?.let { model.ordered(items, it) }.orEmpty()
    val topItems = ordered.take(3)

    PiruCard(modifier = modifier.fillMaxWidth(), onClick = onOpen) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.toolsb_inventory_title), style = MaterialTheme.typography.titleSmall)
            if (topItems.isEmpty()) {
                Text(
                    stringResource(R.string.toolsb_inventory_card_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            } else {
                for (item in topItems) {
                    InventorySummaryRow(
                        item = item,
                        tint = tints[item.substance.lowercase()],
                        catalog = catalog,
                    )
                }
                if (items.size > topItems.size) {
                    Text(
                        stringResource(R.string.toolsb_inventory_more_count, items.size - topItems.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }
    }
}

/**
 * One image-style row inside the hub card: dot and name, the plain number, and a
 * bar below when a baseline exists.
 */
@Composable
private fun InventorySummaryRow(item: InventoryItemEntity, tint: P3Color?, catalog: SubstanceCatalog?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background((tint ?: P3Color.NEUTRAL).toComposeColor()),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                item.displayTitle(catalog),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            StockAmountText(item)
        }
        item.fillFraction?.let { fraction ->
            InventorySupplyBar(
                fraction = fraction,
                tint = item.stockStatus.barTint,
                status = item.stockStatus,
                modifier = Modifier.padding(start = 19.dp),
            )
        }
    }
}

/** A text style for a caption line under a value. Kept here so the three files agree. */
internal val captionSecondary: TextStyle
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.typography.bodySmall.copy(color = PiruTheme.colors.secondaryLabel)
