pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "droiddoodle"

// Pure-Kotlin modules only. See docs/10-architecture.md §1.
//
// The Android modules (:inference-llama, :app) are deliberately NOT included
// yet. Gradle configures every included project even when a single task is
// requested, so including them would force the Android Gradle Plugin and the
// Android SDK onto the `jvm` CI job -- breaking the P0 acceptance criterion
// that the JVM job needs neither. They are added when P7/P8 begin.
include(":core-model")
include(":core-world")
include(":core-grammar")
include(":inference")
include(":core-agent")
