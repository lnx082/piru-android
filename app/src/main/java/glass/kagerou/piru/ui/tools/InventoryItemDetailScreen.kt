package glass.kagerou.piru.ui.tools

import android.content.Context
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.widget.MedWidgetRefresh
import glass.kagerou.piru.data.InventoryMath
import glass.kagerou.piru.data.ManualEvent
import glass.kagerou.piru.data.PiruDatabase
import glass.kagerou.piru.data.SubstanceColorStore
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.data.entity.InventoryItemEntity
import glass.kagerou.piru.engine.SubstanceCatalog
import glass.kagerou.piru.model.Substance
import glass.kagerou.piru.model.inventoryFormatted
import glass.kagerou.piru.substance.DbSubstanceCatalog
import glass.kagerou.piru.substance.SubstanceMatch
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.labels.appLocale
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.nav.PushRoute
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlinx.coroutines.launch

/**
 * One tracked item: its amount, its run-out estimate, its history, and the three
 * forms that write to it.
 *
 * Ported from `InventoryItemDetailView.swift` (348 lines),
 * `InventoryItemForm.swift` (428), `InventoryItemEditView.swift` (164), and the
 * write paths of `InventoryService.swift` (625).
 *
 * ## Everything here is a replay, so every write is small
 * There is no running total in the database. A restock appends an event, a
 * correction appends a signed adjustment, and the quantity is the sum of the
 * events minus the doses — see `InventoryMath.replayQuantity`. That is why
 * [InventoryStore] can be a short list of appends rather than a transactional
 * service, and why a stock level that has gone wrong is always recoverable: the
 * inputs are all still there.
 *
 * ## The low-stock decision is a pure function, and the alert is not wired
 * [lowStockAlert] answers "should the alert fire, and what should the flag become
 * after this replay" without touching a notification. Posting one needs a
 * notification channel, a permission surface and a deep link back to this item —
 * a separate line of work — and the decision is the part that has to agree with
 * the flag's own persistence, so it is the part that lives here.
 */

// MARK: - Prefill

/**
 * What a scanned box stated, for the add form to open with.
 *
 * Ported from `InventoryPrefill` (`Piru/Navigation/Routes.swift:288`). Not a route
 * field in this build — the scanner is not ported — but the form's draft takes it
 * so the shape is already right when the scanner lands.
 *
 * @param count a pack count, which opens the count x strength shape.
 * @param unit the unit the box printed. A count noun ("tabs") opens the pieces
 *   shape; anything else ("30 mL") is a plain amount.
 * @param strengthMG the per-piece strength, when the box printed one.
 */
data class InventoryPrefill(
    val count: Double? = null,
    val unit: String? = null,
    val strengthMG: Double? = null,
    val note: String? = null,
)

// MARK: - Store

/**
 * The write paths for a tracked supply.
 *
 * Ported from `InventoryService`'s mutations. Reached through the DAO rather than
 * through a service object because that is what the port has: the entity is a
 * Room row, the history is a JSON column on it, and the quantity is derived. So
 * each function here is "append the event, replay, write the row back".
 *
 * ## Why [recompute] rewrites the whole row
 * Upstream's `recompute` writes only the two derived columns, because its replay
 * can run off the main actor against a copy of the item while the user edits the
 * real one. This port has no off-main replay pass, so the in-memory row is always
 * the freshest copy of itself and the split would only be ceremony.
 */
object InventoryStore {

    /**
     * Should the low-stock alert fire, and what should [InventoryItemEntity.lowStockNotified]
     * become after this replay?
     *
     * ## The flag is a latch, cleared by the thing that raised it
     * It is set when the alert fires and stays set until the quantity climbs back
     * above the threshold — so a shortage alerts once, not once per replay, and a
     * restock that crosses the line re-arms it. That re-arming is the whole reason
     * the flag is a column rather than a comparison: without it, "below the
     * threshold" would be a state and the user would be told about it forever.
     */
    fun lowStockAlert(item: InventoryItemEntity, quantity: Double): LowStockAlert {
        val threshold = item.lowStockThreshold
        val out = quantity <= 0
        if (threshold == null || threshold <= 0) return LowStockAlert(notified = false, shouldNotify = false, isOut = out)
        if (quantity > threshold) return LowStockAlert(notified = false, shouldNotify = false, isOut = out)
        if (item.lowStockNotified) return LowStockAlert(notified = true, shouldNotify = false, isOut = out)
        return LowStockAlert(notified = true, shouldNotify = true, isOut = out)
    }

    /** One replay's answer: the flag's new value, whether to alert, and whether the supply is empty. */
    data class LowStockAlert(val notified: Boolean, val shouldNotify: Boolean, val isOut: Boolean)

    /** The logged doses since [since], bucketed by identity — one fetch for a whole list. */
    suspend fun dosesByMatchKey(
        db: PiruDatabase,
        since: Instant,
        catalog: SubstanceCatalog,
    ): Map<String, List<DoseEntryEntity>> = InventoryMath.bucketDoses(
        db.doseEntryDao().all().filter { it.timestamp.toInstant() >= since },
        catalog,
    )

    /** The replayed quantity for [item], without writing anything. */
    suspend fun quantity(db: PiruDatabase, catalog: SubstanceCatalog, item: InventoryItemEntity): Double {
        val buckets = dosesByMatchKey(db, item.trackingStart, catalog)
        return InventoryMath.quantity(item, buckets, catalog)
    }

    /**
     * Re-derive the cached quantity and the alert latch, then write the row.
     *
     * The [LowStockAlert.shouldNotify] answer is deliberately discarded here — see
     * the file note. Whoever wires notifications reads it from a call of their own.
     */
    suspend fun recompute(db: PiruDatabase, catalog: SubstanceCatalog, item: InventoryItemEntity): InventoryItemEntity {
        val quantity = quantity(db, catalog, item)
        val alert = lowStockAlert(item, quantity)
        val updated = item.copy(currentQuantity = quantity, lowStockNotified = alert.notified)
        db.inventoryDao().upsert(updated)
        return updated
    }

    /**
     * Start tracking a substance, or add to the item already tracking it.
     *
     * One identity per `(substance, salt)` is enforced **here**, at the write, so a
     * second box of the same thing is a restock of the first rather than a second
     * item — which is also why the entity has no unique index: the rule is a
     * behaviour, not a constraint.
     *
     * The stated amount is reconciled into the existing item's unit — mass family,
     * or a count against a mass through a known strength. When it cannot be
     * reconciled the item moves to the new unit and is recounted to the amount
     * just stated, because the stock on hand is what the user just told us.
     */
    suspend fun create(
        db: PiruDatabase,
        catalog: SubstanceCatalog,
        substance: String,
        saltForm: String?,
        unit: String,
        initial: Double,
        threshold: Double?,
        setBaseline: Boolean,
        note: String? = null,
        unitStrengthMG: Double? = null,
    ): InventoryItemEntity {
        val strength = if (InventoryMath.isCountUnit(unit)) normalizedPositive(unitStrengthMG) else null

        val existing = db.inventoryDao().byIdentity(substance, saltForm)
        if (existing != null) {
            var working = existing
            if (working.lowStockThreshold == null) {
                working = working.copy(lowStockThreshold = normalizedPositive(threshold))
            }
            if (working.unitStrengthMG == null && working.unit == unit) {
                working = working.copy(unitStrengthMG = strength)
            }

            val reconciled = InventoryMath.convert(
                amount = initial,
                from = unit,
                to = working.unit,
                unitStrengthMG = working.unitStrengthMG ?: normalizedPositive(unitStrengthMG),
            )
            if (reconciled == null) {
                var moved = changeUnit(db, catalog, working, unit)
                moved = moved.copy(unitStrengthMG = strength)
                moved = correctTo(db, catalog, moved, initial, note)
                if (setBaseline) {
                    moved = moved.copy(baselineQuantity = normalizedPositive(initial))
                    db.inventoryDao().upsert(moved)
                }
                return moved
            }
            if (reconciled != 0.0 || setBaseline) {
                return restock(db, catalog, working, reconciled, note, setBaseline)
            }
            db.inventoryDao().upsert(working)
            return working
        }

        val now = Instant.now()
        val events = if (initial != 0.0) {
            listOf(
                ManualEvent(
                    kind = ManualEvent.Kind.INITIAL,
                    amount = initial,
                    date = now,
                    note = note,
                    setsBaseline = setBaseline,
                ),
            )
        } else {
            emptyList()
        }
        val item = InventoryItemEntity(
            substance = substance,
            saltForm = saltForm,
            unit = unit,
            trackingStart = now,
            lowStockThreshold = normalizedPositive(threshold),
            unitStrengthMG = strength,
            createdAt = now,
            sortOrder = nextSortOrder(db),
        ).withManualEvents(events)

        var saved = recompute(db, catalog, item)
        if (setBaseline) {
            saved = saved.copy(baselineQuantity = normalizedPositive(saved.currentQuantity))
            db.inventoryDao().upsert(saved)
        }
        // Give the substance its colour row now, so a first-time-tracked
        // substance is editable in the colours list before its first logged dose.
        SubstanceColorStore(db, catalog).ensureRow(substance)
        return saved
    }

    /**
     * Add stock, as an event.
     *
     * With [setBaseline] the post-restock total becomes the new baseline and the
     * event records that it was the one that set it. A counted item that has no
     * strength yet takes it from the box being added.
     */
    suspend fun restock(
        db: PiruDatabase,
        catalog: SubstanceCatalog,
        item: InventoryItemEntity,
        amount: Double,
        note: String?,
        setBaseline: Boolean,
        unitStrengthMG: Double? = null,
    ): InventoryItemEntity {
        var working = item
        if (working.unitStrengthMG == null && InventoryMath.isCountUnit(working.unit)) {
            working = working.copy(unitStrengthMG = normalizedPositive(unitStrengthMG))
        }
        working = working.withManualEvents(
            working.manualEvents + ManualEvent(
                kind = ManualEvent.Kind.RESTOCK,
                amount = amount,
                date = Instant.now(),
                note = note,
                setsBaseline = setBaseline,
            ),
        )
        working = recompute(db, catalog, working)
        if (setBaseline) {
            working = working.copy(baselineQuantity = normalizedPositive(working.currentQuantity))
            db.inventoryDao().upsert(working)
        }
        return working
    }

    /**
     * Set the exact amount on hand, by appending the signed adjustment that lands
     * the replay on [exact].
     *
     * An event rather than an overwrite, so the recount is visible in the history
     * and the correction can be corrected again.
     */
    suspend fun correctTo(
        db: PiruDatabase,
        catalog: SubstanceCatalog,
        item: InventoryItemEntity,
        exact: Double,
        note: String?,
    ): InventoryItemEntity {
        val current = quantity(db, catalog, item)
        val delta = exact - current
        val working = item.withManualEvents(
            item.manualEvents + ManualEvent(
                kind = ManualEvent.Kind.ADJUSTMENT,
                amount = delta,
                date = Instant.now(),
                note = note,
            ),
        )
        return recompute(db, catalog, working)
    }

    /**
     * Change the item's base unit, converting everything denominated in it.
     *
     * The manual-event amounts are stored in the item's unit, so they convert too
     * — otherwise the replay would read old-unit numbers as new-unit ones, which
     * is a silent factor-of-a-thousand error rather than a visible one. When the
     * units are not convertible the raw event amounts are kept and the three
     * derived fields are cleared, which is the "the user re-sets it on the next
     * edit" intent.
     */
    suspend fun changeUnit(
        db: PiruDatabase,
        catalog: SubstanceCatalog,
        item: InventoryItemEntity,
        newUnit: String,
    ): InventoryItemEntity {
        val oldUnit = item.unit
        if (oldUnit == newUnit) return item

        val factor = InventoryMath.convert(1.0, oldUnit, newUnit, item.unitStrengthMG)
        var working = item
        working = if (factor != null) {
            working
                .withManualEvents(working.manualEvents.map { it.copy(amount = it.amount * factor) })
                .copy(
                    baselineQuantity = working.baselineQuantity?.let { it * factor },
                    lowStockThreshold = working.lowStockThreshold?.let { it * factor },
                    doseSize = working.doseSize?.let { it * factor },
                )
        } else {
            working.copy(baselineQuantity = null, lowStockThreshold = null, doseSize = null)
        }
        if (!InventoryMath.isCountUnit(newUnit)) working = working.copy(unitStrengthMG = null)
        working = working.copy(unit = newUnit)
        return recompute(db, catalog, working)
    }

    /** Stop tracking an item. The doses are untouched — inventory only derives from them. */
    suspend fun delete(db: PiruDatabase, item: InventoryItemEntity) {
        db.inventoryDao().delete(item)
    }

    /** Persist a manual order: the whole list renumbered `0..n`, so `sortOrder` is the single source of truth. */
    suspend fun reorder(db: PiruDatabase, ordered: List<InventoryItemEntity>) {
        for ((index, item) in ordered.withIndex()) {
            if (item.sortOrder != index) db.inventoryDao().upsert(item.copy(sortOrder = index))
        }
    }

    /** Drop one manual event from the history and replay. */
    suspend fun deleteEvent(
        db: PiruDatabase,
        catalog: SubstanceCatalog,
        item: InventoryItemEntity,
        eventId: UUID,
    ): InventoryItemEntity = recompute(
        db,
        catalog,
        item.withManualEvents(item.manualEvents.filterNot { it.id == eventId }),
    )

    /**
     * The `sortOrder` for a freshly tracked item.
     *
     * `0` while the list is still status-sorted — where every item is at zero — so
     * a new item joins the automatic arrangement; once the user has reordered by
     * hand, `max + 1` appends it to the bottom instead of colliding at the top.
     */
    private suspend fun nextSortOrder(db: PiruDatabase): Int {
        val max = db.inventoryDao().maxSortOrder() ?: return 0
        return if (max == 0) 0 else max + 1
    }
}

// MARK: - Amount draft

/**
 * The stock a form states, in one of two shapes: a plain amount in a unit
 * ("500 mg", "30 mL"), or a count of pieces at a strength ("28 x 36 mg") — the
 * shape a box prints and a scanner reads.
 *
 * Counted stock keeps the count as the item's own unit (`tabs`, `caps`) with the
 * strength alongside, so "28 tabs" stays 28 tabs rather than becoming a flat
 * 1,008 mg. That is the difference between a count the user can check against the
 * pack and a number they cannot.
 *
 * Ported from `InventoryAmountDraft`.
 */
@Stable
class InventoryAmountDraft(
    private val prefill: InventoryPrefill?,
    private val catalog: SubstanceCatalog,
    existingItem: InventoryItemEntity?,
) {
    enum class Mode { AMOUNT, PIECES }

    /** What the form commits: the stock in the unit it was stated in, with the per-piece strength when counted. */
    data class Stated(val amount: Double, val unit: String, val unitStrengthMG: Double?)

    var mode by mutableStateOf(Mode.AMOUNT)
    var amount by mutableStateOf(0.0)
    var unit by mutableStateOf("mg")
    var count by mutableStateOf(0.0)
    var countUnit by mutableStateOf("tabs")
    var strengthMG by mutableStateOf(0.0)

    /**
     * A restock opens on what was last bought — the most recent restock or initial
     * amount — falling back to about ten strong doses. A fresh add starts at zero
     * until a substance is picked, at which point [seed] fills it in.
     *
     * From a box: a pack count opens the pieces shape with the count and any
     * printed strength in place; a liquid ("30 mL") is a plain amount.
     */
    init {
        if (existingItem != null) {
            unit = existingItem.unit
            amount = lastBuy(existingItem)
        }
        val packCount = prefill?.count
        if (prefill != null && packCount != null && packCount > 0) {
            val packUnit = prefill.unit
            if (packUnit != null && !InventoryMath.isCountUnit(packUnit)) {
                amount = packCount
                unit = packUnit
            } else {
                // A counted item restocks in its own unit, whatever noun the box
                // used for its pieces.
                mode = Mode.PIECES
                count = packCount
                countUnit = if (existingItem != null && InventoryMath.isCountUnit(existingItem.unit)) {
                    existingItem.unit
                } else {
                    packUnit ?: "tabs"
                }
                strengthMG = prefill.strengthMG ?: existingItem?.unitStrengthMG ?: 0.0
            }
        }
    }

    val stated: Stated
        get() = when (mode) {
            Mode.AMOUNT -> Stated(amount = amount, unit = unit, unitStrengthMG = null)
            Mode.PIECES -> Stated(
                amount = count,
                unit = countUnit,
                unitStrengthMG = if (strengthMG > 0) strengthMG else null,
            )
        }

    /**
     * The stated stock reconciled into [item]'s unit, for a restock — a count
     * meets a milligram item through its strength. Null when the two cannot be
     * reconciled, which is a real answer and not an error: nothing yet says how
     * big a tablet is.
     */
    fun restockAmount(item: InventoryItemEntity): Double? {
        val stated = stated
        return InventoryMath.convert(
            amount = stated.amount,
            from = stated.unit,
            to = item.unit,
            unitStrengthMG = item.unitStrengthMG ?: stated.unitStrengthMG,
        )
    }

    /**
     * Adopt a picked substance's dosing unit — peptides are not dosed in mg — and,
     * while the amount is still empty, seed it with about ten strong doses, which
     * is a sensible "fresh supply" the user can adjust.
     */
    fun seed(substance: Substance, saltForm: String?) {
        val resolved = substance.unit(substance.defaultRoute, saltForm)
        unit = resolved.ifEmpty { "mg" }
        if (amount == 0.0) {
            InventoryMath.representativeStrongDose(substance.name, saltForm, unit, catalog)?.let {
                amount = roundToTwoSignificantFigures(it * 10)
            }
        }
    }

    private fun lastBuy(item: InventoryItemEntity): Double {
        val last = item.manualEvents
            .filter { it.kind == ManualEvent.Kind.RESTOCK || it.kind == ManualEvent.Kind.INITIAL }
            .maxByOrNull { it.date }
            ?.amount
        if (last != null && last > 0) return last
        val strong = InventoryMath.representativeStrongDose(item.substance, item.saltForm, item.unit, catalog)
        return if (strong != null) roundToTwoSignificantFigures(strong * 10) else 0.0
    }

    /**
     * Round a seed amount to two significant figures so the default reads as a
     * clean number — 3,250 becomes 3,300 — rather than a noisy midpoint.
     */
    private fun roundToTwoSignificantFigures(value: Double): Double {
        if (value <= 0) return value
        val magnitude = 10.0.pow(floor(log10(value)) - 1)
        return round(value / magnitude) * magnitude
    }

    companion object {
        /**
         * The units a supply is stated in, in the order the picker offers them.
         *
         * `µg` is written with MICRO SIGN (U+00B5), the codepoint the catalog uses
         * — the Greek mu is a different character that looks identical and would
         * silently fail to match a dose logged under the other one.
         */
        val unitOptions = listOf("µg", "mg", "g", "mL", "caps", "tabs", "drops")

        /** The count units a piece count can be stated in. */
        val countUnitOptions = listOf("tabs", "caps")
    }
}

// MARK: - Detail

/**
 * Detail for one tracked item: the amount, the run-out estimate and its basis,
 * the supply bar, a Restock action, an Edit action, and the merged history of
 * manual events and matching doses.
 *
 * @param onBack what the back affordance does. Defaults to popping the navigation
 *   stack, which is right when this was pushed; a host presenting it inline
 *   passes its own.
 */
@Composable
fun InventoryItemDetailScreen(
    itemId: String,
    navigator: AppNavigator,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = { navigator.pop() },
) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }

    // Null until Room has produced its first list, which is what distinguishes
    // "not read yet" from "read, and the row is not in it".
    val items by remember { app.database.inventoryDao().observeAll() }
        .collectAsState(initial = null)
    var catalog by remember { mutableStateOf<SubstanceCatalog?>(null) }
    var buckets by remember { mutableStateOf<Map<String, List<DoseEntryEntity>>>(emptyMap()) }
    var showBasisInfo by remember { mutableStateOf(false) }
    var formOpen by remember { mutableStateOf(false) }
    var editOpen by remember { mutableStateOf(false) }

    val item = items?.firstOrNull { it.id.toString().equals(itemId, ignoreCase = true) }

    LaunchedEffect(Unit) { catalog = app.catalog() }

    // Re-derive on arrival, which is what returning from a restock, an edit or a
    // dose change needs: the row may have been written by a path that did not
    // replay it.
    var recomputed by remember(itemId) { mutableStateOf(false) }
    LaunchedEffect(itemId, catalog, item != null) {
        val resolved = catalog ?: return@LaunchedEffect
        val current = item ?: return@LaunchedEffect
        if (recomputed) return@LaunchedEffect
        recomputed = true
        InventoryStore.recompute(app.database, resolved, current)
    }

    LaunchedEffect(item?.trackingStart, catalog) {
        val resolved = catalog ?: return@LaunchedEffect
        val start = item?.trackingStart ?: return@LaunchedEffect
        buckets = InventoryStore.dosesByMatchKey(app.database, start, resolved)
    }

    if (formOpen) {
        InventoryItemFormScreen(
            itemId = itemId,
            prefillSubstance = null,
            navigator = navigator,
            modifier = modifier,
            onDismiss = { formOpen = false },
        )
        return
    }
    if (editOpen) {
        InventoryItemEditScreen(
            itemId = itemId,
            navigator = navigator,
            modifier = modifier,
            onDismiss = { editOpen = false },
        )
        return
    }

    val resolved = catalog
    if (item == null || resolved == null) {
        CenteredMessage(
            stringResource(if (items != null) R.string.toolsb_inventory_item_gone else R.string.toolsb_loading),
            modifier,
        )
        return
    }

    val doses = InventoryMath.dosesFor(item, buckets, resolved)
    val runOut = InventoryMath.runOut(item, buckets, resolved, Instant.now(), zone)
    val dosesLeft = InventoryMath.dosesLeft(item, resolved)
    val info = resolved.lookup(item.substance)

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item(key = "header") {
            DetailHeader(
                item = item,
                runOut = runOut,
                dosesLeft = dosesLeft,
                onBasisInfo = { showBasisInfo = true },
                onRestock = { formOpen = true },
                onEdit = { editOpen = true },
                onBack = onBack,
            )
        }

        if (info != null) {
            item(key = "info") {
                SubstanceInfoRow(onOpen = { navigator.push(PushRoute.Substance(info.name)) })
            }
        }

        item(key = "history-header") {
            Text(
                stringResource(R.string.toolsb_inventory_history),
                modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = PiruTheme.colors.secondaryLabel,
            )
        }

        val rows = historyRows(doses, item)
        if (rows.isEmpty()) {
            item(key = "history-empty") {
                Text(
                    stringResource(R.string.toolsb_inventory_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        } else {
            items(rows, key = { it.key }) { row ->
                SwipeToDelete(onDelete = { deleteHistoryRow(app, catalog, item, row, scope, navigator) }) {
                    HistoryRowLabel(row = row, unit = item.unit)
                }
            }
        }
    }

    if (showBasisInfo) {
        AlertDialog(
            onDismissRequest = { showBasisInfo = false },
            confirmButton = {
                TextButton(onClick = { showBasisInfo = false }) { Text(stringResource(R.string.toolsb_inventory_ok)) }
            },
            title = { Text(stringResource(R.string.toolsb_inventory_runout_title)) },
            text = { Text(stringResource(R.string.toolsb_inventory_runout_basis)) },
        )
    }
}

@Composable
private fun DetailHeader(
    item: InventoryItemEntity,
    runOut: InventoryMath.RunOut?,
    dosesLeft: Int?,
    onBasisInfo: () -> Unit,
    onRestock: () -> Unit,
    onEdit: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.toolsb_inventory_back)) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onEdit) {
                Text(stringResource(R.string.common_edit), style = MaterialTheme.typography.labelLarge)
            }
        }

        PiruCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    item.displayTitle(null),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                val status = item.stockStatus
                if (status == StockStatus.OUT) {
                    Text(
                        stringResource(R.string.toolsb_inventory_status_out),
                        style = MaterialTheme.typography.displaySmall,
                        color = status.numberColor,
                    )
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            inventoryFormatted(item.currentQuantity),
                            style = MaterialTheme.typography.displaySmall,
                            color = status.numberColor,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            item.unit,
                            style = MaterialTheme.typography.titleMedium,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                }

                inventorySupplyLine(context, dosesLeft, runOut)?.let { line ->
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = PiruTheme.colors.secondaryLabel)
                }

                if (runOut != null) {
                    Row(
                        modifier = Modifier.clickable(onClick = onBasisInfo),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = stringResource(R.string.toolsb_inventory_how_calculated),
                            modifier = Modifier.size(14.dp),
                            tint = PiruTheme.colors.secondaryLabel,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            basisLine(context, item, runOut),
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                }

                item.fillFraction?.let { fraction ->
                    InventorySupplyBar(
                        fraction = fraction,
                        tint = item.stockStatus.barTint,
                        status = item.stockStatus,
                        thickness = 12.dp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }

                Button(onClick = onRestock, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.toolsb_inventory_restock))
                }
            }
        }
    }
}

@Composable
private fun SubstanceInfoRow(onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = stringResource(R.string.toolsb_inventory_substance_info),
                onClick = onOpen,
            )
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = PiruTheme.colors.accent)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.toolsb_inventory_substance_info), style = MaterialTheme.typography.bodyLarge)
    }
}

/** The run-out's basis, in one line: what a dose is, and what the week averaged. */
private fun basisLine(context: Context, item: InventoryItemEntity, runOut: InventoryMath.RunOut): String {
    val avg = "${inventoryFormatted(runOut.dailyAvg)} ${item.unit}"
    val size = item.doseSize
    return if (size != null && size > 0) {
        context.getString(
            R.string.toolsb_inventory_basis_single_dose,
            "${inventoryFormatted(size)} ${item.unit}",
            avg,
        )
    } else {
        context.getString(R.string.toolsb_inventory_basis_daily_avg, avg)
    }
}

// MARK: - History

/**
 * One merged history entry: a logged dose or a manual event.
 *
 * The two are different types with different time columns, so the merge needs a
 * common shape — and the shape is deliberately only the fields the row draws,
 * because a row that re-derived them would need the catalog and the replay.
 */
private sealed interface HistoryRow {
    val key: String
    val date: Instant

    data class Dose(val dose: DoseEntryEntity) : HistoryRow {
        override val key: String get() = "dose-${dose.id}"
        override val date: Instant get() = dose.timestamp.toInstant()
    }

    data class Manual(val event: ManualEvent) : HistoryRow {
        override val key: String get() = "manual-${event.id}"
        override val date: Instant get() = event.date
    }
}

private fun historyRows(doses: List<DoseEntryEntity>, item: InventoryItemEntity): List<HistoryRow> {
    val doseRows = doses.map { HistoryRow.Dose(it) }
    val manualRows = item.manualEvents.map { HistoryRow.Manual(it) }
    return (doseRows + manualRows).sortedByDescending { it.date }
}

private fun deleteHistoryRow(
    app: PiruApplication,
    catalog: SubstanceCatalog?,
    item: InventoryItemEntity,
    row: HistoryRow,
    scope: kotlinx.coroutines.CoroutineScope,
    navigator: AppNavigator,
) {
    scope.launch {
        when (row) {
            is HistoryRow.Dose -> {
                app.database.doseEntryDao().deleteByRowId(row.dose.rowId)
                // The session it was in may now be empty or have a different span.
                row.dose.sessionId?.let { app.database.sessionDao().refreshDoseBounds(it) }
                // Same as the journal's delete: the slot this dose satisfied is
                // no longer satisfied, and the record has to say so.
                app.reconcileRoutineOccurrences()
// And the home-screen widget, which draws this slot's state. A dose retimed,
                // relabelled or deleted settles a different slot than it did, and the widget
                // was left showing the previous answer.
                MedWidgetRefresh.afterWrite(app)
                catalog?.let { InventoryStore.recompute(app.database, it, item) }
                navigator.invalidate()
            }

            is HistoryRow.Manual -> {
                catalog?.let { InventoryStore.deleteEvent(app.database, it, item, row.event.id) }
            }
        }
    }
}

/**
 * The visual content of a history row.
 *
 * The leading glyph is the arithmetic sign rather than a symbol per kind. The
 * build has no `pills`, no `cart` and no `slider.horizontal.3` in its core icon
 * set, and of the three the one thing a reader needs at a glance is the
 * direction the stock moved — which the sign says exactly, and which a
 * substituted icon would only approximate.
 */
@Composable
private fun HistoryRowLabel(row: HistoryRow, unit: String) {
    val historyDatePattern = stringResource(R.string.datefmt_day_month_time)
    val dateLocale = appLocale()
    val glyph: String
    val tint: Color
    val title: String
    val amount: String
    val amountColor: Color

    when (row) {
        is HistoryRow.Dose -> {
            glyph = "−"
            tint = PiruTheme.colors.secondaryLabel
            title = stringResource(R.string.toolsb_inventory_row_dose)
            amount = "−${inventoryFormatted(row.dose.amount)} ${row.dose.unit}"
            amountColor = PiruTheme.colors.secondaryLabel
        }

        is HistoryRow.Manual -> {
            val event = row.event
            title = stringResource(
                when (event.kind) {
                    ManualEvent.Kind.INITIAL -> R.string.toolsb_inventory_row_initial
                    ManualEvent.Kind.RESTOCK -> R.string.toolsb_inventory_restock
                    ManualEvent.Kind.ADJUSTMENT -> R.string.toolsb_inventory_row_adjustment
                },
            )
            tint = when (event.kind) {
                ManualEvent.Kind.INITIAL -> PiruTheme.colors.accent
                ManualEvent.Kind.RESTOCK -> PiruTheme.colors.successText
                ManualEvent.Kind.ADJUSTMENT -> PiruTheme.colors.cautionText
            }
            glyph = when (event.kind) {
                ManualEvent.Kind.INITIAL -> "+"
                ManualEvent.Kind.RESTOCK -> "+"
                ManualEvent.Kind.ADJUSTMENT -> "±"
            }
            amount = "${if (event.amount >= 0) "+" else "−"}${inventoryFormatted(abs(event.amount))} $unit"
            amountColor = if (event.amount >= 0) PiruTheme.colors.successText else PiruTheme.colors.secondaryLabel
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            glyph,
            modifier = Modifier.width(26.dp),
            style = MaterialTheme.typography.titleMedium,
            color = tint,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            val note = (row as? HistoryRow.Manual)?.event?.note
            if (!note.isNullOrEmpty()) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                    maxLines = 1,
                )
            }
            Text(
                historyDateFormat(historyDatePattern, dateLocale).format(row.date.atZone(ZoneId.systemDefault())),
                style = MaterialTheme.typography.labelSmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            amount,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = amountColor,
        )
    }
}

/**
 * The stock-history row's date. The pattern is a parameter rather than a literal:
 * its field order is locale-specific (Chinese reads M月d日 HH:mm), and the caller
 * is the composable that can read the resource. The locale comes the same way —
 * the month *name* is a word, so it is the app's language, not the device's.
 */
private fun historyDateFormat(pattern: String, locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern(pattern, locale)

/**
 * A row that can be swiped away, with a red delete panel behind it.
 *
 * The deletion is driven by the settled state rather than by a veto callback:
 * there is nothing to veto here — the row is meant to go — and the state is a
 * plain read, which is also the form the API still supports.
 */
@Composable
private fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    LaunchedEffect(state.currentValue) {
        if (state.currentValue == SwipeToDismissBoxValue.EndToStart) onDelete()
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.error)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.onError,
                )
            }
        },
    ) {
        content()
    }
}

// MARK: - Form

/**
 * Add a tracked item, or restock an existing one.
 *
 * With an item id this is a restock: the substance is fixed and the unit comes
 * from the item. Without one it is the add form, where the substance is picked
 * from the catalog and prefilled when it arrived from a substance screen.
 *
 * Uses a close/check pair at the top with the check as the commit — no bottom
 * button.
 *
 * @param onDismiss what the close button does, and what a successful commit does
 *   after the write. Defaults to dismissing the modal stack, which is right when
 *   a host presents this as a sheet.
 */
@Composable
fun InventoryItemFormScreen(
    itemId: String?,
    prefillSubstance: String?,
    navigator: AppNavigator,
    modifier: Modifier = Modifier,
    prefillSalt: String? = null,
    prefill: InventoryPrefill? = null,
    onDismiss: () -> Unit = { navigator.dismiss() },
) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val scope = rememberCoroutineScope()

    var catalog by remember { mutableStateOf<DbSubstanceCatalog?>(null) }
    var existing by remember { mutableStateOf<InventoryItemEntity?>(null) }
    var resolved by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<InventoryAmountDraft?>(null) }

    var substanceName by remember { mutableStateOf(prefillSubstance.orEmpty()) }
    var selectedSubstance by remember { mutableStateOf<Substance?>(null) }
    var useBaseline by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf(prefill?.note.orEmpty()) }

    LaunchedEffect(Unit) { catalog = app.catalog() }

    LaunchedEffect(itemId, catalog) {
        val loaded = catalog ?: return@LaunchedEffect
        val id = itemId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        existing = id?.let { app.database.inventoryDao().byId(it) }
        resolved = true
        existing?.let { substanceName = it.substance }
        // The draft is built once, after the item is known: its opening amount is
        // "what you last bought", which is a question about that row.
        draft = InventoryAmountDraft(prefill = prefill, catalog = loaded, existingItem = existing)
    }

    val current = draft
    val loaded = catalog
    if (!resolved || current == null || loaded == null) {
        CenteredMessage(stringResource(R.string.toolsb_loading), modifier)
        return
    }

    val isRestock = existing != null
    val substanceFixed = itemId != null || prefillSubstance != null
    val itemSalt = existing?.saltForm ?: prefillSalt
    val titleName = loaded.lookup(substanceName)?.displayTitle ?: substanceName
    val navTitle = when {
        isRestock -> stringResource(R.string.toolsb_inventory_title_restock, titleName)
        substanceFixed -> stringResource(R.string.toolsb_inventory_title_track, titleName)
        else -> stringResource(R.string.toolsb_inventory_track_substance)
    }
    val canCommit = if (existing != null) {
        (current.restockAmount(existing!!) ?: 0.0) > 0
    } else {
        substanceName.trim().isNotEmpty()
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.common_cancel))
            }
            Text(
                navTitle,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(
                onClick = {
                    scope.launch {
                        commitForm(app, loaded, existing, substanceName, current, useBaseline, note, prefillSalt)
                        onDismiss()
                    }
                },
                enabled = canCommit,
            ) { Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.common_save)) }
        }

        // Only the generic add-from-manager form shows a substance picker; opened
        // from a substance — track or restock — the substance lives in the title.
        if (!substanceFixed) {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.common_substance),
                        style = MaterialTheme.typography.labelLarge,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                    SubstanceSearchField(
                        value = substanceName,
                        catalog = loaded,
                        onValueChange = {
                            substanceName = it
                            selectedSubstance = null
                        },
                        onPick = { picked ->
                            selectedSubstance = picked
                            substanceName = picked.name
                            current.seed(substance = picked, saltForm = itemSalt)
                        },
                    )
                    if (selectedSubstance == null && substanceName.isNotEmpty()) {
                        Text(
                            stringResource(R.string.toolsb_inventory_custom_substance),
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                }
            }
        }

        PiruCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        if (isRestock) R.string.toolsb_inventory_amount_added else R.string.toolsb_inventory_starting_amount,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = PiruTheme.colors.secondaryLabel,
                )

                // A counted item restocks in its own unit; the pieces shape is for
                // stating a box against a plain amount.
                val allowsPieces = !InventoryMath.isCountUnit(existing?.unit.orEmpty())
                if (allowsPieces) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = current.mode == InventoryAmountDraft.Mode.AMOUNT,
                            onClick = { current.mode = InventoryAmountDraft.Mode.AMOUNT },
                            label = { Text(stringResource(R.string.toolsb_inventory_mode_amount)) },
                        )
                        FilterChip(
                            selected = current.mode == InventoryAmountDraft.Mode.PIECES,
                            onClick = { current.mode = InventoryAmountDraft.Mode.PIECES },
                            label = { Text(stringResource(R.string.toolsb_inventory_mode_pieces)) },
                        )
                    }
                }

                val stepBasis: (String) -> Double? = { u ->
                    InventoryMath.referenceDose(substanceName, itemSalt, u, loaded)
                }

                when (current.mode) {
                    InventoryAmountDraft.Mode.AMOUNT -> InventoryStepperRow(
                        value = current.amount,
                        onValueChange = { current.amount = it },
                        unit = current.unit,
                        label = stringResource(
                            if (isRestock) R.string.toolsb_inventory_amount_added else R.string.toolsb_inventory_starting_amount,
                        ),
                        stepBasis = stepBasis(current.unit),
                        // A restock's unit is the item's, already fixed.
                        unitChoices = if (isRestock) null else unitChoicesWith(current.unit),
                        onUnitChange = if (isRestock) null else { { current.unit = it } },
                        focusOnAppear = prefill != null && current.amount == 0.0,
                    )

                    InventoryAmountDraft.Mode.PIECES -> {
                        InventoryStepperRow(
                            value = current.count,
                            onValueChange = { current.count = it },
                            unit = current.countUnit,
                            label = stringResource(R.string.toolsb_inventory_count_label),
                            unitChoices = InventoryAmountDraft.countUnitOptions,
                            onUnitChange = { current.countUnit = it },
                            focusOnAppear = prefill != null && current.count == 0.0,
                        )
                        InventoryStepperRow(
                            value = current.strengthMG,
                            onValueChange = { current.strengthMG = it },
                            unit = "mg",
                            label = stringResource(R.string.toolsb_inventory_strength_label),
                            stepBasis = stepBasis("mg"),
                            focusOnAppear = prefill != null && current.count > 0 && current.strengthMG == 0.0,
                        )
                    }
                }

                val footer = when {
                    current.mode == InventoryAmountDraft.Mode.PIECES && isRestock ->
                        stringResource(R.string.toolsb_inventory_added_at_strength)

                    current.mode == InventoryAmountDraft.Mode.PIECES ->
                        stringResource(R.string.toolsb_inventory_counted_in, current.countUnit)

                    else -> null
                }
                footer?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = PiruTheme.colors.secondaryLabel)
                }
            }
        }

        PiruCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            if (existing?.hasBaseline == true) {
                                R.string.toolsb_inventory_set_as_baseline
                            } else {
                                R.string.toolsb_inventory_use_as_baseline
                            },
                        ),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(checked = useBaseline, onCheckedChange = { useBaseline = it })
                }
                Text(
                    stringResource(R.string.toolsb_inventory_baseline_toggle_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        PiruCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.toolsb_inventory_note),
                    style = MaterialTheme.typography.labelLarge,
                    color = PiruTheme.colors.secondaryLabel,
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    placeholder = { Text(stringResource(R.string.toolsb_inventory_add_note)) },
                    minLines = 1,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(FAB_CLEARANCE))
    }
}

/** The picker's choices, always including the current unit so a peptide dosed in IU never renders blank. */
private fun unitChoicesWith(current: String): List<String> {
    val trimmed = current.trim()
    val options = InventoryAmountDraft.unitOptions
    return if (trimmed.isNotEmpty() && trimmed !in options) listOf(trimmed) + options else options
}

private suspend fun commitForm(
    app: PiruApplication,
    catalog: SubstanceCatalog,
    existing: InventoryItemEntity?,
    substanceName: String,
    draft: InventoryAmountDraft,
    useBaseline: Boolean,
    note: String,
    prefillSalt: String?,
) {
    val trimmedNote = note.trim().ifEmpty { null }
    if (existing != null) {
        InventoryStore.restock(
            db = app.database,
            catalog = catalog,
            item = existing,
            amount = draft.restockAmount(existing) ?: 0.0,
            note = trimmedNote,
            setBaseline = useBaseline,
            unitStrengthMG = draft.stated.unitStrengthMG,
        )
    } else {
        val stated = draft.stated
        InventoryStore.create(
            db = app.database,
            catalog = catalog,
            substance = substanceName.trim(),
            // The salt a route named, not the picker's — the add form has no salt
            // field, and the substance it picked is the same one either way.
            saltForm = prefillSalt,
            unit = stated.unit,
            initial = stated.amount,
            threshold = null,
            setBaseline = useBaseline,
            note = trimmedNote,
            unitStrengthMG = stated.unitStrengthMG,
        )
    }
}

/**
 * The substance picker: a text field over the catalog's own ranked search.
 *
 * Upstream shows a dropdown; this shows the top few matches inline under the
 * field. A `Popup` anchored to a field inside a scrolling form is one more thing
 * that can land off-screen, and the list is capped at six either way.
 */
@Composable
private fun SubstanceSearchField(
    value: String,
    catalog: DbSubstanceCatalog,
    onValueChange: (String) -> Unit,
    onPick: (Substance) -> Unit,
) {
    var matches by remember { mutableStateOf<List<SubstanceMatch<Substance>>>(emptyList()) }

    LaunchedEffect(value, catalog) {
        matches = if (value.isBlank()) emptyList() else catalog.search(value, limit = 6)
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(stringResource(R.string.common_substance)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        for (match in matches) {
            val substance = match.substance
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        onClickLabel = stringResource(R.string.toolsb_inventory_use_substance, substance.displayTitle),
                    ) { onPick(substance) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(substance.displayTitle, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(8.dp))
                Text(
                    match.matchedAlias ?: substance.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }
    }
}

// MARK: - Edit

/**
 * Edit an item's exact amount, baseline, single-dose size and low-stock threshold.
 *
 * On hand and Baseline are independent fields — a recount against the "full"
 * reference — and `0` disables the baseline, the single dose and the threshold.
 *
 * @param onDismiss what the close button does, and what a successful commit does
 *   after the write.
 */
@Composable
fun InventoryItemEditScreen(
    itemId: String,
    navigator: AppNavigator,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = { navigator.dismiss() },
) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val scope = rememberCoroutineScope()

    var catalog by remember { mutableStateOf<SubstanceCatalog?>(null) }
    var item by remember { mutableStateOf<InventoryItemEntity?>(null) }
    var seeded by remember { mutableStateOf(false) }
    var missing by remember { mutableStateOf(false) }

    var unit by remember { mutableStateOf("mg") }
    var onHand by remember { mutableStateOf(0.0) }
    var baseline by remember { mutableStateOf(0.0) }
    var doseSize by remember { mutableStateOf(0.0) }
    var threshold by remember { mutableStateOf(0.0) }

    LaunchedEffect(Unit) { catalog = app.catalog() }

    LaunchedEffect(itemId, catalog) {
        val loaded = catalog ?: return@LaunchedEffect
        if (seeded) return@LaunchedEffect
        val id = runCatching { UUID.fromString(itemId) }.getOrNull() ?: run {
            missing = true
            return@LaunchedEffect
        }
        val row = app.database.inventoryDao().byId(id) ?: run {
            missing = true
            return@LaunchedEffect
        }
        item = row
        unit = row.unit
        onHand = row.currentQuantity
        baseline = row.baselineQuantity ?: 0.0
        doseSize = row.doseSize ?: 0.0
        threshold = row.lowStockThreshold ?: 0.0
        seeded = true
    }

    val current = item
    val loaded = catalog
    if (current == null || loaded == null) {
        // A row deleted from somewhere else closes rather than showing a blank —
        // upstream's edit host does the same, and an empty form here would create
        // a second item on save.
        CenteredMessage(
            stringResource(if (missing) R.string.toolsb_inventory_item_gone else R.string.toolsb_loading),
            modifier,
        )
        return
    }

    // The stepper's increment anchor, for this substance in the unit being edited.
    val stepBasis = InventoryMath.referenceDose(current.substance, current.saltForm, unit, loaded)

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.common_cancel))
            }
            Text(
                stringResource(R.string.common_edit),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(
                onClick = {
                    scope.launch {
                        commitEdit(
                            db = app.database,
                            catalog = loaded,
                            item = current,
                            unit = unit,
                            onHand = onHand,
                            baseline = baseline,
                            doseSize = doseSize,
                            threshold = threshold,
                        )
                        onDismiss()
                    }
                },
            ) { Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.common_save)) }
        }

        EditSection(
            title = stringResource(R.string.toolsb_inventory_on_hand),
            footer = stringResource(R.string.toolsb_inventory_on_hand_footer),
        ) {
            InventoryStepperRow(
                value = onHand,
                onValueChange = { onHand = it },
                unit = unit,
                label = stringResource(R.string.toolsb_inventory_on_hand),
                stepBasis = stepBasis,
                unitChoices = unitChoicesWith(unit),
                onUnitChange = { unit = it },
            )
        }

        EditSection(
            title = stringResource(R.string.toolsb_inventory_baseline_title),
            footer = stringResource(R.string.toolsb_inventory_baseline_footer),
        ) {
            InventoryStepperRow(
                value = baseline,
                onValueChange = { baseline = it },
                unit = unit,
                label = stringResource(R.string.toolsb_inventory_baseline),
                stepBasis = stepBasis,
            )
        }

        EditSection(
            title = stringResource(R.string.toolsb_inventory_single_dose),
            footer = stringResource(R.string.toolsb_inventory_single_dose_footer),
        ) {
            InventoryStepperRow(
                value = doseSize,
                onValueChange = { doseSize = it },
                unit = unit,
                label = stringResource(R.string.toolsb_inventory_single_dose),
                stepBasis = stepBasis,
            )
        }

        EditSection(
            title = stringResource(R.string.toolsb_inventory_warn_below),
            footer = stringResource(R.string.toolsb_inventory_warn_below_footer),
        ) {
            InventoryStepperRow(
                value = threshold,
                onValueChange = { threshold = it },
                unit = unit,
                label = stringResource(R.string.toolsb_inventory_warn_below),
                stepBasis = stepBasis,
            )
        }

        Spacer(Modifier.height(FAB_CLEARANCE))
    }
}

@Composable
private fun EditSection(title: String, footer: String, content: @Composable () -> Unit) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = PiruTheme.colors.secondaryLabel)
            content()
            Text(footer, style = MaterialTheme.typography.bodySmall, color = PiruTheme.colors.secondaryLabel)
        }
    }
}

/**
 * Apply the edit, in the order the write paths require.
 *
 * Order is load-bearing, not cosmetic:
 * 1. **Unit first.** Changing it converts the stored events and the derived
 *    fields, so the replay stays consistent. The scalars below are already
 *    expressed in the new unit — they are what is on screen — so they overwrite
 *    whatever the conversion produced.
 * 2. **Correction second, and only when the figure moved.** Comparing against the
 *    *live* quantity rather than the cached one means opening the screen and
 *    saving without touching anything appends no adjustment.
 * 3. **The three derived fields last**, then one replay to land the cache.
 */
private suspend fun commitEdit(
    db: PiruDatabase,
    catalog: SubstanceCatalog,
    item: InventoryItemEntity,
    unit: String,
    onHand: Double,
    baseline: Double,
    doseSize: Double,
    threshold: Double,
) {
    var working = item
    if (unit != working.unit) {
        working = InventoryStore.changeUnit(db, catalog, working, unit)
    }

    val current = InventoryStore.quantity(db, catalog, working)
    if (abs(onHand - current) > 0.0001) {
        working = InventoryStore.correctTo(db, catalog, working, onHand, note = null)
    }

    working = working.copy(
        baselineQuantity = normalizedPositive(baseline),
        doseSize = normalizedPositive(doseSize),
        lowStockThreshold = normalizedPositive(threshold),
    )
    InventoryStore.recompute(db, catalog, working)
}

// MARK: - Shared

@Composable
private fun CenteredMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = PiruTheme.colors.secondaryLabel)
    }
}
