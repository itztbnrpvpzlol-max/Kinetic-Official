#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstddef>
#include <list>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace kre {

struct FrameTicket {
    int64_t timestampUs = 0;
    uint64_t fingerprint = 0;
    int priority = 0;
};

class FrameScheduler {
public:
    std::vector<FrameTicket> plan(int64_t playheadUs, double fps, double speed,
                                  int64_t durationUs, int requested) {
        std::vector<FrameTicket> result;
        const int count = std::clamp(requested, 1, 20);
        const double safeFps = std::clamp(fps, 1.0, 120.0);
        const double safeSpeed = std::clamp(speed, 0.1, 4.0);
        const int64_t step = static_cast<int64_t>(1000000.0 / safeFps * safeSpeed);
        result.reserve(static_cast<size_t>(count));
        for (int i = 0; i < count; ++i) {
            int64_t pts = std::max<int64_t>(0, playheadUs) + step * i;
            if (durationUs > 0 && pts >= durationUs) break;
            result.push_back({pts, 0, i == 0 ? 100 : (i < 5 ? 80 : 50)});
        }
        return result;
    }
};

class CalculationCache {
    struct Entry { std::vector<float> values; std::list<uint64_t>::iterator lru; };
    std::mutex mutex_;
    std::unordered_map<uint64_t, Entry> entries_;
    std::list<uint64_t> lru_;
    size_t bytes_ = 0;
    size_t budget_ = 2u * 1024u * 1024u;
public:
    void setBudget(size_t bytes) {
        std::lock_guard<std::mutex> guard(mutex_);
        budget_ = std::clamp<size_t>(bytes, 64u * 1024u, 16u * 1024u * 1024u);
        trimLocked();
    }
    bool get(uint64_t key, std::vector<float>& output) {
        std::lock_guard<std::mutex> guard(mutex_);
        auto it = entries_.find(key);
        if (it == entries_.end()) return false;
        lru_.splice(lru_.begin(), lru_, it->second.lru);
        output = it->second.values;
        return true;
    }
    void put(uint64_t key, const float* values, std::size_t count) {
        if (!values || count == 0 || count > 4096) return;
        std::vector<float> copy(values, values + count);
        const size_t cost = copy.size() * sizeof(float);
        std::lock_guard<std::mutex> guard(mutex_);
        auto old = entries_.find(key);
        if (old != entries_.end()) {
            bytes_ -= old->second.values.size() * sizeof(float);
            lru_.erase(old->second.lru);
            entries_.erase(old);
        }
        if (cost > budget_) return;
        lru_.push_front(key);
        entries_.emplace(key, Entry{std::move(copy), lru_.begin()});
        bytes_ += cost;
        trimLocked();
    }
    void clear() {
        std::lock_guard<std::mutex> guard(mutex_);
        entries_.clear(); lru_.clear(); bytes_ = 0;
    }
    size_t bytes() {
        std::lock_guard<std::mutex> guard(mutex_);
        return bytes_;
    }
private:
    void trimLocked() {
        while (bytes_ > budget_ && !lru_.empty()) {
            const uint64_t key = lru_.back();
            auto it = entries_.find(key);
            if (it != entries_.end()) {
                bytes_ -= it->second.values.size() * sizeof(float);
                entries_.erase(it);
            }
            lru_.pop_back();
        }
    }
};

static FrameScheduler scheduler;
static CalculationCache calculations;

} // namespace kre

extern "C" bool kreGetCalculation(uint64_t key, float* output, std::size_t count) {
    if (!output || count == 0) return false;
    std::vector<float> values;
    if (!kre::calculations.get(key, values) || values.size() != count) return false;
    std::copy(values.begin(), values.end(), output);
    return true;
}

extern "C" void krePutCalculation(uint64_t key, const float* values, size_t count) {
    kre::calculations.put(key, values, count);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_kinetic_app_engine_KreEngine_nativePlanFrames(
        JNIEnv* env, jobject, jlong playheadUs, jdouble fps, jdouble speed,
        jlong durationUs, jint count) {
    const auto frames = kre::scheduler.plan(playheadUs, fps, speed, durationUs, count);
    jlongArray result = env->NewLongArray(static_cast<jsize>(frames.size()));
    if (!result) return nullptr;
    std::vector<jlong> values;
    values.reserve(frames.size());
    for (const auto& frame : frames) values.push_back(static_cast<jlong>(frame.timestampUs));
    if (!values.empty()) env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kinetic_app_engine_KreEngine_nativeSetCalculationCacheBudget(
        JNIEnv*, jobject, jint bytes) {
    kre::calculations.setBudget(static_cast<size_t>(std::max(0, bytes)));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kinetic_app_engine_KreEngine_nativeGetCalculation(
        JNIEnv* env, jobject, jlong fingerprint, jfloatArray output) {
    if (!output) return JNI_FALSE;
    const jsize count = env->GetArrayLength(output);
    std::vector<float> values;
    if (!kre::calculations.get(static_cast<uint64_t>(fingerprint), values) ||
        values.size() != static_cast<size_t>(count)) return JNI_FALSE;
    env->SetFloatArrayRegion(output, 0, count, values.data());
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kinetic_app_engine_KreEngine_nativePutCalculation(
        JNIEnv* env, jobject, jlong fingerprint, jfloatArray input) {
    if (!input) return;
    const jsize count = env->GetArrayLength(input);
    if (count <= 0 || count > 4096) return;
    std::vector<float> values(static_cast<size_t>(count));
    env->GetFloatArrayRegion(input, 0, count, values.data());
    if (env->ExceptionCheck()) { env->ExceptionClear(); return; }
    kre::calculations.put(static_cast<uint64_t>(fingerprint), values.data(), values.size());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_kinetic_app_engine_KreEngine_nativeCalculationCacheBytes(JNIEnv*, jobject) {
    return static_cast<jint>(std::min<size_t>(kre::calculations.bytes(), 0x7fffffff));
}

extern "C" JNIEXPORT void JNICALL
Java_com_kinetic_app_engine_KreEngine_nativeClearCalculationCache(JNIEnv*, jobject) {
    kre::calculations.clear();
}
