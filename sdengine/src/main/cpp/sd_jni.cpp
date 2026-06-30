// JNI bridge for on-device image generation via stable-diffusion.cpp (§4c).
// Built into libaarso_sd.so (its own ggml). Runs txt2img on CPU and writes a PNG.
// Symbol names match dev.aarso.inference.image.SdImageEngine in :app.

#include <jni.h>
#include <android/log.h>
#include <cstdlib>

#include "stable-diffusion.h"

#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

#define SDLOG(...) __android_log_print(ANDROID_LOG_INFO, "aarso-sd", __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_aarso_inference_image_SdImageEngine_nativeSdLoad(
        JNIEnv* env, jobject, jstring modelPath, jint threads) {
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    sd_ctx_params_t p;
    sd_ctx_params_init(&p);
    p.model_path = path;
    p.n_threads = threads > 0 ? threads : 4;
    p.vae_decode_only = true;
    sd_ctx_t* ctx = new_sd_ctx(&p);
    env->ReleaseStringUTFChars(modelPath, path);
    if (ctx == nullptr) {
        SDLOG("new_sd_ctx failed");
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_dev_aarso_inference_image_SdImageEngine_nativeSdFree(JNIEnv*, jobject, jlong handle) {
    if (handle != 0) free_sd_ctx(reinterpret_cast<sd_ctx_t*>(handle));
}

JNIEXPORT jboolean JNICALL
Java_dev_aarso_inference_image_SdImageEngine_nativeSdTxt2Img(
        JNIEnv* env, jobject, jlong handle, jstring prompt, jstring negative,
        jint steps, jint width, jint height, jlong seed, jstring outPath) {
    if (handle == 0) return JNI_FALSE;
    auto* ctx = reinterpret_cast<sd_ctx_t*>(handle);

    const char* cPrompt = env->GetStringUTFChars(prompt, nullptr);
    const char* cNeg = negative ? env->GetStringUTFChars(negative, nullptr) : "";
    const char* cOut = env->GetStringUTFChars(outPath, nullptr);

    sd_img_gen_params_t g;
    sd_img_gen_params_init(&g);
    g.prompt = cPrompt;
    g.negative_prompt = cNeg;
    g.width = width;
    g.height = height;
    g.sample_params.sample_steps = steps > 0 ? steps : 20;
    g.seed = seed;
    g.batch_count = 1;

    sd_image_t* results = generate_image(ctx, &g);

    jboolean ok = JNI_FALSE;
    if (results != nullptr && results[0].data != nullptr) {
        sd_image_t img = results[0];
        if (stbi_write_png(cOut, (int)img.width, (int)img.height, (int)img.channel, img.data, 0) != 0) {
            ok = JNI_TRUE;
        }
        free(results[0].data);
        free(results);
    }

    env->ReleaseStringUTFChars(prompt, cPrompt);
    if (negative) env->ReleaseStringUTFChars(negative, cNeg);
    env->ReleaseStringUTFChars(outPath, cOut);
    return ok;
}

} // extern "C"
