# Builds libwhisper_jni.so from the vendored whisper.cpp submodule + whisper_jni.cpp.
# Only included in the Gradle build when assembled with -PwithWhisper (see app/build.gradle.kts),
# so the default build needs no NDK/CMake. Modelled on whisper.cpp/examples/whisper.android.
cmake_minimum_required(VERSION 3.10)
project(whisper_jni CXX C)

set(CMAKE_CXX_STANDARD 17)
set(WHISPER_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

if(NOT EXISTS "${WHISPER_LIB_DIR}/src/whisper.cpp")
    message(FATAL_ERROR
        "whisper.cpp submodule missing. Run: git submodule update --init --recursive")
endif()

# Pull the whisper.cpp version string for diagnostics.
file(READ "${WHISPER_LIB_DIR}/CMakeLists.txt" MAIN_CMAKE_CONTENT)
string(REGEX MATCH "project\\(\"whisper\\.cpp\" VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)\\)"
       VERSION_MATCH "${MAIN_CMAKE_CONTENT}")
if(CMAKE_MATCH_1)
    set(WHISPER_VERSION ${CMAKE_MATCH_1})
else()
    set(WHISPER_VERSION "unknown")
endif()
message(STATUS "whisper.cpp version: ${WHISPER_VERSION}")

set(SOURCE_FILES
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/whisper_jni.cpp
)

find_library(LOG_LIB log)
include(FetchContent)

add_library(whisper_jni SHARED ${SOURCE_FILES})

target_compile_definitions(whisper_jni PUBLIC GGML_USE_CPU)
target_compile_definitions(whisper_jni PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")

if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(whisper_jni PRIVATE
        -O3 -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections)
    target_link_options(whisper_jni PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -flto)
endif()

# Build ggml from the submodule's copy.
FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
FetchContent_MakeAvailable(ggml)

target_include_directories(whisper_jni PRIVATE
    ${WHISPER_LIB_DIR}
    ${WHISPER_LIB_DIR}/src
    ${WHISPER_LIB_DIR}/include
    ${WHISPER_LIB_DIR}/ggml/include
)

target_link_libraries(whisper_jni ${LOG_LIB} android ggml)
