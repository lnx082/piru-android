package glass.kagerou.piru

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import glass.kagerou.piru.ui.PiruApp
import glass.kagerou.piru.ui.nav.deepLinkOf

/**
 * The app's single activity.
 *
 * Compose owns the whole surface, so this does four things: go edge to edge, hold
 * the pending deep link, hand the tree to [PiruApp], and get out of the way.
 * Everything the screens need they reach for through the application — see
 * [PiruApplication] for why that is a property lookup rather than a singleton.
 *
 * ## Why the deep link lives here
 * A notification's content intent starts this activity, and the link it carries
 * has to survive into the composition — but the composition is rebuilt from
 * scratch every time the activity is recreated, so it cannot hold the link
 * itself. Keeping it in an activity-scoped state means the link is read once, on
 * the way in, and cleared once the shell has acted on it, which is also what
 * stops a configuration change from re-navigating to the same screen.
 *
 * ## `singleTop` is load-bearing
 * Declared in the manifest. Without it, tapping a notification while the app is
 * already open starts a **second** `MainActivity` rather than delivering the
 * intent to [onNewIntent] — so the link lands on a fresh instance stacked over
 * the first, and the screen the user was on is still sitting behind it.
 */
class MainActivity : ComponentActivity() {

    /** The link a notification arrived with, or null. Cleared once the shell has used it. */
    private var pendingDeepLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingDeepLink = deepLinkOf(intent)
        setContent {
            PiruApp(
                deepLink = pendingDeepLink,
                onDeepLinkHandled = { pendingDeepLink = null },
            )
        }
    }

    /**
     * A notification tapped while the app was already running.
     *
     * `setIntent` as well as reading it, so anything that later asks the activity
     * what it was started with gets the current answer rather than the original
     * one.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLink = deepLinkOf(intent)
    }
}
