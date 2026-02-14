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

// --- Phase 3: Native codec JNI methods ---

JNIEXPORT jboolean JNICALL
Java_tech_torlando_lxst_audio_NativeCaptureEngine_nativeConfigureEncoder(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jint codecType,
        jint sampleRate,
        jint channels,
        jint opusApp,
        jint opusBitrate,
        jint opusComplexity,
        jint codec2Mode) {

    if (!sCaptureEngine) {
        LOGE("nativeConfigureEncoder: engine not created");
        return JNI_FALSE;
    }

    return static_cast<jboolean>(
        sCaptureEngine->configureEncoder(codecType, sampleRate, channels,
                                         opusApp, opusBitrate, opusComplexity,
                                         codec2Mode));
}

JNIEXPORT jint JNICALL
Java_tech_torlando_lxst_audio_NativeCaptureEngine_nativeReadEncodedPacket(
        JNIEnv* env,
        jobject /*thiz*/,
        jbyteArray dest) {

    if (!sCaptureEngine) {
        LOGE("nativeReadEncodedPacket: engine not created");
        return 0;
    }

    jint maxLen = env->GetArrayLength(dest);
    jbyte* data = env->GetByteArrayElements(dest, nullptr);
    if (!data) return 0;

    int actualLength = 0;
    bool ok = sCaptureEngine->readEncodedPacket(
        reinterpret_cast<uint8_t*>(data), maxLen, &actualLength);

    // Copy back on success, discard on failure
    env->ReleaseByteArrayElements(dest, data, ok ? 0 : JNI_ABORT);
    return ok ? actualLength : 0;
}

JNIEXPORT void JNICALL
Java_tech_torlando_lxst_audio_NativeCaptureEngine_nativeSetCaptureMute(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jboolean mute) {

    if (sCaptureEngine) {
        sCaptureEngine->setCaptureMute(mute);
    }
}

JNIEXPORT void JNICALL
Java_tech_torlando_lxst_audio_NativeCaptureEngine_nativeDestroyEncoder(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {

    if (sCaptureEngine) {
        sCaptureEngine->destroyEncoder();
    }
}

} // extern "C"
