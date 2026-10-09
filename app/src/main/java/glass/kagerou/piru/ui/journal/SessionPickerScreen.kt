package glass.kagerou.piru.ui.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.data.entity.SessionEntity
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.nav.PushRoute
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Choose a session to merge into, or to move one dose into — BUG #31's missing half.
 *
 * ## What was missing, and what was not
 * `SessionRepository.merge` and `move` were written, tested and unreachable: both take a **target session** and nothing
 * let a reader name one. This is that screen, and it is the same screen for both operations because the choice is the
 * same — the [kind] only decides which repository call runs.
 *
 * ## What it does not offer, and why that is the same rule as `split`'s
 * - **The source itself.** Merging a session into itself is a no-op and moving a dose to its own session is a no-op, so
 *   the row is filtered out rather than drawn and silently ignored. `split` hides its button under the same rule.
 * - **A dose count of zero.** `SessionEntity` can exist with no doses momentarily while a refresh runs; a target with
 *   nothing in it would swallow the source's doses into a session that reads as empty history.
 *
 * ## Why the list is `Dispatchers.IO`
 * The sessions and their dose bounds are two database reads. Reading them on the main thread is BUG #23's shape, which
 * this port has already fixed once for the substance catalogue.
 */
@Composable
fun SessionPickerScreen(
    kindWire: String,
    sourceId: String,
    navigator: AppNavigator,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }

    val kind = remember(kindWire) { PushRoute.SessionPicker.Kind.from(kindWire) }
    val source = remember(sourceId) { runCatching { UUID.fromString(sourceId) }.getOrNull() }

    var choices by remember(kind, source) { mutableStateOf<List<Pair<SessionEntity, Int>>>(emptyList()) }
    var loading by remember(kind, source) { mutableStateOf(true) }

    LaunchedEffect(kind, source) {
        // A missing or malformed id leaves the screen in its empty state rather than crashing: the route is serialised
        // state, and a stale one should not take the app down.
        if (kind == null || source == null) {
            loading = false
            return@LaunchedEffect
        }
        choices = withContext(Dispatchers.IO) {
            val all = app.database.sessionDao().all()
            all.filterNot { it.id == source }
                .map { session ->
                    // `canSplitAt` answers "is there a later dose" — used here for the cheaper question of whether the
                    // session holds anything at all, through the same ordered read the split uses.
                    val doses = app.database.doseEntryDao().dosesFor(session.id)
                    session to doses.size
                }
                .filter { (_, count) -> count > 0 }
                .sortedByDescending { (session, _) -> session.startDate }
        }
        loading = false
    }

    val formatter = remember(zone) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.ROOT)
            .withZone(zone)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Text(
                stringResource(
                    when (kind) {
                        PushRoute.SessionPicker.Kind.MOVE -> R.string.session_picker_move_title
                        else -> R.string.session_picker_merge_title
                    },
                ),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        item {
            Text(
                stringResource(R.string.session_picker_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = PiruTheme.colors.secondaryLabel,
            )
        }

        if (loading) {
            item {
                Text(
                    stringResource(R.string.session_picker_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        } else if (choices.isEmpty()) {
            item {
                // Named rather than blank: "there is no other session" is a fact the reader can act on, and an empty
                // screen would look like a failure to load.
                Text(
                    stringResource(R.string.session_picker_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        } else {
            items(choices, key = { it.first.id.toString() }) { (session, doseCount) ->
                PiruCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val target = session.id
                        // Hoisted to locals: a nullable property does not smart-cast inside a lambda, and `source` is
                        // captured by the coroutine. The fourth time this has bitten in this port.
                        val picked = kind ?: return@PiruCard
                        val from = source ?: return@PiruCard
                        scope.launch {
                            if (picked == PushRoute.SessionPicker.Kind.MERGE) {
                                app.sessionRepository().merge(sourceId = from, targetId = target)
                            } else {
                                // `move` takes the **dose** row id, and the route carries it in `sourceId` for that
                                // kind. Parsing it here rather than in the route keeps one shape for both kinds.
                                runCatching { sourceId.toLong() }.getOrNull()?.let { rowId ->
                                    app.sessionRepository().move(doseRowId = rowId, targetId = target)
                                }
                            }
                            navigator.invalidate()
                            // To the session that absorbed the doses, which is where the reader's attention is now.
                            navigator.push(PushRoute.Session(target.toString()))
                        }
                    },
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            session.title?.takeIf { it.isNotBlank() } ?: formatter.format(session.startDate.toInstant()),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(R.string.session_picker_dose_count, doseCount.toString()),
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                }
            }
        }
    }
}
