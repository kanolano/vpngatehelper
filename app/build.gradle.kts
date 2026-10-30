plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tb.vpngate"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tb.vpngate"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    // Signed only when a keystore is supplied, e.g. in ~/.gradle/gradle.properties:
    //   vpngate.keystore=/path/to/release.jks
    //   vpngate.storePassword=...   vpngate.keyAlias=...   vpngate.keyPassword=...
    // Without those, assembleRelease still builds but emits an unsigned APK, so a release can
    // never silently ship with debug keys.
    val releaseKeystore = (findProperty("vpngate.keystore") as String?)?.let(::file)

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = findProperty("vpngate.storePassword") as String?
                keyAlias = findProperty("vpngate.keyAlias") as String?
                keyPassword = findProperty("vpngate.keyPassword") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

/**
 * AGP 9 registers `generateReleaseComposePreviewRunfiles` for every variant once the Compose
 * plugin is applied, and that task needs unit tests enabled to package resources for Layoutlib.
 * Release unit tests are off by default, so realizing every task in this module -- `./gradlew
 * :app:tasks`, and some IDE syncs -- failed to configure. Turning them on for release costs a
 * task that nobody runs and keeps the task graph valid.
 */
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        (variant as com.android.build.api.variant.HasUnitTestBuilder).enableUnitTest = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    // Preview tooling is debug-only: previews live in src/debug, see ui/Previews.kt.
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.tooling.preview)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.okio)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    // Compose UI tests run on the JVM via Robolectric rather than as instrumented tests, so
    // they execute in `testDebugUnitTest` and in CI without a device or emulator attached.
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
