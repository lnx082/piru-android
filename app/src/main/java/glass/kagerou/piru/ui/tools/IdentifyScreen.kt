package glass.kagerou.piru.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.R
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.nav.AppTab
import glass.kagerou.piru.ui.theme.PiruTheme

/**
 * Identify a box — **not built, and this screen says so**.
 *
 * Ported from `Views/Tools/IdentifyBoxView.swift` (369 lines), whose whole
 * substance is a camera: `LabelScannerView` wraps VisionKit's
 * `DataScannerViewController` over on-device text recognition and barcode
 * decoding, and `BoxIdentifier` then resolves what was read against the catalog
 * and the packaged registries.
 *
 * ## What the port deliberately does not do
 * There is no scanner in this build, so there is no scan button, no reading, no
 * chips and no confidence marks. A screen that looked tappable and did nothing —
 * or that faked a reading from a fixture — would be worse than its absence: the
 * one thing this feature can do is tell someone what is inside a box they cannot
 * read, and a plausible-looking guess is the exact failure that costs.
 *
 * The intro copy below is the original's, kept because it is the honest
 * description of what the feature *is*. What follows it is the part the iOS
 * screen has no reason to say and this one cannot avoid.
 *
 * ## What it needs, named
 * - Camera permission and a preview surface (`CameraX`) — VisionKit's scanner is
 *   a view controller; its nearest Android equivalent is a `PreviewView` plus an
 *   `ImageAnalysis` use case.
 * - On-device text recognition and barcode decoding over the analysis frames
 *   (ML Kit's `TextRecognition` and `BarcodeScanning`), including the GS1
 *   symbologies the original requests — DataMatrix, EAN-13/8, UPC-E, Code 128,
 *   GS1 DataBar.
 * - The product-code lookup (`ProductCodeKey`'s GTIN-14 and NDC handling) and the
 *   packaged registry tables it matches against. Neither is in this build's
 *   catalog read path.
 *
 * Every one of those is a new dependency, and the port's rule for a screen that
 * is not built is to name what it needs rather than to render a stub. So: this.
 */
@Composable
fun IdentifyScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.identify_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.identify_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        item {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.identify_what_title), style = MaterialTheme.typography.titleSmall)
                    // Verbatim from IdentifyBoxView's intro card.
                    Text(
                        stringResource(R.string.identify_intro),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.identify_registries),
                        style = MaterialTheme.typography.bodySmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                    Text(
                        // The original's disclaimer, kept where it belongs: on the
                        // reading, which is a reading of the label. It is not this
                        // screen's footer, because this screen produces no reading.
                        stringResource(R.string.identify_label_disclaimer),
                        style = MaterialTheme.typography.bodySmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }

        item {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.identify_not_built_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.identify_not_built_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                    Text(
                        stringResource(R.string.identify_nothing_guessed),
                        style = MaterialTheme.typography.bodySmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }

        item {
            Text(
                stringResource(R.string.identify_what_works),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        item {
            Text(
                stringResource(R.string.identify_what_works_body),
                style = MaterialTheme.typography.bodyMedium,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = { navigator.select(AppTab.SEARCH) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.identify_search_by_name))
                }
                OutlinedButton(
                    onClick = { navigator.select(AppTab.LIBRARY) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.identify_browse_library))
                }
            }
        }

        item {
            // Deliberately not a string resource: this is the one sentence that
            // has to stay legible to someone who cannot read the rest of the
            // screen, so it is English in every build.
            Text(
                "Not medical advice.",
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
        }
    }
}
