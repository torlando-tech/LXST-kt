cmake_minimum_required(VERSION 3.22)
project(lxst_native C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- Oboe (via Prefab from AAR) ------------------------------------------
find_package(oboe REQUIRED CONFIG)

# --- Prebuilt libcodec2 (LGPL-2.1) --------------------------------------
add_library(codec2 SHARED IMPORTED)
set_target_properties(codec2 PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libcodec2.so
)

# --- Prebuilt libopus (BSD-3-Clause) -------------------------------------
add_library(opus SHARED IMPORTED)
set_target_properties(opus PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libopus.so
)

# --- lxst_codec2_jni (MIT) -----------------------------------------------
add_library(lxst_codec2_jni SHARED lxst_codec2_jni.c)
target_include_directories(lxst_codec2_jni PRIVATE ${CMAKE_SOURCE_DIR})
target_link_libraries(lxst_codec2_jni codec2 log)

# --- lxst_opus_jni (MIT) -------------------------------------------------
add_library(lxst_opus_jni SHARED lxst_opus_jni.c)
target_include_directories(lxst_opus_jni PRIVATE ${CMAKE_SOURCE_DIR})
target_link_libraries(lxst_opus_jni opus log)

# --- lxst_playback_engine (MPL-2.0) — Oboe playback with SPSC ring buffer + native codec
add_library(lxst_playback_engine SHARED
    oboe_playback_engine.cpp
    oboe_playback_jni.cpp
    packet_ring_buffer.cpp
    codec_wrapper.cpp
)
target_include_directories(lxst_playback_engine PRIVATE ${CMAKE_SOURCE_DIR})
target_link_libraries(lxst_playback_engine oboe::oboe opus codec2 log)

# --- lxst_capture_engine (MPL-2.0) — Oboe capture with native filters + SPSC ring buffer + native codec
add_library(lxst_capture_engine SHARED
    oboe_capture_engine.cpp
    oboe_capture_jni.cpp
    native_audio_filters.cpp
    packet_ring_buffer.cpp
    codec_wrapper.cpp
    encoded_ring_buffer.cpp
)
target_include_directories(lxst_capture_engine PRIVATE ${CMAKE_SOURCE_DIR})
target_link_libraries(lxst_capture_engine oboe::oboe opus codec2 log)
