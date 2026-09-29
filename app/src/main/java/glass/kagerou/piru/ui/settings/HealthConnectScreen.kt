package glass.kagerou.piru.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
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

    // Held once rather than rebuilt every recomposition: the contract is a
    // stable object and `rememberLauncherForActivityResult` expects the same
    // instance across passes.
    val permissionContract = remember { health.permissionContract() }
    val launcher = rememberLauncherForActivityResult(permissionContract) {
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
                Text(stringResource(R.string.shell_settings_health_data), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.shell_health_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        when (availability) {
            HealthConnectVitals.Availability.NOT_INSTALLED -> item {
                Notice(
                    title = stringResource(R.string.shell_health_not_installed_title),
                    body = stringResource(R.string.shell_health_not_installed_body),
                )
            }

            HealthConnectVitals.Availability.PROVIDER_UPDATE_REQUIRED -> item {
                Notice(
                    title = stringResource(R.string.shell_health_update_title),
                    body = stringResource(R.string.shell_health_update_body),
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
                                when {
                                    loaded && granted.size == health.requiredPermissions.size ->
                                        stringResource(R.string.shell_health_connected)
                                    loaded && granted.isNotEmpty() ->
                                        stringResource(R.string.shell_health_partly_connected)
                                    else ->
                                        stringResource(R.string.shell_health_not_connected)
                                },
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                if (loaded && granted.isNotEmpty()) {
                                    stringResource(
                                        R.string.shell_health_allowed_count,
                                        granted.size,
                                        health.requiredPermissions.size,
                                    )
                                } else {
                                    stringResource(R.string.shell_health_nothing_read)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = PiruTheme.colors.secondaryLabel,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    // A tap must never be a no-op. On a device
                                    // where the SDK reports itself available but
                                    // no activity actually handles the request
                                    // (an OEM build or an emulator without Play
                                    // services), `launch` throws — say so rather
                                    // than letting the button feel dead.
                                    runCatching { launcher.launch(health.requiredPermissions) }
                                        .onFailure {
                                            Toast.makeText(
                                                context,
                                                R.string.shell_health_open_failed,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                }) {
                                    Text(
                                        if (granted.isEmpty()) {
                                            stringResource(R.string.shell_health_allow)
                                        } else {
                                            stringResource(R.string.shell_health_change_access)
                                        },
                                    )
                                }
                                TextButton(onClick = { openHealthConnectSettings(context) }) {
                                    Text(stringResource(R.string.shell_health_open_settings))
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
                            Text(stringResource(R.string.shell_health_what_is_read), style = MaterialTheme.typography.titleSmall)
                            for ((label, detail) in READ_TYPES) {
                                Column {
                                    Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        stringResource(detail),
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
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(stringResource(R.string.shell_health_source_title), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(R.string.shell_health_source_lead),
                                style = MaterialTheme.typography.bodyMedium,
                                color = PiruTheme.colors.secondaryLabel,
                            )
                            // Named rather than described: the most common source on
                            // Android is a Xiaomi band, and its two switches live in
                            // places nobody guesses. The generic "check your app's
                            // settings" would be true and useless.
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    stringResource(R.string.shell_health_source_xiaomi_title),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    stringResource(R.string.shell_health_source_xiaomi_detail),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PiruTheme.colors.secondaryLabel,
                                )
                            }
                            Text(
                                stringResource(R.string.shell_health_source_other),
                                style = MaterialTheme.typography.bodySmall,
                                color = PiruTheme.colors.secondaryLabel,
                            )
                        }
                    }
                }

                item {
                    PiruCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(stringResource(R.string.shell_health_body_weight), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(R.string.shell_health_body_weight_detail),
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
                                        stringResource(R.string.shell_health_weight_source_manual)
                                    UserProfileStore.WeightSource.HEALTH_CONNECT ->
                                        stringResource(R.string.shell_health_weight_source_synced)
                                    UserProfileStore.WeightSource.ESTIMATED ->
                                        stringResource(R.string.shell_health_weight_source_estimated)
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
                                    Text(stringResource(R.string.shell_health_use_reading, reading))
                                }
                            }
                        }
                    }
                }

                item {
                    Notice(
                        title = stringResource(R.string.shell_health_no_reading_title),
                        body = stringResource(R.string.shell_health_no_reading_body),
                    )
                }
            }
        }

        item {
            Text(
                stringResource(R.string.shell_health_disclaimer),
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
private val READ_TYPES: List<Pair<Int, Int>> = listOf(
    R.string.shell_health_type_heart_rate to R.string.shell_health_type_heart_rate_detail,
    R.string.shell_health_type_resting_heart_rate to R.string.shell_health_type_resting_heart_rate_detail,
    R.string.shell_health_type_blood_pressure to R.string.shell_health_type_blood_pressure_detail,
    R.string.shell_health_type_workouts to R.string.shell_health_type_workouts_detail,
    R.string.shell_health_type_weight to R.string.shell_health_type_weight_detail,
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
            stringResource(R.string.shell_health_weight_value, value),
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

/**
 * Hand off to Health Connect's own settings, where the grants are actually
 * managed.
 *
 * The previous version swallowed the failure silently, so on a device without a
 * real Health Connect the button did nothing at all. Now it uses the documented
 * constant and, when no activity handles it, falls back to Health Connect's
 * Play Store listing — and only if that too is absent does it say so.
 */
private fun openHealthConnectSettings(context: Context) {
    val intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))
        runCatching { context.startActivity(market) }
            .onFailure { Toast.makeText(context, R.string.shell_health_open_failed, Toast.LENGTH_SHORT).show() }
    }
}
