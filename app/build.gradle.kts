import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Release signing material is never committed (repo rule). The release build
// signs only when all four anansi.release.* local.properties keys (or their
// ANANSI_RELEASE_* environment fallbacks) are present; storeFile resolves
// relative to the repo root. Without material the release build degrades to
// an unsigned APK so secret-free machines can still run the release check.
val releaseLocalProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun releaseSetting(name: String, envVar: String): String? =
    releaseLocalProperties.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envVar)?.takeIf { it.isNotBlank() }

val releaseStoreFilePath = releaseSetting("anansi.release.storeFile", "ANANSI_RELEASE_STORE_FILE")
val releaseStorePassword = releaseSetting("anansi.release.storePassword", "ANANSI_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSetting("anansi.release.keyAlias", "ANANSI_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSetting("anansi.release.keyPassword", "ANANSI_RELEASE_KEY_PASSWORD")
val releaseSigningComplete = releaseStoreFilePath != null &&
    releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.chief.anansi"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.chief.anansi"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (releaseSigningComplete) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // Minification stays off for v0.1.0: the QuickJS JNI surface and
            // the plugin serialization DTOs are exactly what shrinking breaks,
            // and the APK-size win at this app size is marginal.
            isMinifyEnabled = false
            if (releaseSigningComplete) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Plugin-host scripts are Android assets; unit tests load them off the
    // test classpath instead.
    sourceSets {
        getByName("test") {
            resources.srcDir("src/main/assets")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.coil)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.media3.session)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.quickjs.kt)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Unit tests run the JS engine on the desktop JVM: the Android variant's
    // natives cannot load there, so the unit-test classpath swaps the Android
    // artifact for the JVM one (which bundles desktop natives).
    testImplementation(libs.quickjs.kt.jvm)
    testImplementation(libs.junit)
}

configurations.matching { it.name.endsWith("UnitTestCompileClasspath") || it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(module = "quickjs-kt-android")
}
