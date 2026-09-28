package glass.kagerou.piru.ui.meds

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.R
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.Substance
import glass.kagerou.piru.model.SubstanceCategory
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Consequences at pick-time (`Specs/archive/meds-ux-review.md` §9).
 *
 * Ported from `Piru/Views/Journal/DailyDose/MedTimeConsequence.swift` (207 lines).
 *
 * ## Why the form states pharmacology
 * A time picker that says only "morning" makes the user do the pharmacology in
 * their head: they choose 4 PM without any way to see that this particular med
 * is still working at midnight. Piru already models onset, peak and offset for
 * every dose it draws — the same numbers behind the journal curve — so the form
 * can simply *state* them against the clock the user is setting.
 *
 * Reference, not instruction: it says what the model says the med does, and
 * never that a time is wrong or that a different one would be better.
 *
 * ## Two divergences from the Swift source
 * - `SubstanceCategory.wakePromoting` does not exist in this port's
 *   `:core:model` (upstream declares it in `Piru/Domain/SubstanceCategory.swift`
 *   line 174). It is defined here as [WAKE_PROMOTING] rather than added to the
 *   core module, because a second reader would want it there and one form does
 *   not justify touching a module every other feature depends on.
 * - `Date.formatted(date: .omitted, time: .shortened)` is `clockText` in
 *   `MedsChrome.kt`, reading the ambient locale — a display preference, the one
 *   place this port does not force `Locale.ROOT`.
 */

/** The three moments of one scheduled time, as absolute instants. */
data class ConsequenceTimes(
    val onset: Instant,
    val wearOff: Instant,
    val effectsEnd: Instant,
)

/**
 * When a scheduled dose starts working, starts easing off, and — for the
 * classes that hold sleep off — when it is out of the way of it.
 *
 * Minutes are relative to the scheduled time. All three come from the profile
 * the timeline itself draws ([Substance.timelineDuration], phase-filled exactly
 * as the curve is), so the form and the graph can never disagree about the same
 * med.
 */
data class MedTimeConsequence(
    /** End of the onset phase — the modeled moment effects begin. */
    val onsetMinutes: Double,
    /** End of the peak plateau — where the offset limb starts. */
    val wearOffMinutes: Double,
    /** End of acute effects, the same value the entry rail calls "effects ended". */
    val effectsEndMinutes: Double,
    /**
     * Whether this med's class is one that holds sleep off, making the
     * effects-end time worth stating against bedtime rather than left implicit.
     */
    val affectsSleep: Boolean,
) {

    /**
     * Whether the easing-off moment is far enough from the end to be worth its
     * own clause. A profile with no offset phase puts both at the same minute,
     * where printing them twice reads as a rendering bug.
     */
    val statesWearOff: Boolean
        get() = effectsEndMinutes - wearOffMinutes >= 1 && wearOffMinutes - onsetMinutes >= 1

    /**
     * The three moments as absolute instants, anchored to [minutesOfDay] on
     * [on]'s day in [zone].
     *
     * Crossing midnight is ordinary here (a 4 PM stimulant routinely clears the
     * next morning), so the instants simply run forward — there is no wrapping
     * back to the same day.
     */
    fun clockTimes(minutesOfDay: Int, on: Instant, zone: ZoneId): ConsequenceTimes {
        val base = on.atZone(zone).toLocalDate()
            .atTime(minutesOfDay / 60, minutesOfDay % 60)
            .atZone(zone)
            .toInstant()
        return ConsequenceTimes(
            onset = base.plusMillis((onsetMinutes * 60_000).roundToLong()),
            wearOff = base.plusMillis((wearOffMinutes * 60_000).roundToLong()),
            effectsEnd = base.plusMillis((effectsEndMinutes * 60_000).roundToLong()),
        )
    }

    /**
     * Whether effects run into the hours most people are asleep. A population
     * statement, and the only kind available: Piru holds no bedtime for anyone.
     */
    fun landsInNight(minutesOfDay: Int, on: Instant, zone: ZoneId): Boolean =
        isNight(clockTimes(minutesOfDay, on, zone).effectsEnd, zone)

    companion object {

        /**
         * Local hours the sleep clause treats as "most people's night". Used
         * only to decide whether the effects-end time is worth flagging — never
         * to prescribe a bedtime, which Piru does not know and does not ask for.
         */
        const val NIGHT_START_HOUR: Int = 22
        const val NIGHT_END_HOUR: Int = 6

        /**
         * The classes whose effects hold sleep off.
         *
         * Upstream's `SubstanceCategory.wakePromoting` — see this file's note on
         * why it is declared here rather than in `:core:model`.
         */
        val WAKE_PROMOTING: Set<SubstanceCategory> = setOf(
            SubstanceCategory.STIMULANT,
            SubstanceCategory.EMPATHOGEN,
            SubstanceCategory.EUGEROIC,
        )

        /**
         * The modeled consequence of [substance] on [route], or null — "say
         * nothing" — whenever the model has no acute answer.
         *
         * Null is the honest answer for a hand-typed custom substance, and for a
         * chronic med (SSRIs, thyroid, GLP-1s) whose
         * [Substance.timelineDuration] is deliberately absent because an
         * onset → peak → offset shape is the wrong model for it. A med that
         * steadies a level over weeks does not "kick in" at 9:35, and inventing
         * a time for it would be worse than the silence this replaces.
         */
        fun resolve(substance: Substance?, route: RouteOfAdministration): MedTimeConsequence? {
            val raw = substance?.timelineDuration(route) ?: return null
            // The same phase fill the curve is drawn with, so endpoint-only
            // source data (a `total` with no come-up/peak) still yields a peak
            // boundary instead of collapsing onto the onset.
            val profile = raw.fillingMissingPhases(substance.category)
            val bounds = profile.phaseBoundaries
            val end = profile.estimatedTotalMinutes
            val onset = max(0.0, bounds.onsetEnd)
            if (end <= onset) return null
            return MedTimeConsequence(
                onsetMinutes = onset,
                wearOffMinutes = min(max(bounds.peakEnd, onset), end),
                effectsEndMinutes = end,
                affectsSleep = substance.category in WAKE_PROMOTING,
            )
        }

        /** Whether a moment falls in the hours most people are asleep. */
        fun isNight(date: Instant, zone: ZoneId): Boolean {
            val hour = date.atZone(zone).hour
            return hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR
        }

        /**
         * When a dose logged at [at] is one whose effects the model runs past
         * the hours most people sleep — and the instant they end. Null for
         * anything else: a dose that clears before the night, a class the app
         * may not talk about bedtime for, or a substance with no acute curve.
         *
         * The quick-log dock states this beside the interaction warnings. By the
         * time a 6 PM stimulant is being logged, the useful fact is not that it
         * will work — it is the hour it stops.
         */
        fun nightEnd(
            substance: Substance?,
            route: RouteOfAdministration,
            at: Instant,
            zone: ZoneId,
        ): Instant? {
            val consequence = resolve(substance, route) ?: return null
            if (!consequence.affectsSleep) return null
            val local = at.atZone(zone)
            val minutesOfDay = local.hour * 60 + local.minute
            if (!consequence.landsInNight(minutesOfDay, at, zone)) return null
            return consequence.clockTimes(minutesOfDay, at, zone).effectsEnd
        }
    }
}

/**
 * "Modeled effects end ~6:35 AM" beside a dose that is still running.
 *
 * The one fact a stimulant logged at 1 AM raises and the curve does not answer
 * on its own: the graph's own window usually ends before the tail does, and the
 * hour the effects run out is the hour that decides the night. Draws only for a
 * class the app may talk about bedtime for, and only when the end actually
 * lands in the night — a morning dose says nothing here.
 */
@Composable
fun DoseSleepClause(
    effectsEnd: Instant,
    affectsSleep: Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    if (!affectsSleep || !MedTimeConsequence.isNight(effectsEnd, zone)) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MedsGlyph(kind = MedsGlyphKind.MOON, tint = PiruTheme.colors.secondaryLabel)
        Text(
            stringResource(R.string.meds_consequence_effects_end, clockText(effectsEnd, zone)),
            style = captionSecondaryStyle,
        )
    }
}

/**
 * The line under one time row: what that time does.
 *
 * Its own composable so a keystroke in the amount field doesn't re-evaluate it,
 * and so the (pure) formatting stays testable apart from the form's draft state.
 */
@Composable
fun MedTimeConsequenceLine(
    consequence: MedTimeConsequence,
    minutesOfDay: Int,
    zone: ZoneId = ZoneId.systemDefault(),
    now: Instant = Instant.now(),
) {
    val moments = consequence.clockTimes(minutesOfDay, now, zone)
    val landsInNight = consequence.landsInNight(minutesOfDay, now, zone)
    Column(
        modifier = Modifier.padding(top = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MedsGlyph(kind = MedsGlyphKind.CLOCK, tint = PiruTheme.colors.secondaryLabel)
            Text(
                if (consequence.statesWearOff) {
                    stringResource(
                        R.string.meds_consequence_onset_and_wear_off,
                        clockText(moments.onset, zone),
                        clockText(moments.wearOff, zone),
                    )
                } else {
                    stringResource(R.string.meds_consequence_onset, clockText(moments.onset, zone))
                },
                style = captionSecondaryStyle,
            )
        }
        if (consequence.affectsSleep) {
            val end = clockText(moments.effectsEnd, zone)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MedsGlyph(
                    kind = MedsGlyphKind.MOON,
                    // Orange only when the end actually lands in the night: a
                    // morning dose must not wear the same mark as a midnight one.
                    tint = if (landsInNight) PiruTheme.colors.caution else PiruTheme.colors.secondaryLabel,
                )
                Text(
                    if (landsInNight) {
                        stringResource(R.string.meds_consequence_after_bedtime, end)
                    } else {
                        stringResource(R.string.meds_consequence_effects_end, end)
                    },
                    style = captionSecondaryStyle,
                )
            }
        }
    }
}
