// JNI bridge to Chromaprint. Kotlin side: com.tune.music.data.Chromaprint.
#include <jni.h>
#include <chromaprint.h>

namespace {

ChromaprintContext *ctx(jlong handle) {
    return reinterpret_cast<ChromaprintContext *>(handle);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_tune_music_data_Chromaprint_nativeStart(JNIEnv *, jclass, jint sampleRate, jint channels) {
    ChromaprintContext *c = chromaprint_new(CHROMAPRINT_ALGORITHM_DEFAULT);
    if (!c) return 0;
    if (!chromaprint_start(c, sampleRate, channels)) {
        chromaprint_free(c);
        return 0;
    }
    return reinterpret_cast<jlong>(c);
}

JNIEXPORT jboolean JNICALL
Java_com_tune_music_data_Chromaprint_nativeFeed(JNIEnv *env, jclass, jlong handle, jshortArray samples, jint count) {
    jshort *data = env->GetShortArrayElements(samples, nullptr);
    if (!data) return JNI_FALSE;
    int ok = chromaprint_feed(ctx(handle), reinterpret_cast<const int16_t *>(data), count);
    env->ReleaseShortArrayElements(samples, data, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/** Finishes and returns the compressed, base64 fingerprint AcoustID expects (or null). */
JNIEXPORT jstring JNICALL
Java_com_tune_music_data_Chromaprint_nativeFinish(JNIEnv *env, jclass, jlong handle) {
    if (!chromaprint_finish(ctx(handle))) return nullptr;
    char *fp = nullptr;
    if (!chromaprint_get_fingerprint(ctx(handle), &fp) || !fp) return nullptr;
    jstring out = env->NewStringUTF(fp);
    chromaprint_dealloc(fp);
    return out;
}

JNIEXPORT void JNICALL
Java_com_tune_music_data_Chromaprint_nativeFree(JNIEnv *, jclass, jlong handle) {
    if (handle) chromaprint_free(ctx(handle));
}

} // extern "C"
