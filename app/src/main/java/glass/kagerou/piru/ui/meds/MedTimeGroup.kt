package glass.kagerou.piru.ui.meds

import glass.kagerou.piru.data.entity.DailyDoseItemEntity

/**
 * The derived time-of-day buckets of the My Meds hub.
 *
 * Ported from the `MedTimeGroup` enum in
 * `Piru/Views/Journal/DailyDose/MyMedsHubView.swift` (lines 10-93).
 *
 * ## Why there are no named containers
 * A med's reminder times slot it into Morning / Afternoon / Evening / Night on
 * their own, so a user never creates a "Morning" list to put a med in. A med
 * with no times is Anytime; a PRN med gets its own group.
 *
 * ## Declaration order is the ordering semantics
 * Kotlin enums take `compareTo` from the declaration order and it cannot be
 * overridden, so these five are declared in upstream's raw-value order
 * (`morning` 0 … `asNeeded` 5). Reordering them later would silently change any
 * comparison a consumer adds.
 *
 * [slug] is the stable string that rides in notification anchors and deep links
 * (`piru://quicklog?routine=morning`), which is why it is spelled out rather
 * than derived from the enum's name.
 */
enum class MedTimeGroup(
    /** The group's heading. */
    val label: String,
    /** The heading's quiet companion — the clock bounds the group means. */
    val rangeLabel: String,
    /** The stable identifier carried outside the app. */
    val slug: String,
) {
    MORNING("Morning", "before 12:00", "morning"),
    AFTERNOON("Afternoon", "12:00 – 17:00", "afternoon"),
    EVENING("Evening", "17:00 – 21:00", "evening"),
    NIGHT("Night", "after 21:00", "night"),
    ANYTIME("Anytime", "no set time", "anytime"),
    AS_NEEDED("As needed", "no schedule", "as-needed"),
    ;

    companion object {

        /**
         * The group a reminder time falls in.
         *
         * The bounds are hard-coded local hours rather than anything the user
         * configures: they are a reading of a clock the user already set, not a
         * claim about when anyone's morning starts.
         */
        fun groupForMinutes(minutes: Int): MedTimeGroup = when {
            minutes < 720 -> MORNING
            minutes < 1_020 -> AFTERNOON
            minutes < 1_260 -> EVENING
            else -> NIGHT
        }

        /** The group a stored [slug] names, or null for one this build predates. */
        fun fromSlug(slug: String): MedTimeGroup? = entries.firstOrNull { it.slug == slug }

        /**
         * Whether [item] shows in [group].
         *
         * PRN meds live only in [AS_NEEDED], meds without times only in
         * [ANYTIME], and a multi-time med appears in **every** group it has a
         * time in — that is what makes one med with a morning and an evening
         * dose visible at both ends of the hub rather than filed under one.
         */
        fun belongs(item: DailyDoseItemEntity, group: MedTimeGroup): Boolean {
            if (item.isAsNeeded) return group == AS_NEEDED
            val times = item.reminderTimesMinutes
            if (times.isEmpty()) return group == ANYTIME
            return times.any { groupForMinutes(it) == group }
        }

        /** How many slots one item carries per due day — the adherence expectation. */
        fun slotCount(item: DailyDoseItemEntity): Int = maxOf(1, item.reminderTimesMinutes.size)
    }
}
