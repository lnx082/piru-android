package glass.kagerou.piru.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The color of one substance the user has met.
 *
 * Ported from `Shared/Models/SubstanceColor.swift`.
 *
 * ## Default and custom
 * A substance's default color is derived from its class and identity (see
 * `SubstanceColorGenerator`). A row with [usesDefault] set holds that generated
 * color; a row with it cleared holds a color the user picked. The resolved
 * Display P3 components are stored either way, because a reader with no
 * substance catalog — the widget, and later the Android equivalent — has
 * nothing to generate from.
 *
 * ## Uniqueness
 * [substance] is unique: exactly one row per substance name. SwiftData treats a
 * duplicate insert as an upsert against the existing row, so callers recolor
 * the existing instance rather than constructing a new one. Room has no upsert
 * on a unique index, so the DAO reproduces that with a transaction — never a
 * bare `insert`, which would throw.
 */
@Entity(
    tableName = "substance_colors",
    indices = [Index(value = ["substance"], unique = true)],
)
data class SubstanceColorEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** Substance name this color applies to. Unique key. */
    @ColumnInfo(name = "substance")
    val substance: String,

    /**
     * sRGB hex from builds that predate class colors. Non-empty marks a row the
     * color-update notice has yet to convert; [tint] reads it until then.
     * Nothing writes a new value here.
     */
    @ColumnInfo(name = "hex_color", defaultValue = "")
    val hexColor: String = "",

    /** Encoded Display P3 components of the resolved color. */
    @ColumnInfo(name = "red", defaultValue = "0")
    val red: Double = 0.0,

    @ColumnInfo(name = "green", defaultValue = "0")
    val green: Double = 0.0,

    @ColumnInfo(name = "blue", defaultValue = "0")
    val blue: Double = 0.0,

    /** Whether the components are the generator's output for this substance. */
    @ColumnInfo(name = "uses_default", defaultValue = "1")
    val usesDefault: Boolean = true,
) {
    /** Whether the row still carries its pre-class-colors hex. */
    val isLegacy: Boolean get() = hexColor.isNotEmpty()
}
