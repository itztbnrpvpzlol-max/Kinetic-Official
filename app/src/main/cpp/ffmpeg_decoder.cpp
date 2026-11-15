#include <jni.h>
#include <android/log.h>
#include <libavutil/mem.h>
#include <cstdlib>
#include <new>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <libavcodec/avcodec.h>
#include <libavcodec/mediacodec.h>
#include <libavcodec/jni.h>
#include <libavformat/avformat.h>
#include <libavutil/error.h>
#include <libavutil/time.h>
#include <pthread.h>
#include <atomic>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>

namespace {
JavaVM* gJavaVm = nullptr;

struct ClockSnapshot {
    bool valid = false;
    int64_t positionUs = 0;
    int64_t monotonicNs = 0;
    double masterSpeed = 1.0;
    bool playing = false;
    int64_t clipStartUs = 0;
    int64_t sourceOffsetUs = 0;
    double clipSpeed = 1.0;
    int64_t generation = -1;
};

struct DecoderSession {
    std::string source;
    jobject surface = nullptr;
    int width = 1280;
    int height = 720;

    std::mutex mutex;
    std::condition_variable condition;
    pthread_t worker{};
    bool workerStarted = false;
    std::atomic<bool> stop{false};

    int state = 0; // 0=starting, 1=ready, -1=failed, 2=released
    std::string error;

    bool playing = false;
    bool stepPending = true;
    bool seekPending = true;
    int64_t seekTargetUs = 0;
    float manualSpeed = 1.0f;
    int64_t localPositionUs = 0;
    int64_t localAnchorNs = 0;
    ClockSnapshot clock;
    int64_t lastRenderedPtsUs = -1;

    AVFormatContext* format = nullptr;
    AVCodecContext* codec = nullptr;
    AVMediaCodecContext* mediaCodec = nullptr;
    AVPacket* packet = nullptr;
    AVFrame* frame = nullptr;
    int videoStream = -1;
    AVRational timeBase{1, AV_TIME_BASE};
    int64_t streamStart = 0;
    bool codecOpened = false;
};

static int64_t monotonicNs() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

static int interruptInput(void* opaque) {
    auto* session = static_cast<DecoderSession*>(opaque);
    return session && session->stop.load() ? 1 : 0;
}

static std::string ffmpegError(int code) {
    char buffer[AV_ERROR_MAX_STRING_SIZE] = {};
    av_strerror(code, buffer, sizeof(buffer));
    return std::string(buffer);
}

static void setError(DecoderSession* session, const std::string& message) {
    std::lock_guard<std::mutex> lock(session->mutex);
    session->error = message;
    session->state = -1;
    session->condition.notify_all();
}

static int64_t expectedSourceUsLocked(const DecoderSession* session, int64_t nowNs) {
    if (session->clock.valid) {
        const auto& c = session->clock;
        const int64_t elapsedUs = c.playing
            ? static_cast<int64_t>(std::max<int64_t>(0, nowNs - c.monotonicNs) / 1000.0 * c.masterSpeed)
            : 0;
        const int64_t timelineUs = std::max<int64_t>(0, c.positionUs + elapsedUs);
        const int64_t clipElapsedUs = std::max<int64_t>(0, timelineUs - c.clipStartUs);
        return std::max<int64_t>(0, c.sourceOffsetUs +
            static_cast<int64_t>(clipElapsedUs * c.clipSpeed));
    }
    const int64_t elapsedUs = session->playing
        ? static_cast<int64_t>(std::max<int64_t>(0, nowNs - session->localAnchorNs) / 1000.0 *
            session->manualSpeed)
        : 0;
    return std::max<int64_t>(0, session->localPositionUs + elapsedUs);
}

static const char* mediaCodecDecoderName(AVCodecID id) {
    switch (id) {
        case AV_CODEC_ID_H264: return "h264_mediacodec";
        case AV_CODEC_ID_HEVC: return "hevc_mediacodec";
        case AV_CODEC_ID_MPEG2VIDEO: return "mpeg2_mediacodec";
        case AV_CODEC_ID_MPEG4: return "mpeg4_mediacodec";
        case AV_CODEC_ID_VP8: return "vp8_mediacodec";
        case AV_CODEC_ID_VP9: return "vp9_mediacodec";
        case AV_CODEC_ID_AV1: return "av1_mediacodec";
        default: return nullptr;
    }
}

static void closeDecoder(DecoderSession* session) {
    if (session->codec) {
        if (session->codecOpened) {
            avcodec_close(session->codec);
            session->codecOpened = false;
        }
        if (session->mediaCodec) {
            av_mediacodec_default_free(session->codec);
            session->mediaCodec = nullptr;
        }
        avcodec_free_context(&session->codec);
    }
    av_packet_free(&session->packet);
    av_frame_free(&session->frame);
}

static bool openDecoder(DecoderSession* session) {
    session->format = avformat_alloc_context();
    if (!session->format) {
        setError(session, "FFmpeg could not allocate a media container");
        return false;
    }
    session->format->interrupt_callback.callback = interruptInput;
    session->format->interrupt_callback.opaque = session;

    int result = avformat_open_input(&session->format, session->source.c_str(), nullptr, nullptr);
    if (result < 0) {
        setError(session, "FFmpeg could not open the video: " + ffmpegError(result));
        return false;
    }
    result = avformat_find_stream_info(session->format, nullptr);
    if (result < 0) {
        setError(session, "FFmpeg could not read video stream information: " + ffmpegError(result));
        return false;
    }

    const AVCodec* streamCodec = nullptr;
    result = av_find_best_stream(session->format, AVMEDIA_TYPE_VIDEO, -1, -1, &streamCodec, 0);
    if (result < 0 || !streamCodec) {
        setError(session, "The selected media does not contain a supported video stream");
        return false;
    }
    session->videoStream = result;
    AVStream* stream = session->format->streams[session->videoStream];
    session->timeBase = stream->time_base;
    session->streamStart = stream->start_time == AV_NOPTS_VALUE ? 0 : stream->start_time;

    const char* hardwareName = mediaCodecDecoderName(stream->codecpar->codec_id);
    const AVCodec* decoder = hardwareName ? avcodec_find_decoder_by_name(hardwareName) : nullptr;
    if (!decoder) {
        setError(session, "This video codec has no FFmpeg MediaCodec decoder in the bundled build");
        return false;
    }

    session->codec = avcodec_alloc_context3(decoder);
    if (!session->codec) {
        setError(session, "FFmpeg could not allocate a video decoder");
        return false;
    }
    result = avcodec_parameters_to_context(session->codec, stream->codecpar);
    if (result < 0) {
        setError(session, "FFmpeg could not read the video codec parameters: " + ffmpegError(result));
        return false;
    }
    session->codec->pkt_timebase = stream->time_base;
    session->codec->thread_count = 1;
    session->codec->thread_type = 0;

    session->mediaCodec = av_mediacodec_alloc_context();
    if (!session->mediaCodec) {
        setError(session, "FFmpeg MediaCodec support was not included in this build");
        return false;
    }
    result = av_mediacodec_default_init(session->codec, session->mediaCodec, session->surface);
    if (result < 0) {
        av_free(session->mediaCodec);
        session->mediaCodec = nullptr;
        setError(session, "FFmpeg could not attach Android's hardware decoder to the preview surface: " +
            ffmpegError(result));
        return false;
    }

    result = avcodec_open2(session->codec, decoder, nullptr);
    if (result < 0) {
        closeDecoder(session);
        setError(session, "Android could not start the FFmpeg hardware decoder: " + ffmpegError(result));
        return false;
    }
    session->codecOpened = true;
    session->packet = av_packet_alloc();
    session->frame = av_frame_alloc();
    if (!session->packet || !session->frame) {
        setError(session, "FFmpeg could not allocate video frame buffers");
        return false;
    }

    {
        std::lock_guard<std::mutex> lock(session->mutex);
        session->state = 1;
        session->error.clear();
        session->condition.notify_all();
    }
    __android_log_print(ANDROID_LOG_INFO, "KineticFFmpeg",
        "Direct FFmpeg decoder ready: codec=%s size=%dx%d",
        decoder->name ? decoder->name : "unknown",
        session->codec->width, session->codec->height);
    return true;
}

static int64_t frameSourceTimeUs(const DecoderSession* session, const AVFrame* frame) {
    int64_t pts = frame->best_effort_timestamp;
    if (pts == AV_NOPTS_VALUE) pts = frame->pts;
    if (pts == AV_NOPTS_VALUE) return -1;
    return av_rescale_q(pts - session->streamStart, session->timeBase, AV_TIME_BASE_Q);
}

static bool renderHardwareFrame(DecoderSession* session, AVFrame* frame) {
    auto* buffer = reinterpret_cast<AVMediaCodecBuffer*>(frame->data[3]);
    if (!buffer) return false;

    bool playing = false;
    int64_t ptsUs = frameSourceTimeUs(session, frame);
    int64_t expectedUs = 0;
    double rate = 1.0;
    {
        std::lock_guard<std::mutex> lock(session->mutex);
        playing = session->playing;
        expectedUs = expectedSourceUsLocked(session, monotonicNs());
        rate = session->clock.valid
            ? std::max(0.1, session->clock.masterSpeed * session->clock.clipSpeed)
            : std::max(0.1f, session->manualSpeed);
    }
    if (ptsUs < 0) ptsUs = expectedUs;

    if (!playing) {
        if (!session->stepPending) {
            av_mediacodec_release_buffer(buffer, 0);
            return true;
        }
        const int64_t target = session->seekTargetUs;
        if (ptsUs + 40000 < target) {
            av_mediacodec_release_buffer(buffer, 0);
            return true;
        }
        const int result = av_mediacodec_release_buffer(buffer, 1);
        if (result < 0) return false;
        std::lock_guard<std::mutex> lock(session->mutex);
        session->lastRenderedPtsUs = ptsUs;
        session->stepPending = false;
        session->condition.notify_all();
        return true;
    }

    int64_t deltaUs = ptsUs - expectedUs;
    if (deltaUs < -100000) {
        av_mediacodec_release_buffer(buffer, 0);
        return true;
    }

    // Keep MediaCodec's output queue bounded. Very early frames wait until
    // they are close enough to the presentation clock for timed release.
    while (deltaUs > 500000 && !session->stop.load()) {
        std::unique_lock<std::mutex> lock(session->mutex);
        session->condition.wait_for(lock, std::chrono::milliseconds(8));
        if (session->seekPending) {
            lock.unlock();
            av_mediacodec_release_buffer(buffer, 0);
            return true;
        }
        expectedUs = expectedSourceUsLocked(session, monotonicNs());
        deltaUs = ptsUs - expectedUs;
    }
    if (session->stop.load()) {
        av_mediacodec_release_buffer(buffer, 0);
        return true;
    }

    const int64_t now = monotonicNs();
    const int64_t renderAt = now + static_cast<int64_t>(
        std::max<int64_t>(0, deltaUs) * 1000.0 / rate);
    int result = av_mediacodec_render_buffer_at_time(buffer, renderAt);
    if (result < 0) result = av_mediacodec_release_buffer(buffer, 1);
    if (result < 0) return false;

    std::lock_guard<std::mutex> lock(session->mutex);
    session->lastRenderedPtsUs = ptsUs;
    return true;
}

static bool applyPendingSeek(DecoderSession* session) {
    int64_t targetUs = 0;
    {
        std::lock_guard<std::mutex> lock(session->mutex);
        if (!session->seekPending) return true;
        targetUs = session->seekTargetUs;
        session->seekPending = false;
    }

    AVStream* stream = session->format->streams[session->videoStream];
    const int64_t targetTs = session->streamStart +
        av_rescale_q(targetUs, AV_TIME_BASE_Q, stream->time_base);
    const int result = av_seek_frame(session->format, session->videoStream,
        targetTs, AVSEEK_FLAG_BACKWARD);
    if (result < 0) {
        setError(session, "FFmpeg could not seek to the requested frame: " + ffmpegError(result));
        return false;
    }
    avcodec_flush_buffers(session->codec);
    return true;
}

static bool drainDecoder(DecoderSession* session, bool* received) {
    while (!session->stop.load()) {
        const int result = avcodec_receive_frame(session->codec, session->frame);
        if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) return true;
        if (result < 0) {
            setError(session, "FFmpeg video decoding failed: " + ffmpegError(result));
            return false;
        }
        *received = true;
        const bool rendered = renderHardwareFrame(session, session->frame);
        av_frame_unref(session->frame);
        if (!rendered) {
            setError(session, "FFmpeg could not present a decoded frame to the preview surface");
            return false;
        }
    }
    return true;
}

static void cleanupSession(DecoderSession* session) {
    closeDecoder(session);
    if (session->format) avformat_close_input(&session->format);
}

static void* decoderThread(void* opaque) {
    auto* session = static_cast<DecoderSession*>(opaque);
    if (gJavaVm) {
        JNIEnv* env = nullptr;
        if (gJavaVm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            setError(session, "FFmpeg decoder thread could not attach to the Android runtime");
            return nullptr;
        }
    }

    av_log_set_level(AV_LOG_WARNING);
    avformat_network_init();
    if (!openDecoder(session)) {
        cleanupSession(session);
        if (gJavaVm) gJavaVm->DetachCurrentThread();
        return nullptr;
    }

    bool eof = false;
    while (!session->stop.load()) {
        if (!applyPendingSeek(session)) break;

        bool shouldRead = false;
        {
            std::unique_lock<std::mutex> lock(session->mutex);
            if (!session->playing && !session->stepPending) {
                session->condition.wait_for(lock, std::chrono::milliseconds(25), [&] {
                    return session->stop.load() || session->playing ||
                        session->stepPending || session->seekPending;
                });
            }
            if (session->stop.load()) break;
            shouldRead = session->playing || session->stepPending;
        }
        if (!shouldRead) continue;

        if (eof) {
            std::lock_guard<std::mutex> lock(session->mutex);
            session->playing = false;
            session->stepPending = false;
            session->condition.notify_all();
            continue;
        }

        const int readResult = av_read_frame(session->format, session->packet);
        if (readResult == AVERROR_EOF) {
            eof = true;
            avcodec_send_packet(session->codec, nullptr);
            bool received = false;
            if (!drainDecoder(session, &received)) break;
            continue;
        }
        if (readResult < 0) {
            if (session->stop.load()) break;
            setError(session, "FFmpeg could not read the next video packet: " + ffmpegError(readResult));
            break;
        }

        if (session->packet->stream_index == session->videoStream) {
            int sendResult = avcodec_send_packet(session->codec, session->packet);
            if (sendResult == AVERROR(EAGAIN)) {
                bool received = false;
                if (!drainDecoder(session, &received)) {
                    av_packet_unref(session->packet);
                    break;
                }
                sendResult = avcodec_send_packet(session->codec, session->packet);
            }
            if (sendResult < 0 && sendResult != AVERROR_EOF) {
                av_packet_unref(session->packet);
                setError(session, "FFmpeg rejected a video packet: " + ffmpegError(sendResult));
                break;
            }
            bool received = false;
            if (!drainDecoder(session, &received)) {
                av_packet_unref(session->packet);
                break;
            }
        }
        av_packet_unref(session->packet);
    }

    cleanupSession(session);
    {
        std::lock_guard<std::mutex> lock(session->mutex);
        if (session->state != -1) session->state = session->stop.load() ? 2 : 1;
        session->condition.notify_all();
    }
    if (gJavaVm) gJavaVm->DetachCurrentThread();
    return nullptr;
}

static DecoderSession* fromHandle(jlong handle) {
    return reinterpret_cast<DecoderSession*>(static_cast<intptr_t>(handle));
}
} // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    gJavaVm = vm;
    av_jni_set_java_vm(vm, nullptr);
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_kinetic_app_engine_NativeFfmpegDecoder_nativeCreate(
    JNIEnv* env, jobject, jstring sourcePath, jobject surface, jint width, jint height) {
    if (!sourcePath || !surface || !gJavaVm) return 0;
    const char* chars = env->GetStringUTFChars(sourcePath, nullptr);
    if (!chars) return 0;

    auto* session = new (std::nothrow) DecoderSession();
    if (!session) {
        env->ReleaseStringUTFChars(sourcePath, chars);
        return 0;
    }
    session->source = chars;
    env->ReleaseStringUTFChars(sourcePath, chars);
    session->surface = env->NewGlobalRef(surface);
    session->width = std::max(16, static_cast<int>(width));
    session->height = std::max(16, static_cast<int>(height));
    if (!session->surface) {
        delete session;
        return 0;
    }
    session->localAnchorNs = monotonicNs();

    if (pthread_create(&session->worker, nullptr, decoderThread, session) != 0) {
        env->DeleteGlobalRef(session->surface);
        delete session;
        return 0;
    }
    session->workerStarted = true;
    return static_cast<jlong>(reinterpret_cast<intptr_t>(session));
}

extern "C" JNIEXPORT void JNICALL
Java_com_kinetic_app_engine_NativeFfmpegDecoder_nativeSetClock(
    JNIEnv*, jobject, jlong handle, jlong positionUs, jlong monotonicNsValue,
    jdouble masterSpeed, jboolean playing, jlong clipStartUs, jlong sourceOffsetUs,
    jdouble clipSpeed, jlong generation) {
    auto* session = fromHandle(handle);
    if (!session || session->stop.load()) return;
    std::lock_guard<std::mutex> lock(session->mutex);
    const int64_t now = monotonicNs();
    ClockSnapshot next;
    next.valid = true;
    next.positionUs = std::max<jlong>(0, positionUs);
    next.monotonicNs = monotonicNsValue > 0 ? monotonicNsValue : now;
    next.masterSpeed = std::clamp(static_cast<double>(masterSpeed), 0.1, 4.0);
    next.playing = playing == JNI_TRUE;
    next.clipStartUs = clipStartUs;
    next.sourceOffsetUs = std::max<jlong>(0, sourceOffsetUs);
    next.clipSpeed = std::clamp(static_cast<double>(clipSpeed), 0.1, 4.0);
    next.generation = generation;

    if (session->clock.valid && session->clock.generation != generation) {
        const int64_t elapsedUs = next.playing
            ? static_cast<int64_t>(std::max<int64_t>(0, now - next.monotonicNs) / 1000.0 * next.masterSpeed)
            : 0;
        const int64_t timelineUs = std::max<int64_t>(0, next.positionUs + elapsedUs);
        const int64_t target = std::max<int64_t>(0, next.sourceOffsetUs +
            static_cast<int64_t>(std::max<int64_t>(0, timelineUs - next.clipStartUs) * next.clipSpeed));
        if (session->lastRenderedPtsUs < 0 ||
            std::llabs(target - session->lastRenderedPtsUs) > 140000) {
            session->seekTargetUs = target;
            session->seekPending = true;
            session->stepPending = !next.playing;
        }
    }
    session->clock = next;
    session->playing = next.playing;
    session->condition.notify_all();
}

extern "C" JNIEXPORT void JNICALL
Java_com_kinetic_app_engine_NativeFfmpegDecoder_nativeSeek(
    JNIEnv*, jobject, jlong handle, jlong targetUs) {
    auto* session = fromHandle(handle);
    if (!session || session->stop.load()) return;
    std::lock_guard<std::mutex> lock(session->mutex);
    session->seekTargetUs = std::max<jlong>(0, targetUs);
    session->seekPending = true;
    session->stepPending = !session->playing;
    session->localPositionUs = session->seekTargetUs;
    session->localAnchorNs = monotonicNs();
    session->condition.notify_all();
}

extern "C" JNIEXPORT void JNICALL
Java_com_kinetic_app_engine_NativeFfmpegDecoder_nativeSetSpeed(
    JNIEnv*, jobject, jlong handle, jfloat speed) {
    auto* session = fromHandle(handle);
    if (!session || session->stop.load()) return;
    std::lock_guard<std::mutex> lock(session->mutex);
    session->localPositionUs = expectedSourceUsLocked(session, monotonicNs());
    session->localAnchorNs = monotonicNs();
    session->manualSpeed = std::clamp(static_cast<float>(speed), 0.1f, 4.0f);
    session->condition.notify_all();
}

extern "C" JNIEXPORT void JNICALL
Java_com_kinetic_app_engine_NativeFfmpegDecoder_nativePlay(
    JNIEnv*, jobject, jlong handle) {
    auto* session = fromHandle(handle);
    if (!session || session->stop.load()) return;
    std::lock_guard<std::mutex> lock(session->mutex);
    session->localPositionUs = expectedSourceUsLocked(session, monotonicNs());
    session->localAnchorNs = monotonicNs();
    session->playing = true;
    session->stepPending = false;
    if (session->clock.valid) session->clock.playing = true;
    session->condition.notify_all();
}

extern "C" JNIEXPORT void JNICALL
Java_com_kinetic_app_engine_NativeFfmpegDecoder_nativePause(
    JNIEnv*, jobject, jlong handle) {
    auto* session = fromHandle(handle);
    if (!session || session->stop.load()) return;
    std::lock_guard<std::mutex> lock(session->mutex);
    session->localPositionUs = expectedSourceUsLocked(session, monotonicNs());
    session->localAnchorNs = monotonicNs();
    session->playing = false;
    session->stepPending = false;
    if (session->clock.valid) session->clock.playing = false;
    session->condition.notify_all();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_kinetic_app_engine_NativeFfmpegDecoder_nativeState(
    JNIEnv*, jobject, jlong handle) {
    auto* session = fromHandle(handle);
    if (!session) return -1;
    std::lock_guard<std::mutex> lock(session->mutex);
    return session->state;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kinetic_app_engine_NativeFfmpegDecoder_nativeError(
    JNIEnv* env, jobject, jlong handle) {
    auto* session = fromHandle(handle);
    if (!session) return env->NewStringUTF("FFmpeg decoder is not initialized");
    std::lock_guard<std::mutex> lock(session->mutex);
    return env->NewStringUTF(session->error.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_kinetic_app_engine_NativeFfmpegDecoder_nativeRelease(
    JNIEnv* env, jobject, jlong handle) {
    auto* session = fromHandle(handle);
    if (!session) return;
    session->stop.store(true);
    session->condition.notify_all();
    if (session->workerStarted && !pthread_equal(pthread_self(), session->worker)) {
        pthread_join(session->worker, nullptr);
    }
    if (session->surface) env->DeleteGlobalRef(session->surface);
    session->state = 2;
    delete session;
}
