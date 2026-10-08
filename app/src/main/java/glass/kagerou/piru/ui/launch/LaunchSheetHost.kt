package glass.kagerou.piru.ui.launch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import glass.kagerou.piru.data.SubstanceColorStore
import glass.kagerou.piru.ui.onboarding.OnboardingPrefs
import glass.kagerou.piru.ui.theme.PiruTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one presenter for every sheet the app raises on its own.
 *
 * Ported from `LaunchSheetModifier`. Priority is [LaunchNotices.nextSheet]'s, so the order is stated once rather
 * than implied by the order two callers happen to check in — and **at most one sheet per launch**, so they never
 * stack or race each other's presentation.
 *
 * ## The settle delay
 * Upstream waits a second before raising anything, and the reason is the same here: a dialog attached on the first
 * frame fights the composition that is still building the screen behind it, and on a cold start it can be dismissed
 * by the same tap that opened the app. One second is enough for the first frame and short enough not to feel stuck.
 *
 * ## Why the colour notice is the one that matters
 * BUG #44, and the audit put it precisely: `SubstanceColorStore.resolveLegacy` is a decision that needs a person —
 * a colour the user picked by hand either moves to the generated class palette or is frozen exactly as it was — and
 * **nothing called it**, so a legacy row could never be settled and the "generated" palette was unreachable for it.
 * This is the caller.
 */
@Composable
fun LaunchSheetHost() {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val scope = rememberCoroutineScope()

    var sheet by remember { mutableStateOf<LaunchSheet?>(null) }

    LaunchedEffect(Unit) {
        val onboardingComplete = OnboardingPrefs.hasCompleted(context)
        // Recorded before anything is decided: on the very first launch this is what makes the install "new" and
        // therefore not owed a notice about a change that shipped before it existed.
        InstallRecord.recordIfNeeded(context, onboardingComplete)

        delay(SETTLE_DELAY_MILLIS)

        val catalogue = runCatching {
            withContext(Dispatchers.IO) { app.catalog() }
        }.getOrNull()
        val legacyRows = catalogue?.let {
            runCatching { SubstanceColorStore(app.database, it).legacyRowCount() }.getOrDefault(0)
        } ?: 0
        val doseCount = runCatching {
            withContext(Dispatchers.IO) { app.database.doseEntryDao().all().size }
        }.getOrDefault(0)
        // The same preferences file `AppSettingsStore` writes, through `LaunchNotices`' own accessor — its
        // `prefs()` is private and the file name is public, which is how `OnboardingPrefs` reads it too.
        val settings = launchPrefs(context)

        sheet = LaunchNotices.nextSheet(
            onboardingComplete = onboardingComplete,
            firstBuild = InstallRecord.firstBuild(context),
            legacyColorRows = legacyRows,
            seenNotices = LaunchNoticeRecord.seenNotices(context),
            launchCount = settings.getInt(LaunchNotices.KEY_LAUNCH_COUNT, 0),
            doseCount = doseCount,
            discordShown = settings.getBoolean(LaunchNotices.KEY_DISCORD_SHOWN, false),
            discordDismissed = settings.getBoolean(LaunchNotices.KEY_DISCORD_DISMISSED, false),
        )
    }

    when (val current = sheet) {
        is LaunchSheet.UpdateNotice -> ClassColorsNotice(
            onResolve = { adoptClassColors ->
                scope.launch {
                    val catalogue = runCatching {
                        withContext(Dispatchers.IO) { app.catalog() }
                    }.getOrNull()
                    if (catalogue != null) {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                SubstanceColorStore(app.database, catalogue).resolveLegacy(adoptClassColors)
                            }
                        }
                    }
                    // Marked seen whatever the outcome: the user has answered, and re-asking a question they have
                    // answered is the failure this system exists to avoid. If the write failed the rows are still
                    // legacy, and the *next* launch would ask again — which is correct, because the decision was
                    // never recorded.
                    LaunchNoticeRecord.markSeen(context, current.notice)
                    sheet = null
                }
            },
            onDismiss = {
                // A swipe dismisses the sheet but does not answer the question, so the notice is not marked seen
                // and comes back next launch. Upstream does the same, and the alternative — treating a dismiss as
                // "keep my old colours" — would decide something on the user's behalf by accident.
                sheet = null
            },
        )

        LaunchSheet.DiscordInvite -> DiscordInvite(
            onClose = { join ->
                // Through the accessor rather than a captured value: this branch runs long after the effect that
                // read the preferences, so reading again keeps it honest about what is on file.
                launchPrefs(context).edit()
                    .putBoolean(LaunchNotices.KEY_DISCORD_SHOWN, true)
                    // Joining and closing both retire it: there is no repeat nag.
                    .putBoolean(LaunchNotices.KEY_DISCORD_DISMISSED, true)
                    .apply()
                if (join) {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(LaunchNotices.DISCORD_URL),
                            ),
                        )
                    }
                }
                sheet = null
            },
        )

        null -> Unit
    }
}

/** How long to let the first frame settle before a sheet rises. */
private const val SETTLE_DELAY_MILLIS: Long = 1_000L

/**
 * The colour migration: adopt the generated class palette, or keep the colours exactly as they were.
 *
 * Upstream's framing, and the reason this is a **choice** rather than an automatic migration: the old palette was
 * hand-picked and the new one is generated from the substance's class, so adopting repaints something the user may
 * have chosen deliberately. Keeping them freezes the old hex as a custom colour — the appearance is preserved
 * exactly, at the cost of never following the class palette again. Both are honest; neither is obviously right,
 * which is what makes it a question rather than a migration.
 */
@Composable
private fun ClassColorsNotice(
    onResolve: (adoptClassColors: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.launch_class_colours_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.launch_class_colours_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.launch_class_colours_tradeoff),
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onResolve(true) }) {
                Text(stringResource(R.string.launch_class_colours_adopt))
            }
        },
        dismissButton = {
            TextButton(onClick = { onResolve(false) }) {
                Text(stringResource(R.string.launch_class_colours_keep))
            }
        },
    )
}

/** The community invite, shown once the user is genuinely engaged. */
@Composable
private fun DiscordInvite(onClose: (join: Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onClose(false) },
        title = { Text(stringResource(R.string.launch_discord_title)) },
        text = {
            Text(
                stringResource(R.string.launch_discord_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = { onClose(true) }) {
                Text(stringResource(R.string.launch_discord_join))
            }
        },
        dismissButton = {
            TextButton(onClick = { onClose(false) }) {
                Text(stringResource(R.string.shell_close))
            }
        },
    )
}
