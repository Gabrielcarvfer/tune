// JNI bridge to Chromaprint. Kotlin side: com.music.tune.data.Chromaprint.
#include <jni.h>
#include <chromaprint.h>

namespace {

ChromaprintContext *ctx(jlong handle) {
    return reinterpret_cast<ChromaprintContext *>(handle);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_music_tune_data_Chromaprint_nativeStart(JNIEnv *, jclass, jint sampleRate, jint channels) {
    ChromaprintContext *c = chromaprint_new(CHROMAPRINT_ALGORITHM_DEFAULT);
    if (!c) return 0;
    if (!chromaprint_start(c, sampleRate, channels)) {
        chromaprint_free(c);
        return 0;
    }
    return reinterpret_cast<jlong>(c);
}

JNIEXPORT jboolean JNICALL
Java_com_music_tune_data_Chromaprint_nativeFeed(JNIEnv *env, jclass, jlong handle, jshortArray samples, jint count) {
    jshort *data = env->GetShortArrayElements(samples, nullptr);
    if (!data) return JNI_FALSE;
    int ok = chromaprint_feed(ctx(handle), reinterpret_cast<const int16_t *>(data), count);
    env->ReleaseShortArrayElements(samples, data, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/** Finishes and returns the compressed, base64 fingerprint AcoustID expects (or null). */
JNIEXPORT jstring JNICALL
Java_com_music_tune_data_Chromaprint_nativeFinish(JNIEnv *env, jclass, jlong handle) {
    if (!chromaprint_finish(ctx(handle))) return nullptr;
    char *fp = nullptr;
    if (!chromaprint_get_fingerprint(ctx(handle), &fp) || !fp) return nullptr;
    jstring out = env->NewStringUTF(fp);
    chromaprint_dealloc(fp);
    return out;
}

/** Decodes a compressed, base64 fingerprint back to its raw 32-bit items (or null). */
JNIEXPORT jintArray JNICALL
Java_com_music_tune_data_Chromaprint_nativeDecode(JNIEnv *env, jclass, jstring encoded) {
    const char *chars = env->GetStringUTFChars(encoded, nullptr);
    if (!chars) return nullptr;
    uint32_t *raw = nullptr;
    int size = 0, algorithm = 0;
    int ok = chromaprint_decode_fingerprint(chars, env->GetStringUTFLength(encoded), &raw, &size, &algorithm, 1);
    env->ReleaseStringUTFChars(encoded, chars);
    if (!ok || !raw) return nullptr;
    jintArray out = env->NewIntArray(size);
    if (out) env->SetIntArrayRegion(out, 0, size, reinterpret_cast<const jint *>(raw));
    chromaprint_dealloc(raw);
    return out;
}

JNIEXPORT void JNICALL
Java_com_music_tune_data_Chromaprint_nativeFree(JNIEnv *, jclass, jlong handle) {
    if (handle) chromaprint_free(ctx(handle));
}

} // extern "C"
