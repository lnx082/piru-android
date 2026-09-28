package glass.kagerou.piru.engine

import glass.kagerou.piru.model.BindingAction
import glass.kagerou.piru.model.ConfidenceTier
import glass.kagerou.piru.model.SubstanceCategory

/**
 * Routes a receptor or transporter target to its **tolerance class**, and carries
 * that class's right-shift kinetics.
 *
 * Ported from `Piru/Data/Pharmacology/ReceptorClasses.swift`.
 *
 * The load-bearing design claim is that tolerance is *per-mechanism*, set by the
 * target the catalog already stores, rather than one universal "tolerance %".
 *
 * ## Honesty
 * Binding affinities (Kᵢ, EC₅₀, Vd) come from the citation-verified evidence run;
 * the **tolerance kinetics here do not** — that run graded exposure and affinity,
 * not adaptation rates, which are the field's softest numbers. Over-claiming them
 * is exactly the PsychonautWiki failure this engine refuses. So every class ships
 * its kinetics flagged with a [ConfidenceTier]: [ConfidenceTier.MEDIUM] only where
 * a controlled-human value anchors it, [ConfidenceTier.LOW] (class-default,
 * order-of-magnitude) otherwise.
 *
 * The constants are calibrated to reproduce the *shape* the literature agrees on
 * — clustered dosing right-shifts the curve and spacing relaxes it, the acute
 * layer moves within a session while the adaptive layer is the days-to-weeks
 * baseline shift, and the deep layer stays dark for therapeutic users — not a
 * false-precision percentage.
 */
object ReceptorClasses {

    // MARK: - Time constants

    private const val HOUR = 60.0
    private const val DAY = 1_440.0
    private const val MONTH = 30 * DAY

    // MARK: - Deep-layer chronicity gate (class-shared)

    /**
     * The **deep** layer's drive is the product of two smoothsteps — how *heavy*
     * dosing is ([DEEP_MAGNITUDE_THRESHOLD] and [DEEP_MAGNITUDE_WIDTH] on the
     * escalation factor) times how *sustained* it is ([DEEP_CHRONICITY_THRESHOLD]
     * and [DEEP_CHRONICITY_WIDTH] on the leaky duty-cycle [chronicExposure]).
     *
     * Neither alone suffices, and that is the point: a one-off binge is heavy but
     * not sustained and recovers; a therapeutic daily dose is sustained but not
     * heavy and is stable. Deep needs **both**.
     *
     * Magnitude soft-ons as dosing approaches the heavy ceiling (escalation ≈ 1),
     * is about zero well below it, and is full by ~1.5×, so there is no hard
     * two-times cliff. Chronicity sits below the knee for a once-daily therapeutic
     * user (duty ≈ 0.15) and crosses it for a heavy multi-daily pattern, full by
     * duty ≈ 0.6. These are class-shared: the field evidence does not support
     * per-class chronicity knobs.
     */
    const val DEEP_MAGNITUDE_THRESHOLD: Double = 0.5
    const val DEEP_MAGNITUDE_WIDTH: Double = 1.0
    const val DEEP_CHRONICITY_THRESHOLD: Double = 0.25
    const val DEEP_CHRONICITY_WIDTH: Double = 0.35

    /**
     * Time constant of the chronic-exposure leaky integrator — the duty-cycle proxy
     * that tracks the time-averaged occupancy over about three weeks.
     *
     * Many doses per day read high, a once-daily therapeutic pattern about
     * 0.1–0.2, and occasional use about zero.
     */
    const val TAU_CHRONIC_EXPOSURE_MINUTES: Double = 21 * DAY

    // MARK: - Tolerance class

    /**
     * The mechanism family a target belongs to — what kind of tolerance it shows.
     *
     * Declaration order is upstream's and carries no ranking; nothing sorts on it.
     */
    enum class ReceptorClass(val wireValue: String) {
        /** Classic serotonergic psychedelics (5-HT2A agonists): fast, near-total, a valid multiplier. */
        PSYCHEDELIC_5HT2A("psychedelic5HT2A"),

        /** μ-opioid agonists: the reset-after-break overdose axis. */
        MU_OPIOID("muOpioid"),

        /**
         * Catecholamine transporters (DAT/NET) — stimulants: strong acute
         * tachyphylaxis, and a *months* allostatic axis that is **not** a dose
         * multiplier.
         */
        CATECHOLAMINE_STIMULANT("catecholamineStimulant"),

        /** Serotonin transporter (SERT) releasers and blockers — MDMA-type: reversible-leaning, partial. */
        SEROTONERGIC_RELEASER("serotonergicReleaser"),

        /** GABA-A/B (benzodiazepines, alcohol): the dependence and kindling axis. */
        GABA("gaba"),

        /** NMDA antagonists (ketamine, DXM, MXE): cumulative-toxicity axis, and also a tolerance modulator. */
        NMDA_ANTAGONIST("nmdaAntagonist"),

        /** CB1 cannabinoids (THC): fast, real, recoverable tolerance. */
        CANNABINOID_CB1("cannabinoidCB1"),

        /** Adenosine receptors (caffeine): clean, well-behaved tolerance. */
        ADENOSINE("adenosine"),

        /** Nicotinic ACh receptors (nicotine): desensitisation-driven, fast. */
        NICOTINIC("nicotinic"),

        /**
         * α₂-adrenergic agonists (clonidine, guanfacine): little efficacy tolerance
         * — a faint reading that *hosts* the discontinuation rebound warning.
         */
        ALPHA2_AGONIST("alpha2Agonist"),

        /** β-adrenergic antagonists (propranolol, metoprolol): little efficacy tolerance, hosts the same rebound warning. */
        BETA_BLOCKER("betaBlocker"),

        /**
         * α2δ voltage-gated calcium channel modulators (gabapentin, pregabalin,
         * phenibut): hypnotic tolerance develops, anxiolytic tolerance unclear, and
         * dependence can be severe.
         */
        ALPHA2_DELTA("alpha2Delta"),

        /** No curated class — generic class-default kinetics at the lowest confidence. */
        UNKNOWN("unknown"),
        ;

        /**
         * Whether the class exists only to **host a discontinuation-rebound warning**
         * rather than to predict a meaningful tolerance curve — the adrenergics.
         *
         * Such a class has no PK-less class representative *by design*, because
         * there is no clean dose equivalence to borrow. So a PK-less member must not
         * be surfaced as "incomplete tolerance data": there is no tolerance to
         * predict, complete PK or not. A PK-complete member still produces its faint
         * reading through the ordinary occupancy path.
         */
        val hostsReboundWarningOnly: Boolean
            get() = this == ALPHA2_AGONIST || this == BETA_BLOCKER

        /**
         * The occupancy the gauge should cap at before forming a response ratio, or
         * null to use the true, uncapped usual-dose occupancy.
         *
         * `true` only for the classes whose transporters saturate at *recreational*
         * doses: there, felt effect tracks release or reuptake **flux**, not static
         * occupancy, so an uncapped ratio hides real tolerance — a releaser at DAT
         * sits at occupancy ≈ 1, making any shift read as "no tolerance". Evaluating
         * at the sensitive part of the curve is the honest reading.
         *
         * The **agonists, PAMs and antagonists** are uncapped on purpose: there the
         * usual-dose occupancy *is* the effect proxy, and capping would pretend a
         * heavy user's escalated dose is a half-saturated one — throwing away their
         * escalation and over-reading their tolerance.
         *
         * The two `0.5` groups are kept separate rather than merged: they arrive at
         * the same number from different evidence, and merging them would erase the
         * reason if one ever moves.
         */
        val gaugeOccupancyCap: Double?
            get() = when (this) {
                // Felt effect tracks flux, not static occupancy.
                CATECHOLAMINE_STIMULANT, SEROTONERGIC_RELEASER -> 0.5
                // Gabapentinoids saturate α2δ at ordinary doses — pregabalin peaks at
                // occupancy ≈ 1 at 300 mg — so an uncapped gauge reads ~100% even at
                // a 3× right-shift and cannot show the well-attested sedative
                // tolerance.
                ALPHA2_DELTA -> 0.5
                else -> null
            }

        /**
         * The class's name for a reader who wants the mechanism — the tool card's
         * headline, with the receptor in parentheses.
         */
        val displayName: String
            get() = when (this) {
                PSYCHEDELIC_5HT2A -> "Psychedelics (5-HT2A)"
                MU_OPIOID -> "Opioids (μ)"
                CATECHOLAMINE_STIMULANT -> "Stimulants (DAT/NET)"
                SEROTONERGIC_RELEASER -> "Serotonin releasers (SERT)"
                GABA -> "GABA (benzos / alcohol)"
                NMDA_ANTAGONIST -> "Dissociatives (NMDA)"
                CANNABINOID_CB1 -> "Cannabinoids (CB1)"
                ADENOSINE -> "Adenosine (caffeine)"
                NICOTINIC -> "Nicotinic (nAChR)"
                ALPHA2_AGONIST -> "α₂-agonists (clonidine)"
                BETA_BLOCKER -> "Beta-blockers (propranolol)"
                ALPHA2_DELTA -> "Gabapentinoids (α2δ)"
                UNKNOWN -> "Other"
            }

        /** The same class with no receptor in the name, for a reader who does not want one. */
        val casualName: String
            get() = when (this) {
                PSYCHEDELIC_5HT2A -> "Psychedelics"
                MU_OPIOID -> "Opioids"
                CATECHOLAMINE_STIMULANT -> "Stimulants"
                SEROTONERGIC_RELEASER -> "Serotonin releasers"
                GABA -> "Sedatives"
                NMDA_ANTAGONIST -> "Dissociatives"
                CANNABINOID_CB1 -> "Cannabis"
                ADENOSINE -> "Caffeine"
                NICOTINIC -> "Nicotine"
                ALPHA2_AGONIST -> "α₂-agonists"
                BETA_BLOCKER -> "Beta-blockers"
                ALPHA2_DELTA -> "Gabapentinoids"
                UNKNOWN -> "Other"
            }

        /**
         * Whether the class's card belongs at the top of the tool.
         *
         * Safety-critical rather than merely severe: an opioid after a break and a
         * sedative dependence are the two the app has an explicit warning for, and
         * the adrenergic classes host the discontinuation-rebound warning, which is
         * the whole reason they appear at all.
         */
        val isSafetyCritical: Boolean
            get() = this == MU_OPIOID || this == GABA || hostsReboundWarningOnly

        companion object {
            fun fromWire(value: String?): ReceptorClass? = entries.firstOrNull { it.wireValue == value }

            /**
             * Infer a substance's tolerance class from its **pharmacological
             * category**, independent of any binding data — so a benzodiazepine
             * drives the GABA class and an opioid the μ class *even when the catalog
             * lists no receptor rows for them*.
             *
             * This is the class-level analogue of classifying by target. The
             * engine's missing-PK fallback uses it to model an
             * untargeted-but-categorised substance as its class representative,
             * which rescues the long research-chemical tail — designer benzos,
             * fluoro-amphetamines, RC opioids — that ships without curated bindings.
             *
             * Only categories with a well-defined mechanism class map; the rest
             * (nootropic, supplement, …) return null. Note [SubstanceCategory.BENZODIAZEPINE]
             * rather than the broad [SubstanceCategory.DEPRESSANT]: the catalog also
             * pins the latter on beta-blockers, α₂-agonists, antihistamines and
             * anxiolytics that are not GABA drugs. The true GABAergic depressants —
             * alcohol, barbiturates, GHB, baclofen — carry their own binding rows and
             * so route through the target path without needing this fallback.
             */
            fun toleranceClassFor(category: SubstanceCategory): ReceptorClass? = when (category) {
                SubstanceCategory.STIMULANT, SubstanceCategory.EUGEROIC -> CATECHOLAMINE_STIMULANT
                SubstanceCategory.OPIOID -> MU_OPIOID
                SubstanceCategory.BENZODIAZEPINE -> GABA
                SubstanceCategory.PSYCHEDELIC -> PSYCHEDELIC_5HT2A
                SubstanceCategory.DISSOCIATIVE -> NMDA_ANTAGONIST
                SubstanceCategory.EMPATHOGEN -> SEROTONERGIC_RELEASER
                SubstanceCategory.CANNABINOID -> CANNABINOID_CB1
                SubstanceCategory.GABAPENTINOID -> ALPHA2_DELTA
                else -> null
            }

            /**
             * The binding directions that *are* the tolerance mechanism for a class.
             *
             * The gate exists because a target string alone is not enough: trazodone's
             * 5-HT2A **antagonism** is not psychedelic tolerance and cocaine's SERT
             * **blockade** is not the MDMA-type serotonin-releaser story, so neither may
             * drive that class's card.
             *
             * The asymmetry is deliberate rather than an oversight: caffeine's adenosine
             * **antagonism** and ketamine's NMDA **channel blockade** *are* correct,
             * because antagonism is the tolerance mechanism of those classes.
             */
            private fun mechanismActionsFor(receptorClass: ReceptorClass): Set<BindingAction> =
                when (receptorClass) {
                    ReceptorClass.PSYCHEDELIC_5HT2A -> setOf(BindingAction.AGONIST, BindingAction.PARTIAL_AGONIST)
                    ReceptorClass.MU_OPIOID -> setOf(BindingAction.AGONIST, BindingAction.PARTIAL_AGONIST)
                    ReceptorClass.CATECHOLAMINE_STIMULANT ->
                        setOf(BindingAction.RELEASING_AGENT, BindingAction.REUPTAKE_INHIBITOR)
                    // SERT *releasers* only — plain reuptake inhibition is the SSRI and
                    // cocaine story, a different tolerance.
                    ReceptorClass.SEROTONERGIC_RELEASER -> setOf(BindingAction.RELEASING_AGENT)
                    ReceptorClass.GABA ->
                        setOf(BindingAction.POSITIVE_ALLOSTERIC_MODULATOR, BindingAction.AGONIST)
                    ReceptorClass.NMDA_ANTAGONIST -> setOf(
                        BindingAction.ANTAGONIST,
                        BindingAction.CHANNEL_BLOCKER,
                        BindingAction.NEGATIVE_ALLOSTERIC_MODULATOR,
                    )
                    ReceptorClass.CANNABINOID_CB1 -> setOf(BindingAction.AGONIST, BindingAction.PARTIAL_AGONIST)
                    ReceptorClass.ADENOSINE -> setOf(BindingAction.ANTAGONIST)
                    ReceptorClass.NICOTINIC -> setOf(BindingAction.AGONIST, BindingAction.PARTIAL_AGONIST)
                    ReceptorClass.ALPHA2_AGONIST -> setOf(BindingAction.AGONIST, BindingAction.PARTIAL_AGONIST)
                    ReceptorClass.BETA_BLOCKER -> setOf(BindingAction.ANTAGONIST)
                    ReceptorClass.ALPHA2_DELTA -> setOf(
                        BindingAction.MODULATOR,
                        BindingAction.CHANNEL_BLOCKER,
                        BindingAction.PARTIAL_AGONIST,
                    )
                    ReceptorClass.UNKNOWN -> emptySet()
                }

            /**
             * Classify a target string by **name only** — case-insensitive substring
             * matching, to absorb the catalog's qualifying suffixes (`"GABA-A α1β2γ2"`,
             * `"nAChR α4β2"`, `"Adenosine A2A"`).
             *
             * ## Do not route this through the target fold
             * It matches over the **raw, un-stripped** string on purpose. Stripping the
             * parenthetical reclassifies L-Theanine's `"Glutamate receptors (NMDA/AMPA/
             * kainate, low-affinity)"` from [ReceptorClass.NMDA_ANTAGONIST] to
             * [ReceptorClass.UNKNOWN], which changes which tolerance cards exist.
             */
            internal fun matchTarget(target: String): ReceptorClass {
                val t = target.lowercase()

                if (t.contains("5-ht2a") || t.contains("5ht2a")) return ReceptorClass.PSYCHEDELIC_5HT2A
                if (t.contains("mor") || t.contains("μ-opioid") || t.contains("mu-opioid") ||
                    t.contains("μ opioid")
                ) {
                    return ReceptorClass.MU_OPIOID
                }
                if (t.contains("sert") || t.contains("serotonin transporter")) {
                    return ReceptorClass.SEROTONERGIC_RELEASER
                }
                if (t.contains("dat") || t.contains("net") || t.contains("dopamine transporter") ||
                    t.contains("norepinephrine transporter") || t.contains("noradrenaline transporter")
                ) {
                    return ReceptorClass.CATECHOLAMINE_STIMULANT
                }
                if (t.contains("gaba-a") || t.contains("gabaa") || t.contains("α4β3δ gaba")) {
                    return ReceptorClass.GABA
                }
                // Adrenergic targets, keyed *after* gaba so a GABA-A subunit string —
                // "GABA-A α2β2γ2" contains "α2" — routes to gaba first. α1 and
                // unspecified adrenergic deliberately fall through to UNKNOWN: a
                // different receptor, out of scope.
                if (t.contains("adrenergic") || t.contains("adrenoceptor")) {
                    if (t.contains("α2") || t.contains("alpha-2") || t.contains("alpha2") || t.contains("alpha 2")) {
                        return ReceptorClass.ALPHA2_AGONIST
                    }
                    if (t.contains("β") || t.contains("beta")) return ReceptorClass.BETA_BLOCKER
                }
                if (t.contains("alpha2delta") || t.contains("α2δ")) return ReceptorClass.ALPHA2_DELTA
                if (t.contains("nmda")) return ReceptorClass.NMDA_ANTAGONIST
                if (t.contains("cb1") || t.contains("cannabinoid")) return ReceptorClass.CANNABINOID_CB1
                if (t.contains("adenosine")) return ReceptorClass.ADENOSINE
                if (t.contains("nachr") || t.contains("nicotinic") || t.contains("acetylcholine")) {
                    return ReceptorClass.NICOTINIC
                }

                return ReceptorClass.UNKNOWN
            }

            /**
             * Classify a target into its tolerance class.
             *
             * With no [action] this is name-only, which is what the CNS-distribution Vd
             * fallback and the cache reload use — there, direction is irrelevant. With
             * an action the **mechanism-direction gate** applies: a target whose action
             * is not in the class's own set is off-mechanism and returns
             * [ReceptorClass.UNKNOWN], so it drives no tolerance card.
             */
            fun classify(target: String, action: BindingAction? = null): ReceptorClass {
                val cls = matchTarget(target)
                if (cls == ReceptorClass.UNKNOWN || action == null) return cls
                return if (action in mechanismActionsFor(cls)) cls else ReceptorClass.UNKNOWN
            }
        }
    }

    /** The harm-reduction axis a class hands tolerance off to. */
    enum class SafetyAxis(val wireValue: String) {
        NONE("none"),
        RESET_OVERDOSE("resetOverdose"),
        STIMULANT_LOAD("stimulantLoad"),
        SEROTONERGIC_LOAD("serotonergicLoad"),
        DEPENDENCE_KINDLING("dependenceKindling"),
        CUMULATIVE_TOXICITY("cumulativeToxicity"),
        HPPD("hppd"),

        /**
         * α₂-agonist discontinuation rebound — a noradrenaline-surge rebound
         * hypertension on abrupt or too-rapid taper. The class shows little efficacy
         * tolerance, so this rebound axis, not a right-shift, is the reason it hosts
         * a card.
         */
        ALPHA2_REBOUND("alpha2Rebound"),

        /** β-blocker discontinuation rebound — receptor-upregulation rebound hypertension and tachycardia on abrupt stop. Likewise a rebound axis rather than a tolerance curve. */
        BETA_REBOUND("betaRebound"),
    }

    /**
     * A **second** effect of a class that tolerizes *differently* from the desired
     * effect — the differential-tolerance safety story.
     *
     * Two minimal log-space layers (acute and adaptive only, no deep, no escalation
     * gate) run in parallel to the primary three, driven by the *same* occupancy, so
     * the gap between the desired effect's right-shift and this endpoint's becomes
     * observable:
     * - **Opioids:** analgesia and euphoria tolerize fast while **respiratory
     *   depression tolerizes shallower and recovers faster** — so after a break the
     *   breathing is unprotected while the user still expects their old dose. That
     *   gap is the reset-after-break overdose mechanism.
     * - **Stimulants:** the subjective high tolerizes while **cardiovascular and
     *   pressor effects do not** ([acuteShiftMax] 0 and the endpoint shift staying
     *   ≡ 1), so a toleranced high pushes redoses onto an un-toleranced pressor.
     */
    data class SafetyEndpoint(
        /** Which harm axis this endpoint measures. */
        val kind: Kind,
        /** Acute-layer ln-shift ceiling, within-session. `0` means the endpoint has no acute pool. */
        val acuteShiftMax: Double,
        val tauAcuteMinutes: Double,
        /** Adaptive-layer ln-shift ceiling. `0` means the endpoint does not tolerize at all, so its shift factor is always 1. */
        val adaptiveShiftMax: Double,
        val tauAdaptiveMinutes: Double,
    ) {
        enum class Kind(val wireValue: String) {
            /** Opioid respiratory depression — the reset-after-break overdose axis. */
            RESPIRATORY("respiratory"),

            /** Stimulant cardiovascular and pressor load — the redose-toxicity axis. */
            CARDIOVASCULAR("cardiovascular"),

            /**
             * Benzodiazepine cognitive and psychomotor impairment — the
             * escalation-impairment axis.
             *
             * Sedation tolerizes near-completely in about two weeks; memory and
             * psychomotor impairment do not tolerize at all. The dose goes up because
             * sedation fades, and the impairment scales with the new, higher dose.
             */
            COGNITIVE_IMPAIRMENT("cognitiveImpairment"),
        }
    }

    /**
     * A distinguishable **effect** a class's tolerance breaks out into — one axis of
     * the effect ladder.
     *
     * Benzodiazepine effects tolerize at very different rates (sedation
     * near-completely, anxiolysis and amnesia probably not at all), and the split is
     * mechanistic: which α-subtype mediates the effect and how that subtype adapts.
     * Classic benzodiazepines are non-selective, so this is not a per-drug affinity
     * story — the differential lives in the adaptation, not the binding.
     */
    enum class EffectAxis(val wireValue: String) {
        SEDATION("sedation"),
        ANXIOLYSIS("anxiolysis"),
        ANTICONVULSANT("anticonvulsant"),
        MYORELAXATION("myorelaxation"),
        MEMORY("memory"),
        COORDINATION("coordination"),

        /** Gabapentinoid hypnotic and sleep effect. */
        HYPNOTIC("hypnotic"),
        ;

        /**
         * An effect you *notice* against an *impairment* that does not announce
         * itself. Drives the ladder's colour, never its rank.
         */
        enum class Kind { FELT, IMPAIRMENT }

        val kind: Kind
            get() = when (this) {
                MEMORY, COORDINATION -> Kind.IMPAIRMENT
                else -> Kind.FELT
            }
    }

    /**
     * One effect's tolerance kinetics — an adaptive-only ln-shift endpoint driven by
     * the same occupancy as the primary layer (no deep layer, no escalation gate), on
     * the effect's own time constant.
     *
     * [adaptiveShiftMax] of `0` means the effect does not tolerize, so its shift
     * factor stays ≡ 1. The kinetics are literature *directions* — a large gap at low
     * confidence, never a fitted "recovers in X".
     *
     * Sedation is not an endpoint: it is the class's primary layer.
     */
    data class EffectEndpoint(
        val axis: EffectAxis,
        val adaptiveShiftMax: Double,
        val tauAdaptiveMinutes: Double,
    )

    /**
     * Right-shift parameters for one tolerance class: the four log-space layers that
     * sum into the class's shift factor, plus the endpoints that break out of it.
     *
     * The closed-form [PDModel.stepShift] advances each layer, so the parameters are
     * calibrated by the steady-state ln-shift a saturating dose pattern produces
     * rather than by a fragile Euler grid.
     */
    data class Parameters(
        /** Acute-layer ln-shift ceiling (within-session tachyphylaxis). `0` means the class has no acute pool. */
        val acuteShiftMax: Double,
        /** Acute-layer build and recover time constant — hours; recovers overnight. */
        val tauAcuteMinutes: Double,
        /** Adaptive-layer ln-shift ceiling — the days-to-weeks baseline shift people mean by "tolerance". */
        val adaptiveShiftMax: Double,
        /** Adaptive-layer build and recover time constant — days to about two weeks. */
        val tauAdaptiveMinutes: Double,
        /** Deep-layer ln-shift ceiling (entrenched neuroadaptation). `0` means no deep layer. */
        val deepShiftMax: Double,
        /** Deep-layer build and recover time constant — months. */
        val tauDeepMinutes: Double,
        /**
         * Synthesis-layer ln-shift ceiling — the slow serotonin-synthesis pool that
         * splits the SERT releaser class onto two recovery clocks. `0` means the class
         * has no synthesis layer, so it never accrues regardless of the per-substance
         * flag.
         *
         * Only the serotonergic releaser class is non-zero, and it engages only for
         * substances flagged as synthesis suppressors (the MDMA-type entactogens).
         */
        val synthesisShiftMax: Double,
        /** Synthesis-layer build and recover time constant — weeks. Inert for every class whose [synthesisShiftMax] is 0. */
        val tauSynthesisMinutes: Double,
        val safetyAxis: SafetyAxis,
        /** Confidence in *these kinetics*, not in the affinity data. */
        val confidence: ConfidenceTier,
        /**
         * CNS-distribution volume-of-distribution fallback (L/kg), used — flagged
         * unverified — when a substance in this class has no graded Vd of its own.
         *
         * Load-bearing rather than a nicety: 399 substances with binding data have no
         * Vd of their own, the LSD analogs, synthetic cannabinoids and most of the
         * 2C-x and substituted-tryptamine families among them. Null for the classes
         * with no representative, where occupancy going uncomputed is the correct
         * answer.
         *
         * These are hand-copied from each class's representative substance. Upstream
         * flags the same drift risk and names the durable fix — read the
         * representative's cited `pk_routes` row rather than restating the literal —
         * without making that change; this port restates them, matching behaviour.
         */
        val classDefaultVdLPerKg: Double?,
        /** A parallel **differential safety endpoint** that tolerizes on its own kinetics, or null for the classes without one. */
        val safetyEndpoint: SafetyEndpoint? = null,
        /**
         * The **effect ladder** endpoints for a class whose tolerance is
         * effect-selective (GABA) — each an adaptive-only endpoint on its own
         * kinetics, driven by the same occupancy. Empty for the classes whose effects
         * all tolerize together, where one gauge suffices.
         */
        val effectEndpoints: List<EffectEndpoint> = emptyList(),
        /** Which effect the **primary layer** represents for a ladder class (GABA → sedation). Null for non-ladder classes. */
        val primaryEffectAxis: EffectAxis? = null,
        /**
         * Drive occupancy from the substance's **therapeutic plasma threshold** instead
         * of its binding Kᵢ.
         *
         * Set only for the gabapentinoids, and for a real reason: they bind α2δ at tens
         * of nanomolar while acting at micromolar plasma levels through slow α2δ
         * trafficking, so a Kᵢ-driven occupancy stays at ≈ 1 for three days after a
         * single pregabalin dose and a once-weekly dose accrues "mild tolerance". With
         * the threshold as half-max, occupancy follows plasma over the ~8–12 h the
         * effect actually lasts. A substance in a flagged class with no such row keeps
         * its Kᵢ.
         */
        val occupancyHalfMaxFromTherapeuticRange: Boolean = false,
    )

    /**
     * The curated right-shift parameters for a class — the literature-anchored table,
     * calibrated to the literature's *shape* rather than to a fitted number.
     *
     * Every entry's evidence anchor and its grade are recorded upstream beside the
     * constants; what is worth keeping in view here is the **pattern**: the acute
     * layer is non-zero for everything that shows within-session tachyphylaxis and
     * zero where it does not (adenosine, the adrenergics, and the cardiovascular
     * endpoint), and the deep layer is non-zero for exactly three classes — the
     * opioid, stimulant and (via the escalation gate) none of the rest.
     */
    fun parametersFor(receptorClass: ReceptorClass): Parameters = when (receptorClass) {
        ReceptorClass.PSYCHEDELIC_5HT2A -> Parameters(
            // Two clocks: a same-day tachyphylaxis pool (fast 5-HT2A internalisation, mostly
            // gone by the next morning) and the multi-day adaptive shift toward near-total
            // in-class cross-tolerance. No deep layer — psychedelics do not entrench over
            // months. Grade medium, on a controlled-human anchor.
            acuteShiftMax = 1.2, tauAcuteMinutes = 18 * HOUR,
            adaptiveShiftMax = 2.5, tauAdaptiveMinutes = 3.5 * DAY,
            deepShiftMax = 0.0, tauDeepMinutes = 3 * MONTH,
            synthesisShiftMax = 0.0, tauSynthesisMinutes = 3 * MONTH,
            safetyAxis = SafetyAxis.HPPD, confidence = ConfidenceTier.MEDIUM,
            classDefaultVdLPerKg = 4.0,
            safetyEndpoint = null,
        )

        ReceptorClass.MU_OPIOID -> Parameters(
            // Days-to-weeks adaptive shift (recovery t½ ~14 d, so τ ~20 d), a mild
            // within-session acute pool, and an escalation-gated deep layer: dosing sustained
            // above the heavy ceiling entrenches over months. Grade low.
            acuteShiftMax = 0.3, tauAcuteMinutes = 4 * HOUR,
            adaptiveShiftMax = 1.8, tauAdaptiveMinutes = 20 * DAY,
            deepShiftMax = 1.1, tauDeepMinutes = 6 * MONTH,
            synthesisShiftMax = 0.0, tauSynthesisMinutes = 3 * MONTH,
            safetyAxis = SafetyAxis.RESET_OVERDOSE, confidence = ConfidenceTier.LOW,
            classDefaultVdLPerKg = 3.5,
            // Respiratory depression tolerizes shallower than analgesia (ceiling 1.0
            // against the analgesic 1.8) and recovers faster (τ 10 d against 20 d) — so
            // after a break the breathing is unprotected while the user still expects
            // their old dose. That gap is the reset-OD mechanism.
            safetyEndpoint = SafetyEndpoint(
                kind = SafetyEndpoint.Kind.RESPIRATORY,
                acuteShiftMax = 0.15, tauAcuteMinutes = 4 * HOUR,
                adaptiveShiftMax = 1.0, tauAdaptiveMinutes = 10 * DAY,
            ),
        )

        ReceptorClass.CATECHOLAMINE_STIMULANT -> Parameters(
            // The flagship three-layer class. A strong acute layer (vesicular reserve-pool
            // depletion, the redose loop) recovering overnight; a modest adaptive baseline
            // shift, since ADHD response is stable for years; and a deep layer that stays
            // off for therapeutic users until the escalation gate opens, then entrenches
            // over ~9 months as DAT density recovers slowly. Grade low.
            acuteShiftMax = 0.8, tauAcuteMinutes = 9 * HOUR,
            adaptiveShiftMax = 0.4, tauAdaptiveMinutes = 12 * DAY,
            deepShiftMax = 1.6, tauDeepMinutes = 9 * MONTH,
            synthesisShiftMax = 0.0, tauSynthesisMinutes = 3 * MONTH,
            safetyAxis = SafetyAxis.STIMULANT_LOAD, confidence = ConfidenceTier.LOW,
            classDefaultVdLPerKg = 4.0,
            // Cardiovascular is two mechanisms on two timescales. The **acute**
            // within-session pressor does NOT tolerize, so a chronic user redosing
            // in-session still lands on a fresh spike — the redose-toxicity hazard. The
            // **chronic resting** response does adapt over weeks. acute 0 with
            // adaptive > 0 is what sharpens the safety story: the resting response
            // settles, the per-redose spike does not.
            safetyEndpoint = SafetyEndpoint(
                kind = SafetyEndpoint.Kind.CARDIOVASCULAR,
                acuteShiftMax = 0.0, tauAcuteMinutes = 4 * HOUR,
                adaptiveShiftMax = 0.6, tauAdaptiveMinutes = 12 * DAY,
            ),
        )

        ReceptorClass.SEROTONERGIC_RELEASER -> Parameters(
            // Split per-substance onto two recovery clocks. A within-session acute fade plus
            // a *fast* adaptive pool (τ ~4 d — receptor and transporter resensitisation,
            // which is the whole story for the cathinone releasers). The slow synthesis
            // pool (τ ~2 wk) engages only for substances that suppress serotonin synthesis,
            // so MDMA recovers over weeks while mephedrone resets in days, same class.
            acuteShiftMax = 0.5, tauAcuteMinutes = 12 * HOUR,
            adaptiveShiftMax = 1.0, tauAdaptiveMinutes = 4 * DAY,
            deepShiftMax = 0.0, tauDeepMinutes = 3 * MONTH,
            synthesisShiftMax = 1.0, tauSynthesisMinutes = 14 * DAY,
            safetyAxis = SafetyAxis.SEROTONERGIC_LOAD, confidence = ConfidenceTier.LOW,
            classDefaultVdLPerKg = 6.5,
            safetyEndpoint = null,
        )

        ReceptorClass.GABA -> Parameters(
            // Benzodiazepines and alcohol: sedative tolerance is near-complete in about two
            // weeks, while anxiolytic and cognitive tolerance are absent or negligible. The
            // primary layer therefore represents the **sedative** endpoint alone, and the
            // safety endpoint captures the cognitive impairment that does not tolerize — so
            // the dose that no longer sedates still impairs memory and coordination at full
            // strength. Grade low.
            acuteShiftMax = 0.4, tauAcuteMinutes = 6 * HOUR,
            adaptiveShiftMax = 2.0, tauAdaptiveMinutes = 14 * DAY,
            deepShiftMax = 0.0, tauDeepMinutes = 3 * MONTH,
            synthesisShiftMax = 0.0, tauSynthesisMinutes = 3 * MONTH,
            safetyAxis = SafetyAxis.DEPENDENCE_KINDLING, confidence = ConfidenceTier.LOW,
            classDefaultVdLPerKg = 1.1,
            safetyEndpoint = SafetyEndpoint(
                kind = SafetyEndpoint.Kind.COGNITIVE_IMPAIRMENT,
                acuteShiftMax = 0.0, tauAcuteMinutes = 6 * HOUR,
                adaptiveShiftMax = 0.0, tauAdaptiveMinutes = 14 * DAY,
            ),
            // The effect ladder. Sedation is the primary layer. Anxiolysis and the two
            // impairments do NOT tolerize — that is the escalation trap: the dose goes up
            // because sedation faded, and the impairment scales with the new dose.
            // Anticonvulsant fades slowly and partially over months; myorelaxation
            // develops at an unquantified rate.
            effectEndpoints = listOf(
                EffectEndpoint(EffectAxis.ANXIOLYSIS, adaptiveShiftMax = 0.0, tauAdaptiveMinutes = 14 * DAY),
                EffectEndpoint(EffectAxis.ANTICONVULSANT, adaptiveShiftMax = 0.4, tauAdaptiveMinutes = 2 * MONTH),
                EffectEndpoint(EffectAxis.MYORELAXATION, adaptiveShiftMax = 0.5, tauAdaptiveMinutes = 14 * DAY),
                EffectEndpoint(EffectAxis.MEMORY, adaptiveShiftMax = 0.0, tauAdaptiveMinutes = 14 * DAY),
                EffectEndpoint(EffectAxis.COORDINATION, adaptiveShiftMax = 0.0, tauAdaptiveMinutes = 14 * DAY),
            ),
            primaryEffectAxis = EffectAxis.SEDATION,
        )

        ReceptorClass.ALPHA2_DELTA -> Parameters(
            // Gabapentinoids. Deliberately a single gauge rather than an effect ladder: the
            // only evidenced dissociation is binary — sedation habituates within weeks while
            // anxiolytic efficacy is maintained long term — and a benzo-style graded ladder
            // has no direct evidence for this class. The mechanism is α2δ-1, not GABA-A, so
            // the subtype story that drives the GABA ladder does not apply.
            acuteShiftMax = 0.3, tauAcuteMinutes = 6 * HOUR,
            adaptiveShiftMax = 0.8, tauAdaptiveMinutes = 10 * DAY,
            deepShiftMax = 0.0, tauDeepMinutes = 3 * MONTH,
            synthesisShiftMax = 0.0, tauSynthesisMinutes = 3 * MONTH,
            safetyAxis = SafetyAxis.DEPENDENCE_KINDLING, confidence = ConfidenceTier.LOW,
            classDefaultVdLPerKg = 0.5,
            safetyEndpoint = null,
            // The occupancy cap above exists for the same reason this flag does: α2δ
            // saturates at ordinary doses.
            occupancyHalfMaxFromTherapeuticRange = true,
        )

        ReceptorClass.NMDA_ANTAGONIST -> Parameters(
            // Dissociatives: a short acute fade, a fast adaptive shift, and no deep layer.
            // The cumulative-toxicity axis is the safety hand-off rather than a right-shift.
            acuteShiftMax = 0.4, tauAcuteMinutes = 4 * HOUR,
            adaptiveShiftMax = 1.0, tauAdaptiveMinutes = 3 * DAY,
            deepShiftMax = 0.0, tauDeepMinutes = 3 * MONTH,
            synthesisShiftMax = 0.0, tauSynthesisMinutes = 3 * MONTH,
            safetyAxis = SafetyAxis.CUMULATIVE_TOXICITY, confidence = ConfidenceTier.LOW,
            classDefaultVdLPerKg = 2.5,
            safetyEndpoint = null,
        )

        ReceptorClass.CANNABINOID_CB1 -> Parameters(
            acuteShiftMax = 0.3, tauAcuteMinutes = 6 * HOUR,
            adaptiveShiftMax = 1.2, tauAdaptiveMinutes = 4 * DAY,
            deepShiftMax = 0.0, tauDeepMinutes = 3 * MONTH,
            synthesisShiftMax = 0.0, tauSynthesisMinutes = 3 * MONTH,
            safetyAxis = SafetyAxis.NONE, confidence = ConfidenceTier.LOW,
            classDefaultVdLPerKg = 3.4,
            safetyEndpoint = null,
        )

        ReceptorClass.ADENOSINE -> Parameters(
            // No acute pool: caffeine's tolerance is a clean, well-behaved adaptive shift.
            acuteShiftMax = 0.0, tauAcuteMinutes = 4 * HOUR,
            adaptiveShiftMax = 1.0, tauAdaptiveMinutes = 5 * DAY,
            deepShiftMax = 0.0, tauDeepMinutes = 3 * MONTH,
            synthesisShiftMax = 0.0, tauSynthesisMinutes = 3 * MONTH,
            safetyAxis = SafetyAxis.NONE, confidence = ConfidenceTier.LOW,
            classDefaultVdLPerKg = null,
            safetyEndpoint = null,
        )

        ReceptorClass.NICOTINIC -> Parameters(
            // Desensitisation-driven and fast: the shortest adaptive constant in the table.
            acuteShiftMax = 0.8, tauAcuteMinutes = 2 * HOUR,
            adaptiveShiftMax = 0.4, tauAdaptiveMinutes = 1 * DAY,
            deepShiftMax = 0.0, tauDeepMinutes = 3 * MONTH,
            synthesisShiftMax = 0.0, tauSynthesisMinutes = 3 * MONTH,
            safetyAxis = SafetyAxis.NONE, confidence = ConfidenceTier.LOW,
            classDefaultVdLPerKg = null,
            safetyEndpoint = null,
        )

        ReceptorClass.ALPHA2_AGONIST -> Parameters(
            // Little efficacy tolerance — the faint reading that hosts the rebound warning.
            acuteShiftMax = 0.0, tauAcuteMinutes = 4 * HOUR,
            adaptiveShiftMax = 0.2, tauAdaptiveMinutes = 7 * DAY,
            deepShiftMax = 0.0, tauDeepMinutes = 3 * MONTH,
            synthesisShiftMax = 0.0, tauSynthesisMinutes = 3 * MONTH,
            safetyAxis = SafetyAxis.ALPHA2_REBOUND, confidence = ConfidenceTier.LOW,
            classDefaultVdLPerKg = null,
            safetyEndpoint = null,
        )

        ReceptorClass.BETA_BLOCKER -> Parameters(
            acuteShiftMax = 0.0, tauAcuteMinutes = 4 * HOUR,
            adaptiveShiftMax = 0.15, tauAdaptiveMinutes = 7 * DAY,
            deepShiftMax = 0.0, tauDeepMinutes = 3 * MONTH,
            synthesisShiftMax = 0.0, tauSynthesisMinutes = 3 * MONTH,
            safetyAxis = SafetyAxis.BETA_REBOUND, confidence = ConfidenceTier.LOW,
            classDefaultVdLPerKg = null,
            safetyEndpoint = null,
        )

        ReceptorClass.UNKNOWN -> Parameters(
            // The generic class-default: an adaptive shift at the lowest confidence, so an
            // unclassifiable substance still produces a reading rather than nothing.
            acuteShiftMax = 0.0, tauAcuteMinutes = 4 * HOUR,
            adaptiveShiftMax = 0.7, tauAdaptiveMinutes = 7 * DAY,
            deepShiftMax = 0.0, tauDeepMinutes = 3 * MONTH,
            synthesisShiftMax = 0.0, tauSynthesisMinutes = 3 * MONTH,
            safetyAxis = SafetyAxis.NONE, confidence = ConfidenceTier.UNVERIFIED,
            classDefaultVdLPerKg = null,
            safetyEndpoint = null,
        )
    }
}
