package glass.kagerou.piru.ui.quicklog

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import glass.kagerou.piru.ui.theme.PiruTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The quick log sheet takes focus when it opens — on a device, with a real IME.
 *
 * ## Why this is an instrumentation spec and not a JVM one
 * The defect it pins is "the keyboard never appears and the first characters go nowhere",
 * and neither half of that is visible to a JVM shadow: Robolectric has no IME, so the field
 * can report `focused=true` there while nothing on a phone would receive typing. Against a
 * real device the field is a real `EditText` behind a real input connection, which is the
 * machinery that was wrong.
 *
 * It is also the shape of the user report rather than a unit: "I tapped + and typed, and
 * the substance field was empty". This spec opens the sheet and asks whether the field is
 * the focused node, which is that report reduced to its assertion.
 *
 * ## Why a bare `ComponentActivity`
 * `createComposeRule` renders into a `ComposeView` on an activity of its own, but the window
 * is never focused — and focus requests in an unfocused window are dropped, so the spec
 * would report "not focused" against a build that is fine. `ComponentActivity` gives a real
 * focused window while setting no content of its own; `MainActivity` cannot be used, because
 * it sets its own content and the rule then refuses the second `setContent`.
 */
@RunWith(AndroidJUnit4::class)
class QuickLogFocusTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * The sheet's one editable node is the focused node as soon as it is composed.
     *
     * Asked by capability (`hasSetTextAction`) rather than by label, because the label is a
     * translation and the field is the only editable thing on the sheet either way.
     */
    @Test
    fun theSubstanceFieldTakesFocusWhenTheSheetOpens(): Unit {
        compose.setContent {
            // The sheet reads `PiruTheme.colors` directly, so it has to be hosted inside the
            // theme — the app does that at its root, and a spec that skipped it would fail
            // on the theme rather than on the focus it is asking about.
            PiruTheme {
                QuickLogSheet(onDismiss = {}, onCommitted = {})
            }
        }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        Unit
    }
}
