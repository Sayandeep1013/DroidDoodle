cmake_minimum_required(VERSION 3.22.1)
project(droiddoodle_llama CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp is vendored as a pinned submodule. Pinning matters: GBNF behaviour
# and the GGUF format both change upstream, and an unpinned dependency turns a
# reproducible experiment into a moving target. See docs/25-inference.md §5.
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp)

if(NOT EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    message(FATAL_ERROR
        "llama.cpp submodule missing at ${LLAMA_DIR}. "
        "Run: git submodule update --init --recursive")
endif()

# Build the library only. add_subdirectory picks up upstream's own CMake, so the
# -DGGML_* / -DLLAMA_BUILD_* arguments set in build.gradle.kts apply here.
add_subdirectory(${LLAMA_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp EXCLUDE_FROM_ALL)

add_library(droiddoodle_llama SHARED llama_jni.cpp)

target_link_libraries(droiddoodle_llama
    PRIVATE
    llama
    android
    log
)

target_compile_options(droiddoodle_llama PRIVATE -Wall -Wextra)
