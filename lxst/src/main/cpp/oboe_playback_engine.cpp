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
    destroyed_.store(false, std::memory_order_release);
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
    stream_.reset();
}

void OboePlaybackEngine::destroy() {
    destroyed_.store(true, std::memory_order_release);
    isPlaying_.store(false);
    closeStream();
    stream_.reset();       // Safe now — close() has returned and destroyed_ guards callback
    destroyDecoder();
    ringBuffer_.reset();
    callbackBuffer_.reset();
    dropBuffer_.reset();
    callbackBufferOffset_ = 0;
    callbackBufferValid_ = 0;
    isCreated_.store(false);
    decodedFrameCount_.store(0, std::memory_order_relaxed);
    callbackFrameCount_.store(0, std::memory_order_relaxed);
    callbackSilenceCount_.store(0, std::memory_order_relaxed);
    callbackPlcCount_.store(0, std::memory_order_relaxed);
    consecutivePlcCount_ = 0;
    LOGI("Destroyed");
}

int OboePlaybackEngine::getBufferedFrameCount() const {
    return ringBuffer_ ? ringBuffer_->availableFrames() : 0;
}

int OboePlaybackEngine::getXRunCount() const {
    auto s = stream_;  // Local copy prevents TOCTOU if stream_ is reset concurrently
    if (!s) return 0;
    auto result = s->getXRunCount();
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

    // Set isPlaying_ BEFORE requestStart() to avoid a race condition:
    // The SCHED_FIFO callback can fire immediately after requestStart(),
    // and if isPlaying_ is still false, the callback returns Stop,
    // permanently killing the stream.
    isPlaying_.store(true);

    result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        isPlaying_.store(false);
        LOGE("Failed to start stream: %s", oboe::convertToText(result));
        closeStream();
        return false;
    }

    LOGI("Stream started");
    return true;
}

void OboePlaybackEngine::closeStream() {
    if (stream_) {
        stream_->close();  // close() internally stops — calling stop() first is unnecessary
        LOGI("Stream closed");
    }
}

bool OboePlaybackEngine::restartStream() {
    if (!isPlaying_.load()) return false;
    LOGI("Restarting stream for audio routing change");
    isPlaying_.store(false);
    closeStream();
    stream_.reset();
    return openStream();  // restores isPlaying_ = true on success
}

// --- Oboe audio callback (runs on SCHED_FIFO thread) ---

oboe::DataCallbackResult OboePlaybackEngine::onAudioReady(
        oboe::AudioStream* /*stream*/,
        void* audioData,
        int32_t numFrames) {

    auto* output = static_cast<int16_t*>(audioData);
    int32_t totalSamples = numFrames * channels_;

    // Guard against callback firing after destroy() on OpenSL ES legacy path
    if (destroyed_.load(std::memory_order_acquire)) {
        std::memset(output, 0, sizeof(int16_t) * totalSamples);
        return oboe::DataCallbackResult::Stop;
    }

    // Phase 3: Mute outputs silence, ring buffer continues accumulating
    if (playbackMuted_.load(std::memory_order_relaxed)) {
        std::memset(output, 0, sizeof(int16_t) * totalSamples);
        return isPlaying_.load(std::memory_order_relaxed)
            ? oboe::DataCallbackResult::Continue
            : oboe::DataCallbackResult::Stop;
    }

    int32_t samplesWritten = 0;

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
                callbackFrameCount_.fetch_add(1, std::memory_order_relaxed);
                consecutivePlcCount_ = 0;
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
                callbackFrameCount_.fetch_add(1, std::memory_order_relaxed);
                consecutivePlcCount_ = 0;
            } else {
                break;  // Ring buffer empty
            }
        }
    }

    // Fill remaining output with PLC or silence (underrun)
    if (samplesWritten < totalSamples) {
        bool usedPlc = false;

        // Try Opus PLC if decoder is available and we haven't exhausted PLC quality
        if (decoder_ && decoder_->type() == CodecType::OPUS
                && consecutivePlcCount_ < 5) {
            // Non-blocking try-lock: if writeEncodedPacket() holds the lock,
            // fall through to silence (near-zero contention in practice since
            // empty buffer means packets aren't arriving).
            if (!decoderLock_.test_and_set(std::memory_order_acquire)) {
                int plcSamples = decoder_->decodePlc(
                    callbackBuffer_.get(), frameSamples_ / channels_);
                decoderLock_.clear(std::memory_order_release);

                if (plcSamples > 0) {
                    // Copy PLC samples to output, handling partial frame
                    int remaining = totalSamples - samplesWritten;
                    int toCopy = (remaining < plcSamples) ? remaining : plcSamples;
                    std::memcpy(output + samplesWritten, callbackBuffer_.get(),
                               sizeof(int16_t) * toCopy);
                    samplesWritten += toCopy;
                    consecutivePlcCount_++;
                    callbackPlcCount_.fetch_add(1, std::memory_order_relaxed);
                    usedPlc = true;

                    // If PLC didn't fill everything, zero the rest
                    if (samplesWritten < totalSamples) {
                        std::memset(output + samplesWritten, 0,
                                   sizeof(int16_t) * (totalSamples - samplesWritten));
                    }
                }
            }
        }

        if (!usedPlc) {
            std::memset(output + samplesWritten, 0,
                       sizeof(int16_t) * (totalSamples - samplesWritten));
            if (samplesWritten == 0) {
                callbackSilenceCount_.fetch_add(1, std::memory_order_relaxed);
            }
        }
    }

    return isPlaying_.load(std::memory_order_relaxed)
        ? oboe::DataCallbackResult::Continue
        : oboe::DataCallbackResult::Stop;
}

// --- Phase 3: Native codec integration ---

bool OboePlaybackEngine::configureDecoder(int codecType, int sampleRate, int channels,
                                           int opusApp, int opusBitrate, int opusComplexity,
                                           int codec2Mode) {
    destroyDecoder();

    decoder_ = std::make_unique<CodecWrapper>();
    bool ok = false;

    if (codecType == static_cast<int>(CodecType::OPUS)) {
        ok = decoder_->createOpus(sampleRate, channels, opusApp, opusBitrate, opusComplexity);
    } else if (codecType == static_cast<int>(CodecType::CODEC2)) {
        ok = decoder_->createCodec2(codec2Mode);
    }

    if (!ok) {
        LOGE("configureDecoder failed: type=%d rate=%d ch=%d", codecType, sampleRate, channels);
        decoder_.reset();
        return false;
    }

    // Pre-allocate decode output buffer.
    // Opus: max 60ms × sampleRate × channels (handles stereo)
    // Codec2: frame times up to 400ms, but always mono — use frameSamples_
    decodeBufSize_ = std::max((sampleRate * 60 / 1000) * channels, frameSamples_);
    decodeBuf_ = std::make_unique<int16_t[]>(decodeBufSize_);

    LOGI("Decoder configured: type=%d rate=%d ch=%d bufSize=%d",
         codecType, sampleRate, channels, decodeBufSize_);
    return true;
}

bool OboePlaybackEngine::writeEncodedPacket(const uint8_t* data, int length) {
    if (!decoder_ || !ringBuffer_ || !decodeBuf_) return false;

    // Acquire decoder lock (spin is fine — not real-time thread, and PLC
    // hold time is microseconds). Prevents concurrent access with PLC in
    // the Oboe callback.
    while (decoderLock_.test_and_set(std::memory_order_acquire)) { /* spin */ }
    int decodedSamples = decoder_->decode(data, length,
                                          decodeBuf_.get(), decodeBufSize_);
    decoderLock_.clear(std::memory_order_release);
    if (decodedSamples <= 0) {
        static int errCount = 0;
        if (++errCount <= 5) {
            LOGW("writeEncodedPacket: decode returned %d (len=%d bufSize=%d)",
                 decodedSamples, length, decodeBufSize_);
        }
        return false;
    }

    // Sanity check: decoded sample count must match ring buffer frame size
    if (decodedSamples != frameSamples_) {
        static int mismatchCount = 0;
        if (++mismatchCount <= 5) {
            LOGW("writeEncodedPacket: decoded %d samples but frameSamples=%d (mismatch #%d)",
                 decodedSamples, frameSamples_, mismatchCount);
        }
    }

    int count = decodedFrameCount_.fetch_add(1, std::memory_order_relaxed) + 1;
    if (count <= 5 || count % 50 == 0) {
        int buf = ringBuffer_->availableFrames();
        int cb = callbackFrameCount_.load(std::memory_order_relaxed);
        int sil = callbackSilenceCount_.load(std::memory_order_relaxed);
        int plc = callbackPlcCount_.load(std::memory_order_relaxed);
        LOGI("RX#%d: decoded=%d len=%d buf=%d cbServed=%d cbSilence=%d cbPlc=%d",
             count, decodedSamples, length, buf, cb, sil, plc);
    }

    // Write decoded PCM into the existing ring buffer
    return writeSamples(decodeBuf_.get(), decodedSamples);
}

void OboePlaybackEngine::setPlaybackMute(bool mute) {
    playbackMuted_.store(mute, std::memory_order_relaxed);
}

void OboePlaybackEngine::destroyDecoder() {
    decoder_.reset();
    decodeBuf_.reset();
    decodeBufSize_ = 0;
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
