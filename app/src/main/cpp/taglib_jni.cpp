// JNI bridge to TagLib. Kotlin side: com.tune.music.data.TagLib.
#include <jni.h>
#include <string>
#include <vector>

#include <fileref.h>
#include <tpropertymap.h>
#include <tvariant.h>

namespace {

// Tag fields exchanged with Kotlin, in this order.
const char *const kKeys[] = {
    "TITLE", "ARTIST", "ALBUM", "ALBUMARTIST", "GENRE", "DATE", "TRACKNUMBER", "DISCNUMBER",
};
constexpr int kKeyCount = sizeof(kKeys) / sizeof(kKeys[0]);

std::string toUtf8(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

TagLib::String toTag(const std::string &s) {
    return TagLib::String(s, TagLib::String::UTF8);
}

jstring toJava(JNIEnv *env, const TagLib::String &s) {
    return env->NewStringUTF(s.to8Bit(true).c_str());
}

} // namespace

extern "C" {

/** Returns the fields in kKeys order (empty string when missing), or null if unreadable. */
JNIEXPORT jobjectArray JNICALL
Java_com_tune_music_data_TagLib_nativeRead(JNIEnv *env, jclass, jstring path) {
    TagLib::FileRef f(toUtf8(env, path).c_str(), false);
    if (f.isNull()) return nullptr;
    TagLib::PropertyMap props = f.properties();
    jobjectArray out = env->NewObjectArray(kKeyCount, env->FindClass("java/lang/String"), nullptr);
    for (int i = 0; i < kKeyCount; ++i) {
        TagLib::StringList v = props[kKeys[i]];
        jstring js = toJava(env, v.isEmpty() ? TagLib::String() : v.front());
        env->SetObjectArrayElement(out, i, js);
        env->DeleteLocalRef(js);
    }
    return out;
}

/**
 * Applies changes and saves. values[i] == null leaves kKeys[i] unchanged, an
 * empty string removes it. art != null replaces the pictures with one front cover.
 */
JNIEXPORT jboolean JNICALL
Java_com_tune_music_data_TagLib_nativeWrite(JNIEnv *env, jclass, jstring path, jobjectArray values,
                                           jbyteArray art, jstring artMime) {
    TagLib::FileRef f(toUtf8(env, path).c_str(), false);
    if (f.isNull()) return JNI_FALSE;

    TagLib::PropertyMap props = f.properties();
    for (int i = 0; i < kKeyCount && i < env->GetArrayLength(values); ++i) {
        auto v = static_cast<jstring>(env->GetObjectArrayElement(values, i));
        if (!v) continue;
        std::string s = toUtf8(env, v);
        env->DeleteLocalRef(v);
        if (s.empty()) props.erase(kKeys[i]);
        else props.replace(kKeys[i], TagLib::StringList(toTag(s)));
    }
    f.setProperties(props);

    if (art) {
        jsize n = env->GetArrayLength(art);
        std::vector<char> buf(static_cast<size_t>(n));
        env->GetByteArrayRegion(art, 0, n, reinterpret_cast<jbyte *>(buf.data()));
        TagLib::VariantMap pic;
        pic["data"] = TagLib::ByteVector(buf.data(), static_cast<unsigned int>(n));
        pic["mimeType"] = toTag(artMime ? toUtf8(env, artMime) : "image/jpeg");
        pic["pictureType"] = TagLib::String("Front Cover");
        pic["description"] = TagLib::String();
        f.setComplexProperties("PICTURE", {pic});
    }
    return f.save() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
