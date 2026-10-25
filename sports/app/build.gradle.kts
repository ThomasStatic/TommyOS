import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signed with Lumen's key (launcher/keystore.properties) so a later Lumen ↔ Sports link can use a
// signature-level permission instead of an open component.
val launcherDir = rootProject.file("../launcher")
val keystoreProps = Properties().apply {
    File(launcherDir, "keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "app.lumen.sports"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.lumen.sports"
        minSdk = 26
        targetSdk = 30 // matches Fire OS 8 (Android 11)
        versionCode = 1
        versionName = "0.1-poc"
    }

    signingConfigs {
        create("release") {
            storeFile = File(launcherDir, keystoreProps.getProperty("storeFile", "lumen-release.jks"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint { checkReleaseBuilds = false }

    testOptions.unitTests.all {
        // test.sh runs Gradle with -q, so failures are logged at the quiet level.
        it.testLogging.quiet {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

dependencies {
    // Plain-JVM tests of the ESPN parsing, ticker text, polling and cache logic. Android's own
    // org.json is a stub off-device, so tests use the real library.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
