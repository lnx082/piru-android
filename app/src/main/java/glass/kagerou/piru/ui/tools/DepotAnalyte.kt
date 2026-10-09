package glass.kagerou.piru.ui.tools

import glass.kagerou.piru.engine.PKResolver
import glass.kagerou.piru.engine.SubstanceCatalog
import glass.kagerou.piru.model.RouteOfAdministration

/**
 * Which depot curve, if any, a logged dose belongs to.
 *
 * Ported from `DepotLevels.analyte(for:)`. A depot dose's detail screen shows the **injection-levels curve its dose
 * lives on** — the same curve the Injection Levels tool draws, from the same log, labs and calibration preferences, so
 * the two can never disagree about what the user's levels are.
 *
 * ## Two steps, and each is a way for this to be wrong
 *
 * 1. **Is it a depot at all?** [isDepot] answers from the release form and route. A non-depot dose has no curve to
 *    belong to, and drawing one would invent a months-long projection for a tablet.
 * 2. **Which analyte do that family's esters name?** Estradiol esters name `estradiol`; testosterone esters name
 *    `testosterone`. The **first modelable** ester's analyte is taken, and *modelable* is load-bearing: a family can
 *    carry catalog-only esters that ship no validated curve, and a curve drawn from one would be a guess wearing a
 *    confidence label.
 *
 * ## Why the ester table is a parameter
 * The rows live in `EsterPKIndex`, which the app loads once per process off the installed catalogue — a 18 MB asset
 * and a hash verification, so a screen's effect cannot afford it inline and a JVM test cannot have it at all. Taking
 * the index in keeps the rule testable and makes the cost a caller's decision, which is where it belongs.
 *
 * The rule lives here rather than in the screen because each step has a distinct "no": a screen that collapsed them
 * would offer a curve to a tablet, or to an ester with no parameters, and both failures would look like a drawing
 * rather than a decision.
 */
internal object DepotAnalyte {

    /**
     * The analyte key for a dose, or null when there is no curve to draw.
     *
     * The order is deliberate: the depot question comes **first**, so a plain oral tablet does not even reach the
     * ester table. Reversing them would be the same answer for less work only if `estersOf` were free, and it is not.
     */
    fun analyteKeyFor(
        substance: String,
        route: RouteOfAdministration,
        releaseForm: String?,
        saltForm: String?,
        substanceUID: String?,
        catalog: SubstanceCatalog,
        esters: EsterPKIndex,
    ): String? {
        if (!isDepot(substance, route, releaseForm, saltForm, substanceUID, catalog)) return null
        val uid = substanceUID ?: catalog.substanceUID(substance) ?: return null
        return analyteKeyOf(uid, esters)
    }

    /**
     * The analyte whose curves belong to family [uid], or null when the database draws none.
     *
     * Exposed because the library's hormone history asks it **without** a dose: a substance page shows its ester curves
     * whether or not anything has been logged, so the rule has a second caller with no entry to hand.
     */
    fun analyteKeyOf(uid: String?, esters: EsterPKIndex): String? =
        uid?.let { parent ->
            // The first **modelable** ester, not simply the first: see this object's own note on why a catalog-only
            // ester must not supply the analyte.
            esters.forParentUID(parent).firstOrNull { it.isModelable }?.analyte
        }

    /**
     * Whether this dose is a depot — the first of the two questions.
     *
     * Separate so a caller can ask it **before** loading the ester table, which is what the entry screen does: the
     * table costs a catalogue read, and a non-depot dose should not pay for one.
     */
    fun isDepot(
        substance: String,
        route: RouteOfAdministration,
        releaseForm: String?,
        saltForm: String?,
        substanceUID: String?,
        catalog: SubstanceCatalog,
    ): Boolean = PKResolver.isDepot(
        entry = glass.kagerou.piru.engine.DoseRecord(
            substance = substance,
            amount = 0.0,
            unit = "mg",
            route = route,
            releaseForm = releaseForm,
            saltForm = saltForm,
            substanceUID = substanceUID,
            // The depot test reads the release form, the route and the ester index — never the clock — so this instant
            // is arbitrary. Written out rather than defaulted, because a default would read as "this matters".
            timestamp = java.time.Instant.EPOCH,
        ),
        catalog = catalog,
    )
}
