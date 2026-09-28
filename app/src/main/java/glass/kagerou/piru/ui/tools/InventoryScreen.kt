package glass.kagerou.piru.ui.tools

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.data.InventoryMath
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.data.entity.InventoryItemEntity
import glass.kagerou.piru.engine.SubstanceCatalog
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.model.SubstanceCategory
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import glass.kagerou.piru.ui.theme.toComposeColor

/**
 * The inventory manager: a searchable, sortable list of everything tracked,
 * grouped by substance class by default.
 *
 * Ported from `Piru/Views/Tools/Inventory/InventoryListView.swift` (lines 82-335)
 * and `InventoryListModel.swift` (359 lines), plus `InventoryMenus.swift` (236)
 * and `InventoryClassOrderView.swift` (78).
 *
 * ## The screen is thin on purpose
 * Everything about ordering lives in [InventoryListModel], and each row is its own
 * composable, so a keystroke in the search field does not re-evaluate eighty rows
 * worth of catalog lookups. That is the same split upstream has, and it is the
 * reason the Swift file is 250 lines of view around a model that does the work.
 *
 * ## Sub-navigation is local state, not routes
 * The port's `PushRoute` carries no inventory case — the routes are wired by
 * whoever lands them, and a screen that referenced a route the enum does not have
 * would not compile. So the detail, the form, the edit screen and the class
 * arrangement are presented by swapping this screen's content, exactly the way a
 * push would look. Each of those composables takes an `onBack`/`onDismiss`, which
 * is the seam: when the routes land, the host passes a lambda that pushes instead.
 *
 * ## What is not carried
 * The barcode scanner (`LabelScannerView` + `BoxIdentifier`), which needs
 * on-device image work and a `DataScannerViewController` equivalent. The `+`
 * button is the way in, and the add form's prefill parameters are already in
 * place for the scanner line to fill.
 */

// MARK: - Sort

/**
 * How the manager orders its rows.
 *
 * The same key orders *sections* when grouping is on: a section inherits the rank
 * of its best-ranked item, so switching grouping on and off never changes what
 * the chosen order is trying to say.
 *
 * Declaration order is the iOS `InventorySort` order, and [wireValue] is the
 * `String` raw value the persisted preference carries — never derive either from
 * the Kotlin constant's name.
 */
enum class InventorySort(val wireValue: String, val displayName: String) {
    /** Needs-attention first: out, then low, then healthy, then most recent activity. */
    STATUS("status", "Status"),

    /** Alphabetical by display title. */
    NAME("name", "Name"),

    /** Emptiest first, as a fraction of baseline. Items without a baseline sort last. */
    SUPPLY("supply", "Supply Level"),

    /** Most recently restocked or corrected first. */
    RECENT("recent", "Recently Updated"),

    /** The user's own arrangement — the only mode where rows can be moved. */
    MANUAL("manual", "Manual"),
    ;

    companion object {
        fun fromWire(value: String?): InventorySort? = entries.firstOrNull { it.wireValue == value }
    }
}

// MARK: - Section

/**
 * One rendered group of inventory rows.
 *
 * In flat mode the list is a single section with no [category]; grouped, there is
 * one per substance class.
 */
data class InventorySectionGroup(
    /** Stable across rebuilds, so the list keeps section identity while filtering. */
    val id: String,
    /** Null in flat mode — the section then renders headerless. */
    val category: SubstanceCategory?,
    val items: List<InventoryItemEntity>,
)

/**
 * The class label as the app writes it.
 *
 * Almost every class spells its [SubstanceCategory.wireValue] as its display name;
 * the orexin antagonists are the one that does not ("OrexinAntagonist" against
 * "Orexin Antagonist"), so that one is named here rather than derived.
 */
internal fun SubstanceCategory.label(): String =
    if (this == SubstanceCategory.OREXIN_ANTAGONIST) "Orexin Antagonist" else wireValue

// MARK: - Model

/**
 * The manager's view options — sort, grouping, search and the two filter facets —
 * plus the filtering and grouping itself.
 *
 * ## Why this is shared and not per-screen
 * Two surfaces render the same ordering: the manager and the Tools summary card.
 * A per-screen instance would read the persisted options once at construction and
 * then miss every later change, so the card would keep showing the previous sort
 * until the tab was rebuilt. Upstream's answer is a `static let shared`, and this
 * is the same object with the same lifetime.
 *
 * ## Sort and grouping persist, search and filters do not
 * Sort and grouping read as a *preference* — the user chose how inventory reads,
 * and it should still read that way tomorrow. Search and the facets are a
 * transient narrowing of one screen, and a filter silently left on is how a list
 * comes to lie about what is in stock.
 *
 * ## `SharedPreferences`, not Room
 * These are four scalars belonging to one screen, which is what the platform's
 * preference store is for. The port has no settings store yet; when it does, this
 * moves, and the keys are already namespaced `inventory.*` so the move is a
 * copy.
 */
@Stable
class InventoryListModel private constructor(private val prefs: SharedPreferences) {

    private var sortState by mutableStateOf(InventorySort.fromWire(prefs.getString(KEY_SORT, null)) ?: InventorySort.STATUS)
    private var groupedState by mutableStateOf(prefs.getBoolean(KEY_GROUPED, true))

    var sort: InventorySort
        get() = sortState
        set(value) {
            sortState = value
            prefs.edit { putString(KEY_SORT, value.wireValue) }
        }

    var isGrouped: Boolean
        get() = groupedState
        set(value) {
            groupedState = value
            prefs.edit { putBoolean(KEY_GROUPED, value) }
        }

    /**
     * The default is grouped, and the read distinguishes "never set" from an
     * explicit false — hence `getBoolean(key, true)` rather than a nullable read.
     * A fresh install groups; a user who turned it off stays off.
     */
    var searchText by mutableStateOf("")

    /** Empty means "every status" — the menu shows no checkmarks in that state. */
    var filterStatuses by mutableStateOf<Set<StockStatus>>(emptySet())

    /** Empty means "every class". */
    var filterCategories by mutableStateOf<Set<SubstanceCategory>>(emptySet())

    /** Classes the user has folded away. Persisted: a class you never think about should stay folded. */
    var collapsedCategories by mutableStateOf(
        decodeCategories(prefs.getString(KEY_COLLAPSED, null)).toSet(),
    )
        private set

    /** The user's manual arrangement of class sections. Empty means "derive it from the sort". */
    var categoryOrder by mutableStateOf(
        decodeCategories(prefs.getString(KEY_CATEGORY_ORDER, null)),
    )
        private set

    val hasActiveFilters: Boolean
        get() = filterStatuses.isNotEmpty() || filterCategories.isNotEmpty()

    /**
     * Reordering only makes sense when a manual order is what is on screen — any
     * other sort would immediately overwrite the move, and moving across section
     * boundaries has no meaning.
     */
    val canReorder: Boolean
        get() = sort == InventorySort.MANUAL && !isGrouped

    /** True once the user has arranged classes by hand, which then outranks the sort. */
    val hasCustomCategoryOrder: Boolean
        get() = categoryOrder.isNotEmpty()

    fun clearFilters() {
        filterStatuses = emptySet()
        filterCategories = emptySet()
    }

    fun toggleStatus(status: StockStatus) {
        filterStatuses = filterStatuses.toggle(status)
    }

    fun toggleCategory(category: SubstanceCategory) {
        filterCategories = filterCategories.toggle(category)
    }

    /**
     * Whether a class section shows its rows.
     *
     * A live search force-expands everything: a folded section would swallow its
     * own matches, so the search would look like it found nothing.
     */
    fun isExpanded(category: SubstanceCategory): Boolean =
        searchText.trim().isNotEmpty() || category !in collapsedCategories

    fun toggleCollapsed(category: SubstanceCategory) {
        collapsedCategories = collapsedCategories.toggle(category)
        prefs.edit { putString(KEY_COLLAPSED, encodeCategories(collapsedCategories.toList())) }
    }

    /** Fold or unfold everything at once — with twenty classes, one at a time is a chore. */
    fun setAllCollapsed(collapsed: Boolean, categories: List<SubstanceCategory>) {
        collapsedCategories = if (collapsed) categories.toSet() else emptySet()
        prefs.edit { putString(KEY_COLLAPSED, encodeCategories(collapsedCategories.toList())) }
    }

    /**
     * Set the manual class arrangement.
     *
     * Named `apply…` rather than `set…`: Kotlin generates `setCategoryOrder` for
     * the [categoryOrder] property's own setter, and a method of the same name
     * would collide on the JVM with no compile-time complaint until the bytecode
     * pass.
     */
    fun applyCategoryOrder(order: List<SubstanceCategory>) {
        categoryOrder = order
        prefs.edit { putString(KEY_CATEGORY_ORDER, encodeCategories(order)) }
    }

    /** Hand section ordering back to the sort. */
    fun resetCategoryOrder() {
        applyCategoryOrder(emptyList())
    }

    // MARK: Category resolution

    /**
     * Memoized `substance -> class` lookups, keyed by the lowercased canonical
     * name. Resolving eighty items through the catalog on every pass is the one
     * genuinely expensive part of grouping.
     *
     * A name that does not resolve is **not** memoized, so a cold catalog cannot
     * pin a substance to `Other` for the life of the process — the same posture
     * upstream takes, and the reason the cache is keyed on a successful lookup
     * only.
     */
    private val categoryCache = mutableMapOf<String, SubstanceCategory>()

    fun categoryFor(item: InventoryItemEntity, catalog: SubstanceCatalog?): SubstanceCategory {
        val key = item.substance.lowercase()
        categoryCache[key]?.let { return it }
        val resolved = catalog?.lookup(item.substance)?.category ?: return SubstanceCategory.OTHER
        categoryCache[key] = resolved
        return resolved
    }

    /**
     * Every class present in the *unfiltered* inventory, alphabetized — the filter
     * offers only classes the user actually stocks.
     */
    fun availableCategories(items: List<InventoryItemEntity>, catalog: SubstanceCatalog?): List<SubstanceCategory> =
        items.map { categoryFor(it, catalog) }.distinct().sortedBy { it.label() }

    // MARK: Sectioning

    /**
     * The full pipeline: filter, sort, group. Pure with respect to the options, so
     * the screen just renders whatever comes back.
     */
    fun sections(items: List<InventoryItemEntity>, catalog: SubstanceCatalog?): List<InventorySectionGroup> =
        arrange(filtered(items, catalog), catalog)

    /**
     * Every item in the manager's order, flattened — what the Tools summary card
     * takes its top few from.
     *
     * Deliberately skips [filtered]: sort and class arrangement are settings the
     * user chose for how inventory *reads*, while search and the facets are a
     * transient narrowing of one screen. A hub card that quietly hid most of the
     * inventory because a filter was left on elsewhere would be a lie about what
     * is in stock.
     */
    fun ordered(items: List<InventoryItemEntity>, catalog: SubstanceCatalog?): List<InventoryItemEntity> =
        arrange(items, catalog).flatMap { it.items }

    /** Sort, then group when grouping is on — the ordering half, shared so the card and the list cannot drift. */
    private fun arrange(items: List<InventoryItemEntity>, catalog: SubstanceCatalog?): List<InventorySectionGroup> {
        val rows = sorted(items, catalog)
        if (!isGrouped) {
            return if (rows.isEmpty()) {
                emptyList()
            } else {
                listOf(InventorySectionGroup(id = "all", category = null, items = rows))
            }
        }
        return grouped(rows, catalog)
    }

    /**
     * Search plus both facets.
     *
     * Search matches the display title, the stored canonical name — so
     * "bromoketamine" finds the row shown as "2-Br-DCK" — the salt, and the class
     * name.
     */
    private fun filtered(items: List<InventoryItemEntity>, catalog: SubstanceCatalog?): List<InventoryItemEntity> {
        val query = searchText.trim().lowercase()
        return items.filter { item ->
            if (filterStatuses.isNotEmpty() && item.stockStatus !in filterStatuses) return@filter false
            if (filterCategories.isNotEmpty() && categoryFor(item, catalog) !in filterCategories) return@filter false
            if (query.isEmpty()) return@filter true
            val haystack = listOf(
                item.displayTitle(catalog),
                item.substance,
                item.saltForm ?: "",
                categoryFor(item, catalog).label(),
            )
            haystack.any { it.lowercase().contains(query) }
        }
    }

    private fun sorted(items: List<InventoryItemEntity>, catalog: SubstanceCatalog?): List<InventoryItemEntity> = when (sort) {
        // Attention first, then most recent within a rank.
        InventorySort.STATUS -> items.sortedWith(
            compareBy<InventoryItemEntity> { it.sortPriority }.thenByDescending { it.lastActivity },
        )

        InventorySort.NAME -> items.sortedWith { a, b ->
            a.displayTitle(catalog).compareTo(b.displayTitle(catalog), ignoreCase = true)
        }

        // No baseline means no comparable level, so those rows collect at the end
        // rather than pretending to be full or empty.
        InventorySort.SUPPLY -> items.sortedWith { a, b ->
            val left = a.fillFraction ?: Double.POSITIVE_INFINITY
            val right = b.fillFraction ?: Double.POSITIVE_INFINITY
            if (left == right) {
                a.displayTitle(catalog).compareTo(b.displayTitle(catalog), ignoreCase = true)
            } else {
                left.compareTo(right)
            }
        }

        InventorySort.RECENT -> items.sortedByDescending { it.lastActivity }

        InventorySort.MANUAL -> items.sortedBy { it.sortOrder }
    }

    /**
     * Bucket the already-sorted rows by class, preserving the row order inside each
     * section.
     *
     * Section order follows the same key as the rows: first appearance in the
     * sorted list, so a section is ranked by its best item — the class holding the
     * only Out item leads a status sort. Sorting *by name* is the exception; there
     * the sections themselves go alphabetical, which is what "by name" means once
     * the rows are already grouped.
     *
     * A user-dragged [categoryOrder] overrides all of that. Classes it does not
     * mention — a newly tracked one — keep their derived position and settle after
     * the arranged ones rather than jumping to the top.
     */
    private fun grouped(rows: List<InventoryItemEntity>, catalog: SubstanceCatalog?): List<InventorySectionGroup> {
        val buckets = LinkedHashMap<SubstanceCategory, MutableList<InventoryItemEntity>>()
        for (item in rows) {
            val category = categoryFor(item, catalog)
            buckets.getOrPut(category) { mutableListOf() }.add(item)
        }
        val order = buckets.keys.toMutableList()
        if (sort == InventorySort.NAME) {
            order.sortBy { it.label() }
        }
        if (hasCustomCategoryOrder) {
            val derived = order.withIndex().associate { (index, category) -> category to index }
            val arranged = categoryOrder.withIndex().associate { (index, category) -> category to index }
            // Two keys in order: the user's arrangement first, then the position
            // the sort derived. A class the arrangement does not mention keeps its
            // derived rank and settles after the arranged ones rather than
            // jumping to the top.
            order.sortWith { a, b ->
                val leftRank = arranged[a] ?: Int.MAX_VALUE
                val rightRank = arranged[b] ?: Int.MAX_VALUE
                if (leftRank != rightRank) {
                    leftRank.compareTo(rightRank)
                } else {
                    (derived[a] ?: 0).compareTo(derived[b] ?: 0)
                }
            }
        }
        return order.map { InventorySectionGroup(id = it.wireValue, category = it, items = buckets.getValue(it)) }
    }

    companion object {
        private const val PREFS_NAME = "inventory_options"
        private const val KEY_SORT = "inventory.sort"
        private const val KEY_GROUPED = "inventory.grouped"
        private const val KEY_COLLAPSED = "inventory.collapsedCategories"
        private const val KEY_CATEGORY_ORDER = "inventory.categoryOrder"

        @Volatile
        private var instance: InventoryListModel? = null

        /** The one instance, matching upstream's `static let shared`. */
        fun shared(context: Context): InventoryListModel =
            instance ?: synchronized(this) {
                instance ?: InventoryListModel(
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                ).also { instance = it }
            }

        private fun encodeCategories(categories: List<SubstanceCategory>): String =
            categories.joinToString(",") { it.wireValue }

        /**
         * A stored class list, order preserved. An unknown wire value is dropped
         * rather than fatal: the class list grows, and a preference written by a
         * later build must not make this one throw.
         */
        private fun decodeCategories(raw: String?): List<SubstanceCategory> =
            raw?.split(",")?.mapNotNull { SubstanceCategory.fromWire(it) }.orEmpty()
    }
}

private fun <T> Set<T>.toggle(value: T): Set<T> = if (contains(value)) this - value else this + value

// MARK: - Screen

/**
 * The inventory manager.
 *
 * Presented by whoever owns the route; the screen itself owns the way in to the
 * detail, the add form and the class arrangement, because no route in this build
 * carries them yet.
 */
@Composable
fun InventoryScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val scope = rememberCoroutineScope()
    val model = remember { InventoryListModel.shared(context) }

    val items by remember { app.database.inventoryDao().observeAll() }.collectAsState(emptyList())
    var catalog by remember { mutableStateOf<SubstanceCatalog?>(null) }
    var tints by remember { mutableStateOf<Map<String, P3Color>>(emptyMap()) }
    var dosesByMatchKey by remember { mutableStateOf<Map<String, List<DoseEntryEntity>>>(emptyMap()) }

    // Which item the detail is showing, and which sheet is up. See the file note
    // on why these are local rather than routes.
    var detailId by remember { mutableStateOf<String?>(null) }
    var formRequest by remember { mutableStateOf<FormRequest?>(null) }
    var showClassOrder by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { catalog = app.catalog() }

    val names = remember(items) { items.map { it.substance }.distinct() }
    LaunchedEffect(names) { tints = app.palette().tintsFor(names) }

    // One dose fetch for the whole list, bucketed by identity — the rows' "~N doses
    // left" and "last dose" subtitles would otherwise replay it per row.
    LaunchedEffect(items, catalog) {
        val resolved = catalog ?: return@LaunchedEffect
        val earliest = items.minOfOrNull { it.trackingStart } ?: return@LaunchedEffect
        dosesByMatchKey = InventoryMath.bucketDoses(
            app.database.doseEntryDao().all().filter { it.timestamp.toInstant() >= earliest },
            resolved,
        )
    }

    // A manual order is persisted as the whole list, renumbered. Nothing has to
    // tell the screen to reload: the DAO's `observeAll` flow above is the reader.
    val reorder: (List<InventoryItemEntity>) -> Unit = { ordered ->
        scope.launch { InventoryStore.reorder(app.database, ordered) }
    }

    val openDetail = detailId
    if (openDetail != null) {
        InventoryItemDetailScreen(
            itemId = openDetail,
            navigator = navigator,
            modifier = modifier,
            onBack = { detailId = null },
        )
        return
    }

    val request = formRequest
    if (request != null) {
        InventoryItemFormScreen(
            itemId = request.itemId,
            prefillSubstance = request.prefillSubstance,
            navigator = navigator,
            modifier = modifier,
            onDismiss = { formRequest = null },
        )
        return
    }

    if (showClassOrder) {
        InventoryClassOrderScreen(
            model = model,
            categories = model.availableCategories(items, catalog),
            modifier = modifier,
            onDone = { showClassOrder = false },
        )
        return
    }

    val sections = model.sections(items, catalog)

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item(key = "header") {
            ManagerHeader(
                showMenu = items.isNotEmpty(),
                model = model,
                categories = model.availableCategories(items, catalog),
                onAdd = { formRequest = FormRequest(itemId = null, prefillSubstance = null) },
                onArrangeClasses = { showClassOrder = true },
            )
        }

        if (items.isEmpty()) {
            item(key = "empty") { NoInventoryYet(onTrack = { formRequest = FormRequest(null, null) }) }
            return@LazyColumn
        }

        item(key = "search") {
            // Always visible rather than pull-to-reveal: with dozens of tracked
            // items search is the primary way in, and a hidden field reads as
            // "there is no search here".
            OutlinedTextField(
                value = model.searchText,
                onValueChange = { model.searchText = it },
                placeholder = { Text("Search inventory") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (model.searchText.isNotEmpty()) {
                    {
                        IconButton(onClick = { model.searchText = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }

        if (model.hasActiveFilters) {
            item(key = "filters") { InventoryFilterBar(model, Modifier.fillMaxWidth()) }
        }

        if (sections.isEmpty()) {
            item(key = "nomatch") { NoMatchingItems(model) }
            return@LazyColumn
        }

        for (section in sections) {
            val category = section.category
            if (category != null) {
                item(key = "h:${section.id}") {
                    CategoryHeader(
                        category = category,
                        count = section.items.size,
                        expanded = model.isExpanded(category),
                        onToggle = { model.toggleCollapsed(category) },
                    )
                }
            }
            // A folded section keeps its header — and its count — but renders no
            // rows. Folding it away entirely would lose the handle to get it back.
            if (category == null || model.isExpanded(category)) {
                items(section.items, key = { "i:${it.id}" }) { item ->
                    InventoryRow(
                        item = item,
                        tint = tints[item.substance.lowercase()],
                        catalog = catalog,
                        dosesByMatchKey = dosesByMatchKey,
                        onOpen = { detailId = item.id.toString() },
                        // Rows carry the move handles only in manual, ungrouped
                        // mode — where the visible list *is* the whole list, and
                        // so renumbering it is exactly what the user dragged.
                        onMove = if (model.canReorder) {
                            { delta -> reorder(reorderSection(section.items, item, delta)) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

/** A request to open the add/restock form. */
private data class FormRequest(val itemId: String?, val prefillSubstance: String?)

/**
 * [items] with [item] moved [delta] places, or [items] unchanged when the move
 * would fall off either end.
 *
 * A stand-in for the drag handle upstream uses: Compose's `LazyColumn` has no
 * built-in reorder and a drag-and-drop implementation is a library this build
 * does not carry. The buttons keep the feature reachable, and the commit — the
 * whole list renumbered `0..n` — is exactly what the drag did.
 */
private fun reorderSection(
    items: List<InventoryItemEntity>,
    item: InventoryItemEntity,
    delta: Int,
): List<InventoryItemEntity> {
    val from = items.indexOf(item)
    val to = from + delta
    if (from < 0 || to < 0 || to >= items.size) return items
    val ordered = items.toMutableList()
    ordered.add(to, ordered.removeAt(from))
    return ordered
}

// MARK: - Header

@Composable
private fun ManagerHeader(
    showMenu: Boolean,
    model: InventoryListModel,
    categories: List<SubstanceCategory>,
    onAdd: () -> Unit,
    onArrangeClasses: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Inventory",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.headlineSmall,
        )
        if (showMenu) {
            InventoryOptionsMenu(model = model, categories = categories, onArrangeClasses = onArrangeClasses)
        }
        IconButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = "Add inventory item")
        }
    }
}

// MARK: - Empty states

@Composable
private fun NoInventoryYet(onTrack: () -> Unit) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("No inventory yet", style = MaterialTheme.typography.titleSmall)
            Text(
                "Track a substance to see how much you have left as you log doses.",
                style = MaterialTheme.typography.bodyMedium,
                color = PiruTheme.colors.secondaryLabel,
            )
            Button(onClick = onTrack) { Text("Track a substance") }
        }
    }
}

/**
 * Distinct from the "nothing tracked yet" state: here the user *has* items, they
 * are just all filtered or searched away, so the way out is to widen the query
 * rather than to add stock.
 */
@Composable
private fun NoMatchingItems(model: InventoryListModel) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("No matching items", style = MaterialTheme.typography.titleSmall)
            Text(
                "No tracked substance matches the current search and filters.",
                style = MaterialTheme.typography.bodyMedium,
                color = PiruTheme.colors.secondaryLabel,
            )
            if (model.hasActiveFilters) {
                Button(onClick = { model.clearFilters() }) { Text("Clear filters") }
            }
        }
    }
}

// MARK: - Section header

@Composable
private fun CategoryHeader(
    category: SubstanceCategory,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = if (expanded) "Collapse" else "Expand", onClick = onToggle)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            category.label().uppercase(),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = PiruTheme.colors.secondaryLabel,
        )
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = PiruTheme.colors.tertiaryLabel,
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = PiruTheme.colors.secondaryLabel,
        )
    }
}

// MARK: - Row

/**
 * A manager row, built to the app's standard row anatomy: a status dot, a
 * title over a secondary subtitle, and the trailing amount. The supply bar, when
 * the item has a baseline, sits under the text column.
 */
@Composable
private fun InventoryRow(
    item: InventoryItemEntity,
    tint: P3Color?,
    catalog: SubstanceCatalog?,
    dosesByMatchKey: Map<String, List<DoseEntryEntity>>,
    onOpen: () -> Unit,
    onMove: ((Int) -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open ${item.displayTitle(catalog)}", onClick = onOpen)
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background((tint ?: P3Color.NEUTRAL).toComposeColor()),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.displayTitle(catalog),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                rowSubtitle(item, catalog, dosesByMatchKey)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = PiruTheme.colors.secondaryLabel)
                }
            }
            if (onMove != null) {
                IconButton(onClick = { onMove(-1) }) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                }
                IconButton(onClick = { onMove(1) }) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
                }
            }
            Spacer(Modifier.width(8.dp))
            StockAmountText(item)
        }
        item.fillFraction?.let { fraction ->
            InventorySupplyBar(
                fraction = fraction,
                tint = item.stockStatus.barTint,
                status = item.stockStatus,
                modifier = Modifier.padding(start = 21.dp),
            )
        }
    }
}

/**
 * "~N doses left" when a dose size is known; for an Out item the last dose's date
 * instead, so the row does not simply repeat "Out"; otherwise nothing.
 */
private fun rowSubtitle(
    item: InventoryItemEntity,
    catalog: SubstanceCatalog?,
    dosesByMatchKey: Map<String, List<DoseEntryEntity>>,
): String? {
    if (item.stockStatus == StockStatus.OUT) {
        val last = catalog
            ?.let { InventoryMath.dosesFor(item, dosesByMatchKey, it) }
            ?.maxOfOrNull { it.timestamp.toInstant() }
            ?: return null
        return "last dose ${shortDayFormatter.format(last.atZone(ZoneId.systemDefault()))}"
    }
    return catalog?.let { InventoryMath.dosesLeft(item, it) }?.let { "~$it doses left" }
}

private val shortDayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

// MARK: - Filter bar

/**
 * The removable chips shown while a filter is applied.
 *
 * With the filter controls buried in the overflow menu, this bar *is* the
 * indicator that the list is narrowed — so it stays pinned above the rows rather
 * than scrolling away with them.
 */
@Composable
private fun InventoryFilterBar(model: InventoryListModel, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (status in model.filterStatuses.sortedBy { it.sortIndex }) {
            RemovableChip(status.displayName) { model.toggleStatus(status) }
        }
        for (category in model.filterCategories.sortedBy { it.label() }) {
            RemovableChip(category.label()) { model.toggleCategory(category) }
        }
        TextButton(onClick = { model.clearFilters() }) { Text("Clear") }
    }
}

@Composable
private fun RemovableChip(title: String, onRemove: () -> Unit) {
    AssistChip(
        onClick = onRemove,
        label = { Text(title, style = MaterialTheme.typography.bodySmall) },
        trailingIcon = {
            Icon(Icons.Filled.Clear, contentDescription = "Remove filter", modifier = Modifier.size(14.dp))
        },
    )
}

// MARK: - Options menu

/**
 * The manager's single overflow menu — everything except the one primary action.
 *
 * The ordering is Files.app's, which is what upstream copies: how the list is
 * arranged first, then how it is narrowed. Material has no submenu, so the two
 * facets open as a second popup anchored to the same button rather than as nested
 * items; that is the platform's own idiom for a menu that would otherwise be
 * thirty rows long.
 */
@Composable
private fun InventoryOptionsMenu(
    model: InventoryListModel,
    categories: List<SubstanceCategory>,
    onArrangeClasses: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    var classOpen by remember { mutableStateOf(false) }

    // True when every present class is folded, which flips the row between
    // "Collapse all" and "Expand all".
    val allCollapsed = categories.isNotEmpty() && categories.all { it in model.collapsedCategories }
    val filterCount = model.filterStatuses.size + model.filterCategories.size

    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Group by Class") },
                onClick = { model.isGrouped = !model.isGrouped },
                leadingIcon = if (model.isGrouped) {
                    { Icon(Icons.Filled.Check, contentDescription = null) }
                } else {
                    null
                },
            )
            // Only meaningful once there are sections to fold or arrange.
            if (model.isGrouped && categories.size > 1) {
                DropdownMenuItem(
                    text = { Text(if (allCollapsed) "Expand all" else "Collapse all") },
                    onClick = { model.setAllCollapsed(!allCollapsed, categories) },
                )
                DropdownMenuItem(
                    text = { Text("Arrange classes…") },
                    onClick = {
                        open = false
                        onArrangeClasses()
                    },
                )
            }

            HorizontalDivider()

            MenuLabel("Sort by")
            for (option in InventorySort.entries) {
                DropdownMenuItem(
                    text = { Text(option.displayName) },
                    onClick = { model.sort = option },
                    leadingIcon = if (model.sort == option) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }

            HorizontalDivider()

            DropdownMenuItem(
                text = { Text(if (filterCount > 0) "Filter ($filterCount)" else "Filter") },
                onClick = {
                    open = false
                    filterOpen = true
                },
            )
        }

        DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
            MenuLabel("Status")
            for (status in StockStatus.entries) {
                DropdownMenuItem(
                    text = { Text(status.displayName) },
                    onClick = { model.toggleStatus(status) },
                    leadingIcon = {
                        Icon(
                            if (status in model.filterStatuses) Icons.Filled.Check else status.icon,
                            contentDescription = null,
                        )
                    },
                )
            }
            if (categories.size > 1) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Class…") },
                    onClick = {
                        filterOpen = false
                        classOpen = true
                    },
                )
            }
            if (model.hasActiveFilters) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Clear filters") },
                    onClick = { model.clearFilters() },
                )
            }
        }

        DropdownMenu(expanded = classOpen, onDismissRequest = { classOpen = false }) {
            for (category in categories) {
                DropdownMenuItem(
                    text = { Text(category.label()) },
                    onClick = { model.toggleCategory(category) },
                    leadingIcon = if (category in model.filterCategories) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** A non-interactive heading inside a menu, standing in for the `Section` grouping Material lacks. */
@Composable
private fun MenuLabel(text: String) {
    Text(
        text.uppercase(),
        modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = PiruTheme.colors.tertiaryLabel,
    )
}

// MARK: - Class arrangement

/**
 * Lets the user put the class sections in their own order.
 *
 * A separate screen rather than in-place moving, for the same reason upstream
 * gives: the list reorders rows but has no notion of moving a whole section, and
 * section order is what this is about.
 *
 * Upstream drags with permanent grips; Compose has no built-in list reorder, so
 * each row carries an up and a down. The commit is the same either way — every
 * move writes the whole order straight to the model, so the list behind keeps up.
 */
@Composable
internal fun InventoryClassOrderScreen(
    model: InventoryListModel,
    categories: List<SubstanceCategory>,
    modifier: Modifier = Modifier,
    onDone: () -> Unit = {},
) {
    var ordered by remember { mutableStateOf(categories) }
    LaunchedEffect(categories) { ordered = categories }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Arrange classes", modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            TextButton(
                onClick = {
                    model.resetCategoryOrder()
                    ordered = categories
                },
                enabled = model.hasCustomCategoryOrder,
            ) { Text("Reset") }
            IconButton(onClick = onDone) { Icon(Icons.Filled.Check, contentDescription = "Done") }
        }

        Text(
            "Drag to set the order class sections appear in. Reset to let the current sort decide.",
            style = MaterialTheme.typography.bodyMedium,
            color = PiruTheme.colors.secondaryLabel,
        )

        LazyColumn(contentPadding = PaddingValues(bottom = FAB_CLEARANCE)) {
            items(ordered, key = { "c:${it.wireValue}" }) { category ->
                val index = ordered.indexOf(category)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(category.label(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    IconButton(
                        onClick = { ordered = ordered.moved(index, index - 1).also(model::applyCategoryOrder) },
                        enabled = index > 0,
                    ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up") }
                    IconButton(
                        onClick = { ordered = ordered.moved(index, index + 1).also(model::applyCategoryOrder) },
                        enabled = index < ordered.lastIndex,
                    ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down") }
                }
            }
        }
    }
}

/** This list with the entry at [from] moved to [to]; unchanged when either is out of range. */
private fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    val copy = toMutableList()
    copy.add(to, copy.removeAt(from))
    return copy
}
