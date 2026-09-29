// Explicit, because the `java` extension Gradle installs on the project shadows
// the `java` package inside this script — `java.util.Properties` resolves to a
// property lookup on that extension and fails with "Unresolved reference 'util'".
import java.util.Properties

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
        versionCode = 5
        versionName = "0.4.0"
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

    /**
     * The release signing identity, read from `keystore.properties` at the repo
     * root — which is gitignored, and must stay that way.
     *
     * Deliberately **absent rather than defaulted**. A release build with no
     * signing config still produces an APK; it is unsigned and cannot be
     * installed over anything, which is a confusing failure a long way from its
     * cause. This reads the file when it exists and says nothing when it does
     * not, so `assembleDebug` works on a fresh clone and `assembleRelease`
     * without a keystore fails at the point of signing with a message naming the
     * missing file.
     *
     * To make one:
     *   keytool -genkeypair -v -keystore piru-release.jks -alias piru \
     *           -keyalg RSA -keysize 4096 -validity 10000
     * then write `storeFile`, `storePassword`, `keyAlias`, `keyPassword` into
     * `keystore.properties`. **Back the file up somewhere you will still have it
     * in five years.**
     */
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties().apply {
        if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8, and the reason it is worth the trouble here: this app is mostly
            // *generated* code — Room's DAOs, kotlinx.serialization's serializers,
            // Compose's compiler output — and all three are exactly what a shrinker
            // gets wrong by default. The failure mode is the bad one: a debug build
            // that works, a release build that throws `NoSuchMethodError` on the
            // first database query, and nothing in between to point at it.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    implementation(libs.androidx.glance.appwidget)

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
