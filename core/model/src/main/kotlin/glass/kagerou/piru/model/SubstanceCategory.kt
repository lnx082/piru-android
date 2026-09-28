package glass.kagerou.piru.model

/**
 * The class a substance belongs to.
 *
 * [wireValue] is the iOS `rawValue` and is what the bundled database and every
 * exported file carry — never derive a stored value from [name]. Several differ
 * outright ([GABAPENTINOID] is "GABAergic", [OREXIN_ANTAGONIST] is
 * "OrexinAntagonist").
 */
enum class SubstanceCategory(val wireValue: String) {
    STIMULANT("Stimulant"),
    PSYCHEDELIC("Psychedelic"),
    DISSOCIATIVE("Dissociative"),
    DYSDELIC("Dysdelic"),
    DELIRIANT("Deliriant"),
    OPIOID("Opioid"),
    BENZODIAZEPINE("Benzodiazepine"),
    GABAPENTINOID("GABAergic"),
    EMPATHOGEN("Empathogen"),
    CANNABINOID("Cannabinoid"),
    NOOTROPIC("Nootropic"),
    AMPAKINE("AMPAkine"),
    EUGEROIC("Eugeroic"),
    DEPRESSANT("Depressant"),
    OREXIN_ANTAGONIST("OrexinAntagonist"),
    ANTIDEPRESSANT("Antidepressant"),
    ANTIPSYCHOTIC("Antipsychotic"),
    ANALGESIC("Analgesic"),
    ANTIHISTAMINE("Antihistamine"),
    CARDIOVASCULAR("Cardiovascular"),
    ANTIMICROBIAL("Antimicrobial"),
    GASTROINTESTINAL("Gastrointestinal"),
    RESPIRATORY("Respiratory"),
    ENDOCRINE("Endocrine"),
    IMMUNOLOGICAL("Immunological"),
    SUPPLEMENT("Supplement"),
    PEPTIDE("Peptide"),
    ANTICONVULSANT("Anticonvulsant"),
    OTHER("Other"),
    ;

    /**
     * How much of a dose's effect the descending limb loses to acute
     * (within-session) tolerance. Zero for classes with no meaningful
     * tachyphylaxis.
     */
    val acuteToleranceFactor: Double
        get() = when (this) {
            STIMULANT -> 0.75
            EMPATHOGEN -> 0.70
            EUGEROIC -> 0.20
            DISSOCIATIVE -> 0.25
            else -> 0.0
        }

    /**
     * Proportions for synthesizing a renderable effect curve from endpoint-only
     * duration data — a `total` but no come-up/peak/offset, the LSD-oral class
     * where one source supplied only onset and total.
     *
     * [onset] is a fraction of `total`, used only when no onset phase exists.
     * The other three are relative weights that split the remaining active span
     * into the rising, plateau and falling shoulders of the bell. Shaped by
     * class pharmacology: psychedelics build slowly into a broad peak,
     * stimulants spike then taper (the descending limb is further crashed by
     * [acuteToleranceFactor]), opioids peak fast.
     *
     * See [DurationProfile.fillingMissingPhases].
     */
    val synthesizedPhaseShape: PhaseShape
        get() = when (this) {
            PSYCHEDELIC, DYSDELIC, DELIRIANT -> PhaseShape(0.08, 0.20, 0.30, 0.50)
            STIMULANT -> PhaseShape(0.06, 0.15, 0.20, 0.65)
            EMPATHOGEN -> PhaseShape(0.07, 0.18, 0.27, 0.55)
            EUGEROIC -> PhaseShape(0.08, 0.15, 0.35, 0.50)
            OPIOID, ANALGESIC -> PhaseShape(0.06, 0.16, 0.24, 0.60)
            DISSOCIATIVE -> PhaseShape(0.06, 0.17, 0.27, 0.56)
            BENZODIAZEPINE, DEPRESSANT, GABAPENTINOID, OREXIN_ANTAGONIST ->
                PhaseShape(0.07, 0.18, 0.30, 0.52)
            CANNABINOID -> PhaseShape(0.06, 0.18, 0.26, 0.56)
            else -> PhaseShape(0.08, 0.20, 0.25, 0.55)
        }

    companion object {
        /** Resolve a stored class name, or null when the database carries one this build predates. */
        fun fromWire(value: String): SubstanceCategory? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/** The relative weights [SubstanceCategory.synthesizedPhaseShape] splits a span into. */
data class PhaseShape(
    val onset: Double,
    val comeup: Double,
    val peak: Double,
    val offset: Double,
)
