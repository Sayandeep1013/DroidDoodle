pluginManagement {
    repositories {
        // google() must come first and must be present at all: the Android
        // Gradle Plugin is published only to Google's Maven, so without it the
        // root `plugins { ... apply false }` block fails to resolve during
        // configuration -- before any module is even evaluated.
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "droiddoodle"

// Pure-Kotlin modules. See docs/10-architecture.md §1.
include(":core-model")
include(":core-world")
include(":core-grammar")
include(":inference")
include(":core-agent")

// Android modules, included conditionally.
//
// Gradle configures every included project even when a single task is
// requested, so unconditionally including these would drag the Android Gradle
// Plugin and the Android SDK into the `jvm` CI job -- breaking the P0
// acceptance criterion that the fast job needs neither, and slowing the loop
// that actually catches our bugs.
//
// DD_SKIP_ANDROID=1 keeps that job pure Kotlin. Everything else -- local
// development, the `android` CI job, Android Studio -- gets the full build.
// :inference-llama joins in P8, when there is native code for it to hold.
if (System.getenv("DD_SKIP_ANDROID") != "1") {
    include(":app")
}
