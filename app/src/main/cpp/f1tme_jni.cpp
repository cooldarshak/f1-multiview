#include <jni.h>
#include <android/log.h>
#include <deque>
#include <mutex>
#include <string>
#include <vector>
#include <cstring>

#ifdef F1TME_WITH_GPAC
#include <gpac/filters.h>
#include <gpac/constants.h>
#include <gpac/tools.h>
#endif

namespace {

#ifdef F1TME_WITH_GPAC

struct OutputSample {
    std::vector<uint8_t> data;
    uint64_t pts = 0;
    uint64_t dts = 0;
    uint32_t duration = 0;
    uint32_t timescale = 1000000;
    bool key = false;
    std::vector<uint8_t> decoderConfig;
};

struct SinkCtx {
    GF_FilterPid *pid = nullptr;
    std::deque<OutputSample> samples;
    std::vector<uint8_t> decoderConfig;
    std::mutex mutex;
};

struct SourceCtx {
    GF_FilterPid *pid = nullptr;
};

static GF_FilterRegister SourceRegister;
static GF_FilterRegister SinkRegister;

static GF_Err source_initialize(GF_Filter *filter) {
    SourceCtx *ctx = static_cast<SourceCtx *>(gf_filter_get_udta(filter));
    ctx->pid = gf_filter_pid_new(filter);
    if (!ctx->pid) return GF_OUT_OF_MEM;
    return GF_OK;
}

static const GF_FilterCapability SourceCaps[] = {
    CAP_UINT(GF_CAPS_OUTPUT, GF_PROP_PID_STREAM_TYPE, GF_STREAM_VISUAL),
    CAP_UINT(GF_CAPS_OUTPUT, GF_PROP_PID_CODECID, GF_CODECID_HEVC),
    {0}
};

static GF_Err sink_configure(GF_Filter *filter, GF_FilterPid *pid, Bool is_remove) {
    SinkCtx *ctx = static_cast<SinkCtx *>(gf_filter_get_udta(filter));
    if (is_remove) {
        if (ctx->pid == pid) ctx->pid = nullptr;
        return GF_OK;
    }
    if (ctx->pid && ctx->pid != pid) return GF_REQUIRES_NEW_INSTANCE;
    ctx->pid = pid;
    const GF_PropertyValue *dsi = gf_filter_pid_get_property(pid, GF_PROP_PID_DECODER_CONFIG);
    if (dsi && dsi->type == GF_PROP_DATA && dsi->value.data.ptr && dsi->value.data.size) {
        ctx->decoderConfig.assign(
            dsi->value.data.ptr,
            dsi->value.data.ptr + dsi->value.data.size
        );
    }
    return GF_OK;
}

static GF_Err sink_process(GF_Filter *filter) {
    SinkCtx *ctx = static_cast<SinkCtx *>(gf_filter_get_udta(filter));
    if (!ctx->pid) return GF_EOS;

    GF_FilterPacket *pck = gf_filter_pid_get_packet(ctx->pid);
    if (!pck) {
        if (gf_filter_pid_is_eos(ctx->pid)) return GF_EOS;
        return GF_OK;
    }

    u32 size = 0;
    const u8 *data = gf_filter_pck_get_data(pck, &size);
    if (data && size) {
        OutputSample out;
        out.data.assign(data, data + size);
        out.pts = gf_filter_pck_get_cts(pck);
        out.dts = gf_filter_pck_get_dts(pck);
        out.duration = gf_filter_pck_get_duration(pck);
        out.timescale = gf_filter_pck_get_timescale(pck);
        out.key = gf_filter_pck_get_sap(pck) != GF_FILTER_SAP_NONE;
        out.decoderConfig = ctx->decoderConfig;
        std::lock_guard<std::mutex> lock(ctx->mutex);
        ctx->samples.emplace_back(std::move(out));
    }
    gf_filter_pid_drop_packet(ctx->pid);
    return GF_OK;
}

static const GF_FilterCapability SinkCaps[] = {
    CAP_UINT(GF_CAPS_INPUT, GF_PROP_PID_STREAM_TYPE, GF_STREAM_VISUAL),
    CAP_UINT(GF_CAPS_INPUT, GF_PROP_PID_CODECID, GF_CODECID_HEVC),
    {0}
};

static const GF_FilterRegister *source_register(GF_FilterSession *) {
    SourceRegister.name = "f1tmesrc";
    SourceRegister.private_size = sizeof(SourceCtx);
    SourceRegister.flags = GF_FS_REG_ACT_AS_SOURCE | GF_FS_REG_EXPLICIT_ONLY;
    SourceRegister.initialize = source_initialize;
    SourceRegister.caps = SourceCaps;
    SourceRegister.nb_caps = sizeof(SourceCaps) / sizeof(SourceCaps[0]) - 1;
    return &SourceRegister;
}

static const GF_FilterRegister *sink_register(GF_FilterSession *) {
    SinkRegister.name = "f1tmesink";
    SinkRegister.private_size = sizeof(SinkCtx);
    SinkRegister.flags = GF_FS_REG_EXPLICIT_ONLY;
    SinkRegister.configure_pid = sink_configure;
    SinkRegister.process = sink_process;
    SinkRegister.caps = SinkCaps;
    SinkRegister.nb_caps = sizeof(SinkCaps) / sizeof(SinkCaps[0]) - 1;
    return &SinkRegister;
}

struct Graph {
    GF_FilterSession *session = nullptr;
    GF_Filter *merger = nullptr;
    GF_Filter *sink = nullptr;
    std::vector<GF_Filter *> sources;
    std::vector<GF_FilterPid *> sourcePids;
    SinkCtx *sinkCtx = nullptr;
    bool configured = false;
};

static bool ensure_gpac() {
    static bool initialized = false;
    if (!initialized) {
        if (gf_sys_init(GF_MemTrackerNone, nullptr) < 0) return false;
        initialized = true;
    }
    return true;
}

static void set_source_props(
    GF_FilterPid *pid,
    uint32_t id,
    int32_t x,
    int32_t y,
    uint32_t width,
    uint32_t height,
    const uint8_t *dsi,
    uint32_t dsiSize
) {
    GF_PropertyValue streamType = PROP_UINT(GF_STREAM_VISUAL);
    GF_PropertyValue codecId = PROP_UINT(GF_CODECID_HEVC);
    // hevcmerge groups independent HEVC PIDs by the mergeable-set property.
    // Without a shared non-zero value, the graph has four HEVC inputs but no
    // explicit instruction that they form one compressed-domain tile set.
    GF_PropertyValue mergeable = PROP_UINT(1);
    GF_PropertyValue timescale = PROP_UINT(1000000);
    GF_PropertyValue pidId = PROP_UINT(id);
    GF_PropertyValue pidWidth = PROP_UINT(width);
    GF_PropertyValue pidHeight = PROP_UINT(height);
    GF_PropertyValue cropPos = PROP_VEC2I_INT(x, y);

    gf_filter_pid_set_property(pid, GF_PROP_PID_STREAM_TYPE, &streamType);
    gf_filter_pid_set_property(pid, GF_PROP_PID_CODECID, &codecId);
    gf_filter_pid_set_property(pid, GF_PROP_PID_CODEC_MERGEABLE, &mergeable);
    gf_filter_pid_set_property(pid, GF_PROP_PID_TIMESCALE, &timescale);
    gf_filter_pid_set_property(pid, GF_PROP_PID_ID, &pidId);
    gf_filter_pid_set_property(pid, GF_PROP_PID_WIDTH, &pidWidth);
    gf_filter_pid_set_property(pid, GF_PROP_PID_HEIGHT, &pidHeight);
    gf_filter_pid_set_property(pid, GF_PROP_PID_CROP_POS, &cropPos);

    if (dsi && dsiSize) {
        GF_PropertyValue decoderConfig = PROP_DATA((u8 *)dsi, dsiSize);
        gf_filter_pid_set_property(pid, GF_PROP_PID_DECODER_CONFIG, &decoderConfig);
    }
}

static bool add_source(Graph &g, uint32_t id, int32_t x, int32_t y, uint32_t w, uint32_t h,
                       const uint8_t *dsi, uint32_t dsiSize) {
    GF_Err err = GF_OK;
    GF_Filter *src = gf_fs_load_filter(g.session, "f1tmesrc", &err);
    if (!src || err) return false;
    SourceCtx *ctx = static_cast<SourceCtx *>(gf_filter_get_udta(src));
    if (!ctx || !ctx->pid) return false;
    set_source_props(ctx->pid, id, x, y, w, h, dsi, dsiSize);
    if (gf_filter_set_source(g.merger, src, nullptr) != GF_OK) return false;
    g.sources.push_back(src);
    g.sourcePids.push_back(ctx->pid);
    return true;
}

static bool configure_graph(
    Graph &g,
    JNIEnv *env,
    jobjectArray configs,
    jintArray xs,
    jintArray ys,
    jintArray widths,
    jintArray heights,
    jint outputWidth,
    jint outputHeight
) {
    const jsize count = env->GetArrayLength(configs);
    if (count < 2) return false;

    g.session = gf_fs_new(0, GF_FS_SCHEDULER_DIRECT, GF_FS_FLAG_NON_BLOCKING, nullptr);
    if (!g.session) return false;

    gf_fs_add_filter_register(g.session, source_register(g.session));
    gf_fs_add_filter_register(g.session, sink_register(g.session));

    GF_Err err = GF_OK;
    g.merger = gf_fs_load_filter(g.session, "hevcmerge:mrows=true:strict=true", &err);
    if (!g.merger || err) return false;

    g.sink = gf_fs_load_filter(g.session, "f1tmesink", &err);
    if (!g.sink || err) return false;

    if (gf_filter_set_source(g.sink, g.merger, nullptr) != GF_OK) return false;

    auto *x = env->GetIntArrayElements(xs, nullptr);
    auto *y = env->GetIntArrayElements(ys, nullptr);
    auto *w = env->GetIntArrayElements(widths, nullptr);
    auto *h = env->GetIntArrayElements(heights, nullptr);

    for (jsize i = 0; i < count; ++i) {
        jbyteArray cfg = static_cast<jbyteArray>(env->GetObjectArrayElement(configs, i));
        const jsize n = cfg ? env->GetArrayLength(cfg) : 0;
        std::vector<uint8_t> bytes(static_cast<size_t>(n));
        if (cfg && n) env->GetByteArrayRegion(cfg, 0, n, reinterpret_cast<jbyte *>(bytes.data()));
        if (cfg) env->DeleteLocalRef(cfg);
        if (!add_source(g, static_cast<uint32_t>(i + 1), x[i], y[i], w[i], h[i],
                        bytes.empty() ? nullptr : bytes.data(), static_cast<uint32_t>(bytes.size()))) {
            env->ReleaseIntArrayElements(xs, x, JNI_ABORT);
            env->ReleaseIntArrayElements(ys, y, JNI_ABORT);
            env->ReleaseIntArrayElements(widths, w, JNI_ABORT);
            env->ReleaseIntArrayElements(heights, h, JNI_ABORT);
            return false;
        }
    }

    env->ReleaseIntArrayElements(xs, x, JNI_ABORT);
    env->ReleaseIntArrayElements(ys, y, JNI_ABORT);
    env->ReleaseIntArrayElements(widths, w, JNI_ABORT);
    env->ReleaseIntArrayElements(heights, h, JNI_ABORT);

    g.sinkCtx = static_cast<SinkCtx *>(gf_filter_get_udta(g.sink));
    g.configured = true;
    (void) outputWidth;
    (void) outputHeight;
    return true;
}

static void destroy_graph(Graph &g) {
    if (g.session) gf_fs_del(g.session);
    g = Graph{};
}

static jobjectArray drain_samples(JNIEnv *env, Graph &g) {
    std::deque<OutputSample> samples;
    std::vector<uint8_t> config;
    {
        std::lock_guard<std::mutex> lock(g.sinkCtx->mutex);
        samples.swap(g.sinkCtx->samples);
        config = g.sinkCtx->decoderConfig;
    }

    jclass cls = env->FindClass("app/f1multiview/media/TmeMergedAccessUnit");
    if (!cls) return nullptr;
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJJZ[B[B)V");
    if (!ctor) return nullptr;

    jobjectArray result = env->NewObjectArray(static_cast<jsize>(samples.size()), cls, nullptr);
    jsize index = 0;
    for (auto &sample : samples) {
        const long long scale = sample.timescale ? sample.timescale : 1000000LL;
        const jlong ptsUs = static_cast<jlong>((sample.pts * 1000000ULL) / scale);
        const jlong dtsUs = static_cast<jlong>((sample.dts * 1000000ULL) / scale);
        const jlong durUs = static_cast<jlong>((sample.duration * 1000000ULL) / scale);
        jbyteArray payload = env->NewByteArray(static_cast<jsize>(sample.data.size()));
        env->SetByteArrayRegion(payload, 0, static_cast<jsize>(sample.data.size()),
                                reinterpret_cast<const jbyte *>(sample.data.data()));
        jbyteArray dsi = nullptr;
        if (!sample.decoderConfig.empty()) {
            dsi = env->NewByteArray(static_cast<jsize>(sample.decoderConfig.size()));
            env->SetByteArrayRegion(dsi, 0, static_cast<jsize>(sample.decoderConfig.size()),
                                    reinterpret_cast<const jbyte *>(sample.decoderConfig.data()));
        }
        jobject obj = env->NewObject(
            cls, ctor, ptsUs, dtsUs, durUs,
            sample.key ? JNI_TRUE : JNI_FALSE, dsi, payload
        );
        env->SetObjectArrayElement(result, index++, obj);
        env->DeleteLocalRef(payload);
        if (dsi) env->DeleteLocalRef(dsi);
        env->DeleteLocalRef(obj);
    }
    env->DeleteLocalRef(cls);
    return result;
}

static Graph g_graph;

#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_f1multiview_media_GpacNativeTmeMerger_nativeIsAvailable(JNIEnv *, jobject) {
#ifdef F1TME_WITH_GPAC
    if (!ensure_gpac()) return JNI_FALSE;
    GF_FilterSession *session = gf_fs_new(0, GF_FS_SCHEDULER_DIRECT, GF_FS_FLAG_NON_BLOCKING, nullptr);
    if (!session) return JNI_FALSE;
    const Bool exists = gf_fs_filter_exists(session, "hevcmerge");
    gf_fs_del(session);
    return exists ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_app_f1multiview_media_GpacNativeTmeMerger_nativeConfigure(
    JNIEnv *env, jobject,
    jobjectArray, jint outputWidth, jint outputHeight,
    jobjectArray configs, jintArray xs, jintArray ys, jintArray widths, jintArray heights
) {
#ifdef F1TME_WITH_GPAC
    if (!ensure_gpac()) {
        jclass cls = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(cls, "GPAC could not initialize");
        return;
    }
    destroy_graph(g_graph);
    if (!configure_graph(g_graph, env, configs, xs, ys, widths, heights, outputWidth, outputHeight)) {
        destroy_graph(g_graph);
        jclass cls = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(cls, "Unable to construct GPAC HEVC tile merger graph");
    }
#else
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(cls, "F1TME was built without GPAC");
#endif
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_app_f1multiview_media_GpacNativeTmeMerger_nativePush(
    JNIEnv *env, jobject, jlong sequence, jlong epochStartUs, jlong durationUs,
    jobjectArray feedIds, jobjectArray payloads, jbooleanArray keyFrames
) {
#ifdef F1TME_WITH_GPAC
    (void) sequence;
    (void) feedIds;
    if (!g_graph.configured || !g_graph.session) {
        jclass cls = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(cls, "Native TME graph is not configured");
        return nullptr;
    }

    const jsize count = env->GetArrayLength(payloads);
    const jsize keyCount = env->GetArrayLength(keyFrames);
    if (keyCount != count) {
        jclass cls = env->FindClass("java/lang/IllegalArgumentException");
        env->ThrowNew(cls, "TME key-frame count does not match tile count");
        return nullptr;
    }
    std::vector<jboolean> keyValues(static_cast<size_t>(count));
    env->GetBooleanArrayRegion(keyFrames, 0, count, keyValues.data());
    if (count != static_cast<jsize>(g_graph.sourcePids.size())) {
        jclass cls = env->FindClass("java/lang/IllegalArgumentException");
        env->ThrowNew(cls, "TME push tile count does not match configured tile count");
        return nullptr;
    }

    for (jsize i = 0; i < count; ++i) {
        auto *bytes = static_cast<jbyteArray>(env->GetObjectArrayElement(payloads, i));
        const jsize n = bytes ? env->GetArrayLength(bytes) : 0;
        u8 *dst = nullptr;
        GF_FilterPacket *pck = gf_filter_pck_new_alloc(g_graph.sourcePids[i], static_cast<u32>(n), &dst);
        if (!pck || (n && !dst)) {
            if (bytes) env->DeleteLocalRef(bytes);
            jclass cls = env->FindClass("java/lang/OutOfMemoryError");
            env->ThrowNew(cls, "Unable to allocate GPAC tile packet");
            return nullptr;
        }
        if (n) env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(dst));
        if (bytes) env->DeleteLocalRef(bytes);
        // nativePush is called once per aligned media sample. Preserve that
        // sample timestamp so hevcmerge sees synchronized access units rather
        // than assigning every sample the segment's first timestamp.
        gf_filter_pck_set_cts(pck, static_cast<u64>(epochStartUs));
        gf_filter_pck_set_dts(pck, static_cast<u64>(epochStartUs));
        gf_filter_pck_set_duration(pck, static_cast<u32>(durationUs));
        gf_filter_pck_set_framing(pck, GF_TRUE, GF_TRUE);
        if (keyValues[static_cast<size_t>(i)]) {
            gf_filter_pck_set_sap(pck, GF_FILTER_SAP_1);
        }
        gf_filter_pck_send(pck);
    }

    // Direct/non-blocking GPAC sessions may need more than one scheduler pass:
    // input packets must reach hevcmerge before its output reaches the sink.
    // Keep this bounded so malformed input can never hang the playback thread.
    for (int pass = 0; pass < 16; ++pass) {
        GF_Err runErr = gf_fs_run(g_graph.session);
        if (runErr < 0) break;
        bool hasOutput = false;
        {
            std::lock_guard<std::mutex> lock(g_graph.sinkCtx->mutex);
            hasOutput = !g_graph.sinkCtx->samples.empty();
        }
        if (hasOutput) break;
    }
    return drain_samples(env, g_graph);
#else
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(cls, "F1TME was built without GPAC");
    return nullptr;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_app_f1multiview_media_GpacNativeTmeMerger_nativeUpdateSelection(JNIEnv *, jobject, jobjectArray) {
    // Logical feed selection is intentionally compositor-side. The compressed-domain
    // merger keeps the complete tile set alive so changing selection never tears down
    // the physical decoder.
}

extern "C" JNIEXPORT void JNICALL
Java_app_f1multiview_media_GpacNativeTmeMerger_nativeRelease(JNIEnv *, jobject) {
#ifdef F1TME_WITH_GPAC
    destroy_graph(g_graph);
#endif
}
