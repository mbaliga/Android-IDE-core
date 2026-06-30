# Native build for the llama.cpp JNI bridge (aarso_llama).
#
# Builds llama.cpp (CPU-only, arm64) and the JNI shim into one shared library.
# Wired into app/build.gradle.kts via externalNativeBuild. Requires the NDK and
# the llama.cpp submodule checked out at ./llama.cpp.

cmake_minimum_required(VERSION 3.22.1)
project(aarso_llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# CRITICAL: the Android debug variant builds native code at -O0, which makes
# llama.cpp 10-50x slower. Force real optimization regardless of build type, and
# enable the arm64 CPU features the target SoC has — dotprod + i8mm give
# the big speedups for quantized matmul. (These instructions require a modern
# arm64 core; this app targets such devices and gates downloads by spec.)
add_compile_options(-O3 -march=armv8.2-a+dotprod+i8mm+fp16)

# --- llama.cpp build options (set before add_subdirectory) ---
# GGML_NATIVE must be OFF when cross-compiling (no -march=native on the host CPU).
set(GGML_NATIVE      OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP      OFF CACHE BOOL "" FORCE)  # NDK has no libomp by default
set(LLAMA_CURL       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

add_library(aarso_llama SHARED llama_jni.cpp)

find_library(log-lib log)

target_link_libraries(aarso_llama
    llama
    ${log-lib})
