package glass.kagerou.piru.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.journal.EntryDetailScreen
import glass.kagerou.piru.ui.journal.JournalScreen
import glass.kagerou.piru.ui.journal.SessionDetailScreen
import glass.kagerou.piru.ui.insights.AdherenceScreen
import glass.kagerou.piru.ui.insights.FeltPatternsScreen
import glass.kagerou.piru.ui.insights.HormoneLevelsScreen
import glass.kagerou.piru.ui.insights.InsightsScreen
import glass.kagerou.piru.ui.insights.PatternsScreen
import glass.kagerou.piru.ui.insights.ReportsScreen
import glass.kagerou.piru.ui.insights.SteadyStateProjectionScreen
import glass.kagerou.piru.ui.insights.UsageScreen
import glass.kagerou.piru.ui.library.CategoryBrowseScreen
import glass.kagerou.piru.ui.library.LibraryScreen
import glass.kagerou.piru.ui.meds.LogMedicationsScreen
import glass.kagerou.piru.ui.meds.MedDetailScreen
import glass.kagerou.piru.ui.meds.MyMedsHubScreen
import glass.kagerou.piru.ui.library.SubstanceDetailScreen
import glass.kagerou.piru.ui.nav.SheetRoute
import glass.kagerou.piru.ui.onboarding.OnboardingFlow
import glass.kagerou.piru.ui.onboarding.OnboardingState
import glass.kagerou.piru.ui.quicklog.QuickLogSheet
import glass.kagerou.piru.ui.search.SearchScreen
import glass.kagerou.piru.ui.tools.AlcoholScreen
import glass.kagerou.piru.ui.tools.BodyLoadScreen
import glass.kagerou.piru.ui.tools.ComedownGuideScreen
import glass.kagerou.piru.ui.tools.DrugClassScreen
import glass.kagerou.piru.ui.tools.EducationCardsScreen
import glass.kagerou.piru.ui.tools.EquivalenceScreen
import glass.kagerou.piru.ui.tools.HalfLifeScreen
import glass.kagerou.piru.ui.tools.HelpScreen
import glass.kagerou.piru.ui.tools.IdentifyScreen
import glass.kagerou.piru.ui.tools.InjectionLevelsScreen
import glass.kagerou.piru.ui.tools.InteractionTimelineScreen
import glass.kagerou.piru.ui.tools.InteractionsScreen
import glass.kagerou.piru.ui.tools.InventoryItemDetailScreen
import glass.kagerou.piru.ui.tools.InventoryItemFormScreen
import glass.kagerou.piru.ui.tools.InventoryScreen
import glass.kagerou.piru.ui.tools.ReceptorLoadScreen
import glass.kagerou.piru.ui.tools.SteadyStateToolScreen
import glass.kagerou.piru.ui.tools.ToleranceToolScreen
import glass.kagerou.piru.ui.tools.ToolsScreen
import glass.kagerou.piru.ui.settings.DataStorageScreen
import glass.kagerou.piru.ui.settings.HealthConnectScreen
import glass.kagerou.piru.ui.settings.NotificationSettingsScreen
import glass.kagerou.piru.ui.settings.SettingsScreen
import glass.kagerou.piru.ui.settings.SubstanceColorsScreen
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.nav.AppTab
import glass.kagerou.piru.ui.nav.DeepLinkTarget
import glass.kagerou.piru.ui.nav.PushRoute
import glass.kagerou.piru.ui.nav.parseDeepLink
import glass.kagerou.piru.ui.nav.key
import glass.kagerou.piru.ui.theme.PiruTheme

/**
 * The app shell: five tabs, a push stack per tab, and a modal above them.
 *
 * ## No navigation library, on purpose
 * Upstream's `AppNavigator` is a **value stack** — `[PushRoute]`, mutated
 * directly — and its header is explicit about why dismissal is a state write
 * rather than a callback through the view tree. `navigation-compose` is built
 * around string routes and a `NavController` that owns the stack, so adopting it
 * would mean either flattening these values into strings (losing the typed
 * arguments) or keeping two sources of truth for the same thing. A `when` over
 * the top of the stack is a smaller amount of code than the adapter would be,
 * and it is the shape the port is copying.
 *
 * The cost is the framework's free deep-link parsing and its transition
 * animations, which `AnimatedContent` covers one of and which a typed route
 * decoder would cover the other.
 */
@Composable
fun PiruApp(
    navigator: AppNavigator = remember { AppNavigator() },
    /**
     * A `piru://` link the app was started with, from a notification. Null when
     * there was none — the ordinary case.
     */
    deepLink: String? = null,
    /** Called once the link has been acted on, so the host can clear it and not navigate twice. */
    onDeepLinkHandled: () -> Unit = {},
    /** Resolves the top of a stack to a screen. A lambda so tests can supply their own. */
    destinations: @Composable (PushRoute) -> Unit = { DefaultDestination(it, navigator) },
) {
    PiruTheme {
        // A notification's link, applied once per arrival.
        //
        // Keyed on the link rather than run on every recomposition: the host
        // clears it after handling, so this fires on a fresh tap and on a cold
        // start, and not again when the composition happens to rebuild. Acting on
        // it therefore has to be idempotent-safe — pushing twice would put two
        // copies of the same screen on the stack.
        LaunchedEffect(deepLink) {
            if (deepLink == null) return@LaunchedEffect
            when (val target = parseDeepLink(deepLink)) {
                is DeepLinkTarget.Session -> navigator.push(PushRoute.Session(target.id))
                is DeepLinkTarget.Entry -> navigator.push(PushRoute.Entry(target.timestampEpochMillis))
                is DeepLinkTarget.InventoryItem -> navigator.push(PushRoute.InventoryItem(target.id))
                // The log sheet, not a push: the routine's reminder is an invitation
                // to record a dose, and a sheet is what the app already uses for
                // that and what can be abandoned without unwinding a stack.
                is DeepLinkTarget.QuickLog -> navigator.present(SheetRoute.QuickLog)
                null -> Unit
            }
            onDeepLinkHandled()
        }

        // First run, and only first run.
        //
        // The gate is here rather than in `MainActivity` because everything below
        // it — the navigator, its stacks, the sheet — is state that should not
        // exist until there is a user to use it. Building the shell behind the
        // flow and hiding it would leave a journal loading its first page for
        // someone who is still reading about what the app does.
        //
        // `OnboardingState.markCompleted` runs inside `finish()` before the
        // callback, so flipping this flag is the whole of the hand-off: there is
        // no window where a restart would show the flow again.
        val context = LocalContext.current
        var onboarded by remember { mutableStateOf(OnboardingState.hasCompleted(context)) }
        if (!onboarded) {
            OnboardingFlow(onFinished = { onboarded = true })
            return@PiruTheme
        }

        // Back pops the visible stack before it leaves the app, which is the one
        // behaviour the platform expects and a hand-rolled stack has to add.
        BackHandler(enabled = navigator.path().isNotEmpty() || navigator.sheetStack.isNotEmpty()) {
            if (navigator.sheetStack.isNotEmpty()) navigator.dismiss() else navigator.pop()
        }

        Scaffold(
            bottomBar = { PiruNavigationBar(navigator) },
            floatingActionButton = {
                // The one action the app exists for, so it is always one tap away
                // rather than buried in a tab.
                FloatingActionButton(onClick = { navigator.present(SheetRoute.QuickLog) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Log a dose")
                }
            },
        ) { padding ->
            val stack = navigator.path()
            AnimatedContent(
                targetState = navigator.selectedTab to stack.lastOrNull(),
                transitionSpec = { androidx.compose.animation.fadeIn() togetherWith androidx.compose.animation.fadeOut() },
                label = "tab",
                modifier = Modifier.fillMaxSize().padding(padding),
            ) { (tab, top) ->
                when (top) {
                    null -> when (tab) {
                        AppTab.JOURNAL -> JournalScreen(navigator)
                        AppTab.LIBRARY -> LibraryScreen(navigator)
                        AppTab.TOOLS -> ToolsScreen(navigator)
                        AppTab.INSIGHTS -> InsightsScreen(navigator)
                        AppTab.SEARCH -> SearchScreen(navigator)
                    }
                    else -> destinations(top)
                }
            }
        }

        // The modal stack, rendered above the shell. Upstream's navigator allows
        // several deep; the MVP has one sheet, so this walks the stack and renders
        // the top — the rest is state that will matter when a sheet pushes another.
        when (navigator.sheetStack.lastOrNull()) {
            SheetRoute.QuickLog -> QuickLogSheet(
                onDismiss = { navigator.dismiss() },
                // A committed dose invalidates the log. This counter stands in for
                // the Flow observation the view-model layer will bring; it is a
                // manual signal, not a reactive one, and it is named that way so
                // nobody mistakes it for the real thing.
                onCommitted = { navigator.invalidate() },
            )
            is SheetRoute.EntryEditor, SheetRoute.NewSession -> NotPortedYet(null, "Not ported yet")
            null -> Unit
        }
    }
}

@Composable
private fun PiruNavigationBar(navigator: AppNavigator) {
    val colors = PiruTheme.colors
    NavigationBar(containerColor = colors.cardBackground) {
        for (tab in AppTab.entries) {
            val selected = navigator.selectedTab == tab
            NavigationBarItem(
                selected = selected,
                onClick = { navigator.select(tab) },
                icon = { Icon(if (selected) tab.filledIcon() else tab.outlinedIcon(), contentDescription = null) },
                label = { Text(stringResource(tab.labelRes)) },
                // Material's default indicator is `secondaryContainer`, which this
                // theme never sets — so the selected tab would come out framework
                // grey while everything around it is branded. The accent is the
                // point of having the token at all.
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = colors.accent,
                    selectedTextColor = colors.accent,
                    indicatorColor = colors.accent.copy(alpha = 0.15f),
                    unselectedIconColor = colors.secondaryLabel,
                    unselectedTextColor = colors.secondaryLabel,
                ),
            )
        }
    }
}

/**
 * Tab icons, drawn from the framework's *core* icon set.
 *
 * Not the set the app wants — a bookmark for the journal and an equaliser for the
 * tools are the obvious choices, and both live in `material-icons-extended`,
 * which is a large artifact this build has no other use for. Substituting from
 * core keeps the port compiling without pulling in a few thousand vectors, and
 * the substitution is visible rather than silent: a journal tab wearing a house
 * is a reminder, not a decision.
 */
private fun AppTab.filledIcon(): ImageVector = when (this) {
    AppTab.JOURNAL -> Icons.Filled.Home
    AppTab.LIBRARY -> Icons.Filled.List
    AppTab.TOOLS -> Icons.Filled.Settings
    AppTab.INSIGHTS -> Icons.Filled.Star
    AppTab.SEARCH -> Icons.Filled.Search
}

private fun AppTab.outlinedIcon(): ImageVector = when (this) {
    AppTab.JOURNAL -> Icons.Outlined.Home
    AppTab.LIBRARY -> Icons.Outlined.List
    AppTab.TOOLS -> Icons.Outlined.Settings
    AppTab.INSIGHTS -> Icons.Outlined.Star
    AppTab.SEARCH -> Icons.Outlined.Search
}

/**
 * The routes this build renders.
 *
 * Every `ToolKind` and every `InsightKind` has a destination here, and that is a
 * claim the `when` blocks check at compile time: Kotlin refuses an incomplete
 * `when` over an enum, so adding a tool without a screen stops the build rather
 * than producing a route that crashes on a restore. The final `else` covers the
 * library routes that still have no destination — the tag row, favorites and the
 * user's own substances — which read user data rather than the read-only catalog.
 */
@Composable
private fun DefaultDestination(route: PushRoute, navigator: AppNavigator) {
    when (route) {
        is PushRoute.Entry -> EntryDetailScreen(route.timestampEpochMillis, route.id, navigator)
        is PushRoute.Substance -> SubstanceDetailScreen(route.name, navigator)
        is PushRoute.Session -> SessionDetailScreen(route.id, navigator)
        PushRoute.SubstanceColors -> SubstanceColorsScreen(onChanged = { navigator.invalidate() })
        PushRoute.Settings -> SettingsScreen(navigator)
        PushRoute.HealthData -> HealthConnectScreen(onChanged = { navigator.invalidate() })
        PushRoute.NotificationSettings -> NotificationSettingsScreen()
        PushRoute.DataStorage -> DataStorageScreen(onChanged = { navigator.invalidate() })

        is PushRoute.InventoryItem -> InventoryItemDetailScreen(route.id, navigator)
        is PushRoute.InventoryItemForm -> InventoryItemFormScreen(route.id, route.substance, navigator)
        is PushRoute.InteractionTimeline ->
            InteractionTimelineScreen(route.substanceA, route.substanceB, navigator)
        is PushRoute.DrugClass -> DrugClassScreen(route.className, navigator)

        PushRoute.MyMeds -> MyMedsHubScreen(
            navigator = navigator,
            onOpenMed = { navigator.push(PushRoute.MedDetail(it.rowId)) },
        )
        is PushRoute.MedDetail -> MedDetailScreen(route.rowId, onDismissed = { navigator.pop() })
        is PushRoute.LogMedications ->
            LogMedicationsScreen(route.category, onDismissed = { navigator.popToRoot() })

        is PushRoute.Tool -> when (route.kind) {
            PushRoute.ToolKind.TOLERANCE -> ToleranceToolScreen(navigator)
            PushRoute.ToolKind.BODY_LOAD -> BodyLoadScreen()
            PushRoute.ToolKind.HALF_LIFE -> HalfLifeScreen()
            PushRoute.ToolKind.INTERACTIONS -> InteractionsScreen(navigator)
            PushRoute.ToolKind.INJECTION_LEVELS -> InjectionLevelsScreen(navigator)
            PushRoute.ToolKind.INVENTORY -> InventoryScreen(navigator)
            PushRoute.ToolKind.STEADY_STATE -> SteadyStateToolScreen(navigator)
            PushRoute.ToolKind.ALCOHOL -> AlcoholScreen(navigator)
            PushRoute.ToolKind.EQUIVALENCE -> EquivalenceScreen(navigator)
            PushRoute.ToolKind.IDENTIFY -> IdentifyScreen(navigator)
            PushRoute.ToolKind.DRUG_CLASS -> DrugClassScreen("", navigator)
            PushRoute.ToolKind.COMEDOWN -> ComedownGuideScreen(navigator)
            PushRoute.ToolKind.HELP -> HelpScreen(navigator)
            PushRoute.ToolKind.EDUCATION -> EducationCardsScreen(navigator)
        }

        is PushRoute.Insight -> when (route.kind) {
            PushRoute.InsightKind.ADHERENCE -> AdherenceScreen(navigator)
            PushRoute.InsightKind.USAGE -> UsageScreen(navigator)
            PushRoute.InsightKind.PATTERNS -> PatternsScreen(navigator)
            PushRoute.InsightKind.FELT_PATTERNS -> FeltPatternsScreen(navigator)
            PushRoute.InsightKind.REPORTS -> ReportsScreen(navigator)
            PushRoute.InsightKind.RECEPTOR_LOAD -> ReceptorLoadScreen(navigator)
            PushRoute.InsightKind.HORMONE_LEVELS -> HormoneLevelsScreen(navigator)
            PushRoute.InsightKind.STEADY_STATE_PROJECTION -> SteadyStateProjectionScreen(navigator)
        }

        is PushRoute.LibraryCategory -> CategoryBrowseScreen(route.category, navigator)

        // The remaining library browse routes — the tag row, favorites and the
        // user's own substances — read user data (Room) rather than the read-only
        // catalog, so they name themselves here rather than pretending to have a
        // destination.
        else -> NotPortedYet(null, "Not ported yet", route.key())
    }
}

/**
 * What a screen that does not exist yet says.
 *
 * Deliberately explicit rather than an empty surface: a blank screen and a broken
 * screen look the same, and this build ships mid-port. Naming what is missing is
 * the difference between "unfinished" and "malfunctioning".
 */
@Composable
fun NotPortedYet(
    tab: AppTab?,
    title: String = tab?.let { stringResource(it.labelRes) } ?: "Coming",
    detail: String = "",
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PiruCard {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    detail.ifEmpty { "This screen is part of the port and has not landed yet." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }
    }
}

@Preview
@Composable
private fun PiruAppPreview() {
    PiruApp()
}
