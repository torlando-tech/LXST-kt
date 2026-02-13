/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

#include <jni.h>
#include <android/log.h>
#include "oboe_playback_engine.h"

#define LOG_TAG "LXST:OboeJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Singleton engine — one playback stream at a time (matches Telephone lifecycle)
static OboePlaybackEngine* sEngine = nullptr;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_tech_torlando_lxst_audio_NativePlaybackEngine_nativeCreate(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jint sampleRate,
        jint channels,
        jint frameSamples,
        jint maxBufferFrames,
        jint prebufferFrames) {

    if (sEngine) {
        sEngine->destroy();
        delete sEngine;
    }

    sEngine = new OboePlaybackEngine();
    return static_cast<jboolean>(
        sEngine->create(sampleRate, channels, frameSamples,
                        maxBufferFrames, prebufferFrames));
}

JNIEXPORT jboolean JNICALL
Java_tech_torlando_lxst_audio_NativePlaybackEngine_nativeWriteSamples(
        JNIEnv* env,
        jobject /*thiz*/,
        jshortArray samples) {

    if (!sEngine) {
        LOGE("nativeWriteSamples: engine not created");
        return JNI_FALSE;
    }

    jint len = env->GetArrayLength(samples);
    jshort* data = env->GetShortArrayElements(samples, nullptr);
    if (!data) return JNI_FALSE;

    bool ok = sEngine->writeSamples(data, len);
    env->ReleaseShortArrayElements(samples, data, JNI_ABORT);
    return static_cast<jboolean>(ok);
}

JNIEXPORT jboolean JNICALL
Java_tech_torlando_lxst_audio_NativePlaybackEngine_nativeStartStream(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {

    if (!sEngine) {
        LOGE("nativeStartStream: engine not created");
        return JNI_FALSE;
    }

    return static_cast<jboolean>(sEngine->startStream());
}

JNIEXPORT void JNICALL
Java_tech_torlando_lxst_audio_NativePlaybackEngine_nativeStopStream(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {

    if (sEngine) {
        sEngine->stopStream();
    }
}

JNIEXPORT void JNICALL
Java_tech_torlando_lxst_audio_NativePlaybackEngine_nativeDestroy(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {

    if (sEngine) {
        sEngine->destroy();
        delete sEngine;
        sEngine = nullptr;
    }
}

JNIEXPORT jint JNICALL
Java_tech_torlando_lxst_audio_NativePlaybackEngine_nativeGetBufferedFrameCount(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {

    return sEngine ? sEngine->getBufferedFrameCount() : 0;
}

JNIEXPORT jboolean JNICALL
Java_tech_torlando_lxst_audio_NativePlaybackEngine_nativeIsPlaying(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {

    return sEngine ? static_cast<jboolean>(sEngine->isPlaying()) : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_tech_torlando_lxst_audio_NativePlaybackEngine_nativeGetXRunCount(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {

    return sEngine ? sEngine->getXRunCount() : 0;
}

} // extern "C"
