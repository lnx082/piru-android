package glass.kagerou.piru.model

/**
 * The seed colour of a substance class — where its palette sits before a
 * substance's own identity jitters it.
 *
 * Ported from `Piru/Domain/SubstanceCategory+OklchSeed.swift`, hand-tuned in
 * Oklch so the classes are distinguishable from one another at a glance.
 *
 * These are *seeds*, not the colours anything is drawn in: [SubstanceColorGenerator]
 * pulls lightness into a legible band, scales the chroma, and jitters the hue by
 * the substance's own identity. Changing one of these recolours every substance
 * in that class — which is the point, and the reason they are stated once here
 * rather than per substance in the catalog.
 */
val SubstanceCategory.oklchSeed: Oklch
    get() = when (this) {
        SubstanceCategory.STIMULANT -> Oklch.of(0.765, 0.175, 62.6)
        SubstanceCategory.PSYCHEDELIC -> Oklch.of(0.615, 0.213, 312.4)
        SubstanceCategory.DISSOCIATIVE -> Oklch.of(0.707, 0.133, 233.9)
        SubstanceCategory.DYSDELIC -> Oklch.of(0.493, 0.132, 333.7)
        SubstanceCategory.DELIRIANT -> Oklch.of(0.579, 0.058, 94.6)
        SubstanceCategory.OPIOID -> Oklch.of(0.654, 0.232, 28.7)
        SubstanceCategory.BENZODIAZEPINE -> Oklch.of(0.603, 0.218, 257.4)
        SubstanceCategory.GABAPENTINOID -> Oklch.of(0.529, 0.191, 278.3)
        SubstanceCategory.EMPATHOGEN -> Oklch.of(0.650, 0.238, 17.9)
        SubstanceCategory.CANNABINOID -> Oklch.of(0.730, 0.194, 147.5)
        SubstanceCategory.NOOTROPIC -> Oklch.of(0.700, 0.111, 212.8)
        SubstanceCategory.AMPAKINE -> Oklch.of(0.812, 0.156, 138.5)
        SubstanceCategory.EUGEROIC -> Oklch.of(0.807, 0.137, 76.4)
        SubstanceCategory.DEPRESSANT -> Oklch.of(0.638, 0.073, 261.5)
        SubstanceCategory.OREXIN_ANTAGONIST -> Oklch.of(0.537, 0.117, 287.8)
        SubstanceCategory.ANTIDEPRESSANT -> Oklch.of(0.865, 0.177, 90.4)
        SubstanceCategory.ANTIPSYCHOTIC -> Oklch.of(0.748, 0.130, 189.1)
        SubstanceCategory.ANALGESIC -> Oklch.of(0.632, 0.064, 72.8)
        SubstanceCategory.ANTIHISTAMINE -> Oklch.of(0.636, 0.092, 356.7)
        SubstanceCategory.CARDIOVASCULAR -> Oklch.of(0.697, 0.193, 26.6)
        SubstanceCategory.ANTIMICROBIAL -> Oklch.of(0.766, 0.094, 207.8)
        SubstanceCategory.GASTROINTESTINAL -> Oklch.of(0.811, 0.152, 70.2)
        SubstanceCategory.RESPIRATORY -> Oklch.of(0.767, 0.108, 230.4)
        SubstanceCategory.ENDOCRINE -> Oklch.of(0.701, 0.162, 313.4)
        SubstanceCategory.IMMUNOLOGICAL -> Oklch.of(0.670, 0.177, 255.7)
        SubstanceCategory.SUPPLEMENT -> Oklch.of(0.778, 0.161, 150.2)
        SubstanceCategory.PEPTIDE -> Oklch.of(0.702, 0.100, 244.0)
        SubstanceCategory.ANTICONVULSANT -> Oklch.of(0.693, 0.114, 298.9)
        // The catch-all's seed is deliberately almost grey — see the generator's
        // achromatic branch, which spreads it around the wheel at low chroma
        // rather than leaving every unclassified substance one indistinguishable
        // shade.
        SubstanceCategory.OTHER -> Oklch.of(0.648, 0.007, 285.9)
    }
