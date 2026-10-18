cmake_minimum_required(VERSION 3.22)
project(vdx_whisper C CXX)

# whisper.cpp — the Handy engine (MIT license). Fetched at native-build time
# into the Gradle build cache; nothing ships vendored, attribution via LICENSE
# remains in the upstream repo.
include(FetchContent)
FetchContent_Declare(
  whisper
  GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp
  GIT_TAG        v1.7.5
  GIT_SHALLOW    TRUE
)
set(BUILD_SHARED_LIBS OFF)
set(WHISPER_BUILD_TESTS OFF)
set(WHISPER_BUILD_EXAMPLES OFF)
set(WHISPER_BUILD_SERVER OFF)
FetchContent_MakeAvailable(whisper)

add_library(whisper_jni SHARED whisper_jni.cpp)
target_link_libraries(whisper_jni PRIVATE whisper log)
target_include_directories(whisper_jni PRIVATE ${whisper_SOURCE_DIR}/include)
target_link_options(whisper_jni PRIVATE -Wl,--gc-sections)
