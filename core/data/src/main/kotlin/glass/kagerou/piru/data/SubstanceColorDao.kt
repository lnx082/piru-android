package glass.kagerou.piru.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import glass.kagerou.piru.data.entity.SubstanceColorEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SubstanceColorDao {

    @Query("SELECT * FROM substance_colors")
    fun observeAll(): Flow<List<SubstanceColorEntity>>

    @Query("SELECT * FROM substance_colors")
    suspend fun all(): List<SubstanceColorEntity>

    @Query("SELECT * FROM substance_colors WHERE substance = :name")
    suspend fun forSubstance(name: String): SubstanceColorEntity?

    /**
     * Recolor [substance], minting the row if it does not exist.
     *
     * SwiftData's unique-attribute semantics make a duplicate insert an upsert,
     * so the iOS callers recolor the existing instance without checking first.
     * Room raises on a unique-index conflict instead, so this reproduces the
     * upsert explicitly — a bare [insert] here would crash the moment a
     * substance's color was set twice, which is the common path.
     *
     * [Upsert] will not do: it matches on the primary key, not on the unique
     * index, so it would insert a second row for the same substance and violate
     * the index. Hence the read-then-write inside one transaction.
     */
    @Transaction
    suspend fun setColor(
        name: String,
        red: Double,
        green: Double,
        blue: Double,
        usesDefault: Boolean,
    ) {
        val existing = forSubstance(name)
        if (existing == null) {
            insert(
                SubstanceColorEntity(
                    substance = name,
                    hexColor = "",
                    red = red,
                    green = green,
                    blue = blue,
                    usesDefault = usesDefault,
                ),
            )
        } else {
            update(
                existing.copy(
                    hexColor = "",
                    red = red,
                    green = green,
                    blue = blue,
                    usesDefault = usesDefault,
                ),
            )
        }
    }

    /** Every row still carrying its pre-class-colours hex, in insertion order. */
    @Query("SELECT * FROM substance_colors WHERE hex_color != '' ORDER BY id")
    suspend fun legacyRows(): List<SubstanceColorEntity>

    /** How many rows the colour-update notice still has to resolve. */
    @Query("SELECT COUNT(*) FROM substance_colors WHERE hex_color != ''")
    suspend fun legacyRowCount(): Int

    /**
     * Insert a row for a substance that has none, and do nothing if it already
     * has one.
     *
     * `IGNORE` rather than [upsert] because the two callers that mint rows — the
     * background pass and [SubstanceColorStore.ensureRow] — are both racing an
     * existing user choice they must not overwrite, and the unique index on
     * `substance` is what settles the race.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringDuplicates(entity: SubstanceColorEntity): Long

    @Upsert
    suspend fun upsert(entity: SubstanceColorEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: SubstanceColorEntity): Long

    @Update
    suspend fun update(entity: SubstanceColorEntity)

    @Query("DELETE FROM substance_colors")
    suspend fun deleteAll()
}
