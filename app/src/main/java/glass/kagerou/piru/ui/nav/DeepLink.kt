package glass.kagerou.piru.ui.nav

import android.content.Intent
import glass.kagerou.piru.notifications.DoseNotificationScheduler
import glass.kagerou.piru.notifications.PiruNotifications

/**
 * Where a `piru://` link points.
 *
 * Ported from `DeepLink.decode(url:)`.
 *
 * ## Why a notification needs one at all
 * Every notification this app posts is about something specific — a session
 * check-in, a medication slot, a supply running low — and the entire value of
 * tapping it is arriving at that thing. Without this, a reminder opens the app
 * wherever it was last left, which is how a notification becomes an interruption
 * that costs the user a navigation.
 *
 * ## Unknown links are dropped, not guessed
 * A link that does not parse returns null. The alternative — opening the journal
 * and hoping — makes a typo in a scheduler indistinguishable from a feature, and
 * a restore that carries an old-format link would silently land somewhere
 * unrelated. Nothing here is a deep link a user can type, so there is no
 * typo case to be generous about.
 */
sealed interface DeepLinkTarget {

    /** A session's detail screen, from a check-in nudge. */
    data class Session(val id: String) : DeepLinkTarget

    /** One entry, by its timestamp — the form a widget or a legacy link carries. */
    data class Entry(val timestampEpochMillis: Long) : DeepLinkTarget

    /** A tracked supply, from a low-stock alert. */
    data class InventoryItem(val id: String) : DeepLinkTarget

    /** The log sheet, optionally narrowed to one routine's slot. */
    data class QuickLog(val routine: String?) : DeepLinkTarget
}

/**
 * The link carried by [intent], or null.
 *
 * Both places are checked because both are used: the schedulers set the URI with
 * `setData` *and* put the same string in an extra, and which one survives a
 * round trip through the system depends on how the activity was started. The URI
 * is read first because it is the one the platform routes on; the extra is the
 * fallback for a link that arrived as an explicit intent with no data.
 */
fun deepLinkOf(intent: Intent?): String? {
    if (intent == null) return null
    intent.data?.toString()?.let { if (it.isNotEmpty()) return it }
    return intent.getStringExtra(PiruNotifications.EXTRA_DEEP_LINK)?.takeIf { it.isNotEmpty() }
}

/**
 * Parse a `piru://` link.
 *
 * Hand-parsed rather than through `android.net.Uri` so this stays a pure function
 * that a JVM test can pin. `Uri` is an Android class and the one place a link
 * format can actually break is the string arithmetic, which is exactly what a
 * unit test should cover.
 */
fun parseDeepLink(uri: String?): DeepLinkTarget? {
    if (uri.isNullOrEmpty()) return null
    val prefix = "${DoseNotificationScheduler.DEEP_LINK_SCHEME}://"
    if (!uri.startsWith(prefix)) return null

    val rest = uri.removePrefix(prefix)
    val path = rest.substringBefore('?')
    val query = rest.substringAfter('?', missingDelimiterValue = "")

    val segments = path.split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty()) return null

    return when (segments[0]) {
        "session" -> segments.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { DeepLinkTarget.Session(it) }

        "entry" -> {
            // Milliseconds since the epoch, matching `DoseEntry.timestamp` and the
            // export format. A value that is not a number is a link this build does
            // not understand, not a link to the beginning of time.
            val raw = segments.getOrNull(1) ?: return null
            raw.toLongOrNull()?.let { DeepLinkTarget.Entry(it) }
        }

        "inventory" -> segments.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { DeepLinkTarget.InventoryItem(it) }

        "quicklog" -> DeepLinkTarget.QuickLog(routineSlug(query))

        else -> null
    }
}

/** The `routine` parameter of a query string, or null when absent or empty. */
private fun routineSlug(query: String): String? {
    if (query.isEmpty()) return null
    for (pair in query.split('&')) {
        val key = pair.substringBefore('=', missingDelimiterValue = "")
        if (key != "routine") continue
        val value = pair.substringAfter('=', missingDelimiterValue = "")
        if (value.isNotEmpty()) return value
    }
    return null
}
