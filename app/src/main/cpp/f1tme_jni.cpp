#include <jni.h>
#include <android/log.h>
#ifdef F1TME_WITH_GPAC
#include <gpac/filters.h>
#endif
namespace {
bool gpac_available() {
#ifdef F1TME_WITH_GPAC
    static bool initialized = false;
    if (!initialized) {
        if (gf_sys_init(GF_MemTrackerNone, nullptr) < 0) return false;
        initialized = true;
    }
    return true;
#else
    return false;
#endif
}
}
extern "C" JNIEXPORT jboolean JNICALL
Java_app_f1multiview_media_GpacNativeTmeMerger_nativeIsAvailable(JNIEnv*, jobject) {
#ifdef F1TME_WITH_GPAC
    if (!gpac_available()) return JNI_FALSE;
    GF_FilterSession *session = gf_fs_new(0, GF_FS_SCHEDULER_DIRECT,
        GF_FS_FLAG_NON_BLOCKING | GF_FS_FLAG_NO_PROBE, nullptr);
    if (!session) return JNI_FALSE;
    const Bool hasHevcMerge = gf_fs_filter_exists(session, "hevcmerge");
    gf_fs_del(session);
    return hasHevcMerge ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}
extern "C" JNIEXPORT void JNICALL
Java_app_f1multiview_media_GpacNativeTmeMerger_nativeConfigure(JNIEnv*, jobject, jobjectArray, jint, jint) {
    if (!gpac_available()) {
        __android_log_print(ANDROID_LOG_ERROR, "F1TME", "GPAC native merger is not linked");
        return;
    }
    __android_log_print(ANDROID_LOG_INFO, "F1TME", "GPAC linked; hevcmerge is available");
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_app_f1multiview_media_GpacNativeTmeMerger_nativePush(JNIEnv* env, jobject, jlong, jlong, jlong, jobjectArray, jobjectArray) {
    if (!gpac_available()) {
        jclass cls = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(cls, "GPAC native TME merger is not linked");
        return nullptr;
    }
    jclass cls = env->FindClass("app/f1multiview/media/TmeMergedAccessUnit");
    return env->NewObjectArray(0, cls, nullptr);
}
extern "C" JNIEXPORT void JNICALL
Java_app_f1multiview_media_GpacNativeTmeMerger_nativeUpdateSelection(JNIEnv*, jobject, jobjectArray) {
    if (!gpac_available()) return;
}
extern "C" JNIEXPORT void JNICALL
Java_app_f1multiview_media_GpacNativeTmeMerger_nativeRelease(JNIEnv*, jobject) {}
