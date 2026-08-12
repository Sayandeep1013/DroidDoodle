plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core-model"))
    api(project(":core-world"))
    api(project(":core-grammar"))
    api(project(":core-agent"))
    api(project(":inference"))

    testImplementation(libs.kotlinx.coroutines.test)
}
