package glass.kagerou.piru.data

import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.data.entity.SessionEntity
import glass.kagerou.piru.engine.ActiveSubstanceState
import glass.kagerou.piru.engine.PKModel
import glass.kagerou.piru.engine.SessionClustering
import glass.kagerou.piru.engine.SubstanceCatalog
import glass.kagerou.piru.engine.from
import glass.kagerou.piru.model.P3Color
import java.time.Instant
import java.util.UUID

/**
 * Assigns doses to sessions — the bridge between the pure clustering heuristic
 * and the store.
 *
 * Ported from `Piru/Utilities/SessionService.swift`.
 *
 * ## Sessions are decided at write time, and the user owns them after
 * The heuristic runs when a dose is logged, or once over history for a store that
 * predates the feature. It does **not** re-run on render. Everything afterwards —
 * merge, split, move, retitle — is the user's explicit say-so and is never
 * re-evaluated, which is why those operations have no heuristic in them at all.
 *
 * ## What a session is
 * An **analysis artifact**. Time is continuous and a dose is logged when it is
 * taken; the grouping is a reading of the log, not a container the user has to
 * open first. That is why nothing here can ever block a dose from being written.
 */
class SessionRepository(
    private val database: PiruDatabase,
    private val catalog: SubstanceCatalog,
    private val weightKg: () -> Double = { PKModel.REFERENCE_BODY_WEIGHT_KG },
) {

    private val sessions get() = database.sessionDao()
    private val doses get() = database.doseEntryDao()

    /**
     * A dose as the clustering heuristic reads it.
     *
     * Its effect duration is the **curve's** total — the length the timeline would
     * draw — resolved from the catalog. A dose with no profile (`null`) is not a
     * gap: the heuristic has a fallback for exactly that case, and a dose of
     * unknown amount or unmodelled form is an ordinary thing to have logged.
     */
    private fun clusterDose(entry: DoseEntryEntity): SessionClustering.Dose = SessionClustering.Dose(
        timestamp = entry.timestamp.toInstant(),
        effectDurationMinutes = ActiveSubstanceState.from(
            entry = entry.toDoseRecord(),
            tint = NEUTRAL_TINT,
            catalog = catalog,
            weightKg = weightKg(),
        )?.totalMinutes,
        isBackgroundMed = entry.isBackgroundMed,
    )

    // MARK: - Single-dose assignment

    /**
     * Assign one dose to the session it belongs to, or start a new one. Returns
     * the session's id.
     *
     * Precedence, matching upstream:
     * 1. **In-span** — a dose whose timestamp falls inside an existing session's
     *    dose range belongs to it, even when logged out of order, so a back-dated
     *    entry joins rather than spawning an overlapping session. Among
     *    transitional overlaps the most recent start wins.
     * 2. **Extend** — otherwise the candidate is the most recent session whose last
     *    dose is at or before this one, and the heuristic decides join-or-new from
     *    the trailing gap.
     * 3. **Prepend** — a back-dated dose may immediately *precede* an existing
     *    session ("15 minutes ago", logged after a dose that already opened one).
     *    Mirrors the extend rule: would the session's first dose join a session
     *    ending with this one?
     * 4. **New** — failing all three, start a fresh session.
     */
    suspend fun assignSession(doseRowId: Long): UUID? {
        val entry = doses.byRowId(doseRowId) ?: return null
        val target = entry.timestamp.toInstant()
        val horizon = SessionClustering.Constants.horizon

        val candidates = window(target, horizon)
        val spans = candidates.mapNotNull { session ->
            // A session with no other doses carries no span and cannot be joined —
            // except by the dose already in it, which is excluded here so a
            // re-assign is a no-op rather than a self-join.
            val attached = doses.dosesFor(session.id).filter { it.rowId != doseRowId }
            if (attached.isEmpty()) return@mapNotNull null
            val first = attached.minBy { it.timestamp }
            val last = attached.maxOf { it.timestamp }
            Span(session, attached, first.timestamp.toInstant(), last.toInstant())
        }

        // 1. In-span. `spans` is newest-start first, so the first match is the
        //    most recent start, which is the tie-break.
        for (span in spans) {
            if (target >= span.first && target <= span.last) {
                return attach(entry, span.session)
            }
        }

        // 2. Extend.
        val candidate = spans.filter { it.last <= target }.maxByOrNull { it.last }
        if (candidate != null) {
            val open = SessionClustering.OpenSession.from(
                candidate.doses.sortedBy { it.timestamp }.map { clusterDose(it) },
            )
            if (SessionClustering.placement(clusterDose(entry), open) == SessionClustering.Placement.JOIN) {
                return attach(entry, candidate.session)
            }
        }

        // 3. Prepend.
        val next = spans.filter { it.first >= target }.minByOrNull { it.first }
        if (next != null) {
            val endingWithEntry = SessionClustering.OpenSession.from(listOf(clusterDose(entry)))
            if (SessionClustering.placement(clusterDose(next.firstEntry), endingWithEntry) ==
                SessionClustering.Placement.JOIN
            ) {
                return attach(entry, next.session)
            }
        }

        // 4. New — but only for a dose that has no session yet.
        //
        //    Falling through here for an already-grouped dose would *move* it into a
        //    fresh session, silently regrouping history. Upstream never hits this
        //    (it assigns once, at log time), so the behaviour is unobserved there;
        //    here the function is idempotent instead, which is what a retry after a
        //    failed write — or a re-import — needs it to be.
        entry.sessionId?.let { existing -> return existing }

        val session = SessionEntity(id = UUID.randomUUID(), startDate = entry.timestamp)
        sessions.insert(session)
        return attach(entry, session)
    }

    // MARK: - Bulk backfill

    /**
     * Cluster every session-less dose into new sessions.
     *
     * Clusters the unassigned doses **among themselves**, leaving existing
     * sessions intact — so a fresh store gets a complete grouping and an import
     * builds its own, without ever disturbing a merge or split the user made.
     *
     * ## Deliberately has no "already done" flag
     * Upstream's note is worth keeping: the flag-gated version stranded data. A
     * launch that ran against a store which was momentarily unavailable set the
     * flag with zero doses, and when the real data appeared every dose stayed
     * session-less and rendered as its own session. Sweeping unconditionally
     * self-heals that, and any imported or recovered history, at the cost of one
     * query that normally returns nothing.
     */
    suspend fun ensureSessionsPopulated(): Int {
        val unassigned = doses.unassigned()
        var created = 0
        if (unassigned.isNotEmpty()) {
            val groups = SessionClustering.cluster(unassigned.map { clusterDose(it) })
            for (group in groups) {
                val members = group.map { unassigned[it] }
                val start = members.minOf { it.timestamp }
                val session = SessionEntity(id = UUID.randomUUID(), startDate = start)
                sessions.insert(session)
                for (dose in members) attach(dose, session)
                sessions.refreshDoseBounds(session.id)
                created++
            }
        }
        // Rows predating `last_dose_date` self-heal here. Correctness never
        // depends on it having run — `assignSession` always fetches null-bound rows
        // — but a healed row graduates into the windowed fetch.
        sessions.refreshAllDoseBounds()
        return created
    }

    // MARK: - Manual overrides

    /** Move every dose of [sourceId] into [targetId] and delete the emptied source. */
    suspend fun merge(sourceId: UUID, targetId: UUID) {
        if (sourceId == targetId) return
        for (dose in doses.dosesFor(sourceId)) {
            doses.update(dose.copy(sessionId = targetId))
        }
        sessions.deleteById(sourceId)
        sessions.refreshDoseBounds(targetId)
    }

    /**
     * Move [pivotRowId] and every later dose into a new session. Null when the
     * pivot is already the first dose — there would be nothing left behind.
     */
    suspend fun split(sessionId: UUID, pivotRowId: Long): UUID? {
        val ordered = doses.dosesFor(sessionId)
        val index = ordered.indexOfFirst { it.rowId == pivotRowId }
        if (index <= 0) return null
        val moving = ordered.subList(index, ordered.size).toList()
        val created = SessionEntity(id = UUID.randomUUID(), startDate = moving.first().timestamp)
        sessions.insert(created)
        for (dose in moving) doses.update(dose.copy(sessionId = created.id))
        sessions.refreshDoseBounds(sessionId)
        sessions.refreshDoseBounds(created.id)
        return created.id
    }

    /** Move one dose to [targetId], deleting its old session when that empties. */
    suspend fun move(doseRowId: Long, targetId: UUID) {
        val dose = doses.byRowId(doseRowId) ?: return
        val sourceId = dose.sessionId
        if (sourceId == targetId) return
        doses.update(dose.copy(sessionId = targetId))
        sessions.refreshDoseBounds(targetId)
        if (sourceId != null) {
            if (doses.dosesFor(sourceId).isEmpty()) sessions.deleteById(sourceId)
            else sessions.refreshDoseBounds(sourceId)
        }
    }

    /**
     * Set or clear a session's title. Blank trims to null, so a cleared field is
     * the absence of a title rather than an empty one.
     */
    suspend fun setTitle(sessionId: UUID, title: String) {
        val session = sessions.byId(sessionId) ?: return
        val trimmed = title.trim().ifEmpty { null }
        if (session.title == trimmed) return
        sessions.update(session.copy(title = trimmed))
    }

    // MARK: - Internals

    /** One candidate session and the span its *other* doses occupy. */
    private class Span(
        val session: SessionEntity,
        val doses: List<DoseEntryEntity>,
        val first: Instant,
        val last: Instant,
    ) {
        val firstEntry: DoseEntryEntity get() = doses.minBy { it.timestamp }
    }

    /**
     * The sessions worth considering, newest first.
     *
     * Every placement rule requires the joined span to fit inside the horizon, so
     * only sessions whose persisted bounds come within one horizon of the target
     * can matter. The bounds only *bound* the fetch — the true span is re-derived
     * from the doses below, because a merged session's stored bounds are its
     * extremes rather than its length.
     */
    private suspend fun window(target: Instant, horizonSeconds: Double): List<SessionEntity> {
        val lower = target.minusSeconds(horizonSeconds.toLong()).toEpochMilli()
        val upper = target.plusSeconds(horizonSeconds.toLong()).toEpochMilli()
        // Sorted here rather than in SQL: `inWindow` orders oldest-first, and rule
        // 1's tie-break needs the most recent start first.
        return sessions.inWindow(lower, upper).sortedByDescending { it.startDate }
    }

    /** Point [entry] at [session] and refresh the session's bounds. */
    private suspend fun attach(entry: DoseEntryEntity, session: SessionEntity): UUID {
        doses.update(entry.copy(sessionId = session.id))
        sessions.refreshDoseBounds(session.id)
        return session.id
    }

    private companion object {
        /**
         * The tint handed to the duration resolve.
         *
         * Never drawn — the resolve only reads the duration — so it carries no
         * meaning and must not be mistaken for a real identity colour.
         */
        val NEUTRAL_TINT = P3Color(red = 0.5, green = 0.5, blue = 0.5)
    }
}

/** The dose's row as the engine reads it. */
private fun DoseEntryEntity.toDoseRecord() = glass.kagerou.piru.engine.DoseRecord(
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
