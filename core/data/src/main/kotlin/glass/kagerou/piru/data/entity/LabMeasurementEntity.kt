package glass.kagerou.piru.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

/**
 * One blood draw: a measured analyte value at a point in time.
 *
 * Ported from the SwiftData `@Model LabMeasurement`
 * (`Shared/Models/PiruSchema.swift`), which is why this is a Room entity at all —
 * see below.
 *
 * ## Why this moved into the database
 * It used to live as JSON in a private `SharedPreferences` file
 * (`piru.labMeasurements`), justified on the grounds that it is "a small,
 * append-mostly list … never joined against anything". Both halves of that have
 * since stopped being true, and the arrangement cost the user data:
 *
 * - **It was outside every export, import and erase path.** The export layer
 *   described it as having "no table on Android" and omitted it; the importer
 *   counted it as dropped; `deleteAll` never touched a preferences file, so
 *   "Delete Everything" left it behind; and `android:allowBackup="false"` ruled
 *   out the OS backup. A restore from iOS discarded these rows and a reinstall
 *   lost them permanently.
 * - **It is read by more than one screen** — the hormone-levels screen and the
 *   injection-levels tool both read it — which is the condition the old comment
 *   named as the point at which this belongs "in the database beside the doses".
 *
 * The old comment was right; it was just describing a future that had arrived.
 */
@Entity(
    tableName = "lab_measurements",
    indices = [Index(value = ["analyte_key"]), Index(value = ["date"])],
)
data class LabMeasurementEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "row_id")
    val rowId: Long = 0,

    /**
     * The row's own identity, carried through export/import.
     *
     * Distinct from [rowId] for the same reason doses carry one: a row that
     * round-trips through a file must come back as the same entity, and an
     * autoincrement key is the database's business rather than the user's.
     */
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "date")
    val date: Date,

    /** `"estradiol"`, `"testosterone"`, or a companion's key (`"hematocrit"`, …). */
    @ColumnInfo(name = "analyte_key")
    val analyteKey: String,

    /**
     * The value in the analyte's **canonical** unit.
     *
     * [inputUnit] records what the user typed, so a row can echo their lab's own
     * unit back to them; the arithmetic always uses the canonical one.
     */
    @ColumnInfo(name = "value")
    val value: Double,

    @ColumnInfo(name = "input_unit")
    val inputUnit: String,

    /** The ester this reading belongs to, when the analyte has more than one. */
    @ColumnInfo(name = "ester_id")
    val esterId: String? = null,

    /**
     * Whether the user has taken this reading out of the calibration fit.
     *
     * A lab result can be right and still not belong in a regression — a draw
     * taken while travelling, or one the lab flagged — and excluding it is a
     * decision about the fit rather than about the measurement, so the two travel
     * together.
     */
    @ColumnInfo(name = "excluded_from_calibration", defaultValue = "0")
    val excludedFromCalibration: Boolean = false,

    /**
     * A free-text note the user attached to the draw.
     *
     * Absent from the old preferences blob, which is why it is nullable: rows that
     * arrive through [importLegacyRows] have none, and a null here means "no note"
     * rather than an empty one.
     */
    @ColumnInfo(name = "note")
    val note: String? = null,

    /**
     * When the row was written, as opposed to when the blood was drawn.
     *
     * Carried because iOS carries it: without it an import from iOS would have to
     * invent a `createdAt`, and re-exporting would then write a different value
     * than the file it came from.
     */
    @ColumnInfo(name = "created_at")
    val createdAt: Date = Date(),
)
