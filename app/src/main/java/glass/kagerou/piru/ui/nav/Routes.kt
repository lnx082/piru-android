package glass.kagerou.piru.ui.nav

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
enum class AppTab(val wireValue: String, val label: String) {
    JOURNAL("journal", "Journal"),
    LIBRARY("library", "Library"),
    TOOLS("tools", "Tools"),
    INSIGHTS("insights", "Insights"),
    SEARCH("search", "Search"),
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

    /** A receptor class's write-up and members, from the drug-class browser. */
    @Serializable
    data class DrugClass(val className: String) : PushRoute
}

/**
 * A modal destination.
 *
 * Ported from `SheetRoute`. Quick log is the one that matters: it is how every
 * dose in the app gets logged, and it is a sheet rather than a push because the
 * user is mid-something and must be able to abandon it without unwinding a stack.
 */
@Serializable
sealed interface SheetRoute {

    @Serializable
    data object QuickLog : SheetRoute

    @Serializable
    data class EntryEditor(val timestampEpochMillis: Long, val id: String? = null) : SheetRoute

    @Serializable
    data object NewSession : SheetRoute

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
    is PushRoute.Tool -> "tool:${kind.wireValue}"
    is PushRoute.Insight -> "insight:${kind.wireValue}"
    PushRoute.TabRoot -> "root"
    is PushRoute.InventoryItem -> "inventory:$id"
    is PushRoute.InventoryItemForm -> "inventory-form:${id ?: "new"}"
    is PushRoute.InteractionTimeline -> "interaction:$substanceA:$substanceB"
    is PushRoute.DrugClass -> "drug-class:$className"
}

/** A UUID from the string form a route carries, or null when it was not one. */
fun PushRoute.Session.uuidOrNull(): UUID? = runCatching { UUID.fromString(id) }.getOrNull()
