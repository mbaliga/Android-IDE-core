// SPIKE COMPAT HEADER -- NOT part of core-engine, NOT the Android NDK header.
//
// core-engine/src/main/cpp/llama_jni.cpp does `#include <android/log.h>` unconditionally and logs
// through __android_log_print(). That header only exists in the NDK, so the shim cannot compile
// for Linux as written (FINDING F6, see README). Because the additive rule forbids editing the
// existing .cpp, this spike puts THIS header first on the include path so the unmodified source
// compiles; the equivalent upstream fix is proposed/llama_jni-android-guard.patch (also built and
// checked, by the `patched_copy` target).
#pragma once
#include <cstdarg>
#include <cstdio>

enum {
    ANDROID_LOG_UNKNOWN = 0, ANDROID_LOG_DEFAULT, ANDROID_LOG_VERBOSE, ANDROID_LOG_DEBUG,
    ANDROID_LOG_INFO, ANDROID_LOG_WARN, ANDROID_LOG_ERROR, ANDROID_LOG_FATAL, ANDROID_LOG_SILENT,
};

static inline int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
    static const char kLevel[] = {'?', '?', 'V', 'D', 'I', 'W', 'E', 'F', 'S'};
    std::fprintf(stderr, "%c/%s: ", kLevel[prio < 0 || prio > 8 ? 0 : prio], tag);
    va_list ap;
    va_start(ap, fmt);
    int n = std::vfprintf(stderr, fmt, ap);
    va_end(ap);
    std::fputc('\n', stderr);
    return n;
}
