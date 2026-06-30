// JNI bridge between LlamaCppEngine.kt and llama.cpp.
//
// Runs a GGUF model on-device: load, tokenize, and a decode loop that streams
// each token back to Kotlin together with its log-probability and the entropy of
// the model's next-token distribution (the inputs to the §5a confidence colouring
// — the reason llama.cpp was chosen over Ollama, §3).
//
// The decode loop runs synchronously on the calling (Kotlin background) thread,
// so the JNIEnv handed to each native method is valid for the sink callbacks.
//
// CPU-only for the first cut (n_gpu_layers = 0); the handoff says CPU is a strong
// baseline on the target SoC, and the Adreno/Vulkan accel path is decided later by
// on-device benchmarking (§10.6).

#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cmath>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <cstdio>  // rename, remove
#include <sys/stat.h>  // stat

#include "llama.h"

#define LOG_TAG "aarso-llama"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

std::once_flag g_backend_once;
void ensure_backend() { std::call_once(g_backend_once, [] { llama_backend_init(); }); }

struct AarsoCtx {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    std::atomic<bool> stop{false};
};

std::string token_to_text(const llama_vocab* vocab, llama_token tok) {
    char buf[256];
    int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, /*special=*/false);
    if (n < 0) {
        std::string out(static_cast<size_t>(-n), '\0');
        n = llama_token_to_piece(vocab, tok, out.data(), static_cast<int>(out.size()), 0, false);
        if (n < 0) return std::string();
        out.resize(static_cast<size_t>(n));
        return out;
    }
    return std::string(buf, static_cast<size_t>(n));
}

// BPE token pieces are raw byte chunks that can end mid-character (an emoji is
// usually split across tokens). Returns the length of the longest prefix that
// ends on a complete UTF-8 sequence, so the trailing fragment waits for the
// next token. Byte sequences that can never complete are passed through whole —
// the Kotlin decoder replaces them — rather than held back forever.
size_t utf8_complete_prefix(const std::string& s) {
    size_t n = s.size();
    size_t i = n;
    size_t back = 0;
    while (i > 0 && back < 3 && (static_cast<unsigned char>(s[i - 1]) & 0xC0) == 0x80) {
        --i;
        ++back;
    }
    if (i == 0) return n;
    unsigned char lead = static_cast<unsigned char>(s[i - 1]);
    size_t need;
    if (lead < 0x80) need = 1;
    else if ((lead & 0xE0) == 0xC0) need = 2;
    else if ((lead & 0xF0) == 0xE0) need = 3;
    else if ((lead & 0xF8) == 0xF0) need = 4;
    else return n;
    return (n - (i - 1)) < need ? i - 1 : n;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_aarso_inference_LlamaCppEngine_nativeLoadModel(
        JNIEnv* env, jobject, jstring modelPath, jint contextSize) {
    ensure_backend();
    const char* path = env->GetStringUTFChars(modelPath, nullptr);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // CPU-only first cut

    llama_model* model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(modelPath, path);
    if (model == nullptr) {
        LOGE("failed to load model");
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = contextSize > 0 ? static_cast<uint32_t>(contextSize) : 4096;
    cp.n_batch = 512;
    // Use the performance cores; clamp so we don't fight the UI thread.
    unsigned hw = std::thread::hardware_concurrency();
    int threads = hw == 0 ? 4 : static_cast<int>(hw > 6 ? 6 : hw);
    cp.n_threads = threads;
    cp.n_threads_batch = threads;

    llama_context* ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) {
        LOGE("failed to create context");
        llama_model_free(model);
        return 0;
    }

    auto* h = new AarsoCtx{model, ctx};
    LOGI("model loaded, n_ctx=%u", cp.n_ctx);
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL
Java_dev_aarso_inference_LlamaCppEngine_nativeFree(JNIEnv*, jobject, jlong handle) {
    auto* h = reinterpret_cast<AarsoCtx*>(handle);
    if (h == nullptr) return;
    if (h->ctx) llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}

JNIEXPORT jint JNICALL
Java_dev_aarso_inference_LlamaCppEngine_nativeCountTokens(
        JNIEnv* env, jobject, jlong handle, jbyteArray utf8) {
    auto* h = reinterpret_cast<AarsoCtx*>(handle);
    if (h == nullptr) return 0;
    const llama_vocab* vocab = llama_model_get_vocab(h->model);
    // Text crosses as real UTF-8 bytes: jstring would arrive as Modified UTF-8
    // (CESU-8), which miscounts anything outside the BMP.
    jsize len = env->GetArrayLength(utf8);
    std::vector<char> buf(static_cast<size_t>(len));
    if (len > 0) env->GetByteArrayRegion(utf8, 0, len, reinterpret_cast<jbyte*>(buf.data()));
    // First call with no buffer returns the negative of the required count.
    int n = llama_tokenize(vocab, buf.data(), static_cast<int>(len), nullptr, 0, true, true);
    return n < 0 ? -n : n;
}

JNIEXPORT void JNICALL
Java_dev_aarso_inference_LlamaCppEngine_nativeGenerate(
        JNIEnv* env, jobject, jlong handle, jobjectArray roles, jobjectArray contents,
        jfloatArray samplingArgs, jobject sink,
        jstring sessionLoadPath, jstring sessionSavePath) {
    auto* h = reinterpret_cast<AarsoCtx*>(handle);

    jclass sinkClass = env->GetObjectClass(sink);
    // Token text crosses as a byte[] of real UTF-8: NewStringUTF aborts the whole
    // process on anything that isn't Modified UTF-8 — which includes both split
    // multi-byte sequences AND every complete 4-byte character (all emoji).
    jmethodID onToken = env->GetMethodID(sinkClass, "onToken", "([BFF)V");
    jmethodID onDone = env->GetMethodID(sinkClass, "onDone", "()V");
    jmethodID onError = env->GetMethodID(sinkClass, "onError", "(Ljava/lang/String;)V");

    auto fail = [&](const char* msg) {
        jstring jmsg = env->NewStringUTF(msg);
        env->CallVoidMethod(sink, onError, jmsg);
        env->DeleteLocalRef(jmsg);
    };

    if (h == nullptr) { fail("no model loaded"); return; }
    h->stop.store(false);

    const llama_vocab* vocab = llama_model_get_vocab(h->model);

    // Sampling args: [temp, topP, topK, minP, repeatPenalty, maxTokens].
    jfloat* args = env->GetFloatArrayElements(samplingArgs, nullptr);
    float temp = args[0], topP = args[1];
    int topK = static_cast<int>(args[2]);
    float minP = args[3];
    float repeatPenalty = args[4];
    int maxTokens = static_cast<int>(args[5]);
    env->ReleaseFloatArrayElements(samplingArgs, args, 0);

    // Render the prompt with the MODEL'S OWN chat template (not a hardcoded one),
    // so Qwen/Gemma/Llama/etc. each get the right format. Falls back to a plain
    // concatenation if the model has no template.
    int nMsg = env->GetArrayLength(roles);
    std::vector<std::string> roleStore(nMsg), contentStore(nMsg);
    std::vector<llama_chat_message> chat(nMsg);
    for (int i = 0; i < nMsg; ++i) {
        auto r = (jstring) env->GetObjectArrayElement(roles, i);
        auto c = (jbyteArray) env->GetObjectArrayElement(contents, i);
        const char* rc = env->GetStringUTFChars(r, nullptr);
        roleStore[i] = rc;
        env->ReleaseStringUTFChars(r, rc);
        // Message text crosses as real UTF-8 bytes (GetStringUTFChars would hand
        // us CESU-8 for emoji, which the model's tokenizer treats as mojibake).
        jsize clen = env->GetArrayLength(c);
        contentStore[i].resize(static_cast<size_t>(clen));
        if (clen > 0) env->GetByteArrayRegion(c, 0, clen, reinterpret_cast<jbyte*>(&contentStore[i][0]));
        env->DeleteLocalRef(r); env->DeleteLocalRef(c);
        chat[i].role = roleStore[i].c_str();
        chat[i].content = contentStore[i].c_str();
    }
    const char* tmpl = llama_model_chat_template(h->model, nullptr);
    std::string promptStr;
    if (tmpl != nullptr && nMsg > 0) {
        int need = llama_chat_apply_template(tmpl, chat.data(), nMsg, true, nullptr, 0);
        if (need > 0) {
            std::vector<char> buf(need);
            int wrote = llama_chat_apply_template(tmpl, chat.data(), nMsg, true, buf.data(), need);
            if (wrote > 0) promptStr.assign(buf.data(), wrote);
        }
    }
    if (promptStr.empty()) { // fallback: plain role: content
        for (int i = 0; i < nMsg; ++i) promptStr += roleStore[i] + ": " + contentStore[i] + "\n";
        promptStr += "assistant: ";
    }

    // Tokenize the rendered prompt.
    int need = llama_tokenize(vocab, promptStr.c_str(), (int) promptStr.size(), nullptr, 0, true, true);
    std::vector<llama_token> tokens(need < 0 ? -need : need);
    llama_tokenize(vocab, promptStr.c_str(), (int) promptStr.size(), tokens.data(), (int) tokens.size(), true, true);

    // Sampler chain: penalties first (curb the looping), then top_k/top_p/min_p/temp/dist.
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (repeatPenalty > 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(256, repeatPenalty, 0.0f, 0.0f));
    }
    if (topK > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
    if (topP < 1.0f) llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    if (minP > 0.0f) llama_sampler_chain_add(smpl, llama_sampler_init_min_p(minP, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    const int n_vocab = llama_vocab_n_tokens(vocab);

    // KV-cache snapshots (§8.3): if a saved session is an exact token-prefix of
    // this prompt, restore it and only decode the new suffix — skipping reprocess
    // of the whole prefix. Otherwise start from a cleared cache and full-prefill.
    // (Without this, reusing the context across turns would also wrongly grow KV.)
    size_t n_prefilled = 0;
    const char* loadPath = sessionLoadPath ? env->GetStringUTFChars(sessionLoadPath, nullptr) : nullptr;
    bool resumed = false;
    if (loadPath && loadPath[0] != '\0') {
        // Sanity-check file size before handing it to llama.cpp: a valid KV
        // snapshot is always at least a few KB (header + token list). A 0-byte
        // or very small file means the previous save was interrupted — skip it
        // and delete so we don't trip over it again.
        struct stat kv_st{};
        bool skip = (stat(loadPath, &kv_st) != 0) || (kv_st.st_size < 1024);
        if (skip) {
            if (kv_st.st_size >= 0) std::remove(loadPath);
        } else {
            std::vector<llama_token> cached(llama_n_ctx(h->ctx));
            size_t n_cached = 0;
            if (llama_state_load_file(h->ctx, loadPath, cached.data(), cached.size(), &n_cached)) {
                bool isPrefix = n_cached <= tokens.size();
                for (size_t k = 0; isPrefix && k < n_cached; ++k) {
                    if (cached[k] != tokens[k]) isPrefix = false;
                }
                if (isPrefix && n_cached < tokens.size()) {
                    n_prefilled = n_cached;
                    resumed = true;
                }
            } else {
                // Corrupt/incompatible session file: delete it so the next run
                // does a clean full-prefill instead of crashing again.
                LOGI("session load failed — deleting corrupt file: %s", loadPath);
                std::remove(loadPath);
            }
        }
    }
    if (loadPath) env->ReleaseStringUTFChars(sessionLoadPath, loadPath);
    if (!resumed) {
        llama_memory_clear(llama_get_memory(h->ctx), true);
    }

    // Prefill the (remaining) prompt.
    {
        llama_batch batch = llama_batch_get_one(tokens.data() + n_prefilled,
                                                static_cast<int>(tokens.size() - n_prefilled));
        if (llama_decode(h->ctx, batch) != 0) {
            llama_sampler_free(smpl);
            fail("decode failed on prompt (context may be too small)");
            return;
        }
    }

    // Track the full token sequence in KV so we can snapshot it afterwards.
    std::vector<llama_token> allTokens = tokens;

    // Streamed text accumulates here until it ends on a UTF-8 boundary; only
    // complete sequences cross to Kotlin (as bytes), so split emoji/CJK render
    // once whole instead of crashing or garbling.
    std::string pending;
    auto emitChunk = [&](const char* data, size_t len, jfloat logprob, jfloat entropy) -> bool {
        jbyteArray arr = env->NewByteArray(static_cast<jsize>(len));
        if (arr == nullptr) { env->ExceptionClear(); return false; }
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(len), reinterpret_cast<const jbyte*>(data));
        env->CallVoidMethod(sink, onToken, arr, logprob, entropy);
        env->DeleteLocalRef(arr);
        if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
        return true;
    };

    for (int produced = 0; produced < maxTokens; ++produced) {
        if (h->stop.load()) break;

        float* logits = llama_get_logits_ith(h->ctx, -1);
        if (logits == nullptr) {
            llama_sampler_free(smpl);
            fail("logits not available after decode");
            return;
        }

        // Entropy of the raw next-token distribution + chosen-token logprob (§5a).
        float maxl = logits[0];
        for (int i = 1; i < n_vocab; ++i) maxl = std::max(maxl, logits[i]);
        double sum = 0.0;
        for (int i = 0; i < n_vocab; ++i) sum += std::exp(static_cast<double>(logits[i]) - maxl);
        double entropy = 0.0;
        for (int i = 0; i < n_vocab; ++i) {
            double p = std::exp(static_cast<double>(logits[i]) - maxl) / sum;
            if (p > 0.0) entropy -= p * std::log(p);
        }

        llama_token id = llama_sampler_sample(smpl, h->ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;

        double p_id = std::exp(static_cast<double>(logits[id]) - maxl) / sum;
        float logprob = static_cast<float>(std::log(p_id > 0.0 ? p_id : 1e-12));

        pending += token_to_text(vocab, id);
        size_t boundary = utf8_complete_prefix(pending);
        if (boundary > 0) {
            bool delivered = emitChunk(pending.data(), boundary, logprob, static_cast<jfloat>(entropy));
            pending.erase(0, boundary);
            if (!delivered) break; // collector gone — stop like a user cancel
        }

        // Feed the sampled token back in.
        allTokens.push_back(id);
        llama_batch next = llama_batch_get_one(&id, 1);
        if (llama_decode(h->ctx, next) != 0) {
            llama_sampler_free(smpl);
            fail("decode failed during generation");
            return;
        }
    }

    // Flush any held-back fragment (e.g. generation stopped mid-character);
    // NaN logprob/entropy marks it as carrying no per-token instrumentation.
    if (!pending.empty()) {
        emitChunk(pending.data(), pending.size(), static_cast<jfloat>(NAN), static_cast<jfloat>(NAN));
    }

    llama_sampler_free(smpl);

    // Snapshot the KV/session so a future branch from here can resume (§8.3).
    // Write to a .tmp file first, then rename — atomically so an interrupted
    // save never leaves a partial file that crashes llama_state_load_file.
    if (sessionSavePath) {
        const char* savePath = env->GetStringUTFChars(sessionSavePath, nullptr);
        if (savePath && savePath[0] != '\0') {
            std::string tmpPath = std::string(savePath) + ".tmp";
            if (llama_state_save_file(h->ctx, tmpPath.c_str(), allTokens.data(), allTokens.size())) {
                if (std::rename(tmpPath.c_str(), savePath) != 0) {
                    std::remove(tmpPath.c_str()); // rename failed; clean up
                }
            } else {
                std::remove(tmpPath.c_str()); // save failed; clean up
            }
        }
        env->ReleaseStringUTFChars(sessionSavePath, savePath);
    }

    env->CallVoidMethod(sink, onDone);
}

JNIEXPORT void JNICALL
Java_dev_aarso_inference_LlamaCppEngine_nativeRequestStop(JNIEnv*, jobject, jlong handle) {
    auto* h = reinterpret_cast<AarsoCtx*>(handle);
    if (h) h->stop.store(true);
}

} // extern "C"
