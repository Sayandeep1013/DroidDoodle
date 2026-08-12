plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.droiddoodle.inference.llama"
    compileSdk = 35

    // Supplied by CI from the runner's installed NDK. See app/build.gradle.kts.
    (findProperty("dd.ndkVersion") as String?)?.let { ndkVersion = it }

    defaultConfig {
        minSdk = 26

        // arm64 only, per docs/25-inference.md §5. Building ABIs we cannot test
        // would ship untested native code.
        ndk { abiFilters += "arm64-v8a" }

        externalNativeBuild {
            cmake {
                // -DLLAMA_BUILD_* off: we link the library, not the CLI tools.
                // GGML_OPENMP off: the NDK does not ship libomp for all ABIs and
                // we set thread count explicitly anyway.
                arguments += listOf(
                    // Exactly one .so ships, so there is no C++ ABI boundary to
                    // share and no libc++_shared.so to package.
                    "-DANDROID_STL=c++_static",
                    // Link llama and ggml statically into libdroiddoodle_llama.so.
                    // Upstream defaults to shared, which would leave AGP to
                    // discover and package four extra libraries.
                    "-DBUILD_SHARED_LIBS=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_CURL=OFF",
                )
                cppFlags += listOf("-O3", "-fexceptions", "-frtti")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(project(":core-model"))
    api(project(":inference"))
    implementation(libs.kotlinx.coroutines.core)
}
