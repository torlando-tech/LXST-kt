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

    // Per-burst resample scratch (Phase 3). A single capture-rate frame can grow
    // when resampled to a higher encoder rate (e.g. 24k capture -> 48k encoder
    // = 2x), so size it generously; the resampler never returns more than
    // (inLen * encoderRate / captureRate) samples. The encoder-rate ACCUMULATOR
    // (encAccum_) is allocated in configureEncoder() once the encoder frame size
    // is known, and emits whole encoder frames so every encode() gets an exact
    // codec frame (see header comment). Unused (identity) when rates match.
    resampleCap_ = frameSamples * 8;
    resampleBuf_ = std::make_unique<int16_t[]>(resampleCap_);

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
           ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
           ->setDataCallback(this)
           ->setErrorCallback(this);

    oboe::Result result = builder.openStream(stream_);

    if (result != oboe::Result::OK) {
        LOGE("Failed to open input stream: %s", oboe::convertToText(result));
        return false;
    }

    LOGI("Input stream opened: API=%s, rate=%d (requested=%d), ch=%d, framesPerBurst=%d, bufferCapacity=%d",
         stream_->getAudioApi() == oboe::AudioApi::AAudio ? "AAudio" : "OpenSLES",
         stream_->getSampleRate(),
         sampleRate_,
         stream_->getChannelCount(),
         stream_->getFramesPerBurst(),
         stream_->getBufferCapacityInFrames());

    if (stream_->getSampleRate() != sampleRate_) {
        LOGI("Capture stream: SRC active, HAL rate=%d, codec rate=%d",
             stream_->getSampleRate(), sampleRate_);
    }

    // Record the ACTUAL capture rate and (re)configure the capture→encoder
    // resampler now that we know it. The encoder (if configured) runs at the
    // profile's native rate; when it differs from the hardware capture rate we
    // resample before encoding (matches Python LXST's encode-time resample).
    captureRate_ = stream_->getSampleRate();
    if (encodeInCallback_ && encoder_ && captureRate_ > 0 && encoderRate_ > 0) {
        resampler_.configure(captureRate_, encoderRate_);

        // Size the per-burst resample scratch from the ACTUAL rate ratio. A
        // single capture-rate frame (frameSamples_) resampled to a higher
        // encoder rate grows by encoderRate_/captureRate_; sizing from the
        // configured ratio (not a fixed multiple) means even a large upsample
        // (e.g. 8k capture -> 48k encoder = 6x) is never truncated.
        double ratio = static_cast<double>(encoderRate_) / static_cast<double>(captureRate_);
        int maxOut = frameSamples_ +
            static_cast<int>(std::ceil(frameSamples_ * ratio));
        if (!resampleBuf_ || resampleCap_ < maxOut) {
            resampleBuf_ = std::make_unique<int16_t[]>(maxOut);
            resampleCap_ = maxOut;
        }

        // Re-size the encoder-rate accumulator for this stream. Capacity of
        // two encoder frames comfortably holds the carry-over (a partial
        // frame) plus one full resampled capture frame at the worst ratio.
        if (encoderFrameSize_ > 0) {
            int cap = encoderFrameSize_ * 2;
            if (!encAccum_ || encAccumCap_ != cap) {
                encAccum_.reset();  // discard any carried samples across restart
                encAccumCount_ = 0;
                encAccumCap_ = cap;
                encAccum_ = std::make_unique<int16_t[]>(encAccumCap_);
            }
        }

        LOGI("TX resampler: capture=%d -> encoder=%d (%s), encFrame=%d, scratchCap=%d",
             captureRate_, encoderRate_, resampler_.enabled() ? "active" : "identity",
             encoderFrameSize_, resampleCap_);
    }

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

            // Apply mute: replace with silence if capture is muted
            int16_t* frameData = accumBuffer_.get();
            if (captureMuted_.load(std::memory_order_relaxed)) {
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

            if (encodeInCallback_ && encoder_ && encodedRingBuffer_ &&
                encoderFrameSize_ > 0 && encAccum_) {
                // Phase 3: Encode directly in callback → encoded ring buffer.
                //
                // Bring this capture frame to the ENCODER rate. The encoder is
                // configured at the profile's native rate (e.g. Codec2 @8k, Opus
                // @48k) but the Oboe capture stream runs at the hardware rate
                // (e.g. 24k), so resample first - mirroring Python LXST's
                // encode-time resample (Codec2.py:66-69, Opus.py:142-145). In
                // identity mode (rates match) we use the filtered frame as-is.
                const int16_t* encRatePcm = frameData;
                int encRateSamples = frameSamples_;
                if (resampler_.enabled() && resampleBuf_) {
                    int n = resampler_.process(frameData, frameSamples_,
                                               resampleBuf_.get(), resampleCap_);
                    if (n < 0) {
                        // Scratch too small (should not happen: sized from the
                        // actual ratio in openStream). Skip this burst rather
                        // than risk a truncated feed.
                        LOGE("TX resample overflow: in=%d cap=%d",
                             frameSamples_, resampleCap_);
                        n = 0;
                    }
                    encRatePcm = resampleBuf_.get();
                    encRateSamples = n;
                }

                // Append the encoder-rate samples to the accumulator, then emit
                // complete encoder-frame chunks. The codec frame size is a
                // function of the ENCODER rate and a single resampled capture
                // frame can be shorter (downsample) or longer (upsample) than it
                // - plus a fractional carry-over - so we accumulate and emit only
                // whole frames. This guarantees every encode() call gets an exact
                // codec frame: Opus rejects non-standard sizes (P2: 958 != 960
                // dropped the first packet), and Codec2's numFrames = samples /
                // spf would otherwise encode 0 frames from a truncated buffer
                // (P1: silence). Identity mode has encRateSamples ==
                // encoderFrameSize_, so it emits exactly one chunk per frame with
                // no carry - identical to the pre-fix behaviour.
                if (encRateSamples > 0) {
                    int room = encAccumCap_ - encAccumCount_;
                    if (encRateSamples > room) {
                        // Accumulator sized for 2x an encoder frame and the
                        // carry is always < 1 frame, so this never triggers; cap
                        // defensively to keep the buffer bounded regardless.
                        encRateSamples = room;
                    }
                    std::memcpy(encAccum_.get() + encAccumCount_, encRatePcm,
                                sizeof(int16_t) * encRateSamples);
                    encAccumCount_ += encRateSamples;

                    while (encAccumCount_ >= encoderFrameSize_) {
                        int encodedLen = encoder_->encode(
                            encAccum_.get(), encoderFrameSize_,
                            encodeBuf_, sizeof(encodeBuf_));
                        if (encodedLen > 0) {
                            if (!encodedRingBuffer_->write(encodeBuf_, encodedLen)) {
                                // Encoded ring buffer full — drop (consumer too slow)
                                uint8_t discard[1];
                                int discardLen;
                                encodedRingBuffer_->read(discard, 1, &discardLen);
                                encodedRingBuffer_->write(encodeBuf_, encodedLen);
                            }
                        }
                        // Shift the remainder to the front for the next burst.
                        encAccumCount_ -= encoderFrameSize_;
                        if (encAccumCount_ > 0) {
                            std::memmove(encAccum_.get(),
                                         encAccum_.get() + encoderFrameSize_,
                                         sizeof(int16_t) * encAccumCount_);
                        }
                    }
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

    // The encoder's native sample rate - the capture stream may run at a
    // different (hardware) rate, in which case openStream() sizes and configures
    // the capture→encoder resampler to bridge the gap (see Python LXST's
    // encode-time resample).
    encoderRate_ = sampleRate;
    resampler_.reset();

    // Samples per encoder frame. The codec frame size is a function of the
    // ENCODER rate (Codec2 @8k = 160, Opus @48k = 960 for a 20ms frame), so the
    // encoder-rate accumulator emits chunks of this size - not the capture-rate
    // frameSamples_ - guaranteeing every encode() call gets an exact codec frame
    // (Opus rejects non-standard sizes; Codec2's numFrames = samples / spf would
    // otherwise drop a truncated frame). frameSamples_ is frameDurationMs at the
    // requested (sampleRate_) rate, so the same duration at the encoder rate is:
    encoderFrameSize_ = static_cast<int>(
        frameSamples_ * (static_cast<double>(sampleRate) / sampleRate_) + 0.5);

    // Encoded ring buffer: 32 slots, 1500 bytes max per slot
    encodedRingBuffer_ = std::make_unique<EncodedRingBuffer>(32, 1500);

    // Pre-allocate silence buffer for mute
    silenceBuf_ = std::make_unique<int16_t[]>(frameSamples_);
    std::memset(silenceBuf_.get(), 0, sizeof(int16_t) * frameSamples_);

    // Encoder-rate accumulator. openStream() re-sizes this once the actual
    // capture rate is known; allocate a sensible default now so the engine is
    // usable even if configureEncoder() runs before the first stream open.
    encAccumCount_ = 0;
    encAccumCap_ = encoderFrameSize_ * 2;
    encAccum_ = std::make_unique<int16_t[]>(encAccumCap_);

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

void OboeCaptureEngine::setAgcPaused(bool paused) {
    if (filterChain_) {
        filterChain_->setAgcPaused(paused);
    }
}

void OboeCaptureEngine::destroyEncoder() {
    encodeInCallback_ = false;
    encoder_.reset();
    encodedRingBuffer_.reset();
    silenceBuf_.reset();
    encAccum_.reset();
    encAccumCount_ = 0;
    encAccumCap_ = 0;
    encoderFrameSize_ = 0;
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
