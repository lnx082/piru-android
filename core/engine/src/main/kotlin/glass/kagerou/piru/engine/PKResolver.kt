package glass.kagerou.piru.engine

import glass.kagerou.piru.model.DurationProfile
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.Substance
import kotlin.math.ln

/**
 * Resolves the one-compartment oral PK parameters a dose needs — the elimination
 * half-life and the `(ke, ka)` rate constants — from a substance model, its
 * aliases, and an acute duration profile.
 *
 * Ported from `Piru/Data/Pharmacology/PKResolver.swift`.
 *
 * This is the single home for half-life fallback and `ka`-from-time-to-peak
 * resolution. Never re-derive either at a call site — the body-load readout, the
 * two PK tools and the timeline's dose-state builder all read through here so they
 * cannot disagree.
 *
 * Upstream is an enum of `static` functions on the main actor, because the
 * substance and duration models it reads are main-actor isolated. That constraint
 * does not exist here: the catalog arrives as a parameter, so every function is
 * pure and callable from anywhere. The output is plain scalars either way, which
 * is what lets a body-load replay resolve a substance's parameters once and then
 * reuse the `(ke, ka)` pair across an off-thread per-day walk.
 */
object PKResolver {

    /**
     * A depot dose that must not carry a depot heuristic below this, so a bad match
     * can never fabricate a plausible-looking short half-life.
     *
     * 21 days spans the long injectable esters (undecylate) and the LAI
     * antipsychotics (paliperidone palmitate ~25–49 d, aripiprazole ~30–47 d)
     * closely enough for a body-load magnitude; the ester curves that *are* modeled
     * bypass it with a real rate.
     */
    const val DEFAULT_DEPOT_HALF_LIFE_DAYS: Double = 21.0

    /** Resolved one-compartment oral PK parameters for a dose. */
    data class Params(
        /** Elimination half-life in minutes. */
        val halfLifeMinutes: Double,
        /** Elimination rate constant (per minute), `ln(2) / t½`. */
        val ke: Double,
        /** Absorption rate constant (per minute) — from the profile's time-to-peak when one is known, else `PKModel.defaultKa`. */
        val ka: Double,
    )

    // MARK: - Half-life

    /**
     * The elimination half-life (minutes) from the substance's own record. Null when
     * the catalog knows none — which is the honest answer for a compound nobody has
     * measured, and is what every caller must keep handling.
     */
    fun halfLifeMinutes(substance: Substance?): Double? {
        val halfLife = substance?.halfLifeMinutes ?: return null
        return if (halfLife > 0) halfLife else null
    }

    /**
     * The half-life (minutes) for a *specific logged dose*, depot-aware: a depot
     * administration reports its slow terminal half-life, not the parent molecule's
     * fast elimination.
     *
     * Every body-load path that has the entry in hand should use this rather than
     * [halfLifeMinutes] with one argument, so a depot ester isn't shown clearing in
     * days. Null when no half-life can be resolved.
     */
    fun halfLifeMinutes(entry: DoseRecord, substance: Substance?, catalog: SubstanceCatalog): Double? =
        depotHalfLifeMinutes(entry, catalog) ?: halfLifeMinutes(substance)

    // MARK: - Depot administrations

    /**
     * Whether a dose is a **depot administration** — released slowly from a depot
     * over days-to-weeks rather than acting acutely.
     *
     * Two signals: an injectable hormone ester (Estradiol Valerate IM/SC), or a
     * formulation the catalog flags depot (`releaseForm` "DEP": LAI antipsychotics,
     * Vivitrol, Depo-Provera). These have no acute onset/peak/offset curve, and
     * their terminal half-life is governed by slow release (flip-flop kinetics), not
     * the molecule's elimination.
     *
     * Note the first signal is scoped to the injection routes: the same ester
     * swallowed is not a depot, because nothing releases it slowly.
     */
    fun isDepot(entry: DoseRecord, catalog: SubstanceCatalog): Boolean {
        if (entry.releaseForm == "DEP") return true
        if (entry.route != RouteOfAdministration.INTRAMUSCULAR &&
            entry.route != RouteOfAdministration.SUBCUTANEOUS
        ) {
            return false
        }
        val uid = entry.substanceUID ?: catalog.substanceUID(entry.substance)
        return catalog.isEster(entry.saltForm, uid)
    }

    /**
     * The depot terminal half-life (minutes) for a depot dose, or null when the dose
     * is not a depot.
     *
     * An injectable ester derives it from its `ester_pk` terminal release rate
     * (`ln2 / k1`, the flip-flop terminal slope — ~3 d for valerate, ~8 d for
     * cypionate); a catalog-only ester (undecylate, no curve) or a non-ester depot
     * falls back to [DEFAULT_DEPOT_HALF_LIFE_DAYS].
     */
    fun depotHalfLifeMinutes(entry: DoseRecord, catalog: SubstanceCatalog): Double? =
        depotHalfLifeMinutes(entry, isDepot(entry, catalog), catalog)

    /**
     * [depotHalfLifeMinutes] for a caller that has already asked [isDepot] — the
     * check reads the entry's route, form and salt and resolves the parent
     * substance, so a per-entry loop asks once.
     */
    fun depotHalfLifeMinutes(
        entry: DoseRecord,
        isDepot: Boolean,
        catalog: SubstanceCatalog,
    ): Double? {
        if (!isDepot) return null
        val uid = entry.substanceUID ?: catalog.substanceUID(entry.substance)
        val label = entry.saltForm
        if (uid != null && label != null) {
            val k1 = catalog.esters(uid).firstOrNull { it.label == label }?.terminalRatePerDay
            if (k1 != null && k1 > 0) return ln(2.0) / k1 * 24 * 60 // days → minutes
        }
        return DEFAULT_DEPOT_HALF_LIFE_DAYS * 24 * 60
    }

    // MARK: - Rate constants

    /**
     * Derive `(ke, ka)` from a half-life and an optional acute duration profile.
     *
     * `ka` is fitted to the profile's time-to-peak (onset + come-up midpoints) when
     * that is positive, otherwise it falls back to `PKModel.defaultKa`.
     *
     * Returned as a pair rather than a [Params], because a depot dose legitimately
     * has a half-life and no duration at all — the destructuring keeps that call
     * site from having to invent one to get at the two numbers.
     */
    fun rateConstants(halfLifeMinutes: Double, duration: DurationProfile?): Pair<Double, Double> {
        val ke = PKModel.keFromHalfLifeMinutes(halfLifeMinutes)
        if (duration == null) return ke to PKModel.defaultKa(ke)
        val timeToPeak = (duration.onset?.midpoint ?: 0.0) + (duration.comeup?.midpoint ?: 0.0)
        val ka = if (timeToPeak > 0) PKModel.estimateKa(timeToPeak, ke) else PKModel.defaultKa(ke)
        return ke to ka
    }

    /**
     * Full resolution from a caller-resolved duration. [duration] is whichever acute
     * profile the caller already picks — a per-product envelope, a
     * route/salt/isomer-specific profile, or null. Null result when no half-life can
     * be resolved.
     */
    fun params(substance: Substance?, duration: DurationProfile?): Params? {
        val halfLife = halfLifeMinutes(substance) ?: return null
        val (ke, ka) = rateConstants(halfLife, duration)
        return Params(halfLifeMinutes = halfLife, ke = ke, ka = ka)
    }

    /**
     * Full resolution that also resolves the route's acute profile off the substance
     * model (no product envelope) — the convenience the single-dose PK tools use.
     */
    fun params(substance: Substance?, route: RouteOfAdministration): Params? =
        params(substance, substance?.resolveDuration(route))
}
