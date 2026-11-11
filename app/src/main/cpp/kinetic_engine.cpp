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
