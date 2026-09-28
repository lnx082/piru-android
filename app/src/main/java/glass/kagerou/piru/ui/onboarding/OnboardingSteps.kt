package glass.kagerou.piru.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.data.UserProfileStore
import glass.kagerou.piru.health.HealthConnectVitals
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * The five text-forward steps of the flow, in order: welcome, privacy, depth,
 * health, reminders.
 *
 * Ported from `Piru/Views/Onboarding/OnboardingStepViews.swift` (375 lines) and
 * `Piru/Views/Onboarding/OnboardingHealthStep.swift` (244 lines).
 *
 * ## The two steps that ask for something, and the one that does not
 * `health` and `reminders` are the only steps that can raise a system dialog,
 * and both are placed next to the value they unlock rather than batched up
 * front — that ordering is the whole design of the flow. Neither can block:
 * health has "I'll Set This Later" and reminders has "Not Now", and both advance
 * without asking for anything.
 */

// MARK: - Welcome

/**
 * The flow's root, and the only place it can be left early.
 *
 * ## The iCloud branch is kept and is permanently false
 * The source detects whether an existing backup is in the user's iCloud and
 * offers "Restore from Backup" beside "Start Fresh". **There is no Android
 * equivalent, and this is not a stub waiting for one.**
 * `BackupManager.iCloudBackupExists()` asks
 * `FileManager.default.ubiquityIdentityToken` — an Apple ID's iCloud
 * entitlement — and looks for a file in the app's ubiquity container. Android
 * has no such identity token and no shared per-user document container, so
 * there is no question this code could ask. The branch is left in place because
 * the copy, the two-button footer, and the subtitle fork are all part of the
 * screen's design, and deleting the fork would make it harder to see what a
 * future local-backup detector should plug into. What is *not* here is a
 * pretend check: `hasICloudBackup` is a literal, not a probe that always
 * returns false for reasons nobody can see.
 */
@Composable
fun OnboardingWelcomeStep(nav: OnboardingNav) {
    val hasICloudBackup = false

    OnboardingLayout(
        title = "Welcome to Piru",
        subtitle = if (hasICloudBackup) {
            "We found an existing backup in your iCloud. Pick up where you left off, or start fresh."
        } else {
            "Log medications and substances, record how you feel, and explore referenced " +
                "information. Piru is a record and a reference, not medical advice."
        },
        hero = { OnboardingAppIconHero() },
    ) {
        if (hasICloudBackup) {
            OnboardingPillButton(
                title = "Restore from Backup",
                onClick = {
                    // Unreachable while `hasICloudBackup` is false, and deliberately
                    // a no-op rather than a plausible-looking lie. When a backup
                    // layer lands this is where its restore call goes, followed by
                    // `nav.advance()`.
                },
            )
            OnboardingPillButton(title = "Start Fresh", prominence = Prominence.NEUTRAL, onClick = nav.advance)
        } else {
            OnboardingPillButton(title = "Get Started", onClick = nav.advance)
        }
    }
}

// MARK: - Privacy

/**
 * The reassurance step: what the journal is, where it lives, and what is not
 * done with it.
 *
 * The copy is the source's, unchanged. The third row's detail ("Details are in
 * Settings under About Piru") still resolves in this port, because Settings
 * carries the About section.
 */
@Composable
fun OnboardingPrivacyStep(nav: OnboardingNav) {
    OnboardingLayout(
        title = "Where your journal lives",
        subtitle = "Your journal is stored in the app on this device.",
        hero = { OnboardingIconHero(Icons.Filled.Lock) },
        mid = {
            OnboardingGroupedCard(
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp),
            ) {
                OnboardingBulletRow(
                    icon = Icons.Filled.Phone,
                    title = "No account",
                    detail = "No sign-up, and no Piru server that receives your journal.",
                )
                OnboardingBulletRow(
                    icon = Icons.Filled.Share,
                    title = "Copies only when you ask",
                    detail = "An export or backup is made when you ask and saved where you choose.",
                )
                OnboardingBulletRow(
                    icon = Icons.Filled.Info,
                    title = "No ads or trackers",
                    detail = "Details are in Settings under About Piru.",
                )
            }
        },
    ) {
        OnboardingPillButton(title = "Continue", onClick = nav.advance)
    }
}

// MARK: - Depth

/**
 * How much pharmacology the app opens with.
 *
 * The source renders a `List` of `UserProfile.allCases` at a fixed 340 pt with
 * scrolling disabled — two rows, so the height is the list's own chrome rather
 * than its content. Here the two rows are plain cards, which is both what the
 * port has and what the source's rows look like once the list chrome is gone.
 *
 * The stored value is the **wire value** the entity documents:
 * `"harm-reduction"` for the tier the UI calls "Curious". The name looks like a
 * copy violation and is not one — it is storage, it is what installed builds
 * hold, and renaming it would orphan a stored profile.
 */
@Composable
fun OnboardingDepthStep(nav: OnboardingNav) {
    val context = LocalContext.current
    var selection by remember { mutableStateOf(DisclosureTier.CURIOUS) }

    OnboardingLayout(
        title = "How much detail?",
        subtitle = "Piru can keep it simple or go deep into the pharmacology. " +
            "Change this anytime in Settings.",
        hero = { OnboardingIconHero(Icons.Filled.Menu) },
        mid = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (tier in DisclosureTier.entries) {
                    TierRow(tier = tier, selected = tier == selection, onSelect = { selection = tier })
                }
            }
        },
    ) {
        OnboardingPillButton(
            title = "Continue",
            onClick = {
                // The profile row, not preferences: the tier is a profile field
                // that a screen reads to decide how much to show, and a preferences
                // file that nothing reads is how this went wrong the first time.
                (context.applicationContext as PiruApplication).setDisclosureTier(selection.wire)
                nav.advance()
            },
        )
    }
}

/** One depth choice. A card, because a row that changes a setting is tappable. */
@Composable
private fun TierRow(tier: DisclosureTier, selected: Boolean, onSelect: () -> Unit) {
    val colors = PiruTheme.colors
    PiruCard(modifier = Modifier.fillMaxWidth(), onClick = onSelect) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                tier.icon,
                contentDescription = null,
                tint = if (selected) colors.accent else colors.secondaryLabel,
                modifier = Modifier.size(22.dp),
            )
            Column(
                modifier = Modifier.weight(1f).padding(start = 14.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(tier.displayName, style = MaterialTheme.typography.titleSmall)
                Text(tier.summary, style = MaterialTheme.typography.bodySmall, color = colors.secondaryLabel)
            }
            if (selected) {
                // The row's own selected state carries this for assistive tech;
                // the glyph would only repeat it.
                Icon(Icons.Filled.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
            } else {
                Spacer(Modifier.width(20.dp))
            }
        }
    }
}

/**
 * The two disclosure tiers, with the entity's wire values.
 *
 * Ported from `Piru/Data/Services/UserProfile.swift`. The retired
 * `pharma-nerd` value is missing on purpose: the source maps it forward to
 * Curious on read, and nothing in this port can write it.
 */
private enum class DisclosureTier(
    val displayName: String,
    val summary: String,
    val wire: String,
    val icon: ImageVector,
) {
    CASUAL(
        displayName = "Casual",
        summary = "Plain names, pharmacology folded away until you open it.",
        wire = OnboardingPrefs.TIER_CASUAL,
        icon = Icons.AutoMirrored.Filled.List,
    ),
    CURIOUS(
        displayName = "Curious",
        summary = "Mechanism and pharmacokinetics open on the page, receptor names in the Tolerance tool.",
        wire = OnboardingPrefs.TIER_CURIOUS,
        icon = Icons.Filled.Search,
    ),
}

// MARK: - Health

/** The clamp the source applies before storing a weight, in kg. */
private const val WEIGHT_MIN_KG = 20.0
private const val WEIGHT_MAX_KG = 300.0

/** The population default the source uses until the user provides one. */
private const val WEIGHT_DEFAULT_KG = 60.0

/**
 * Connect the phone's health data.
 *
 * Ported from `OnboardingHealthStep.swift`, with Apple Health replaced by
 * **Health Connect** through [HealthConnectVitals].
 *
 * ## Three states, and only one of them is a permission request
 * Health Connect is part of Android 14 and up, and an app the user may not have
 * installed below that. [HealthConnectVitals.availability] distinguishes the
 * cases, and they need different words — "install this" and "your phone does not
 * have it" are not both "connection failed". So the unavailable states are put
 * on screen **before** the button is tapped and Continue does not attempt to
 * launch a permission screen that cannot open; it saves the weight and advances,
 * exactly as "I'll Set This Later" does. That is the one place this port
 * deliberately differs from the source, which cannot tell the three states apart
 * and always raises the Health sheet.
 *
 * ## A refused grant is a soft failure
 * Upstream cannot observe read authorization at all — an empty result is
 * indistinguishable from no data, and the UI shows a neutral note either way.
 * Health Connect does expose the grant, but the screen behaves the same way: no
 * permission means no reading, the note says so, and **the weight the stepper
 * shows is saved regardless**, so a user who declines still keeps their number.
 *
 * ## What is read
 * One permission request covering heart rate, resting heart rate, blood
 * pressure, exercise sessions and weight — the same single sheet the source
 * raises. The weight is then read back so the stepper can show what the phone
 * already knows.
 */
@Composable
fun OnboardingHealthStep(nav: OnboardingNav) {
    val context = LocalContext.current
    val health = remember(context) { HealthConnectVitals(context) }
    val availability = remember(health) { health.availability() }
    val scope = rememberCoroutineScope()

    var weightKg by remember { mutableStateOf(WEIGHT_DEFAULT_KG) }
    var healthValue by remember { mutableStateOf<Double?>(null) }
    var connecting by remember { mutableStateOf(false) }
    var noReadNote by remember { mutableStateOf(false) }

    fun save() {
        val clamped = weightKg.coerceIn(WEIGHT_MIN_KG, WEIGHT_MAX_KG)
        // Compare against the clamped Health value: an out-of-range reading
        // clamps to the same bound a typed one would, and still counts as
        // Health Connect provenance rather than a manual entry.
        val clampedHealth = healthValue?.coerceIn(WEIGHT_MIN_KG, WEIGHT_MAX_KG)
        val fromHealthConnect = clampedHealth != null && abs(clampedHealth - clamped) < 0.05
        // Into the profile row, which is what the dose models actually read — and
        // on an application scope, because this step advances immediately and a
        // coroutine tied to its composition would be cancelled before the write
        // landed.
        (context.applicationContext as PiruApplication).setBodyWeight(
            kg = clamped,
            source = if (fromHealthConnect) {
                UserProfileStore.WeightSource.HEALTH_CONNECT
            } else {
                UserProfileStore.WeightSource.MANUAL
            },
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(health.permissionContract()) { granted ->
        scope.launch {
            if (granted.isEmpty()) {
                // Declined, or granted nothing this screen asked for. Not an
                // error: the stepper's number is what gets saved.
                noReadNote = true
            } else {
                OnboardingPrefs.writeShowSessionVitals(context, true)
                val kg = health.latestBodyMassKg()
                if (kg == null) noReadNote = true else healthValue = kg.also { weightKg = it }
            }
            connecting = false
            save()
            nav.advance()
        }
    }

    OnboardingLayout(
        title = "Turn on Health Connect",
        subtitle = "Show your body weight and heart rate from Health Connect alongside your " +
            "journal entries, on the session timeline.",
        hero = { OnboardingIconHero(Icons.Filled.Favorite) },
        mid = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PiruCard(modifier = Modifier.fillMaxWidth()) { OnboardingVitalsSampleChart() }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your body weight", style = MaterialTheme.typography.titleSmall)
                    OnboardingWeightStepper(value = weightKg, onValueChange = { weightKg = it })
                    OnboardingNote(
                        icon = when {
                            healthValue != null -> Icons.Filled.CheckCircle
                            noReadNote -> Icons.Filled.Warning
                            else -> Icons.Filled.Favorite
                        },
                        text = noteText(
                            availability = availability,
                            synced = healthValue != null,
                            noRead = noReadNote,
                        ),
                    )
                }
            }
        },
    ) {
        OnboardingPillButton(
            title = if (connecting) "Connecting…" else "Continue",
            enabled = !connecting,
            onClick = {
                if (availability != HealthConnectVitals.Availability.AVAILABLE) {
                    // Nothing to ask for on this device. Saving here rather than
                    // raising a screen that cannot open is the difference between
                    // "we could not connect" and "there is nothing to connect to".
                    save()
                    nav.advance()
                    return@OnboardingPillButton
                }
                connecting = true
                noReadNote = false
                permissionLauncher.launch(health.requiredPermissions)
            },
        )
        OnboardingPillButton(
            title = "I'll Set This Later",
            prominence = Prominence.NEUTRAL,
            onClick = {
                // Deliberately requests nothing at all — not even a permission
                // check. Skipping is a complete answer.
                save()
                nav.advance()
            },
        )
    }
}

/** The three-state note under the weight, plus the two unavailable states. */
private fun noteText(
    availability: HealthConnectVitals.Availability,
    synced: Boolean,
    noRead: Boolean,
): String = when {
    availability == HealthConnectVitals.Availability.NOT_INSTALLED ->
        "This device does not have Health Connect. Set your weight above instead."
    availability == HealthConnectVitals.Availability.PROVIDER_UPDATE_REQUIRED ->
        "Health Connect is on this device but needs an update before Piru can read it."
    synced -> "Synced from Health Connect — check the number looks right."
    noRead -> "Couldn't read a weight from Health Connect. Set it above instead."
    else -> "Change what Piru can see anytime in Health Connect's own settings."
}

/**
 * The weight editor: minus, the number and its unit, plus.
 *
 * Ported from `InventoryStepperRow` as the health step uses it — `stepBasis: 10`
 * and nothing else, so the unit menu, the focus-on-appear behaviour and the
 * accessible-slider representation the inventory forms need are not here.
 *
 * `stepBasis: 10` resolves through the source's `InventoryStep.nice` to a **1 kg
 * step**, which is what makes the basis worth keeping rather than writing `1.0`:
 * the number is derived from the same rule upstream uses, at the source's value.
 * The value is snapped to the step grid so repeated taps stay on round numbers.
 */
@Composable
private fun OnboardingWeightStepper(value: Double, onValueChange: (Double) -> Unit) {
    val colors = PiruTheme.colors
    val step = remember { niceStep(10.0) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(Icons.Filled.KeyboardArrowDown, "Decrease") {
            onValueChange(max(0.0, value - step).snapTo(step))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                formatWeight(value),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                " kg",
                modifier = Modifier.padding(bottom = 3.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.secondaryLabel,
            )
        }
        StepButton(Icons.Filled.KeyboardArrowUp, "Increase") {
            onValueChange((value + step).snapTo(step))
        }
    }
}

/**
 * One step of the stepper.
 *
 * A chevron rather than a minus and a plus: the core icon set has `Add` but no
 * `Remove`, and a pair of opposing chevrons says the same thing without one of
 * them being a rotated copy of the other with a different accessibility label.
 * The 38dp circle is the source's own size.
 */
@Composable
private fun StepButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = PiruTheme.colors
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(colors.inputBackground)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = colors.secondaryLabel,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** The source's `InventoryStep.nice(for:)`: a step at 1, 2.5, 5 or 10 × a power of ten. */
private fun niceStep(basis: Double): Double {
    val magnitude = 10.0.pow(floor(log10(max(abs(basis), 1.0) / 10.0)))
    val normalized = (max(abs(basis), 1.0) / 10.0) / magnitude
    val snapped = when {
        normalized < 1.75 -> 1.0
        normalized < 3.75 -> 2.5
        normalized < 7.5 -> 5.0
        else -> 10.0
    }
    return snapped * magnitude
}

private fun Double.snapTo(step: Double): Double = round(this / step) * step

/**
 * A weight with one decimal, and none when it has none.
 *
 * `Locale.ROOT`, per the port's rule: Kotlin's formatting is locale-sensitive
 * and Swift's `String(format:)` is not, so a Turkish device would otherwise
 * render "72,5" for a model value of 72.5.
 */
private fun formatWeight(kg: Double): String =
    if (kg == round(kg)) String.format(java.util.Locale.ROOT, "%.0f", kg)
    else String.format(java.util.Locale.ROOT, "%.1f", kg)

/**
 * A static, illustrative preview of the session vitals overlay: an alcohol
 * effect curve with the heart rate a watch recorded alongside it.
 *
 * Ported from the source's `OnboardingVitalsSampleChart`, numbers and all —
 * including the Bateman bump and the lagged, jittered heart-rate echo. **Nothing
 * here reads health data**; it is a picture of what connecting buys, drawn
 * before the user decides.
 */
@Composable
private fun OnboardingVitalsSampleChart() {
    val colors = PiruTheme.colors
    // Hoisted: a theme read cannot happen inside `Canvas { }`.
    val accent = colors.accent
    // VitalsPalette.heart, from the source.
    val heart = Color(red = 0.898f, green = 0.290f, blue = 0.310f)
    val secondary = colors.secondaryLabel

    Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ChartLegend(color = accent, label = "Alcohol", labelColor = secondary)
            ChartLegend(color = heart, label = "Heart rate", labelColor = secondary)
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(108.dp)) {
            val pad = 4f
            val plotWidth = size.width - pad * 2
            val gap = 8f
            val bandHeight = size.height * 0.34f
            val effectTop = 4f
            val effectBottom = size.height - bandHeight - gap
            val effectHeight = effectBottom - effectTop
            val bandTop = effectBottom + gap
            val bandBottom = size.height

            val count = 80
            fun fraction(index: Int) = index.toDouble() / (count - 1)
            fun xFor(index: Int) = pad + fraction(index).toFloat() * plotWidth

            fun rawEffect(t: Double): Double {
                val minutes = t * 240
                return exp(-0.017 * minutes) - exp(-0.055 * minutes)
            }
            val peak = (0..100).maxOf { rawEffect(it / 100.0) }
            fun effect(t: Double) = max(0.0, rawEffect(t) / peak)

            // The filled effect area, then the curve over it.
            val area = Path()
            area.moveTo(pad, effectBottom)
            for (index in 0 until count) {
                area.lineTo(xFor(index), effectBottom - effect(fraction(index)).toFloat() * effectHeight)
            }
            area.lineTo(pad + plotWidth, effectBottom)
            area.close()
            drawPath(area, color = accent.copy(alpha = 0.18f))

            val curve = Path()
            for (index in 0 until count) {
                val point = androidx.compose.ui.geometry.Offset(
                    xFor(index),
                    effectBottom - effect(fraction(index)).toFloat() * effectHeight,
                )
                if (index == 0) curve.moveTo(point.x, point.y) else curve.lineTo(point.x, point.y)
            }
            drawPath(curve, color = accent, style = Stroke(width = 2f))

            // The dose marker, at t=0.
            drawCircle(color = accent, radius = 3.5f, center = androidx.compose.ui.geometry.Offset(pad, effectBottom))

            // The companion cardio band.
            drawRect(
                color = heart.copy(alpha = 0.07f),
                topLeft = androidx.compose.ui.geometry.Offset(pad, bandTop),
                size = androidx.compose.ui.geometry.Size(plotWidth, bandHeight),
            )

            val hrLow = 58.0
            val hrHigh = 92.0
            fun heartRate(t: Double) = 62 + 26 * effect(max(0.0, t - 0.06)) + 2.2 * sin(t * 20)
            fun yForHeartRate(bpm: Double): Float {
                val clamped = min(hrHigh, max(hrLow, bpm))
                return bandBottom - 4f - ((clamped - hrLow) / (hrHigh - hrLow)).toFloat() * (bandHeight - 8f)
            }

            val heartArea = Path()
            heartArea.moveTo(pad, bandBottom - 4f)
            for (index in 0 until count) {
                heartArea.lineTo(xFor(index), yForHeartRate(heartRate(fraction(index))))
            }
            heartArea.lineTo(pad + plotWidth, bandBottom - 4f)
            heartArea.close()
            drawPath(heartArea, color = heart.copy(alpha = 0.12f))

            val heartLine = Path()
            for (index in 0 until count) {
                val point = androidx.compose.ui.geometry.Offset(xFor(index), yForHeartRate(heartRate(fraction(index))))
                if (index == 0) heartLine.moveTo(point.x, point.y) else heartLine.lineTo(point.x, point.y)
            }
            drawPath(heartLine, color = heart, style = Stroke(width = 1.8f))
        }
        Text(
            "A couple of drinks, with the heart rate a watch recorded alongside.",
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
        )
    }
}

@Composable
private fun ChartLegend(color: Color, label: String, labelColor: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.bodySmall, color = labelColor)
    }
}

// MARK: - Reminders

/**
 * The notification groups, each one tap to decline.
 *
 * Three groups rather than one blanket prompt or nine switches: the user opts
 * into what is meaningful, and every group starts **on**, which is the source's
 * deliberate default (a group the user does not care about is one tap away from
 * off; a group they would have wanted and never saw is not).
 *
 * ## The single system prompt
 * "Enable Selected" raises `POST_NOTIFICATIONS` if anything is on and the grant
 * is not already held. Below API 33 there is no such permission and the grant is
 * implicit, so nothing is raised. The user's choices are written **either way** —
 * the grant is a system fact that the Notifications screen reports separately,
 * and a denial must not silently discard which groups they chose.
 *
 * ## Where the choices go, and what still has to happen
 * [OnboardingPrefs] holds the three booleans under the keys the notification
 * layer will read. Translating them into the nine `NotificationType` values the
 * source sets, and re-syncing pending notifications, belongs to that layer — see
 * `NotificationPreferencesStore.setEnabled` in the source for the mapping
 * (routine + routineFollowUp ← dose reminders, hydration + sleep + phase ←
 * session alerts, cumulative + inventory ← safety net).
 */
@Composable
fun OnboardingRemindersStep(nav: OnboardingNav) {
    val context = LocalContext.current
    var requesting by remember { mutableStateOf(false) }

    // Held as state holders rather than `by` delegates so a reader can see they
    // are read at the moment of the write, not at composition.
    val doseReminders = remember { mutableStateOf(true) }
    val sessionAlerts = remember { mutableStateOf(true) }
    val safetyNet = remember { mutableStateOf(true) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // The result is deliberately ignored: the choices are written whether the
        // grant landed or not, and the Notifications screen is where a denial is
        // surfaced with a way back to Settings.
        requesting = false
        nav.advance()
    }

    OnboardingLayout(
        title = "Notifications, your pick",
        subtitle = "Choose what Piru may send. Everything stays adjustable in Settings, switch by switch.",
        hero = { OnboardingIconHero(Icons.Filled.Notifications) },
        mid = {
            OnboardingGroupedCard(
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp),
            ) {
                OnboardingToggleRow(
                    icon = Icons.Filled.Refresh,
                    title = "Never miss a dose",
                    detail = "Reminders at each routine's time — and, if you want, a gentle re-ask " +
                        "a little later, like snooze.",
                    checked = doseReminders.value,
                    onCheckedChange = { doseReminders.value = it },
                )
                OnboardingToggleRow(
                    icon = Icons.Filled.PlayArrow,
                    title = "During a session",
                    detail = "Hydration and wind-down nudges, wearing-off alerts, and onset/peak " +
                        "timing cues while something is active.",
                    checked = sessionAlerts.value,
                    onCheckedChange = { sessionAlerts.value = it },
                )
                OnboardingToggleRow(
                    icon = Icons.Filled.Warning,
                    title = "A safety net",
                    detail = "A heads-up if one substance's daily total climbs into a heavy " +
                        "range, or tracked stock runs low.",
                    checked = safetyNet.value,
                    onCheckedChange = { safetyNet.value = it },
                )
            }
        },
    ) {
        OnboardingPillButton(
            title = if (requesting) "Turning On…" else "Enable Selected",
            enabled = !requesting,
            onClick = {
                if (requesting) return@OnboardingPillButton
                // Written before the prompt, not after: the prompt is a system
                // fact and the choice is the user's.
                OnboardingPrefs.writeReminderChoices(
                    context = context,
                    doseReminders = doseReminders.value,
                    sessionAlerts = sessionAlerts.value,
                    safetyNet = safetyNet.value,
                )
                val anySelected = doseReminders.value || sessionAlerts.value || safetyNet.value
                if (anySelected && needsNotificationPrompt(context)) {
                    requesting = true
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    nav.advance()
                }
            },
        )
        OnboardingPillButton(
            title = "Not Now",
            prominence = Prominence.NEUTRAL,
            onClick = {
                // No choices written and nothing requested, matching the source:
                // "Not Now" is declining the question, not answering it "off".
                nav.advance()
            },
        )
    }
}

/**
 * Whether raising `POST_NOTIFICATIONS` would show a dialog.
 *
 * Below API 33 the permission does not exist and the grant is implicit, so the
 * answer is always no — asking anyway would return "granted" without showing
 * anything, which is harmless but misleading in the UI.
 */
private fun needsNotificationPrompt(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED
