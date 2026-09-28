package glass.kagerou.piru.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import glass.kagerou.piru.data.JsonLists
import java.util.Date
import java.util.UUID

/**
 * One timestamped observation inside a [SessionEntity] — a session is a timeline
 * of these, each anchored to the moment it was made, optionally structured.
 *
 * Ported from `Shared/Models/SessionNote.swift`.
 *
 * Free text is the only field that is always meaningful; everything else is
 * captured when the person chose to. A note records; it never grades — nothing
 * here is interpreted by the app.
 */
@Entity(
    tableName = "session_notes",
    indices = [
        Index(value = ["timestamp"]),
        // Room requires an index on a foreign key's child column.
        Index(value = ["session_id"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            // A note has no meaning outside its session, which is the iOS
            // `.cascade` rule.
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SessionNoteEntity(
    /** Stable identifier for routing (a sheet editing one note) and export. */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: UUID = UUID.randomUUID(),

    /**
     * When the observation was made — editable, so a note typed later can be
     * placed at the moment it describes.
     */
    @ColumnInfo(name = "timestamp")
    val timestamp: Date,

    /** Free text; may be empty when only structure was captured. */
    @ColumnInfo(name = "text", defaultValue = "")
    val text: String = "",

    /** Shulgin rating `0…4` → ±, +, ++, +++, ++++ (PiHKAL, 1991). Null = not rated. */
    @ColumnInfo(name = "shulgin")
    val shulgin: Int? = null,

    /** Mood, `-3…+3`. Null = not captured. */
    @ColumnInfo(name = "mood")
    val mood: Int? = null,

    /** Energy, `-3…+3` (sedated … stimulated). Null = not captured. */
    @ColumnInfo(name = "energy")
    val energy: Int? = null,

    /**
     * Desire to be around people, `-3…+3` (alone … social). Separate from [mood]:
     * wanting company and feeling good are different axes, and on an empathogen
     * they are the axis people report.
     */
    @ColumnInfo(name = "social")
    val social: Int? = null,

    /**
     * Whether the dose did its job, `-1…+1`. A separate question from [shulgin]:
     * that scale measures how strong an experience is, this one whether a
     * medication performed the way it usually does.
     */
    @ColumnInfo(name = "worked")
    val worked: Int? = null,

    /**
     * SubFxOnEx concept ids (`subjective_effect_concepts.id`) as JSON — the
     * vocabulary lives in the bundled database, so a note stores identity, never
     * the display name.
     *
     * A JSON column rather than a related table because the iOS model declares a
     * plain `[String]` (SwiftData's native array), and keeping the shape
     * identical means a backup round-trips unchanged. Read it through
     * [descriptors]; nothing should parse the column directly.
     */
    @ColumnInfo(name = "descriptors_json", defaultValue = "")
    val descriptorsJson: String = "",

    /**
     * Heart rate (bpm) nearest the note's timestamp, captured when the health
     * overlay is on. Shown beside the note as a number and nothing more.
     */
    @ColumnInfo(name = "heart_rate")
    val heartRate: Double? = null,

    /**
     * What prompted the note, by wire value. Use [kind] rather than this column:
     * an unrecognized value would otherwise sit there unexamined, and an unknown
     * kind must read as an ordinary observation rather than break the row.
     */
    @ColumnInfo(name = "kind_raw", defaultValue = "observation")
    val kindRaw: String = Kind.OBSERVATION.wireValue,

    @ColumnInfo(name = "session_id")
    val sessionId: UUID? = null,
) {
    /** [descriptorsJson] as concept ids. Empty when unset or malformed. */
    val descriptors: List<String> get() = JsonLists.strings(descriptorsJson)

    /** The prompt behind this note; [kindRaw] resolved, defaulting to an observation. */
    val kind: Kind get() = Kind.fromWire(kindRaw)

    /**
     * `true` when the note carries something beyond its timestamp — the note
     * sheet's Save gate and the export's "skip empty" test.
     */
    val hasContent: Boolean
        get() = text.isNotBlank() ||
            shulgin != null || mood != null || energy != null ||
            social != null || worked != null ||
            descriptors.isNotEmpty() || heartRate != null

    /** What prompted the note. Persisted by [wireValue], matching the iOS raw values. */
    enum class Kind(val wireValue: String) {
        /** Written unprompted. */
        OBSERVATION("observation"),

        /** Written from a scheduled check-in notification. */
        CHECK_IN("checkIn"),

        /** The session's one summary — mirrors `Session.note`. */
        SUMMARY("summary"),
        ;

        companion object {
            fun fromWire(value: String): Kind =
                entries.firstOrNull { it.wireValue == value } ?: OBSERVATION
        }
    }
}
