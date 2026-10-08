package glass.kagerou.piru.ui.journal

import glass.kagerou.piru.engine.InteractionMechanism
import glass.kagerou.piru.engine.InteractionResult
import glass.kagerou.piru.engine.InteractionSeverity

/**
 * Folding a session's warnings into rows.
 *
 * Ported from `SessionSafetySection`'s private `grouped`. The reason it exists is on the upstream comment: a
 * stimulant stack produces the same sentence five times, once per pair, and five identical warnings read as noise
 * rather than as five facts.
 *
 * ## Keyed on the rule's class pair, not its prose — and that distinction is the whole point
 * Several distinct rules share boilerplate. "Additive CNS depression — increased sedation and impairment" is the
 * sentence for more than one class pair, so folding on the **text** would merge two genuinely different causes into
 * one row and assert a single mechanism where there are two. The rule key is the identity; the text is only a
 * fallback for a result that carries no key.
 *
 * ## The severity of a group is its worst member
 * A folded row stands for several pairs, and a reader scanning the list needs the row's chip to be the answer to
 * "how bad is this", which is the worst of them rather than the first. The mechanism and description come from the
 * **first** member, because within one rule key they are the same — that is what the key being the identity means.
 *
 * ## Ordering
 * Most severe first; **within a severity, first-seen order is preserved**. Both halves matter: the sort must be
 * stable (Kotlin's `sortedWith` is), and the first-seen order must come from the input rather than from a map's
 * iteration — which is why the key order is recorded in a list as it is discovered.
 */
internal object InteractionGrouping {

    /** One folded warning: a rule, and every pair it fired for. */
    data class Group(
        val id: String,
        val severity: InteractionSeverity,
        val mechanism: InteractionMechanism,
        val description: String,
        /** The pairs as "A + B", in the order they were seen. */
        val pairs: List<String>,
        val substanceAs: List<String>,
        val substanceBs: List<String>,
    ) {
        /**
         * The substance the timeline screen is opened for.
         *
         * The first of each side, which is what the row's navigation carries — a folded row stands for several
         * pairs and the screen can only be opened for one.
         */
        val navigationSubstanceA: String get() = substanceAs.firstOrNull().orEmpty()
        val navigationSubstanceB: String get() = substanceBs.firstOrNull().orEmpty()
    }

    /**
     * Folds [warnings], most severe first and stable within a severity.
     *
     * The key is the rule key, or the description when a result carries none — a hand-made result has no rule and
     * its text is the only identity there is.
     */
    fun group(warnings: List<InteractionResult>): List<Group> {
        // The discovery order, kept in a list because a map's iteration order is not the input's.
        val order = mutableListOf<String>()
        val pairs = mutableMapOf<String, MutableList<String>>()
        val substanceAs = mutableMapOf<String, MutableList<String>>()
        val substanceBs = mutableMapOf<String, MutableList<String>>()
        val severities = mutableMapOf<String, InteractionSeverity>()
        val mechanisms = mutableMapOf<String, InteractionMechanism>()
        val descriptions = mutableMapOf<String, String>()

        for (warning in warnings) {
            val key = warning.ruleKey.ifEmpty { warning.description }
            descriptions.putIfAbsent(key, warning.description)
            if (key !in severities) {
                order += key
                severities[key] = warning.severity
                mechanisms[key] = warning.mechanism
            }
            pairs.getOrPut(key) { mutableListOf() } += "${warning.substanceA} + ${warning.substanceB}"
            substanceAs.getOrPut(key) { mutableListOf() } += warning.substanceA
            substanceBs.getOrPut(key) { mutableListOf() } += warning.substanceB
            // The worst of the group, which is what the row's chip answers.
            val existing = severities.getValue(key)
            if (warning.severity.raw > existing.raw) severities[key] = warning.severity
        }

        return order
            .map { key ->
                Group(
                    id = key,
                    severity = severities.getValue(key),
                    // A group always has a mechanism, because it was recorded when the key was first seen.
                    mechanism = mechanisms.getValue(key),
                    description = descriptions.getValue(key),
                    pairs = pairs.getValue(key).toList(),
                    substanceAs = substanceAs.getValue(key).toList(),
                    substanceBs = substanceBs.getValue(key).toList(),
                )
            }
            // Stable: equal severities keep the discovery order from `order`.
            .sortedWith(compareByDescending { it.severity.raw })
    }
}
