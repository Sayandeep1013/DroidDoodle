plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin. No Android SDK on the compile classpath, which is what makes the
// dependency rules of docs/10-architecture.md §2 structurally enforced rather
// than merely documented.
