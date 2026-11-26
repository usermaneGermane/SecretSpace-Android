import java.util.Properties

// The Android app: screens only. Everything else is in :core, tested on a
// PC against the PC app's own code and a real server.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.usermanegermane.secretspace"
    compileSdk { version = release(37) { minorApiLevel = 1 } }

    defaultConfig {
        applicationId = "io.github.usermanegermane.secretspace"
        minSdk = 26                 // java.nio.file and java.time, which :core uses
        targetSdk = 37
        versionCode = 4
        versionName = "0.4.0"       // = secretspace.core.AppVersion.VERSION (a test checks)
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        // BouncyCastle carries files that only matter on a desktop JVM.
        resources.excludes += listOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/*.kotlin_module")
    }
    // Releases are signed with the project's key, kept outside the repository:
    // SECRETSPACE_SIGNING names its properties file (storeFile, storePassword,
    // keyAlias, keyPassword). Without it a release build is left unsigned.
    val signing = System.getenv("SECRETSPACE_SIGNING")?.let { file(it) }?.takeIf { it.isFile }
    if (signing != null) {
        val p = Properties().apply { signing.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = signing.parentFile.resolve(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (signing != null) signingConfig = signingConfigs.getByName("release")
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.1")
    // 2FA codes from a QR code: the camera, and ZXing to read the code (Apache-2.0, no Google services)
    implementation("androidx.camera:camera-camera2:1.7.0")
    implementation("androidx.camera:camera-lifecycle:1.7.0")
    implementation("androidx.camera:camera-view:1.7.0")
    implementation("com.google.zxing:core:3.5.4")
    // Unlock with fingerprint, face or the phone's PIN (the Android Keystore + the system's prompt).
    // 1.4.0, not the last stable 1.1.0: androidx.core 1.19 made the fingerprint helper 1.1.0 uses on
    // Android 8-9 a stub that always says "no sensor"; 1.4.0 carries its own (tested on API 26).
    implementation("androidx.biometric:biometric:1.4.0-rc01")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("androidx.appcompat:appcompat:1.7.1")      // the theme the prompt's dialog needs
    testImplementation("junit:junit:4.13.2")
}
