package glass.kagerou.piru.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.R
import glass.kagerou.piru.model.Physicochemical
import glass.kagerou.piru.model.Substance
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme
import java.util.Locale
import glass.kagerou.piru.model.PeptideProfile
import glass.kagerou.piru.model.SubstanceOverview
import glass.kagerou.piru.model.ToleranceInfo
import glass.kagerou.piru.model.Citation
import glass.kagerou.piru.data.entity.InventoryItemEntity

/**
 * The substance page's own sections, split out of the screen that composes them.
 *
 * ## Why a separate file
 * `SubstanceDetailScreen.kt` was already carrying the header, the dose ladder, the duration trio, the
 * mechanism card, the misconception, the combinations and the footer. The audit lists roughly
 * twenty-five sections the page is missing, and adding them in place would make that file the kind
 * this port has already been bitten by — `InteractionTimelineScreen.kt` reached 1,661 lines and two of
 * its calculators were untestable because of it.
 *
 * Nothing here reads the catalogue or holds state: every section takes a `Substance` and draws what is
 * on it, so a section can be rendered in a JVM spec with a constructed model.
 */

/**
 * Physicochemical descriptors: the numbers that describe the molecule.
 *
 * ## Why the units are not converted
 * Upstream prints SI with the same values, and a reader comparing a logP here against one in a paper
 * expects the same number rather than a rounded one. Å² is spelled out rather than written as "A2",
 * which is what a plain-ASCII fallback would produce and is not what the field means.
 *
 * ## Why LD50 is in this card and not a safety card
 * It is a descriptor — an order-of-magnitude toxicity figure from rodent studies — and grouping it with
 * the boiling point is what keeps it from reading as advice. The header the card carries, and the note
 * under the toxicity rows, are the whole reason it is safe to show at all.
 */
@Composable
fun PhysicochemicalCard(physicochemical: Physicochemical) {
    if (!physicochemical.hasAnyValue) return
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle(stringResource(R.string.shell_section_chemistry))
            physicochemical.logP?.let {
                DescriptorRow(stringResource(R.string.shell_descriptor_logp), formatDecimal(it))
            }
            physicochemical.tpsa?.let {
                DescriptorRow(stringResource(R.string.shell_descriptor_tpsa), "${formatDecimal(it)} Å²")
            }
            physicochemical.hba?.let {
                DescriptorRow(stringResource(R.string.shell_descriptor_hba), it.toString())
            }
            physicochemical.hbd?.let {
                DescriptorRow(stringResource(R.string.shell_descriptor_hbd), it.toString())
            }
            physicochemical.meltingPointC?.let {
                DescriptorRow(
                    stringResource(R.string.shell_descriptor_melting),
                    stringResource(R.string.shell_degrees_c, formatDecimal(it)),
                )
            }
            physicochemical.boilingPointC?.let {
                DescriptorRow(
                    stringResource(R.string.shell_descriptor_boiling),
                    stringResource(R.string.shell_degrees_c, formatDecimal(it)),
                )
            }
            physicochemical.ld50OralMgPerKg?.let {
                DescriptorRow(
                    stringResource(R.string.shell_descriptor_ld50_oral),
                    stringResource(R.string.shell_mg_per_kg, formatDecimal(it)),
                )
            }
            physicochemical.ld50DermalMgPerKg?.let {
                DescriptorRow(
                    stringResource(R.string.shell_descriptor_ld50_dermal),
                    stringResource(R.string.shell_mg_per_kg, formatDecimal(it)),
                )
            }
            // The note belongs to the toxicity rows above it, and it is not decoration: an LD50 read as
            // a dose limit is the single most dangerous misreading this card could invite.
            if (physicochemical.ld50OralMgPerKg != null || physicochemical.ld50DermalMgPerKg != null) {
                Text(
                    stringResource(R.string.shell_ld50_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }
    }
}

/**
 * The identity line: what the compound is called, and the registry numbers that identify it.
 *
 * ## Why the synonyms are here
 * A reader arriving from a brand name, a street name or a paper's spelling needs to know it is the same
 * compound. The catalogue's alias list is that answer, and it had no reader: `Substance.aliases` is
 * used for *searching* and never shown.
 *
 * The IUPAC name, InChIKey and PubChem id are the version of that which a paper can be checked against.
 */
@Composable
fun IdentityCard(substance: Substance) {
    val synonyms = buildList {
        // `localizedName` and `regionalName` are what the catalogue serves for this app's language, so
        // they lead: a Chinese reader sees 氯胺酮 before "Ketamine".
        substance.localizedName?.takeIf { it != substance.name }?.let { add(it) }
        substance.regionalName?.takeIf { it != substance.name }?.let { add(it) }
        addAll(substance.aliases)
    }.distinct().filterNot { it.equals(substance.name, ignoreCase = true) }

    val hasNumbers = substance.iupacName != null || substance.inchikey != null || substance.pubchemCID != null
    if (synonyms.isEmpty() && !hasNumbers) return

    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle(stringResource(R.string.shell_section_identity))
            if (synonyms.isNotEmpty()) {
                DescriptorRow(stringResource(R.string.shell_descriptor_also_known_as), synonyms.joinToString(", "))
            }
            substance.iupacName?.let {
                DescriptorRow(stringResource(R.string.shell_descriptor_iupac), it)
            }
            substance.inchikey?.let {
                DescriptorRow(stringResource(R.string.shell_descriptor_inchikey), it)
            }
            substance.pubchemCID?.let {
                DescriptorRow(stringResource(R.string.shell_descriptor_pubchem), it.toString())
            }
            substance.regulatoryStatus?.let {
                DescriptorRow(stringResource(R.string.shell_descriptor_regulatory), it)
            }
        }
    }
}

/**
 * The half-life, when the catalogue carries one.
 *
 * Its own card rather than a line in the PK card: `halfLifeMinutes` is what the engine scales every
 * modelled curve by, so it is the number a reader is most likely to have come for, and it is the one
 * whose unit is most often misread — 180 minutes is three hours, not three minutes.
 */
@Composable
fun HalfLifeCard(minutes: Double) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle(stringResource(R.string.shell_section_half_life))
            Text(
                formatHalfLife(minutes),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/**
 * A half-life in the unit a person would say it in.
 *
 * Under two hours it is minutes; under two days, hours; beyond that, days. The threshold is two rather
 * than one because "1.5 days" is worse than "36 hours" and "1.5 hours" is worse than "90 minutes".
 */
fun formatHalfLife(minutes: Double): String {
    val safe = minutes.coerceAtLeast(0.0)
    return when {
        safe < 120 -> String.format(Locale.ROOT, "%.0f min", safe)
        safe < 2 * 24 * 60 -> {
            val hours = safe / 60.0
            // A whole number of hours loses its decimal: "4 h" rather than "4.0 h".
            if (hours == hours.toLong().toDouble()) {
                String.format(Locale.ROOT, "%.0f h", hours)
            } else {
                String.format(Locale.ROOT, "%.1f h", hours)
            }
        }
        else -> String.format(Locale.ROOT, "%.1f d", safe / (24 * 60))
    }
}

/**
 * The long-form overview prose.
 *
 * ## Why the machine-translation badge is not optional
 * `SubstanceOverview.machineTranslated` says the catalogue translated this text rather than a person
 * writing it, and the flag exists because that is a real difference in trustworthiness. An unbadged
 * machine translation reads as curation, which is the one thing it is not — and prose about a drug is
 * exactly where a wrong nuance costs something.
 */
@Composable
fun OverviewCard(overview: SubstanceOverview) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle(stringResource(R.string.shell_section_overview))
            if (overview.machineTranslated) {
                Text(
                    stringResource(R.string.shell_overview_machine_translated),
                    style = MaterialTheme.typography.labelSmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
            Text(overview.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * The tolerance readout: how fast tolerance builds, and how long it takes to clear.
 *
 * ## Why an absent tolerance says so
 * A substance with no tolerance row is **not** a substance without tolerance — the catalogue has not
 * recorded it. `mayReportLimitedData` is the gate for whether saying that is even coherent for this
 * kind of compound, and where it is, the card says the data is missing rather than drawing nothing.
 * Drawing nothing would let a reader conclude the substance is not habit-forming.
 */
/**
 * The tolerance readout: how fast tolerance builds, and how long it takes to clear.
 *
 * ## What the model actually carries
 * `ToleranceInfo` has three fields: the days for tolerance to halve, the days for a full reset, and a
 * build rate ("rapid" / "moderate" / "slow"). There is no cross-tolerance list and no separate
 * clearance figure — I wrote those first, from what the card ought to say rather than from what the
 * catalogue has, and they do not exist.
 *
 * ## Why the build rate leads
 * It is the only field that answers "should I do this two days running", which is the question a
 * tolerance card is opened with. The two numbers are what stand behind the word.
 */
@Composable
fun ToleranceCard(tolerance: ToleranceInfo) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle(stringResource(R.string.shell_section_tolerance))
            DescriptorRow(
                stringResource(R.string.shell_descriptor_tolerance_build),
                tolerance.buildRate,
            )
            DescriptorRow(
                stringResource(R.string.shell_descriptor_tolerance_half_life),
                formatDays(tolerance.halfLife),
            )
            DescriptorRow(
                stringResource(R.string.shell_descriptor_tolerance_reset),
                formatDays(tolerance.fullResetDays),
            )
            // Named, because "rapid" is a word most readers will assume means a scale they already
            // know, and this one is the catalogue's own.
            Text(
                stringResource(R.string.shell_tolerance_note),
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
    }
}

/**
 * A number of days, in the largest unit that stays readable.
 *
 * Tolerance figures arrive in days and range from well under one to several weeks, so "0.3 d" and
 * "21.0 d" both want a different unit.
 */
fun formatDays(days: Double): String {
    val safe = days.coerceAtLeast(0.0)
    return when {
        safe < 1 -> String.format(Locale.ROOT, "%.0f h", safe * 24)
        safe < 14 -> if (safe == safe.toLong().toDouble()) {
            String.format(Locale.ROOT, "%.0f d", safe)
        } else {
            String.format(Locale.ROOT, "%.1f d", safe)
        }
        else -> String.format(Locale.ROOT, "%.1f weeks", safe / 7)
    }
}

/**
 * The peptide protocol: reconstitution and dosing, for the compounds that are sold as a powder.
 *
 * ## Why this is a table rather than prose
 * A peptide's protocol is a set of steps with numbers in them — the diluent volume, the resulting
 * concentration, the dose in units on a syringe — and prose is where those numbers get misread. Upstream
 * renders the same rows.
 *
 * ## Why it is separately gated
 * Only peptides have it, and the catalogue marks them. A card that drew for a compound without a
 * protocol would be inventing one, which for a reconstitution instruction is the worst possible thing
 * to get wrong.
 */
/**
 * The peptide profile: what is in the vial, and what to dissolve it in.
 *
 * ## What the model actually carries
 * `PeptideProfile` is the *preparation's* facts: the amino-acid sequence, the supplied form, a typical
 * vial size, the recommended solvent, a storage requirement, and an IU-per-mg bridge for the hormones
 * dosed in international units. It is not a dosing protocol, which is what I assumed first and what the
 * catalogue does not ship.
 *
 * ## Why the IU bridge is the row that matters
 * GH and HCG are dosed in IU and sold in mg, so a reader holding a vial has to convert. The catalogue
 * carries the factor and nothing read it, which made the one number that turns a vial into a dose
 * unreachable.
 */
@Composable
fun PeptideCard(profile: PeptideProfile) {
    if (!profile.hasAnyValue) return
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle(stringResource(R.string.shell_section_peptide))
            profile.sequence?.let {
                DescriptorRow(stringResource(R.string.shell_descriptor_sequence), it)
            }
            profile.suppliedForm?.let {
                DescriptorRow(
                    stringResource(R.string.shell_descriptor_supplied_form),
                    it.wireValue,
                )
            }
            profile.typicalVialMg?.let {
                DescriptorRow(
                    stringResource(R.string.shell_descriptor_vial),
                    stringResource(R.string.shell_mg, formatDecimal(it)),
                )
            }
            profile.reconstitutionSolvent?.let {
                DescriptorRow(stringResource(R.string.shell_descriptor_solvent), it)
            }
            profile.iuPerMg?.let {
                DescriptorRow(
                    stringResource(R.string.shell_descriptor_iu_per_mg),
                    stringResource(R.string.shell_iu_per_mg, formatDecimal(it)),
                )
            }
            profile.storage?.let { storage ->
                // The temperature is the enum; the light sensitivity and the stability window are its
                // own fields. A single joined line, because they are one instruction between them.
                val parts = buildList {
                    add(storage.temperature.wireValue)
                    if (storage.lightSensitive) add(stringResource(R.string.shell_storage_light))
                    storage.reconstitutedStabilityDays?.let {
                        add(stringResource(R.string.shell_storage_stability, formatDays(it)))
                    }
                }
                DescriptorRow(stringResource(R.string.shell_descriptor_storage), parts.joinToString(", "))
            }
        }
    }
}

/**
 * The salt and isomer forms the catalogue carries ladders for.
 *
 * ## Why this exists
 * A dose ladder is keyed by `(substance, route, salt, isomer)` — a salt and an isomer are different
 * ladders, which the reader's own doc says. The substance page collapsed every ladder for a route into
 * one list, so a compound with a hydrochloride and a free base showed one set of numbers with no
 * indication that the other exists, and an isomer's separate ladder was simply invisible.
 *
 * The lists are computed by the model from the ladders themselves, so this card cannot disagree with
 * what the dose card draws — it names what is there rather than asserting what should be.
 */
@Composable
fun FormsCard(availableSaltForms: List<String>, availableIsomers: List<String>) {
    if (availableSaltForms.isEmpty() && availableIsomers.isEmpty()) return
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle(stringResource(R.string.shell_section_forms))
            if (availableSaltForms.isNotEmpty()) {
                DescriptorRow(
                    stringResource(R.string.shell_descriptor_salts),
                    availableSaltForms.joinToString(", "),
                )
            }
            if (availableIsomers.isNotEmpty()) {
                DescriptorRow(
                    stringResource(R.string.shell_descriptor_isomers),
                    availableIsomers.joinToString(", "),
                )
            }
            // Said plainly, because a reader who has just seen two ladders collapsed into one dose
            // card will assume the numbers above already account for the difference.
            Text(
                stringResource(R.string.shell_forms_note),
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
    }
}

/**
 * The references behind the catalogue's facts.
 *
 * ## Why the source names in the footer are not this
 * The footer prints `substance.sources` — the *publishers* (PsychonautWiki, PDSP, DailyMed) — which says
 * where a fact came from at the level of a database. A citation is the paper, and `Substance.references`
 * had no reader at all, so the one thing a reader could check a number against was unreachable.
 *
 * A reference with no identifier is drawn as its title alone: `Citation.resolvedUrl` returns null for
 * the free-text labels the catalogue stores without a scheme, and those are still worth printing.
 */
@Composable
fun ReferencesCard(references: List<Citation>) {
    if (references.isEmpty()) return
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle(stringResource(R.string.shell_section_references))
            for (citation in references) {
                Text(
                    // The title, then whichever identifier exists. An entry with none of the three is
                    // possible — the catalogue has free-text labels — so the fallback keeps the row
                    // from rendering as an empty line.
                    listOfNotNull(
                        citation.title,
                        citation.doi,
                        citation.pmid?.let { "PMID $it" },
                        citation.url?.takeIf { citation.title == null && citation.doi == null },
                    ).joinToString(" · ").ifBlank { stringResource(R.string.shell_reference_untitled) },
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }
    }
}

/**
 * What the user has on hand for this substance.
 *
 * ## Why this is worth a card on a reference page
 * A substance page answers "what is this"; the question a reader arrives with when they have a stash is
 * "do I have any". Upstream puts the inventory row here for that reason, and this port's item DAO
 * already had `byIdentity` — nothing asked it.
 *
 * ## What it deliberately does not say
 * No restock advice and no warning: the low-stock notification is the place for that, and it needs the
 * threshold *and* the reorder history that only the inventory screen holds. This card reports a number
 * and nothing else.
 */
@Composable
fun InventoryCard(item: InventoryItemEntity) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle(stringResource(R.string.shell_section_inventory))
            DescriptorRow(
                stringResource(R.string.shell_inventory_on_hand),
                // Formatted rather than printed raw: `currentQuantity` is a `Double` that the restock
                // arithmetic accumulates, so it arrives as 8.999999 often enough to matter.
                stringResource(
                    R.string.shell_inventory_amount,
                    formatQuantity(item.currentQuantity),
                    item.unit,
                ),
            )
            item.doseSize?.let {
                DescriptorRow(
                    stringResource(R.string.shell_inventory_dose_size),
                    stringResource(R.string.shell_inventory_amount, formatQuantity(it), item.unit),
                )
            }
            item.lowStockThreshold?.let {
                DescriptorRow(
                    stringResource(R.string.shell_inventory_threshold),
                    stringResource(R.string.shell_inventory_amount, formatQuantity(it), item.unit),
                )
            }
        }
    }
}

/**
 * A quantity to at most one decimal, without a trailing zero.
 *
 * The restock arithmetic adds and subtracts `Double`s, so a stash of nine 1 mg doses reads as
 * `8.999999999`. Two decimals would still show `9.0`; one significant figure past the point is what a
 * person would write.
 */
fun formatQuantity(value: Double): String {
    // Clamped, because the restock arithmetic can go below zero when a dose is logged against an empty
    // stash, and "-3 mg on hand" is not a fact about a shelf.
    val rounded = Math.round(value.coerceAtLeast(0.0) * 10.0) / 10.0
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        String.format(Locale.ROOT, "%.1f", rounded)
    }
}

/**
 * What else is still in the body, from the log.
 *
 * ## Why the page says this
 * A substance page is read *while deciding*, and the decision depends on what is already active —
 * which is the one fact about the user that a reference page can honestly carry. Upstream draws the same
 * idea on a session; here it is derived from the log and the catalogue's own durations.
 *
 * ## Why it takes names rather than a query result
 * The caller has already read the log for its own purposes on some paths and has not on others, so the
 * set is a parameter: this draws what it is given and does not decide what is active. That keeps the
 * window arithmetic — which needs the catalogue and the user's weight — in one place rather than in a
 * card.
 */
@Composable
fun AlsoActiveCard(activeNames: List<String>) {
    if (activeNames.isEmpty()) return
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle(stringResource(R.string.shell_section_also_active))
            Text(activeNames.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.shell_also_active_note),
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
    }
}

/**
 * Whether the catalogue has dose data for this compound at all.
 *
 * ## Why a card rather than the absence of one
 * `hasNoDoseData` is true for a substance whose row exists but carries no ladder — a metabolite, a
 * research chemical nobody has assayed, a stub the catalogue keeps for name resolution. The page
 * already withheld the ladder card for those, but said nothing, so a reader saw a page missing its most
 * important section and no explanation. This says which it is.
 */
@Composable
fun LimitedDataCard() {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle(stringResource(R.string.shell_section_data_status))
            Text(
                stringResource(R.string.shell_no_dose_data),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** One label/value line, the label given a fixed column so the values line up. */
@Composable
private fun DescriptorRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = PiruTheme.colors.secondaryLabel,
            modifier = Modifier.width(112.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * A decimal without a spurious trailing zero.
 *
 * `%.1f` on 2.0 gives "2.0", which reads as a measured value with a precision the catalogue does not
 * claim; logP values arrive as `Double` from three different sources.
 */
private fun formatDecimal(value: Double): String =
    if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
    }
