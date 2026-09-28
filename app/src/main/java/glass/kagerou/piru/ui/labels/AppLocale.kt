package glass.kagerou.piru.ui.labels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/**
 * The language this app's own resources resolved to — English or Simplified
 * Chinese, whichever of `values/` and `values-zh/` supplied the strings now on
 * screen.
 *
 * ## Why not `Locale.getDefault()`
 * `Locale.getDefault()` is the **device's** language, and this app ships two.
 * They are the same only when the phone happens to be set to one of them, and
 * they part company the moment it is not: a **German** phone gets the English
 * screens, because `values/` is the fallback for a language with no
 * `values-de/` — but `Locale.getDefault()` hands a date formatter German, and
 * the journal's own header reads `Montag, 28 September` above a row that says
 * "Nothing logged yet". The screen is consistent with itself and the date is
 * not, which reads as a bug in the date rather than in the phone.
 *
 * ## Why not `Locale.ROOT`
 * It fails in the other direction. It pins month and weekday *names* to English,
 * so a Chinese device reads "28 Sep" beside Chinese text. The pattern alone
 * cannot fix this, because a pattern carries the field *order* and the locale
 * carries the *words*.
 *
 * ## Why this is a mapping and not simply the configuration
 * `LocalConfiguration` reports the locale the app **asked for**, not the one the
 * lookup **landed on** — which is why the first version of this function changed
 * nothing. Android has no API for "which `values-*` folder did this string come
 * from", so the answer is written out here and has to stay in step with the
 * resource folders.
 *
 * It is keyed on **language**, not on region or script, and that is what the
 * folder names have to agree with: `values-zh` is a language qualifier, so every
 * Chinese variant resolves there — Simplified and Traditional alike — and every
 * non-Chinese variant resolves to `values/`. A `values-zh-rCN` folder would
 * break the agreement in a way nothing would report: `zh-TW` would fall back to
 * English strings while this function returned Chinese, and the dates would be
 * wrong in the opposite direction from the one this file exists to fix.
 *
 * Traditional readers get Simplified. That is the trade the two-language scope
 * makes, and it is the same trade the resource folder makes — it is written here
 * so the two cannot drift apart.
 */
@Composable
@ReadOnlyComposable
fun appLocale(): Locale = when (LocalConfiguration.current.locales[0].language) {
    Locale.CHINESE.language -> Locale.SIMPLIFIED_CHINESE
    else -> Locale.ENGLISH
}
