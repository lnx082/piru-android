package glass.kagerou.piru.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row record holding user profile and physiology state.
 *
 * Ported from `Shared/Models/UserProfileRecord.swift`.
 *
 * Lives alongside the other user-data models so all user-authored state shares
 * one store and one backup path, rather than a separate preferences database.
 *
 * ## Single row, by convention
 * The iOS model carries no key at all — there is simply one row, found by
 * "take the first". Room needs a primary key, so this gets an auto-generated one
 * and the same convention: every reader selects the lowest [rowId] and every
 * writer updates it rather than inserting. See `UserProfileDao.current`.
 *
 * This is a single-row *profile*, not a list, so nothing should ever insert a
 * second row. A test asserts the count stays at one after a write.
 */
@Entity(tableName = "user_profile")
data class UserProfileRecordEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "row_id")
    val rowId: Long = 0,

    /**
     * Disclosure-tier wire value. The stored string is `"harm-reduction"` for the
     * tier the UI calls "Curious"; it is a **wire value and must not be renamed**,
     * or a stored profile stops resolving. The iOS reader maps the retired
     * `"pharma-nerd"` value forward, which this does too.
     */
    @ColumnInfo(name = "disclosure_tier_raw", defaultValue = "harm-reduction")
    val disclosureTierRaw: String = "harm-reduction",

    /** Body weight in kg, or null when the user has not provided one (→ population default). */
    @ColumnInfo(name = "body_weight_kg")
    val bodyWeightKg: Double? = null,

    /** Weight-source wire value, mirroring the store's `WeightSource` raw values. */
    @ColumnInfo(name = "weight_source_raw", defaultValue = "estimated")
    val weightSourceRaw: String = "estimated",

    /**
     * Whether the per-dose "had grapefruit" toggle is shown in the dose logger.
     * Off by default — it is niche, surfaced only on CYP3A4-heavy substrates. A
     * presentation preference, not a physiological fact.
     */
    @ColumnInfo(name = "grapefruit_logging_enabled", defaultValue = "0")
    val grapefruitLoggingEnabled: Boolean = false,

    /**
     * Whether the user carries an ALDH2 loss-of-function variant ("Asian flush"),
     * self-reported via the alcohol-flush question. While set, the alcohol
     * surface shows an acetaldehyde-accumulation readout — the real toxic
     * intermediate that ALDH2 clears slowly in carriers. A genuine physiological
     * flag, not a presentation preference.
     */
    @ColumnInfo(name = "aldh2_deficient", defaultValue = "0")
    val aldh2Deficient: Boolean = false,
)
