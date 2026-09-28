package glass.kagerou.piru.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import glass.kagerou.piru.data.SubstanceIdentity
import java.util.Date

/**
 * A substance the user pinned to the Favorites section.
 *
 * Ported from `Shared/Models/FavoriteSubstance.swift`.
 */
@Entity(
    tableName = "favorite_substances",
    indices = [
        // The quick-log and reorder views sort by sortOrder then createdAt
        // (reverse), so a compound index matches that exact ordering.
        Index(value = ["sort_order", "created_at"]),
    ],
)
data class FavoriteSubstanceEntity(
    /**
     * The favorited substance's name. Unique on the iOS side, so it is the
     * primary key here — one favorite per substance name.
     *
     * Note the iOS `isFavorite(_:)` helper compares **lowercased** names despite
     * this being the unique key, so two casings of one name could in principle
     * both be stored. The unique index here is on the raw value, matching the
     * iOS constraint exactly rather than tightening it: a divergence would
     * reject a row the iOS side accepts, and the two stores must round-trip.
     */
    @PrimaryKey
    @ColumnInfo(name = "substance")
    val substance: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Date = Date(),

    /** User-defined position in the Favorites section (lower = first). New favorites append at the end. */
    @ColumnInfo(name = "sort_order", defaultValue = "0")
    val sortOrder: Int = 0,

    /**
     * The PSID identity this favorite pins, so it matches the recents card of the
     * same form — a Concerta favorite (Methylphenidate·XR) highlights the
     * Concerta card, not a plain Methylphenidate one. All null for a favorite
     * added before PSID, which keys by lowercased name until a backfill resolves
     * it. [productName] keeps the user's word for the favorited product.
     */
    @ColumnInfo(name = "substance_uid")
    val substanceUID: String? = null,

    @ColumnInfo(name = "isomer")
    val isomer: String? = null,

    @ColumnInfo(name = "release_form")
    val releaseForm: String? = null,

    @ColumnInfo(name = "salt_form")
    val saltForm: String? = null,

    @ColumnInfo(name = "product_name")
    val productName: String? = null,
) {
    /** This favorite's identity key — compared against a recents card's id to decide membership. */
    val identityKey: String
        get() = SubstanceIdentity.identityKey(
            substanceUID = substanceUID,
            substance = substance,
            isomer = isomer,
            releaseForm = releaseForm,
            saltForm = saltForm,
        )
}
