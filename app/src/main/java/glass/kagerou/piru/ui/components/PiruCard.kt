package glass.kagerou.piru.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.ui.theme.PiruTheme

/**
 * The app's card: the skin's fill and the skin's corner.
 *
 * Every card in the port goes through here rather than calling `Card` directly,
 * for the same reason upstream routes its ~1,000 colour call sites through
 * `Theme.*` — a token that each screen applies by hand is a token that some
 * screens will not apply. Material's defaults are not the same values: its
 * `surfaceContainerLow` corner is 12, and the skin's is 22.
 *
 * @param onClick when set, the card is a button. The journal's rows are; a
 *   summary panel is not, and an inert card that looks tappable is worse than one
 *   that does not.
 */
@Composable
fun PiruCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = PiruTheme.colors
    val shape = colors.cardShape
    if (onClick == null) {
        Card(
            modifier = modifier,
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
            content = content,
        )
    } else {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
            content = content,
        )
    }
}

/**
 * Room a scrolling list must leave for the floating action button.
 *
 * A FAB floats *above* content, so without this it covers the last line of
 * whatever is longest — which is how a dose's duration line ends up unreadable
 * with no indication that anything is missing. One constant rather than a literal
 * at each list, so the three screens cannot drift apart on it.
 */
internal val FAB_CLEARANCE = 88.dp
