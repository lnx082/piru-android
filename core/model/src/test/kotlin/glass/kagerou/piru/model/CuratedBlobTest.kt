package glass.kagerou.piru.model

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * The curated editorial blobs the `substances` table carries as JSON text.
 *
 * These are hand-authored by the pipeline and read by the detail screen, so the
 * wire shape is a contract between two codebases that never compile together.
 * The fixtures below are copied verbatim (trimmed) from the shipped database —
 * `Misconceptions` from MDMA, `combinations` from Alprazolam, `water_heat` from
 * Amphetamine — so a rename on either side fails here rather than silently
 * emptying a card.
 */
class CuratedBlobTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `A misconception decodes with its citation roles`() {
        val raw = """
        [
          {
            "claim": "You must wait 3 months between uses",
            "correction": "An uncited community rule of thumb.",
            "citations": [
              { "citation": { "pmid": 26073279 }, "role": "refutes", "note": "Farr 2015" },
              {
                "citation": { "doi": "10.1000/xyz", "title": "A retracted paper" },
                "role": "retractedSource",
                "note": null
              }
            ]
          }
        ]
        """
        val myths = json.decodeFromString<List<MythBust>>(raw)
        myths.size shouldBe 1
        myths[0].claim shouldBe "You must wait 3 months between uses"
        // The role is the whole point of the type: a retracted source must never
        // render as support for the correction it was disproved by.
        myths[0].citations.map { it.role } shouldBe listOf(
            MythCitation.Role.REFUTES,
            MythCitation.Role.RETRACTED_SOURCE,
        )
        myths[0].citations[0].citation.pmid shouldBe 26073279
        myths[0].citations[0].citation.resolvedUrl shouldBe "https://pubmed.ncbi.nlm.nih.gov/26073279/"
        myths[0].citations[1].citation.resolvedUrl shouldBe "https://doi.org/10.1000/xyz"
        // A pull quote is present on a handful of flagship substances only.
        myths[0].pullQuote.shouldBeNull()
    }

    @Test
    fun `A combination decodes its severity`() {
        val raw = """
        [
          {
            "severity": "danger",
            "name": "Opioids",
            "description": "Both slow your breathing, by different routes."
          },
          { "severity": "caution", "name": "Gabapentinoids", "description": "They add sedation.", "note": "space the timing" }
        ]
        """
        val combinations = json.decodeFromString<List<Combination>>(raw)
        combinations.map { it.severity } shouldBe listOf(
            Combination.Severity.DANGER,
            Combination.Severity.CAUTION,
        )
        combinations[0].note.shouldBeNull()
        combinations[1].note shouldBe "space the timing"
    }

    @Test
    fun `Water and heat guidance decodes as a two-field card`() {
        val raw = """
        {
          "headline": "Sip to thirst",
          "body": "Stimulants blunt thirst without changing how much fluid you need."
        }
        """
        val guidance = json.decodeFromString<WaterHeatGuidance>(raw)
        guidance.headline shouldBe "Sip to thirst"
        (guidance.body.startsWith("Stimulants blunt thirst")) shouldBe true
    }

    @Test
    fun `An unknown key from a newer pipeline is tolerated`() {
        // The blobs are hand-authored and grow faster than the app is rebuilt. A
        // key this build has never heard of is a newer pipeline's addition, not a
        // malformed blob.
        val raw = """{ "headline": "Sip to thirst", "body": "…", "authored_by": "curator" }"""
        json.decodeFromString<WaterHeatGuidance>(raw).headline shouldBe "Sip to thirst"
    }
}
