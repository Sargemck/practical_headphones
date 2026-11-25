import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

// Release signing from keystore.properties at the repo root (gitignored): storeFile, storePassword,
// keyAlias, keyPassword. Without it, release builds are unsigned, which is what F-Droid and the
// reproducible-build check use.
val keystoreProperties = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

android {
    namespace = "io.github.sargemck.practicalheadphones"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.sargemck.practicalheadphones"
        minSdk = 31
        targetSdk = 37
        versionCode = 2
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProperties != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // v3 lets the key be rotated later without breaking updates. v1 isn't needed at minSdk 31.
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (keystoreProperties != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    // Reproducible builds (F-Droid): no build-time timestamps or VCS info in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.useJUnitPlatform()
            // Robolectric reaches FileDescriptor internals (SDK 37 shared memory), closed by default on JDK 21.
            it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }
}

roborazzi {
    outputDir.set(file("src/test/screenshots"))
}

dependencies {
    implementation(project(":bmap"))
    implementation(project(":sony"))
    implementation(project(":soundcore"))
    implementation(project(":samsung"))
    implementation(project(":nothing"))
    implementation(project(":jbl"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":bmap")))
    testImplementation(testFixtures(project(":sony")))
    testImplementation(testFixtures(project(":soundcore")))
    testImplementation(testFixtures(project(":samsung")))
    testImplementation(testFixtures(project(":nothing")))
    testImplementation(testFixtures(project(":jbl")))
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Robolectric brings espresso 3.5.1, which calls InputManager.getInstance(), gone in SDK 37.
    testImplementation(libs.androidx.test.espresso.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // Demo mode runs the screen on the simulated headphones. Debug only: release has no fake.
    debugImplementation(testFixtures(project(":bmap")))
    debugImplementation(testFixtures(project(":sony")))
    debugImplementation(testFixtures(project(":soundcore")))
    debugImplementation(testFixtures(project(":samsung")))
    debugImplementation(testFixtures(project(":nothing")))
    debugImplementation(testFixtures(project(":jbl")))

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
