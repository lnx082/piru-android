package glass.kagerou.piru.widget

import android.content.ComponentName
import android.content.Context
import android.appwidget.AppWidgetManager
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.data.PiruDatabase
import glass.kagerou.piru.data.entity.DailyDoseItemEntity
import glass.kagerou.piru.model.DoseFrequency
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The medication widget, against the store the app actually ships.
 *
 * ## What this covers, and the gap it closes
 * The widget's drawing is a pure function of [WidgetState], so the part that can be
 * *wrong* is the part deciding what that state is: which meds are due today, how a
 * reminder time becomes a slot, and whether a logged dose settles one. That read goes
 * through `MyMedsModel.slots` and the real DAOs, and it is what these specs exercise —
 * on a device, against the same Room store a placed widget would read.
 *
 * It also asserts the provider is declared, because the manifest entry is what the
 * launcher enumerates at all: a receiver without it compiles, passes every unit test,
 * and never appears in the widget picker.
 *
 * ## Why the real database and not an in-memory one
 * `WidgetState.load` opens `PiruDatabase` by name — deliberately, because a widget can
 * be drawn before any activity has started. So this seeds the real store and removes
 * what it added afterwards; an in-memory database would test a store the widget never
 * reads.
 *
 * JUnit 4 through AndroidJUnitRunner, so the expression-bodied methods say `: Unit`
 * explicitly: kotest's `shouldBe` returns its receiver, and a method whose inferred
 * return type is not `void` is rejected by the runner.
 */
@RunWith(AndroidJUnit4::class)
class TodayMedsWidgetTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database: PiruDatabase get() = (context as PiruApplication).database
    private val inserted = mutableListOf<DailyDoseItemEntity>()

    private fun item(
        substance: String,
        sortOrder: Int,
        reminderTimes: String,
        frequency: DoseFrequency = DoseFrequency.DAILY,
    ): DailyDoseItemEntity = DailyDoseItemEntity(
        substance = substance,
        amount = 10.0,
        sortOrder = sortOrder,
        reminderTimesJson = reminderTimes,
        frequencyRaw = frequency.wireValue,
        startDate = Date(0),
    )

    private suspend fun seed(vararg items: DailyDoseItemEntity) {
        for (entry in items) {
            database.dailyDoseItemDao().insert(entry)
            inserted += entry
        }
    }

    @After
    fun removeWhatThisAdded() = runBlocking {
        // Deleted by value rather than by row id: `insert` returns the id, but the
        // entity is what `delete` takes, and the two must agree or the cleanup is a
        // no-op that leaves probe meds in the emulator's real journal.
        for (entry in inserted) database.dailyDoseItemDao().delete(entry)
        inserted.clear()
        Unit
    }

    /**
     * The slots, the counts, and the due flag — the three things the drawing reads.
     */
    @Test
    fun aDueMedBecomesARowWithItsTimeAndDose(): Unit = runBlocking {
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val local = now.atZone(zone)
        val minutes = local.hour * 60 + local.minute
        // One slot well behind now and one well ahead, so the due flag is exercised
        // both ways from a single item. Wrapped into 0..1439 so a test running near
        // midnight still produces two valid times rather than a negative one.
        val past = ((minutes - 120) + 1440) % 1440
        val future = (minutes + 120) % 1440

        seed(item("WidgetProbe", sortOrder = 999_999, reminderTimes = "[$past,$future]"))

        val state = WidgetState.load(context, now)

        val mine = state.rows.filter { it.name == "WidgetProbe" }
        mine.size shouldBe 2
        // Exactly one of the two is due: the one whose minute has passed.
        mine.count { it.isDueNow(minutes) } shouldBe 1
        state.totalCount shouldBeGreaterThan 0
        mine.first().doseText shouldBe "10 mg"
        // The row's id is stable and identity-keyed rather than name-keyed.
        mine.all { it.id.contains("999999") } shouldBe true
    }

    /**
     * A med whose frequency excludes today contributes no slots.
     *
     * The difference between a widget that says what is due and one that invents it:
     * `AdherenceCalculator.isDue` is the gate, and a weekly med on the wrong day must
     * not appear.
     */
    @Test
    fun aMedNotDueTodayContributesNoRow(): Unit = runBlocking {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        // Started tomorrow, weekly: it cannot be due today whatever the day is.
        val tomorrow = now.atZone(zone).toLocalDate().plusDays(1)
            .atStartOfDay(zone).toInstant()
        seed(
            item("WidgetNotToday", sortOrder = 999_998, reminderTimes = "[600]")
                .copy(
                    frequencyRaw = DoseFrequency.WEEKLY.wireValue,
                    startDate = Date(tomorrow.toEpochMilli()),
                ),
        )

        WidgetState.load(context, now).rows.map { it.name } shouldNotContain "WidgetNotToday"
        Unit
    }

    /**
     * Taking every slot reports as done, which is what the header's "All taken" reads.
     *
     * Asserted through `WidgetState`'s own derivation rather than by drawing, so it
     * covers the branch the tile shows without needing a launcher to render one.
     */
    @Test
    fun allSlotsTakenMakesTheStateSaySo(): Unit {
        val rows = listOf(
            WidgetState.Row("a#0", "A", "10 mg", 480, taken = true, isQuiet = false),
            WidgetState.Row("b#0", "B", "10 mg", 720, taken = true, isQuiet = false),
        )
        val state = WidgetState(rows = rows, takenCount = 2, totalCount = 2)
        state.allTaken shouldBe true
        // And the untaken case, so the flag is not simply always true.
        WidgetState(rows, takenCount = 1, totalCount = 2).allTaken shouldBe false
        Unit
    }

    /**
     * Quiet meds fold into one line only from two upward, matching the card's rule.
     */
    @Test
    fun quietMedsFoldOnlyFromTwoUpward(): Unit {
        fun quiet(name: String) = WidgetState.Row("$name#0", name, "10 mg", null, false, isQuiet = true)
        fun loud(name: String) = WidgetState.Row("$name#0", name, "10 mg", 480, false, isQuiet = false)

        val one = WidgetState(listOf(loud("A"), quiet("B")), 0, 2)
        one.quietRows.size shouldBe 0
        one.loudRows.size shouldBe 2

        val two = WidgetState(listOf(loud("A"), quiet("B"), quiet("C")), 0, 3)
        two.quietRows.size shouldBe 2
        two.loudRows.map { it.name } shouldBe listOf("A")
        Unit
    }

    /**
     * The launcher's view: the receiver is declared and carries its provider metadata.
     *
     * The metadata is what an `AppWidgetProviderInfo` is parsed from, and without it the
     * widget is absent from the picker while the receiver still resolves.
     */
    @Test
    fun theProviderIsDeclaredWithItsMetadata(): Unit {
        val component = ComponentName(context, TodayMedsWidgetReceiver::class.java)
        val info = context.packageManager.getReceiverInfo(component, PackageManager.GET_META_DATA)
        info.exported shouldBe true
        val xml = info.loadXmlMetaData(context.packageManager, "android.appwidget.provider")
        (xml != null) shouldBe true
        // `updatePeriodMillis="0"` is what hands the refresh schedule to
        // `MedWidgetWorker`, and it is read through the platform's own parsed
        // `AppWidgetProviderInfo` rather than the raw XML attribute: that class is the
        // thing the launcher reads, so asserting on it is asserting on the real value
        // rather than on the spelling of one attribute.
        val provider = AppWidgetManager.getInstance(context)
            .installedProviders
            .firstOrNull { it.provider == component }
        (provider != null) shouldBe true
        provider!!.updatePeriodMillis shouldBe 0L
        Unit
    }
}
