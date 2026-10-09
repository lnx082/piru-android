package glass.kagerou.piru.substance

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Which molecule a preparation's pharmacology is read from.
 *
 * ## Why this has cases rather than only a card
 * The rule's failure mode is invisible on screen: a preparation that fails to borrow shows **its own** numbers, which
 * look exactly like borrowed ones. The reference records the consequence — a CB1 Ki of 40.7 nM filed under Cannabis
 * whose note says "Original Felder/Showalter measurement", where those papers assayed **THC**, and a citation that
 * resolved to a nursing-ethics bibliography.
 *
 * ## Why every case reloads, and clears afterwards
 * `ActiveIngredient` holds one process-wide map, installed at catalogue build. A case that left a mapping behind would
 * change what a later case sees — the cross-test ordering problem that made `MedsStoreWidgetRefreshTest` flake in a full
 * run and pass alone.
 */
class ActiveIngredientTest {

    @AfterEach
    fun clearMapping() {
        // Back to "every substance speaks for itself", which is the state before a catalogue loads.
        ActiveIngredient.load(emptyMap())
    }

    private val cannabisToThc = mapOf("cannabis" to "THC")

    /** The installed mapping resolves, and to the molecule rather than the preparation. */
    @Test
    fun `an installed preparation resolves to its molecule`() {
        ActiveIngredient.load(cannabisToThc)
        ActiveIngredient.resolve("Cannabis") shouldBe "THC"
        ActiveIngredient.pharmacologyName("Cannabis") shouldBe "THC"
        ActiveIngredient.borrows("Cannabis") shouldBe true
    }

    /** Case does not matter, because the map is keyed lowercased and a display title arrives capitalised. */
    @Test
    fun `resolution ignores case and surrounding space`() {
        ActiveIngredient.load(cannabisToThc)
        ActiveIngredient.resolve("CANNABIS") shouldBe "THC"
        ActiveIngredient.resolve("cannabis") shouldBe "THC"
        ActiveIngredient.resolve("  Cannabis  ") shouldBe "THC"
    }

    /**
     * Almost everything speaks for itself, and that is the **default** rather than a special case.
     *
     * The case that matters most in practice: with one mapping installed and tens of thousands of substances, a
     * resolver that borrowed by accident would attribute one substance's numbers to another.
     */
    @Test
    fun `a substance with no mapping speaks for itself`() {
        ActiveIngredient.load(cannabisToThc)
        ActiveIngredient.resolve("MDMA") shouldBe null
        ActiveIngredient.pharmacologyName("MDMA") shouldBe "MDMA"
        ActiveIngredient.borrows("MDMA") shouldBe false
    }

    /** Before any catalogue loads, every substance speaks for itself — the empty-map state. */
    @Test
    fun `an empty mapping borrows nothing`() {
        ActiveIngredient.load(emptyMap())
        ActiveIngredient.resolve("Cannabis") shouldBe null
        ActiveIngredient.pharmacologyName("Cannabis") shouldBe "Cannabis"
    }

    /** Null and blank names are answered rather than thrown at, because a screen may render before a record exists. */
    @Test
    fun `a blank or absent name borrows nothing`() {
        ActiveIngredient.load(cannabisToThc)
        ActiveIngredient.resolve(null) shouldBe null
        ActiveIngredient.resolve("") shouldBe null
        ActiveIngredient.resolve("   ") shouldBe null
        // And the query name falls back to the input, so a caller passing a blank gets a blank back rather than a throw.
        ActiveIngredient.pharmacologyName(null) shouldBe ""
    }

    /**
     * The map is keyed **lowercased on load**, whatever the caller installed.
     *
     * The case that catches a loader which forgot to normalise: the catalogue's canonical names are capitalised, and a
     * mapping installed verbatim would resolve for `"Cannabis"` and fail for `"cannabis"` — a difference no screen shows.
     */
    @Test
    fun `a mapping installed with capitals still resolves in any case`() {
        ActiveIngredient.load(mapOf("Cannabis" to "THC"))
        ActiveIngredient.resolve("cannabis") shouldBe "THC"
        ActiveIngredient.resolve("Cannabis") shouldBe "THC"
    }

    /** A later load replaces the earlier one wholesale, so a stale mapping cannot survive a catalogue change. */
    @Test
    fun `a second load replaces the first`() {
        ActiveIngredient.load(cannabisToThc)
        ActiveIngredient.resolve("Cannabis") shouldBe "THC"

        ActiveIngredient.load(mapOf("ayahuasca" to "DMT"))
        ActiveIngredient.resolve("Cannabis") shouldBe null
        ActiveIngredient.resolve("Ayahuasca") shouldBe "DMT"
    }
}
