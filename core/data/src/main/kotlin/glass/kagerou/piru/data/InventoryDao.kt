package glass.kagerou.piru.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import glass.kagerou.piru.data.entity.InventoryItemEntity
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * The tracked supplies.
 *
 * Every read is ordered by `sort_order` and then `created_at`, which is the
 * arrangement the list falls back to when nothing has been dragged: an all-zero
 * sort column means "never reordered", and creation order is the stable tiebreak
 * that keeps the list from shuffling between queries.
 */
@Dao
interface InventoryDao {

    @Query("SELECT * FROM inventory_items ORDER BY sort_order, created_at")
    fun observeAll(): Flow<List<InventoryItemEntity>>

    @Query("SELECT * FROM inventory_items ORDER BY sort_order, created_at")
    suspend fun all(): List<InventoryItemEntity>

    @Query("SELECT * FROM inventory_items WHERE id = :id")
    suspend fun byId(id: UUID): InventoryItemEntity?

    @Query("SELECT MAX(sort_order) FROM inventory_items")
    suspend fun maxSortOrder(): Int?

    /**
     * The row for one identity, or null.
     *
     * Identity is the `(substance, saltForm)` pair matched case-insensitively on
     * the name and **exactly** on the salt — a null salt form matches only another
     * null, because "the free base" and "the hydrochloride" are different
     * supplies with different masses, and folding them together would silently
     * add a salt's worth of weight to every count.
     */
    @Query(
        "SELECT * FROM inventory_items " +
            "WHERE LOWER(substance) = LOWER(:substance) " +
            "AND ((salt_form IS NULL AND :saltForm IS NULL) OR salt_form = :saltForm)",
    )
    suspend fun byIdentity(substance: String, saltForm: String?): InventoryItemEntity?

    @Upsert
    suspend fun upsert(item: InventoryItemEntity)

    @Delete
    suspend fun delete(item: InventoryItemEntity)

    @Query("DELETE FROM inventory_items")
    suspend fun deleteAll()
}
