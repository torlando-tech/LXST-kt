/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

#ifndef LXST_OBOE_CAPTURE_ENGINE_H
#define LXST_OBOE_CAPTURE_ENGINE_H

#include <oboe/Oboe.h>
#include <atomic>
#include <memory>
#include "packet_ring_buffer.h"
#include "native_audio_filters.h"
#include "codec_wrapper.h"
#include "encoded_ring_buffer.h"

/**
 * Oboe-based audio capture engine for LXST.
 *
 * Opens an Oboe input stream with InputPreset::VoiceCommunication for
 * platform AEC. The capture callback runs on a SCHED_FIFO thread:
 *   1. Accumulates samples until a full LXST frame is ready
 *   2. Applies native voice filters (HPF → LPF → AGC)
 *   3. Writes the filtered frame to a lock-free SPSC ring buffer
 *
 * Kotlin reads from the ring buffer via JNI (consumer side).
 *
 * Lifecycle: create() → startStream() → readSamples() → stopStream() → destroy()
 */
class OboeCaptureEngine : public oboe::AudioStreamDataCallback,
                          public oboe::AudioStreamErrorCallback {
public:
    OboeCaptureEngine();
    ~OboeCaptureEngine();

    // Non-copyable
    OboeCaptureEngine(const OboeCaptureEngine&) = delete;
    OboeCaptureEngine& operator=(const OboeCaptureEngine&) = delete;

    /**
     * Create the capture engine with audio parameters.
     *
     * @param sampleRate      Input sample rate (e.g., 48000)
     * @param channels        Number of channels (1=mono)
     * @param frameSamples    Number of int16 samples per LXST frame
     * @param maxBufferFrames Maximum frames in ring buffer
     * @param enableFilters   Enable native voice filter chain
     */
    bool create(int sampleRate, int channels, int frameSamples,
                int maxBufferFrames, bool enableFilters);

    /** Open and start the Oboe input stream. */
    bool startStream();

    /** Stop and close the Oboe input stream. */
    void stopStream();

    /** Release all native resources. */
    void destroy();

    /**
     * Read one frame from the ring buffer (consumer side).
     *
     * @param dest  Destination buffer (must hold frameSamples int16s)
     * @param count Number of samples to read (must equal frameSamples)
     * @return true if a frame was read, false if buffer is empty
     */
    bool readSamples(int16_t* dest, int count);

    /** Number of frames currently buffered in the native ring buffer. */
    int getBufferedFrameCount() const;

    /** True if the Oboe input stream is open and recording. */
    bool isRecording() const;

    /** Cumulative xrun count from the Oboe stream. */
    int getXRunCount() const;

    // --- Phase 3: Native codec integration ---

    /**
     * Configure a native encoder on the capture engine.
     *
     * When configured, the Oboe callback encodes directly after filtering,
     * writing encoded packets to an EncodedRingBuffer. Kotlin reads via
     * readEncodedPacket() instead of readSamples().
     */
    bool configureEncoder(int codecType, int sampleRate, int channels,
                          int opusApp, int opusBitrate, int opusComplexity,
                          int codec2Mode);

    /**
     * Read one encoded packet from the encoded ring buffer.
     *
     * @param dest      Destination buffer
     * @param maxLength Size of destination buffer
     * @param actualLength [out] Actual bytes read
     * @return true if a packet was read, false if buffer empty
     */
    bool readEncodedPacket(uint8_t* dest, int maxLength, int* actualLength);

    /**
     * Set capture mute state.
     *
     * When muted, the callback encodes silence so the remote side still
     * receives packets (prevents jitter buffer underrun).
     */
    void setCaptureMute(bool mute);

    /** Destroy the native encoder, freeing codec resources. */
    void destroyEncoder();

    // --- Oboe callbacks ---

    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;

    void onErrorAfterClose(
        oboe::AudioStream* stream, oboe::Result error) override;

private:
    bool openStream();
    void closeStream();

    int sampleRate_ = 0;
    int channels_ = 0;
    int frameSamples_ = 0;

    std::unique_ptr<PacketRingBuffer> ringBuffer_;
    std::unique_ptr<VoiceFilterChain> filterChain_;
    std::shared_ptr<oboe::AudioStream> stream_;

    // Accumulation buffer: aligns variable-size Oboe callbacks to fixed LXST frames
    std::unique_ptr<int16_t[]> accumBuffer_;
    int accumCount_ = 0;

    std::atomic<bool> isCreated_{false};
    std::atomic<bool> isRecording_{false};

    // Phase 3: Native codec encoder
    std::unique_ptr<CodecWrapper> encoder_;
    std::unique_ptr<EncodedRingBuffer> encodedRingBuffer_;
    std::unique_ptr<int16_t[]> monoToStereoBuf_;  // For SHQ stereo upmix
    std::atomic<bool> captureMuted_{false};
    bool encodeInCallback_ = false;  // True when encoder is configured

    // Pre-allocated encode output buffer (max Opus output ~1275 bytes)
    uint8_t encodeBuf_[1500];
    // Pre-allocated silence buffer for mute
    std::unique_ptr<int16_t[]> silenceBuf_;
};

#endif // LXST_OBOE_CAPTURE_ENGINE_H
