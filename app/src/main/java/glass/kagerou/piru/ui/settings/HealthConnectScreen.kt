package glass.kagerou.piru.ui.settings

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.data.UserProfileStore
import glass.kagerou.piru.health.HealthConnectVitals
import kotlin.math.abs
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What Piru can see of the phone's health data, and the switch for it.
 *
 * Ported from the Health section of the iOS settings, rewritten for what Health
 * Connect actually is.
 *
 * ## The one thing this screen has to say clearly
 * Health Connect is not a link to a device. It is a store inside Android that
 * any app may write to, so **a reading appears here only if something else put
 * it there**. A user whose watch syncs to an app that does not write to Health
 * Connect will grant every permission, see this screen say "connected", and
 * still get nothing on their timeline. That is the confusing case, and the copy
 * below names it rather than leaving them to work it out.
 *
 * ## Refusal is a normal state, not an error
 * With permission refused, everything else in the app keeps working and this
 * screen says so. It does not nag, and it does not hide the rest of the app
 * behind a prompt.
 */
@Composable
fun HealthConnectScreen(modifier: Modifier = Modifier, onChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val health = remember { HealthConnectVitals(context) }
    val app = remember(context) { context.applicationContext as PiruApplication }

    var availability by remember { mutableStateOf(health.availability()) }
    var granted by remember { mutableStateOf<Set<String>>(emptySet()) }
    var healthWeightKg by remember { mutableStateOf<Double?>(null) }
    var loaded by remember { mutableStateOf(false) }

    // The weight the models actually use, which is the profile row's — not the
    // one Health Connect is holding. They are separate on purpose: a reading from
    // the phone is refreshed whenever it exists, while a typed number is the
    // user's and is never overwritten.
    var profileWeightKg by remember { mutableStateOf(app.profile().weightKgOrDefault()) }
    var profileSource by remember { mutableStateOf(app.profile().weightSource()) }

    suspend fun refresh() {
        granted = health.grantedPermissions()
        healthWeightKg = health.latestBodyMassKg()
        profileWeightKg = app.profile().load().bodyWeightKg ?: profileWeightKg
        profileSource = app.profile().weightSource()
        loaded = true
    }

    val launcher = rememberLauncherForActivityResult(health.permissionContract()) {
        // The system hands back the granted set directly rather than the app
        // re-reading it, so there is no window where the screen shows a stale
        // answer. Whatever comes back is what is true, including an empty set.
        scope.launch { withContext(Dispatchers.IO) { refresh() } }
    }

    LaunchedEffect(Unit) {
        availability = health.availability()
        withContext(Dispatchers.IO) { refresh() }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Health data", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Piru can show your heart rate, blood pressure and weight next to " +
                        "the doses you logged. It only reads — it never writes a health " +
                        "record, and nothing here leaves the phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        when (availability) {
            HealthConnectVitals.Availability.NOT_INSTALLED -> item {
                Notice(
                    title = "Health Connect is not on this device",
                    body = "On this version of Android it is a separate app. Installing it " +
                        "from the Play Store turns this on; until then the rest of Piru works " +
                        "exactly as it does now.",
                )
            }

            HealthConnectVitals.Availability.PROVIDER_UPDATE_REQUIRED -> item {
                Notice(
                    title = "Health Connect needs an update",
                    body = "The version installed here is older than the one Piru talks to. " +
                        "Updating it from the Play Store turns this on.",
                )
            }

            HealthConnectVitals.Availability.AVAILABLE -> {
                item {
                    PiruCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                if (loaded && granted.size == health.requiredPermissions.size) {
                                    "Connected"
                                } else if (loaded && granted.isNotEmpty()) {
                                    "Partly connected"
                                } else {
                                    "Not connected"
                                },
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                if (loaded && granted.isNotEmpty()) {
                                    "${granted.size} of ${health.requiredPermissions.size} data " +
                                        "types allowed. You can change any of them in Health Connect."
                                } else {
                                    "Piru reads nothing from your health data until you allow it."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = PiruTheme.colors.secondaryLabel,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { launcher.launch(health.requiredPermissions) }) {
                                    Text(if (granted.isEmpty()) "Allow access" else "Change access")
                                }
                                TextButton(onClick = { openHealthConnectSettings(context) }) {
                                    Text("Open Health Connect")
                                }
                            }
                        }
                    }
                }

                item {
                    PiruCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("What is read", style = MaterialTheme.typography.titleSmall)
                            for ((label, detail) in READ_TYPES) {
                                Column {
                                    Text(label, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = PiruTheme.colors.secondaryLabel,
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    PiruCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("Body weight", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Piru scales every dose model by this — how fast a dose is " +
                                    "cleared, and how concentrated it is while it is there. " +
                                    "A closer number is a closer curve, not a different one.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = PiruTheme.colors.secondaryLabel,
                            )

                            BodyWeightStepper(
                                value = profileWeightKg,
                                onChange = { kg ->
                                    profileWeightKg = kg
                                    // Through the app, not the composable's scope: the
                                    // value has to outlive this screen, and the journal
                                    // behind it has to be told to redraw.
                                    app.setBodyWeight(kg, UserProfileStore.WeightSource.MANUAL)
                                    profileSource = UserProfileStore.WeightSource.MANUAL
                                    // The journal, the session timeline and the receptor
                                    // replay are all scaled by this, and each of them
                                    // holds a result computed from the old number.
                                    onChanged()
                                },
                            )

                            Text(
                                when (profileSource) {
                                    UserProfileStore.WeightSource.MANUAL ->
                                        "You entered this. A reading from Health Connect will not replace it."
                                    UserProfileStore.WeightSource.HEALTH_CONNECT ->
                                        "Read from Health Connect. A number you type replaces it."
                                    UserProfileStore.WeightSource.ESTIMATED ->
                                        "Not set. The model uses a 60 kg reference until you give it one."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = PiruTheme.colors.secondaryLabel,
                            )

                            // Offer the phone's number only when it differs from what
                            // is in use — a button that sets the value it already has
                            // is a button that does nothing.
                            val reading = healthWeightKg
                            if (reading != null && abs(reading - profileWeightKg) >= 0.05) {
                                TextButton(
                                    onClick = {
                                        app.setBodyWeight(reading, UserProfileStore.WeightSource.HEALTH_CONNECT)
                                        profileWeightKg = reading
                                        profileSource = UserProfileStore.WeightSource.HEALTH_CONNECT
                                        onChanged()
                                    },
                                ) {
                                    Text(
                                        java.lang.String.format(
                                            java.util.Locale.ROOT,
                                            "Use the reading from Health Connect (%.1f kg)",
                                            reading,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Notice(
                        title = "If a reading never appears",
                        body = "Piru sees only what other apps have written into Health " +
                            "Connect. If your watch syncs to an app that does not write " +
                            "there, nothing Piru does will make those numbers show up.",
                    )
                }
            }
        }

        item {
            Text(
                "Readings are shown as the other app recorded them. Piru does not verify " +
                    "them. Not medical advice.",
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

/**
 * The five data types, in the app's own words.
 *
 * Written as what each one is *for* here rather than as the permission name: a
 * user reading "android.permission.health.READ_EXERCISE" learns nothing about
 * why a dose journal wants it, and the answer — that a workout is a competing
 * explanation for a heart rate change — is worth the line.
 */
private val READ_TYPES: List<Pair<String, String>> = listOf(
    "Heart rate" to "Plotted against your doses, so the curve shows what your body did around each one.",
    "Resting heart rate" to "The slow baseline, for comparison against a session.",
    "Blood pressure" to "Shown on the session timeline where a reading exists.",
    "Workouts" to "Marks the heart rate during exercise, so a rise from a run is not read as a rise from a dose.",
    "Weight" to "Scales the dose models. The newest reading is used.",
)

/**
 * A weight stepper.
 *
 * Half-kilogram steps: a whole kilogram is coarser than anyone knows their own
 * weight, and a tenth is a precision nothing here uses — the models are scaled
 * linearly by this and a 0.1 kg difference is invisible in any curve.
 *
 * The bounds are the ones a body can plausibly be. They are not a judgement about
 * who may use the app; they are there so a mistyped digit lands somewhere
 * obviously wrong rather than quietly scaling every curve by ten.
 */
@Composable
private fun BodyWeightStepper(value: Double, onChange: (Double) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = { onChange((value - 0.5).coerceAtLeast(MIN_WEIGHT_KG)) },
            enabled = value > MIN_WEIGHT_KG,
        ) { Text("−") }

        Text(
            java.lang.String.format(java.util.Locale.ROOT, "%.1f kg", value),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )

        TextButton(
            onClick = { onChange((value + 0.5).coerceAtMost(MAX_WEIGHT_KG)) },
            enabled = value < MAX_WEIGHT_KG,
        ) { Text("+") }
    }
}

private const val MIN_WEIGHT_KG = 25.0
private const val MAX_WEIGHT_KG = 300.0

@Composable
private fun Notice(title: String, body: String) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = PiruTheme.colors.secondaryLabel)
        }
    }
}

/** Hand off to Health Connect's own settings, where the grants are actually managed. */
private fun openHealthConnectSettings(context: Context) {
    val intent = Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
