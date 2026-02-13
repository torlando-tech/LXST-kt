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
};

#endif // LXST_OBOE_CAPTURE_ENGINE_H
