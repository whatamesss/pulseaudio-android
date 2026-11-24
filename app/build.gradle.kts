import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

// Local-only signing credentials. keystore.properties and the keystore itself
// are gitignored; see README for how to create them. Without the file the
// release build still assembles, but the APK is left unsigned.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "us.foxtek.pulseserver"
    compileSdk = 34

    defaultConfig {
        applicationId = "us.foxtek.pulseserver"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
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
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = true
        explainIssues = true
        textReport = true
        baseline = file("lint-baseline.xml")
        disable += setOf(
            "AndroidGradlePluginVersion",
            "GradleDependency",
            "DataExtractionRules",
            "Overdraw",
            "UnusedResources",
            "MonochromeLauncherIcon",
            "SetTextI18n",
            "HardcodedText",
            // This is a sideloaded server app; the battery exemption is the point.
            "BatteryLife",
            // compileSdk/targetSdk stay on 34: AGP 8.3 does not support 35 and
            // targetSdk 35 would force edge-to-edge on Android 15.
            "OldTargetApi"
        )
    }
}

// This app is only ever built and installed as a release variant. Disabling
// the debug variant keeps `assemble`, `install`, tests, lint, and coverage on
// the release build so nothing can drift between "debug" and "release".
androidComponents {
    beforeVariants(selector().withBuildType("debug")) { variant ->
        variant.enable = false
    }
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/detekt.yml"))
    baseline = file("$rootDir/detekt-baseline.xml")
}

tasks.register("qualityCheck") {
    group = "verification"
    description = "Runs release tests, lint, detekt, and Kotlin coverage"
    dependsOn(
        "testReleaseUnitTest",
        "lintRelease",
        "detekt",
        "koverXmlReportRelease",
        "koverHtmlReportRelease"
    )
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
