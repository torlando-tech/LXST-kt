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
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

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
    destroyEncoder();
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

    // Set isRecording_ BEFORE requestStart() to avoid a race condition:
    // The SCHED_FIFO callback can fire immediately after requestStart(),
    // and if isRecording_ is still false, the callback returns Stop,
    // permanently killing the stream.
    isRecording_.store(true);
    accumCount_ = 0;

    result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        isRecording_.store(false);
        LOGE("Failed to start input stream: %s", oboe::convertToText(result));
        closeStream();
        return false;
    }

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
            // Full LXST frame accumulated
            static int diagCount = 0;
            diagCount++;

            // Log frequently at start (every frame for first 30, then every 10)
            bool shouldLog = (diagCount <= 30) || (diagCount % 10 == 0);

            // DIAG: peak of raw accumulation buffer (before mute/filter)
            int16_t preMutePeak = 0;
            if (shouldLog) {
                for (int di = 0; di < frameSamples_; di++) {
                    int16_t v = accumBuffer_[di] < 0 ? -accumBuffer_[di] : accumBuffer_[di];
                    if (v > preMutePeak) preMutePeak = v;
                }
            }

            // Apply mute: replace with silence if capture is muted
            int16_t* frameData = accumBuffer_.get();
            bool isMuted = captureMuted_.load(std::memory_order_relaxed);
            if (isMuted) {
                if (silenceBuf_) {
                    frameData = silenceBuf_.get();
                } else {
                    std::memset(accumBuffer_.get(), 0, sizeof(int16_t) * frameSamples_);
                }
            }

            // Apply filters
            if (filterChain_) {
                filterChain_->process(frameData, frameSamples_, sampleRate_);
            }

            // DIAG: peak after mute+filter
            int16_t postFilterPeak = 0;
            if (shouldLog) {
                for (int di = 0; di < frameSamples_; di++) {
                    int16_t v = frameData[di] < 0 ? -frameData[di] : frameData[di];
                    if (v > postFilterPeak) postFilterPeak = v;
                }
                LOGI("TX-DIAG cap#%d: rawPeak=%d muted=%d filtPeak=%d samples=%d sr=%d ch=%d encCb=%d",
                     diagCount, preMutePeak, isMuted ? 1 : 0, postFilterPeak, frameSamples_,
                     sampleRate_, channels_, encodeInCallback_ ? 1 : 0);
            }

            if (encodeInCallback_ && encoder_ && encodedRingBuffer_) {
                // Phase 3: Encode directly in callback → encoded ring buffer
                int encodedLen = encoder_->encode(frameData, frameSamples_,
                                                  encodeBuf_, sizeof(encodeBuf_));
                // DIAG: log encode result with first bytes
                if (shouldLog && encodedLen > 0) {
                    LOGI("TX-DIAG enc#%d: len=%d TOC=0x%02x bytes=[%02x %02x %02x %02x]",
                         diagCount, encodedLen, encodeBuf_[0],
                         encodeBuf_[0], encodedLen > 1 ? encodeBuf_[1] : 0,
                         encodedLen > 2 ? encodeBuf_[2] : 0, encodedLen > 3 ? encodeBuf_[3] : 0);
                }
                if (encodedLen > 0) {
                    if (!encodedRingBuffer_->write(encodeBuf_, encodedLen)) {
                        // Encoded ring buffer full — drop (consumer too slow)
                        uint8_t discard[1];
                        int discardLen;
                        encodedRingBuffer_->read(discard, 1, &discardLen);
                        encodedRingBuffer_->write(encodeBuf_, encodedLen);
                    }
                } else if (shouldLog) {
                    LOGW("TX-DIAG enc#%d: ENCODE FAILED len=%d", diagCount, encodedLen);
                }
            } else {
                // Phase 2: Write raw PCM to ring buffer
                if (!ringBuffer_->write(frameData, frameSamples_)) {
                    int16_t discard[1];
                    ringBuffer_->read(discard, frameSamples_);
                    ringBuffer_->write(frameData, frameSamples_);
                }
            }

            accumCount_ = 0;
        }
    }

    return isRecording_.load(std::memory_order_relaxed)
        ? oboe::DataCallbackResult::Continue
        : oboe::DataCallbackResult::Stop;
}

// --- Phase 3: Native codec integration ---

bool OboeCaptureEngine::configureEncoder(int codecType, int sampleRate, int channels,
                                          int opusApp, int opusBitrate, int opusComplexity,
                                          int codec2Mode) {
    destroyEncoder();

    encoder_ = std::make_unique<CodecWrapper>();
    bool ok = false;

    if (codecType == static_cast<int>(CodecType::OPUS)) {
        ok = encoder_->createOpus(sampleRate, channels, opusApp, opusBitrate, opusComplexity);
    } else if (codecType == static_cast<int>(CodecType::CODEC2)) {
        ok = encoder_->createCodec2(codec2Mode);
    }

    if (!ok) {
        LOGE("configureEncoder failed: type=%d rate=%d ch=%d", codecType, sampleRate, channels);
        encoder_.reset();
        return false;
    }

    // Encoded ring buffer: 32 slots, 1500 bytes max per slot
    encodedRingBuffer_ = std::make_unique<EncodedRingBuffer>(32, 1500);

    // Pre-allocate silence buffer for mute
    silenceBuf_ = std::make_unique<int16_t[]>(frameSamples_);
    std::memset(silenceBuf_.get(), 0, sizeof(int16_t) * frameSamples_);

    encodeInCallback_ = true;

    LOGI("Encoder configured: type=%d rate=%d ch=%d", codecType, sampleRate, channels);
    return true;
}

bool OboeCaptureEngine::readEncodedPacket(uint8_t* dest, int maxLength, int* actualLength) {
    if (!encodedRingBuffer_) return false;
    return encodedRingBuffer_->read(dest, maxLength, actualLength);
}

void OboeCaptureEngine::setCaptureMute(bool mute) {
    captureMuted_.store(mute, std::memory_order_relaxed);
}

void OboeCaptureEngine::destroyEncoder() {
    encodeInCallback_ = false;
    encoder_.reset();
    encodedRingBuffer_.reset();
    silenceBuf_.reset();
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
