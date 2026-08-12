plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core-model"))
    api(project(":core-world"))
    api(project(":core-grammar"))
    api(project(":inference"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    // Test-only. TraceJsonTest reflects over the traced types to catch a field
    // added to a data class but not to the hand-written exporter.
    testImplementation(kotlin("reflect"))
}
