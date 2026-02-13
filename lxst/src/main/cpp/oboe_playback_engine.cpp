/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "oboe_playback_engine.h"
#include <android/log.h>
#include <cstring>

#define LOG_TAG "LXST:OboeEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

OboePlaybackEngine::OboePlaybackEngine() = default;

OboePlaybackEngine::~OboePlaybackEngine() {
    destroy();
}

bool OboePlaybackEngine::create(int sampleRate, int channels, int frameSamples,
                                 int maxBufferFrames, int prebufferFrames) {
    if (isCreated_.load()) {
        LOGW("Engine already created, destroying first");
        destroy();
    }

    sampleRate_ = sampleRate;
    channels_ = channels;
    frameSamples_ = frameSamples;
    prebufferFrames_ = prebufferFrames;

    ringBuffer_ = std::make_unique<PacketRingBuffer>(maxBufferFrames, frameSamples);
    callbackBuffer_ = std::make_unique<int16_t[]>(frameSamples);
    dropBuffer_ = std::make_unique<int16_t[]>(frameSamples);
    callbackBufferOffset_ = 0;
    callbackBufferValid_ = 0;

    isCreated_.store(true);
    LOGI("Created: rate=%d ch=%d frameSamples=%d maxBuf=%d prebuf=%d",
         sampleRate, channels, frameSamples, maxBufferFrames, prebufferFrames);
    return true;
}

bool OboePlaybackEngine::writeSamples(const int16_t* samples, int count) {
    if (!ringBuffer_) return false;

    if (!ringBuffer_->write(samples, count)) {
        // Buffer full — drop oldest frame and retry.
        // Use dropBuffer_ (not callbackBuffer_ which holds partial frame state
        // for the audio callback thread).
        ringBuffer_->read(dropBuffer_.get(), count);
        ringBuffer_->write(samples, count);
        return false;  // Signal that a drop occurred
    }
    return true;
}

bool OboePlaybackEngine::startStream() {
    if (!isCreated_.load()) {
        LOGE("Cannot start: engine not created");
        return false;
    }

    if (isPlaying_.load()) {
        LOGW("Stream already playing");
        return true;
    }

    return openStream();
}

void OboePlaybackEngine::stopStream() {
    isPlaying_.store(false);
    closeStream();
}

void OboePlaybackEngine::destroy() {
    stopStream();
    ringBuffer_.reset();
    callbackBuffer_.reset();
    dropBuffer_.reset();
    callbackBufferOffset_ = 0;
    callbackBufferValid_ = 0;
    isCreated_.store(false);
    LOGI("Destroyed");
}

int OboePlaybackEngine::getBufferedFrameCount() const {
    return ringBuffer_ ? ringBuffer_->availableFrames() : 0;
}

int OboePlaybackEngine::getXRunCount() const {
    if (!stream_) return 0;
    auto result = stream_->getXRunCount();
    return (result.value() > 0) ? result.value() : 0;
}

// --- Oboe stream management ---

bool OboePlaybackEngine::openStream() {
    oboe::AudioStreamBuilder builder;

    builder.setDirection(oboe::Direction::Output)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Exclusive)
           ->setFormat(oboe::AudioFormat::I16)
           ->setSampleRate(sampleRate_)
           ->setChannelCount(channels_)
           ->setUsage(oboe::Usage::VoiceCommunication)
           ->setContentType(oboe::ContentType::Speech)
           ->setDataCallback(this)
           ->setErrorCallback(this);

    oboe::Result result = builder.openStream(stream_);

    if (result != oboe::Result::OK) {
        LOGE("Failed to open stream: %s", oboe::convertToText(result));
        return false;
    }

    LOGI("Stream opened: API=%s, rate=%d, ch=%d, framesPerBurst=%d, bufferCapacity=%d",
         stream_->getAudioApi() == oboe::AudioApi::AAudio ? "AAudio" : "OpenSLES",
         stream_->getSampleRate(),
         stream_->getChannelCount(),
         stream_->getFramesPerBurst(),
         stream_->getBufferCapacityInFrames());

    // Set buffer size to 2x burst for low latency while avoiding underruns
    auto burstSize = stream_->getFramesPerBurst();
    stream_->setBufferSizeInFrames(burstSize * 2);

    result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start stream: %s", oboe::convertToText(result));
        closeStream();
        return false;
    }

    isPlaying_.store(true);
    LOGI("Stream started");
    return true;
}

void OboePlaybackEngine::closeStream() {
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
        LOGI("Stream closed");
    }
}

// --- Oboe audio callback (runs on SCHED_FIFO thread) ---

oboe::DataCallbackResult OboePlaybackEngine::onAudioReady(
        oboe::AudioStream* /*stream*/,
        void* audioData,
        int32_t numFrames) {

    auto* output = static_cast<int16_t*>(audioData);
    int32_t samplesWritten = 0;
    int32_t totalSamples = numFrames * channels_;

    // Fill the output buffer from LXST frames.
    //
    // Oboe's burst size often differs from our LXST frame size. For example,
    // burst=192 samples (4ms) while LXST frame=960 samples (20ms). We must
    // track a partial frame across callbacks: read one LXST frame from the
    // ring buffer and serve it over multiple callbacks until fully consumed.
    // This is the inverse of the capture engine's accumBuffer_ pattern.
    while (samplesWritten < totalSamples && ringBuffer_) {
        int remaining = totalSamples - samplesWritten;

        // 1) Drain any leftover samples from a partially-consumed LXST frame.
        if (callbackBufferValid_ > 0) {
            int available = callbackBufferValid_ - callbackBufferOffset_;
            int toCopy = (remaining < available) ? remaining : available;
            std::memcpy(output + samplesWritten,
                       callbackBuffer_.get() + callbackBufferOffset_,
                       sizeof(int16_t) * toCopy);
            samplesWritten += toCopy;
            callbackBufferOffset_ += toCopy;

            if (callbackBufferOffset_ >= callbackBufferValid_) {
                // Fully consumed this LXST frame
                callbackBufferOffset_ = 0;
                callbackBufferValid_ = 0;
            }
            continue;
        }

        // 2) No partial frame — read a new LXST frame from the ring buffer.
        if (remaining >= frameSamples_) {
            // Output has room for a full LXST frame — read directly into output
            if (ringBuffer_->read(output + samplesWritten, frameSamples_)) {
                samplesWritten += frameSamples_;
            } else {
                break;  // Ring buffer empty
            }
        } else {
            // Output needs fewer samples than a full LXST frame. Read into
            // callbackBuffer_, copy what's needed now, save the remainder
            // for subsequent callbacks.
            if (ringBuffer_->read(callbackBuffer_.get(), frameSamples_)) {
                std::memcpy(output + samplesWritten, callbackBuffer_.get(),
                           sizeof(int16_t) * remaining);
                samplesWritten += remaining;
                callbackBufferOffset_ = remaining;
                callbackBufferValid_ = frameSamples_;
            } else {
                break;  // Ring buffer empty
            }
        }
    }

    // Fill remaining output with silence (underrun)
    if (samplesWritten < totalSamples) {
        std::memset(output + samplesWritten, 0,
                   sizeof(int16_t) * (totalSamples - samplesWritten));
    }

    return isPlaying_.load(std::memory_order_relaxed)
        ? oboe::DataCallbackResult::Continue
        : oboe::DataCallbackResult::Stop;
}

// --- Oboe error callback (stream disconnect recovery) ---

void OboePlaybackEngine::onErrorAfterClose(
        oboe::AudioStream* /*stream*/,
        oboe::Result error) {
    LOGW("Stream error: %s — attempting restart", oboe::convertToText(error));

    // Standard Oboe recovery pattern: reopen the stream.
    // This handles headphone plug/unplug, BT disconnect, etc.
    if (isPlaying_.load()) {
        openStream();
    }
}
