plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core-model"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}
