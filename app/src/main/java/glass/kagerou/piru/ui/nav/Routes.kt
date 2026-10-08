package glass.kagerou.piru.ui.nav

import glass.kagerou.piru.R
import glass.kagerou.piru.model.SubstanceCategory
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * The five tabs.
 *
 * Ported from `AppTab`. The split is load-bearing rather than cosmetic: Search is
 * its own tab because looking something up mid-session must not cost the user
 * their place in the journal, and Tools and Insights are separate because one is
 * a set of instruments and the other is a reading of the user's own log.
 */
@Serializable
enum class AppTab(
    val wireValue: String,
    /**
     * The tab's label, as a resource rather than a string.
     *
     * A resource id rather than the text itself because a tab bar is built once
     * per composition: resolving here would freeze whichever language the process
     * started in, and a language change would leave the bar in the old one until
     * the app was killed. The id is resolved at the point of drawing, which is
     * what makes [androidx.compose.runtime.CompositionLocalProvider] for a locale
     * take effect on recomposition.
     *
     * Not part of the wire format — an enum serializes by name, so this field is
     * invisible to a saved route.
     */
    @androidx.annotation.StringRes val labelRes: Int,
) {
    JOURNAL("journal", R.string.tab_journal),
    LIBRARY("library", R.string.tab_library),
    TOOLS("tools", R.string.tab_tools),
    INSIGHTS("insights", R.string.tab_insights),
    SEARCH("search", R.string.tab_search),
    ;

    companion object {
        fun fromWire(value: String?): AppTab = entries.firstOrNull { it.wireValue == value } ?: JOURNAL
    }
}

/**
 * A destination pushed onto a tab's own stack.
 *
 * Ported from `PushRoute`, restricted to the routes the MVP can actually render.
 * Upstream's enum is considerably longer — the fourteen tools, the eleven
 * insights pages, inventory, the scan box — and each of those arrives with its
 * screen rather than ahead of it: a route with no destination is a crash waiting
 * for a deep link.
 *
 * ## Entries carry both an id and a timestamp
 * The `id` is what a route should resolve by, because it survives an edit to the
 * timestamp. The timestamp is the fallback for a route that arrived without one —
 * a decoded older payload, or a `piru://entry/<timestamp>` deep link from a
 * widget — and resolving through it is why the two fields travel together rather
 * than the id alone.
 */
@Serializable
sealed interface PushRoute {

    /** A session's detail screen — the timeline, check-ins, notes and body-load sections. */
    @Serializable
    data class Session(val id: String) : PushRoute

    @Serializable
    data class Entry(val timestampEpochMillis: Long, val id: String? = null) : PushRoute

    /** The continuous timeline, pushed from the journal's day header. */
    @Serializable
    data object Timeline : PushRoute

    @Serializable
    data class Substance(val name: String) : PushRoute

    @Serializable
    data class LibraryCategory(val category: SubstanceCategory) : PushRoute

    @Serializable
    data class LibraryTag(val tag: String) : PushRoute

    @Serializable
    data object LibraryFavorites : PushRoute

    @Serializable
    data object Settings : PushRoute

    /** The user's own substance colours, pushed from Settings. */
    @Serializable
    data object SubstanceColors : PushRoute

    /**
     * What Piru may read from the phone's health store, and the switch for it.
     *
     * A route rather than a section of Settings because it carries its own
     * permission flow, and because the onboarding health step lands on the same
     * explanation — one screen, reached from two places, saying one thing.
     */
    @Serializable
    data object HealthData : PushRoute

    /** Which notifications the app may send, per type. */
    @Serializable
    data object NotificationSettings : PushRoute

    /**
     * What is stored, what can be taken out of it, and what can be thrown away.
     *
     * The screen this app cannot do without: it is the only way a journal leaves
     * the device, and the only way it comes back after a phone is lost.
     */
    @Serializable
    data object DataStorage : PushRoute

    /** A tool, pushed from the hub. */
    @Serializable
    data class Tool(val kind: ToolKind) : PushRoute

    /** A reading of the log, pushed from the Insights hub. */
    @Serializable
    data class Insight(val kind: InsightKind) : PushRoute

    /**
     * The fourteen tools.
     *
     * Upstream carries the same set. Every entry here has a destination — a route
     * that can be pushed but not rendered is a crash waiting for a restore — and
     * the three that are still static content say so on their own screen rather
     * than being absent from the hub.
     */
    @Serializable
    enum class ToolKind(val wireValue: String) {
        TOLERANCE("tolerance"),
        BODY_LOAD("bodyLoad"),
        HALF_LIFE("halfLife"),
        INTERACTIONS("interactions"),
        INJECTION_LEVELS("injectionLevels"),
        INVENTORY("inventory"),
        STEADY_STATE("steadyState"),
        ALCOHOL("alcohol"),
        EQUIVALENCE("equivalence"),
        IDENTIFY("identify"),
        DRUG_CLASS("drugClass"),
        COMEDOWN("comedown"),
        HELP("help"),
        EDUCATION("education"),
        ;
    }

    /**
     * The insights pages.
     *
     * Split from [ToolKind] because the two are different in kind, not only in
     * label: a tool is an instrument the user points at something, an insight is a
     * reading of what they already logged. Upstream keeps them in one enum and
     * groups them on the way out; keeping them apart here means a route cannot
     * land a tools screen in the insights stack.
     */
    @Serializable
    enum class InsightKind(val wireValue: String) {
        ADHERENCE("adherence"),
        USAGE("usage"),
        PATTERNS("patterns"),
        FELT_PATTERNS("feltPatterns"),
        REPORTS("reports"),
        RECEPTOR_LOAD("receptorLoad"),
        HORMONE_LEVELS("hormoneLevels"),
        STEADY_STATE_PROJECTION("steadyStateProjection"),
        ;
    }

    /** A tab's root, used to seed a `NavHost` and to compare against for pop-to-root. */
    @Serializable
    data object TabRoot : PushRoute

    /** One tracked supply, by its row id. */
    @Serializable
    data class InventoryItem(val id: String) : PushRoute

    /**
     * The add/edit form for a supply.
     *
     * [id] null means a new one; [substance] carries the name the form was opened
     * from, so an item added off a substance's own screen arrives pre-filled
     * rather than asking the user to type a name they just tapped.
     */
    @Serializable
    data class InventoryItemForm(val id: String? = null, val substance: String? = null) : PushRoute

    /** One pair's pharmacokinetic timeline, from the interaction checker's results. */
    @Serializable
    data class InteractionTimeline(val substanceA: String, val substanceB: String) : PushRoute

    /** The scheduled medications, and how they are going this week. */
    @Serializable
    data object MyMeds : PushRoute

    /**
     * One scheduled medication, by its row id.
     *
     * Keyed on `row_id` rather than on the substance identity the iOS build uses.
     * SwiftData fetches by predicate and can afford to key on anything; this
     * build's DAO exposes `byRowId` and nothing else, so the row id is the only
     * id that can be resolved. It is also why nothing may rewrite that key when a
     * med is edited.
     */
    @Serializable
    data class MedDetail(val rowId: Long) : PushRoute

    /** Logging the meds due in one time-of-day group. */
    @Serializable
    data class LogMedications(val category: String) : PushRoute

    /** A receptor class's write-up and members, from the drug-class browser. */
    @Serializable
    data class DrugClass(val className: String) : PushRoute

    /**
     * Every effect a substance is reported to produce, grouped by category.
     *
     * Keyed by name rather than by id because that is what the substance page has: it resolved the
     * page by name, and an id would have to be carried through the whole browse path to be useful
     * here.
     */
    @Serializable
    data class Effects(val name: String) : PushRoute
}

/**
 * A modal destination.
 *
 * Ported from `SheetRoute`. Quick log is the one that matters: it is how every
 * dose in the app gets logged, and it is a sheet rather than a push because the
 * user is mid-something and must be able to abandon it without unwinding a stack.
 *
 * ## Two variants this used to carry, and why they are gone
 * `EntryEditor` and `NewSession` were declared and never presented by anything: editing an entry
 * is `PushRoute.Entry`, which the sheet duplicated, and session creation happens through the
 * grouping sweep rather than a form. Their one dispatch arm rendered "Not ported yet" — a screen
 * no user could reach, and a message about a feature that was not missing.
 */
@Serializable
sealed interface SheetRoute {

    @Serializable
    data object QuickLog : SheetRoute

    /**
     * Whether this sheet hosts its own push stack.
     *
     * Upstream's quick log pushes within itself — the tray, then a picker on top
     * of it — and a push issued while such a sheet is up has to land on the
     * sheet's stack rather than the tab's, which is behind it and invisible. The
     * navigator reads this to route the push.
     */
    val supportsPushNavigation: Boolean
        get() = this is QuickLog
}

/** Minutes since the epoch, the axis the engine's replays and timestamps share. */
fun Instant.toEpochMinutes(): Double = toEpochMilli() / 60_000.0

/** A stable string for a route, for logging and for a Compose key. */
fun PushRoute.key(): String = when (this) {
    is PushRoute.Session -> "session:$id"
    is PushRoute.Entry -> "entry:$timestampEpochMillis:${id ?: "-"}"
    PushRoute.Timeline -> "timeline"
    is PushRoute.Substance -> "substance:$name"
    is PushRoute.LibraryCategory -> "category:${category.wireValue}"
    is PushRoute.LibraryTag -> "tag:$tag"
    PushRoute.LibraryFavorites -> "favorites"
    PushRoute.Settings -> "settings"
    PushRoute.SubstanceColors -> "substance-colors"
    PushRoute.HealthData -> "health-data"
    PushRoute.NotificationSettings -> "notification-settings"
    PushRoute.DataStorage -> "data-storage"
    is PushRoute.Tool -> "tool:${kind.wireValue}"
    is PushRoute.Insight -> "insight:${kind.wireValue}"
    PushRoute.TabRoot -> "root"
    is PushRoute.InventoryItem -> "inventory:$id"
    is PushRoute.InventoryItemForm -> "inventory-form:${id ?: "new"}"
    is PushRoute.InteractionTimeline -> "interaction:$substanceA:$substanceB"
    is PushRoute.DrugClass -> "drug-class:$className"
    is PushRoute.Effects -> "effects:$name"
    PushRoute.MyMeds -> "my-meds"
    is PushRoute.MedDetail -> "med-detail:$rowId"
    is PushRoute.LogMedications -> "log-medications:$category"
}

/** A UUID from the string form a route carries, or null when it was not one. */
fun PushRoute.Session.uuidOrNull(): UUID? = runCatching { UUID.fromString(id) }.getOrNull()
