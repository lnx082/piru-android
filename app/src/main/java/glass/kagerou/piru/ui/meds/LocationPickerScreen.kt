package glass.kagerou.piru.ui.meds

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme

/**
 * A place chosen for a dose: a display name plus its coordinate.
 *
 * Ported from `PickedLocation` in
 * `Piru/Views/Journal/DailyDose/LocationPickerView.swift` (lines 1-10), with one
 * deliberate change: upstream's coordinate is non-optional because MapKit always
 * has one. This build can name a place but cannot locate it, so the coordinate
 * half is nullable. `0.0, 0.0` would have been a real point in the Gulf of
 * Guinea, and a dose stamped there is worse than a dose stamped nowhere.
 */
data class PickedLocation(
    val name: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/**
 * Attaching a place to a dose — and, in this build, what that would take.
 *
 * ## What happened to the map
 * Upstream is 328 lines of MapKit and Core Location: `MKLocalSearchCompleter`
 * for as-you-type place completions, `MKLocalSearch` to resolve a completion to
 * a coordinate, `CLLocationManager` plus `MKReverseGeocodingRequest` for a
 * one-shot "current location". None of that has a counterpart in this build's
 * dependencies, and the honest reason is not that it is hard — it is that **a
 * place search and a blue dot are paid services**:
 *
 * - a Maps SDK for Android artifact plus a Google Maps API key, with the Places
 *   API and the Geocoding API enabled on a billing account, and the key
 *   restricted to this app's package and signing certificate;
 * - `ACCESS_FINE_LOCATION` in the manifest, a runtime permission request, a
 *   rationale, and a Play Store data-safety declaration for it.
 *
 * The port's rule for this is written down in `docs/porting-conventions.md`:
 * *a screen that is not built yet says so and names what it needs.* So this
 * screen offers the two things it genuinely can do, and states plainly what the
 * third one costs.
 *
 * ## What it can do, and why those are not consolation prizes
 * - **Name the place.** A dose's location is a memory aid ("the festival", "the
 *   flat"), and a string carries that memory on its own; the coordinate is what
 *   a map *would* have added, not what makes the field useful.
 * - **Use the time.** The dose already records when it was logged, from the
 *   device clock, with no permission and no key. "When" is answered; "where" is
 *   the optional half.
 *
 * ## No new dependencies
 * This screen adds none. That is the point: a location feature that arrives
 * with a billing account attached is a decision for the project, not for a
 * porting task.
 */
@Composable
fun LocationPickerScreen(
    /** Places already used, most recent first. */
    recents: List<PickedLocation> = emptyList(),
    onPick: (PickedLocation) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Location", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            MedsGlyph(
                kind = MedsGlyphKind.CLOSE,
                tint = PiruTheme.colors.secondaryLabel,
                size = 18.dp,
                modifier = Modifier.clickable(onClick = onDismiss).padding(6.dp),
            )
        }

        // The honest note, first, so nobody types a search query into a field
        // that was never going to search anything.
        PiruCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("No map, no place search", style = MaterialTheme.typography.titleSmall)
                Text(
                    "This build has no map and cannot look up an address or find your " +
                        "current location. A map picker needs the Google Maps SDK, a " +
                        "Places API key on a billing account, and the location " +
                        "permission — none of which this build carries. You can still " +
                        "name the place below, and the dose already records the time " +
                        "from the device clock.",
                    style = captionSecondaryStyle,
                )
            }
        }

        PiruCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Name the place", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Place") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // One action, and only when there is something to record: the
                // "no location" case is already what a dose has by default, so a
                // control for it would be a button that does nothing.
                TextButton(
                    enabled = query.isNotBlank(),
                    onClick = { onPick(PickedLocation(name = query.trim())) },
                ) {
                    Text(
                        "Use This Name",
                        color = if (query.isNotBlank()) {
                            PiruTheme.colors.accent
                        } else {
                            PiruTheme.colors.tertiaryLabel
                        },
                    )
                }
            }
        }

        if (recents.isNotEmpty()) {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Recents", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
                    // Deduped by name before rendering, which is what upstream's
                    // `uniqued(by: \.name)` does — a `ForEach` keyed on a value
                    // cannot see a duplicate id, and this list is keyed on the
                    // name it prints.
                    for (place in recents.distinctBy { it.name }) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(place) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MedsGlyph(
                                kind = MedsGlyphKind.PILL,
                                tint = PiruTheme.colors.accent,
                                size = 14.dp,
                            )
                            Text(place.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.padding(bottom = FAB_CLEARANCE))
    }
}

/**
 * What the full picker would need, named so the gap is actionable rather than
 * merely stated.
 *
 * Kept as a value rather than as prose in the composable so the list can be
 * read — and checked off — without opening the screen.
 */
internal val LOCATION_PICKER_REQUIREMENTS: List<String> = listOf(
    "com.google.android.gms:play-services-maps (or maps-compose) for the map surface",
    "A Google Maps API key with Places API and Geocoding API enabled, on a billing account",
    "The key restricted to this app's package name and signing certificate",
    "ACCESS_FINE_LOCATION (or COARSE) in the manifest plus a runtime permission request",
    "A Play Store data-safety declaration covering location",
)
