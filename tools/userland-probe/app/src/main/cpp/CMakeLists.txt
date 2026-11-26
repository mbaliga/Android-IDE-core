cmake_minimum_required(VERSION 3.22.1)
project(userland_probe C)

# (1) The probe's own test executable, shipped as lib*.so so the APK installer places it in
# nativeLibraryDir. It prints identity and limits only (see probe_exec.c).
add_executable(probe_exec probe_exec.c)
set_target_properties(probe_exec PROPERTIES
    OUTPUT_NAME "probe_exec" PREFIX "lib" SUFFIX ".so")

# (2) JNI helper: ptrace / process_vm_readv / unshare / mmap-exec checks, run in forked children.
add_library(probe_jni SHARED probe_jni.c)
target_link_libraries(probe_jni log)

# (3) A trivial shared library the probe copies into filesDir and tries to dlopen.
add_library(probe_dl SHARED probe_dl.c)
