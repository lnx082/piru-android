package glass.kagerou.piru.ui.journal

/**
 * Which timeline options are worth offering, given the others.
 *
 * Ported from the note above upstream's options menu, and it is a real rule rather than a nicety: **zoom, PK curves
 * and gap compression describe the strip's geometry, so with the axis off — the bubbles stacked as a plain list —
 * they are left out rather than offered with no visible effect.**
 *
 * Upstream's own comment records why "left out" and not "disabled": `.disabled` on a menu-style `Picker` inside a
 * `Menu` **renders fully active** on iOS 26, so a greyed-out control is not available there as an option. The same
 * reasoning holds on a Compose `DropdownMenu`, where a disabled row still takes a tap target and still reads as
 * something the user might be able to turn on. Omitting the row is the honest version: an option that cannot do
 * anything is not an option.
 *
 * Kept separate from the drawing because it is the sort of rule that gets lost — a menu that offers zoom while the
 * axis is off looks entirely normal and does nothing.
 */
internal object TimelineOptions {

    /**
     * One row the menu should offer.
     *
     * Only rows that **change what is drawn**. Upstream's menu has two more — modeled curves and gap compression —
     * and neither is here: this port has no concentration-curve layer for the first to draw, and no vertical bubble
     * strip with empty stretches for the second to collapse. A row a user can toggle with no effect is a lie about
     * the app, and it is worse than an absent row because it costs a tap to discover.
     */
    enum class Row {
        ZOOM,
        SHOWS_AXIS,

    /**
     * The session chart's cardio lane: heart rate and blood pressure from Health Connect.
     *
     * Beside the axis because it is the same kind of question — what the chart draws — and this enum's order is the
     * order the cards appear in, so the member's position is the feature's position.
     */
    VITALS,
        COMPACT_ENTRIES,
    }

    /**
     * The rows to offer, in order.
     *
     * With the axis on: all three. With it off: the axis toggle and the bubble style, and nothing else — because
     * those two are the only ones that still describe what is on screen.
     *
     * The bubble style stays because it changes the rows themselves rather than the strip's geometry, which is why
     * upstream calls it "Compact Entries" and keeps it available either way.
     */
    fun rows(showsAxis: Boolean): List<Row> = if (showsAxis) {
        listOf(Row.ZOOM, Row.SHOWS_AXIS, Row.COMPACT_ENTRIES, Row.VITALS)
    } else {
        // The cardio lane is on both lists, beside the bubble style and for the same reason: it describes content the
        // session chart carries, and the lane is drawn whether or not the axis strip is. Hiding its switch when the axis
        // is off would leave a lane the reader can see and cannot switch off.
        listOf(Row.SHOWS_AXIS, Row.COMPACT_ENTRIES, Row.VITALS)
    }

    /**
     * Whether [row] is offered.
     *
     * A predicate as well as the list, because a call site that draws the rows in a fixed order needs to ask about
     * one rather than iterate — and having both keeps the two answers from drifting.
     */
    fun offers(row: Row, showsAxis: Boolean): Boolean = row in rows(showsAxis)

    /**
     * Whether turning the axis off should also stop compressing gaps.
     *
     * It should not, and this is worth stating: the preference is **kept** rather than cleared, so turning the axis
     * back on restores what the user had. Clearing it would make the toggle a one-way trip — the failure mode the
     * port's own tab settings already hit once, where hiding every tab in turn left one because the stored shape
     * could not express the answer.
     */
    fun retainsHiddenPreferences(): Boolean = true
}
