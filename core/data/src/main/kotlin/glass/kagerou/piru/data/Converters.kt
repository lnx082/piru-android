package glass.kagerou.piru.data

import androidx.room.TypeConverter
import glass.kagerou.piru.model.RouteOfAdministration
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * Column conversions for the value types the iOS store persists.
 *
 * ## Dates are epoch milliseconds
 * SwiftData stores a `Date` as a `Double` of seconds since the 2001 reference
 * date. Nothing outside SwiftData ever sees that representation: the Piru export
 * format writes `Date.msSince1970` as an `Int64` millisecond value, and the DTOs
 * that cross the app boundary do the same. Storing milliseconds here keeps the
 * column in the same terms as the wire format, so an export needs no conversion
 * and an import round-trips exactly.
 *
 * ## Enums are their wire values
 * [RouteOfAdministration] persists as its `wireValue` (`"oral"`,
 * `"insufflation"`, …), matching the iOS side — the column stays human-readable
 * and stable if the Kotlin type is ever renamed. An unrecognized value reads as
 * [RouteOfAdministration.OTHER] rather than throwing: the iOS vocabulary is
 * open-ended, and a stored dose must never become unreadable because upstream
 * coined a route this build predates.
 */
class Converters {

    @TypeConverter
    fun uuidToString(value: UUID?): String? = value?.toString()

    @TypeConverter
    fun stringToUuid(value: String?): UUID? = value?.let(UUID::fromString)

    /**
     * An [Instant] in the same millisecond column a [Date] uses.
     *
     * Not a second date convention — the stored value is identical either way,
     * which is the point. The storage layer's older entities carry `java.util.Date`
     * because that is what SwiftData's `Date` maps onto; the newer ones carry
     * `java.time.Instant` because that is what the engine and the value types
     * already speak, and converting at the entity boundary on every comparison was
     * pure ceremony. Two Kotlin types, one column type, one representation.
     */
    @TypeConverter
    fun instantToEpochMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun epochMillisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun dateToEpochMillis(value: Date?): Long? = value?.time

    @TypeConverter
    fun epochMillisToDate(value: Long?): Date? = value?.let(::Date)

    @TypeConverter
    fun routeToWire(value: RouteOfAdministration?): String? = value?.wireValue

    @TypeConverter
    fun wireToRoute(value: String?): RouteOfAdministration? = value?.let { stored ->
        RouteOfAdministration.entries.firstOrNull { it.wireValue == stored }
            ?: RouteOfAdministration.OTHER
    }
}
