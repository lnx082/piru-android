package glass.kagerou.piru.substance

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The status flags, over the shipped catalogue.
 *
 * ## Why a table of thirteen rows is worth a test
 * Because two of its three flags are **harm-reduction findings rather than metadata**:
 * `suppresses-serotonin-synthesis` and `missold-as-mdma`. The port could answer `hasFlag(flag, id)` for any flag
 * and showed none of them, so the reading existed and no user could see it.
 *
 * The flags are also the kind of data that is easy to *lose* rather than to get wrong: the reader joins `sources`
 * and `citations` with `LEFT JOIN`, because five of the thirteen rows carry no citation, and an inner join would
 * silently drop those five — a section that looks right and is missing its most important rows.
 *
 * Counts and names were read out of `piru-substances.sqlite` before being written down.
 */
class SubstanceFlagReaderTest {

    private fun catalog(db: SubstanceDb) = DbSubstanceCatalog.open(
        db = db,
        order = db.query("SELECT slug FROM sources ORDER BY default_priority, slug")
            .mapNotNull { it.string("slug") },
        language = ContentLanguage.EN,
    )

    /**
     * MDMA carries exactly its one flag, and the note-less rows still come back.
     *
     * `suppresses-serotonin-synthesis` is the flag with no citation on some rows, which is the case the `LEFT
     * JOIN` exists for.
     */
    @Test
    fun `MDMA's flag is returned`() {
        openBundledSubstanceDb().use { db ->
            val flags = catalog(db).substanceFlags("MDMA")
            flags.map { it.flag } shouldContainExactly listOf("suppresses-serotonin-synthesis")
        }
    }

    /**
     * The three cathinones sold as MDMA each carry the flag.
     *
     * The harm-reduction case the flag exists for, and the one a user most needs before rather than after.
     */
    @Test
    fun `the cathinones sold as MDMA carry the flag`() {
        openBundledSubstanceDb().use { db ->
            val resolved = catalog(db)
            for (name in listOf("Eutylone", "N-Ethylpentylone", "Pentylone")) {
                resolved.substanceFlags(name).map { it.flag } shouldBe listOf("missold-as-mdma")
            }
        }
    }

    /**
     * A substance with no flags returns an empty list, and the card hides.
     *
     * 1676 of the 1689 substances have none, so this is the ordinary path rather than an edge case.
     */
    @Test
    fun `a substance with no flags has none`() {
        openBundledSubstanceDb().use { db ->
            val resolved = catalog(db)
            resolved.substanceFlags("Diazepam").isEmpty() shouldBe true
            resolved.substanceFlags("Not A Real Substance Name").isEmpty() shouldBe true
        }
    }

    /** The catalogue's three flags are the three the card knows how to describe. */
    @Test
    fun `the catalogue carries the three documented flags`() {
        openBundledSubstanceDb().use { db ->
            val resolved = catalog(db)
            val seen = mutableSetOf<String>()
            for (name in listOf("MDMA", "Eutylone", "Amphetamine")) {
                seen += resolved.substanceFlags(name).map { it.flag }
            }
            seen shouldBe setOf("suppresses-serotonin-synthesis", "missold-as-mdma", "model-calibrated")
        }
    }

    /**
     * The order is stable, which is what lets a reader find a known flag in the same place.
     *
     * Ordered by flag name rather than by rowid, so the same substance lists its flags the same way twice.
     */
    @Test
    fun `flags are ordered by name`() {
        openBundledSubstanceDb().use { db ->
            val resolved = catalog(db)
            // A substance with more than one flag would be the interesting case; none has one, so the property is
            // asserted over every flagged substance there is.
            for (name in listOf("MDMA", "3-MMC", "Eutylone")) {
                val flags = resolved.substanceFlags(name).map { it.flag }
                flags shouldBe flags.sorted()
            }
        }
    }

    /**
     * The flag read and the engine agree.
     *
     * `reader.hasFlag(…)` is what fills `PharmacologyParameters.suppressesSerotoninSynthesis`, which the interaction
     * engine multiplies by — so the card and the model are two readings of one table. If they disagreed, the page
     * would flag a substance the engine does not treat as flagged, or the reverse.
     *
     * Checked through `pharmacologyForLog`, which is the public route to that field. `hasFlag` is deliberately
     * not exposed on the catalogue: it takes a substance **id**, and the callers that need it already hold one.
     */
    @Test
    fun `the flag read agrees with the engine`() {
        openBundledSubstanceDb().use { db ->
            val resolved = catalog(db)
            val names = listOf("MDMA", "MDA", "Eutylone", "Amphetamine", "Diazepam")
            val parameters = resolved.pharmacologyForLog(names)
            for (name in names) {
                val listed = resolved.substanceFlags(name)
                    .any { it.flag == "suppresses-serotonin-synthesis" }
                parameters.getValue(name).suppressesSerotoninSynthesis shouldBe listed
            }
        }
    }
}
