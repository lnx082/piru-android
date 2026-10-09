package glass.kagerou.piru.ui.library

import glass.kagerou.piru.model.DoseRange
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.Substance
import glass.kagerou.piru.model.SubstanceRoute
import java.util.Locale

/**
 * How much detail a shared substance card carries.
 *
 * Ported from `ShareDetailLevel`. The three levels are a **decision the user makes**, not a layout variant: what is
 * safe to send to a friend is a different question from what belongs on a printed plate, and a share that quietly
 * included the receptor table would put pharmacology in a group chat.
 *
 * ## What each level adds, and why that order
 * [MINIMAL] is the identity and the dose ladder — the two things somebody who received this needs in order to act on
 * it. [STANDARD] adds the effects and the duration, which is what makes the card *useful* rather than merely correct.
 * [RICH] adds the pharmacology, which is what makes it a reference rather than a message.
 */
enum class ShareDetailLevel {
    MINIMAL,
    STANDARD,
    RICH,
    ;

    /** The label the share sheet offers, as a resource id the caller resolves. */
    val resourceId: Int
        get() = when (this) {
            MINIMAL -> glass.kagerou.piru.R.string.share_level_minimal
            STANDARD -> glass.kagerou.piru.R.string.share_level_standard
            RICH -> glass.kagerou.piru.R.string.share_level_rich
        }
}

/**
 * The text a shared substance card carries.
 *
 * ## Why the share is text and not an image
 * Upstream renders a `SubstanceShareCard` through `ImageRenderer` and offers it as a picture. This port has no
 * off-screen renderer for a Compose tree, and inventing one would be a large piece of work whose failure modes are
 * invisible — a card drawn at the wrong size, or with a font the receiving app lacks. **Text shares everywhere**, needs
 * no renderer, is searchable by the person who receives it, and is readable by a screen reader on the other end.
 *
 * That is a deliberate difference from the reference, recorded here rather than left for a reader to notice.
 *
 * ## What it never includes
 * No dosage advice beyond what the catalogue's ladder already says, and **no dose ladder for a route the catalogue
 * marks as prescription-only context** — [DoseRange]'s tiers are the recreational ladder where a compound has both, and
 * printing it under a clinical heading is the mistake `doseContext` exists to prevent. The ladder is labelled by its
 * context in the output for the same reason.
 */
internal object SubstanceShareText {

    /**
     * Builds the share body.
     *
     * [route] is the route the user has selected on the page, because a card that lists every route's ladder is a table
     * rather than a share. `null` omits the ladder entirely, which is the honest result when the user has selected
     * nothing and the substance has no default.
     */
    fun build(
        substance: Substance,
        route: SubstanceRoute?,
        routeChoice: RouteOfAdministration?,
        detail: ShareDetailLevel,
    ): String {
        val out = StringBuilder()
        out.appendLine(substance.displayTitle)

        // Identity: always, at every level. A share that omits what the substance is cannot be acted on.
        out.appendLine(categoryLine(substance))

        if (route != null && route.doses.hasAnyValue) {
            out.appendLine()
            out.appendLine(doseLadder(route, routeChoice))
        }

        if (detail == ShareDetailLevel.MINIMAL) return out.toString().trimEnd() + "\n"

        // Duration and effects: the two things that make the card useful rather than merely correct.
        val duration = route?.duration?.estimatedTotalMinutes
        if (duration != null && duration > 0) {
            out.appendLine()
            out.appendLine("Duration: about ${formatMinutes(duration)}")
        }
        val effects = topEffects(substance)
        if (effects.isNotEmpty()) {
            out.appendLine()
            out.appendLine("Reported effects: " + effects.joinToString(", "))
        }
        val halfLife = substance.halfLifeMinutes
        if (halfLife != null && halfLife > 0) {
            out.appendLine("Half-life: about ${formatMinutes(halfLife)}")
        }

        if (detail == ShareDetailLevel.STANDARD) return out.toString().trimEnd() + "\n"

        // RICH adds nothing yet: the pharmacology it would carry — the monoamine lean bar and the graded receptor
        // meter — needs data this port reads but has no text form for, and inventing one here would put numbers in a
        // message with none of the hedges the on-screen card attaches to them. Stated rather than silently equal to
        // STANDARD.
        out.appendLine()
        out.appendLine("(Open Piru for the full pharmacology.)")
        return out.toString().trimEnd() + "\n"
    }

    /**
     * The classification, so a receiver knows what class of thing they are reading about.
     *
     * The enum's own \wireValue\ — \'Stimulant\' — rather than a lower-cased enum name, because that string is the
     * catalogue\'s own label for the class and a share is a place where the app\'s internal spelling should not show.
     */
    private fun categoryLine(substance: Substance): String = substance.category.wireValue

    /**
     * The dose ladder, **labelled by its context**.
     *
     * `Light 5–10 mg · Common 10–20 mg · Strong 20–40 mg`, and a `heavy` threshold as `Heavy 40+ mg`. A tier the
     * catalogue does not carry is omitted rather than printed as `0`, which is the same rule the duration card's em
     * dash follows: an absent phase is not a zero.
     */
    private fun doseLadder(route: SubstanceRoute, choice: RouteOfAdministration?): String {
        val unit = route.unit
        val doses = route.doses
        val parts = mutableListOf<String>()
        doses.threshold?.let { parts += "Threshold ${formatAmount(it)} $unit" }
        doses.light?.let { parts += "Light ${range(it, unit)}" }
        doses.common?.let { parts += "Common ${range(it, unit)}" }
        doses.strong?.let { parts += "Strong ${range(it, unit)}" }
        doses.heavy?.let { parts += "Heavy ${formatAmount(it)}+ $unit" }
        val where = (choice ?: route.route).name.lowercase().replace('_', ' ')
        return "Dosage ($where): " + parts.joinToString(" · ")
    }

    // `endInclusive`, not `end`: Kotlin's `ClosedRange` names the upper bound for what it is, and `end` is the
    // half-open spelling this interface does not have.
    private fun range(range: ClosedRange<Double>, unit: String): String =
        "${formatAmount(range.start)}–${formatAmount(range.endInclusive)} $unit"

    /** Up to six effects, as upstream's card takes. More turns a share into a wall. */
    private fun topEffects(substance: Substance): List<String> =
        substance.subjectiveEffects.take(6).map { it.name }.filter { it.isNotBlank() }

    /**
     * A dose amount without a trailing `.0`, using a **point** whatever the device's locale is.
     *
     * The same rule as everywhere else in this port, and it matters more here than anywhere: this string leaves the app
     * and is read in another one, where a decimal comma would be read as a thousands separator.
     */
    internal fun formatAmount(value: Double): String = when {
        value == value.toLong().toDouble() -> value.toLong().toString()
        else -> String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
    }

    /** Minutes as a reader reads them: `45 min`, `2 h`, `2 h 30`. */
    internal fun formatMinutes(minutes: Double): String {
        val whole = minutes.toLong()
        if (whole < 60) return "$whole min"
        val hours = whole / 60
        val rest = whole % 60
        return if (rest == 0L) "$hours h" else "$hours h $rest"
    }
}
