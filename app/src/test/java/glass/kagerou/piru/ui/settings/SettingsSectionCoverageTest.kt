package glass.kagerou.piru.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import glass.kagerou.piru.data.AppSettingsStore
import glass.kagerou.piru.data.export.PiruSettingsData
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.lang.reflect.Modifier
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import glass.kagerou.piru.PiruTestApplication

/**
 * The export's `settings` section covers every preference this build stores.
 *
 * ## The defect this exists for
 * The section carried **two** keys while the store grew to eleven. Nothing failed: the export reported success and
 * dropped a timeline layout, a quick-log dock arrangement and a tab bar, so a backup could not restore them and no
 * message said so. The omission started as a decision — "writing keys this build cannot honour is worse than omitting
 * them" — and became neglect as the store gained preferences, which is a failure mode no amount of care in the
 * original comment prevents.
 *
 * ## Why reflection rather than a hand-written list
 * A hand-written list of the store's keys is the very thing that goes stale. This reads the store's own `KEY_`
 * constants, so **adding a twelfth preference without exporting it fails here** — which is the whole point, and the
 * only form of the check that cannot silently rot.
 *
 * The assertion is one-directional on purpose: every store key must be in the section. A section key with no store key
 * behind it would be a different bug, and a wire key may legitimately differ from the store's own constant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PiruTestApplication::class)
class SettingsSectionCoverageTest {

    private fun storeKeys(): Set<String> {
        val fields = AppSettingsStore::class.java.declaredFields
            .filter { it.name.startsWith("KEY_") }
            .onEach { it.isAccessible = true }
        check(fields.isNotEmpty()) {
            "no KEY_ fields were found on AppSettingsStore — the reflection this test is built on has stopped " +
                "working, and a check that iterates nothing passes over nothing"
        }
        return fields
            .filter { Modifier.isStatic(it.modifiers) }
            .mapNotNull { it.get(null) as? String }
            .toSet()
    }

    /** Every key the store has is in the section. The regression, stated directly. */
    @Test
    fun `every stored preference is exported`() {
        val stored = storeKeys()
        println("SETTINGSPROBE stored=" + stored.sorted())
        println("SETTINGSPROBE exported=" + SettingsSection.KEYS.sorted())
        val missing = stored - SettingsSection.KEYS.toSet()
        missing.shouldBeEmpty()
    }

    /** And the two keys the section started with are still there, so a widening did not replace them. */
    @Test
    fun `the original two keys are still exported`() {
        SettingsSection.KEYS shouldContain PiruSettingsData.KEY_DAY_BOUNDARY_HOUR
        SettingsSection.KEYS shouldContain PiruSettingsData.KEY_STACK_REDOSES
    }

    /** The declared list has no duplicates: a key twice would apply twice and report itself twice. */
    @Test
    fun `the key list has no duplicates`() {
        SettingsSection.KEYS.size shouldBe SettingsSection.KEYS.toSet().size
    }

    /**
     * A round trip restores every preference.
     *
     * The coverage test proves each key is *listed*; this proves each one is actually **read and applied**, which is
     * the other half — a key can be in `KEYS` and still have no arm in `apply`.
     *
     * Non-default values throughout, because a round trip of defaults would pass on an implementation that applied
     * nothing at all.
     */
    @Test
    fun `every preference survives a round trip`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = AppSettingsStore(context)
        source.setDayBoundaryHour(4)
        source.setStackRedoses(false)
        source.setTimelineZoom(2.0)
        source.setTimelineCompressGaps(false)
        source.setTimelinePKCurves(true)
        source.setTimelineShowsAxis(false)
        source.setTimelineBubbleStyle("compact")
        source.setQuickLogFixedOrder(true)
        source.setQuickLogSuppressedRecents(setOf("a,b:c", "with space", "ümlaut"))
        source.setSourceOrder(listOf("pubchem", "who"))
        source.setHiddenTabs(listOf("library", "tools"))
        source.setTabLabelsShown(false)

        val section = SettingsSection.read(source)

        // A second store, cleared first so nothing of the source's values can be mistaken for a restore.
        val target = AppSettingsStore(context)
        target.clearForImport()
        val applied = SettingsSection.apply(section, target)
        println("SETTINGSPROBE applied=" + applied.sorted())

        applied.toSet() shouldBe SettingsSection.KEYS.toSet()
        target.dayBoundaryHour() shouldBe 4
        target.stackRedoses() shouldBe false
        target.timelineZoom() shouldBe 2.0
        target.timelineCompressGaps() shouldBe false
        target.timelinePKCurves() shouldBe true
        target.timelineShowsAxis() shouldBe false
        target.timelineBubbleStyle() shouldBe "compact"
        target.quickLogFixedOrder() shouldBe true
        // The set with the comma, the space and the umlaut: this is the separator-collision case, and a joined
        // encoding that used a printable character would merge the first two entries.
        target.quickLogSuppressedRecents() shouldBe setOf("a,b:c", "with space", "ümlaut")
        target.sourceOrder() shouldBe listOf("pubchem", "who")
        target.hiddenTabs() shouldBe listOf("library", "tools")
        target.tabLabelsShown() shouldBe false
    }

    /**
     * An **unset** list is written as null and leaves the local value alone.
     *
     * `sourceOrder()` and `hiddenTabs()` return null for "never set", which is not "set to nothing". Writing an empty
     * array for them would make an import **clear** a user's arrangement — the difference between "this install has no
     * opinion" and "this install says there are none".
     */
    @Test
    fun `an unset list does not clear the local one`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = AppSettingsStore(context)
        source.clearForImport()
        // Deliberately never set on the source.
        val section = SettingsSection.read(source)

        val target = AppSettingsStore(context)
        target.setSourceOrder(listOf("kept"))
        target.setHiddenTabs(listOf("kept"))
        SettingsSection.apply(section, target)

        target.sourceOrder() shouldBe listOf("kept")
        target.hiddenTabs() shouldBe listOf("kept")
    }

    /**
     * The two kinds of list preference behave differently, and both differences are deliberate.
     *
     * **The quick-log suppressed set keeps an empty set as a value.** A user who removed every chip and backs up must
     * not have them resurrected by their own restore, so `emptySet()` is stored and applied. That is what
     * `setQuickLogSuppressedRecents` uses a joined string for rather than removing the key.
     *
     * **The source order and the hidden tabs treat empty as "no preference".** A ranking of nothing and no ranking
     * name the same state, so the store removes the key and the accessor returns null. The section writes that null,
     * the import skips it, and the local arrangement survives — which is the behaviour asserted in
     * `an unset list does not clear the local one`.
     *
     * Both halves are asserted here because the asymmetry is the thing a future reader will question, and a test that
     * states why is worth more than one that only states what.
     */
    @Test
    fun `an empty suppressed set is a value and an empty ranking is not`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // The suppressed set: an empty set is a value, so a restore clears the target's chips.
        val emptied = AppSettingsStore(context)
        emptied.clearForImport()
        emptied.setQuickLogSuppressedRecents(emptySet())
        val section = SettingsSection.read(emptied)

        val target = AppSettingsStore(context)
        target.setQuickLogSuppressedRecents(setOf("should go"))
        val applied = SettingsSection.apply(section, target)

        applied shouldContain PiruSettingsData.KEY_QUICK_LOG_SUPPRESSED
        target.quickLogSuppressedRecents() shouldBe emptySet()

        // The source order: empty is "no preference", so `read` writes null and the import leaves the target alone.
        val noRanking = AppSettingsStore(context)
        noRanking.clearForImport()
        noRanking.setSourceOrder(emptyList())
        // The store's own statement of the rule, asserted rather than assumed.
        noRanking.sourceOrder() shouldBe null

        val ranking = AppSettingsStore(context)
        ranking.setSourceOrder(listOf("kept"))
        SettingsSection.apply(SettingsSection.read(noRanking), ranking)
        ranking.sourceOrder() shouldBe listOf("kept")
    }
}
