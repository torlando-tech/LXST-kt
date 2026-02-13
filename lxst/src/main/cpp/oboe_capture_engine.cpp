/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "oboe_capture_engine.h"
#include <android/log.h>
#include <cstring>

#define LOG_TAG "LXST:OboeCaptureEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

OboeCaptureEngine::OboeCaptureEngine() = default;

OboeCaptureEngine::~OboeCaptureEngine() {
    destroy();
}

bool OboeCaptureEngine::create(int sampleRate, int channels, int frameSamples,
                               int maxBufferFrames, bool enableFilters) {
    if (isCreated_.load()) {
        LOGW("Engine already created, destroying first");
        destroy();
    }

    sampleRate_ = sampleRate;
    channels_ = channels;
    frameSamples_ = frameSamples;

    ringBuffer_ = std::make_unique<PacketRingBuffer>(maxBufferFrames, frameSamples);
    accumBuffer_ = std::make_unique<int16_t[]>(frameSamples);
    accumCount_ = 0;

    if (enableFilters) {
        filterChain_ = std::make_unique<VoiceFilterChain>(
            channels,
            300.0f,    // HP cutoff: remove rumble/hum
            3400.0f,   // LP cutoff: voice band limit
            -12.0f,    // AGC target dBFS
            12.0f      // AGC max gain dB
        );
    }

    isCreated_.store(true);
    LOGI("Created: rate=%d ch=%d frameSamples=%d maxBuf=%d filters=%s",
         sampleRate, channels, frameSamples, maxBufferFrames,
         enableFilters ? "on" : "off");
    return true;
}

bool OboeCaptureEngine::startStream() {
    if (!isCreated_.load()) {
        LOGE("Cannot start: engine not created");
        return false;
    }

    if (isRecording_.load()) {
        LOGW("Stream already recording");
        return true;
    }

    return openStream();
}

void OboeCaptureEngine::stopStream() {
    isRecording_.store(false);
    closeStream();
}

void OboeCaptureEngine::destroy() {
    stopStream();
    ringBuffer_.reset();
    accumBuffer_.reset();
    filterChain_.reset();
    accumCount_ = 0;
    isCreated_.store(false);
    LOGI("Destroyed");
}

bool OboeCaptureEngine::readSamples(int16_t* dest, int count) {
    if (!ringBuffer_) return false;
    return ringBuffer_->read(dest, count);
}

int OboeCaptureEngine::getBufferedFrameCount() const {
    return ringBuffer_ ? ringBuffer_->availableFrames() : 0;
}

bool OboeCaptureEngine::isRecording() const {
    return isRecording_.load();
}

int OboeCaptureEngine::getXRunCount() const {
    if (!stream_) return 0;
    auto result = stream_->getXRunCount();
    return (result.value() > 0) ? result.value() : 0;
}

// --- Oboe stream management ---

bool OboeCaptureEngine::openStream() {
    oboe::AudioStreamBuilder builder;

    builder.setDirection(oboe::Direction::Input)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Exclusive)
           ->setFormat(oboe::AudioFormat::I16)
           ->setSampleRate(sampleRate_)
           ->setChannelCount(channels_)
           ->setInputPreset(oboe::InputPreset::VoiceCommunication)
           ->setDataCallback(this)
           ->setErrorCallback(this);

    oboe::Result result = builder.openStream(stream_);

    if (result != oboe::Result::OK) {
        LOGE("Failed to open input stream: %s", oboe::convertToText(result));
        return false;
    }

    LOGI("Input stream opened: API=%s, rate=%d, ch=%d, framesPerBurst=%d, bufferCapacity=%d",
         stream_->getAudioApi() == oboe::AudioApi::AAudio ? "AAudio" : "OpenSLES",
         stream_->getSampleRate(),
         stream_->getChannelCount(),
         stream_->getFramesPerBurst(),
         stream_->getBufferCapacityInFrames());

    result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start input stream: %s", oboe::convertToText(result));
        closeStream();
        return false;
    }

    isRecording_.store(true);
    accumCount_ = 0;
    LOGI("Input stream started");
    return true;
}

void OboeCaptureEngine::closeStream() {
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
        LOGI("Input stream closed");
    }
}

// --- Oboe audio callback (runs on SCHED_FIFO thread) ---

oboe::DataCallbackResult OboeCaptureEngine::onAudioReady(
        oboe::AudioStream* /*stream*/,
        void* audioData,
        int32_t numFrames) {

    auto* input = static_cast<int16_t*>(audioData);
    int32_t totalSamples = numFrames * channels_;
    int32_t processed = 0;

    // Accumulate callback data into LXST-sized frames.
    // Oboe callbacks may deliver variable-size bursts (e.g., 192 samples)
    // that don't align with LXST frame size (e.g., 960 samples for 20ms).
    while (processed < totalSamples) {
        int remaining = totalSamples - processed;
        int needed = frameSamples_ - accumCount_;
        int toCopy = (remaining < needed) ? remaining : needed;

        std::memcpy(accumBuffer_.get() + accumCount_, input + processed,
                     sizeof(int16_t) * toCopy);
        accumCount_ += toCopy;
        processed += toCopy;

        if (accumCount_ == frameSamples_) {
            // Full LXST frame accumulated — apply filters and write to ring buffer
            if (filterChain_) {
                filterChain_->process(accumBuffer_.get(), frameSamples_, sampleRate_);
            }

            if (!ringBuffer_->write(accumBuffer_.get(), frameSamples_)) {
                // Ring buffer full — drop oldest frame and retry
                // (Kotlin consumer is too slow; drop stale audio)
                int16_t discard[1]; // read() just advances the index
                ringBuffer_->read(discard, frameSamples_);
                ringBuffer_->write(accumBuffer_.get(), frameSamples_);
            }

            accumCount_ = 0;
        }
    }

    return isRecording_.load(std::memory_order_relaxed)
        ? oboe::DataCallbackResult::Continue
        : oboe::DataCallbackResult::Stop;
}

// --- Oboe error callback (stream disconnect recovery) ---

void OboeCaptureEngine::onErrorAfterClose(
        oboe::AudioStream* /*stream*/,
        oboe::Result error) {
    LOGW("Input stream error: %s — attempting restart", oboe::convertToText(error));

    if (isRecording_.load()) {
        openStream();
    }
}
