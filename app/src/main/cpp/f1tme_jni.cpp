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
Java_app_f1multiview_media_GpacNativeTmeMerger_nativeIsAvailable(JNIEnv*, jclass) {
#ifdef F1TME_WITH_GPAC
    if (!gpac_available()) return JNI_FALSE;
    GF_FilterSession *session = gf_fs_new(0, GF_FS_SCHEDULER_DIRECT, GF_FS_FLAG_NON_BLOCKING, nullptr);
    if (!session) return JNI_FALSE;
    const Bool hasTileAgg = gf_fs_filter_exists(session, "tileagg");
    gf_fs_del(session);
    return hasTileAgg ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_app_f1multiview_media_GpacNativeTmeMerger_nativeConfigure(
    JNIEnv*, jobject, jobjectArray, jint, jint) {
    if (!gpac_available()) {
        __android_log_print(ANDROID_LOG_WARN, "F1TME",
                            "GPAC native merger is not linked");
        return;
    }
    // Real filter-session construction is deliberately gated on the pinned
    // Android GPAC build. No fake merge is permitted here.
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_app_f1multiview_media_GpacNativeTmeMerger_nativePush(
    JNIEnv* env, jobject, jlong, jlong, jlong, jobjectArray, jobjectArray) {
    if (!gpac_available()) {
        jclass cls = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(cls, "GPAC native TME merger is not linked");
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_app_f1multiview_media_GpacNativeTmeMerger_nativeUpdateSelection(
    JNIEnv*, jobject, jobjectArray) {
    if (!gpac_available()) {
        return;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_f1multiview_media_GpacNativeTmeMerger_nativeRelease(
    JNIEnv*, jobject) {
}
