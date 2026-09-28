package glass.kagerou.piru.data

/**
 * Whether the store holds anything the user would miss.
 *
 * Ported from `StoreRecovery.countUserRows`. See [UserDataDao] for why the four
 * tables it counts are the definition of "user data" rather than a sample of it.
 */
object UserDataCounter {

    /**
     * Rows the user authored, across the four tables that count.
     *
     * Zero means the store is empty even if other tables hold rows — a tolerance
     * cache and a profile row are not content.
     */
    suspend fun userRows(db: PiruDatabase): Long {
        val dao = db.userDataDao()
        return dao.doseEntries() + dao.dailyDoseItems() + dao.favorites() + dao.substanceColors()
    }
}
