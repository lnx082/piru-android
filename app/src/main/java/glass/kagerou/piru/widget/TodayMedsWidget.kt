package glass.kagerou.piru.widget

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import glass.kagerou.piru.MainActivity
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.ui.meds.MedSlot
import glass.kagerou.piru.ui.meds.MyMedsModel
import glass.kagerou.piru.ui.meds.SlotState
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Today's medication schedule on the home screen.
 *
 * Ported from `PiruWidget/TodayMedsWidget.swift` (612 lines), with one thing left out
 * on purpose — see "What is not here" below.
 *
 * ## Why this is the widget that came first
 * Upstream ships six. This is the one that earns a home-screen slot: the other five are
 * *readings* of the log (today's summary, the latest dose, the next dose), and a reading
 * is something you open the app for. A medication schedule is the surface where the
 * glance is most of the value — see the day's slots, see what is still due — and it is
 * the one upstream made interactive.
 *
 * ## It only reads
 * The widget paints a snapshot and opens the app when tapped. It writes nothing, and it
 * is not a cache the app reads back: `MyMedsModel.slots` is the single implementation of
 * "which slots exist and which are settled", called here exactly as the My Meds card
 * calls it. A second implementation in the widget is how the two would come to disagree
 * about whether a dose was taken, which is the one thing a medication checklist must
 * never do.
 *
 * ## What is not here: checking a slot off from the widget
 * Upstream's rows are buttons — take a med without opening the app. Glance can do that
 * through `ActionCallback`, and the honest reason it is not here yet is the row
 * identity: a callback is addressed by `ActionParameters`, and threading a per-row key
 * through each clickable needs the key defined on both sides. The version that shipped
 * in this file's first draft carried the id in a zero-sized node's content description,
 * which works and is an accessibility defect — a screen reader announces a row's
 * identity as a stray string. That is not a trade worth making for a convenience; the
 * next revision does it with `ActionParameters` properly, or not at all.
 *
 * So a tap opens My Meds, where the tap is one more and the accessibility is right.
 */
class TodayMedsWidget : GlanceAppWidget() {

    /** Shared by the widget and its state loader, so one filter finds both. */
    internal companion object {
        const val TAG = "PiruMedWidget"
    }


    /**
     * Two layouts come from one composition.
     *
     * `SizeMode.Exact` so Glance measures against the actual host size rather than
     * scaling a single bitmap: the small family then shows the header and the first row
     * or two, and the medium family shows the rest, without two sets of thresholds to
     * keep in step with the launcher's.
     */
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Off the main thread: this reads Room, and `provideGlance` runs in a scope the
        // host controls.
        val state = try {
            withContext(Dispatchers.IO) { WidgetState.load(context) }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // The host cancelled the session — a resize, a removal, a rebind. Rethrowing is
            // what lets the coroutine machinery unwind; swallowing it here would keep a
            // dead session alive.
            throw cancellation
        } catch (failure: Exception) {
            // The widget still renders, as an empty day. Glance's alternative is to
            // propagate, which the launcher shows as "there was a problem loading the
            // widget" — an error the user cannot act on, for a read that failed. The log
            // is what makes it diagnosable, because the tile cannot say what went wrong.
            Log.e(TAG, "widget state read failed; rendering an empty day", failure)
            WidgetState.EMPTY
        }
        provideContent {
            GlanceTheme {
                TodayMedsContent(state)
            }
        }
    }
}

/**
 * The receiver the manifest points at and the widget picker enumerates.
 *
 * `updatePeriodMillis` is deliberately zero in that manifest entry: this widget's
 * schedule has to refresh at times the platform's own timer cannot express — the moment
 * a slot falls due, and the moment the day rolls over — so it is refreshed by
 * `MedWidgetWorker` and by every write path instead. See that class's note.
 */
class TodayMedsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayMedsWidget()
}

/**
 * What the widget draws, resolved off the composition.
 *
 * A value snapshot rather than the entities: Room rows do not cross into a widget's
 * state, and the shapes the drawing needs are three fields per row anyway.
 */
internal data class WidgetState(
    val rows: List<Row>,
    val takenCount: Int,
    val totalCount: Int,
) {
    /** One line of the list. */
    data class Row(
        val id: String,
        val name: String,
        val doseText: String,
        /** Minutes from midnight, or null for a med with no set time. */
        val timeMinutes: Int?,
        val taken: Boolean,
        /** Quiet meds fold into one line when there are two or more. */
        val isQuiet: Boolean,
    ) {
        /**
         * Whether this slot is due right now: an untimed one always is, a timed one once
         * its minute has passed, and a settled one never — the "due" mark must not sit on
         * a row that is already answered.
         */
        fun isDueNow(nowMinutes: Int): Boolean {
            if (taken) return false
            val at = timeMinutes ?: return true
            return at <= nowMinutes
        }
    }

    val isEmpty: Boolean get() = rows.isEmpty()

    val allTaken: Boolean get() = rows.isNotEmpty() && takenCount == totalCount

    /**
     * The Supplements fold, at the same threshold the card uses: two or more quiet meds.
     * One quiet med is just a med, and a group named after its category is a worse label
     * than its own name.
     */
    val quietRows: List<Row> get() = if (rows.count { it.isQuiet } >= 2) rows.filter { it.isQuiet } else emptyList()

    val loudRows: List<Row> get() = if (quietRows.isEmpty()) rows else rows.filterNot { it.isQuiet }

    companion object {

        /**
         * Read the schedule out of the store.
         *
         * The same two reads the My Meds card makes — the daily items and the occurrence
         * rows — followed by the same `MyMedsModel.slots` call, so the widget cannot
         * drift from the card.
         *
         * Every failure returns empty rather than throwing: a widget that crashes its
         * host shows the launcher's error state, and "no meds today" is a worse-looking
         * but far more recoverable answer than a broken tile.
         */
        suspend fun load(context: Context, now: Instant = Instant.now()): WidgetState {
            val zone = ZoneId.systemDefault()
            val app = context.applicationContext as? PiruApplication
            return runCatching {
                // The application's own database, and **not** a second instance opened
                // with `PiruDatabase.open`. An earlier revision did open one, so the widget
                // could read before any activity had started; that traded a rare empty tile
                // for a Room builder running inside a widget-binding callback, on whatever
                // thread and process state the launcher happens to provide — and OEM builds
                // restrict exactly that. Opening a second connection to a WAL database from
                // a callback the launcher has a deadline on is the kind of thing that works
                // on an emulator and throws on a phone.
                //
                // The cost is bounded and the app covers it: with no database yet this
                // returns empty, and `MedWidgetRefresh.afterWrite` runs on every launch and
                // every dose commit, so the first frame after the app starts is the real
                // one rather than a stale one.
                val database = app?.database ?: return@runCatching EMPTY
                val items = database.dailyDoseItemDao().all()
                if (items.isEmpty()) return@runCatching EMPTY
                // Occurrences are keyed by their due day, and the slot pass reads the
                // state for the slots it derives; a day either side covers a device whose
                // clock has crossed midnight since the row was written.
                val today = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
                val occurrences = database.routineOccurrenceDao().all().filter {
                    val due = it.dueDay.toInstant()
                    due >= today.minusSeconds(86_400) && due < today.plusSeconds(2 * 86_400)
                }
                from(MyMedsModel.slots(items, occurrences, now, zone))
            }.getOrDefault(EMPTY)
        }

        internal fun from(slots: List<MedSlot>): WidgetState = WidgetState(
            rows = slots.map { slot ->
                Row(
                    id = slot.id,
                    name = slot.item.productName ?: slot.item.substance,
                    doseText = "${formatAmount(slot.item.amount)} ${slot.item.unit}",
                    timeMinutes = slot.time,
                    taken = slot.state == SlotState.TAKEN,
                    isQuiet = slot.item.isQuiet,
                )
            },
            takenCount = slots.count { state -> state.state == SlotState.TAKEN },
            totalCount = slots.size,
        )

        /**
         * A dose as the row prints it.
         *
         * `Locale.ROOT`, per the port's standing rule: the number is one the app produced
         * and a locale-sensitive formatter would render a different one.
         */
        private fun formatAmount(value: Double): String =
            if (value == value.toLong().toDouble()) {
                value.toLong().toString()
            } else {
                String.format(java.util.Locale.ROOT, "%.1f", value)
            }

        val EMPTY = WidgetState(rows = emptyList(), takenCount = 0, totalCount = 0)
    }
}

/**
 * A string resource, read from the widget's own context.
 *
 * Glance has no `stringResource` — a widget is composed by the launcher's process and
 * Glance does not carry the Compose resource plumbing — so the host context is the way
 * to a string. Not cached across calls: the locale can change under a placed widget, and
 * a remembered string would keep the language it was first drawn in.
 */
@Composable
private fun widgetString(resId: Int): String = LocalContext.current.getString(resId)

// MARK: - The surface

@Composable
private fun TodayMedsContent(state: WidgetState) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetColors.background)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        if (state.isEmpty) {
            EmptyState()
        } else {
            MedsList(state)
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = widgetString(R.string.widget_no_meds),
            style = TextStyle(color = WidgetColors.secondary, fontSize = 12.sp),
        )
    }
}

/**
 * The list, for both families.
 *
 * One composition rather than two: Glance measures against the host's box, so the small
 * family simply runs out of room first. The header carries the count so a small tile
 * still answers "am I done today" in one glance.
 */
@Composable
private fun MedsList(state: WidgetState) {
    val nowMinutes = MyMedsModel.nowMinutes()
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = widgetString(R.string.widget_todays_meds),
                style = TextStyle(
                    color = WidgetColors.secondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(
                text = if (state.allTaken) {
                    widgetString(R.string.widget_all_taken)
                } else {
                    "${state.takenCount}/${state.totalCount}"
                },
                style = TextStyle(
                    color = if (state.allTaken) WidgetColors.success else WidgetColors.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }

        Spacer(modifier = GlanceModifier.height(8.dp))

        val quiet = state.quietRows
        val rows = state.loudRows
        // A plain `Column`, not a `LazyColumn`, and the difference is not style.
        //
        // A `LazyColumn` makes Glance emit a list that is served by
        // `GlanceRemoteViewsService` through a `RemoteViewsService`/`RemoteViewsFactory`
        // pair, rather than emitting everything into the widget's own `RemoteViews`. On a
        // Xiaomi HyperOS launcher that indirection produced "载入窗口小部件时出现问题" — the
        // app's session completed successfully and logged nothing, while the tile showed
        // the host's error state, which is the signature of the host failing to build the
        // adapter-backed view rather than the app failing to compose.
        //
        // Scrolling was never load-bearing here anyway. The slots are today's, so the list
        // is naturally bounded by how many times a day the user takes medication, and a
        // widget has room for a handful — anything past that is clipped by the host just as
        // it would be by a lazy list.
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            for (row in rows) {
                SlotRow(row, nowMinutes)
            }
            if (quiet.isNotEmpty()) {
                QuietRow(taken = quiet.count { it.taken }, total = quiet.size)
            }
        }
    }
}

/** One row: the state dot, the name, the dose, and the time when it has one. */
@Composable
private fun SlotRow(row: WidgetState.Row, nowMinutes: Int) {
    val due = row.isDueNow(nowMinutes)
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StateDot(done = row.taken, due = due)
        Spacer(modifier = GlanceModifier.width(8.dp))
        Text(
            text = row.name,
            style = TextStyle(
                color = if (row.taken) WidgetColors.secondary else WidgetColors.primary,
                fontSize = 13.sp,
                fontWeight = if (row.taken) FontWeight.Normal else FontWeight.Medium,
            ),
            modifier = GlanceModifier.defaultWeight(),
            maxLines = 1,
        )
        Spacer(modifier = GlanceModifier.width(6.dp))
        row.timeMinutes?.let { minutes ->
            Text(
                text = WidgetTime.text(minutes),
                style = TextStyle(
                    // A due slot's time is the accent colour, so "what is late" reads
                    // without counting rows.
                    color = if (due) WidgetColors.accent else WidgetColors.secondary,
                    fontSize = 11.sp,
                ),
                maxLines = 1,
            )
        }
    }
}

/** The collapsed quiet-meds line. */
@Composable
private fun QuietRow(taken: Int, total: Int) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StateDot(done = taken == total, due = false)
        Spacer(modifier = GlanceModifier.width(8.dp))
        Text(
            text = widgetString(R.string.widget_supplements),
            style = TextStyle(
                color = if (taken == total) WidgetColors.secondary else WidgetColors.primary,
                fontSize = 13.sp,
            ),
            modifier = GlanceModifier.defaultWeight(),
        )
        Text(
            text = "$taken/$total",
            style = TextStyle(color = WidgetColors.secondary, fontSize = 11.sp),
        )
    }
}

/**
 * A row's state: filled and ticked when taken, ringed in the accent when due, plain
 * otherwise. Not a button — see the class note on what is deliberately not here — so it
 * is a `Box` and carries no click action of its own.
 */
@Composable
private fun StateDot(done: Boolean, due: Boolean) {
    Box(
        modifier = GlanceModifier
            .size(18.dp)
            .background(if (done) WidgetColors.success else WidgetColors.track),
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            Text(
                text = "✓",
                style = TextStyle(
                    color = WidgetColors.onSuccess,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        } else if (due) {
            Box(
                modifier = GlanceModifier
                    .size(7.dp)
                    .background(WidgetColors.accent),
            ) {}
        }
    }
}

/**
 * The widget's palette.
 *
 * Fixed values rather than the app's `PiruColors`: a widget is drawn by the launcher's
 * process with no composition of its own, so it cannot read the app's theme, and a
 * widget that changed colour with a skin the launcher cannot see would be a surprise.
 * The accent matches the app's, which is the part that has to be recognisable.
 *
 * ## `Color(...)`, never `android.graphics.Color.rgb(...)`
 * This was the bug behind "载入窗口小部件时出现问题" on a HyperOS launcher. `ColorProvider`
 * takes a `Color`, and `android.graphics.Color.rgb` returns a bare `Int`; the two are
 * different types but the `Int` converts silently. A bare `Int` travelling through Glance
 * is indistinguishable from a **resource id**, and that is how the launcher read it:
 *
 * ```
 * W AppWidgetHostView: Error inflating RemoteViews
 * android.widget.RemoteViews$ActionException:
 *   android.content.res.Resources$NotFoundException: Resource ID #0xffffffff
 *   at RemoteViews$ResourceReflectionAction.getParameterValue
 * ```
 *
 * `0xffffffff` is what the framework calls a null resource. The AOSP launcher happened to
 * resolve the colour anyway, which is why this only ever appeared on the one launcher that
 * did not.
 */
private object WidgetColors {
    val accent = ColorProvider(Color(0xFFED5787))
    val success = ColorProvider(Color(0xFF2EA043))
    val onSuccess = ColorProvider(Color(0xFFFFFFFF))
    val track = ColorProvider(Color(0x26202021))
    val background = ColorProvider(Color(0xFFFFFFFF))
    val primary = ColorProvider(Color(0xFF141416))
    val secondary = ColorProvider(Color(0xFF6E6E73))
}

/** `"HH:mm"` from minutes past midnight — the shape every other time readout uses. */
internal object WidgetTime {
    fun text(minutes: Int): String =
        String.format(java.util.Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)
}
