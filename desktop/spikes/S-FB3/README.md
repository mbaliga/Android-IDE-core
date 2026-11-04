# S-FB3 — Linux x86_64 CPU build of the llama JNI shim, `System.load`ed into a JVM (and next to the sd shim)

**Verdict: PROVEN** on `CONTAINER-BUILD-ONLY` evidence (Linux x86_64, glibc; JNI headers from the container's JDK 21, test JVM = the Gradle JDK-17 toolchain, `Eclipse Adoptium 17.0.20.1`, printed by the first test),
with one required patch recorded as a finding (**F1**, LX-7 territory). **Never run on a hosted CI runner**
(see "CI status"). Plan step: `docs/PORTING_PLAN.md` §6.1 **LX-2 / S-FB3**, including the half the plan marks
"unknown": *both ggml-bearing libraries (llama and sd) loaded in one JVM*.

## The question

1. Can core-engine's **unmodified** `llama_jni.cpp`, with the **pinned llama.cpp submodule** (`5343f450`, ggml
   0.13.1), be built for Linux x86_64 (CPU only) out of tree — without touching the Android build — and
   `System.load`ed into a JVM so that the shim **and** the ggml code resolve in one process? Calling a real model
   is **not** required and is **not** done: no model file exists in this repo or container.
2. Plan "unknown": do `libaarso_llama.so` and `libaarso_sd.so` — each carrying *its own* ggml fork, both
   exporting the same `ggml_*` names — coexist in one JVM?

## Evidence

```bash
git submodule update --init --depth 1 -- core-engine/src/main/cpp/llama.cpp                       # public
git submodule update --init --depth 1 --recursive -- sdengine/src/main/cpp/stable-diffusion.cpp    # public, part B only
export LANG=C.UTF-8 LC_ALL=C.UTF-8                       # JDK + cmake + a C++17 compiler + `patch`; no Android SDK/NDK
./gradlew -p desktop/spikes/S-FB3 --no-daemon test                       # part A, static variant (the Android layout): 8 tests
./gradlew -p desktop/spikes/S-FB3 --no-daemon test -Pfb3.shared=true     # part A, shared libllama/libggml*: same 8 tests
./gradlew -p desktop/spikes/S-FB3 --no-daemon testTwoLibs                # part B, llama + sd shims in one JVM: 3 tests
```

The native build is `cmake` **out of tree** into `desktop/spikes/S-FB3/build/native/<variant>/` (git-ignored).
`CMakeLists.txt` `add_subdirectory()`s `core-engine/src/main/cpp/llama.cpp` and compiles
`core-engine/src/main/cpp/llama_jni.cpp` **read-only**; part B does the same for `sdengine/` from a *separate*
CMake project (`sd/`), exactly as core uses two Gradle modules, because the two ggml forks' CMake target names
collide inside one project. No existing file is edited (`git diff` is empty after the run).

Result on 2026-10-14 in the build container: part A static **8/8**, part A shared **8/8**, part B **3/3**, 0 failures.

| Part | Test | What it shows |
|---|---|---|
| A | the shim is an `ELF64 / X86-64 / DYN` shared object | the artefact is what it claims to be |
| A | `System.loadLibrary("aarso_llama")` — the same call core's `LlamaCppEngine` makes — loads it | `/proc/self/maps` lists `libaarso_llama.so` (and, in the shared variant, `libllama.so.0`, `libggml.so.0`, `libggml-base.so.0`, `libggml-cpu.so.0`) |
| A | `nativeLoadModel("/definitely/not/a/model.gguf", 512)` returns `0` | the JNI symbol resolves and **real llama.cpp code runs** (`llama_backend_init`, the GGUF reader, which logs `gguf_init_from_file: failed to open GGUF file …`) |
| A | `nativeGenerate(0, …)` calls back `TokenSink.onError("no model loaded")` | the shim's JNI callback path (`GetMethodID` on `onToken`/`onDone`/`onError`) works against a Kotlin object |
| A | `nativeFree(0)`, `nativeRequestStop(0)`, `nativeCountTokens(0, …) == 0` | the null-handle guards |
| A | all five `Java_dev_fonebrew_inference_LlamaCppEngine_*` are exported; static variant defines `llama_backend_init`, `llama_model_load_from_file`, `ggml_init`, `ggml_backend_cpu_init`; `DT_NEEDED` is `libstdc++/libm/libgcc_s/libc` (+ `libllama.so.0` in the shared variant) — **no `liblog`/`libandroid`**; `ldd` has no `not found`; no undefined `android_log` reference | symbol resolution is complete |
| A | **negative control**: the *unmodified* `llama_jni.cpp` compiled **without** the compat header exits non-zero with `llama_jni.cpp:16:10: fatal error: android/log.h: No such file or directory` | finding **F1** is real, not assumed |
| A | the **proposed patch** applied (with `patch(1)`) to a *copy* of `llama_jni.cpp` compiles with no compat header | the recorded patch is correct and sufficient |
| B | both shims are built; **800** `ggml_*` symbols are defined (default visibility) by **both** `libaarso_llama.so` and `libaarso_sd.so` | the interposition hazard is real |
| B | both load into one JVM; `nativeLoadModel` (llama) and `nativeSdLoad` (sd) on missing files each return `0`; `nativeSdTxt2Img(0,…)` returns `false`; 8 further calls from a 4-thread pool, alternating the two libraries, all return as expected | both ggml copies initialise and run side by side |
| B | `LD_DEBUG=bindings` on a child JVM: **71** ggml/llama references from the llama shim and **88** ggml/sd references from the sd shim, **0** of them bound to the other library | each shim stays bound to its *own* ggml (the JVM `dlopen`s with `RTLD_LOCAL`); the check refuses to pass vacuously (needs > 0 of each) |

Sizes / times observed (this container, other builds running at the same time, so treat as indicative):
`libaarso_llama.so` static **5.6 MB**; `libaarso_sd.so` **56 MB**; a from-scratch static llama build ≈ **6 min**
at `-j3`; a clean part-A-static + part-B run (both native trees from scratch, then all 11 tests) took **528 s** wall (machine load differed between runs, so the two figures are not comparable); the sd build's one expensive translation unit is F7.

## Findings

| # | Finding | Evidence |
|---|---|---|
| **F1** | **`llama_jni.cpp` cannot compile for Linux as written (LX-7).** It does `#include <android/log.h>` unconditionally (line 16) and logs through `__android_log_print` via `LOGE`/`LOGI` (lines 29–30). `sd_jni.cpp` has the same two dependencies (lines 6, 14). Exact error: `core-engine/src/main/cpp/llama_jni.cpp:16:10: fatal error: android/log.h: No such file or directory`. **Because the additive rule forbids editing the file, this spike puts a compat `android/log.h` first on the include path (`compat/android/log.h`, a stderr-printing stub) and builds the unmodified source.** The equivalent upstream fix, verified to compile on a copy with no compat header, is `proposed/llama_jni-android-guard.patch` (reproduced below). Nothing was applied to `core-engine/`. | negative-control + `patched_copy` tests |
| F2 | **Deltas from core's `CMakeLists.txt` a Linux host needs:** drop `add_compile_options(-O3 -march=armv8.2-a+dotprod+i8mm+fp16)` (arm64-only; not tried on x86); drop `find_library(log-lib log)` and the `log` link (Android-only); set `CMAKE_POSITION_INDEPENDENT_CODE ON` because static ggml/llama objects are linked into a SHARED lib (set proactively — what happens without it was **not** tested); `LLAMA_CURL` is `llama_option_depr(WARNING …)` at this llama.cpp pin (core sets it `OFF`, harmless). Target name `aarso_llama` is kept so `System.loadLibrary("aarso_llama")` works unchanged. `aarso_pty` (`pty_exec.cpp`) was not built. | `CMakeLists.txt` |
| F3 | **`GGML_NATIVE=OFF` does not mean baseline x86-64.** With it off, ggml's x86 options default **ON** for SSE4.2, AVX, **AVX2**, BMI2, FMA and F16C (AVX-512 stays OFF) — read from `CMakeCache.txt` for both the llama.cpp and the stable-diffusion.cpp copy. The shims therefore **require an AVX2-capable CPU** (running them on an older one was not tested). A portable/desktop-distributable build needs an explicit choice (a baseline flag set, or `GGML_CPU_ALL_VARIANTS` + `GGML_BACKEND_DL` runtime dispatch) — an LX-7 packaging decision. | `CMakeCache.txt` |
| F4 | **Shared variant (`-DSFB3_SHARED_GGML=ON`).** ggml's CMake writes `libllama.so.0`, `libggml.so.0`, `libggml-base.so.0`, `libggml-cpu.so.0` (real files `….so.0.13.1`) into `<build>/bin`; the shim's `DT_NEEDED` is `libllama.so.0` and its `RUNPATH` is `$ORIGIN:<absolute build-tree path>`. It loads and passes the same 8 tests, but a distributable must copy the four libraries next to the shim (the `$ORIGIN` entry then suffices); the build-tree path is not relocatable. | shared-variant output |
| F5 | **Two statically linked ggml forks coexist in one JVM here.** Both shims export default-visibility `ggml_*` (800 shared names), yet the dynamic linker bound every ggml/llama/sd reference of each to its own library (0 cross-bindings in 159 logged references). This relies on the JVM loading with `RTLD_LOCAL`; if a loader ever used `RTLD_GLOBAL`, or a future change linked both into one object, the two ggml versions would interpose. Not tested: that case, or any real inference through either library. | `TwoGgmlLibsTest` |
| F6 | The JNI binding is by **exact class name**: the Kotlin side must be `dev.fonebrew.inference.LlamaCppEngine` (and `…inference.image.SdImageEngine`) with the same `external` signatures. The spike carries minimal copies (`src/main/kotlin/…`, labelled); core's real classes were not used. A desktop head that reuses core's `LlamaCppEngine.kt` keeps the package. | source |
| **F7** | **stable-diffusion.cpp's `src/tokenizers/vocab/vocab.cpp` is a heavy translation unit.** It `#include`s ~240 MB of vocabulary headers; compiled alone it took **~104 s and 5.2 GB peak RSS** (measured with `resource.getrusage` on the exact `compile_commands.json` command). An unconstrained `-j4` build in the 16 GB container, with other builds running, had `cc1plus` OOM-killed: `c++: fatal error: Killed signal terminated program cc1plus`. The spike therefore builds the sd target with 2 jobs by default (`-Pfb3.sdJobs=N`). It is a plausible contributor to the runner OOM that keeps `assembleFullDebug` disabled in `ci.yml` (not verified — the Android build was not touched). | build log + measurement |
| F8 | The llama.cpp pin is `5343f4502ab5273d7cef85012af020cad0182376` (commit date 2026-06-06, ggml **0.13.1**). At this pin the top-level CMake also exposes `LLAMA_BUILD_APP`, `LLAMA_BUILD_UI`, `LLAMA_OPENSSL` (all irrelevant when added as a subdirectory with `LLAMA_BUILD_COMMON` off). | `llama.cpp/CMakeLists.txt` |

### The exact patch for F1 (not applied; `proposed/llama_jni-android-guard.patch`)

```diff
--- a/core-engine/src/main/cpp/llama_jni.cpp
+++ b/core-engine/src/main/cpp/llama_jni.cpp
@@ -13,7 +13,11 @@
 // on-device benchmarking (§10.6).
 
 #include <jni.h>
+#ifdef __ANDROID__
 #include <android/log.h>
+#else
+#include <cstdarg>  // desktop JVM builds: no NDK liblog
+#endif
 #include <atomic>
 #include <cmath>
 #include <mutex>
@@ -26,8 +30,21 @@
 #include "llama.h"
 
 #define LOG_TAG "aarso-llama"
+#ifdef __ANDROID__
 #define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
 #define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
+#else
+static void fonebrew_log(char level, const char* fmt, ...) {
+    std::fprintf(stderr, "%c/" LOG_TAG ": ", level);
+    va_list ap;
+    va_start(ap, fmt);
+    std::vfprintf(stderr, fmt, ap);
+    va_end(ap);
+    std::fputc('\n', stderr);
+}
+#define LOGE(...) fonebrew_log('E', __VA_ARGS__)
+#define LOGI(...) fonebrew_log('I', __VA_ARGS__)
+#endif
 
 namespace {
```

It is behaviour-neutral on Android (`__ANDROID__` is defined by the NDK toolchains). `sd_jni.cpp` needs the analogous
two-line guard (`#include <android/log.h>` and its `SDLOG` macro). Applying either is an Android-side PR (one concern,
Android gate green) and is **not** done here.

## What this does not show

- **No model was loaded, no token generated, nothing about speed** (CPU baseline or otherwise) — loading and
  linking are the question. No GPU backend (Vulkan/CUDA) was attempted.
- **Linux x86_64/glibc only.** Not arm64 Linux, musl, macOS, Windows, or Ubuntu Touch's armhf/arm64 libc.
- The `AVX2` requirement (F3) means the artefact has not been shown to run on pre-AVX2 CPUs.
- It does **not** show that the *app* can do local inference on a desktop — only that the native layer links and runs
  its failure paths in a JVM.

## CI status

`.github/workflows/desktop-linux.yml` runs this spike in the job `s-fb3-native-shim` (part A static + part B). **That job has
never run on a hosted runner**; its memory/time behaviour there is unmeasured. It is time-boxed (`timeout-minutes: 60`) and
carries no `continue-on-error`: if it OOMs or times out on a hosted runner, the honest outcome is a red job to be
removed or re-sized, not a masked one. The shared variant is local-evidence only (not in CI).

## Layout

`CMakeLists.txt` (part A; static or shared) · `sd/CMakeLists.txt` (part B) · `compat/android/log.h` (the spike-only
stub for F1) · `proposed/llama_jni-android-guard.patch` · `build.gradle.kts` (Exec tasks around cmake; `test`,
`testTwoLibs`) · `src/main/kotlin` (the JNI binding classes and the part-B driver `TwoLibsMain`) · `src/test/kotlin`
(`LlamaShimLoadTest`, `TwoGgmlLibsTest`). Everything generated is under `build/` (git-ignored).
