# S-FB3 -- Linux x86_64 CPU build of core-engine's llama JNI shim, out of tree.
#
# Mirrors core-engine/src/main/cpp/CMakeLists.txt (same llama.cpp options, same target name
# `aarso_llama` so System.loadLibrary("aarso_llama") works unchanged) with exactly the deltas a
# Linux host needs. It adds_subdirectory() the existing sources READ-ONLY: nothing under
# core-engine/ is edited and the Android build is not involved.
cmake_minimum_required(VERSION 3.22)
project(fonebrew_llama_linux_spike LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

set(CORE_CPP "${CMAKE_CURRENT_LIST_DIR}/../../../core-engine/src/main/cpp" CACHE PATH "core-engine native sources")
if(NOT EXISTS "${CORE_CPP}/llama.cpp/CMakeLists.txt")
  message(FATAL_ERROR "llama.cpp submodule is not checked out. Run: git submodule update --init core-engine/src/main/cpp/llama.cpp")
endif()

# DELTA 1 (vs core): no -march=armv8.2-a+dotprod+i8mm+fp16 (arm64-only; core adds it with add_compile_options).
# DELTA 2: static ggml/llama objects are linked into a SHARED lib, so they must be position-independent.
#          (The NDK toolchain does this implicitly; a plain Linux toolchain does not.)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Same llama.cpp options as core. GGML_NATIVE OFF does NOT mean baseline x86-64: with it off, ggml's x86
# options default ON for SSE4.2, AVX, AVX2, BMI2, FMA and F16C (AVX512* stay OFF) -- see CMakeCache.txt in the
# build tree. The resulting shim therefore needs an AVX2-capable CPU. (Core's arm64 flags are the analogue.)
option(SFB3_SHARED_GGML "Build llama/ggml as shared libs (libllama.so, libggml*.so) instead of static-into-shim" OFF)
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)   # deprecated at this llama.cpp pin (llama_option_depr(WARNING LLAMA_CURL)); kept to mirror core
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    ${SFB3_SHARED_GGML} CACHE BOOL "" FORCE)

add_subdirectory("${CORE_CPP}/llama.cpp" llama.cpp-build)

find_package(JNI REQUIRED)

# ---- the shim: the UNMODIFIED llama_jni.cpp + the compat android/log.h ------------------------
add_library(aarso_llama SHARED "${CORE_CPP}/llama_jni.cpp")
target_include_directories(aarso_llama PRIVATE "${JNI_INCLUDE_DIRS}" "${CMAKE_CURRENT_LIST_DIR}/compat")
target_link_libraries(aarso_llama PRIVATE llama)  # DELTA 3: no `log` library on Linux (find_library(log) is Android-only)
if(SFB3_SHARED_GGML)
  # let the dynamic loader find libllama.so / libggml*.so next to the shim
  set_target_properties(aarso_llama PROPERTIES BUILD_RPATH "\$ORIGIN" INSTALL_RPATH "\$ORIGIN")
endif()

# ---- negative control: the same unmodified source WITHOUT the compat header must NOT compile ----
add_library(negative_control OBJECT EXCLUDE_FROM_ALL "${CORE_CPP}/llama_jni.cpp")
target_include_directories(negative_control PRIVATE "${JNI_INCLUDE_DIRS}")
target_link_libraries(negative_control PRIVATE llama)

# ---- patched copy: proves the exact patch recorded in the README is correct and sufficient ------
set(PATCHED_DIR "${CMAKE_BINARY_DIR}/patched")
add_custom_command(
  OUTPUT "${PATCHED_DIR}/llama_jni.cpp"
  COMMAND ${CMAKE_COMMAND} -E make_directory "${PATCHED_DIR}"
  COMMAND ${CMAKE_COMMAND} -E copy "${CORE_CPP}/llama_jni.cpp" "${PATCHED_DIR}/llama_jni.cpp"
  COMMAND patch "${PATCHED_DIR}/llama_jni.cpp" "${CMAKE_CURRENT_LIST_DIR}/proposed/llama_jni-android-guard.patch"
  DEPENDS "${CORE_CPP}/llama_jni.cpp" "${CMAKE_CURRENT_LIST_DIR}/proposed/llama_jni-android-guard.patch"
  COMMENT "Applying proposed/llama_jni-android-guard.patch to a COPY of llama_jni.cpp")
add_library(patched_copy OBJECT EXCLUDE_FROM_ALL "${PATCHED_DIR}/llama_jni.cpp")
target_include_directories(patched_copy PRIVATE "${JNI_INCLUDE_DIRS}")   # NOTE: no compat dir
target_link_libraries(patched_copy PRIVATE llama)
