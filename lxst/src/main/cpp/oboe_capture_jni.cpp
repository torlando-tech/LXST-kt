/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

#include <jni.h>
#include <android/log.h>
#include "oboe_capture_engine.h"

#define LOG_TAG "LXST:OboeCaptureJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Singleton engine — one capture stream at a time (matches Telephone lifecycle)
static OboeCaptureEngine* sCaptureEngine = nullptr;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_tech_torlando_lxst_audio_NativeCaptureEngine_nativeCreate(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jint sampleRate,
        jint channels,
        jint frameSamples,
        jint maxBufferFrames,
        jboolean enableFilters) {

    if (sCaptureEngine) {
        sCaptureEngine->destroy();
        delete sCaptureEngine;
    }

    sCaptureEngine = new OboeCaptureEngine();
    return static_cast<jboolean>(
        sCaptureEngine->create(sampleRate, channels, frameSamples,
                               maxBufferFrames, enableFilters));
}

JNIEXPORT jboolean JNICALL
Java_tech_torlando_lxst_audio_NativeCaptureEngine_nativeReadSamples(
        JNIEnv* env,
        jobject /*thiz*/,
        jshortArray dest) {

    if (!sCaptureEngine) {
        LOGE("nativeReadSamples: engine not created");
        return JNI_FALSE;
    }

    jint len = env->GetArrayLength(dest);
    jshort* data = env->GetShortArrayElements(dest, nullptr);
    if (!data) return JNI_FALSE;

    bool ok = sCaptureEngine->readSamples(data, len);
    // Use 0 (copy back) on success, JNI_ABORT (discard) on failure
    env->ReleaseShortArrayElements(dest, data, ok ? 0 : JNI_ABORT);
    return static_cast<jboolean>(ok);
}

JNIEXPORT jboolean JNICALL
Java_tech_torlando_lxst_audio_NativeCaptureEngine_nativeStartStream(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {

    if (!sCaptureEngine) {
        LOGE("nativeStartStream: engine not created");
        return JNI_FALSE;
    }

    return static_cast<jboolean>(sCaptureEngine->startStream());
}

JNIEXPORT void JNICALL
Java_tech_torlando_lxst_audio_NativeCaptureEngine_nativeStopStream(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {

    if (sCaptureEngine) {
        sCaptureEngine->stopStream();
    }
}

JNIEXPORT void JNICALL
Java_tech_torlando_lxst_audio_NativeCaptureEngine_nativeDestroy(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {

    if (sCaptureEngine) {
        sCaptureEngine->destroy();
        delete sCaptureEngine;
        sCaptureEngine = nullptr;
    }
}

JNIEXPORT jint JNICALL
Java_tech_torlando_lxst_audio_NativeCaptureEngine_nativeGetBufferedFrameCount(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {

    return sCaptureEngine ? sCaptureEngine->getBufferedFrameCount() : 0;
}

JNIEXPORT jboolean JNICALL
Java_tech_torlando_lxst_audio_NativeCaptureEngine_nativeIsRecording(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {

    return sCaptureEngine ? static_cast<jboolean>(sCaptureEngine->isRecording()) : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_tech_torlando_lxst_audio_NativeCaptureEngine_nativeGetXRunCount(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {

    return sCaptureEngine ? sCaptureEngine->getXRunCount() : 0;
}

} // extern "C"
