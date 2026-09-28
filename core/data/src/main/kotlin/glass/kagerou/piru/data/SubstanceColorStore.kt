package glass.kagerou.piru.data

import glass.kagerou.piru.data.entity.SubstanceColorEntity
import glass.kagerou.piru.engine.SubstanceCatalog
import glass.kagerou.piru.model.LegacyColorImport
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.model.SubstanceCategory
import glass.kagerou.piru.model.SubstanceColorGenerator

/**
 * Keeping the colour rows in step with the palette.
 *
 * Ported from `SubstanceColorStore`. This is the write side of
 * [SubstancePalette], which only reads.
 *
 * ## Three kinds of row, and they are not interchangeable
 * The store holds three states and the whole file is about not confusing them:
 *
 * - **generated** — [SubstanceColorEntity.usesDefault] set, no legacy hex. The
 *   row is a cache of what the generator produces for this substance today, and
 *   it is meant to be rewritten when the generator changes.
 * - **custom** — `usesDefault` clear. The user picked this. **Nothing here
 *   overwrites it**, ever.
 * - **legacy** — a non-empty `hexColor`, from a build that predated the
 *   generated palette. It is undecided: it has a colour the user may well have
 *   chosen, and a generator that now says something else. Only a person can say
 *   which, which is why [resolveLegacy] exists and why nothing calls it
 *   automatically.
 *
 * The failure this prevents is a migration that silently repaints every colour a
 * user had customised, and its mirror image — one that refuses to ever update a
 * colour again because it cannot tell the two apart.
 */
class SubstanceColorStore(
    private val database: PiruDatabase,
    private val catalog: SubstanceCatalog,
) {

    /**
     * The generator's colour for [name] — the substance's class colour, which is
     * what "default" means everywhere in this feature.
     *
     * Seeded by the **PSID family** where the catalog resolves one, so a racemate
     * and its enantiomers come out the same colour, and by the lowercased name
     * otherwise, so a substance the catalog does not carry is still stable for as
     * long as it is spelled the same way.
     */
    suspend fun defaultTint(name: String): P3Color {
        val substance = catalog.lookup(name)
            ?: return SubstanceColorGenerator.displayP3(SubstanceCategory.OTHER, name.lowercase())
        return SubstanceColorGenerator.displayP3(
            substance.category,
            substance.substanceUID ?: substance.name.lowercase(),
        )
    }

    /**
     * Give [name] a row if it has none, and leave it alone if it has one.
     *
     * Returns the row that exists afterwards, or null for a blank name.
     *
     * The check is on the **trimmed** name, so a stray space does not mint a
     * second row for the same substance — which the unique index would reject
     * anyway, but as an exception rather than a no-op.
     */
    suspend fun ensureRow(name: String): SubstanceColorEntity? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        database.substanceColorDao().forSubstance(trimmed)?.let { return it }
        val tint = defaultTint(trimmed)
        database.substanceColorDao().insertIgnoringDuplicates(
            SubstanceColorEntity(substance = trimmed, hexColor = "", red = tint.red, green = tint.green, blue = tint.blue, usesDefault = true),
        )
        return database.substanceColorDao().forSubstance(trimmed)
    }

    /**
     * Record [tint] as this substance's colour.
     *
     * [usesDefault] is what the caller is asserting: true means "this is the
     * class colour" and leaves the row eligible for [refreshDefaults], false
     * means "the user chose this" and exempts it forever.
     */
    suspend fun apply(name: String, tint: P3Color, usesDefault: Boolean) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        database.substanceColorDao().setColor(
            name = trimmed,
            red = tint.red,
            green = tint.green,
            blue = tint.blue,
            usesDefault = usesDefault,
        )
    }

    /** Put every substance back on its class colour, including the ones the user had customised. */
    suspend fun resetAll() {
        val dao = database.substanceColorDao()
        for (row in dao.all()) {
            if (row.usesDefault && !row.isLegacy) continue
            val tint = defaultTint(row.substance)
            dao.update(row.copy(hexColor = "", red = tint.red, green = tint.green, blue = tint.blue, usesDefault = true))
        }
    }

    /**
     * Recompute the generated rows so they follow the current generator.
     *
     * ## What it deliberately leaves alone
     * Custom rows, because the user chose them. Legacy rows, because they are the
     * undecided ones and [resolveLegacy] is the only thing that may settle them —
     * a refresh that claimed a legacy row would answer on the user's behalf a
     * question that has not been asked yet.
     *
     * Writes only when something actually changed, so the common case — a
     * generator that has not moved since last launch — costs one read.
     */
    suspend fun refreshDefaults(): Int {
        val dao = database.substanceColorDao()
        var changed = 0
        for (row in dao.all()) {
            if (!row.usesDefault || row.isLegacy) continue
            val tint = defaultTint(row.substance)
            if (row.red == tint.red && row.green == tint.green && row.blue == tint.blue) continue
            dao.update(row.copy(red = tint.red, green = tint.green, blue = tint.blue))
            changed++
        }
        return changed
    }

    /**
     * Mint a row for every substance the user has ever logged or tracked.
     *
     * ## First name wins
     * [names] is read in a fixed order — doses, then inventory, then daily items —
     * and the first spelling of a substance to arrive claims the row. That order
     * is the only tiebreak there is, and it is deliberate: the dose log is the
     * largest and most authoritative record of what the user calls things, so it
     * should name the row, not a daily-item template they set up once.
     *
     * Deduplication is case-insensitive, because "caffeine" and "Caffeine" are
     * one substance and the unique index would refuse the second row anyway.
     */
    suspend fun mintMissingRows(names: Collection<String>): Int {
        val dao = database.substanceColorDao()
        val known = dao.all().mapTo(HashSet()) { it.substance.lowercase() }
        var minted = 0
        for (raw in names) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            if (!known.add(trimmed.lowercase())) continue
            val tint = defaultTint(trimmed)
            dao.insertIgnoringDuplicates(
                SubstanceColorEntity(substance = trimmed, hexColor = "", red = tint.red, green = tint.green, blue = tint.blue, usesDefault = true),
            )
            minted++
        }
        return minted
    }

    /** How many rows the colour-update notice still has to resolve. */
    suspend fun legacyRowCount(): Int = database.substanceColorDao().legacyRowCount()

    /**
     * Settle every legacy row — the one migration decision that needs a person.
     *
     * @param adoptClassColors what the user chose when asked whether to take the
     *   new class colours or keep the look they already had.
     *   - **true**: the row becomes generated, and the class colour replaces the
     *     old hex. The substance joins the palette properly and will follow it
     *     from here on.
     *   - **false**: the old hex is converted to Display P3 and frozen as a
     *     **custom** colour. The appearance the user had is kept exactly, at the
     *     cost of never following the class palette again — which is the honest
     *     trade, because the alternative is repainting something they chose.
     *
     * Either way `hexColor` is cleared, which is what makes the row decided: the
     * notice stops offering, and [refreshDefaults] may now touch it (if adopting)
     * or never touch it (if not).
     */
    suspend fun resolveLegacy(adoptClassColors: Boolean): Int {
        val dao = database.substanceColorDao()
        val legacy = dao.legacyRows()
        for (row in legacy) {
            val tint = if (adoptClassColors) defaultTint(row.substance) else LegacyColorImport.p3(row.hexColor)
            dao.update(
                row.copy(
                    hexColor = "",
                    red = tint.red,
                    green = tint.green,
                    blue = tint.blue,
                    usesDefault = adoptClassColors,
                ),
            )
        }
        return legacy.size
    }
}
