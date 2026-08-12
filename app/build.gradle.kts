plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// A stable signing key, supplied by CI. Without one, AGP generates a fresh
// debug keystore on every machine -- and on a fresh CI runner that means every
// build is signed with a different key. Android then refuses to install the new
// APK over the old one, so the only way in is to uninstall, which deletes
// filesDir and with it the several-hundred-megabyte model. That is why the
// model was being downloaded again after every update.
val devKeystore = (findProperty("dd.keystore") as String?)?.takeIf { it.isNotBlank() }?.let(::file)

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

    signingConfigs {
        if (devKeystore != null) {
            create("dev") {
                storeFile = devKeystore
                // A development key with a published password. It exists to
                // keep the signature stable across builds, not to protect
                // anything; treating it as a secret would only stop it doing
                // its job.
                storePassword = "droiddoodle"
                keyAlias = "droiddoodle"
                keyPassword = "droiddoodle"
            }
        }
    }

    buildTypes {
        // Both types use the same key, so a debug build and a release build can
        // replace one another without losing the downloaded model.
        val signing = signingConfigs.findByName("dev") ?: signingConfigs.getByName("debug")
        debug {
            isMinifyEnabled = false
            signingConfig = signing
        }
        release {
            isMinifyEnabled = false
            signingConfig = signing
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
