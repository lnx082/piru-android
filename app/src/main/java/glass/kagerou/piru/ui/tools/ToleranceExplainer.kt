package glass.kagerou.piru.ui.tools

import glass.kagerou.piru.R
import glass.kagerou.piru.engine.ReceptorClasses

/**
 * The tolerance explainer's content, as data.
 *
 * Ported from `ToleranceExplainerView`, whose copy is mechanism-first and frames tolerance the way the
 * engine actually models it: the brain adapting to a repeated input — an allostatic/predictive picture — not
 * the 2000s "receptors used up" one.
 *
 * ## Why the copy is a data structure rather than a screen
 * Two reasons, and the second is the one that matters.
 *
 * 1. The screen becomes a renderer, so a section can be added without touching layout.
 * 2. **The per-mechanism rows are keyed by the engine's own `ReceptorClass`**, so the explainer cannot drift
 *    from the model it explains. A class the engine gains and this table does not name shows up as a gap a
 *    test can see, rather than as a class the user's card shows and the explainer never mentions.
 *
 * Every string is a resource id in both languages, so a translation is a resource edit rather than a code
 * change.
 */
object ToleranceExplainer {

    /** One teaching card: a heading and its prose. */
    data class Concept(val titleRes: Int, val bodyRes: Int)

    /** One group of cards under a heading. */
    data class Section(val titleRes: Int, val concepts: List<Concept>)

    /**
     * The prose sections, in upstream's order.
     *
     * The order is pedagogical rather than arbitrary: what tolerance *is*, then the three timescales the
     * engine models, then why it is shared across drugs, then the effect-selective exception the
     * benzodiazepine card draws, then the associative part the model cannot see, and finally what the number
     * does not include. A reader who stops after the first two has the useful half.
     */
    val sections: List<Section> = listOf(
        Section(
            R.string.tolerance_explainer_idea,
            listOf(
                Concept(
                    R.string.tolerance_explainer_adapts_title,
                    R.string.tolerance_explainer_adapts_body,
                ),
                Concept(
                    R.string.tolerance_explainer_opposite_title,
                    R.string.tolerance_explainer_opposite_body,
                ),
            ),
        ),
        Section(
            R.string.tolerance_explainer_timescales,
            listOf(
                Concept(
                    R.string.tolerance_explainer_session_title,
                    R.string.tolerance_explainer_session_body,
                ),
                Concept(
                    R.string.tolerance_explainer_weeks_title,
                    R.string.tolerance_explainer_weeks_body,
                ),
                Concept(
                    R.string.tolerance_explainer_deep_title,
                    R.string.tolerance_explainer_deep_body,
                ),
            ),
        ),
        Section(
            R.string.tolerance_explainer_cross,
            listOf(
                Concept(
                    R.string.tolerance_explainer_cross_title,
                    R.string.tolerance_explainer_cross_body,
                ),
            ),
        ),
        Section(
            R.string.tolerance_explainer_selective,
            listOf(
                Concept(
                    R.string.tolerance_explainer_selective_title,
                    R.string.tolerance_explainer_selective_body,
                ),
                Concept(
                    R.string.tolerance_explainer_subtypes_title,
                    R.string.tolerance_explainer_subtypes_body,
                ),
            ),
        ),
        Section(
            R.string.tolerance_explainer_conditioned,
            listOf(
                Concept(
                    R.string.tolerance_explainer_bracing_title,
                    R.string.tolerance_explainer_bracing_body,
                ),
                Concept(
                    R.string.tolerance_explainer_novel_title,
                    R.string.tolerance_explainer_novel_body,
                ),
                Concept(
                    R.string.tolerance_explainer_cues_title,
                    R.string.tolerance_explainer_cues_body,
                ),
            ),
        ),
        Section(
            R.string.tolerance_explainer_boundary,
            listOf(
                Concept(
                    R.string.tolerance_explainer_boundary_title,
                    R.string.tolerance_explainer_boundary_body,
                ),
            ),
        ),
    )

    /**
     * The classes the explainer covers, in teaching order.
     *
     * Everyday-tolerance mechanisms first and the rebound-hosting adrenergics last, because the adrenergics
     * barely tolerize and their note is about *stopping* rather than about tolerance. `.UNKNOWN` is omitted:
     * it is the engine's fallback, not a mechanism, and a row reading "generic class-default kinetics" would
     * be explaining the absence of an explanation.
     */
    val orderedClasses: List<ReceptorClasses.ReceptorClass> = listOf(
        ReceptorClasses.ReceptorClass.PSYCHEDELIC_5HT2A,
        ReceptorClasses.ReceptorClass.MU_OPIOID,
        ReceptorClasses.ReceptorClass.GABA,
        ReceptorClasses.ReceptorClass.NMDA_ANTAGONIST,
        ReceptorClasses.ReceptorClass.CANNABINOID_CB1,
        ReceptorClasses.ReceptorClass.ADENOSINE,
        ReceptorClasses.ReceptorClass.CATECHOLAMINE_STIMULANT,
        ReceptorClasses.ReceptorClass.SEROTONERGIC_RELEASER,
        ReceptorClasses.ReceptorClass.NICOTINIC,
        ReceptorClasses.ReceptorClass.ALPHA2_DELTA,
        ReceptorClasses.ReceptorClass.ALPHA2_AGONIST,
        ReceptorClasses.ReceptorClass.BETA_BLOCKER,
    )

    /**
     * The one-line character of each mechanism, keyed by the class it describes.
     *
     * A function rather than a map so the compiler can require an arm per enum value: a new receptor class
     * added to the engine becomes a compile error here, which is the only way an explainer stays complete.
     * `.UNKNOWN` has an arm and is simply not listed in [orderedClasses].
     */
    fun meaningRes(receptorClass: ReceptorClasses.ReceptorClass): Int = when (receptorClass) {
        ReceptorClasses.ReceptorClass.PSYCHEDELIC_5HT2A -> R.string.tolerance_meaning_psychedelic
        ReceptorClasses.ReceptorClass.MU_OPIOID -> R.string.tolerance_meaning_opioid
        ReceptorClasses.ReceptorClass.GABA -> R.string.tolerance_meaning_gaba
        ReceptorClasses.ReceptorClass.NMDA_ANTAGONIST -> R.string.tolerance_meaning_nmda
        ReceptorClasses.ReceptorClass.CANNABINOID_CB1 -> R.string.tolerance_meaning_cannabinoid
        ReceptorClasses.ReceptorClass.ADENOSINE -> R.string.tolerance_meaning_adenosine
        ReceptorClasses.ReceptorClass.CATECHOLAMINE_STIMULANT -> R.string.tolerance_meaning_stimulant
        ReceptorClasses.ReceptorClass.SEROTONERGIC_RELEASER -> R.string.tolerance_meaning_releaser
        ReceptorClasses.ReceptorClass.NICOTINIC -> R.string.tolerance_meaning_nicotinic
        ReceptorClasses.ReceptorClass.ALPHA2_DELTA -> R.string.tolerance_meaning_alpha2_delta
        ReceptorClasses.ReceptorClass.ALPHA2_AGONIST -> R.string.tolerance_meaning_alpha2_agonist
        ReceptorClasses.ReceptorClass.BETA_BLOCKER -> R.string.tolerance_meaning_beta_blocker
        ReceptorClasses.ReceptorClass.UNKNOWN -> R.string.tolerance_meaning_unknown
    }

    /**
     * The literature the copy draws on, one row per topic.
     *
     * Carried because the explainer makes specific empirical claims — the benzodiazepine subtype dissociation,
     * the conditioned-tolerance mortality study — and a claim without its source is an assertion the app has no
     * standing to make.
     */
    val sources: List<String> = listOf(
        "Benzodiazepine effect kinetics: Vinkers & Olivier 2012; Piot & Jovanovic 2026.",
        "Conditioned tolerance: Siegel 1976; Siegel, Hinson, Krank & McCully 1982; Weise-Kelly & Siegel 2001; Carlton & Wolgin 1971.",
    )
}
