// JNI bridge for on-device text embeddings (dev.fonebrew.embedding.LlamaEmbedNative).
//
// Own llama context per loaded model, own mutex per context, and a registry of opaque handle ids
// (never raw pointers) so a stale or garbage handle returns a typed failure instead of crashing.
// Every entry point is wrapped in try/catch: no C++ exception crosses JNI, and every error is a
// typed negative code that EmbedNative.kt maps (keep the two files' codes in step).
//
// Pooling comes from the GGUF metadata (pooling_type left UNSPECIFIED); a model that declares
// NONE or RANK is refused at load, never guessed at. Output is L2-normalised.
//
// Deliberately separate from llama_jni.cpp (chat) -- docs/design/semantic-search.md section 3.

#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <sys/stat.h>
#include <unordered_map>
#include <vector>

#include "llama.h"

#define EMB_TAG "aarso-embed"
#define ELOGE(...) __android_log_print(ANDROID_LOG_ERROR, EMB_TAG, __VA_ARGS__)
#define ELOGI(...) __android_log_print(ANDROID_LOG_INFO, EMB_TAG, __VA_ARGS__)

namespace {

// load() failures
constexpr jlong L_BAD_ARGS = -1, L_FILE_MISSING = -2, L_MODEL_LOAD = -3, L_CONTEXT = -4,
                L_POOLING = -5, L_INTERNAL = -6;
// embed() results
constexpr jint E_OK = 0, E_OK_TRUNCATED = 1, E_BAD_ARGS = -1, E_EMPTY = -2, E_TOO_LARGE = -3,
               E_TOKENIZE = -4, E_DECODE = -5, E_NON_FINITE = -6, E_INVALID_HANDLE = -7,
               E_INTERNAL = -8, E_OUTPUT_SIZE = -9;

// Refuse absurd inputs before tokenising (Kotlin chunks far below this; this is defence).
constexpr size_t kMaxTextBytes = 4u * 1024u * 1024u;

std::once_flag g_backend_once;
void ensure_backend() { std::call_once(g_backend_once, [] { llama_backend_init(); }); }

struct EmbedCtx {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    int n_embd = 0;
    int n_ctx = 0;
    int pooling = -1;
    std::mutex mu;  // guards ctx: llama contexts are not thread-safe
    ~EmbedCtx() {
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
    }
};

std::mutex g_reg_mu;
std::unordered_map<jlong, std::shared_ptr<EmbedCtx>> g_registry;
jlong g_next_id = 1;

std::shared_ptr<EmbedCtx> lookup(jlong id) {
    std::lock_guard<std::mutex> lk(g_reg_mu);
    auto it = g_registry.find(id);
    return it == g_registry.end() ? nullptr : it->second;
}

jlong do_load(const std::string& path, jint contextSize, jint threads) {
    struct stat st;
    if (stat(path.c_str(), &st) != 0 || !S_ISREG(st.st_mode)) return L_FILE_MISSING;
    ensure_backend();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;  // CPU-only, like chat
    llama_model* model = llama_model_load_from_file(path.c_str(), mp);
    if (model == nullptr) return L_MODEL_LOAD;

    int n_embd = llama_model_n_embd_out(model);
    if (n_embd <= 0) n_embd = llama_model_n_embd(model);
    int n_train = llama_model_n_ctx_train(model);
    if (n_embd <= 0) { llama_model_free(model); return L_MODEL_LOAD; }

    int n_ctx = contextSize > 0 ? contextSize : 512;
    if (n_train > 0) n_ctx = std::min(n_ctx, n_train);
    int n_thr = std::max(1, std::min<int>(threads > 0 ? threads : 3, 8));

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(n_ctx);
    // Encoder-style embedding models need the whole input in one physical batch.
    cp.n_batch = static_cast<uint32_t>(n_ctx);
    cp.n_ubatch = static_cast<uint32_t>(n_ctx);
    cp.n_seq_max = 1;
    cp.n_threads = n_thr;
    cp.n_threads_batch = n_thr;
    cp.embeddings = true;
    cp.pooling_type = LLAMA_POOLING_TYPE_UNSPECIFIED;  // take it from the GGUF metadata

    llama_context* ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) { llama_model_free(model); return L_CONTEXT; }

    int pooling = static_cast<int>(llama_pooling_type(ctx));
    if (pooling == LLAMA_POOLING_TYPE_NONE || pooling == LLAMA_POOLING_TYPE_RANK ||
        pooling == LLAMA_POOLING_TYPE_UNSPECIFIED) {
        llama_free(ctx);
        llama_model_free(model);
        return L_POOLING;
    }

    auto h = std::make_shared<EmbedCtx>();
    h->model = model;
    h->ctx = ctx;
    h->n_embd = n_embd;
    h->n_ctx = n_ctx;
    h->pooling = pooling;
    std::lock_guard<std::mutex> lk(g_reg_mu);
    jlong id = g_next_id++;
    g_registry[id] = h;
    ELOGI("embedding model loaded: n_embd=%d n_ctx=%d pooling=%d", n_embd, n_ctx, pooling);
    return id;
}

jint do_embed(EmbedCtx& h, const char* text, size_t len, float* out) {
    std::lock_guard<std::mutex> lk(h.mu);
    const llama_vocab* vocab = llama_model_get_vocab(h.model);

    // First call with no buffer returns -(needed count).
    int32_t need = llama_tokenize(vocab, text, static_cast<int32_t>(len), nullptr, 0, true, false);
    if (need == INT32_MIN) return E_TOO_LARGE;
    int32_t n = need < 0 ? -need : need;
    if (n <= 0) return E_TOKENIZE;
    std::vector<llama_token> toks(static_cast<size_t>(n));
    int32_t got = llama_tokenize(vocab, text, static_cast<int32_t>(len), toks.data(), n, true, false);
    if (got < 0) return E_TOKENIZE;
    toks.resize(static_cast<size_t>(got));
    if (toks.empty()) return E_TOKENIZE;

    bool truncated = false;
    if (static_cast<int>(toks.size()) > h.n_ctx) {
        llama_token last = toks.back();
        toks.resize(static_cast<size_t>(h.n_ctx));
        // Keep the closing special token (SEP/EOS) if the model adds one, so the cut text still
        // looks like what the model was trained on.
        if (llama_vocab_get_add_eos(vocab) || llama_vocab_get_add_sep(vocab)) toks.back() = last;
        truncated = true;
    }

    llama_memory_t mem = llama_get_memory(h.ctx);
    if (mem) llama_memory_clear(mem, true);

    llama_batch batch = llama_batch_init(static_cast<int32_t>(toks.size()), 0, 1);
    batch.n_tokens = static_cast<int32_t>(toks.size());
    for (int32_t i = 0; i < batch.n_tokens; ++i) {
        batch.token[i] = toks[static_cast<size_t>(i)];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = 1;  // pooled embeddings need every token's output
    }
    int32_t rc;
    if (llama_model_has_encoder(h.model) && !llama_model_has_decoder(h.model)) {
        rc = llama_encode(h.ctx, batch);
    } else {
        rc = llama_decode(h.ctx, batch);
    }
    llama_batch_free(batch);
    if (rc != 0) return E_DECODE;

    const float* emb = llama_get_embeddings_seq(h.ctx, 0);
    if (emb == nullptr) return E_DECODE;

    double sum = 0.0;
    for (int i = 0; i < h.n_embd; ++i) {
        if (!std::isfinite(emb[i])) return E_NON_FINITE;
        sum += static_cast<double>(emb[i]) * emb[i];
    }
    if (!(sum > 0.0) || !std::isfinite(sum)) return E_NON_FINITE;
    float inv = static_cast<float>(1.0 / std::sqrt(sum));
    for (int i = 0; i < h.n_embd; ++i) out[i] = emb[i] * inv;
    return truncated ? E_OK_TRUNCATED : E_OK;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_fonebrew_embedding_LlamaEmbedNative_nativeLoad(
        JNIEnv* env, jobject, jstring modelPath, jint contextSize, jint threads) {
    try {
        if (modelPath == nullptr) return L_BAD_ARGS;
        const char* p = env->GetStringUTFChars(modelPath, nullptr);
        if (p == nullptr) return L_BAD_ARGS;
        std::string path(p);
        env->ReleaseStringUTFChars(modelPath, p);
        if (path.empty()) return L_BAD_ARGS;
        return do_load(path, contextSize, threads);
    } catch (...) {
        ELOGE("nativeLoad: exception");
        return L_INTERNAL;
    }
}

JNIEXPORT jint JNICALL
Java_dev_fonebrew_embedding_LlamaEmbedNative_nativeEmbed(
        JNIEnv* env, jobject, jlong handle, jbyteArray utf8, jfloatArray out) {
    try {
        if (utf8 == nullptr || out == nullptr) return E_BAD_ARGS;
        auto h = lookup(handle);
        if (!h) return E_INVALID_HANDLE;
        jsize len = env->GetArrayLength(utf8);
        if (len <= 0) return E_EMPTY;
        if (static_cast<size_t>(len) > kMaxTextBytes) return E_TOO_LARGE;
        if (env->GetArrayLength(out) < h->n_embd) return E_OUTPUT_SIZE;

        std::string text(static_cast<size_t>(len), '\0');
        env->GetByteArrayRegion(utf8, 0, len, reinterpret_cast<jbyte*>(text.data()));
        if (env->ExceptionCheck()) { env->ExceptionClear(); return E_BAD_ARGS; }

        std::vector<float> vec(static_cast<size_t>(h->n_embd));
        jint rc = do_embed(*h, text.data(), text.size(), vec.data());
        if (rc == E_OK || rc == E_OK_TRUNCATED) {
            env->SetFloatArrayRegion(out, 0, h->n_embd, vec.data());
            if (env->ExceptionCheck()) { env->ExceptionClear(); return E_INTERNAL; }
        }
        return rc;
    } catch (...) {
        ELOGE("nativeEmbed: exception");
        return E_INTERNAL;
    }
}

JNIEXPORT jint JNICALL
Java_dev_fonebrew_embedding_LlamaEmbedNative_nativeDim(JNIEnv*, jobject, jlong handle) {
    try {
        auto h = lookup(handle);
        return h ? h->n_embd : 0;
    } catch (...) {
        return 0;
    }
}

JNIEXPORT jint JNICALL
Java_dev_fonebrew_embedding_LlamaEmbedNative_nativePooling(JNIEnv*, jobject, jlong handle) {
    try {
        auto h = lookup(handle);
        return h ? h->pooling : -1;
    } catch (...) {
        return -1;
    }
}

JNIEXPORT void JNICALL
Java_dev_fonebrew_embedding_LlamaEmbedNative_nativeUnload(JNIEnv*, jobject, jlong handle) {
    try {
        std::shared_ptr<EmbedCtx> h;
        {
            std::lock_guard<std::mutex> lk(g_reg_mu);
            auto it = g_registry.find(handle);
            if (it == g_registry.end()) return;
            h = it->second;
            g_registry.erase(it);
        }
        // Wait for any in-flight embed on this context, then release (destructor frees llama state).
        std::lock_guard<std::mutex> lk(h->mu);
    } catch (...) {
        ELOGE("nativeUnload: exception");
    }
}

}  // extern "C"
