#include <jni.h>
#include <cmath>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <vector>
#include <cstddef>
#include <android/log.h>

extern "C" bool kreGetCalculation(uint64_t key, float* output, std::size_t count);
extern "C" void krePutCalculation(uint64_t key, const float* values, std::size_t count);

namespace {
inline float cubic(float t, float a, float b) {
    const float inv = 1.0f - t;
    return 3.0f * inv * inv * t * a + 3.0f * inv * t * t * b + t * t * t;
}

uint64_t curveFingerprint(float x1, float y1, float x2, float y2) {
    uint64_t hash = 1469598103934665603ULL;
    const float controls[4] = {x1, y1, x2, y2};
    for (float value : controls) {
        uint32_t bits = 0;
        std::memcpy(&bits, &value, sizeof(bits));
        hash = (hash ^ bits) * 1099511628211ULL;
    }
    return hash;
}

/** Reusable 257-point easing LUT: the cache key excludes progress by design. */
float evaluateCurveCached(float progress, float x1, float y1, float x2, float y2) {
    constexpr std::size_t LUT_SIZE = 257;
    float table[LUT_SIZE];
    const uint64_t fingerprint = curveFingerprint(x1, y1, x2, y2);
    if (!kreGetCalculation(fingerprint, table, LUT_SIZE)) {
        for (std::size_t sample = 0; sample < LUT_SIZE; ++sample) {
            const float targetX = static_cast<float>(sample) / 256.0f;
            float low = 0.0f;
            float high = 1.0f;
            for (int step = 0; step < 16; ++step) {
                const float mid = (low + high) * 0.5f;
                if (cubic(mid, x1, x2) < targetX) low = mid;
                else high = mid;
            }
            table[sample] = cubic((low + high) * 0.5f, y1, y2);
        }
        krePutCalculation(fingerprint, table, LUT_SIZE);
    }

    const float u = std::clamp(progress, 0.0f, 1.0f) * 256.0f;
    const std::size_t lower = static_cast<std::size_t>(u);
    if (lower >= 256) return table[256];
    const float fraction = u - static_cast<float>(lower);
    return table[lower] + (table[lower + 1] - table[lower]) * fraction;
}
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_kinetic_app_engine_NativeRenderEngine_nativeCubicProgress(
        JNIEnv*, jobject, jfloat progress, jfloat x1, jfloat y1, jfloat x2, jfloat y2) {
    return evaluateCurveCached(progress, x1, y1, x2, y2);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_kinetic_app_engine_NativeRenderEngine_nativeEvaluateCurves(
        JNIEnv* env, jobject, jfloatArray input) {
    if (input == nullptr) return nullptr;
    const jsize length = env->GetArrayLength(input);
    if (length % 5 != 0 || length > 5 * 4096) return nullptr;

    jfloat* values = env->GetFloatArrayElements(input, nullptr);
    if (values == nullptr) return nullptr;
    const jsize count = length / 5;
    jfloatArray output = env->NewFloatArray(count);
    if (output == nullptr) {
        env->ReleaseFloatArrayElements(input, values, JNI_ABORT);
        return nullptr;
    }
    std::vector<jfloat> result(static_cast<std::size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        const jsize offset = i * 5;
        result[static_cast<std::size_t>(i)] = evaluateCurveCached(
            values[offset], values[offset + 1], values[offset + 2],
            values[offset + 3], values[offset + 4]
        );
    }
    if (count > 0) env->SetFloatArrayRegion(output, 0, count, result.data());
    env->ReleaseFloatArrayElements(input, values, JNI_ABORT);
    return output;
}

#include <chrono>
#include <cstdint>
#include <mutex>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>

namespace {
struct PlaybackClock {
    std::mutex mutex;
    double positionSeconds = 0.0;
    double durationSeconds = 0.0;
    double speed = 1.0;
    bool looping = false;
    bool playing = false;
    uint64_t generation = 1;
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
                ++generation;
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
    ++clock->generation;
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
    ++clock->generation;
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
    ++clock->generation;
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
    ++clock->generation;
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_kinetic_app_engine_NativePlaybackEngine_nativePosition(JNIEnv*, jobject, jlong handle) {
    auto* clock = reinterpret_cast<PlaybackClock*>(handle);
    if (clock == nullptr) return 0.0;
    std::lock_guard<std::mutex> lock(clock->mutex);
    return clock->positionLocked(std::chrono::steady_clock::now());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_kinetic_app_engine_NativePlaybackEngine_nativePositionFrame(
        JNIEnv*, jobject, jlong handle, jdouble fps) {
    auto* clock = reinterpret_cast<PlaybackClock*>(handle);
    if (clock == nullptr || !std::isfinite(fps) || fps <= 0.0) return 0;
    std::lock_guard<std::mutex> lock(clock->mutex);
    const double position = clock->positionLocked(std::chrono::steady_clock::now());
    const double safeFps = std::clamp(fps, 1.0, 240.0);
    const double frame = std::floor(position * safeFps + 1.0e-7);
    return static_cast<jlong>(std::max(0.0, frame));
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_kinetic_app_engine_NativePlaybackEngine_nativeClockSnapshot(JNIEnv* env, jobject, jlong handle) {
    auto* clock = reinterpret_cast<PlaybackClock*>(handle);
    jlong values[5] = {0, 0, 1'000'000, 0, 0};
    if (clock != nullptr) {
        std::lock_guard<std::mutex> lock(clock->mutex);
        const auto now = std::chrono::steady_clock::now();
        const double position = clock->positionLocked(now);
        const auto nowNs = std::chrono::duration_cast<std::chrono::nanoseconds>(now.time_since_epoch()).count();
        values[0] = static_cast<jlong>(std::max(0.0, position) * 1'000'000.0);
        values[1] = static_cast<jlong>(nowNs);
        values[2] = static_cast<jlong>(std::clamp(clock->speed, 0.1, 4.0) * 1'000'000.0);
        values[3] = clock->playing ? 1 : 0;
        values[4] = static_cast<jlong>(clock->generation);
    }
    jlongArray result = env->NewLongArray(5);
    if (result) env->SetLongArrayRegion(result, 0, 5, values);
    return result;
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


namespace {
GLuint gVertexArrayObject = 0;
GLuint gVertexBuffer = 0;
GLuint gCompositionFramebuffer = 0;
GLuint gCompositionTexture = 0;
GLsizei gCompositionWidth = 0;
GLsizei gCompositionHeight = 0;

void releaseCompositionTarget() {
    if (gCompositionFramebuffer) glDeleteFramebuffers(1, &gCompositionFramebuffer);
    if (gCompositionTexture) glDeleteTextures(1, &gCompositionTexture);
    gCompositionFramebuffer = 0;
    gCompositionTexture = 0;
    gCompositionWidth = 0;
    gCompositionHeight = 0;
}
GLuint gTextureProgram = 0;
GLuint gExternalProgram = 0;
GLuint gSolidProgram = 0;
GLint gTexturePosition = -1, gTextureUv = -1, gTextureSampler = -1;
GLint gTextureAlpha = -1, gTextureGray = -1;
GLint gExternalPosition = -1, gExternalUv = -1, gExternalSampler = -1;
GLint gExternalAlpha = -1, gExternalGray = -1, gExternalMatrix = -1;
GLint gSolidPosition = -1, gSolidColor = -1;

GLuint compileGlShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    if (!shader) return 0;
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint status = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &status);
    if (status != GL_TRUE) {
        GLint logLength = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &logLength);
        if (logLength > 1) {
            std::vector<char> log(static_cast<std::size_t>(logLength));
            glGetShaderInfoLog(shader, logLength, nullptr, log.data());
            __android_log_print(ANDROID_LOG_ERROR, "KRE", "Shader compile failed: %s", log.data());
        }
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

GLuint linkGlProgram(const char* vertexSource, const char* fragmentSource) {
    GLuint vertex = compileGlShader(GL_VERTEX_SHADER, vertexSource);
    GLuint fragment = compileGlShader(GL_FRAGMENT_SHADER, fragmentSource);
    if (!vertex || !fragment) {
        if (vertex) glDeleteShader(vertex);
        if (fragment) glDeleteShader(fragment);
        return 0;
    }
    GLuint program = glCreateProgram();
    if (!program) {
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        return 0;
    }
    glAttachShader(program, vertex);
    glAttachShader(program, fragment);
    glLinkProgram(program);
    GLint status = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &status);
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    if (status != GL_TRUE) {
        GLint logLength = 0;
        glGetProgramiv(program, GL_INFO_LOG_LENGTH, &logLength);
        if (logLength > 1) {
            std::vector<char> log(static_cast<std::size_t>(logLength));
            glGetProgramInfoLog(program, logLength, nullptr, log.data());
            __android_log_print(ANDROID_LOG_ERROR, "KRE", "Program link failed: %s", log.data());
        }
        glDeleteProgram(program);
        return 0;
    }
    return program;
}

void applyNativeBlend(jint mode) {
    switch (mode) {
        case 1: glBlendFunc(GL_DST_COLOR, GL_ONE_MINUS_SRC_ALPHA); break;
        case 2: glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_COLOR); break;
        case 3: glBlendFunc(GL_ONE, GL_ONE); break;
        default: glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA); break;
    }
}

void drawNativeTexture(GLuint program, GLint positionHandle, GLint uvHandle,
                       GLint samplerHandle, GLint alphaHandle, GLint grayHandle,
                       GLenum target, jint textureId, const jfloat* vertices,
                       jfloat alpha, jfloat gray, jint blendMode) {
    if (!program || !vertices || alpha <= 0.f) return;
    if (!gVertexBuffer || !gVertexArrayObject) return;
    glBindVertexArray(gVertexArrayObject);
    glBindBuffer(GL_ARRAY_BUFFER, gVertexBuffer);
    glBufferSubData(GL_ARRAY_BUFFER, 0, 16 * sizeof(jfloat), vertices);
    glUseProgram(program);
    glEnableVertexAttribArray(static_cast<GLuint>(positionHandle));
    glVertexAttribPointer(static_cast<GLuint>(positionHandle), 2, GL_FLOAT, GL_FALSE,
                          4 * sizeof(jfloat), reinterpret_cast<const void*>(0));
    glEnableVertexAttribArray(static_cast<GLuint>(uvHandle));
    glVertexAttribPointer(static_cast<GLuint>(uvHandle), 2, GL_FLOAT, GL_FALSE,
                          4 * sizeof(jfloat), reinterpret_cast<const void*>(2 * sizeof(jfloat)));
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(target, static_cast<GLuint>(textureId));
    glUniform1i(samplerHandle, 0);
    glUniform1f(alphaHandle, alpha);
    glUniform1f(grayHandle, gray);
    applyNativeBlend(blendMode);
    glEnable(GL_BLEND);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisable(GL_BLEND);
    glDisableVertexAttribArray(static_cast<GLuint>(positionHandle));
    glDisableVertexAttribArray(static_cast<GLuint>(uvHandle));
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kinetic_app_engine_NativeRenderEngine_nativeGlEnsureCompositionCache(
        JNIEnv*, jobject, jint width, jint height, jlong budgetBytes) {
    const int safeWidth = std::max(1, width);
    const int safeHeight = std::max(1, height);
    const int64_t requiredBytes = static_cast<int64_t>(safeWidth) *
                                  static_cast<int64_t>(safeHeight) * 4LL;
    if (width <= 0 || height <= 0 || budgetBytes <= 0 || requiredBytes > budgetBytes) {
        releaseCompositionTarget();
        return JNI_FALSE;
    }
    if (gCompositionFramebuffer && gCompositionTexture &&
        gCompositionWidth == width && gCompositionHeight == height) return JNI_TRUE;

    releaseCompositionTarget();
    GLuint texture = 0;
    GLuint framebuffer = 0;
    glGenTextures(1, &texture);
    glGenFramebuffers(1, &framebuffer);
    if (!texture || !framebuffer) {
        if (texture) glDeleteTextures(1, &texture);
        if (framebuffer) glDeleteFramebuffers(1, &framebuffer);
        return JNI_FALSE;
    }
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                           GL_TEXTURE_2D, texture, 0);
    const bool complete = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glBindTexture(GL_TEXTURE_2D, 0);
    if (!complete) {
        glDeleteFramebuffers(1, &framebuffer);
        glDeleteTextures(1, &texture);
        __android_log_print(ANDROID_LOG_WARN, "KRE", "Composition cache framebuffer incomplete");
        return JNI_FALSE;
    }
    gCompositionFramebuffer = framebuffer;
    gCompositionTexture = texture;
    gCompositionWidth = width;
    gCompositionHeight = height;
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kinetic_app_engine_NativeRenderEngine_nativeGlReleaseCompositionCache(JNIEnv*, jobject) {
    releaseCompositionTarget();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kinetic_app_engine_NativeRenderEngine_nativeGlPresentCompositionCache(
        JNIEnv*, jobject, jint viewportWidth, jint viewportHeight) {
    if (!gCompositionFramebuffer) return JNI_FALSE;
    // Isolate the blit result from any earlier texture-upload/draw error.
    while (glGetError() != GL_NO_ERROR) {}
    glBindFramebuffer(GL_READ_FRAMEBUFFER, gCompositionFramebuffer);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
    glBlitFramebuffer(
        0, 0, gCompositionWidth, gCompositionHeight,
        0, 0, std::max(1, viewportWidth), std::max(1, viewportHeight),
        GL_COLOR_BUFFER_BIT, GL_NEAREST
    );
    const GLenum error = glGetError();
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (error != GL_NO_ERROR) {
        __android_log_print(ANDROID_LOG_WARN, "KRE", "Composition cache present failed: 0x%x", error);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kinetic_app_engine_NativeRenderEngine_nativeGlBeginFrame(
        JNIEnv*, jobject, jint backgroundArgb, jint width, jint height,
        jboolean useCompositionCache) {
    const float alpha = static_cast<float>((backgroundArgb >> 24) & 0xFF) / 255.0f;
    const float red = static_cast<float>((backgroundArgb >> 16) & 0xFF) / 255.0f;
    const float green = static_cast<float>((backgroundArgb >> 8) & 0xFF) / 255.0f;
    const float blue = static_cast<float>(backgroundArgb & 0xFF) / 255.0f;
    const GLuint target = useCompositionCache == JNI_TRUE ? gCompositionFramebuffer : 0;
    glBindFramebuffer(GL_FRAMEBUFFER, target);
    glViewport(0, 0, std::max(1, width), std::max(1, height));
    glClearColor(red, green, blue, alpha);
    glClear(GL_COLOR_BUFFER_BIT);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kinetic_app_engine_NativeRenderEngine_nativeGlInit(JNIEnv*, jobject) {
    // KRE requires an actual ES 3.x context. Do not initialize against a
    // silently downgraded ES 2 context.
    GLint glMajor = 0;
    glGetIntegerv(GL_MAJOR_VERSION, &glMajor);
    if (glMajor < 3) return JNI_FALSE;
    // ES 3.x core requires vertex data to be supplied through buffer objects;
    // client-memory attribute pointers from the old ES 2 path are not used.
    gVertexArrayObject = 0;
    gVertexBuffer = 0;
    glGenVertexArrays(1, &gVertexArrayObject);
    glGenBuffers(1, &gVertexBuffer);
    if (!gVertexArrayObject || !gVertexBuffer) {
        if (gVertexArrayObject) glDeleteVertexArrays(1, &gVertexArrayObject);
        if (gVertexBuffer) glDeleteBuffers(1, &gVertexBuffer);
        gVertexArrayObject = 0;
        gVertexBuffer = 0;
        return JNI_FALSE;
    }
    // The previous EGL context may have been destroyed; discard stale names.
    gCompositionFramebuffer = 0;
    gCompositionTexture = 0;
    gCompositionWidth = 0;
    gCompositionHeight = 0;
    glBindVertexArray(gVertexArrayObject);
    // Allocate the streaming vertex store once. Per-layer draws update only
    // its small fixed-size range instead of reallocating a GL buffer each time.
    glBindBuffer(GL_ARRAY_BUFFER, gVertexBuffer);
    glBufferData(GL_ARRAY_BUFFER, 16 * sizeof(float), nullptr, GL_STREAM_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindVertexArray(0);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);

    // KRE uses GLSL ES 3.00 exclusively; no ES 2 shader fallback is
    // silently accepted after requesting an ES 3.x context.
    static const char* vertex =
        "#version 300 es\n"
        "in vec2 aPosition; in vec2 aTexCoord; out vec2 vTexCoord;"
        "void main(){ gl_Position=vec4(aPosition,0.0,1.0); vTexCoord=aTexCoord; }";
    static const char* textureFragment =
        "#version 300 es\n"
        "precision mediump float; uniform sampler2D uTexture; uniform float uAlpha;"
        "uniform float uGray; in vec2 vTexCoord; out vec4 fragColor;"
        "void main(){ vec4 c=texture(uTexture,vTexCoord);"
        "if(uGray>0.5){ float y=dot(c.rgb,vec3(0.299,0.587,0.114)); c.rgb=vec3(y); }"
        "fragColor=vec4(c.rgb*c.a*uAlpha,c.a*uAlpha); }";
    static const char* externalFragment =
        "#version 300 es\n"
        "#extension GL_OES_EGL_image_external_essl3 : require\n"
        "precision mediump float; uniform samplerExternalOES uTexture; uniform float uAlpha;"
        "uniform float uGray; uniform mat4 uTexMatrix; in vec2 vTexCoord; out vec4 fragColor;"
        "void main(){ vec2 uv=(uTexMatrix*vec4(vTexCoord,0.0,1.0)).xy;"
        "vec4 c=texture(uTexture,uv);"
        "if(uGray>0.5){ float y=dot(c.rgb,vec3(0.299,0.587,0.114)); c.rgb=vec3(y); }"
        "fragColor=vec4(c.rgb*c.a*uAlpha,c.a*uAlpha); }";
    static const char* solidVertex =
        "#version 300 es\n"
        "in vec2 aPosition; void main(){ gl_Position=vec4(aPosition,0.0,1.0); }";
    static const char* solidFragment =
        "#version 300 es\n"
        "precision mediump float; uniform vec4 uColor; out vec4 fragColor;"
        "void main(){ fragColor=uColor; }";

    gTextureProgram = linkGlProgram(vertex, textureFragment);
    gExternalProgram = linkGlProgram(vertex, externalFragment);
    gSolidProgram = linkGlProgram(solidVertex, solidFragment);
    if (!gTextureProgram || !gExternalProgram || !gSolidProgram) {
        if (gTextureProgram) glDeleteProgram(gTextureProgram);
        if (gExternalProgram) glDeleteProgram(gExternalProgram);
        if (gSolidProgram) glDeleteProgram(gSolidProgram);
        if (gVertexBuffer) glDeleteBuffers(1, &gVertexBuffer);
        if (gVertexArrayObject) glDeleteVertexArrays(1, &gVertexArrayObject);
        gTextureProgram = gExternalProgram = gSolidProgram = 0;
        gVertexBuffer = 0;
        gVertexArrayObject = 0;
        return JNI_FALSE;
    }

    gTexturePosition = glGetAttribLocation(gTextureProgram, "aPosition");
    gTextureUv = glGetAttribLocation(gTextureProgram, "aTexCoord");
    gTextureSampler = glGetUniformLocation(gTextureProgram, "uTexture");
    gTextureAlpha = glGetUniformLocation(gTextureProgram, "uAlpha");
    gTextureGray = glGetUniformLocation(gTextureProgram, "uGray");
    gExternalPosition = glGetAttribLocation(gExternalProgram, "aPosition");
    gExternalUv = glGetAttribLocation(gExternalProgram, "aTexCoord");
    gExternalSampler = glGetUniformLocation(gExternalProgram, "uTexture");
    gExternalAlpha = glGetUniformLocation(gExternalProgram, "uAlpha");
    gExternalGray = glGetUniformLocation(gExternalProgram, "uGray");
    gExternalMatrix = glGetUniformLocation(gExternalProgram, "uTexMatrix");
    gSolidPosition = glGetAttribLocation(gSolidProgram, "aPosition");
    gSolidColor = glGetUniformLocation(gSolidProgram, "uColor");

    const bool locationsValid =
        gTexturePosition >= 0 && gTextureUv >= 0 && gTextureSampler >= 0 &&
        gTextureAlpha >= 0 && gTextureGray >= 0 &&
        gExternalPosition >= 0 && gExternalUv >= 0 && gExternalSampler >= 0 &&
        gExternalAlpha >= 0 && gExternalGray >= 0 && gExternalMatrix >= 0 &&
        gSolidPosition >= 0 && gSolidColor >= 0;
    if (!locationsValid) {
        __android_log_print(ANDROID_LOG_ERROR, "KRE", "Required GLES3 shader inputs were not linked");
        if (gTextureProgram) glDeleteProgram(gTextureProgram);
        if (gExternalProgram) glDeleteProgram(gExternalProgram);
        if (gSolidProgram) glDeleteProgram(gSolidProgram);
        if (gVertexBuffer) glDeleteBuffers(1, &gVertexBuffer);
        if (gVertexArrayObject) glDeleteVertexArrays(1, &gVertexArrayObject);
        gTextureProgram = gExternalProgram = gSolidProgram = 0;
        gVertexBuffer = gVertexArrayObject = 0;
        gTexturePosition = gTextureUv = gTextureSampler = gTextureAlpha = gTextureGray = -1;
        gExternalPosition = gExternalUv = gExternalSampler = gExternalAlpha = gExternalGray = gExternalMatrix = -1;
        gSolidPosition = gSolidColor = -1;
        return JNI_FALSE;
    }
    __android_log_print(ANDROID_LOG_INFO, "KRE", "Native GLES3 renderer initialized");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kinetic_app_engine_NativeRenderEngine_nativeGlDrawTexture(
        JNIEnv* env, jobject, jint textureId, jfloatArray vertices,
        jfloat alpha, jfloat gray, jint blendMode) {
    if (!vertices || env->GetArrayLength(vertices) < 16) return;
    jfloat* data = env->GetFloatArrayElements(vertices, nullptr);
    if (!data) return;
    drawNativeTexture(gTextureProgram, gTexturePosition, gTextureUv,
        gTextureSampler, gTextureAlpha, gTextureGray, GL_TEXTURE_2D,
        textureId, data, alpha, gray, blendMode);
    env->ReleaseFloatArrayElements(vertices, data, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kinetic_app_engine_NativeRenderEngine_nativeGlDrawExternalTexture(
        JNIEnv* env, jobject, jint textureId, jfloatArray vertices,
        jfloat alpha, jfloat gray, jfloatArray textureMatrix, jint blendMode) {
    if (!vertices || env->GetArrayLength(vertices) < 16 ||
        !textureMatrix || env->GetArrayLength(textureMatrix) < 16) return;
    jfloat* data = env->GetFloatArrayElements(vertices, nullptr);
    jfloat* matrix = env->GetFloatArrayElements(textureMatrix, nullptr);
    if (!data || !matrix) {
        if (data) env->ReleaseFloatArrayElements(vertices, data, JNI_ABORT);
        if (matrix) env->ReleaseFloatArrayElements(textureMatrix, matrix, JNI_ABORT);
        return;
    }
    if (!gVertexBuffer || !gVertexArrayObject) {
        env->ReleaseFloatArrayElements(vertices, data, JNI_ABORT);
        env->ReleaseFloatArrayElements(textureMatrix, matrix, JNI_ABORT);
        return;
    }
    glBindVertexArray(gVertexArrayObject);
    glBindBuffer(GL_ARRAY_BUFFER, gVertexBuffer);
    glBufferSubData(GL_ARRAY_BUFFER, 0, 16 * sizeof(jfloat), data);
    glUseProgram(gExternalProgram);
    glEnableVertexAttribArray(static_cast<GLuint>(gExternalPosition));
    glVertexAttribPointer(static_cast<GLuint>(gExternalPosition), 2, GL_FLOAT, GL_FALSE,
                          4 * sizeof(jfloat), reinterpret_cast<const void*>(0));
    glEnableVertexAttribArray(static_cast<GLuint>(gExternalUv));
    glVertexAttribPointer(static_cast<GLuint>(gExternalUv), 2, GL_FLOAT, GL_FALSE,
                          4 * sizeof(jfloat), reinterpret_cast<const void*>(2 * sizeof(jfloat)));
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, static_cast<GLuint>(textureId));
    glUniform1i(gExternalSampler, 0);
    glUniform1f(gExternalAlpha, alpha);
    glUniform1f(gExternalGray, gray);
    glUniformMatrix4fv(gExternalMatrix, 1, GL_FALSE, matrix);
    applyNativeBlend(blendMode);
    glEnable(GL_BLEND);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisable(GL_BLEND);
    glDisableVertexAttribArray(static_cast<GLuint>(gExternalPosition));
    glDisableVertexAttribArray(static_cast<GLuint>(gExternalUv));
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    env->ReleaseFloatArrayElements(vertices, data, JNI_ABORT);
    env->ReleaseFloatArrayElements(textureMatrix, matrix, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kinetic_app_engine_NativeRenderEngine_nativeGlDrawComposition(
        JNIEnv* env, jobject, jintArray textureIds, jintArray flags,
        jfloatArray params, jfloatArray layerMatrices, jfloatArray textureMatrices,
        jfloatArray frameMetrics, jint layerCount) {
    if (!textureIds || !flags || !params || !layerMatrices ||
        !textureMatrices || !frameMetrics) return;
    const jsize count = static_cast<jsize>(layerCount);
    if (count <= 0 || count > 1024 ||
        env->GetArrayLength(textureIds) < count ||
        env->GetArrayLength(flags) < count ||
        env->GetArrayLength(params) < count * 4 ||
        env->GetArrayLength(layerMatrices) < count * 9 ||
        env->GetArrayLength(textureMatrices) < count * 16 ||
        env->GetArrayLength(frameMetrics) < 4 ||
        !gVertexBuffer || !gVertexArrayObject) return;

    jint* ids = env->GetIntArrayElements(textureIds, nullptr);
    jint* layerFlags = env->GetIntArrayElements(flags, nullptr);
    jfloat* layerParams = env->GetFloatArrayElements(params, nullptr);
    jfloat* transforms = env->GetFloatArrayElements(layerMatrices, nullptr);
    jfloat* textureTransforms = env->GetFloatArrayElements(textureMatrices, nullptr);
    jfloat* metrics = env->GetFloatArrayElements(frameMetrics, nullptr);
    if (!ids || !layerFlags || !layerParams || !transforms || !textureTransforms || !metrics) {
        if (ids) env->ReleaseIntArrayElements(textureIds, ids, JNI_ABORT);
        if (layerFlags) env->ReleaseIntArrayElements(flags, layerFlags, JNI_ABORT);
        if (layerParams) env->ReleaseFloatArrayElements(params, layerParams, JNI_ABORT);
        if (transforms) env->ReleaseFloatArrayElements(layerMatrices, transforms, JNI_ABORT);
        if (textureTransforms) env->ReleaseFloatArrayElements(textureMatrices, textureTransforms, JNI_ABORT);
        if (metrics) env->ReleaseFloatArrayElements(frameMetrics, metrics, JNI_ABORT);
        return;
    }

    const float viewportWidth = std::max(1.0f, metrics[0]);
    const float viewportHeight = std::max(1.0f, metrics[1]);
    const float compositionWidth = std::max(1.0f, metrics[2]);
    const float compositionHeight = std::max(1.0f, metrics[3]);
    const float scale = std::min(viewportWidth / compositionWidth,
                                 viewportHeight / compositionHeight);
    const float offsetX = (viewportWidth - compositionWidth * scale) * 0.5f;
    const float offsetY = (viewportHeight - compositionHeight * scale) * 0.5f;

    glBindVertexArray(gVertexArrayObject);
    glBindBuffer(GL_ARRAY_BUFFER, gVertexBuffer);
    for (jsize i = 0; i < count; ++i) {
        const jint mode = (layerFlags[i] >> 1) & 0x0F;
        const bool external = (layerFlags[i] & 1) != 0;
        const bool masked = (layerFlags[i] & (1 << 5)) != 0;
        const float alpha = std::clamp(layerParams[i * 4], 0.0f, 1.0f);
        if (alpha <= 0.0f || ids[i] <= 0) continue;
        const float gray = layerParams[i * 4 + 1] > 0.5f ? 1.0f : 0.0f;
        const float width = std::max(0.0f, layerParams[i * 4 + 2]);
        const float height = std::max(0.0f, layerParams[i * 4 + 3]);
        const float right = masked ? width * 0.8f : width;
        const float bottom = masked ? height * 0.8f : height;
        const float uvMax = masked ? 0.8f : 1.0f;
        const float* transform = transforms + static_cast<std::size_t>(i) * 9;
        const float* texMatrix = textureTransforms + static_cast<std::size_t>(i) * 16;

        const float sourceX[4] = {0.0f, 0.0f, right, right};
        const float sourceY[4] = {0.0f, bottom, 0.0f, bottom};
        const float uvX[4] = {0.0f, 0.0f, uvMax, uvMax};
        const float uvY[4] = {0.0f, uvMax, 0.0f, uvMax};
        float quad[16];
        for (int corner = 0; corner < 4; ++corner) {
            const float x = sourceX[corner];
            const float y = sourceY[corner];
            const float w = transform[6] * x + transform[7] * y + transform[8];
            const float safeW = std::abs(w) < 0.000001f ? 1.0f : w;
            const float mappedX = (transform[0] * x + transform[1] * y + transform[2]) / safeW;
            const float mappedY = (transform[3] * x + transform[4] * y + transform[5]) / safeW;
            const float screenX = offsetX + mappedX * scale;
            const float screenY = offsetY + mappedY * scale;
            const int vertex = corner * 4;
            quad[vertex] = (screenX / viewportWidth) * 2.0f - 1.0f;
            quad[vertex + 1] = 1.0f - (screenY / viewportHeight) * 2.0f;
            quad[vertex + 2] = uvX[corner];
            quad[vertex + 3] = uvY[corner];
        }

        if (external) {
            if (!gExternalProgram) continue;
            glUseProgram(gExternalProgram);
            glBufferSubData(GL_ARRAY_BUFFER, 0, sizeof(quad), quad);
            glEnableVertexAttribArray(static_cast<GLuint>(gExternalPosition));
            glVertexAttribPointer(static_cast<GLuint>(gExternalPosition), 2, GL_FLOAT, GL_FALSE,
                                  4 * sizeof(float), reinterpret_cast<const void*>(0));
            glEnableVertexAttribArray(static_cast<GLuint>(gExternalUv));
            glVertexAttribPointer(static_cast<GLuint>(gExternalUv), 2, GL_FLOAT, GL_FALSE,
                                  4 * sizeof(float), reinterpret_cast<const void*>(2 * sizeof(float)));
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_EXTERNAL_OES, static_cast<GLuint>(ids[i]));
            glUniform1i(gExternalSampler, 0);
            glUniform1f(gExternalAlpha, alpha);
            glUniform1f(gExternalGray, gray);
            glUniformMatrix4fv(gExternalMatrix, 1, GL_FALSE, texMatrix);
            applyNativeBlend(mode);
            glEnable(GL_BLEND);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            glDisable(GL_BLEND);
            glDisableVertexAttribArray(static_cast<GLuint>(gExternalPosition));
            glDisableVertexAttribArray(static_cast<GLuint>(gExternalUv));
        } else {
            if (!gTextureProgram) continue;
            glUseProgram(gTextureProgram);
            glBufferSubData(GL_ARRAY_BUFFER, 0, sizeof(quad), quad);
            glEnableVertexAttribArray(static_cast<GLuint>(gTexturePosition));
            glVertexAttribPointer(static_cast<GLuint>(gTexturePosition), 2, GL_FLOAT, GL_FALSE,
                                  4 * sizeof(float), reinterpret_cast<const void*>(0));
            glEnableVertexAttribArray(static_cast<GLuint>(gTextureUv));
            glVertexAttribPointer(static_cast<GLuint>(gTextureUv), 2, GL_FLOAT, GL_FALSE,
                                  4 * sizeof(float), reinterpret_cast<const void*>(2 * sizeof(float)));
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(ids[i]));
            glUniform1i(gTextureSampler, 0);
            glUniform1f(gTextureAlpha, alpha);
            glUniform1f(gTextureGray, gray);
            applyNativeBlend(mode);
            glEnable(GL_BLEND);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            glDisable(GL_BLEND);
            glDisableVertexAttribArray(static_cast<GLuint>(gTexturePosition));
            glDisableVertexAttribArray(static_cast<GLuint>(gTextureUv));
        }
    }
    glBindTexture(GL_TEXTURE_2D, 0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindVertexArray(0);

    env->ReleaseIntArrayElements(textureIds, ids, JNI_ABORT);
    env->ReleaseIntArrayElements(flags, layerFlags, JNI_ABORT);
    env->ReleaseFloatArrayElements(params, layerParams, JNI_ABORT);
    env->ReleaseFloatArrayElements(layerMatrices, transforms, JNI_ABORT);
    env->ReleaseFloatArrayElements(textureMatrices, textureTransforms, JNI_ABORT);
    env->ReleaseFloatArrayElements(frameMetrics, metrics, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kinetic_app_engine_NativeRenderEngine_nativeGlDrawSelection(
        JNIEnv* env, jobject, jfloatArray vertices) {
    if (!vertices || env->GetArrayLength(vertices) < 8 || !gSolidProgram) return;
    jfloat* data = env->GetFloatArrayElements(vertices, nullptr);
    if (!data) return;
    if (!gVertexBuffer || !gVertexArrayObject) {
        env->ReleaseFloatArrayElements(vertices, data, JNI_ABORT);
        return;
    }
    glBindVertexArray(gVertexArrayObject);
    glBindBuffer(GL_ARRAY_BUFFER, gVertexBuffer);
    glBufferSubData(GL_ARRAY_BUFFER, 0, 8 * sizeof(jfloat), data);
    glUseProgram(gSolidProgram);
    glEnableVertexAttribArray(static_cast<GLuint>(gSolidPosition));
    glVertexAttribPointer(static_cast<GLuint>(gSolidPosition), 2, GL_FLOAT, GL_FALSE, 0,
                          reinterpret_cast<const void*>(0));
    glUniform4f(gSolidColor, 0.52f, 0.40f, 1.f, 1.f);
    glLineWidth(2.f);
    glDrawArrays(GL_LINE_LOOP, 0, 4);
    glDisableVertexAttribArray(static_cast<GLuint>(gSolidPosition));
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    env->ReleaseFloatArrayElements(vertices, data, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kinetic_app_engine_NativeRenderEngine_nativeGlRelease(JNIEnv*, jobject) {
    if (gTextureProgram) glDeleteProgram(gTextureProgram);
    if (gExternalProgram) glDeleteProgram(gExternalProgram);
    releaseCompositionTarget();
    if (gSolidProgram) glDeleteProgram(gSolidProgram);
    if (gVertexBuffer) glDeleteBuffers(1, &gVertexBuffer);
    if (gVertexArrayObject) glDeleteVertexArrays(1, &gVertexArrayObject);
    gVertexBuffer = 0;
    gVertexArrayObject = 0;
    gTextureProgram = gExternalProgram = gSolidProgram = 0;
}
