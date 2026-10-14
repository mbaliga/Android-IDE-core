# S-FB3 part B -- Linux x86_64 CPU build of sdengine's JNI shim (libaarso_sd.so), out of tree.
#
# A SEPARATE CMake project from ../CMakeLists.txt on purpose: stable-diffusion.cpp carries its own
# ggml fork whose targets (`ggml`, `ggml-base`, `ggml-cpu`) collide with llama.cpp's if both are
# add_subdirectory()'d into one project. core avoids that the same way (two Gradle modules,
# two CMake projects: :core-engine -> libaarso_llama.so, :sdengine -> libaarso_sd.so), and this
# spike mirrors it. Mirrors sdengine/src/main/cpp/CMakeLists.txt minus the arm64 -march flags
# and the Android `log` library. Nothing under sdengine/ is edited.
cmake_minimum_required(VERSION 3.22)
project(fonebrew_sd_linux_spike LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()
set(CMAKE_POSITION_INDEPENDENT_CODE ON)   # static ggml/sd objects go into a SHARED lib

set(SD_CPP "${CMAKE_CURRENT_LIST_DIR}/../../../../sdengine/src/main/cpp" CACHE PATH "sdengine native sources")
if(NOT EXISTS "${SD_CPP}/stable-diffusion.cpp/CMakeLists.txt" OR NOT EXISTS "${SD_CPP}/stable-diffusion.cpp/ggml/CMakeLists.txt")
  message(FATAL_ERROR "stable-diffusion.cpp (or its nested ggml submodule) is not checked out. Run: git submodule update --init --recursive sdengine/src/main/cpp/stable-diffusion.cpp")
endif()

set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)   # (x86: ggml then defaults AVX2/FMA/BMI2/F16C ON; see ../CMakeLists.txt)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(SD_BUILD_EXAMPLES    OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SD_WEBP              OFF CACHE BOOL "" FORCE)
set(SD_WEBM              OFF CACHE BOOL "" FORCE)

add_subdirectory("${SD_CPP}/stable-diffusion.cpp" sd.cpp-build)

find_package(JNI REQUIRED)

# The UNMODIFIED sd_jni.cpp; its only Android dependency is <android/log.h>, satisfied by the same
# compat header S-FB3 part A uses (see ../compat/android/log.h and the README finding F6).
add_library(aarso_sd SHARED "${SD_CPP}/sd_jni.cpp")
target_include_directories(aarso_sd PRIVATE
  "${JNI_INCLUDE_DIRS}"
  "${CMAKE_CURRENT_LIST_DIR}/../compat"
  "${SD_CPP}/stable-diffusion.cpp/include"
  "${SD_CPP}/stable-diffusion.cpp/thirdparty")
target_link_libraries(aarso_sd PRIVATE stable-diffusion)
