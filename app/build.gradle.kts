plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "glass.kagerou.piru"
    // Compose 1.12 and core-ktx 1.19 refuse to be consumed below API 37.
    // minSdk stays at 30: that floor comes from the bundled substance database's
    // SQL, which needs ROW_NUMBER and therefore SQLite ≥ 3.25.
    compileSdk = 37

    defaultConfig {
        applicationId = "glass.kagerou.piru"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
        // The About section reads the version from the build rather than repeating
        // it. Off by default since AGP 8, and a hand-written version string is one
        // that goes stale the first time nobody remembers to bump it.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:engine"))
    implementation(project(":core:substance"))
    implementation(project(":core:data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.health.connect)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    // The app module's first JVM tests. What belongs here is the pure logic that
    // lives in the app only because it is about navigation — the deep-link parser
    // is string arithmetic over a format two schedulers write, and a mistake in it
    // does not crash: it opens the wrong screen or nothing at all.
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
