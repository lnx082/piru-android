plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    // Needed here, not only in :core:model: this module declares its own
    // @Serializable types (the catalog manifest, and the custom-substance
    // blobs), and without the plugin they compile but throw at runtime with
    // "Serializer for class … is not found".
    alias(libs.plugins.kotlin.serialization)
}

// The exported schema is the migration history. Room refuses to run a migration
// it cannot verify against a recorded schema, and this store has no server copy
// to restore from — so the JSON under `schemas/` is tracked, not generated
// build output.
room {
    schemaDirectory("$projectDir/schemas")
}

/**
 * Stage the fetched substance catalog into this module's assets.
 *
 * The catalog and its manifest live in `db/` at the repo root, fetched and never
 * tracked. They are copied here rather than pointing an assets source set at
 * `db/` directly — that directory also holds fetch-db.sh, which has no business
 * shipping inside the APK.
 */
abstract class StageCatalogAssets : DefaultTask() {
    @get:InputFile
    abstract val sqlite: RegularFileProperty

    @get:InputFile
    abstract val manifest: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun stage() {
        val out = outputDir.get().asFile
        out.mkdirs()
        sqlite.get().asFile.copyTo(File(out, "piru-substances.sqlite"), overwrite = true)
        manifest.get().asFile.copyTo(File(out, "manifest.json"), overwrite = true)
    }
}

val stageCatalogAssets = tasks.register<StageCatalogAssets>("stageCatalogAssets") {
    sqlite.set(rootProject.layout.projectDirectory.file("db/piru-substances.sqlite"))
    manifest.set(rootProject.layout.projectDirectory.file("db/manifest.json"))
    outputDir.set(layout.buildDirectory.dir("generated/catalogAssets"))
}

android {
    namespace = "glass.kagerou.piru.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // The catalog is opened by copying it out of the APK; leaving it
        // compressed would mean inflating 18 MB on first launch for no gain.
        noCompress += "sqlite"
    }
}

// Registered through the variant API rather than `sourceSets["main"].assets`,
// which trips a decorator cast inside AGP 9's Kotlin DSL. This also wires the
// task dependency, so nothing has to remember to order the two by hand.
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            stageCatalogAssets,
            StageCatalogAssets::outputDir,
        )
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:engine"))
    api(project(":core:substance"))

    // `api`, not `implementation`: `PiruDatabase` is a public type that *is* a
    // RoomDatabase, so a module handing one out cannot compile without the runtime
    // on its own classpath. Hiding it makes the app module's use of a repository
    // fail with a message about a missing supertype, which names the symptom rather
    // than the cause.
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.sqlite.bundled)

    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions.core)
    testRuntimeOnly(libs.junit.platform.launcher)

    // The DAO specs run on a device: the rules they check are SQL behaviour — which
    // rows survive a persist and which are rewritten — so a shadow of SQLite would
    // test the shadow. `room-testing` brings the in-memory builder.
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.core)
    androidTestImplementation(libs.kotest.assertions.core)
    // JUnit 4 through `androidx.test.ext:junit`, not JUnit 5: instrumentation runs on
    // AndroidJUnitRunner, which is a JUnit 4 runner. The JVM specs in `test/` stay on
    // JUnit 5 — the two source sets do not share a framework, and cannot.
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
