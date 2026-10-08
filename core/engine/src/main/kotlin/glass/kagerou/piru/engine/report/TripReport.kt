package glass.kagerou.piru.engine.report

import glass.kagerou.piru.model.doseFormatted
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * A session rendered as the document people share: what was taken, then every
 * observation at its T+ offset, then the descriptors grouped by domain with the
 * moment each was first noted, then the summary.
 *
 * Ported from `Piru/Utilities/TripReport.swift`. Built once into value types
 * (every substance and vocabulary lookup pre-resolved by the caller), rendered
 * as Markdown anywhere. English on purpose — like the JSON exports, it is the
 * portable form; the on-screen session is the localized one.
 */
data class TripReport(
    val title: String?,
    val sessionStart: Instant,
    val doses: List<Dose>,
    val notes: List<Note>,
    val summary: String?,
) {
    data class Dose(
        val timestamp: Instant,
        val name: String,
        val amount: Double,
        val unit: String,
        val route: String,
        /** A dose with no amount; the table prints `?` for it. */
        val isUnknownDose: Boolean = false,
        /**
         * A dose whose amount is an estimate rather than a measurement; the table prints `~` before it.
         *
         * Carried because the journal marks these and a report exported from the journal has to agree with it —
         * "was this measured" is exactly the kind of thing a reader of a printed summary would otherwise assume.
         */
        val isApproximate: Boolean = false,
        /**
         * The modeled phase boundaries as clock times, in order, when the
         * substance carries duration data. Empty for a dose that draws no curve.
         */
        val phases: List<Phase> = emptyList(),
    ) {
        /**
         * The amount as the report prints it: `?` for an unknown dose, `~` for an estimate, else the numeral.
         *
         * The same two markers the journal's own readout carries, so a PDF and the screen it was exported from
         * agree about which doses were measured.
         */
        val amountDisplay: String
            get() = when {
                isUnknownDose -> "?"
                isApproximate -> "~" + doseFormatted(amount)
                else -> doseFormatted(amount)
            }
    }

    /** One modeled moment of a dose's arc, named in the portable English. */
    data class Phase(val label: String, val at: Instant) {
        companion object {
            /** The column order the modeled-course table prints, so every row lines up. */
            val reportLabels = listOf("kicks in", "peak begins", "starts wearing off", "effects end")
        }
    }

    data class Note(
        val timestamp: Instant,
        /** True for a scheduled check-in observation; summary notes are filtered out by the builder. */
        val checkIn: Boolean,
        val text: String,
        val shulgin: Int?,
        val mood: Int?,
        val energy: Int?,
        val social: Int?,
        val worked: Int?,
        val heartRate: Int?,
        /** Descriptor concepts the vocabulary resolves, in the order they were chosen. */
        val descriptors: List<Descriptor> = emptyList(),
    )

    data class Descriptor(val id: String, val name: String, val domain: String)

    /** One descriptor's first appearance, for the by-domain table. */
    data class FirstNoted(val descriptor: Descriptor, val at: Instant)

    /** The substances taken, in order of first dose, each once. */
    val substances: List<String> = doses.map { it.name }.distinct()

    /**
     * Every descriptor noted in the session, grouped by domain (domains in order
     * of first appearance), each carrying the timestamp it was first noted at.
     */
    val descriptorsByDomain: List<Pair<String, List<FirstNoted>>>
        get() {
            val firstSeen = LinkedHashMap<Descriptor, Instant>()
            val domainOrder = mutableListOf<String>()
            for (note in notes) {
                for (descriptor in note.descriptors) {
                    if (!firstSeen.containsKey(descriptor)) {
                        firstSeen[descriptor] = note.timestamp
                        if (!domainOrder.contains(descriptor.domain)) domainOrder.add(descriptor.domain)
                    }
                }
            }
            return domainOrder.map { domain ->
                val rows = firstSeen
                    .filter { it.key.domain == domain }
                    .map { FirstNoted(descriptor = it.key, at = it.value) }
                    .sortedWith(compareBy({ it.at }, { it.descriptor.name }))
                domain to rows
            }
        }

    // MARK: - Markdown

    fun markdown(locale: Locale, zone: ZoneId): String {
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone)
        val dateOnly = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).withZone(zone)

        val out = mutableListOf<String>()
        val substanceLine = substances.joinToString(" + ")
        val headline = title ?: if (substanceLine.isEmpty()) "Trip report" else substanceLine
        out.add("# $headline — ${dateOnly.format(sessionStart)}")
        out.add("")
        if (title != null && substanceLine.isNotEmpty()) {
            out.add("**$substanceLine**")
            out.add("")
        }
        val facts = mutableListOf(
            "Started ${time.format(sessionStart)}",
            if (doses.size == 1) "1 entry" else "${doses.size} entries",
            if (notes.size == 1) "1 note" else "${notes.size} notes",
        )
        notes.lastOrNull()?.let { facts.add("last note at ${tPlus(sessionStart, it.timestamp)}") }
        out.add(facts.joinToString(" · "))
        out.add("")

        out.add("## Doses")
        out.add("")
        out.add("| T+ | Time | Substance | Dose | Route |")
        out.add("|---|---|---|---|---|")
        for (dose in doses) {
            out.add(
                "| ${tPlus(sessionStart, dose.timestamp)} | ${time.format(dose.timestamp)} | " +
                    "${cell(dose.name)} | ${dose.amountDisplay} ${dose.unit} | ${dose.route.lowercase()} |",
            )
        }
        out.add("")

        val course = doses.filter { it.phases.isNotEmpty() }
        if (course.isNotEmpty()) {
            out.add("## Modeled course")
            out.add("")
            out.add("Population-median phase boundaries for each dose, as clock times — what was expected, to read the notes against.")
            out.add("")
            out.add("| Substance | " + Phase.reportLabels.joinToString(" | ") + " |")
            out.add("|---" + "|---".repeat(Phase.reportLabels.size) + "|")
            for (dose in course) {
                val byLabel = dose.phases.associate { it.label to it.at }
                val cells = Phase.reportLabels.map { label -> byLabel[label]?.let(time::format) ?: "–" }
                out.add("| ${cell(dose.name)} | " + cells.joinToString(" | ") + " |")
            }
            out.add("")
        }

        // Only the columns this session actually recorded: an empty Shulgin
        // column down a stimulant report is noise, and a reader cannot tell it
        // apart from a session where nobody rated anything.
        val hasShulgin = notes.any { it.shulgin != null }
        val hasWorked = notes.any { it.worked != null }
        val hasMoodEnergy = notes.any { it.mood != null || it.energy != null || it.heartRate != null }
        val hasSocial = notes.any { it.social != null }
        val headers = buildList {
            add("T+")
            add("Time")
            if (hasWorked) add("Worked")
            if (hasShulgin) add("Shulgin")
            if (hasMoodEnergy) add("Mood / Energy")
            if (hasSocial) add("Social")
            add("Note")
        }

        out.add("## Timeline")
        out.add("")
        out.add("| " + headers.joinToString(" | ") + " |")
        out.add("|" + "---|".repeat(headers.size))
        for (note in notes) {
            val cells = mutableListOf(tPlus(sessionStart, note.timestamp), time.format(note.timestamp))
            if (hasWorked) cells.add(note.worked?.let(WorkedScale::exportWord) ?: "")
            if (hasShulgin) cells.add(note.shulgin?.let(ShulginScale::glyph) ?: "")
            if (hasMoodEnergy) cells.add(moodEnergyCell(note.mood, note.energy, note.heartRate))
            if (hasSocial) cells.add(note.social?.let(::signed) ?: "")
            val text = mutableListOf<String>()
            if (note.checkIn) text.add("**Check-in**")
            if (note.text.isNotEmpty()) text.add(cell(note.text))
            if (note.descriptors.isNotEmpty()) text.add("_" + note.descriptors.joinToString(" · ") { it.name } + "_")
            cells.add(text.joinToString(" — "))
            out.add("| " + cells.joinToString(" | ") + " |")
        }
        out.add("")

        val grouped = descriptorsByDomain
        if (grouped.isNotEmpty()) {
            out.add("## Descriptors by domain")
            out.add("")
            out.add("First noted at the T+ shown. Vocabulary: SubFxOnEx.")
            out.add("")
            out.add("| Domain | Descriptor | First noted |")
            out.add("|---|---|---|")
            for ((domain, rows) in grouped) {
                for (row in rows) {
                    out.add("| ${domain.replaceFirstChar { it.uppercaseChar() }} | ${cell(row.descriptor.name)} | ${tPlus(sessionStart, row.at)} |")
                }
            }
            out.add("")
        }

        summary?.let {
            out.add("## Summary")
            out.add("")
            out.add(it)
            out.add("")
        }

        out.add("---")
        out.add("")
        out.add("_Not medical advice. A record of one session, written with Piru._")
        return out.joinToString("\n")
    }
}
