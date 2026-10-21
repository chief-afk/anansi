import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
        versionName = "0.1.0-alpha"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
