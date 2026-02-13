/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

#ifndef LXST_OBOE_PLAYBACK_ENGINE_H
#define LXST_OBOE_PLAYBACK_ENGINE_H

#include <oboe/Oboe.h>
#include <atomic>
#include <memory>
#include "packet_ring_buffer.h"

/**
 * Oboe-based playback engine for LXST audio pipeline.
 *
 * Replaces the Java AudioTrack playback path with native Oboe output.
 * Oboe provides:
 *   - SCHED_FIFO real-time thread for the audio callback
 *   - Automatic AAudio/OpenSL ES selection (AAudio on API 27+)
 *   - Zero-copy HAL access on supported devices
 *   - Automatic stream restart on disconnect (headphone plug/unplug)
 *
 * Lifecycle:
 *   1. create()        — Allocate ring buffer, no stream yet
 *   2. writeSamples()  — Producer fills ring buffer (prebuffering)
 *   3. startStream()   — Open Oboe stream, callback reads from ring buffer
 *   4. writeSamples()  — Producer continues feeding during playback
 *   5. stopStream()    — Stop and close Oboe stream
 *   6. destroy()       — Release all resources
 *
 * The engine implements Oboe's AudioStreamDataCallback for the playback
 * callback, and AudioStreamErrorCallback for stream error recovery.
 */
class OboePlaybackEngine : public oboe::AudioStreamDataCallback,
                           public oboe::AudioStreamErrorCallback {
public:
    OboePlaybackEngine();
    ~OboePlaybackEngine() override;

    /**
     * Create the engine with audio parameters.
     *
     * Allocates the ring buffer but does NOT open an Oboe stream yet.
     * Call startStream() after prebuffering.
     *
     * @param sampleRate     Output sample rate (e.g., 48000)
     * @param channels       Number of channels (1=mono, 2=stereo)
     * @param frameSamples   Samples per audio frame (e.g., 2880 for MQ 60ms)
     * @param maxBufferFrames Maximum frames in ring buffer
     * @param prebufferFrames Frames to accumulate before starting playback
     * @return true on success
     */
    bool create(int sampleRate, int channels, int frameSamples,
                int maxBufferFrames, int prebufferFrames);

    /**
     * Write decoded int16 samples into the ring buffer.
     *
     * Called from Kotlin via JNI on the mixer/decode thread.
     * If the buffer is full, the oldest frame is dropped.
     *
     * @param samples  int16 PCM samples
     * @param count    Number of samples (must equal frameSamples)
     * @return true if written without drop, false if oldest was dropped
     */
    bool writeSamples(const int16_t* samples, int count);

    /**
     * Open and start the Oboe output stream.
     *
     * Should be called after prebuffer frames have been written.
     * The Oboe callback will begin reading from the ring buffer.
     *
     * @return true on success
     */
    bool startStream();

    /**
     * Stop and close the Oboe output stream.
     *
     * Ring buffer contents are preserved (not cleared).
     */
    void stopStream();

    /**
     * Release all resources (ring buffer + stream).
     */
    void destroy();

    /** Number of frames currently available in the ring buffer. */
    int getBufferedFrameCount() const;

    /** True if the Oboe stream is open and playing. */
    bool isPlaying() const { return isPlaying_.load(std::memory_order_relaxed); }

    /** Cumulative underrun (xrun) count from the Oboe stream. */
    int getXRunCount() const;

    // --- Oboe callbacks (called on SCHED_FIFO thread) ---

    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* stream,
        void* audioData,
        int32_t numFrames) override;

    void onErrorAfterClose(
        oboe::AudioStream* stream,
        oboe::Result error) override;

private:
    bool openStream();
    void closeStream();

    int sampleRate_ = 0;
    int channels_ = 0;
    int frameSamples_ = 0;     // Samples per LXST frame
    int prebufferFrames_ = 0;

    std::unique_ptr<PacketRingBuffer> ringBuffer_;
    std::shared_ptr<oboe::AudioStream> stream_;

    std::atomic<bool> isPlaying_{false};
    std::atomic<bool> isCreated_{false};

    // Partial frame tracking — handles burst size < LXST frame size.
    // When the Oboe callback requests fewer samples than one LXST frame,
    // we read a full frame into callbackBuffer_ and serve it across
    // multiple callbacks, tracking the offset. This is the inverse of
    // the capture engine's accumBuffer_ pattern.
    std::unique_ptr<int16_t[]> callbackBuffer_;  // Used ONLY by callback thread
    int callbackBufferOffset_ = 0;    // Next sample to copy from callbackBuffer_
    int callbackBufferValid_ = 0;     // Number of valid samples in callbackBuffer_

    // Separate buffer for the drop-oldest path in writeSamples() (producer thread).
    // Must NOT share callbackBuffer_ since that holds persistent partial frame state
    // accessed by the callback thread.
    std::unique_ptr<int16_t[]> dropBuffer_;
};

#endif // LXST_OBOE_PLAYBACK_ENGINE_H
