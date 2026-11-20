cmake_minimum_required(VERSION 3.22.1)
project(kinetic_engine LANGUAGES CXX)

set(FFMPEG_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg/prebuilt/${ANDROID_ABI}")
foreach(required_file
    "${FFMPEG_ROOT}/include/libavcodec/avcodec.h"
    "${FFMPEG_ROOT}/include/libavcodec/mediacodec.h"
    "${FFMPEG_ROOT}/libavformat.so"
    "${FFMPEG_ROOT}/libavcodec.so"
    "${FFMPEG_ROOT}/libswscale.so"
    "${FFMPEG_ROOT}/libavutil.so")
    if(NOT EXISTS "${required_file}")
        message(FATAL_ERROR
            "Missing bundled FFmpeg build: ${required_file}. Run app/src/main/cpp/build-ffmpeg-android.sh for ${ANDROID_ABI} before Gradle.")
    endif()
endforeach()

add_library(kinetic_engine SHARED
    kinetic_engine.cpp
    ffmpeg_decoder.cpp
    kre_engine.cpp
)

foreach(ffmpeg_library avformat avcodec swscale avutil)
    add_library(ffmpeg_${ffmpeg_library} SHARED IMPORTED GLOBAL)
    set_target_properties(ffmpeg_${ffmpeg_library} PROPERTIES
        IMPORTED_LOCATION "${FFMPEG_ROOT}/lib${ffmpeg_library}.so")
endforeach()

target_include_directories(kinetic_engine PRIVATE "${FFMPEG_ROOT}/include")
find_library(log-lib log)
find_library(android-lib android)
find_library(glesv3-lib GLESv3)
find_library(mediandk-lib mediandk)
if(NOT mediandk-lib)
    message(FATAL_ERROR "Android MediaCodec NDK library (mediandk) is required by the FFmpeg build.")
endif()
find_library(z-lib z)
find_library(dl-lib dl)
find_library(m-lib m)
find_library(atomic-lib atomic)

target_compile_features(kinetic_engine PRIVATE cxx_std_17)
target_compile_options(kinetic_engine PRIVATE -O3 -fno-exceptions -fno-rtti)
target_link_options(kinetic_engine PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(kinetic_engine
    ffmpeg_avformat
    ffmpeg_avcodec
    ffmpeg_swscale
    ffmpeg_avutil
    ${log-lib}
    ${android-lib}
    ${glesv3-lib}
    ${mediandk-lib}
    ${z-lib}
    ${dl-lib}
    ${m-lib}
)

if(atomic-lib)
    target_link_libraries(kinetic_engine ${atomic-lib})
endif()
