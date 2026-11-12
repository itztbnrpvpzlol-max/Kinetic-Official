#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
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
constexpr const char* LOG_TAG = "KineticVideo";

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
    std::atomic<int> state{0}; // 0=starting, 1=ready, -1=failed, 2=stopped
    bool inputEos = false;
    bool outputEos = false;
    int64_t pendingSeekUs = -1;
    int64_t initialPositionUs = 0;
    int64_t dropBeforeUs = -1;
    int64_t anchorPtsUs = -1;
    int64_t lastPtsUs = 0;
    int64_t anchorNs = 0;
    double speed = 1.0;
    int64_t frameDurationNs = 33'333'333;
    uint64_t droppedLateFrames = 0;
    bool hasMasterClock = false;
    bool masterPlaying = false;
    int64_t masterPositionUs = 0;
    int64_t masterAnchorNs = 0;
    double masterSpeed = 1.0;
    int64_t clipStartUs = 0;
    int64_t sourceOffsetUs = 0;
    double clipSpeed = 1.0;
    uint64_t masterGeneration = 0;

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
        state.store(2, std::memory_order_release);
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
            if (value && anchorPtsUs >= 0) {
                anchorPtsUs = lastPtsUs;
                anchorNs = monotonicNs();
            }
        }
        wake.notify_all();
    }

    void setSpeed(double value) {
        std::lock_guard<std::mutex> lock(mutex);
        speed = std::clamp(value, 0.1, 4.0);
        clipSpeed = speed;
        if (!hasMasterClock && anchorPtsUs >= 0) {
            anchorPtsUs = lastPtsUs;
            anchorNs = monotonicNs();
        }
    }

    void syncMasterClock(int64_t positionUs, int64_t anchorNsValue, double masterRate,
                         bool isPlaying, int64_t layerStartUs, int64_t sourceStartUs,
                         double layerRate, uint64_t generationValue) {
        std::lock_guard<std::mutex> lock(mutex);
        hasMasterClock = true;
        masterPositionUs = std::max<int64_t>(0, positionUs);
        masterAnchorNs = std::max<int64_t>(0, anchorNsValue);
        masterSpeed = std::clamp(masterRate, 0.1, 4.0);
        masterPlaying = isPlaying;
        clipStartUs = layerStartUs;
        sourceOffsetUs = std::max<int64_t>(0, sourceStartUs);
        clipSpeed = std::clamp(layerRate, 0.1, 4.0);
        masterGeneration = generationValue;
        wake.notify_all();
    }

    static int64_t monotonicNs() {
        return std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
    }

    bool setup() {
        extractor = AMediaExtractor_new();
        if (!extractor || fd < 0) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Extractor allocation or source fd failed (fd=%d)", fd);
            return false;
        }
        media_status_t dataStatus = AMediaExtractor_setDataSourceFd(
            extractor, fd, offset, length > 0 ? length : INT64_MAX);
        if (dataStatus != AMEDIA_OK) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "setDataSourceFd failed: %d (offset=%lld length=%lld)", dataStatus, (long long)offset, (long long)length);
            return false;
        }

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
        if (videoTrack < 0) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "No video track found");
            return false;
        }
        if (AMediaExtractor_selectTrack(extractor, static_cast<size_t>(videoTrack)) != AMEDIA_OK) return false;
        AMediaFormat* format = AMediaExtractor_getTrackFormat(extractor, static_cast<size_t>(videoTrack));
        if (!format) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Could not read video track format");
            return false;
        }
        int32_t videoWidth = 0;
        int32_t videoHeight = 0;
        AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_WIDTH, &videoWidth);
        AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_HEIGHT, &videoHeight);
        if (videoWidth > 0 && videoHeight > 0 && window) {
            const int geometryStatus = ANativeWindow_setBuffersGeometry(window, videoWidth, videoHeight, 0);
            if (geometryStatus != 0) {
                __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "Surface buffer geometry %dx%d returned %d", videoWidth, videoHeight, geometryStatus);
            }
        } else {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "Invalid video dimensions %dx%d", videoWidth, videoHeight);
        }
        int32_t frameRate = 0;
        if (AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_FRAME_RATE, &frameRate) && frameRate > 0) {
            frameDurationNs = std::clamp<int64_t>(1'000'000'000LL / frameRate, 8'000'000LL, 100'000'000LL);
        }
        const char* mime = nullptr;
        const bool hasMime = AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &mime) && mime;
        if (!hasMime) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Video track has no MIME type");
            AMediaFormat_delete(format);
            return false;
        }
        codec = AMediaCodec_createDecoderByType(mime);
        const media_status_t configured = codec
            ? AMediaCodec_configure(codec, format, window, nullptr, 0)
            : AMEDIA_ERROR_UNSUPPORTED;
        AMediaFormat_delete(format);
        if (!codec || configured != AMEDIA_OK) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Codec configure failed: codec=%p status=%d mime=%s", codec, configured, mime);
            return false;
        }
        const media_status_t startStatus = AMediaCodec_start(codec);
        if (startStatus != AMEDIA_OK) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Codec start failed: %d", startStatus);
            return false;
        }
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Decoder started: %s %dx%d start=%lldus", mime, videoWidth, videoHeight, (long long)initialPositionUs);
        if (initialPositionUs > 0) {
            AMediaExtractor_seekTo(extractor, initialPositionUs, AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC);
            AMediaCodec_flush(codec);
        }
        dropBeforeUs = initialPositionUs;
        initialized = true;
        state.store(1, std::memory_order_release);
        return true;
    }

    void performSeek(int64_t targetUs) {
        if (!extractor || !codec) return;
        AMediaExtractor_seekTo(extractor, targetUs, AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC);
        AMediaCodec_flush(codec);
        dropBeforeUs = targetUs;
        inputEos = false;
        outputEos = false;
        {
            std::lock_guard<std::mutex> lock(mutex);
            anchorPtsUs = -1;
            lastPtsUs = targetUs;
            anchorNs = monotonicNs();
        }
    }

    void run() {
        if (!setup()) {
            std::lock_guard<std::mutex> lock(mutex);
            initialized = false;
            playing = false;
            state.store(-1, std::memory_order_release);
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Video decoder setup failed; preview output unavailable");
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
                        AMediaCodec_queueInputBuffer(codec, static_cast<size_t>(inputIndex),
                            0, static_cast<size_t>(sampleSize), pts, 0);
                        AMediaExtractor_advance(extractor);
                    }
                }
            }

            AMediaCodecBufferInfo info{};
            // Keep the decoder responsive to pause/seek/stop commands. A long
            // dequeue timeout can otherwise present stale output after transport changes.
            const ssize_t outputIndex = AMediaCodec_dequeueOutputBuffer(codec, &info, 5'000);
            if (outputIndex >= 0) {
                // Surface-backed video decoders may report size == 0 because
                // decoded pixels are delivered to the Surface, not a CPU buffer.
                // A non-EOS output buffer is still a displayable frame.
                const bool endOfStream = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
                bool transportAllowsOutput = false;
                {
                    std::lock_guard<std::mutex> lock(mutex);
                    // Never publish a frame that completed decoding after a pause,
                    // a newer seek request, or shutdown.
                    transportAllowsOutput = !stopping && playing && pendingSeekUs < 0 && (!hasMasterClock || masterPlaying);
                }
                const bool render = !endOfStream && transportAllowsOutput;
                static std::atomic<uint32_t> outputBuffers{0};
                static std::atomic<uint32_t> renderedFrames{0};
                const uint32_t outputNumber = ++outputBuffers;
                if (outputNumber <= 8 || outputNumber % 300 == 0) {
                    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                        "Codec output #%u pts=%lldus size=%d flags=0x%x render=%d",
                        outputNumber, (long long)info.presentationTimeUs, info.size,
                        info.flags, render ? 1 : 0);
                }
                if (render) {
                    const uint32_t frameNumber = ++renderedFrames;
                    if (frameNumber == 1 || frameNumber % 300 == 0) {
                        __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                            "Queueing video frame %u pts=%lldus size=%d",
                            frameNumber, (long long)info.presentationTimeUs, info.size);
                    }
                }
                if (render && dropBeforeUs >= 0) {
                    if (info.presentationTimeUs < dropBeforeUs) {
                        AMediaCodec_releaseOutputBuffer(codec, static_cast<size_t>(outputIndex), false);
                        continue;
                    }
                    dropBeforeUs = -1;
                }
                int64_t targetNs = monotonicNs();
                if (render) {
                    std::lock_guard<std::mutex> lock(mutex);
                    if (hasMasterClock) {
                        const long double timelineUs = static_cast<long double>(clipStartUs) +
                            (static_cast<long double>(info.presentationTimeUs) -
                             static_cast<long double>(sourceOffsetUs)) /
                                static_cast<long double>(clipSpeed);
                        const long double deltaUs = timelineUs - static_cast<long double>(masterPositionUs);
                        targetNs = masterAnchorNs + static_cast<int64_t>(
                            deltaUs * 1000.0L / static_cast<long double>(masterSpeed));
                    } else {
                        if (anchorPtsUs < 0) {
                            anchorPtsUs = info.presentationTimeUs;
                            anchorNs = monotonicNs();
                        }
                        const double rate = std::clamp(speed, 0.1, 4.0);
                        targetNs = anchorNs + static_cast<int64_t>(
                            (info.presentationTimeUs - anchorPtsUs) * 1000.0 / rate);
                    }
                    lastPtsUs = info.presentationTimeUs;
                }

                // Real-time preview is better served by the newest frame than
                // by presenting an increasingly stale queue. If decoding has
                // fallen more than one source-frame interval behind the clock,
                // discard this decoded output and keep draining toward live time.
                const int64_t nowNs = monotonicNs();
                if (render && targetNs < nowNs - frameDurationNs) {
                    AMediaCodec_releaseOutputBuffer(codec, static_cast<size_t>(outputIndex), false);
                    ++droppedLateFrames;
                    if (droppedLateFrames <= 5 || droppedLateFrames % 120 == 0) {
                        __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                            "Auto frame-skip: dropped %llu late frames (latest pts=%lldus, late by %lldms)",
                            static_cast<unsigned long long>(droppedLateFrames),
                            (long long)info.presentationTimeUs,
                            (long long)((nowNs - targetNs) / 1'000'000LL));
                    }
                    continue;
                }

                if (render) {
                    AMediaCodec_releaseOutputBufferAtTime(
                        codec, static_cast<size_t>(outputIndex), std::max(targetNs, nowNs));
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
Java_com_kinetic_app_engine_NativeVideoPlayer_nativeSyncMasterClock(
        JNIEnv*, jobject, jlong handle, jlong positionUs, jlong anchorNs,
        jdouble masterSpeed, jboolean playing, jlong clipStartUs,
        jlong sourceOffsetUs, jdouble clipSpeed, jlong generation) {
    auto* decoder = reinterpret_cast<NativeVideoDecoder*>(handle);
    if (!decoder) return;
    decoder->syncMasterClock(positionUs, anchorNs, masterSpeed, playing == JNI_TRUE,
        clipStartUs, sourceOffsetUs, clipSpeed,
        static_cast<uint64_t>(std::max<jlong>(0, generation)));
}

extern "C" JNIEXPORT void JNICALL
Java_com_kinetic_app_engine_NativeVideoPlayer_nativeRelease(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<NativeVideoDecoder*>(handle);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_kinetic_app_engine_NativeVideoPlayer_nativeGetState(JNIEnv*, jobject, jlong handle) {
    auto* decoder = reinterpret_cast<NativeVideoDecoder*>(handle);
    return decoder ? decoder->state.load(std::memory_order_acquire) : -1;
}
