/* Trivial JNI shared object for the dlopen-from-filesDir check. The probe copies this exact file
 * into filesDir and System.load()s the copy, then calls answer(). Does nothing else. */
#include <jni.h>

JNIEXPORT jint JNICALL Java_dev_fonebrew_probe_DlProbe_answer(JNIEnv *env, jclass c) {
    (void)env; (void)c;
    return 42;
}
