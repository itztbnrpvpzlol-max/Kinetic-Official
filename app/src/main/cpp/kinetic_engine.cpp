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


namespace {
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
    glUseProgram(program);
    glEnableVertexAttribArray(static_cast<GLuint>(positionHandle));
    glVertexAttribPointer(static_cast<GLuint>(positionHandle), 2, GL_FLOAT, GL_FALSE,
                          4 * sizeof(jfloat), vertices);
    glEnableVertexAttribArray(static_cast<GLuint>(uvHandle));
    glVertexAttribPointer(static_cast<GLuint>(uvHandle), 2, GL_FLOAT, GL_FALSE,
                          4 * sizeof(jfloat), vertices + 2);
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
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kinetic_app_engine_NativeRenderEngine_nativeGlInit(JNIEnv*, jobject) {
    // KRE requires an actual ES 3.x context. Do not initialize against a
    // silently downgraded ES 2 context.
    GLint glMajor = 0;
    glGetIntegerv(GL_MAJOR_VERSION, &glMajor);
    if (glMajor < 3) return JNI_FALSE;

    static const char* vertex =
        "attribute vec2 aPosition; attribute vec2 aTexCoord; varying vec2 vTexCoord;"
        "void main(){ gl_Position=vec4(aPosition,0.0,1.0); vTexCoord=aTexCoord; }";
    static const char* textureFragment =
        "precision mediump float; uniform sampler2D uTexture; uniform float uAlpha;"
        "uniform float uGray; varying vec2 vTexCoord;"
        "void main(){ vec4 c=texture2D(uTexture,vTexCoord);"
        "if(uGray>0.5){ float y=dot(c.rgb,vec3(0.299,0.587,0.114)); c.rgb=vec3(y); }"
        "gl_FragColor=vec4(c.rgb*uAlpha,c.a*uAlpha); }";
    static const char* externalFragment =
        "#extension GL_OES_EGL_image_external : require\n"
        "precision mediump float; uniform samplerExternalOES uTexture; uniform float uAlpha;"
        "uniform float uGray; uniform mat4 uTexMatrix; varying vec2 vTexCoord;"
        "void main(){ vec2 uv=(uTexMatrix*vec4(vTexCoord,0.0,1.0)).xy;"
        "vec4 c=texture2D(uTexture,uv);"
        "if(uGray>0.5){ float y=dot(c.rgb,vec3(0.299,0.587,0.114)); c.rgb=vec3(y); }"
        "gl_FragColor=vec4(c.rgb*uAlpha,c.a*uAlpha); }";
    static const char* solidFragment =
        "precision mediump float; uniform vec4 uColor; void main(){ gl_FragColor=uColor; }";

    gTextureProgram = linkGlProgram(vertex, textureFragment);
    gExternalProgram = linkGlProgram(vertex, externalFragment);
    static const char* solidVertex =
        "attribute vec2 aPosition; void main(){ gl_Position=vec4(aPosition,0.0,1.0); }";
    gSolidProgram = linkGlProgram(solidVertex, solidFragment);
    if (!gTextureProgram || !gExternalProgram || !gSolidProgram) return JNI_FALSE;

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
    glUseProgram(gExternalProgram);
    glEnableVertexAttribArray(static_cast<GLuint>(gExternalPosition));
    glVertexAttribPointer(static_cast<GLuint>(gExternalPosition), 2, GL_FLOAT, GL_FALSE,
                          4 * sizeof(jfloat), data);
    glEnableVertexAttribArray(static_cast<GLuint>(gExternalUv));
    glVertexAttribPointer(static_cast<GLuint>(gExternalUv), 2, GL_FLOAT, GL_FALSE,
                          4 * sizeof(jfloat), data + 2);
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
    env->ReleaseFloatArrayElements(vertices, data, JNI_ABORT);
    env->ReleaseFloatArrayElements(textureMatrix, matrix, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kinetic_app_engine_NativeRenderEngine_nativeGlDrawSelection(
        JNIEnv* env, jobject, jfloatArray vertices) {
    if (!vertices || env->GetArrayLength(vertices) < 8 || !gSolidProgram) return;
    jfloat* data = env->GetFloatArrayElements(vertices, nullptr);
    if (!data) return;
    glUseProgram(gSolidProgram);
    glEnableVertexAttribArray(static_cast<GLuint>(gSolidPosition));
    glVertexAttribPointer(static_cast<GLuint>(gSolidPosition), 2, GL_FLOAT, GL_FALSE, 0, data);
    glUniform4f(gSolidColor, 0.52f, 0.40f, 1.f, 1.f);
    glLineWidth(2.f);
    glDrawArrays(GL_LINE_LOOP, 0, 4);
    glDisableVertexAttribArray(static_cast<GLuint>(gSolidPosition));
    env->ReleaseFloatArrayElements(vertices, data, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kinetic_app_engine_NativeRenderEngine_nativeGlRelease(JNIEnv*, jobject) {
    if (gTextureProgram) glDeleteProgram(gTextureProgram);
    if (gExternalProgram) glDeleteProgram(gExternalProgram);
    if (gSolidProgram) glDeleteProgram(gSolidProgram);
    gTextureProgram = gExternalProgram = gSolidProgram = 0;
}
