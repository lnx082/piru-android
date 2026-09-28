package glass.kagerou.piru.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import glass.kagerou.piru.data.ManualEvent
import glass.kagerou.piru.data.ManualEvents
import java.time.Instant
import java.util.UUID

/**
 * A tracked supply of one substance — how much is on hand, and how fast it goes.
 *
 * Ported from `Shared/Models/InventoryItem.swift`. This is the one entity the MVP
 * schema deliberately left out (`PiruDatabase`'s original 13 tables), added here
 * because the Inventory tool is otherwise unbuildable: there is nowhere else to
 * put a stock level, and a stock level is not derivable from the dose log.
 *
 * ## Quantity is a replay, not a column
 * [currentQuantity] is a cache of `InventoryMath.replayQuantity` over
 * [manualEvents] plus every dose logged since [trackingStart]. Treat it as a
 * memo, never as the record: it exists so a list of thirty items does not replay
 * thirty histories on every frame, and it is recomputed whenever those inputs
 * change. `InventoryMath.quantity` is the authority.
 *
 * ## Identity is (substance, saltForm), enforced in code
 * The iOS model carries no `@Attribute(.unique)` — its identity is the pair
 * `matchKey(substance) + saltForm`, matched case-insensitively, and a merge pass
 * exists precisely because duplicates can arise. A unique index here would reject
 * the rows that pass is written to fold together, so [substance] and [saltForm]
 * carry a plain index and uniqueness stays an application rule.
 *
 * [id] is therefore only a route target and a foreign key for the events, not an
 * identity claim.
 */
@Entity(
    tableName = "inventory_items",
    indices = [
        // The identity pair every lookup and the duplicate-merge pass match on.
        Index(value = ["substance", "salt_form"]),
        // The manual sort order, which the list reads on every arrangement.
        Index(value = ["sort_order"]),
    ],
)
data class InventoryItemEntity(
    /** Route target and event parent. See the class note on identity. */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: UUID = UUID.randomUUID(),

    /** The substance's canonical display name. Matched case-insensitively. */
    @ColumnInfo(name = "substance", defaultValue = "")
    val substance: String = "",

    /** The salt or ester form, matched strictly: null means the free base and only equals another null. */
    @ColumnInfo(name = "salt_form")
    val saltForm: String? = null,

    /** The unit every quantity in this row is expressed in. */
    @ColumnInfo(name = "unit", defaultValue = "mg")
    val unit: String = "mg",

    /**
     * Doses logged before this instant are not counted against the stock. Set when
     * tracking begins, so switching a substance on does not immediately consume a
     * year of history the user never meant to attribute to it.
     */
    @ColumnInfo(name = "tracking_start")
    val trackingStart: Instant,

    /**
     * Warn below this quantity. Null, zero or negative all mean "no warning" —
     * a single check rather than three, because all three arrive from a form field
     * the user cleared.
     */
    @ColumnInfo(name = "low_stock_threshold")
    val lowStockThreshold: Double? = null,

    /**
     * Whether the low-stock notification has already fired for the current dip.
     *
     * Cleared when a restock takes the quantity back over the threshold, which is
     * what makes the alert fire once per shortage rather than once per frame.
     */
    @ColumnInfo(name = "low_stock_notified", defaultValue = "0")
    val lowStockNotified: Boolean = false,

    /** The amount a full supply starts at, for the supply bar. Null or zero hides the bar. */
    @ColumnInfo(name = "baseline_quantity")
    val baselineQuantity: Double? = null,

    /** One dose's size, for "~N doses left". Null or zero hides that readout. */
    @ColumnInfo(name = "dose_size")
    val doseSize: Double? = null,

    /** Milligrams per unit for count units (a 10 mg tablet). Only meaningful when [unit] is a count. */
    @ColumnInfo(name = "unit_strength_mg")
    val unitStrengthMG: Double? = null,

    /** A cache of the replay. See the class note; `InventoryMath.quantity` is the authority. */
    @ColumnInfo(name = "current_quantity", defaultValue = "0")
    val currentQuantity: Double = 0.0,

    /**
     * The restock history as JSON, so the row round-trips through the same backup
     * payload the iOS store writes. Prefer reading and writing [manualEvents].
     */
    @ColumnInfo(name = "restocks_json", defaultValue = "")
    val restocksJson: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Instant,

    /** Manual sort position. All zero means never reordered, which falls back to the automatic arrangement. */
    @ColumnInfo(name = "sort_order", defaultValue = "0")
    val sortOrder: Int = 0,
) {
    /** The restock history, decoded from [restocksJson]. Empty for a malformed or absent blob. */
    val manualEvents: List<ManualEvent>
        get() = ManualEvents.decode(restocksJson)

    /** This item with [events] as its history. */
    fun withManualEvents(events: List<ManualEvent>): InventoryItemEntity =
        copy(restocksJson = ManualEvents.encode(events))
}
