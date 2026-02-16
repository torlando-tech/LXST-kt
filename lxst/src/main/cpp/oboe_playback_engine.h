/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

#ifndef LXST_OBOE_PLAYBACK_ENGINE_H
#define LXST_OBOE_PLAYBACK_ENGINE_H

#include <oboe/Oboe.h>
#include <atomic>
#include <memory>
#include "packet_ring_buffer.h"
#include "codec_wrapper.h"

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

    /** Frames read from ring buffer by the Oboe callback. */
    int getCallbackFrameCount() const { return callbackFrameCount_.load(std::memory_order_relaxed); }

    /** Callbacks that output full silence (ring buffer empty). */
    int getCallbackSilenceCount() const { return callbackSilenceCount_.load(std::memory_order_relaxed); }

    /** Callbacks that used Opus PLC instead of silence. */
    int getCallbackPlcCount() const { return callbackPlcCount_.load(std::memory_order_relaxed); }

    // --- Phase 3: Native codec integration ---

    /**
     * Configure a native decoder for the playback path.
     *
     * When configured, writeEncodedPacket() decodes directly in native code,
     * eliminating JNI crossings and Kotlin allocations on the RX path.
     *
     * @param codecType    1=Opus, 2=Codec2
     * @param sampleRate   Decoder sample rate
     * @param channels     Number of channels
     * @param opusApp      Opus application type (ignored for Codec2)
     * @param opusBitrate  Opus bitrate (ignored for Codec2)
     * @param opusComplexity Opus complexity (ignored for Codec2)
     * @param codec2Mode   Codec2 library mode (ignored for Opus)
     * @return true on success
     */
    bool configureDecoder(int codecType, int sampleRate, int channels,
                          int opusApp, int opusBitrate, int opusComplexity,
                          int codec2Mode);

    /**
     * Write an encoded packet directly into the engine.
     *
     * Decodes to int16 PCM using the native decoder, then writes decoded
     * samples into the existing PCM ring buffer. Called from LinkSource's
     * processing loop on Dispatchers.IO.
     *
     * @param data    Encoded packet bytes (without codec header byte)
     * @param length  Encoded packet length
     * @return true on success
     */
    bool writeEncodedPacket(const uint8_t* data, int length);

    /**
     * Set playback mute state.
     *
     * When muted, the Oboe callback outputs silence but the ring buffer
     * continues accumulating decoded frames (preserves prebuffer state).
     *
     * @param mute True to mute playback output
     */
    void setPlaybackMute(bool mute);

    /** Destroy the native decoder, freeing codec resources. */
    void destroyDecoder();

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

public:
    /**
     * Close and reopen the Oboe stream to pick up audio routing changes.
     *
     * Called when the speaker/earpiece toggle changes — many HALs (especially
     * Samsung low-end OpenSL ES) don't dynamically re-route already-open streams.
     *
     * @return true if the stream was successfully restarted
     */
    bool restartStream();

private:
    int sampleRate_ = 0;
    int channels_ = 0;
    int frameSamples_ = 0;     // Samples per LXST frame
    int prebufferFrames_ = 0;

    std::unique_ptr<PacketRingBuffer> ringBuffer_;
    std::shared_ptr<oboe::AudioStream> stream_;

    std::atomic<bool> isPlaying_{false};
    std::atomic<bool> isCreated_{false};
    std::atomic<bool> destroyed_{false};

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

    // Phase 3: Native codec decoder
    std::unique_ptr<CodecWrapper> decoder_;
    std::unique_ptr<int16_t[]> decodeBuf_;     // Pre-allocated decode output buffer
    int decodeBufSize_ = 0;                     // Size of decodeBuf_ in samples
    std::atomic<bool> playbackMuted_{false};

    // PLC (Packet Loss Concealment)
    // Non-blocking try-lock for decoder access from the SCHED_FIFO callback.
    // When the ring buffer is empty, the callback can try to generate PLC audio
    // from the Opus decoder state. The lock prevents concurrent access with
    // writeEncodedPacket() on the IO thread (contention is near-zero since
    // empty buffer means packets aren't arriving).
    std::atomic_flag decoderLock_ = ATOMIC_FLAG_INIT;
    int consecutivePlcCount_ = 0;  // Callback-thread-only, no atomics needed

    // Diagnostics
    std::atomic<int> decodedFrameCount_{0};   // Frames decoded via writeEncodedPacket
    std::atomic<int> callbackFrameCount_{0};  // Frames served to Oboe callback
    std::atomic<int> callbackSilenceCount_{0}; // Callbacks that output silence (underrun)
    std::atomic<int> callbackPlcCount_{0};     // Callbacks that used Opus PLC
};

#endif // LXST_OBOE_PLAYBACK_ENGINE_H
