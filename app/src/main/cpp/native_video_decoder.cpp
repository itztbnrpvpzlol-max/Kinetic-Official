#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>
#include <unistd.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>

namespace {
struct NativeVideoDecoder {
    int fd = -1;
    int64_t offset = 0;
    int64_t length = 0;
    ANativeWindow* window = nullptr;
    AMediaExtractor* extractor = nullptr;
    AMediaCodec* codec = nullptr;
    std::thread worker;
    std::mutex mutex;
    std::condition_variable wake;
    bool stopping = false;
    bool playing = false;
    bool initialized = false;
    bool inputEos = false;
    bool outputEos = false;
    int64_t pendingSeekUs = -1;
    int64_t initialPositionUs = 0;
    int64_t anchorPtsUs = -1;
    int64_t lastPtsUs = 0;
    int64_t anchorNs = 0;
    double speed = 1.0;

    NativeVideoDecoder(int sourceFd, int64_t sourceOffset, int64_t sourceLength,
                       ANativeWindow* output, int64_t startUs, double playbackSpeed)
        : fd(sourceFd), offset(sourceOffset), length(sourceLength), window(output),
          initialPositionUs(std::max<int64_t>(0, startUs)),
          speed(std::clamp(playbackSpeed, 0.1, 4.0)) {
        worker = std::thread(&NativeVideoDecoder::run, this);
    }

    ~NativeVideoDecoder() { shutdown(); }

    void shutdown() {
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (stopping) return;
            stopping = true;
            playing = false;
        }
        wake.notify_all();
        if (worker.joinable()) worker.join();
        if (codec) {
            AMediaCodec_stop(codec);
            AMediaCodec_delete(codec);
            codec = nullptr;
        }
        if (extractor) {
            AMediaExtractor_delete(extractor);
            extractor = nullptr;
        }
        if (window) {
            ANativeWindow_release(window);
            window = nullptr;
        }
        if (fd >= 0) {
            close(fd);
            fd = -1;
        }
    }

    void seek(int64_t positionUs) {
        std::lock_guard<std::mutex> lock(mutex);
        pendingSeekUs = std::max<int64_t>(0, positionUs);
        wake.notify_all();
    }

    void setPlaying(bool value) {
        std::lock_guard<std::mutex> lock(mutex);
        if (playing != value) {
            playing = value;
            if (value) {
                anchorPtsUs = lastPtsUs;
                anchorNs = monotonicNs();
            }
        }
        wake.notify_all();
    }

    void setSpeed(double value) {
        std::lock_guard<std::mutex> lock(mutex);
        speed = std::clamp(value, 0.1, 4.0);
        anchorPtsUs = lastPtsUs;
        anchorNs = monotonicNs();
    }

    static int64_t monotonicNs() {
        return std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
    }

    bool setup() {
        extractor = AMediaExtractor_new();
        if (!extractor || fd < 0) return false;
        media_status_t dataStatus = AMediaExtractor_setDataSourceFd(
            extractor, fd, offset, length > 0 ? length : INT64_MAX);
        if (dataStatus != AMEDIA_OK) return false;

        ssize_t videoTrack = -1;
        const size_t tracks = AMediaExtractor_getTrackCount(extractor);
        for (size_t i = 0; i < tracks; ++i) {
            AMediaFormat* format = AMediaExtractor_getTrackFormat(extractor, i);
            if (!format) continue;
            const char* mime = nullptr;
            const bool isVideo = AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &mime)
                && mime && std::strncmp(mime, "video/", 6) == 0;
            if (isVideo && videoTrack < 0) videoTrack = static_cast<ssize_t>(i);
            AMediaFormat_delete(format);
            if (isVideo) break;
        }
        if (videoTrack < 0) return false;
        if (AMediaExtractor_selectTrack(extractor, static_cast<size_t>(videoTrack)) != AMEDIA_OK) return false;
        AMediaFormat* format = AMediaExtractor_getTrackFormat(extractor, static_cast<size_t>(videoTrack));
        if (!format) return false;
        const char* mime = nullptr;
        const bool hasMime = AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &mime) && mime;
        if (!hasMime) {
            AMediaFormat_delete(format);
            return false;
        }
        codec = AMediaCodec_createDecoderByType(mime);
        const media_status_t configured = codec
            ? AMediaCodec_configure(codec, format, window, nullptr, 0)
            : AMEDIA_ERROR_UNSUPPORTED;
        AMediaFormat_delete(format);
        if (!codec || configured != AMEDIA_OK || AMediaCodec_start(codec) != AMEDIA_OK) return false;
        if (initialPositionUs > 0) {
            AMediaExtractor_seekTo(extractor, initialPositionUs, AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC);
            AMediaCodec_flush(codec);
        }
        initialized = true;
        return true;
    }

    void performSeek(int64_t targetUs) {
        if (!extractor || !codec) return;
        AMediaExtractor_seekTo(extractor, targetUs, AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC);
        AMediaCodec_flush(codec);
        inputEos = false;
        outputEos = false;
        anchorPtsUs = -1;
        lastPtsUs = targetUs;
        anchorNs = monotonicNs();
    }

    void run() {
        if (!setup()) {
            std::lock_guard<std::mutex> lock(mutex);
            initialized = false;
            playing = false;
            return;
        }
        while (true) {
            int64_t seekTarget = -1;
            bool shouldPlay = false;
            {
                std::unique_lock<std::mutex> lock(mutex);
                if (stopping) break;
                if (pendingSeekUs >= 0) {
                    seekTarget = pendingSeekUs;
                    pendingSeekUs = -1;
                }
                shouldPlay = playing;
                if (!shouldPlay && seekTarget < 0) {
                    wake.wait_for(lock, std::chrono::milliseconds(20), [this] {
                        return stopping || playing || pendingSeekUs >= 0;
                    });
                    if (stopping) break;
                    if (pendingSeekUs >= 0) {
                        seekTarget = pendingSeekUs;
                        pendingSeekUs = -1;
                    }
                    shouldPlay = playing;
                }
            }
            if (seekTarget >= 0) performSeek(seekTarget);
            if (!shouldPlay) continue;
            if (outputEos) {
                std::lock_guard<std::mutex> lock(mutex);
                playing = false;
                continue;
            }

            if (!inputEos) {
                const ssize_t inputIndex = AMediaCodec_dequeueInputBuffer(codec, 0);
                if (inputIndex >= 0) {
                    size_t capacity = 0;
                    uint8_t* input = AMediaCodec_getInputBuffer(codec, static_cast<size_t>(inputIndex), &capacity);
                    const ssize_t sampleSize = input
                        ? AMediaExtractor_readSampleData(extractor, input, capacity)
                        : -1;
                    if (sampleSize < 0) {
                        AMediaCodec_queueInputBuffer(codec, static_cast<size_t>(inputIndex),
                            0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                        inputEos = true;
                    } else {
                        const int64_t pts = AMediaExtractor_getSampleTime(extractor);
                        const uint32_t flags = AMediaExtractor_getSampleFlags(extractor) == AMEDIAEXTRACTOR_SAMPLE_FLAG_SYNC
                            ? 0u : 0u;
                        (void)flags;
                        AMediaCodec_queueInputBuffer(codec, static_cast<size_t>(inputIndex),
                            0, static_cast<size_t>(sampleSize), pts, 0);
                        AMediaExtractor_advance(extractor);
                    }
                }
            }

            AMediaCodecBufferInfo info{};
            const ssize_t outputIndex = AMediaCodec_dequeueOutputBuffer(codec, &info, 1000);
            if (outputIndex >= 0) {
                bool render = info.size > 0;
                int64_t targetNs = monotonicNs();
                if (render) {
                    std::lock_guard<std::mutex> lock(mutex);
                    if (anchorPtsUs < 0) {
                        anchorPtsUs = info.presentationTimeUs;
                        anchorNs = monotonicNs();
                    }
                    const double rate = std::clamp(speed, 0.1, 4.0);
                    targetNs = anchorNs + static_cast<int64_t>(
                        (info.presentationTimeUs - anchorPtsUs) * 1000.0 / rate);
                    lastPtsUs = info.presentationTimeUs;
                }
                if (render) {
                    AMediaCodec_releaseOutputBufferAtTime(codec, static_cast<size_t>(outputIndex), targetNs);
                } else {
                    AMediaCodec_releaseOutputBuffer(codec, static_cast<size_t>(outputIndex), false);
                }
                if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) outputEos = true;
            } else if (outputIndex == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                // Surface output dimensions are owned by the codec and GPU surface.
            }
        }
    }
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_kinetic_app_engine_NativeVideoPlayer_nativeCreate(
        JNIEnv* env, jobject, jint fd, jlong offset, jlong length,
        jobject surface, jlong startPositionMs, jfloat speed) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        close(fd);
        return 0;
    }
    auto* decoder = new NativeVideoDecoder(fd, offset, length, window,
        std::max<jlong>(0, startPositionMs) * 1000, speed);
    return reinterpret_cast<jlong>(decoder);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kinetic_app_engine_NativeVideoPlayer_nativeSeek(
        JNIEnv*, jobject, jlong handle, jlong positionMs) {
    auto* decoder = reinterpret_cast<NativeVideoDecoder*>(handle);
    if (decoder) decoder->seek(std::max<jlong>(0, positionMs) * 1000);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kinetic_app_engine_NativeVideoPlayer_nativePlay(JNIEnv*, jobject, jlong handle) {
    auto* decoder = reinterpret_cast<NativeVideoDecoder*>(handle);
    if (decoder) decoder->setPlaying(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kinetic_app_engine_NativeVideoPlayer_nativePause(JNIEnv*, jobject, jlong handle) {
    auto* decoder = reinterpret_cast<NativeVideoDecoder*>(handle);
    if (decoder) decoder->setPlaying(false);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kinetic_app_engine_NativeVideoPlayer_nativeSetSpeed(
        JNIEnv*, jobject, jlong handle, jfloat speed) {
    auto* decoder = reinterpret_cast<NativeVideoDecoder*>(handle);
    if (decoder) decoder->setSpeed(speed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kinetic_app_engine_NativeVideoPlayer_nativeRelease(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<NativeVideoDecoder*>(handle);
}
