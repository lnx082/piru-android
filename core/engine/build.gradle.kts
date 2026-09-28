plugins {
    alias(libs.plugins.kotlin.jvm)
    // Needed here, not only in :core:model: this module declares its own
    // @Serializable types (the zero-order kinetics carried on a substance state),
    // and without the plugin they compile but throw at runtime.
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
