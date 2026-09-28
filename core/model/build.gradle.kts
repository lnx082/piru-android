plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
