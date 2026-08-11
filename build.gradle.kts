plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(17)

            // Warning rather than strict mode, deliberately. Constraint C2 means
            // nothing compiles on the development machine, so strict explicit-API
            // would turn a style rule into a build blocker with a CI-round-trip
            // feedback loop. Flip to explicitApi() once CI is green.
            // See docs/10-architecture.md §6.
            explicitApiWarning()
        }

        dependencies {
            add("testImplementation", platform("org.junit:junit-bom:${libs.versions.junit.get()}"))
            add("testImplementation", libs.junit.jupiter)
            add("testRuntimeOnly", libs.junit.platform.launcher)
            add("testImplementation", kotlin("test"))
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("failed", "skipped")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}
