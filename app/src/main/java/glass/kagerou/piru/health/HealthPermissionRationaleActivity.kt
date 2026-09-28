package glass.kagerou.piru.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.ui.theme.PiruTheme

/**
 * Why Piru wants to read your heart rate.
 *
 * Health Connect starts this activity from its own permission list, so it is
 * reached by a user who has already seen the request and wants to know what it
 * is for. That shapes what it can usefully say: not what the API is, and not
 * what the permission grants, but **what appears in the app once it is granted**
 * and what happens if it is refused.
 *
 * ## Deliberately its own activity, not a route into the app
 * Health Connect launches this directly with `VIEW_PERMISSION_USAGE`. Routing it
 * through the main activity would drop the user into the journal with a
 * permission dialog over it — an app they did not ask to open, asking for
 * something they were mid-way through reading about.
 *
 * ## What it does not say
 * It does not claim the data is used to improve anything, because nothing leaves
 * the device. It does not promise the numbers are accurate, because they are
 * read back exactly as another app recorded them, and this app does not verify
 * them. Both of those would be the easy sentence and neither would be true.
 */
class HealthPermissionRationaleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { PiruTheme { HealthPermissionRationale() } }
    }
}

@Composable
private fun HealthPermissionRationale() {
    val colors = PiruTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Your health data", style = MaterialTheme.typography.headlineSmall)

        Text(
            "Piru can read your heart rate, resting heart rate, blood pressure, " +
                "workouts and weight, and show them alongside the doses you logged " +
                "on the session timeline. That is the whole use: a heart rate curve " +
                "next to a dose, so you can see what your body did around it.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.secondaryLabel,
        )

        Section(
            "It stays on this device",
            "Nothing here is uploaded. Piru has no account and no server, so there " +
                "is nowhere for it to go. An export or a backup is made only when " +
                "you ask for one, and goes only where you put it.",
        )

        Section(
            "Piru only reads",
            "It never writes a health record, and it never changes anything another " +
                "app recorded. Your log of what you took stays in Piru's own store, " +
                "separate from this.",
        )

        Section(
            "You can say no",
            "Refusing costs you the heart rate overlay and nothing else — the " +
                "journal, the library and every tool keep working. You can grant or " +
                "revoke any of these at any time in Health Connect's settings, and " +
                "Piru will simply stop showing what it can no longer read.",
        )

        Text(
            "Not medical advice.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.secondaryLabel,
        )
    }
}

@Composable
private fun Section(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = PiruTheme.colors.secondaryLabel)
    }
}
