package glass.kagerou.piru.data.report

import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.data.entity.SessionEntity
import glass.kagerou.piru.data.entity.SessionNoteEntity
import glass.kagerou.piru.engine.ActiveSubstanceState
import glass.kagerou.piru.engine.DoseRecord
import glass.kagerou.piru.engine.InteractionChecker
import glass.kagerou.piru.engine.InteractionPolicy
import glass.kagerou.piru.engine.PKModel
import glass.kagerou.piru.engine.PKResolver
import glass.kagerou.piru.engine.SubstanceCatalog
import glass.kagerou.piru.engine.TimelineCurveModel
import glass.kagerou.piru.engine.from
import glass.kagerou.piru.engine.report.EliminationModel
import glass.kagerou.piru.engine.report.FirstOrderDose
import glass.kagerou.piru.engine.report.SessionPhase
import glass.kagerou.piru.engine.report.SessionStateExport
import glass.kagerou.piru.engine.report.TripReport
import glass.kagerou.piru.engine.report.ZeroOrderDose
import glass.kagerou.piru.engine.report.phase
import glass.kagerou.piru.engine.report.structureLine
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.model.Substance
import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The data layer's resolution half of the report pair: turns Room entities and
 * the bundled catalog into the pure value types in `engine.report`, which then
 * render Markdown off the main thread.
 *
 * Ported from `SessionStateExport.build(from:colors:notes:now:)` and
 * `TripReport.build(session:)` upstream, with every `@MainActor` lookup moved to
 * a parameter so the whole thing is pure and testable.
 */
object ReportBuilder {
    private const val EFFECT_SAMPLES = 80
    private const val LIVE_WINDOW_HOURS = 18.0

    // MARK: - Session state export

    fun sessionStateExport(
        entries: List<DoseEntryEntity>,
        notes: List<SessionNoteEntity>,
        catalog: SubstanceCatalog,
        checker: InteractionChecker,
        weightKg: Double,
        descriptorResolver: (String) -> TripReport.Descriptor?,
        now: Instant = Instant.now(),
    ): SessionStateExport? {
        val records = entries.map { it.toDoseRecord() }
        val active = checker.activeEntries(records, now).toSet()
        val mostRecentActive = active.maxOfOrNull { it.timestamp }
        val isLive = mostRecentActive != null &&
            Duration.between(mostRecentActive, now).toMillis() / 3_600_000.0 < LIVE_WINDOW_HOURS
        val working = if (isLive) entries.filter { active.contains(it.toDoseRecord()) } else entries
        if (working.isEmpty()) return null

        val states = working.mapNotNull { makeSubjectiveState(it, catalog, weightKg, now) }
        if (states.isEmpty()) return null
        states.sortedBy { it.doseTimestamp }

        val eliminations = eliminationGroups(working, catalog, weightKg, now)
        val lines = interactionLines(working, records, checker, now)

        val start = states.minOf { it.doseTimestamp }
        val noteLines = notes
            .filter { it.kind != SessionNoteEntity.Kind.SUMMARY && it.hasContent }
            .sortedBy { it.timestamp }
            .map { note ->
                SessionStateExport.NoteLine(
                    timestamp = note.timestamp.toInstant(),
                    checkIn = note.kind == SessionNoteEntity.Kind.CHECK_IN,
                    text = note.text,
                    structure = structureLine(
                        shulgin = note.shulgin,
                        mood = note.mood,
                        energy = note.energy,
                        social = note.social,
                        worked = note.worked,
                        heartRate = note.heartRate?.roundToInt(),
                    ),
                    descriptors = note.descriptors.mapNotNull(descriptorResolver).map { it.name },
                )
            }

        return SessionStateExport(
            generatedAt = now,
            sessionStart = start,
            substances = states,
            eliminations = eliminations,
            interactions = lines,
            isLive = isLive,
            notes = noteLines,
        )
    }

    private fun makeSubjectiveState(
        entry: DoseEntryEntity,
        catalog: SubstanceCatalog,
        weightKg: Double,
        now: Instant,
    ): SessionStateExport.SubstanceState? {
        val state = ActiveSubstanceState.from(entry.toDoseRecord(), P3Color.NEUTRAL, catalog, weightKg) ?: return null
        val elapsed = max(0.0, Duration.between(state.doseTimestamp, now).toMillis() / 60_000.0)
        val total = max(state.totalMinutes, 1.0)
        val effect = (0..EFFECT_SAMPLES).map { i ->
            TimelineCurveModel.intensity(i.toDouble() / EFFECT_SAMPLES * total, state)
        }
        val substance = catalog.lookup(entry.substance)
        return SessionStateExport.SubstanceState(
            name = titleFor(entry, substance),
            amount = entry.amount,
            unit = entry.unit,
            route = state.route,
            doseTimestamp = state.doseTimestamp,
            phase = phase(state, elapsed),
            intensity = min(1.0, max(0.0, TimelineCurveModel.intensity(elapsed, state))),
            progress = min(1.0, max(0.0, elapsed / total)),
            totalMinutes = total,
            phaseBoundaries = listOf(
                state.onsetEndMinutes,
                state.comeupEndMinutes,
                state.peakEndMinutes,
                state.offsetEndMinutes,
            ).map { min(1.0, max(0.0, it / total)) },
            next = nextPhase(state, elapsed),
            baselineAt = state.doseTimestamp.plusSeconds((total * 60).roundToLong()),
            effectCurve = effect,
        )
    }

    private fun nextPhase(state: ActiveSubstanceState, elapsed: Double): SessionStateExport.NextPhase? {
        val ts = state.doseTimestamp
        fun at(minutes: Double): Instant = ts.plusSeconds((minutes * 60).roundToLong())
        return when {
            elapsed < state.onsetEndMinutes -> SessionStateExport.NextPhase(SessionPhase.COMEUP, at(state.onsetEndMinutes))
            elapsed < state.comeupEndMinutes -> SessionStateExport.NextPhase(SessionPhase.PEAK, at(state.comeupEndMinutes))
            elapsed < state.peakEndMinutes -> SessionStateExport.NextPhase(SessionPhase.OFFSET, at(state.peakEndMinutes))
            elapsed < state.offsetEndMinutes -> SessionStateExport.NextPhase(SessionPhase.AFTER, at(state.offsetEndMinutes))
            else -> null
        }
    }

    // MARK: - Elimination

    private fun eliminationGroups(
        entries: List<DoseEntryEntity>,
        catalog: SubstanceCatalog,
        weightKg: Double,
        now: Instant,
    ): List<SessionStateExport.EliminationGroup> {
        val groups = LinkedHashMap<String, MutableList<DoseEntryEntity>>()
        val names = LinkedHashMap<String, String>()
        val products = LinkedHashMap<String, MutableSet<String>>()
        for (entry in entries) {
            val substance = catalog.lookup(entry.substance)
            val name = substance?.displayTitle ?: entry.substance
            val key = name.lowercase()
            if (!groups.containsKey(key)) {
                groups[key] = mutableListOf()
                names[key] = name
                products[key] = mutableSetOf()
            }
            groups[key]!!.add(entry)
            val product = entry.productName?.trim()
            products[key]!!.add(if (product.isNullOrEmpty()) "" else product)
        }
        return groups.keys.map { key ->
            val group = groups[key]!!
            val brand = products[key]!!.let { if (it.size == 1 && !it.contains("")) it.first() else null }
            makeEliminationGroup(brand ?: names[key]!!, group, catalog, weightKg, now)
        }
    }

    private fun makeEliminationGroup(
        name: String,
        entries: List<DoseEntryEntity>,
        catalog: SubstanceCatalog,
        weightKg: Double,
        now: Instant,
    ): SessionStateExport.EliminationGroup {
        val earliest = entries.minOf { it.timestamp.toInstant() }
        val substance = catalog.lookup(entries.first().substance)
        val zeroOrder = catalog.zeroOrderKinetics(substance?.name ?: name, weightKg)

        // A group containing a dose whose form we don't model can't be totalled:
        // this curve is the *combined* body load, so one extended-release dose in
        // it makes the sum unknowable, not merely approximate.
        if (entries.any { it.toDoseRecord().namesUnmodeledForm }) {
            return EliminationModel.unknown(name, entries.first().unit, entries.size, earliest)
        }

        fun offset(entry: DoseEntryEntity): Double =
            Duration.between(earliest, entry.timestamp.toInstant()).toMillis() / 60_000.0

        if (zeroOrder != null) {
            val doses = entries.mapNotNull { entry ->
                val mg = TimelineCurveModel.zeroOrderDoseMilligrams(entry.amount, entry.unit) ?: return@mapNotNull null
                ZeroOrderDose(offsetMinutes = offset(entry), doseMg = mg)
            }
            if (doses.isNotEmpty()) {
                return EliminationModel.zeroOrderGroup(name, zeroOrder, doses, earliest, entries.size, now)
            }
        }

        val halfLife = PKResolver.halfLifeMinutes(substance)
            ?: return EliminationModel.unknown(name, entries.first().unit, entries.size, earliest)
        val ke = PKModel.keFromHalfLifeMinutes(halfLife)
        val doses = entries.map { entry ->
            val (_, ka) = PKResolver.rateConstants(halfLife, substance?.resolveDuration(entry.route))
            FirstOrderDose(offsetMinutes = offset(entry), amount = entry.amount, ke = ke, ka = ka)
        }
        return EliminationModel.firstOrderGroup(name, halfLife, doses, entries.first().unit, earliest, entries.size, now)
    }

    // MARK: - Interactions

    private fun interactionLines(
        entries: List<DoseEntryEntity>,
        records: List<DoseRecord>,
        checker: InteractionChecker,
        now: Instant,
    ): List<SessionStateExport.InteractionLine> {
        val names = entries.map { it.substance }.distinct()
        val lower = entries.map { it.substance.lowercase() }.toSet()
        return checker.checkBatch(names, records, InteractionPolicy.EXPLORE, now)
            .filter { lower.contains(it.substanceA.lowercase()) && lower.contains(it.substanceB.lowercase()) }
            .map { result ->
                SessionStateExport.InteractionLine(
                    severity = result.severity,
                    a = result.substanceA,
                    b = result.substanceB,
                    detail = cleanDescription(result.description),
                )
            }
    }

    // MARK: - Trip report

    fun tripReport(
        session: SessionEntity,
        doses: List<DoseEntryEntity>,
        notes: List<SessionNoteEntity>,
        catalog: SubstanceCatalog,
        weightKg: Double,
        descriptorResolver: (String) -> TripReport.Descriptor?,
    ): TripReport {
        val orderedDoses = doses.sortedBy { it.timestamp }.map { entry ->
            val substance = catalog.lookup(entry.substance)
            TripReport.Dose(
                timestamp = entry.timestamp.toInstant(),
                name = titleFor(entry, substance),
                amount = entry.amount,
                unit = entry.unit,
                route = entry.route.displayName,
                isUnknownDose = entry.isUnknownDose,
                phases = tripPhases(entry, catalog, weightKg),
            )
        }
        val orderedNotes = notes
            .filter { it.kind != SessionNoteEntity.Kind.SUMMARY && it.hasContent }
            .sortedBy { it.timestamp }
            .map { note ->
                TripReport.Note(
                    timestamp = note.timestamp.toInstant(),
                    checkIn = note.kind == SessionNoteEntity.Kind.CHECK_IN,
                    text = note.text,
                    shulgin = note.shulgin,
                    mood = note.mood,
                    energy = note.energy,
                    social = note.social,
                    worked = note.worked,
                    heartRate = note.heartRate?.roundToInt(),
                    descriptors = note.descriptors.mapNotNull(descriptorResolver),
                )
            }
        return TripReport(
            title = session.title,
            sessionStart = session.startDate.toInstant(),
            doses = orderedDoses,
            notes = orderedNotes,
            summary = session.note?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * The modeled course of one dose as clock times, read off the same
     * [ActiveSubstanceState] the timeline draws — so a report and the curve it
     * was written against cannot disagree. Population-median phase boundaries,
     * not a measurement of this session.
     */
    private fun tripPhases(entry: DoseEntryEntity, catalog: SubstanceCatalog, weightKg: Double): List<TripReport.Phase> {
        val state = ActiveSubstanceState.from(entry.toDoseRecord(), P3Color.NEUTRAL, catalog, weightKg) ?: return emptyList()
        fun at(minutes: Double): Instant = entry.timestamp.toInstant().plusSeconds((minutes * 60).roundToLong())
        val rows = mutableListOf(
            TripReport.Phase("kicks in", at(state.onsetEndMinutes)),
            TripReport.Phase("peak begins", at(state.comeupEndMinutes)),
            TripReport.Phase("starts wearing off", at(state.peakEndMinutes)),
            TripReport.Phase("effects end", at(max(state.offsetEndMinutes, state.totalMinutes))),
        )
        // A profile with no onset phase puts "kicks in" on the dose itself, which
        // says nothing.
        if (state.onsetEndMinutes <= 0) rows.removeFirst()
        return rows
    }

    // MARK: - Helpers

    /**
     * A dose's display title, in upstream's `DoseTitle.resolve` precedence: the
     * brand, then the composite title captured at resolve time, then the
     * catalog's canonical title, then the raw logged name.
     */
    private fun titleFor(entry: DoseEntryEntity, substance: Substance?): String {
        entry.productName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        entry.displayNameSnapshot?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return substance?.displayTitle ?: entry.substance
    }

    /** Trim raw CYP/FDA-label fragments from an interaction description. */
    private fun cleanDescription(raw: String): String {
        val trimmed = raw.trim()
        val end = trimmed.indexOf('.')
        if (end >= 0) {
            val first = trimmed.substring(0, end).trim()
            if (first.length >= 12) return "$first."
        }
        return trimmed
    }

    private fun DoseEntryEntity.toDoseRecord(): DoseRecord = DoseRecord(
        substance = substance,
        amount = amount,
        unit = unit,
        route = route,
        timestamp = timestamp.toInstant(),
        isUnknownDose = isUnknownDose,
        releaseForm = releaseForm,
        productName = productName,
        saltForm = saltForm,
        isomer = isomer,
        substanceUID = substanceUID,
    )
}
