cmake_minimum_required(VERSION 3.22.1)
project(p3dof_bridge)

# Viture SDK (headers + prebuilt libglasses.so) lives in <repo>/SDK
set(VSDK "${CMAKE_CURRENT_SOURCE_DIR}/../../../../SDK")

add_library(glasses SHARED IMPORTED)
set_target_properties(glasses PROPERTIES
    IMPORTED_LOCATION "${VSDK}/android/${ANDROID_ABI}/libglasses.so")

add_library(p3dof_bridge SHARED p3dof_bridge.cpp)
target_include_directories(p3dof_bridge PRIVATE "${VSDK}/include")
target_link_options(p3dof_bridge PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(p3dof_bridge glasses android log)

# ---- libffmpegJNI: TrueHD / MLP / DTS decoding (optional) -----------------------------------------------------------
# Built only if tools/build_ffmpeg.sh has produced the FFmpeg static libraries; without them the app still builds and
# plays TrueHD / DTS by passthrough. ffmpeg_jni.cc is the JNI glue from AndroidX Media3 (Apache-2.0).
set(FFMPEG_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/ffmpeg")
set(FFMPEG_LIBS "${FFMPEG_DIR}/android-libs/${ANDROID_ABI}")
if(EXISTS "${FFMPEG_LIBS}/libavcodec.a" AND EXISTS "${FFMPEG_LIBS}/libavutil.a" AND EXISTS "${FFMPEG_LIBS}/libswresample.a")
    foreach(lib avutil swresample avcodec)
        add_library(${lib} STATIC IMPORTED)
        set_target_properties(${lib} PROPERTIES IMPORTED_LOCATION "${FFMPEG_LIBS}/lib${lib}.a")
    endforeach()
    add_library(ffmpegJNI SHARED ffmpeg_jni.cc)
    target_include_directories(ffmpegJNI PRIVATE "${FFMPEG_DIR}")
    target_link_options(ffmpegJNI PRIVATE "-Wl,-z,max-page-size=16384")
    target_link_libraries(ffmpegJNI PRIVATE android swresample avcodec avutil log)
else()
    message(STATUS "FFmpeg libraries not built (tools/build_ffmpeg.sh): libffmpegJNI skipped")
endif()
