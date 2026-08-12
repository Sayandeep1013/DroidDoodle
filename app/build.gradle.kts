plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.droiddoodle.app"
    compileSdk = 35

    // CI passes the NDK version the runner actually has rather than pinning one
    // here: a pinned version that the runner lacks makes AGP fail before it ever
    // reaches the C++ compiler, and the pin would be guesswork anyway since
    // constraint C2 means no NDK exists locally to check against.
    (findProperty("dd.ndkVersion") as String?)?.let { ndkVersion = it }

    defaultConfig {
        applicationId = "dev.droiddoodle"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-p7"

        // arm64 only. See docs/25-inference.md §5 -- 32-bit ARM and x86 are not
        // supported targets, and shipping ABIs we cannot test is dishonest.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // For BuildConfig.DEBUG, which gates the scripted-engine escape hatch in
        // the model picker. It must not exist in a release build.
        buildConfig = true
    }

    lint {
        // A text report so CI can publish it over raw HTTPS like everything
        // else. Not a gate: lint's defaults include stylistic checks that
        // would turn a useful signal into noise the loop learns to ignore.
        textReport = true
        abortOnError = false
        checkDependencies = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-world"))
    implementation(project(":core-agent"))
    implementation(project(":inference"))
    implementation(project(":inference-llama"))
    // The Prompt Suite is data the app runs, not a test dependency.
    implementation(project(":prompt-suite"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
