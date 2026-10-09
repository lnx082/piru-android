package glass.kagerou.piru.ui.tools

import glass.kagerou.piru.engine.SubstanceCatalog
import glass.kagerou.piru.model.RouteOfAdministration
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Which depot curve a dose belongs to.
 *
 * ## The two "no"s that must stay distinguishable
 * A dose can fail to get a curve because it **is not a depot** or because its **family has no modelable ester**. The
 * first is a statement about the dose and the second about the database, and a screen that collapsed them would draw
 * nothing in both cases without saying why. [DepotAnalyte.analyteKeyFor] returns null for both, deliberately — the
 * caller compares against [DepotAnalyte.isDepot] when it needs to tell a reader which happened — so both are asserted
 * here separately.
 *
 * ## The load-bearing filter
 * `analyteKeyOf` takes the first **modelable** ester, not the first ester. A family can carry catalog-only entries
 * that ship no validated curve, and a curve drawn from one would be a guess wearing a confidence label. The fixture
 * puts a non-modelable ester **first** so an implementation using `firstOrNull()` alone fails.
 */
class DepotAnalyteTest {

    /**
     * A catalogue that answers about esters out of [rows], the same rows the index is built from.
     *
     * Both halves of the rule read this one source, which is what the production wiring does too: the catalogue's
     * projection and the app's index are filled from the same `ester_pk` table. A fixture with an empty catalogue and a
     * full index (my first version) tested nothing, because the depot question was correctly answered `false`.
     */
    private fun catalog(
        rows: List<EsterPKRecord> = emptyList(),
        uids: Map<String, String> = emptyMap(),
    ) = object : SubstanceCatalog {
        private val uidByName = uids.mapKeys { it.key.lowercase() }
        override fun lookup(name: String): glass.kagerou.piru.model.Substance? = null
        override fun substanceUID(name: String): String? = uidByName[name.lowercase()]

        /** The engine's two-field projection: the label `isEster` matches a salt form against, and the terminal rate. */
        override fun esters(parentUID: String): List<glass.kagerou.piru.engine.EsterRecord> =
            rows.filter { it.parentUID == parentUID }
                .map {
                    glass.kagerou.piru.engine.EsterRecord(
                        label = it.label,
                        terminalRatePerDay = it.parameters?.k1,
                    )
                }

        override fun productDuration(name: String): glass.kagerou.piru.model.DurationProfile? = null
    }

    /**
     * One ester row.
     *
     * `parameters == null` is exactly what `isModelable` reads, so the fixture controls the flag through the field the
     * production code reads rather than through a second argument that could disagree with it. The parameters
     * themselves are arbitrary non-null numbers: no case here fits a curve, only asks whether one could be.
     */
    private fun ester(
        id: String,
        analyte: String,
        parentUID: String,
        modelable: Boolean,
    ) = EsterPKRecord(
        esterID = id,
        analyte = analyte,
        parent = "Parent",
        parentUID = parentUID,
        // The **label**, because that is what `isEster` matches a dose's salt form against: the real table's labels
        // are `Cypionate`, `Enanthate`, `Undecylate`, and the ids (`estradiol_cypionate`) are a different field. The
        // fixture names the label after the id's suffix so the two cannot drift apart in a reader's head.
        label = id.substringAfterLast('_').replaceFirstChar { it.uppercase() },
        parameters = if (modelable) {
            glass.kagerou.piru.engine.PKModelDepot.DepotParameters(d = 1.0, k1 = 0.1, k2 = 0.2, k3 = 0.3)
        } else {
            null
        },
        confidence = if (modelable) "high" else "none",
        provenance = "test",
        // Required by the record and read by nothing under test: the routes a product may be given by, and the
        // caution text. Left empty rather than filled with invented text, so a case that started depending on either
        // would fail here rather than quietly pass.
        routes = emptyList(),
        caution = null,
    )

    /**
     * The index the production code builds from the catalog's rows, constructed here from the map it wraps.
     *
     * Takes a **list** rather than a vararg: each case names its rows once and hands the same list to both the
     * catalogue and the index, and a `vararg` cannot take a list without a spread at every call.
     */
    private fun index(rows: List<EsterPKRecord>) = EsterPKIndex(rows.associateBy { it.esterID })

    // MARK: - The depot question

    /** A release form of `DEP` is a depot on any route, which is the first early return. */
    @Test
    fun `a depot release form is a depot`() {
        DepotAnalyte.isDepot(
            substance = "Estradiol",
            route = RouteOfAdministration.ORAL,
            releaseForm = "DEP",
            saltForm = null,
            substanceUID = null,
            catalog = catalog(),
        ) shouldBe true
    }

    /**
     * An oral dose of an ester is **not** a depot.
     *
     * The case that stops a months-long projection being drawn for a tablet: estradiol valerate taken orally is the
     * same substance as the injection, and only the route tells them apart.
     */
    @Test
    fun `an oral dose of an ester is not a depot`() {
        DepotAnalyte.isDepot(
            substance = "Estradiol Valerate",
            route = RouteOfAdministration.ORAL,
            releaseForm = null,
            saltForm = null,
            substanceUID = "PSID-ESTRADIOL",
            catalog = catalog(uids = mapOf("Estradiol Valerate" to "PSID-ESTRADIOL")),
        ) shouldBe false
    }

    // MARK: - The analyte question

    /**
     * An intramuscular ester dose resolves to its family's analyte.
     *
     * The ordinary positive case, and the one the entry screen's card depends on.
     */
    @Test
    fun `an intramuscular ester resolves to its analyte`() {
        val rows = listOf(ester("estradiol_cypionate", "estradiol", "PSID-ESTRADIOL", modelable = true))
        DepotAnalyte.analyteKeyFor(
            substance = "Estradiol Cypionate",
            route = RouteOfAdministration.INTRAMUSCULAR,
            releaseForm = null,
            // `Cypionate`, not null: an intramuscular dose counts as a depot only when its salt form names one of the
            // family's esters. My first version passed null and got null back, correctly.
            saltForm = "Cypionate",
            substanceUID = "PSID-ESTRADIOL",
            catalog = catalog(rows),
            esters = index(rows),
        ) shouldBe "estradiol"
    }

    /**
     * A **non-modelable** ester listed first does not supply the analyte.
     *
     * The filter this whole object exists to get right. The fixture's first row has no parameters, so `firstOrNull()`
     * without the `isModelable` predicate returns its analyte — which the fixture makes different, so the wrong
     * implementation returns `"wrong"` rather than `"estradiol"`.
     */
    @Test
    fun `a catalog-only ester listed first does not supply the analyte`() {
        val esters = index(
            listOf(
                ester("aaa_catalog_only", "wrong", "PSID-ESTRADIOL", modelable = false),
                ester("estradiol_cypionate", "estradiol", "PSID-ESTRADIOL", modelable = true),
            ),
        )
        DepotAnalyte.analyteKeyOf("PSID-ESTRADIOL", esters) shouldBe "estradiol"
    }

    /** A family whose esters are **all** catalog-only has no analyte, so no curve is offered. */
    @Test
    fun `a family with only catalog-only esters has no analyte`() {
        val esters = index(
            listOf(
                ester("aaa_catalog_only", "estradiol", "PSID-ESTRADIOL", modelable = false),
                ester("bbb_catalog_only", "estradiol", "PSID-ESTRADIOL", modelable = false),
            ),
        )
        DepotAnalyte.analyteKeyOf("PSID-ESTRADIOL", esters) shouldBe null
    }

    /** A family the database does not carry has no analyte. */
    @Test
    fun `an unknown family has no analyte`() {
        val esters = index(listOf(ester("estradiol_cypionate", "estradiol", "PSID-ESTRADIOL", modelable = true)))
        DepotAnalyte.analyteKeyOf("PSID-UNKNOWN", esters) shouldBe null
        DepotAnalyte.analyteKeyOf(null, esters) shouldBe null
    }

    /**
     * A **non-depot** dose resolves to nothing even when its family has curves.
     *
     * The ordering of the two questions, asserted: the same family, the same esters, and only the route differs. An
     * implementation that resolved the analyte first would return `"estradiol"` here and draw an injection curve for
     * an oral tablet.
     */
    @Test
    fun `a non-depot dose resolves to nothing even with curves available`() {
        val rows = listOf(ester("estradiol_cypionate", "estradiol", "PSID-ESTRADIOL", modelable = true))
        DepotAnalyte.analyteKeyFor(
            substance = "Estradiol Valerate",
            route = RouteOfAdministration.ORAL,
            releaseForm = null,
            // A **valid** ester label on a route that cannot be a depot: so this case fails for the route alone, not
            // because the salt form was absent. That is what makes it a test of the ordering.
            saltForm = "Cypionate",
            substanceUID = "PSID-ESTRADIOL",
            catalog = catalog(rows),
            esters = index(rows),
        ) shouldBe null
    }

    /** The UID may come from the dose or from the catalogue's lookup of its logged name. */
    @Test
    fun `the uid is taken from the dose or the catalogue`() {
        val rows = listOf(ester("estradiol_cypionate", "estradiol", "PSID-ESTRADIOL", modelable = true))
        val esters = index(rows)
        val byLookup = catalog(rows, uids = mapOf("Estradiol Cypionate" to "PSID-ESTRADIOL"))

        // No UID on the dose: the catalogue resolves it by name. The salt form is what makes it a depot.
        DepotAnalyte.analyteKeyFor(
            substance = "Estradiol Cypionate",
            route = RouteOfAdministration.INTRAMUSCULAR,
            releaseForm = null,
            saltForm = "Cypionate",
            substanceUID = null,
            catalog = byLookup,
            esters = esters,
        ) shouldBe "estradiol"

        // A UID on the dose wins over the catalogue, which is what lets a user's own relabel keep working.
        DepotAnalyte.analyteKeyFor(
            substance = "My Own Name For It",
            route = RouteOfAdministration.INTRAMUSCULAR,
            releaseForm = "DEP",
            saltForm = null,
            substanceUID = "PSID-ESTRADIOL",
            catalog = catalog(rows),
            esters = esters,
        ) shouldBe "estradiol"
    }

    /** A subtype route is a depot when the release form says so, which the `DEP` early return covers. */
    @Test
    fun `a subcutaneous depot resolves`() {
        val rows = listOf(ester("testosterone_enanthate", "testosterone", "PSID-TESTOSTERONE", modelable = true))
        DepotAnalyte.analyteKeyFor(
            substance = "Testosterone Enanthate",
            route = RouteOfAdministration.SUBCUTANEOUS,
            releaseForm = "DEP",
            saltForm = null,
            substanceUID = "PSID-TESTOSTERONE",
            catalog = catalog(rows),
            esters = index(rows),
        ) shouldBe "testosterone"
    }
}
