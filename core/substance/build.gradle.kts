plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))

    // The read layer implements the engine's catalog port. `api` rather than
    // `implementation` because a caller wiring the two together — `:core:data` —
    // names `SubstanceCatalog` in its own types.
    api(project(":core:engine"))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.sqlite.jdbc)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The bundled database is fetched, not tracked (db/fetch-db.sh). Its specs are
// end-to-end checks against that real file, so they need its path — and they
// SKIP rather than fail when it is absent, matching how the upstream Python
// suite treats a checkout that has not built the database yet.
val substanceDb = rootProject.layout.projectDirectory.file("db/piru-substances.sqlite")

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("piru.substanceDb", substanceDb.asFile.absolutePath)
}
