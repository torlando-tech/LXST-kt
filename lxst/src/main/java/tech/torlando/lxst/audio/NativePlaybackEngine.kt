/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package tech.torlando.lxst.audio

import android.util.Log

/**
 * JNI bridge to the native Oboe playback engine (lxst_playback_engine.so).
 *
 * All methods are thread-safe. The native engine uses a lock-free SPSC ring
 * buffer for data transfer between the JNI caller and the Oboe callback.
 *
 * Lifecycle:
 *   create() → writeSamples() (prebuffer) → startStream() → writeSamples() → stopStream() → destroy()
 */
object NativePlaybackEngine {
    private const val TAG = "LXST:NativePlayback"

    @Volatile
    private var libraryLoaded = false

    fun ensureLoaded() {
        if (!libraryLoaded) {
            synchronized(this) {
                if (!libraryLoaded) {
                    try {
                        System.loadLibrary("lxst_playback_engine")
                        libraryLoaded = true
                        Log.i(TAG, "Native playback engine loaded")
                    } catch (e: UnsatisfiedLinkError) {
                        Log.e(TAG, "Failed to load lxst_playback_engine: ${e.message}")
                        throw e
                    }
                }
            }
        }
    }

    /**
     * Create the native engine with audio parameters.
     *
     * @param sampleRate      Output sample rate (e.g., 48000)
     * @param channels        Number of channels (1=mono, 2=stereo)
     * @param frameSamples    Number of int16 samples per LXST frame
     * @param maxBufferFrames Maximum frames in ring buffer
     * @param prebufferFrames Frames to accumulate before starting playback
     */
    fun create(
        sampleRate: Int,
        channels: Int,
        frameSamples: Int,
        maxBufferFrames: Int,
        prebufferFrames: Int,
    ): Boolean {
        ensureLoaded()
        return nativeCreate(sampleRate, channels, frameSamples, maxBufferFrames, prebufferFrames)
    }

    /**
     * Write decoded int16 samples into the native ring buffer.
     *
     * @param samples ShortArray of PCM int16 samples
     * @return true if written without drop, false if oldest frame was dropped
     */
    fun writeSamples(samples: ShortArray): Boolean = nativeWriteSamples(samples)

    /** Open and start the Oboe output stream. */
    fun startStream(): Boolean {
        ensureLoaded()
        return nativeStartStream()
    }

    /**
     * Close and reopen the Oboe stream to pick up audio routing changes.
     *
     * Called when the speaker/earpiece toggle changes so the native stream
     * binds to the newly-routed audio device.
     */
    fun restartStream(): Boolean {
        ensureLoaded()
        return nativeRestartStream()
    }

    /** Stop and close the Oboe output stream. */
    fun stopStream() {
        ensureLoaded()
        nativeStopStream()
    }

    /** Release all native resources. */
    fun destroy() {
        ensureLoaded()
        nativeDestroy()
    }

    /** Number of frames currently buffered in the native ring buffer. */
    fun getBufferedFrameCount(): Int = nativeGetBufferedFrameCount()

    /** True if the Oboe stream is open and playing. */
    fun isPlaying(): Boolean = nativeIsPlaying()

    /** Cumulative underrun (xrun) count from the Oboe stream. */
    fun getXRunCount(): Int = nativeGetXRunCount()

    /** Frames read from ring buffer by the Oboe callback (diagnostic). */
    fun getCallbackFrameCount(): Int = nativeGetCallbackFrameCount()

    /** Callbacks that output full silence due to empty ring buffer (diagnostic). */
    fun getCallbackSilenceCount(): Int = nativeGetCallbackSilenceCount()

    // --- Phase 3: Native codec methods ---

    /**
     * Configure a native decoder on the playback engine.
     *
     * @param codecType    1=Opus, 2=Codec2
     * @param sampleRate   Decoder sample rate
     * @param channels     Number of channels
     * @param opusApp      Opus application type (ignored for Codec2)
     * @param opusBitrate  Opus bitrate (ignored for Codec2)
     * @param opusComplexity Opus complexity (ignored for Codec2)
     * @param codec2Mode   Codec2 library mode (ignored for Opus)
     */
    fun configureDecoder(
        codecType: Int,
        sampleRate: Int,
        channels: Int,
        opusApp: Int = 0,
        opusBitrate: Int = 0,
        opusComplexity: Int = 10,
        codec2Mode: Int = 0,
    ): Boolean {
        ensureLoaded()
        return nativeConfigureDecoder(
            codecType,
            sampleRate,
            channels,
            opusApp,
            opusBitrate,
            opusComplexity,
            codec2Mode,
        )
    }

    /**
     * Write an encoded packet to be decoded natively.
     *
     * Uses offset parameter to skip the codec header byte without
     * a Kotlin-side copyOfRange allocation.
     *
     * @param data   Full packet data (with codec header byte)
     * @param offset Offset into data to start reading (typically 1 to skip header)
     * @param length Number of encoded bytes to decode
     */
    fun writeEncodedPacket(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): Boolean = nativeWriteEncodedPacket(data, offset, length)

    /**
     * Set playback mute state.
     *
     * When muted, Oboe callback outputs silence but ring buffer keeps accumulating.
     */
    fun setPlaybackMute(mute: Boolean) {
        ensureLoaded()
        nativeSetPlaybackMute(mute)
    }

    /** Destroy the native decoder, freeing codec resources. */
    fun destroyDecoder() {
        ensureLoaded()
        nativeDestroyDecoder()
    }

    // --- JNI native methods ---

    private external fun nativeCreate(
        sampleRate: Int,
        channels: Int,
        frameSamples: Int,
        maxBufferFrames: Int,
        prebufferFrames: Int,
    ): Boolean

    private external fun nativeWriteSamples(samples: ShortArray): Boolean

    private external fun nativeStartStream(): Boolean

    private external fun nativeRestartStream(): Boolean

    private external fun nativeStopStream()

    private external fun nativeDestroy()

    private external fun nativeGetBufferedFrameCount(): Int

    private external fun nativeIsPlaying(): Boolean

    private external fun nativeGetXRunCount(): Int

    // Phase 3: Native codec JNI methods
    private external fun nativeConfigureDecoder(
        codecType: Int,
        sampleRate: Int,
        channels: Int,
        opusApp: Int,
        opusBitrate: Int,
        opusComplexity: Int,
        codec2Mode: Int,
    ): Boolean

    private external fun nativeWriteEncodedPacket(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): Boolean

    private external fun nativeSetPlaybackMute(mute: Boolean)

    private external fun nativeDestroyDecoder()

    // Diagnostics
    private external fun nativeGetCallbackFrameCount(): Int

    private external fun nativeGetCallbackSilenceCount(): Int
}
