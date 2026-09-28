package glass.kagerou.piru.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import glass.kagerou.piru.data.SubstanceIdentity
import glass.kagerou.piru.model.RouteOfAdministration
import java.util.Date

/**
 * A curated quick-log dose chip: one dose *measurement* (amount plus unit) kept
 * as a one-tap shortcut for a given substance and route.
 *
 * Ported from `Shared/Models/QuickLogDose.swift`.
 *
 * Each chip is explicit, removable and reorderable. The list is seeded once from
 * history, then maintained as the user logs — a freshly logged dose is added, or
 * floated to the top, capped per (substance, route) group.
 *
 * Ordering within a group is driven by [sortOrder] ascending. With the "keep a
 * fixed order" preference off (the default), logging a dose rewrites its
 * [sortOrder] to the front; with it on, the order only changes when the user
 * reorders manually. [lastUsedAt] drives least-recently-used eviction once a
 * group reaches [PER_GROUP_LIMIT].
 *
 * ## Identity
 * The iOS model carries no `id` and no unique constraint; a chip is identified by
 * its [key] and matched case-insensitively on [substance]. The index here is on
 * the (substance, route) group, which is how every read is scoped.
 */
@Entity(
    tableName = "quick_log_doses",
    indices = [Index(value = ["substance", "route"])],
)
data class QuickLogDoseEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "row_id")
    val rowId: Long = 0,

    /** Substance name as logged (matched case-insensitively against history). */
    @ColumnInfo(name = "substance")
    val substance: String,

    /** Route of administration, persisted by wire value. */
    @ColumnInfo(name = "route", defaultValue = "oral")
    val route: RouteOfAdministration = RouteOfAdministration.ORAL,

    /** Dose amount in [unit]. */
    @ColumnInfo(name = "amount")
    val amount: Double,

    /** Unit of measure for [amount]. */
    @ColumnInfo(name = "unit")
    val unit: String,

    /** Display order within the (substance, route) group, ascending. */
    @ColumnInfo(name = "sort_order")
    val sortOrder: Double,

    /** When this dose was last logged — drives float-to-top and LRU eviction. */
    @ColumnInfo(name = "last_used_at")
    val lastUsedAt: Date = Date(),

    /**
     * By-volume detail captured from the logged drink, so the chip can render
     * "🍺 IPA · 330 mL · 6% · 16 g" and re-stage the full drink rather than a
     * bare gram amount. All null for ordinary mass doses.
     */
    @ColumnInfo(name = "volume_ml")
    val volumeML: Double? = null,

    @ColumnInfo(name = "abv")
    val abv: Double? = null,

    @ColumnInfo(name = "drink_name")
    val drinkName: String? = null,

    @ColumnInfo(name = "emoji")
    val emoji: String? = null,

    /**
     * The PSID identity a chip inherits from the dose it was minted for, so
     * recents split and merge on substance identity rather than a name — a
     * Concerta chip (Methylphenidate·XR) and a Ritalin IR chip
     * (Methylphenidate·IR) stay distinct even though [substance] is the same
     * canonical string. All null for a chip seeded from a pre-PSID history row,
     * which keys by lowercased name as before.
     */
    @ColumnInfo(name = "substance_uid")
    val substanceUID: String? = null,

    @ColumnInfo(name = "isomer")
    val isomer: String? = null,

    @ColumnInfo(name = "release_form")
    val releaseForm: String? = null,

    @ColumnInfo(name = "salt_form")
    val saltForm: String? = null,

    /**
     * The user's own word for this dose ("Concerta", "Vyvanse"), carried so a
     * re-staged chip logs the product it named rather than the canonical family.
     * Not part of the identity key: "Concerta" and "Methylphenidate XR" share a
     * card.
     */
    @ColumnInfo(name = "product_name")
    val productName: String? = null,
) {
    /** This chip's substance-identity key — its card and group are grouped by this. */
    val identityKey: String
        get() = SubstanceIdentity.identityKey(
            substanceUID = substanceUID,
            substance = substance,
            isomer = isomer,
            releaseForm = releaseForm,
            saltForm = saltForm,
        )

    /** Whether this chip carries by-volume detail rather than a plain gram amount. */
    val hasDrinkDetail: Boolean
        get() = drinkName != null || volumeML != null || abv != null

    /**
     * Identity of the chip. A by-volume drink folds in its name, strength and
     * volume so distinct drinks stay distinct chips; otherwise the key is
     * `identity|route|amount|unit`.
     */
    val key: String
        get() = SubstanceIdentity.makeKey(
            substance = substance,
            route = route,
            amount = amount,
            unit = unit,
            substanceUID = substanceUID,
            isomer = isomer,
            releaseForm = releaseForm,
            saltForm = saltForm,
            volumeML = volumeML,
            abv = abv,
            drinkName = drinkName,
        )

    companion object {
        /**
         * Maximum chips kept per (substance, route) group before least-recently-used
         * eviction. Eight distinct measurements per route is plenty for one-tap reuse.
         */
        const val PER_GROUP_LIMIT: Int = 8
    }
}
