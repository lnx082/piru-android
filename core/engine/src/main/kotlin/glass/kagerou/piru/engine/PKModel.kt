package glass.kagerou.piru.engine

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * One-compartment oral pharmacokinetics, in the scalar form the whole app uses.
 *
 * Ported from `Shared/Engines/PKModel.swift`. Every function here is pure
 * `Double` arithmetic — there is no linear algebra, no numerical library, and no
 * state — so the port is a transcription and the upstream tests pin it exactly.
 *
 * Times are **minutes** and doses **milligrams** throughout unless a parameter
 * says otherwise; the doc on each function names its unit, because the two
 * concentrations below differ only by a divisor and mixing them understates
 * occupancy by a factor of 10⁹.
 */
object PKModel {

    // MARK: - The shape

    /**
     * Normalized one-compartment oral concentration at time [minutes].
     *
     * The raw (unnormalized) value — divide by [cmax] to get a `[0, 1]` range.
     * Negative time, or a non-positive rate constant, is zero rather than an
     * error: a curve is drawn for every logged dose, including one whose
     * half-life the catalog does not carry.
     */
    fun concentration(minutes: Double, ke: Double, ka: Double): Double {
        if (minutes < 0 || ka <= 0 || ke <= 0) return 0.0

        // The ka = ke singularity, by L'Hôpital: the limit of
        // (ka/(ka−ke))·(e^{−ke·t} − e^{−ka·t}) as ka → ke is ke·t·e^{−ke·t}.
        if (abs(ka - ke) < 1e-10) return ke * minutes * exp(-ke * minutes)

        val raw = (ka / (ka - ke)) * (exp(-ke * minutes) - exp(-ka * minutes))
        return max(0.0, raw)
    }

    /**
     * Absolute plasma concentration on a **mass** basis at time [minutes], in
     * **mg/L** (≡ µg/mL).
     *
     * The multiplicative re-base of [concentration]: that function returns the
     * pure shape, and absolute concentration is `(F · Dose / Vd)` times it, where
     * `Vd = vdPerKg · weightKg`. Unlike the normalized curve the timeline draws,
     * this preserves dose magnitude — 5 mg and 50 mg of the same drug yield
     * *different* curves — which is what makes dose-dependent tolerance
     * expressible at all.
     *
     * For ethanol dosed in mg with `vdPerKg ≈ 0.6` this is the Widmark
     * blood-alcohol concentration (divide by 1000 for g/L, multiply by 100 for
     * g/dL).
     */
    fun concentrationAbsolute(
        dose: Double,
        bioavailability: Double,
        vdPerKg: Double,
        weightKg: Double,
        ke: Double,
        ka: Double,
        minutes: Double,
    ): Double {
        if (dose < 0 || bioavailability <= 0 || vdPerKg <= 0 || weightKg <= 0) return 0.0
        val vd = vdPerKg * weightKg
        return (bioavailability * dose / vd) * concentration(minutes, ke, ka)
    }

    /**
     * Absolute plasma concentration on a **molar** basis at time [minutes], in
     * **mol/L** — the unit receptor occupancy needs, because it compares directly
     * against a Kᵢ or EC₅₀.
     *
     * This is *total* plasma concentration. The unbound fraction `fu` is applied
     * later, in the occupancy step, so it stays out of here.
     *
     * `C_molar = C_abs[mg/L] / 1000 / molarMass[g/mol]`.
     *
     * **The bundled database stores Kᵢ and EC₅₀ in nanomolar**, so multiply this
     * by `1e9` before passing it to [occupancy] against an nM half-max. Mixing
     * mol/L with an nM half-max understates occupancy by 10⁹× and does so
     * silently — the result is a plausible-looking small number.
     */
    fun concentrationMolar(
        dose: Double,
        bioavailability: Double,
        vdPerKg: Double,
        weightKg: Double,
        molarMassGramsPerMole: Double,
        ke: Double,
        ka: Double,
        minutes: Double,
    ): Double {
        if (molarMassGramsPerMole <= 0) return 0.0
        val massPerLiter = concentrationAbsolute(
            dose, bioavailability, vdPerKg, weightKg, ke, ka, minutes,
        )
        return massPerLiter / 1_000.0 / molarMassGramsPerMole
    }

    // MARK: - Receptor occupancy

    /**
     * Fractional receptor occupancy (or transporter engagement) from a
     * Hill/Langmuir isotherm: `O = C^h / (K^h + C^h)`, in `[0, 1]`.
     *
     * One form serves all three mechanism branches the pharmacology axis
     * distinguishes — only the meaning of the half-saturation constant changes:
     * - **agonist / antagonist / PAM:** `K = Kᵢ` (binding affinity).
     * - **releaser / substrate** (amphetamine, MDMA): `K = functional release
     *   EC₅₀` at the transporter.
     * - **reuptake inhibitor** (methylphenidate, cocaine): `K = uptake-inhibition
     *   IC₅₀` or Kᵢ.
     *
     * [concentration] and [halfMax] must be in the **same** unit — it cancels, so
     * nM against nM and mol/L against mol/L both work. Pass the **free** (unbound)
     * concentration: multiply the total molar concentration by `fu` first.
     * [hillCoefficient] defaults to 1, simple mass action; above 1 models positive
     * cooperativity.
     *
     * This is the step that makes tolerance dose-dependent: its input is linear in
     * dose, so at low exposure `C ≪ K` gives `O ≈ C/K → 0` while near or above `K`
     * it saturates. The normalized-shape model could not express that — every dose
     * produced the same curve.
     */
    fun occupancy(concentration: Double, halfMax: Double, hillCoefficient: Double = 1.0): Double {
        if (concentration <= 0 || halfMax <= 0 || hillCoefficient <= 0) return 0.0
        // Fast path for simple mass action, which is the overwhelmingly common
        // case: it avoids two `pow` calls, and this runs per contributor per
        // integration step.
        if (hillCoefficient == 1.0) return concentration / (halfMax + concentration)
        val cH = concentration.pow(hillCoefficient)
        val kH = halfMax.pow(hillCoefficient)
        return cH / (kH + cH)
    }

    /** Time of peak concentration in minutes. */
    fun tmax(ke: Double, ka: Double): Double {
        if (ka <= ke || ka <= 0 || ke <= 0) return 0.0
        if (abs(ka - ke) < 1e-10) return 1.0 / ke
        return ln(ka / ke) / (ka - ke)
    }

    /** Peak concentration value, unnormalized. */
    fun cmax(ke: Double, ka: Double): Double = concentration(tmax(ke, ka), ke, ka)

    /** Elimination rate constant from a half-life in minutes. */
    fun keFromHalfLifeMinutes(halfLifeMinutes: Double): Double {
        if (halfLifeMinutes <= 0) return 0.0
        return ln(2.0) / halfLifeMinutes
    }

    /**
     * Absorption rate constant estimated from a time-to-peak and [ke], by Newton's
     * method on `Tmax = ln(ka/ke)/(ka−ke)`. Falls back to [defaultKa] when it
     * cannot converge.
     *
     * The `max(ke * 1.01, …)` floor is load-bearing rather than cosmetic: the
     * equation has no solution for `ka <= ke`, so an unconstrained step that
     * crosses below would divide by a negative denominator and wander.
     */
    fun estimateKa(timeToPeak: Double, ke: Double): Double {
        if (timeToPeak <= 0 || ke <= 0) return defaultKa(ke)

        var ka = 4 * ke
        // A `for` loop rather than `repeat`: the two bail-outs below are `break`s,
        // and `return@repeat` would continue the iteration instead of leaving it.
        for (iteration in 0 until 50) {
            if (ka <= ke) return defaultKa(ke)
            val f = ln(ka / ke) / (ka - ke) - timeToPeak
            val denom = (ka - ke) * (ka - ke)
            val df = ((ka - ke) / ka - ln(ka / ke)) / denom
            if (abs(df) <= 1e-15) break
            val kaNew = ka - f / df
            if (abs(kaNew - ka) < 1e-6) {
                ka = max(ke * 1.01, kaNew)
                break
            }
            ka = max(ke * 1.01, kaNew)
        }
        return ka
    }

    /** The absorption rate used when no duration data is available: four times [ke]. */
    fun defaultKa(ke: Double): Double = 4 * ke

    /**
     * Fraction of the original dose still in the body — absorption site plus
     * central compartment — at time [minutes].
     *
     * Unlike [concentration], which tracks plasma only, this accounts for drug
     * still being absorbed from the gut, giving an accurate "% eliminated" even
     * before the peak.
     *
     * **Returns 1, not 0, when its guards fail.** The callers use this as "how
     * much is left", and a non-positive rate constant means the model cannot say
     * — answering "everything is still there" reads as a substance that has not
     * cleared, which is the safe direction to be wrong in.
     */
    fun fractionRemainingInBody(minutes: Double, ke: Double, ka: Double): Double {
        if (minutes < 0 || ka <= 0 || ke <= 0) return 1.0

        // ka = ke singularity: the limit is (1 + ke·t)·e^{−ke·t}.
        if (abs(ka - ke) < 1e-10) return (1 + ke * minutes) * exp(-ke * minutes)

        val result = (ka * exp(-ke * minutes) - ke * exp(-ka * minutes)) / (ka - ke)
        return min(1.0, max(0.0, result))
    }

    /**
     * Time in minutes at which concentration falls to [fraction] of Cmax, on the
     * descending side. Used to size a chart's x-axis.
     */
    fun timeToFraction(
        fraction: Double,
        ke: Double,
        ka: Double,
        maxMinutes: Double = 50_000.0,
    ): Double {
        val peak = cmax(ke, ka)
        if (peak <= 0) return 0.0
        val target = peak * fraction
        val peakTime = tmax(ke, ka)

        var lo = peakTime
        var hi = maxMinutes
        repeat(100) {
            val mid = (lo + hi) / 2
            if (concentration(mid, ke, ka) > target) lo = mid else hi = mid
            if (hi - lo < 0.1) return hi
        }
        return hi
    }

    // MARK: - Saturable kinetics (the ceiling effect)

    /**
     * A Michaelis-Menten saturable step layered onto the one-compartment oral
     * model. The same `Vmax·C/(Km+C)` term sits at two attachment points with
     * opposite meaning:
     *
     * - [Elimination] — the *clearing* enzyme saturates, so above `Km` elimination
     *   goes zero-order and a small dose increase produces a **supralinear** jump
     *   in exposure (ethanol, phenytoin, GHB/GBL). The dangerous ceiling.
     * - [Activation] — a prodrug's *activating* enzyme saturates, so the active
     *   metabolite's peak stops scaling with dose past the knee while its tail
     *   lengthens (codeine → morphine, lisdexamfetamine → dexamfetamine). The
     *   relatively safe ceiling: extra dose buys duration and side-effects, not
     *   peak effect.
     *
     * At `C ≪ Km` both reduce to first-order kinetics, so a flagged substance
     * behaves identically to the closed-form path at low dose — nothing changes
     * for the roughly thousand substances carrying no flag.
     */
    sealed interface Saturation {
        /** No saturation — use the closed-form [concentration] path instead. */
        data object None : Saturation

        /**
         * Saturable elimination: `dC/dt = absorption − Vmax·C/(Km+C)`.
         *
         * [km] is the Michaelis constant in **mg/L**, the concentration at
         * half-maximal clearance rate. [vmax] is the maximum elimination rate in
         * **mg/L/min**. At `C ≪ Km` the effective first-order rate constant is
         * `Vmax/Km`, so to match a known half-life set `Vmax = ke·Km`.
         */
        data class Elimination(val km: Double, val vmax: Double) : Saturation

        /**
         * Saturable activation, parent to active metabolite. The *effect* follows
         * the metabolite.
         *
         * [km] and [vmax] are the formation step's kinetics (mg/L, mg/L/min).
         * [fractionConverted] is the fraction of the saturable flux that becomes
         * active metabolite, at most 1. [parentEliminationKe] is the first-order
         * rate of the parent's *other*, non-converting disposal, and
         * [metaboliteKe] the metabolite's own first-order elimination.
         */
        data class Activation(
            val km: Double,
            val vmax: Double,
            val fractionConverted: Double,
            val parentEliminationKe: Double,
            val metaboliteKe: Double,
        ) : Saturation
    }

    /**
     * The integrated saturable curve: a concentration time-series on a fixed grid,
     * in mg/L.
     */
    data class SaturableCurve(
        /** Grid spacing in minutes. */
        val stepMinutes: Double,
        /** Parent concentration at each grid point, mg/L. */
        val parent: List<Double>,
        /**
         * Active-metabolite concentration at each grid point, for [Saturation.Activation]
         * only. Relative concentration units: the metabolite's own Vd and molar
         * mass are constant scale factors that do not change the dose-to-peak
         * *shape* the ceiling tool plots.
         */
        val metabolite: List<Double>?,
    ) {
        /** The species that drives effect: the metabolite for activation, else the parent. */
        val effect: List<Double> get() = metabolite ?: parent

        /** Peak parent concentration. */
        val peakParent: Double get() = parent.maxOrNull() ?: 0.0

        /** Peak effect-species concentration. */
        val peakEffect: Double get() = effect.maxOrNull() ?: 0.0

        /** Area under the effect curve by the trapezoid rule, in concentration·minutes. */
        val effectAUC: Double
            get() {
                val e = effect
                if (e.size <= 1) return 0.0
                var sum = 0.0
                for (i in 1 until e.size) sum += (e[i] + e[i - 1]) / 2 * stepMinutes
                return sum
            }
    }

    /**
     * Integrate one-compartment oral PK with a Michaelis-Menten saturable step,
     * fixed-step RK4.
     *
     * Absorption stays first-order from a gut depot (`dG/dt = −ka·G`); only the
     * elimination or activation step is saturable. The closed-form Bateman path
     * cannot express saturation, so flagged substances integrate numerically —
     * and **only** flagged substances, since everything else keeps the fast
     * analytic path.
     *
     * The default one-minute step is below the stability limit for these rate
     * constants and keeps the zero-order decline shape crisp.
     */
    fun saturableCurve(
        dose: Double,
        bioavailability: Double,
        vdPerKg: Double,
        weightKg: Double,
        ka: Double,
        saturation: Saturation,
        durationMinutes: Double,
        stepMinutes: Double = 1.0,
    ): SaturableCurve {
        if (dose <= 0 || bioavailability <= 0 || vdPerKg <= 0 || weightKg <= 0 || ka <= 0 ||
            durationMinutes <= 0 || stepMinutes <= 0 || saturation == Saturation.None
        ) {
            return SaturableCurve(stepMinutes, listOf(0.0), null)
        }

        val vd = vdPerKg * weightKg
        // The concentration the full absorbed dose would reach if instantaneous
        // and undistributed.
        val scale = bioavailability * dose / vd
        val steps = max(1, Math.round(durationMinutes / stepMinutes).toInt())
        val h = stepMinutes
        val tracksMetabolite = saturation is Saturation.Activation

        // State: [gutFraction, parentConc, metaboliteConc?].
        fun derivative(s: DoubleArray): DoubleArray {
            val g = s[0]
            val c = max(0.0, s[1])
            val absorptionFlux = ka * g * scale
            return when (saturation) {
                Saturation.None -> doubleArrayOf(-ka * g, 0.0)
                is Saturation.Elimination -> {
                    val elim = saturation.vmax * c / (saturation.km + c)
                    doubleArrayOf(-ka * g, absorptionFlux - elim)
                }
                is Saturation.Activation -> {
                    val m = max(0.0, s[2])
                    val formation = saturation.vmax * c / (saturation.km + c)
                    val dC = absorptionFlux - saturation.parentEliminationKe * c - formation
                    val dM = saturation.fractionConverted * formation - saturation.metaboliteKe * m
                    doubleArrayOf(-ka * g, dC, dM)
                }
            }
        }

        fun rk4Step(s: DoubleArray): DoubleArray {
            val k1 = derivative(s)
            val k2 = derivative(DoubleArray(s.size) { s[it] + 0.5 * h * k1[it] })
            val k3 = derivative(DoubleArray(s.size) { s[it] + 0.5 * h * k2[it] })
            val k4 = derivative(DoubleArray(s.size) { s[it] + h * k3[it] })
            // Concentrations cannot go negative; the gut depot floors at zero too.
            return DoubleArray(s.size) {
                max(0.0, s[it] + h / 6 * (k1[it] + 2 * k2[it] + 2 * k3[it] + k4[it]))
            }
        }

        var state = if (tracksMetabolite) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(1.0, 0.0)
        val parent = mutableListOf(0.0)
        val metabolite = if (tracksMetabolite) mutableListOf(0.0) else null
        repeat(steps) {
            state = rk4Step(state)
            parent += state[1]
            metabolite?.add(state[2])
        }
        return SaturableCurve(h, parent, metabolite)
    }

    // MARK: - Zero-order elimination (the alcohol shape)

    /**
     * Kinetics for a substance whose clearing enzyme is **saturated across its
     * normal dose range**, so elimination runs at a fixed mass per unit time
     * rather than halving each half-life.
     *
     * Ethanol is the canonical case — alcohol dehydrogenase maxes out at a very
     * low blood level — and its defining consequence is that **duration scales
     * with dose**: two drinks clear in about two hours, eight in about eight,
     * declining roughly linearly. The generic fixed-width phase bell cannot
     * express that; this can.
     *
     * This is the analytic sibling of [Saturation.Elimination], which the ceiling
     * tool integrates numerically. It works in body content: `M(t) = F·D·(1 −
     * e^{−ka·t}) − Vmax·t`, and the timeline draws its normalized `M(t)/peak`.
     */
    @Serializable
    data class ZeroOrderKinetics(
        /** Bioavailability `F ∈ (0, 1]`. */
        val bioavailability: Double,
        /** Maximal elimination rate **at this person's body weight**, mg/min. */
        val vmaxMgPerMin: Double,
        /** First-order absorption rate constant, per minute. */
        val ka: Double,
    )

    /** Body weight in kg assumed for a profile that has not set one. */
    const val REFERENCE_BODY_WEIGHT_KG: Double = 60.0

    /**
     * The weight band `Vmax` is scaled across. Outside it the rate is held at the
     * bound rather than extrapolated: a clearance projected linearly from a 5 kg
     * or a 999 kg body is arithmetic, not physiology.
     */
    const val MINIMUM_MODELED_WEIGHT_KG: Double = 20.0
    const val MAXIMUM_MODELED_WEIGHT_KG: Double = 300.0

    /**
     * A substance's stored zero-order parameters, scaled to one person.
     *
     * `Vmax` is stored against a reference weight and scaled per kilogram from
     * there; `F` and `ka` are weight-independent. A non-finite weight falls back
     * to the reference rather than propagating a NaN into every draw call.
     */
    fun zeroOrderKinetics(
        vmaxMgPerMin: Double,
        referenceWeightKg: Double,
        kaPerMin: Double,
        bioavailability: Double,
        weightKg: Double,
    ): ZeroOrderKinetics {
        val clamped = if (weightKg.isFinite()) {
            min(max(weightKg, MINIMUM_MODELED_WEIGHT_KG), MAXIMUM_MODELED_WEIGHT_KG)
        } else {
            REFERENCE_BODY_WEIGHT_KG
        }
        val reference = if (referenceWeightKg > 0) referenceWeightKg else REFERENCE_BODY_WEIGHT_KG
        return ZeroOrderKinetics(
            bioavailability = bioavailability,
            vmaxMgPerMin = vmaxMgPerMin / reference * clamped,
            ka = kaPerMin,
        )
    }

    /**
     * Body content — mg still in the body — at [minutes]: first-order absorption
     * from a gut depot minus constant zero-order elimination, floored at zero once
     * cleared.
     */
    fun zeroOrderBodyContent(doseMg: Double, minutes: Double, kinetics: ZeroOrderKinetics): Double {
        if (doseMg <= 0 || minutes < 0 || kinetics.bioavailability <= 0 ||
            kinetics.vmaxMgPerMin <= 0 || kinetics.ka <= 0
        ) {
            return 0.0
        }
        val fd = kinetics.bioavailability * doseMg
        val absorbed = fd * (1 - exp(-kinetics.ka * minutes))
        return max(0.0, absorbed - kinetics.vmaxMgPerMin * minutes)
    }

    /**
     * Time in minutes of peak body content, where the absorption flux falls to
     * the elimination rate (`F·D·ka·e^{−ka·t} = Vmax`).
     *
     * **Returns 0 when the dose is too small to out-pace elimination**
     * (`F·D·ka ≤ Vmax`) — there is no real peak, and the caller falls back to the
     * phase bell rather than drawing a curve that never rises.
     */
    fun zeroOrderPeakMinutes(doseMg: Double, kinetics: ZeroOrderKinetics): Double {
        if (doseMg <= 0 || kinetics.ka <= 0 || kinetics.bioavailability <= 0) return 0.0
        val fd = kinetics.bioavailability * doseMg
        val ratio = kinetics.vmaxMgPerMin / (fd * kinetics.ka)
        if (ratio <= 0 || ratio >= 1) return 0.0
        return -ln(ratio) / kinetics.ka
    }

    /**
     * Minutes until body content returns to zero — the dose-scaled curve width.
     * Bisects `M(t) = 0` on the descending side, with the linear-elimination
     * upper bound `F·D/Vmax`.
     */
    fun zeroOrderClearMinutes(doseMg: Double, kinetics: ZeroOrderKinetics): Double {
        val peak = zeroOrderPeakMinutes(doseMg, kinetics)
        if (peak <= 0) return 0.0
        var lo = peak
        var hi = peak + kinetics.bioavailability * doseMg / kinetics.vmaxMgPerMin + 60
        repeat(60) {
            val mid = (lo + hi) / 2
            if (zeroOrderBodyContent(doseMg, mid, kinetics) > 0) lo = mid else hi = mid
        }
        return hi
    }

    /**
     * The normalized `[0, 1]` effect shape — equivalent to BAC over peak BAC — at
     * [minutes], or null for a dose too small to form a peak, in which case the
     * caller falls back to the generic phase curve.
     */
    fun zeroOrderShape(doseMg: Double, minutes: Double, kinetics: ZeroOrderKinetics): Double? {
        if (minutes < 0) return null
        val peakTime = zeroOrderPeakMinutes(doseMg, kinetics)
        if (peakTime <= 0) return null
        val peak = zeroOrderBodyContent(doseMg, peakTime, kinetics)
        if (peak <= 0) return null
        return min(1.0, max(0.0, zeroOrderBodyContent(doseMg, minutes, kinetics) / peak))
    }
}
