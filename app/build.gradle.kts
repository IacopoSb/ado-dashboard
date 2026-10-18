import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Release signing comes from the environment (GitHub Actions secrets, see
// .github/workflows/release.yml) or local.properties; local builds work without it.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun setting(env: String, local: String): String? = System.getenv(env) ?: localProps.getProperty(local)

// -PappVersion=1.2.3 -> versionName 1.2.3, versionCode 10203
val appVersion = (findProperty("appVersion") as String?) ?: "0.1.0"
val appVersionCode = appVersion.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    .let { (it + listOf(0, 0, 0)).take(3) }.let { (a, b, c) -> a * 10000 + b * 100 + c }
    .coerceAtLeast(1)

android {
    namespace = "io.github.iacoposb.adodash"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.iacoposb.adodash"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion
        // Releases of this repo are where the app looks for updates
        buildConfigField("String", "UPDATE_REPO", "\"IacopoSb/ado-dashboard\"")
    }

    signingConfigs {
        create("release") {
            val store = setting("SIGNING_KEYSTORE", "signing.keystore")
            if (!store.isNullOrBlank()) {
                storeFile = file(store)
                storePassword = setting("SIGNING_STORE_PASSWORD", "signing.storePassword")
                keyAlias = setting("SIGNING_KEY_ALIAS", "signing.keyAlias")
                keyPassword = setting("SIGNING_KEY_PASSWORD", "signing.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Unsigned (app-release-unsigned.apk) when no keystore is configured
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Screenshot tests write PNGs: app/build/outputs/roborazzi
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("roborazzi.record.filePathStrategy", "relativePathFromRoborazziContextOutputDirectory")
                it.systemProperty("roborazzi.outputDir", layout.buildDirectory.dir("outputs/roborazzi").get().asFile.path)
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.msal)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.glance.appwidget.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
