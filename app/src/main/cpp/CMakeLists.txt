cmake_minimum_required(VERSION 3.22.1)
project(kinetic_engine LANGUAGES CXX)

add_library(kinetic_engine SHARED
    kinetic_engine.cpp
    native_video_decoder.cpp
)

find_library(log-lib log)

target_compile_features(kinetic_engine PRIVATE cxx_std_17)
target_compile_options(kinetic_engine PRIVATE -O3 -fno-exceptions -fno-rtti)
target_link_libraries(kinetic_engine ${log-lib})

find_library(android-lib android)
find_library(mediandk-lib mediandk)
find_library(glesv2-lib GLESv2)
target_link_libraries(kinetic_engine ${android-lib} ${mediandk-lib} ${glesv2-lib})
