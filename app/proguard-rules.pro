# R8 rules for the release build.
#
# Most of what this app does at runtime is *code R8 cannot see*: Room generates
# a DAO implementation per interface, kotlinx.serialization generates a serializer
# per @Serializable type, and neither is referenced by name in the source. Room
# and kotlinx.serialization both ship their own consumer rules for the common
# case, so this file is short on purpose — every line here is one a build actually
# needed, not a precaution copied from a blog.
#
# ## What a mistake here costs
# A release build that strips a generated class fails **at runtime**, on the first
# query or the first decode, with `NoSuchMethodError` or
# `SerializationException: Serializer for class ... is not found`. The debug build
# is unaffected, because R8 does not run on it. That asymmetry is why the release
# APK is smoke-tested rather than merely built.

# Room's generated implementations are looked up reflectively through the
# `_Impl` suffix convention. The consumer rules cover the annotated entities and
# DAOs; this keeps the generated names themselves, which the convention depends on.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keepclassmembers class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# kotlinx.serialization: the `Companion.serializer()` lookup is reflective on the
# JVM. Its consumer rules keep `@Serializable` types, but the app's serializers
# are hand-written (see `P3Color` and `DoseRange`) and are referenced only from
# those companions.
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Health Connect's record classes are constructed by the library from the
# permission set at runtime, and its `PermissionController` resolves them by
# class. Stripping an unused record type turns a granted permission into a
# `SecurityException` that names a permission the user did grant.
-keep class androidx.health.connect.client.records.** { *; }
-dontwarn androidx.health.connect.client.**

# The SQLite driver loads its native library by name.
-keep class androidx.sqlite.driver.bundled.** { *; }

# Keep the line numbers, and hide the original file names. A release crash report
# that says `a.b.c(SourceFile:3)` and nothing else is not worth collecting; the
# mapping file is what turns it back into something readable, and it is written
# beside the APK at `app/build/outputs/mapping/release/mapping.txt`. **Keep it
# with every release you publish** — without it, no stack trace from that build
# can ever be read again.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
