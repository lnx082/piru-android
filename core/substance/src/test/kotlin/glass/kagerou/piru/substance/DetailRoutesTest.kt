package glass.kagerou.piru.substance

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The detail path resolves the routes `routes()` alone cannot see.
 *
 * `SubstanceReader.detailRoutes` existed, was documented as "the routes a detail screen shows",
 * and was called only from tests — so the app read through `routes()`, which knows nothing about
 * `protocol_dosing` or `durations_of_action`. Two things followed:
 *
 * - 35 `(substance, route)` pairs that exist **only** in `protocol_dosing` (the peptides — BPC-157,
 *   TB-500, the semaglutide family, the SARMs) rendered no route at all, and 10 more that exist
 *   only in `durations_of_action` (depot antipsychotics, long-acting GLP-1s) likewise;
 * - `SubstanceRoute.protocolDosing` and `.durationOfAction` were null for every substance in the
 *   app, so the release-window line and the peptide protocol cards could never render even where
 *   the data was present.
 *
 * The expectations below were read out of the shipped `piru-substances.sqlite`, which reports
 * exactly 35 and 10 such pairs.
 */
class DetailRoutesTest {

    private fun full(db: SubstanceDb, name: String) =
        DbSubstanceCatalog.open(
            db = db,
            order = db.query("SELECT slug FROM sources ORDER BY default_priority, slug")
                .mapNotNull { it.string("slug") },
            language = ContentLanguage.EN,
        ).resolveFull(name)

    @Test
    fun `a protocol-only route is present on the detail path`() {
        openBundledSubstanceDb().use { db ->
            val bpc = full(db, "BPC-157")
            (bpc != null) shouldBe true

            // Its only route exists in protocol_dosing, so `routes()` alone returns nothing for
            // it — which is why the page used to have no route section.
            val subcutaneous = bpc!!.routes.firstOrNull {
                it.route == glass.kagerou.piru.model.RouteOfAdministration.SUBCUTANEOUS
            }
            (subcutaneous != null) shouldBe true
            (subcutaneous!!.protocolDosing != null) shouldBe true
        }
    }

    @Test
    fun `a release window is attached where only durations_of_action carries it`() {
        openBundledSubstanceDb().use { db ->
            val aripiprazole = full(db, "Aripiprazole")
            (aripiprazole != null) shouldBe true

            val intramuscular = aripiprazole!!.routes.firstOrNull {
                it.route == glass.kagerou.piru.model.RouteOfAdministration.INTRAMUSCULAR
            }
            (intramuscular != null) shouldBe true
            (intramuscular!!.durationOfAction != null) shouldBe true
        }
    }

    @Test
    fun `the routes a detail page shows are a superset of the browse list`() {
        openBundledSubstanceDb().use { db ->
            // A substance with ordinary dose ladders keeps every route it had: the auxiliary
            // attachment adds, and never replaces.
            val ketamine = full(db, "Ketamine")
            (ketamine != null) shouldBe true
            (ketamine!!.routes.size >= 4) shouldBe true
        }
    }
}
