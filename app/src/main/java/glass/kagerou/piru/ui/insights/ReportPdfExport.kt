package glass.kagerou.piru.ui.insights

import android.content.Context
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.data.entity.DailyDoseItemEntity
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.engine.DoseRecord
import glass.kagerou.piru.engine.InteractionChecker
import glass.kagerou.piru.substance.DbSubstanceCatalog
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turn the store into the report's snapshot, then render it to a file.
 *
 * The counterpart of the iOS `ReportsView` PDF export, where the view builds the
 * snapshot on the main actor and renders and writes from a detached task. Same split
 * here, and for the same reason: [PdfReportWriter] reaches no store, so everything it
 * needs —?each substance's half-life, what each logged name resolves to, the drug
 * classes behind each interaction —?is resolved first and carried in. That is also what
 * lets the writer's whole layout be driven from a hand-built snapshot in a test.
 *
 * ## Why this lives in the app module
 * It needs three things only this module has: the summary aggregation
 * ([SummaryStatsResolver] —?the same one the Patterns screen uses, because the report
 * and the screen must not disagree about how many days were used), the report's copy as
 * string resources, and the interaction checker's class resolution. It was written into
 * `:core:data` first and does not belong there: that module can see none of them.
 */
internal object ReportPdfExport {

    /** Where the shared report is written. Must match the provider's declared path. */
    private const val DIRECTORY = "report"

    /**
     * Build the report and write it under the app's own files directory.
     *
     * @return the file, ready for `FileProvider.getUriForFile`.
     */
    suspend fun write(
        context: Context,
        app: PiruApplication,
        entries: List<DoseEntryEntity>,
        dailyDoseItems: List<DailyDoseItemEntity>,
        start: Instant,
        end: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now(),
    ): File = withContext(Dispatchers.Default) {
        val data = snapshot(context, app, entries, dailyDoseItems, start, end, zone, now)
        val bytes = PdfReportWriter.write(data)
        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        val file = File(directory, reportFilename(now, zone))
        file.writeBytes(bytes)
        file
    }

    /**
     * `Piru-report-2026-09-29-1030.pdf`.
     *
     * The date is in the user's own zone and pinned to [Locale.ROOT], matching the JSON
     * export's convention: this is a name a person reads in a share sheet, and a device
     * with a non-Latin numbering system would otherwise write its own digits into it.
     */
    internal fun reportFilename(now: Instant, zone: ZoneId): String {
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.ROOT)
            .withZone(zone)
            .format(now)
        return "Piru-report-$stamp.pdf"
    }

    /** The resolved snapshot, with every store lookup already made. */
    private suspend fun snapshot(
        // Not named `context`: inside a lambda argument that name resolves the Kotlin DSL
        // function rather than this parameter, which is a confusing way to fail.
        androidContext: Context,
        app: PiruApplication,
        entries: List<DoseEntryEntity>,
        dailyDoseItems: List<DailyDoseItemEntity>,
        start: Instant,
        end: Instant,
        zone: ZoneId,
        now: Instant,
    ): PdfReportWriter.ReportData {
        val inWindow = entries.filter { it.timestamp.toInstant() in start..end }
        // The bundled catalog is the engine's three ports at once — `SubstanceCatalog`
        // for name resolution, `PharmacologySource` for the PK parameters the summary
        // needs, and `InteractionData` for the rules. Held as its own type rather than
        // as `SubstanceCatalog`, because all three are wanted below and the one object
        // is what stops them disagreeing about what a name means.
        val catalog: DbSubstanceCatalog? = runCatching { app.catalog() }.getOrNull()

        val entrySnapshots = inWindow.map { entry ->
            val substance = catalog?.lookup(entry.substance)
            PdfReportWriter.EntrySnapshot(
                substance = entry.substance,
                amountDisplay = entry.amountDisplay,
                route = entry.route.wireValue,
                timestamp = entry.timestamp.toInstant(),
                notes = entry.notes,
                // The catalog's own spelling when the name resolved, the logged one
                // otherwise —?so the report reads in the catalog's words without losing
                // a dose the catalog has never heard of.
                displayName = substance?.displayTitle ?: entry.substance,
                identityKey = entry.identityKey,
                halfLifeMinutes = substance?.halfLifeMinutes,
            )
        }

        val summary = buildSummary(androidContext, app, inWindow, catalog, start, end)
        val interactions = compressInteractions(interactionRows(inWindow, catalog))
        val substanceStats = substanceSummary(entrySnapshots)

        return PdfReportWriter.ReportData(
            entries = entrySnapshots,
            dailyDoseItems = dailyDoseItems.map {
                PdfReportWriter.DailyDoseSnapshot(
                    substance = it.substance,
                    amount = it.amount,
                    unit = it.unit,
                    route = it.route.wireValue,
                    sortOrder = it.sortOrder,
                )
            },
            findings = summary?.let {
                findings(
                    report = it,
                    interactions = interactions,
                    // The unit's name is copy, so it is resolved here where a
                    // `Context` exists rather than reached for from the renderer.
                    unitLabel = UnitLabel { res -> androidContext.getString(res) },
                )
            }.orEmpty(),
            interactions = interactions,
            duplicates = duplicates(entrySnapshots),
            substanceSummary = substanceStats,
            clinical = summary,
            start = start,
            end = end,
            generatedAt = now,
            zone = zone,
            copy = copy(androidContext),
        )
    }

    /**
     * The clinical aggregate over the window, through the resolver the Patterns screen
     * uses.
     *
     * Going through [SummaryStatsResolver] rather than summing here is the point: the
     * exposure currency —?MME for an opioid, diazepam-equivalents for a benzodiazepine,
     * multiples of a common dose otherwise —?is decided in one place, and a report that
     * decided it differently would put a different peak-day number in front of a
     * clinician than the app shows the user.
     */
    private suspend fun buildSummary(
        androidContext: Context,
        app: PiruApplication,
        entries: List<DoseEntryEntity>,
        catalog: DbSubstanceCatalog?,
        start: Instant,
        end: Instant,
    ): JournalSummary? {
        if (entries.isEmpty()) return null
        val resolvedCatalog = catalog ?: return null
        val tints = app.palette().tintsFor(entries.map { it.substance }.distinct())
        val (substances, doses) = SummaryStatsResolver.resolve(
            entries = entries,
            catalog = resolvedCatalog,
            pharmacology = resolvedCatalog,
            tintMap = tints,
            end = end,
        )
        if (substances.isEmpty()) return null
        return SummaryStats.report(
            substances = substances,
            doses = doses,
            start = start,
            end = end,
            // The user's own day boundary, not the engine default. This passed `null`, which
            // meant a report's session days were computed from 4 AM whatever the setting said —
            // so a reader who set their day to run at 2 AM would get days the app itself does
            // not use, in the one artefact they hand to somebody else.
            calendar = InsightsCalendar.ambient(
                glass.kagerou.piru.data.AppSettingsStore(androidContext).storedDayBoundaryHour(),
            ),
        )
    }

    /** The interaction rows for the window, with both sides' classes already resolved. */
    private fun interactionRows(
        entries: List<DoseEntryEntity>,
        catalog: DbSubstanceCatalog?,
    ): List<InteractionRow> {
        // The catalog is both halves of the checker —?`InteractionData` for the rules
        // and `SubstanceCatalog` for name resolution —?so the two cannot disagree about
        // what a name means. Same arrangement the Interactions screen uses.
        val resolvedCatalog = catalog ?: return emptyList()
        val checker = InteractionChecker(resolvedCatalog, resolvedCatalog)
        val names = entries.map { it.substance }.distinct()
        val records = entries.map { it.toReportDoseRecord() }
        return checker.checkBatch(names, records).map { result ->
            InteractionRow(
                severity = result.severity,
                substanceA = result.substanceA,
                substanceB = result.substanceB,
                description = result.description,
                drugClassesA = checker.drugClasses(result.substanceA),
                drugClassesB = checker.drugClasses(result.substanceB),
            )
        }
    }

    /** One row per substance, busiest first, carrying the half-life the PK charts need. */
    private fun substanceSummary(entries: List<PdfReportWriter.EntrySnapshot>): List<PdfReportWriter.SubstanceStat> =
        entries
            .groupBy { it.displayName.lowercase(Locale.ROOT) }
            .map { (_, group) ->
                val first = group.first()
                PdfReportWriter.SubstanceStat(
                    displayName = first.displayName,
                    totalDoses = group.size,
                    halfLifeMinutes = first.halfLifeMinutes,
                )
            }
            .sortedByDescending { it.totalDoses }

    /**
     * Names that resolve to one substance —?a brand beside its generic.
     *
     * Keyed on the identity the dose row carries rather than on the name, which is the
     * alias table's whole job: it covers every brand and spelling the catalog knows
     * instead of the dozen a hand-written list could name.
     */
    private fun duplicates(entries: List<PdfReportWriter.EntrySnapshot>): List<PdfReportWriter.DuplicateGroup> =
        entries
            .mapNotNull { entry -> entry.identityKey?.let { it to entry.displayName } }
            .groupBy({ it.first }, { it.second })
            .filter { (_, names) -> names.distinct().size > 1 }
            .map { (_, names) -> PdfReportWriter.DuplicateGroup(names.distinct().sorted(), names.size) }
            .sortedByDescending { it.totalEntries }

    /** The report's copy, resolved once from resources. */
    private fun copy(context: Context): PdfReportWriter.ReportData.Copy = PdfReportWriter.ReportData.Copy(
        title = context.getString(R.string.toolsb_pdf_title),
        period = context.getString(R.string.toolsb_pdf_period),
        generated = context.getString(R.string.toolsb_pdf_generated),
        currentMedications = context.getString(R.string.toolsb_pdf_current_medications),
        substance = context.getString(R.string.toolsb_pdf_col_substance),
        dose = context.getString(R.string.toolsb_pdf_col_dose),
        schedule = context.getString(R.string.toolsb_pdf_col_schedule),
        keyFindings = context.getString(R.string.toolsb_pdf_key_findings),
        interactions = context.getString(R.string.toolsb_pdf_interactions),
        interactionFootnote = context.getString(R.string.toolsb_pdf_interaction_footnote),
        duplicateTitle = context.getString(R.string.toolsb_pdf_duplicate_title),
        duplicateBlurb = context.getString(R.string.toolsb_pdf_duplicate_blurb),
        substanceSummary = context.getString(R.string.toolsb_pdf_substance_summary),
        totalDoses = context.getString(R.string.toolsb_pdf_total_doses),
        medications = context.getString(R.string.toolsb_pdf_medications),
        pkProfiles = context.getString(R.string.toolsb_pdf_pk_profiles),
        usageLog = context.getString(R.string.toolsb_pdf_usage_log),
        notes = context.getString(R.string.toolsb_pdf_notes),
        disclaimer = context.getString(R.string.toolsb_pdf_disclaimer),
        page = context.getString(R.string.toolsb_pdf_page),
        moreSubstance = context.getString(R.string.toolsb_pdf_more_substances),
        daysUsed = context.getString(R.string.toolsb_pdf_days_used),
    )
}

/**
 * A dose row as the interaction checker reads it.
 *
 * Its own mapper rather than a shared one: the checker wants the five identity fields
 * it matches on, and a mapper that also resolved durations would make this export wait
 * on the catalog for numbers the interaction pass never looks at. The two other mappers
 * in this module exist for the same reason, each for its own reader.
 */
private fun DoseEntryEntity.toReportDoseRecord(): DoseRecord = DoseRecord(
    substance = substance,
    amount = amount,
    unit = unit,
    route = route,
    timestamp = timestamp.toInstant(),
    isUnknownDose = isUnknownDose,
    // Carried so the printed table agrees with the journal it came from: a PDF that prints an estimate as though
    // it were measured is the one place a reader cannot check against the app.
    isApproximate = isApproximate,
    releaseForm = releaseForm,
    productName = productName,
    saltForm = saltForm,
    isomer = isomer,
    substanceUID = substanceUID,
)
