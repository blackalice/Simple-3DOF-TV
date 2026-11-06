// Minimal JNI bridge to Viture's glasses SDK (Gen1/Gen2 pose callback path).
// Java side: com.portable3dof.GlassesBridge
#include <jni.h>
#include <android/log.h>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <mutex>

#include "viture_glasses_provider.h"
#include "viture_device.h"
#include "viture_protocol_public.h"
#include "viture_result.h"

#define TAG "P3DOF-bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static XRDeviceProviderHandle g_handle = nullptr;
static bool g_imu_open = false;
static std::mutex g_mu;
static std::condition_variable g_cv;
static float g_pose[7];          // roll, pitch, yaw, qw, qx, qy, qz
static long long g_seq = 0;

static void imu_pose_cb(float* data, uint64_t /*timestamp*/) {
    {
        std::lock_guard<std::mutex> lk(g_mu);
        std::memcpy(g_pose, data, sizeof(g_pose));
        ++g_seq;
    }
    g_cv.notify_all();   // wake the Java reader the instant a sample arrives (no polling jitter)
}

static void teardown() {
    if (!g_handle) return;
    if (g_imu_open) { xr_device_provider_close_imu(g_handle, VITURE_IMU_MODE_POSE); g_imu_open = false; }
    xr_device_provider_stop(g_handle);
    xr_device_provider_shutdown(g_handle);
    xr_device_provider_destroy(g_handle);
    g_handle = nullptr;
    g_cv.notify_all();
}

extern "C" {

// Returns 0 on success, or a negative error code / -1000 - step on failure.
JNIEXPORT jint JNICALL
Java_com_portable3dof_GlassesBridge_begin(JNIEnv*, jclass, jint pid, jint fd) {
    teardown();
    {
        std::lock_guard<std::mutex> lk(g_mu);
        std::memset(g_pose, 0, sizeof(g_pose));
        g_seq = 0;
    }
    xr_device_provider_set_log_level(1);
    if (!xr_device_provider_is_product_id_valid(pid)) { LOGE("invalid product id 0x%04x", pid); return -1001; }

    g_handle = xr_device_provider_create(pid, fd);
    if (!g_handle) { LOGE("create failed pid=0x%04x fd=%d", pid, fd); return -1002; }
    LOGI("device type = %d", xr_device_provider_get_device_type(g_handle));

    int r = xr_device_provider_initialize(g_handle, nullptr, nullptr);
    if (r != VITURE_GLASSES_SUCCESS) { LOGE("initialize failed %d", r); teardown(); return r ? r : -1003; }

    r = xr_device_provider_register_imu_pose_callback(g_handle, imu_pose_cb);
    if (r != VITURE_GLASSES_SUCCESS) { LOGE("register pose cb failed %d", r); teardown(); return r ? r : -1004; }

    r = xr_device_provider_start(g_handle);
    if (r != VITURE_GLASSES_SUCCESS) { LOGE("start failed %d", r); teardown(); return r ? r : -1005; }

    // Pro 2 limits pose mode to <= 120 Hz; try the highest supported of MEDIUM/MEDIUM_LOW/LOW.
    const int freqs[] = {VITURE_IMU_FREQUENCY_MEDIUM, VITURE_IMU_FREQUENCY_MEDIUM_LOW, VITURE_IMU_FREQUENCY_LOW};
    r = -1006;
    for (int f : freqs) {
        if (!xr_device_provider_is_product_support_imu_frequency(pid, VITURE_IMU_MODE_POSE, f)) continue;
        r = xr_device_provider_open_imu(g_handle, VITURE_IMU_MODE_POSE, f);
        LOGI("open_imu(pose, freq=%d) -> %d", f, r);
        if (r == VITURE_GLASSES_SUCCESS) break;
    }
    if (r != VITURE_GLASSES_SUCCESS) { teardown(); return r ? r : -1006; }
    g_imu_open = true;
    return 0;
}

JNIEXPORT void JNICALL
Java_com_portable3dof_GlassesBridge_end(JNIEnv*, jclass) {
    teardown();
}

// Copies the latest pose into out[7] and returns its sequence number (0 = nothing yet).
JNIEXPORT jlong JNICALL
Java_com_portable3dof_GlassesBridge_getPose(JNIEnv* env, jclass, jfloatArray out) {
    float p[7];
    long long seq;
    {
        std::lock_guard<std::mutex> lk(g_mu);
        std::memcpy(p, g_pose, sizeof(p));
        seq = g_seq;
    }
    if (out && env->GetArrayLength(out) >= 7) env->SetFloatArrayRegion(out, 0, 7, p);
    return (jlong) seq;
}

// Blocks until a pose newer than lastSeq arrives (or timeoutMs passes), then copies it into out[7].
JNIEXPORT jlong JNICALL
Java_com_portable3dof_GlassesBridge_waitPose(JNIEnv* env, jclass, jfloatArray out, jlong lastSeq, jint timeoutMs) {
    float p[7];
    long long seq;
    {
        std::unique_lock<std::mutex> lk(g_mu);
        g_cv.wait_for(lk, std::chrono::milliseconds(timeoutMs), [&] { return g_seq != (long long) lastSeq; });
        std::memcpy(p, g_pose, sizeof(p));
        seq = g_seq;
    }
    if (out && env->GetArrayLength(out) >= 7) env->SetFloatArrayRegion(out, 0, 7, p);
    return (jlong) seq;
}

}  // extern "C"
