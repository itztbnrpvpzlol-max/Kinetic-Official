#include <jni.h>
#include <cmath>
#include <algorithm>

namespace {
inline float cubic(float t, float a, float b) {
    const float inv = 1.0f - t;
    return 3.0f * inv * inv * t * a + 3.0f * inv * t * t * b + t * t * t;
}
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_kinetic_app_engine_NativeRenderEngine_nativeCubicProgress(
        JNIEnv*, jobject, jfloat progress, jfloat x1, jfloat y1, jfloat x2, jfloat y2) {
    const float u = std::clamp(progress, 0.0f, 1.0f);
    float low = 0.0f;
    float high = 1.0f;
    for (int i = 0; i < 18; ++i) {
        const float mid = (low + high) * 0.5f;
        if (cubic(mid, x1, x2) < u) low = mid;
        else high = mid;
    }
    return cubic((low + high) * 0.5f, y1, y2);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_kinetic_app_engine_NativeRenderEngine_nativeEvaluateCurves(
        JNIEnv* env, jobject, jfloatArray input) {
    if (input == nullptr) return nullptr;
    const jsize length = env->GetArrayLength(input);
    if (length % 5 != 0) return nullptr;

    jfloat* values = env->GetFloatArrayElements(input, nullptr);
    if (values == nullptr) return nullptr;
    jfloatArray output = env->NewFloatArray(length / 5);
    if (output == nullptr) {
        env->ReleaseFloatArrayElements(input, values, JNI_ABORT);
        return nullptr;
    }

    const jsize count = length / 5;
    jfloat* result = new jfloat[count];
    for (jsize i = 0; i < count; ++i) {
        const jsize offset = i * 5;
        const float u = std::clamp(values[offset], 0.0f, 1.0f);
        float low = 0.0f;
        float high = 1.0f;
        for (int step = 0; step < 18; ++step) {
            const float mid = (low + high) * 0.5f;
            if (cubic(mid, values[offset + 1], values[offset + 3]) < u) low = mid;
            else high = mid;
        }
        result[i] = cubic((low + high) * 0.5f, values[offset + 2], values[offset + 4]);
    }
    env->SetFloatArrayRegion(output, 0, count, result);
    delete[] result;
    env->ReleaseFloatArrayElements(input, values, JNI_ABORT);
    return output;
}


#include <chrono>
#include <cstdint>
#include <mutex>

namespace {
struct PlaybackClock {
    std::mutex mutex;
    double positionSeconds = 0.0;
    double durationSeconds = 0.0;
    double speed = 1.0;
    bool looping = false;
    bool playing = false;
    std::chrono::steady_clock::time_point anchor = std::chrono::steady_clock::now();

    double positionLocked(std::chrono::steady_clock::time_point now) {
        if (!playing) return positionSeconds;
        const double elapsed = std::chrono::duration<double>(now - anchor).count();
        double next = positionSeconds + elapsed * speed;
        if (durationSeconds > 0.0 && next >= durationSeconds) {
            if (looping) {
                next = std::fmod(next, durationSeconds);
                positionSeconds = next;
                anchor = now;
            } else {
                positionSeconds = durationSeconds;
                playing = false;
                anchor = now;
                return positionSeconds;
            }
        }
        return next;
    }
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_kinetic_app_engine_NativePlaybackEngine_nativeCreate(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new PlaybackClock());
}

extern "C" JNIEXPORT void JNICALL
Java_com_kinetic_app_engine_NativePlaybackEngine_nativeConfigure(
        JNIEnv*, jobject, jlong handle, jdouble duration, jboolean looping, jdouble speed) {
    auto* clock = reinterpret_cast<PlaybackClock*>(handle);
    if (clock == nullptr) return;
    std::lock_guard<std::mutex> lock(clock->mutex);
    const auto now = std::chrono::steady_clock::now();
    clock->positionSeconds = clock->positionLocked(now);
    clock->durationSeconds = std::max(0.0, static_cast<double>(duration));
    clock->looping = looping == JNI_TRUE;
    clock->speed = std::clamp(static_cast<double>(speed), 0.1, 4.0);
    if (clock->durationSeconds > 0.0) {
        clock->positionSeconds = std::min(clock->positionSeconds, clock->durationSeconds);
    }
    clock->anchor = now;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kinetic_app_engine_NativePlaybackEngine_nativeSeek(
        JNIEnv*, jobject, jlong handle, jdouble position) {
    auto* clock = reinterpret_cast<PlaybackClock*>(handle);
    if (clock == nullptr) return;
    std::lock_guard<std::mutex> lock(clock->mutex);
    const double upper = clock->durationSeconds > 0.0 ? clock->durationSeconds : 1.0e12;
    clock->positionSeconds = std::clamp(static_cast<double>(position), 0.0, upper);
    clock->anchor = std::chrono::steady_clock::now();
}

extern "C" JNIEXPORT void JNICALL
Java_com_kinetic_app_engine_NativePlaybackEngine_nativePlay(JNIEnv*, jobject, jlong handle) {
    auto* clock = reinterpret_cast<PlaybackClock*>(handle);
    if (clock == nullptr) return;
    std::lock_guard<std::mutex> lock(clock->mutex);
    if (clock->durationSeconds > 0.0 && clock->positionSeconds >= clock->durationSeconds) {
        clock->positionSeconds = clock->looping ? 0.0 : clock->durationSeconds;
    }
    clock->anchor = std::chrono::steady_clock::now();
    clock->playing = clock->durationSeconds <= 0.0 || clock->positionSeconds < clock->durationSeconds || clock->looping;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kinetic_app_engine_NativePlaybackEngine_nativePause(JNIEnv*, jobject, jlong handle) {
    auto* clock = reinterpret_cast<PlaybackClock*>(handle);
    if (clock == nullptr) return;
    std::lock_guard<std::mutex> lock(clock->mutex);
    const auto now = std::chrono::steady_clock::now();
    clock->positionSeconds = clock->positionLocked(now);
    clock->playing = false;
    clock->anchor = now;
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_kinetic_app_engine_NativePlaybackEngine_nativePosition(JNIEnv*, jobject, jlong handle) {
    auto* clock = reinterpret_cast<PlaybackClock*>(handle);
    if (clock == nullptr) return 0.0;
    std::lock_guard<std::mutex> lock(clock->mutex);
    return clock->positionLocked(std::chrono::steady_clock::now());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kinetic_app_engine_NativePlaybackEngine_nativeIsPlaying(JNIEnv*, jobject, jlong handle) {
    auto* clock = reinterpret_cast<PlaybackClock*>(handle);
    if (clock == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(clock->mutex);
    clock->positionLocked(std::chrono::steady_clock::now());
    return clock->playing ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kinetic_app_engine_NativePlaybackEngine_nativeRelease(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<PlaybackClock*>(handle);
}
