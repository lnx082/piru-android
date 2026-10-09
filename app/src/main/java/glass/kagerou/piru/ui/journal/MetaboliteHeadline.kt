package glass.kagerou.piru.ui.journal

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import glass.kagerou.piru.R
import glass.kagerou.piru.engine.ActiveMetaboliteFold

/**
 * The one sentence a metabolite's row says, chosen from the [ActiveMetaboliteFold.Statement] the rules resolved.
 *
 * ## Why the wording is a separate function
 * Every branch of `statement()` exists so that a **different sentence** can be said, and the sentences are where the
 * honesty lives. "Dose for dose" and "molecule for molecule" are the same ratio with opposite meanings; "is still
 * going after the duration" and "persists beyond the parent" differ because one can point upward at a duration the
 * reader just read and the other cannot. Putting that in a `when` inside a composable would make the app's most
 * careful distinctions invisible to a test.
 *
 * ## What each sentence refuses to say
 * - [ActiveMetaboliteFold.Statement.Divergent] never carries a number. Norperidine's 50% of pethidine's analgesia is
 *   true and beside the point, since what makes it matter is that it is a convulsant.
 * - [ActiveMetaboliteFold.Statement.StrongerMolecule] states the conversion share when it is known, and says it is
 *   unrecorded when it is not, rather than letting the ratio imply dose equivalence.
 * - [ActiveMetaboliteFold.Statement.Qualified] always carries its basis and target, because a percentage without them
 *   cannot be read at all.
 */
@Composable
internal fun metaboliteHeadline(statement: ActiveMetaboliteFold.Statement): String = when (statement) {
    is ActiveMetaboliteFold.Statement.OutlastsDuration ->
        stringResource(R.string.metabolite_outlasts_duration, statement.metabolite, statement.parent)

    is ActiveMetaboliteFold.Statement.PersistsBeyondParent ->
        stringResource(R.string.metabolite_persists_beyond, statement.metabolite, statement.parent)

    is ActiveMetaboliteFold.Statement.Comparable ->
        stringResource(R.string.metabolite_comparable, ratio(statement.ratio), statement.parent)

    is ActiveMetaboliteFold.Statement.StrongerMolecule -> {
        // Hoisted: a nullable property on a `data class` does not smart-cast — only a local `val` does. This project
        // has now hit that twice, the other being the entry card's amount.
        val share = statement.convertedPct
        if (share != null) {
            stringResource(
                R.string.metabolite_stronger_with_share,
                ratio(statement.ratio),
                statement.parent,
                percent(share),
            )
        } else {
        // The share is unrecorded, and that is said rather than implied: without it the ratio reads as dose
        // equivalence, which is the error the case exists to prevent.
            stringResource(
                R.string.metabolite_stronger_share_unrecorded,
                ratio(statement.ratio),
                statement.parent,
            )
        }
    }

    is ActiveMetaboliteFold.Statement.Qualified ->
        stringResource(
            R.string.metabolite_qualified,
            ratio(statement.ratio),
            basisLabel(statement.basis),
            statement.target ?: stringResource(R.string.metabolite_target_unspecified),
            statement.parent,
        )

    is ActiveMetaboliteFold.Statement.Divergent ->
        stringResource(R.string.metabolite_divergent, statement.parent)

    is ActiveMetaboliteFold.Statement.RelationshipOnly ->
        stringResource(R.string.metabolite_relationship, statement.parent, statement.metabolite)
}

/**
 * A ratio as a reader should see it: `10×`, `1.5×`, never `10.0×`.
 *
 * One decimal at most, because the catalog's ratios are curated to that precision and printing `9.00×` would claim
 * two figures the source never stated. `Locale.ROOT` for the same reason the body-load amounts use it: a decimal
 * comma would make the number unreadable in a sentence.
 */
internal fun ratio(value: Double): String = when {
    value == value.toLong().toDouble() -> "${value.toLong()}×"
    else -> String.format(java.util.Locale.ROOT, "%.1f×", value)
}

/** A percentage as a reader should see it: `11%`, `50%`. */
internal fun percent(value: Double): String = when {
    value == value.toLong().toDouble() -> "${value.toLong()}%"
    else -> String.format(java.util.Locale.ROOT, "%.1f%%", value)
}

/**
 * The basis, as the sentence needs to name it.
 *
 * `CLINICAL` reads as "clinical" and `RECEPTOR_AFFINITY` as "receptor affinity" — the two have to be distinguishable
 * in the sentence or the hedge the case exists for is lost.
 */
@Composable
private fun basisLabel(basis: glass.kagerou.piru.engine.MetabolitePotencyBasis?): String = when (basis) {
    glass.kagerou.piru.engine.MetabolitePotencyBasis.CLINICAL ->
        stringResource(R.string.metabolite_basis_clinical)
    glass.kagerou.piru.engine.MetabolitePotencyBasis.RECEPTOR_AFFINITY ->
        stringResource(R.string.metabolite_basis_receptor_affinity)
    glass.kagerou.piru.engine.MetabolitePotencyBasis.IN_VITRO ->
        stringResource(R.string.metabolite_basis_in_vitro)
    else -> stringResource(R.string.metabolite_basis_unknown)
}
