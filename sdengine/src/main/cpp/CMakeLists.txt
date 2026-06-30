# Builds libaarso_sd.so: stable-diffusion.cpp + its own ggml + the JNI shim.
# Separate from :app so sd.cpp's ggml fork never collides with llama.cpp's ggml.

cmake_minimum_required(VERSION 3.22.1)
project(aarso_sd LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Optimize (debug variant defaults to -O0) and target the device's CPU features.
add_compile_options(-O3 -march=armv8.2-a+dotprod+i8mm+fp16)

set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)  # required for cross-compile
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(SD_BUILD_EXAMPLES    OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SD_WEBP              OFF CACHE BOOL "" FORCE)
set(SD_WEBM              OFF CACHE BOOL "" FORCE)

add_subdirectory(stable-diffusion.cpp)

add_library(aarso_sd SHARED sd_jni.cpp)

target_include_directories(aarso_sd PRIVATE
    stable-diffusion.cpp/include
    stable-diffusion.cpp/thirdparty)

find_library(log-lib log)

target_link_libraries(aarso_sd
    stable-diffusion
    ${log-lib})
