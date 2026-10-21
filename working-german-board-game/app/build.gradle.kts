plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Firebase config is machine-local (gitignored); without it the app builds offline-only, as on CI.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

android {
    namespace = "com.mauro.wgbg"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mauro.wgbg"
        minSdk = 26
        targetSdk = 36
        versionCode = 19
        versionName = "0.3.5-pre1"
        // Instrumented tests (online play against the Firebase emulators, #35).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // CI signs every build with one stable debug key so phones can update between versions. The key file comes
    // from a repository secret via WGBG_DEBUG_KEYSTORE (no machine paths here); without it, the usual debug key.
    System.getenv("WGBG_DEBUG_KEYSTORE")?.takeIf { it.isNotBlank() }?.let { path ->
        signingConfigs.getByName("debug") { storeFile = file(path) }
    }

    buildTypes {
        // What players install (#147): the same code as debug, but not debuggable, so the test hooks are off (no MOVE
        // or FILAMENT_VIEW broadcasts, no WGBG-TURN lines, no run-as). Signed with the same stable key, so it installs
        // as an update over every earlier (debug) release. No R8: shrinking was never played online.
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        create("perf") {
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Keep gltfio and its native engine in lockstep. The live board is opt-in.
    implementation("com.google.android.filament:filament-android:1.66.1")
    implementation("com.google.android.filament:gltfio-android:1.66.1")
    implementation("com.google.android.filament:filament-utils-android:1.66.1")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation(project(":net"))
    implementation(project(":engine"))
    implementation(project(":ai"))
    implementation(libs.zxing.core)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    // Optional Google sign-in (#83): the account picker, and Task.await() for Firebase Auth.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.googleid)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
