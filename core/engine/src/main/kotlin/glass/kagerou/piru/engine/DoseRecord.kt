package glass.kagerou.piru.engine

import glass.kagerou.piru.model.BaseReleaseForm
import glass.kagerou.piru.model.DurationProfile
import glass.kagerou.piru.model.RouteOfAdministration
import java.time.Instant

/**
 * One logged dose, as the calculation engine sees it.
 *
 * The engine's view of upstream's `DoseEntry` — the stored SwiftData `@Model` is
 * a persistence row with relations and formatting state the math never touches.
 * What is here is the fields the curve and body-load paths actually read.
 */
data class DoseRecord(
    /** The name the dose was logged under — a canonical name, an alias, or a brand. */
    val substance: String,
    val amount: Double,
    val unit: String,
    val route: RouteOfAdministration,
    val timestamp: Instant,

    /**
     * A record with no number: substance, route and time, and nothing else.
     *
     * Every numeric engine leaves such a dose out, and the surfaces that stage and
     * edit it accept it without one: an unmeasured dose is a record, not a zero.
     */
    val isUnknownDose: Boolean = false,

    /**
     * The amount is an estimate rather than a measurement.
     *
     * Carried on the engine's own record because the report prints it: TripReport's readout and the journal's
     * both mark an estimate with ~, and a dose that reached the report without this flag would print as though it
     * had been measured — the one place a reader cannot check the number against the app.
     */
    val isApproximate: Boolean = false,

    /** The PSID release-form code (`"IR"`, `"XR"`, `"DEP"`), or null for the unspecified product. */
    val releaseForm: String? = null,

    /** The brand logged, when the user picked one. Titles the dose's curve and can carry its own duration envelope. */
    val productName: String? = null,

    val saltForm: String? = null,
    val isomer: String? = null,

    /**
     * The PSID family of [substance] as stored on the entry, when it was captured
     * at log time. Null falls back to a catalog lookup by name.
     */
    val substanceUID: String? = null,
) {

    /**
     * Whether this dose names a pharmaceutical form whose kinetics the app
     * deliberately does not model — any release form other than the standard one.
     *
     * Extended-release, depot and transdermal systems are either complicated
     * (Concerta's OROS is zero-order ascending; Ritalin LA is bimodal beads; Jornay
     * PM is delayed-onset overnight) or sensitive (opioid ER, depot antipsychotics),
     * and no source we carry holds a duration for any of them. Authoring one would
     * mean translating an FDA label's PK into our subjective phase ladder — our
     * interpretation wearing the label's authority, on exactly the drugs where
     * being wrong costs the most.
     *
     * So the app declines. It says *what* the dose was ([productName]) and *when*
     * it was taken (a timestamp marker), and draws no curve — rather than answering
     * with the base form's, which is what every fallback would otherwise do.
     *
     * **`IR` does not count.** Immediate release *is* the base form's kinetics —
     * that is what "immediate release" means, and the base ladder every source
     * publishes is measured on it. Treating it as unmodeled meant "Adderall IR"
     * drew no curve while a bare "Adderall" drew one, for the same dose of the same
     * drug; a tester reported exactly that. A bare "Adderall"/"Ritalin" is the
     * unspecified form — the PSID `0` sentinel — and likewise keeps its curve.
     */
    val namesUnmodeledForm: Boolean
        get() = !BaseReleaseForm.contains(releaseForm)

    /**
     * The authored duration-of-effect envelope this dose draws when it names an
     * extended-release *product* we model per-product ("Concerta" → ~12 h,
     * "Adderall XR" → ~11 h). Null for a plain dose, or an unmodeled form with no
     * authored envelope.
     */
    fun productDuration(catalog: SubstanceCatalog): DurationProfile? {
        val name = productName
        if (name.isNullOrEmpty()) return null
        return catalog.productDuration(name)
    }

    /**
     * Whether this dose draws no acute curve at all: it names a form the base
     * ladder doesn't describe ([namesUnmodeledForm]) *and* has no per-product
     * envelope to draw instead. A named ER product with an authored duration is
     * modeled after all, so it draws its own curve rather than a bare marker.
     */
    fun drawsNoAcuteCurve(catalog: SubstanceCatalog): Boolean =
        namesUnmodeledForm && productDuration(catalog) == null
}
