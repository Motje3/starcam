// StarCam JNI bridge — mirrors NativeEngine.kt EXACTLY (package com.wm.starcam.engine,
// object NativeEngine). Kotlin object externals are instance methods: (JNIEnv*, jobject, args...).
// No exception may cross a JNI boundary: every body is wrapped in try/catch(...).

#include <jni.h>
#include <android/bitmap.h>

#include <cstdint>
#include <string>
#include <vector>

#include "common.h"
#include "engine.h"
#include "json_mini.h"
#include "post_options.h"
#include "selftest.h"
#include "sim.h"
#include "studio.h"
#include "timelapse.h"

namespace {

using starcam::EngineConfig;
using starcam::EngineSession;
using starcam::FrameMeta;
using starcam::SensorInfo;
using starcam::SessionMode;

// ---------- small helpers ----------

std::string jstr(JNIEnv* env, jstring s) {
    if (s == nullptr) return std::string();
    const char* c = env->GetStringUTFChars(s, nullptr);
    if (c == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return std::string();
    }
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

jstring toJString(JNIEnv* env, const std::string& s) {
    jstring js = env->NewStringUTF(s.c_str());
    if (js == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        js = env->NewStringUTF("{\"ok\":false,\"error\":\"string conversion failed\"}");
    }
    return js;
}

std::string errJson(const char* msg) {
    jsonmini::Builder b;
    b.add("ok", false);
    b.add("error", msg);
    return b.build();
}

EngineSession* sess(jlong handle) { return reinterpret_cast<EngineSession*>(handle); }

// Validates the direct Bayer buffer; returns pointer or nullptr (with *err set).
const uint16_t* bayerPtr(JNIEnv* env, jobject buf, jint w, jint h, jint rowStrideBytes,
                         int* strideElems, const char** err) {
    *err = nullptr;
    if (buf == nullptr) { *err = "null buffer"; return nullptr; }
    if (w <= 0 || h <= 0) { *err = "bad dimensions"; return nullptr; }
    if ((jlong)rowStrideBytes < (jlong)w * 2 || (rowStrideBytes & 1) != 0) {
        *err = "bad rowStride";
        return nullptr;
    }
    void* p = env->GetDirectBufferAddress(buf);
    if (p == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        *err = "buffer is not direct";
        return nullptr;
    }
    jlong cap = env->GetDirectBufferCapacity(buf);
    jlong need = (jlong)(h - 1) * (jlong)rowStrideBytes + (jlong)w * 2;
    if (cap >= 0 && cap < need) { *err = "buffer too small for w/h/stride"; return nullptr; }
    *strideElems = rowStrideBytes / 2;
    return reinterpret_cast<const uint16_t*>(p);
}

// metaJson (addFrame/addDarkFrame): {index, exposureNs, iso, timestampNs, wbGains:[4], ccm:[9],
// gyroRms, blackLevel:[4] (optional per-frame dynamic black, raster order)}
FrameMeta parseFrameMeta(const jsonmini::Obj& o) {
    FrameMeta m;
    m.index = o.numi("index", 0);
    m.exposureNs = (int64_t)o.num("exposureNs", 0.0);
    m.iso = (int32_t)o.numi("iso", 0);
    m.timestampNs = (int64_t)o.num("timestampNs", 0.0);
    std::vector<double> wb = o.arr("wbGains");
    for (int i = 0; i < 4; ++i) m.wbGain[i] = (i < (int)wb.size()) ? (float)wb[i] : 1.0f;
    std::vector<double> ccm = o.arr("ccm");
    if (ccm.size() >= 9) {
        for (int i = 0; i < 9; ++i) m.ccm[i] = (float)ccm[i];
    }
    m.gyroRms = (float)o.num("gyroRms", 0.0);
    std::vector<double> bl = o.arr("blackLevel");
    if (bl.size() >= 4) {
        m.hasBlackLevel = true;
        for (int i = 0; i < 4; ++i) m.blackLevel[i] = (float)bl[i];
    }
    m.sourcePath = o.str("srawPath", "");
    return m;
}

}  // namespace

extern "C" {

// ---------- createSession ----------

JNIEXPORT jlong JNICALL Java_com_wm_starcam_engine_NativeEngine_createSession(
        JNIEnv* env, jobject /*thiz*/, jstring configJson) {
    try {
        std::string cfgStr = jstr(env, configJson);
        jsonmini::Obj o;
        if (!jsonmini::Obj::parse(cfgStr, &o)) {
            SLOGE("createSession: config JSON parse failed");
            return 0;
        }
        EngineConfig cfg;
        cfg.sessionDir = o.str("sessionDir", "");
        if (cfg.sessionDir.empty()) {
            SLOGE("createSession: missing sessionDir");
            return 0;
        }
        int mode = o.numi("mode", 0);
        if (mode < 0 || mode > 3) mode = 0;
        cfg.mode = static_cast<SessionMode>(mode);
        cfg.sensor.width = o.numi("width", 0);
        cfg.sensor.height = o.numi("height", 0);
        if (cfg.sensor.width <= 0 || cfg.sensor.height <= 0) {
            SLOGE("createSession: bad width/height");
            return 0;
        }
        int cfa = o.numi("cfa", 0);
        if (cfa < 0 || cfa > 3) cfa = 0;
        cfg.sensor.cfa = static_cast<starcam::CFA>(cfa);
        double wl = o.num("whiteLevel", 1023.0);
        if (wl < 1.0) wl = 1023.0;
        cfg.sensor.whiteLevel = (uint32_t)wl;
        std::vector<double> bl = o.arr("blackLevel");
        for (int i = 0; i < 4 && i < (int)bl.size(); ++i) cfg.sensor.blackLevel[i] = (float)bl[i];
        cfg.keepCache = o.boolean("keepCache", false);
        cfg.livePreview = o.boolean("livePreview", true);
        cfg.rotation = starcam::normalize_rotation(o.numi("rotation", 0));

        EngineSession* s = EngineSession::create(cfg);
        if (s == nullptr) SLOGE("createSession: EngineSession::create failed");
        return reinterpret_cast<jlong>(s);
    } catch (...) {
        SLOGE("createSession: native exception");
        return 0;
    }
}

// ---------- addFrame ----------

JNIEXPORT jstring JNICALL Java_com_wm_starcam_engine_NativeEngine_addFrame(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jobject bayer, jint width, jint height,
        jint rowStrideBytes, jstring metaJson) {
    try {
        EngineSession* s = sess(handle);
        if (s == nullptr) return toJString(env, errJson("null handle"));
        int strideElems = 0;
        const char* err = nullptr;
        const uint16_t* px = bayerPtr(env, bayer, width, height, rowStrideBytes, &strideElems, &err);
        if (px == nullptr) return toJString(env, errJson(err ? err : "bad buffer"));
        jsonmini::Obj o;
        if (!jsonmini::Obj::parse(jstr(env, metaJson), &o)) {
            return toJString(env, errJson("meta JSON parse failed"));
        }
        FrameMeta meta = parseFrameMeta(o);
        std::string res = s->addFrame(px, (int)width, (int)height, strideElems, meta);
        return toJString(env, res);
    } catch (...) {
        SLOGE("addFrame: native exception");
        return toJString(env, errJson("native exception in addFrame"));
    }
}

// ---------- checkFrame ----------

JNIEXPORT jstring JNICALL Java_com_wm_starcam_engine_NativeEngine_checkFrame(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jobject bayer, jint width, jint height,
        jint rowStrideBytes, jstring metaJson) {
    try {
        EngineSession* s = sess(handle);
        if (s == nullptr) return toJString(env, errJson("null handle"));
        int strideElems = 0;
        const char* err = nullptr;
        const uint16_t* px = bayerPtr(env, bayer, width, height, rowStrideBytes, &strideElems, &err);
        if (px == nullptr) return toJString(env, errJson(err ? err : "bad buffer"));
        jsonmini::Obj o;
        if (!jsonmini::Obj::parse(jstr(env, metaJson), &o)) {
            return toJString(env, errJson("meta JSON parse failed"));
        }
        FrameMeta meta = parseFrameMeta(o);
        return toJString(env, s->checkFrame(px, (int)width, (int)height, strideElems, meta));
    } catch (...) {
        SLOGE("checkFrame: native exception");
        return toJString(env, errJson("native exception in checkFrame"));
    }
}

// ---------- addDarkFrame ----------

JNIEXPORT jstring JNICALL Java_com_wm_starcam_engine_NativeEngine_addDarkFrame(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jobject bayer, jint width, jint height,
        jint rowStrideBytes, jstring metaJson) {
    try {
        EngineSession* s = sess(handle);
        if (s == nullptr) return toJString(env, errJson("null handle"));
        int strideElems = 0;
        const char* err = nullptr;
        const uint16_t* px = bayerPtr(env, bayer, width, height, rowStrideBytes, &strideElems, &err);
        if (px == nullptr) return toJString(env, errJson(err ? err : "bad buffer"));
        jsonmini::Obj o;
        if (!jsonmini::Obj::parse(jstr(env, metaJson), &o)) {
            return toJString(env, errJson("meta JSON parse failed"));
        }
        FrameMeta meta = parseFrameMeta(o);
        std::string res = s->addDark(px, (int)width, (int)height, strideElems, meta);
        return toJString(env, res);
    } catch (...) {
        SLOGE("addDarkFrame: native exception");
        return toJString(env, errJson("native exception in addDarkFrame"));
    }
}

// ---------- discardDarks ----------

JNIEXPORT void JNICALL Java_com_wm_starcam_engine_NativeEngine_discardDarks(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    try {
        EngineSession* s = sess(handle);
        if (s != nullptr) s->discardDarks();
    } catch (...) {
        SLOGE("discardDarks: native exception");
    }
}

// ---------- rejectFrame ----------

JNIEXPORT void JNICALL Java_com_wm_starcam_engine_NativeEngine_rejectFrame(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jint index, jstring reason) {
    try {
        EngineSession* s = sess(handle);
        if (s == nullptr) return;
        std::string why = jstr(env, reason);
        s->rejectFrame((int)index, why);
    } catch (...) {
        SLOGE("rejectFrame: native exception");
    }
}

// ---------- getPreview ----------

JNIEXPORT jboolean JNICALL Java_com_wm_starcam_engine_NativeEngine_getPreview(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jobject bitmap) {
    try {
        EngineSession* s = sess(handle);
        if (s == nullptr || bitmap == nullptr) return JNI_FALSE;

        AndroidBitmapInfo info;
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
            return JNI_FALSE;
        }
        if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
            SLOGE("getPreview: bitmap is not RGBA_8888");
            return JNI_FALSE;
        }
        if (info.width == 0 || info.height == 0 || info.stride < info.width * 4u) {
            return JNI_FALSE;
        }

        // Engine fills ARGB ints (0xFF000000|r<<16|g<<8|b) into a temp buffer; repack afterwards
        // into RGBA_8888 memory order (R,G,B,A bytes == little-endian uint32 0xAABBGGRR).
        std::vector<uint32_t> tmp((size_t)info.width * (size_t)info.height);
        bool ok = s->getPreview(tmp.data(), (int)info.width, (int)info.height);
        if (!ok) return JNI_FALSE;

        void* pixels = nullptr;
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
            pixels == nullptr) {
            return JNI_FALSE;
        }
        for (uint32_t y = 0; y < info.height; ++y) {
            const uint32_t* src = tmp.data() + (size_t)y * info.width;
            uint32_t* dst = reinterpret_cast<uint32_t*>(
                    static_cast<uint8_t*>(pixels) + (size_t)y * info.stride);
            for (uint32_t x = 0; x < info.width; ++x) {
                uint32_t p = src[x];
                uint32_t r = (p >> 16) & 0xFFu;
                uint32_t g = (p >> 8) & 0xFFu;
                uint32_t b = p & 0xFFu;
                dst[x] = 0xFF000000u | (b << 16) | (g << 8) | r;
            }
        }
        AndroidBitmap_unlockPixels(env, bitmap);
        return JNI_TRUE;
    } catch (...) {
        SLOGE("getPreview: native exception");
        return JNI_FALSE;
    }
}

// ---------- finalizeStack ----------

JNIEXPORT jstring JNICALL Java_com_wm_starcam_engine_NativeEngine_finalizeStack(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jstring optionsJson, jobject callback) {
    try {
        EngineSession* s = sess(handle);
        if (s == nullptr) return toJString(env, errJson("null handle"));
        std::string options = jstr(env, optionsJson);

        jmethodID mid = nullptr;
        if (callback != nullptr) {
            jclass cbCls = env->GetObjectClass(callback);
            if (cbCls != nullptr) {
                mid = env->GetMethodID(cbCls, "onProgress", "(Ljava/lang/String;I)V");
                env->DeleteLocalRef(cbCls);
            }
            if (env->ExceptionCheck()) env->ExceptionClear();
        }

        // Engine guarantees progress is invoked on the calling thread only, so capturing env
        // and the callback local ref is safe for the duration of this native call.
        starcam::ProgressFn progress = [env, callback, mid](const char* stage, int percent) {
            if (mid == nullptr || callback == nullptr) return;
            jstring js = env->NewStringUTF(stage != nullptr ? stage : "");
            if (js == nullptr) {
                if (env->ExceptionCheck()) env->ExceptionClear();
                return;
            }
            env->CallVoidMethod(callback, mid, js, (jint)percent);
            if (env->ExceptionCheck()) env->ExceptionClear();
            env->DeleteLocalRef(js);
        };

        std::string res = s->finalizeStack(options, progress);
        return toJString(env, res);
    } catch (...) {
        SLOGE("finalizeStack: native exception");
        return toJString(env, errJson("native exception in finalizeStack"));
    }
}

// ---------- abortSession / destroySession ----------

JNIEXPORT void JNICALL Java_com_wm_starcam_engine_NativeEngine_abortSession(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    try {
        EngineSession* s = sess(handle);
        if (s != nullptr) s->abort();
    } catch (...) {
        SLOGE("abortSession: native exception");
    }
}

JNIEXPORT void JNICALL Java_com_wm_starcam_engine_NativeEngine_destroySession(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    try {
        EngineSession* s = sess(handle);
        delete s;  // deleting nullptr is a no-op
    } catch (...) {
        SLOGE("destroySession: native exception");
    }
}

// ---------- analyzeFrame ----------

JNIEXPORT jstring JNICALL Java_com_wm_starcam_engine_NativeEngine_analyzeFrame(
        JNIEnv* env, jobject /*thiz*/, jobject bayer, jint width, jint height, jint rowStrideBytes,
        jstring metaJson) {
    try {
        int strideElems = 0;
        const char* err = nullptr;
        const uint16_t* px = bayerPtr(env, bayer, width, height, rowStrideBytes, &strideElems, &err);
        if (px == nullptr) return toJString(env, errJson(err ? err : "bad buffer"));
        jsonmini::Obj o;
        if (!jsonmini::Obj::parse(jstr(env, metaJson), &o)) {
            return toJString(env, errJson("meta JSON parse failed"));
        }
        // metaJson (analyzeFrame): {cfa, whiteLevel, blackLevel:[4], wbGains:[4]}
        SensorInfo si;
        si.width = (int)width;
        si.height = (int)height;
        int cfa = o.numi("cfa", 0);
        if (cfa < 0 || cfa > 3) cfa = 0;
        si.cfa = static_cast<starcam::CFA>(cfa);
        double wl = o.num("whiteLevel", 1023.0);
        if (wl < 1.0) wl = 1023.0;
        si.whiteLevel = (uint32_t)wl;
        std::vector<double> bl = o.arr("blackLevel");
        for (int i = 0; i < 4 && i < (int)bl.size(); ++i) si.blackLevel[i] = (float)bl[i];
        float wb[4] = {1.0f, 1.0f, 1.0f, 1.0f};
        std::vector<double> wbArr = o.arr("wbGains");
        for (int i = 0; i < 4 && i < (int)wbArr.size(); ++i) wb[i] = (float)wbArr[i];

        std::string res = starcam::analyze_frame(px, (int)width, (int)height, strideElems, si, wb);
        return toJString(env, res);
    } catch (...) {
        SLOGE("analyzeFrame: native exception");
        return toJString(env, errJson("native exception in analyzeFrame"));
    }
}

// ---------- simulateSky (debug builds: SkySim.kt) ----------

// paramsJson: {cfa, black, white, drift, seed, stars, sky, cloud, blurPx, dark}
JNIEXPORT jboolean JNICALL Java_com_wm_starcam_engine_NativeEngine_simulateSky(
        JNIEnv* env, jobject /*thiz*/, jobject buffer, jint width, jint height, jint rowStrideBytes,
        jstring paramsJson) {
    try {
        int strideElems = 0;
        const char* err = nullptr;
        // bayerPtr checks the buffer is direct and large enough; the sky is written into it.
        const uint16_t* px = bayerPtr(env, buffer, width, height, rowStrideBytes, &strideElems, &err);
        if (px == nullptr) {
            SLOGE("simulateSky: %s", err ? err : "bad buffer");
            return JNI_FALSE;
        }
        jsonmini::Obj o;
        if (!jsonmini::Obj::parse(jstr(env, paramsJson), &o)) return JNI_FALSE;
        starcam::SimParams p;
        p.cfa = o.numi("cfa", 0);
        p.black = (float)o.num("black", 64.0);
        p.white = (float)o.num("white", 1023.0);
        p.drift = o.numi("drift", 0);
        p.seed = (uint32_t)o.num("seed", 1.0);
        p.stars = o.numi("stars", 700);
        p.sky = (float)o.num("sky", 0.04);
        p.cloud = (float)o.num("cloud", 0.0);
        p.blurPx = (float)o.num("blurPx", 0.0);
        p.dark = o.boolean("dark", false);
        return starcam::simulate_sky(const_cast<uint16_t*>(px), (int)width, (int)height, strideElems, p)
            ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        SLOGE("simulateSky: native exception");
        return JNI_FALSE;
    }
}

// ---------- developFrame (the timelapse video: util/Timelapse.kt) ----------

JNIEXPORT jboolean JNICALL Java_com_wm_starcam_engine_NativeEngine_developFrame(
        JNIEnv* env, jobject /*thiz*/, jstring srawPath, jstring darkPath, jint rotation, jobject bitmap) {
    try {
        if (bitmap == nullptr) return JNI_FALSE;
        AndroidBitmapInfo info;
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width == 0 || info.height == 0 ||
            info.stride < info.width * 4u) {
            return JNI_FALSE;
        }
        std::vector<uint32_t> tmp((size_t)info.width * (size_t)info.height);
        if (!starcam::develop_frame(jstr(env, srawPath), jstr(env, darkPath), (int)rotation, tmp.data(),
                                    (int)info.width, (int)info.height)) {
            return JNI_FALSE;
        }
        void* pixels = nullptr;
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
            pixels == nullptr) {
            return JNI_FALSE;
        }
        // ARGB ints to RGBA_8888 memory order, as getPreview does.
        for (uint32_t y = 0; y < info.height; ++y) {
            const uint32_t* src = tmp.data() + (size_t)y * info.width;
            uint32_t* dst = reinterpret_cast<uint32_t*>(static_cast<uint8_t*>(pixels) + (size_t)y * info.stride);
            for (uint32_t x = 0; x < info.width; ++x) {
                const uint32_t p = src[x];
                dst[x] = 0xFF000000u | ((p & 0xFFu) << 16) | (p & 0xFF00u) | ((p >> 16) & 0xFFu);
            }
        }
        AndroidBitmap_unlockPixels(env, bitmap);
        return JNI_TRUE;
    } catch (...) {
        SLOGE("developFrame: native exception");
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL Java_com_wm_starcam_engine_NativeEngine_developFrameRelease(JNIEnv* /*env*/, jobject /*thiz*/) {
    starcam::develop_frame_release();
}

// ---------- runSelfTest ----------

JNIEXPORT jstring JNICALL Java_com_wm_starcam_engine_NativeEngine_runSelfTest(
        JNIEnv* env, jobject /*thiz*/, jstring tmpDir) {
    try {
        std::string dir = jstr(env, tmpDir);
        if (dir.empty()) return toJString(env, errJson("empty tmpDir"));
        std::string res = starcam::run_selftest(dir);
        return toJString(env, res);
    } catch (...) {
        SLOGE("runSelfTest: native exception");
        return toJString(env, errJson("native exception in runSelfTest"));
    }
}


// ---------- editing studio ----------

static starcam::Studio* studio(jlong handle) { return reinterpret_cast<starcam::Studio*>(handle); }

// The studio's options: the same keys as finalizeStack's post options, plus "rotation" (the
// clockwise turn the photo is shown and saved with). It develops with the current pipeline
// unless told otherwise.
static bool studioParams(JNIEnv* env, jstring json, starcam::PostParams* pp, int* rotation) {
    jsonmini::Obj o;
    const std::string j = jstr(env, json);
    if (!j.empty() && !jsonmini::Obj::parse(j, &o)) return false;
    *pp = starcam::read_post_params(o);
    if (!o.has("version")) pp->version = 2;
    *rotation = starcam::normalize_rotation(o.numi("rotation", 0));
    return true;
}

JNIEXPORT jlong JNICALL Java_com_wm_starcam_engine_NativeEngine_studioOpen(
        JNIEnv* env, jobject /*thiz*/, jstring tiffPath, jint previewLongEdge) {
    try {
        return reinterpret_cast<jlong>(starcam::Studio::open(jstr(env, tiffPath), (int)previewLongEdge));
    } catch (...) {
        SLOGE("studioOpen: native exception");
        return 0;
    }
}

JNIEXPORT jstring JNICALL Java_com_wm_starcam_engine_NativeEngine_studioInfo(
        JNIEnv* env, jobject /*thiz*/, jlong handle) {
    try {
        starcam::Studio* s = studio(handle);
        return toJString(env, s ? s->info() : std::string("{}"));
    } catch (...) {
        return toJString(env, "{}");
    }
}

JNIEXPORT jboolean JNICALL Java_com_wm_starcam_engine_NativeEngine_studioRender(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jstring optionsJson, jobject bitmap) {
    try {
        starcam::Studio* s = studio(handle);
        if (s == nullptr || bitmap == nullptr) return JNI_FALSE;
        starcam::PostParams pp;
        int rotation = 0;
        if (!studioParams(env, optionsJson, &pp, &rotation)) return JNI_FALSE;
        AndroidBitmapInfo info;
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width == 0 || info.height == 0 ||
            info.stride < info.width * 4u) {
            return JNI_FALSE;
        }
        std::vector<uint32_t> tmp((size_t)info.width * (size_t)info.height);
        if (!s->renderPreview(pp, rotation, tmp.data(), (int)info.width, (int)info.height))
            return JNI_FALSE;
        void* pixels = nullptr;
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
            pixels == nullptr) {
            return JNI_FALSE;
        }
        // ARGB ints to RGBA_8888 memory order, as getPreview does.
        for (uint32_t y = 0; y < info.height; ++y) {
            const uint32_t* src = tmp.data() + (size_t)y * info.width;
            uint32_t* dst = reinterpret_cast<uint32_t*>(static_cast<uint8_t*>(pixels) + (size_t)y * info.stride);
            for (uint32_t x = 0; x < info.width; ++x) {
                const uint32_t p = src[x];
                dst[x] = 0xFF000000u | ((p & 0xFFu) << 16) | (p & 0xFF00u) | ((p >> 16) & 0xFFu);
            }
        }
        AndroidBitmap_unlockPixels(env, bitmap);
        return JNI_TRUE;
    } catch (...) {
        SLOGE("studioRender: native exception");
        return JNI_FALSE;
    }
}

JNIEXPORT jstring JNICALL Java_com_wm_starcam_engine_NativeEngine_studioExport(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jstring optionsJson, jstring outPath) {
    try {
        starcam::Studio* s = studio(handle);
        if (s == nullptr) return toJString(env, errJson("null handle"));
        starcam::PostParams pp;
        int rotation = 0;
        if (!studioParams(env, optionsJson, &pp, &rotation)) return toJString(env, errJson("bad options"));
        return toJString(env, s->exportFull(pp, rotation, jstr(env, outPath)));
    } catch (...) {
        SLOGE("studioExport: native exception");
        return toJString(env, errJson("native exception in studioExport"));
    }
}

JNIEXPORT void JNICALL Java_com_wm_starcam_engine_NativeEngine_studioClose(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    try {
        delete studio(handle);
    } catch (...) {}
}

}  // extern "C"
