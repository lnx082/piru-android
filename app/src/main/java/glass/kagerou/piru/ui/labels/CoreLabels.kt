package glass.kagerou.piru.ui.labels

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import glass.kagerou.piru.R
import glass.kagerou.piru.engine.InteractionSeverity
import glass.kagerou.piru.engine.ReceptorClasses
import glass.kagerou.piru.model.BindingAction
import glass.kagerou.piru.model.BindingAffinity
import glass.kagerou.piru.model.Combination
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.SubstanceCategory

/**
 * The reader-facing labels for every vocabulary that lives in `:core:`.
 *
 * ## Why this exists at all
 * `:core:model` and `:core:engine` are plain JVM modules with no Android
 * dependency, deliberately — it is what lets the pharmacology be tested in
 * milliseconds rather than on a device. A resource id is an Android concept, so
 * those modules carry the **English** label (`RouteOfAdministration.displayName`,
 * `SubstanceCategory`'s constructor name, `InteractionSeverity.label`,
 * `ReceptorClasses.ReceptorClass.displayName`) and their own KDocs say the
 * localized one lives with the app's resources. This is that place.
 *
 * A handful of `:core:` types carry no label at all — `BindingAction` has only a
 * `wireValue`, `BindingAffinity` only a tier, `ReceptorClasses.EffectAxis` only
 * the lowercase identifier, and `Combination.Severity` nothing but its cases.
 * Those are the more dangerous ones, because there is nothing to reach for and
 * the identifier ends up on the screen: "reuptakeInhibitor · significant",
 * "myorelaxation". They are resolved here too, and their English lives in the
 * resource file rather than in `:core:`.
 *
 * ## One module, not one per screen
 * Every screen that shows a route also shows a category and sometimes a
 * severity, so the alternative is each screen growing its own resolver — which
 * is what had already happened, in three packages, before this file existed:
 * `ui/meds` kept a `routeLabel` and a `severityLabel`, `ui/tools` a
 * `categoryLabel`, and `ui/library` three extension properties for the binding
 * and combination enums. A route called two different things in two places is
 * the kind of bug nobody reports, because both look right in isolation.
 *
 * ## The one vocabulary that is not here
 * [bindingAction] and [bindingAffinity] resolve `shell_binding_*` in
 * `strings_shell.xml` rather than entries in `strings_core_vocabulary.xml`. That
 * is where they were written and translated, and moving them would cost real
 * translations: `tools/zh_from_xcstrings.py` regenerates a whole file by looking
 * each English value up in upstream's catalogue, upstream abbreviates two of
 * them ("PAM"/"NAM") and never labels the three affinity tiers at all, and its
 * last-resort case-insensitive probe finds an unrelated upstream "significant"
 * for a fifth. The reader-facing vocabulary is still owned here; only the
 * resource file differs.
 *
 * ## Read-only, so it costs nothing
 * These are pure resource lookups with no state, marked [ReadOnlyComposable] so
 * the compiler can skip the recomposition scope it would otherwise open. Calling
 * one inside a `Canvas` draw lambda is still not allowed — a theme read there is
 * the same problem — so hoist the value above the draw. [routeRes] is the one
 * exception to the `@Composable` shape: it hands back the id instead of the
 * string, for the hand-off summary that is built outside composition.
 */
object CoreLabels {

    /**
     * The route's resource, for a caller that has no composition to read it in.
     *
     * The only such caller is the emergency screen's plain-text hand-off
     * (`StaticContentScreens.summaryText`), which assembles its lines with
     * `Context.getString` because it is copied and shared rather than drawn. The
     * alternative — hoisting a map of labels into the composable and threading it
     * through — puts the same `when` in two files; this keeps it in one.
     */
    @StringRes
    fun routeRes(route: RouteOfAdministration): Int = when (route) {
        RouteOfAdministration.ORAL -> R.string.route_oral
        RouteOfAdministration.SUBLINGUAL -> R.string.route_sublingual
        RouteOfAdministration.BUCCAL -> R.string.route_buccal
        RouteOfAdministration.INSUFFLATION -> R.string.route_insufflation
        RouteOfAdministration.INHALATION -> R.string.route_inhalation
        RouteOfAdministration.INTRAVENOUS -> R.string.route_intravenous
        RouteOfAdministration.INTRAMUSCULAR -> R.string.route_intramuscular
        RouteOfAdministration.SUBCUTANEOUS -> R.string.route_subcutaneous
        RouteOfAdministration.TRANSDERMAL -> R.string.route_transdermal
        RouteOfAdministration.RECTAL -> R.string.route_rectal
        RouteOfAdministration.OTHER -> R.string.route_other
    }

    /** The route as a reader would say it: "Oral", "Insufflation", "直肠". */
    @Composable
    @ReadOnlyComposable
    fun route(route: RouteOfAdministration): String = stringResource(routeRes(route))

    /** The category as a reader would say it: "Stimulant", "兴奋剂". */
    @Composable
    @ReadOnlyComposable
    fun category(category: SubstanceCategory): String = stringResource(
        when (category) {
            SubstanceCategory.STIMULANT -> R.string.category_stimulant
            SubstanceCategory.PSYCHEDELIC -> R.string.category_psychedelic
            SubstanceCategory.DISSOCIATIVE -> R.string.category_dissociative
            SubstanceCategory.DYSDELIC -> R.string.category_dysdelic
            SubstanceCategory.DELIRIANT -> R.string.category_deliriant
            SubstanceCategory.OPIOID -> R.string.category_opioid
            SubstanceCategory.BENZODIAZEPINE -> R.string.category_benzodiazepine
            SubstanceCategory.GABAPENTINOID -> R.string.category_gabapentinoid
            SubstanceCategory.EMPATHOGEN -> R.string.category_empathogen
            SubstanceCategory.CANNABINOID -> R.string.category_cannabinoid
            SubstanceCategory.NOOTROPIC -> R.string.category_nootropic
            SubstanceCategory.AMPAKINE -> R.string.category_ampakine
            SubstanceCategory.EUGEROIC -> R.string.category_eugeroic
            SubstanceCategory.DEPRESSANT -> R.string.category_depressant
            SubstanceCategory.OREXIN_ANTAGONIST -> R.string.category_orexin_antagonist
            SubstanceCategory.ANTIDEPRESSANT -> R.string.category_antidepressant
            SubstanceCategory.ANTIPSYCHOTIC -> R.string.category_antipsychotic
            SubstanceCategory.ANALGESIC -> R.string.category_analgesic
            SubstanceCategory.ANTIHISTAMINE -> R.string.category_antihistamine
            SubstanceCategory.CARDIOVASCULAR -> R.string.category_cardiovascular
            SubstanceCategory.ANTIMICROBIAL -> R.string.category_antimicrobial
            SubstanceCategory.GASTROINTESTINAL -> R.string.category_gastrointestinal
            SubstanceCategory.RESPIRATORY -> R.string.category_respiratory
            SubstanceCategory.ENDOCRINE -> R.string.category_endocrine
            SubstanceCategory.IMMUNOLOGICAL -> R.string.category_immunological
            SubstanceCategory.SUPPLEMENT -> R.string.category_supplement
            SubstanceCategory.PEPTIDE -> R.string.category_peptide
            SubstanceCategory.ANTICONVULSANT -> R.string.category_anticonvulsant
            SubstanceCategory.OTHER -> R.string.category_other
        },
    )

    /**
     * The severity as a reader would say it.
     *
     * "Dangerous" is a word this app does not use lightly and does not soften —
     * the Chinese is upstream's, which is equally direct.
     */
    @Composable
    @ReadOnlyComposable
    fun severity(severity: InteractionSeverity): String = stringResource(
        when (severity) {
            InteractionSeverity.CAUTION -> R.string.severity_caution
            InteractionSeverity.UNSAFE -> R.string.severity_unsafe
            InteractionSeverity.DANGEROUS -> R.string.severity_dangerous
        },
    )

    /**
     * The combination row's tier.
     *
     * An overload rather than a second name, because it is the same question —
     * "how bad is this?" — asked of a different type. `Combination.Severity` is
     * **not** `InteractionSeverity`: it describes one hand-curated pairing in the
     * detail page, not a live interaction between two things in the log, and it
     * has three rungs where the checker has three *different* ones. Two of them
     * reuse the shared severity words because they mean the same thing; `NOTE` is
     * the rung the checker has no equivalent of.
     */
    @Composable
    @ReadOnlyComposable
    fun severity(severity: Combination.Severity): String = stringResource(
        when (severity) {
            Combination.Severity.DANGER -> R.string.severity_dangerous
            Combination.Severity.CAUTION -> R.string.severity_caution
            Combination.Severity.NOTE -> R.string.severity_note
        },
    )

    /**
     * The tolerance class as the tool's card headline, receptor and all:
     * "Psychedelics (5-HT2A)", "GABA (苯二氮䓬／酒精)".
     */
    @Composable
    @ReadOnlyComposable
    fun receptorClass(receptorClass: ReceptorClasses.ReceptorClass): String = stringResource(
        when (receptorClass) {
            ReceptorClasses.ReceptorClass.PSYCHEDELIC_5HT2A -> R.string.receptor_class_psychedelic_5ht2a
            ReceptorClasses.ReceptorClass.MU_OPIOID -> R.string.receptor_class_mu_opioid
            ReceptorClasses.ReceptorClass.CATECHOLAMINE_STIMULANT -> R.string.receptor_class_catecholamine_stimulant
            ReceptorClasses.ReceptorClass.SEROTONERGIC_RELEASER -> R.string.receptor_class_serotonergic_releaser
            ReceptorClasses.ReceptorClass.GABA -> R.string.receptor_class_gaba
            ReceptorClasses.ReceptorClass.NMDA_ANTAGONIST -> R.string.receptor_class_nmda_antagonist
            ReceptorClasses.ReceptorClass.CANNABINOID_CB1 -> R.string.receptor_class_cannabinoid_cb1
            ReceptorClasses.ReceptorClass.ADENOSINE -> R.string.receptor_class_adenosine
            ReceptorClasses.ReceptorClass.NICOTINIC -> R.string.receptor_class_nicotinic
            ReceptorClasses.ReceptorClass.ALPHA2_AGONIST -> R.string.receptor_class_alpha2_agonist
            ReceptorClasses.ReceptorClass.BETA_BLOCKER -> R.string.receptor_class_beta_blocker
            ReceptorClasses.ReceptorClass.ALPHA2_DELTA -> R.string.receptor_class_alpha2_delta
            ReceptorClasses.ReceptorClass.UNKNOWN -> R.string.receptor_class_unknown
        },
    )

    /**
     * The same class with the receptor taken out, for the load chart's legend and
     * readout — where the line has room for one word and the reader already chose
     * the class.
     */
    @Composable
    @ReadOnlyComposable
    fun receptorCasualName(receptorClass: ReceptorClasses.ReceptorClass): String = stringResource(
        when (receptorClass) {
            ReceptorClasses.ReceptorClass.PSYCHEDELIC_5HT2A -> R.string.receptor_casual_psychedelic_5ht2a
            ReceptorClasses.ReceptorClass.MU_OPIOID -> R.string.receptor_casual_mu_opioid
            ReceptorClasses.ReceptorClass.CATECHOLAMINE_STIMULANT -> R.string.receptor_casual_catecholamine_stimulant
            ReceptorClasses.ReceptorClass.SEROTONERGIC_RELEASER -> R.string.receptor_casual_serotonergic_releaser
            ReceptorClasses.ReceptorClass.GABA -> R.string.receptor_casual_gaba
            ReceptorClasses.ReceptorClass.NMDA_ANTAGONIST -> R.string.receptor_casual_nmda_antagonist
            ReceptorClasses.ReceptorClass.CANNABINOID_CB1 -> R.string.receptor_casual_cannabinoid_cb1
            ReceptorClasses.ReceptorClass.ADENOSINE -> R.string.receptor_casual_adenosine
            ReceptorClasses.ReceptorClass.NICOTINIC -> R.string.receptor_casual_nicotinic
            ReceptorClasses.ReceptorClass.ALPHA2_AGONIST -> R.string.receptor_casual_alpha2_agonist
            ReceptorClasses.ReceptorClass.BETA_BLOCKER -> R.string.receptor_casual_beta_blocker
            ReceptorClasses.ReceptorClass.ALPHA2_DELTA -> R.string.receptor_casual_alpha2_delta
            ReceptorClasses.ReceptorClass.UNKNOWN -> R.string.receptor_casual_unknown
        },
    )

    /**
     * One rung of a class's effect ladder.
     *
     * The wire values are lowercase identifiers and upstream's names are not, so
     * this is the difference between "myorelaxation" and "肌肉松弛" on the screen.
     */
    @Composable
    @ReadOnlyComposable
    fun effectAxis(axis: ReceptorClasses.EffectAxis): String = stringResource(
        when (axis) {
            ReceptorClasses.EffectAxis.SEDATION -> R.string.effect_axis_sedation
            ReceptorClasses.EffectAxis.ANXIOLYSIS -> R.string.effect_axis_anxiolysis
            ReceptorClasses.EffectAxis.ANTICONVULSANT -> R.string.effect_axis_anticonvulsant
            ReceptorClasses.EffectAxis.MYORELAXATION -> R.string.effect_axis_myorelaxation
            ReceptorClasses.EffectAxis.MEMORY -> R.string.effect_axis_memory
            ReceptorClasses.EffectAxis.COORDINATION -> R.string.effect_axis_coordination
            ReceptorClasses.EffectAxis.HYPNOTIC -> R.string.effect_axis_hypnotic
        },
    )

    /** What a binding does at its target: "Reuptake inhibitor", "再摄取抑制剂". */
    @Composable
    @ReadOnlyComposable
    fun bindingAction(action: BindingAction): String = stringResource(
        when (action) {
            BindingAction.AGONIST -> R.string.shell_binding_action_agonist
            BindingAction.PARTIAL_AGONIST -> R.string.shell_binding_action_partial_agonist
            BindingAction.ANTAGONIST -> R.string.shell_binding_action_antagonist
            BindingAction.INVERSE_AGONIST -> R.string.shell_binding_action_inverse_agonist
            BindingAction.POSITIVE_ALLOSTERIC_MODULATOR ->
                R.string.shell_binding_action_positive_allosteric_modulator
            BindingAction.NEGATIVE_ALLOSTERIC_MODULATOR ->
                R.string.shell_binding_action_negative_allosteric_modulator
            BindingAction.REUPTAKE_INHIBITOR -> R.string.shell_binding_action_reuptake_inhibitor
            BindingAction.RELEASING_AGENT -> R.string.shell_binding_action_releasing_agent
            BindingAction.ENZYME_INHIBITOR -> R.string.shell_binding_action_enzyme_inhibitor
            BindingAction.CHANNEL_BLOCKER -> R.string.shell_binding_action_channel_blocker
            BindingAction.MODULATOR -> R.string.shell_binding_action_modulator
        },
    )

    /**
     * How strongly it binds there: "weak", "significant", "primary".
     *
     * Deliberately not capitalised, and deliberately the same three words the
     * affinity dots' legend uses: these are tiers, not a sentence, and they sit
     * mid-line after the action ("Reuptake inhibitor · significant").
     */
    @Composable
    @ReadOnlyComposable
    fun bindingAffinity(affinity: BindingAffinity): String = stringResource(
        when (affinity) {
            BindingAffinity.WEAK -> R.string.shell_binding_affinity_weak
            BindingAffinity.SIGNIFICANT -> R.string.shell_binding_affinity_significant
            BindingAffinity.PRIMARY -> R.string.shell_binding_affinity_primary
        },
    )
}
