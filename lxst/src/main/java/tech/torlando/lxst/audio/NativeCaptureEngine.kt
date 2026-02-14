/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package tech.torlando.lxst.audio

import android.util.Log

/**
 * JNI bridge to the native Oboe capture engine (lxst_capture_engine.so).
 *
 * All methods are thread-safe. The native engine uses a lock-free SPSC ring
 * buffer for data transfer between the Oboe callback (producer) and the
 * JNI caller (consumer).
 *
 * Lifecycle:
 *   create() → startStream() → readSamples() → stopStream() → destroy()
 */
object NativeCaptureEngine {
    private const val TAG = "LXST:NativeCapture"

    @Volatile
    private var libraryLoaded = false

    fun ensureLoaded() {
        if (!libraryLoaded) {
            synchronized(this) {
                if (!libraryLoaded) {
                    try {
                        System.loadLibrary("lxst_capture_engine")
                        libraryLoaded = true
                        Log.i(TAG, "Native capture engine loaded")
                    } catch (e: UnsatisfiedLinkError) {
                        Log.e(TAG, "Failed to load lxst_capture_engine: ${e.message}")
                        throw e
                    }
                }
            }
        }
    }

    /**
     * Create the native capture engine with audio parameters.
     *
     * @param sampleRate      Input sample rate (e.g., 48000)
     * @param channels        Number of channels (1=mono)
     * @param frameSamples    Number of int16 samples per LXST frame
     * @param maxBufferFrames Maximum frames in ring buffer
     * @param enableFilters   Enable native HPF/LPF/AGC filter chain
     */
    fun create(
        sampleRate: Int,
        channels: Int,
        frameSamples: Int,
        maxBufferFrames: Int,
        enableFilters: Boolean,
    ): Boolean {
        ensureLoaded()
        return nativeCreate(sampleRate, channels, frameSamples, maxBufferFrames, enableFilters)
    }

    /**
     * Read one frame of filtered int16 samples from the native ring buffer.
     *
     * @param dest ShortArray to fill with PCM int16 samples
     * @return true if a frame was read, false if buffer is empty
     */
    fun readSamples(dest: ShortArray): Boolean = nativeReadSamples(dest)

    /** Open and start the Oboe input stream. */
    fun startStream(): Boolean = nativeStartStream()

    /** Stop and close the Oboe input stream. */
    fun stopStream() = nativeStopStream()

    /** Release all native resources. */
    fun destroy() = nativeDestroy()

    /** Number of frames currently buffered in the native ring buffer. */
    fun getBufferedFrameCount(): Int = nativeGetBufferedFrameCount()

    /** True if the Oboe input stream is open and recording. */
    fun isRecording(): Boolean = nativeIsRecording()

    /** Cumulative xrun count from the Oboe input stream. */
    fun getXRunCount(): Int = nativeGetXRunCount()

    // --- Phase 3: Native codec methods ---

    /**
     * Configure a native encoder on the capture engine.
     *
     * When configured, the Oboe callback encodes directly after filtering.
     * Use readEncodedPacket() instead of readSamples() to get encoded output.
     */
    fun configureEncoder(
        codecType: Int,
        sampleRate: Int,
        channels: Int,
        opusApp: Int = 0,
        opusBitrate: Int = 0,
        opusComplexity: Int = 10,
        codec2Mode: Int = 0,
    ): Boolean {
        ensureLoaded()
        return nativeConfigureEncoder(
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
     * Read one encoded packet from the native encoded ring buffer.
     *
     * @param dest ByteArray to fill with encoded data
     * @return Number of bytes read, or 0 if buffer is empty
     */
    fun readEncodedPacket(dest: ByteArray): Int = nativeReadEncodedPacket(dest)

    /**
     * Set capture mute state.
     *
     * When muted, callback encodes silence so remote still receives packets.
     */
    fun setCaptureMute(mute: Boolean) {
        ensureLoaded()
        nativeSetCaptureMute(mute)
    }

    /** Destroy the native encoder, freeing codec resources. */
    fun destroyEncoder() {
        ensureLoaded()
        nativeDestroyEncoder()
    }

    // --- JNI native methods ---

    private external fun nativeCreate(
        sampleRate: Int,
        channels: Int,
        frameSamples: Int,
        maxBufferFrames: Int,
        enableFilters: Boolean,
    ): Boolean

    private external fun nativeReadSamples(dest: ShortArray): Boolean

    private external fun nativeStartStream(): Boolean

    private external fun nativeStopStream()

    private external fun nativeDestroy()

    private external fun nativeGetBufferedFrameCount(): Int

    private external fun nativeIsRecording(): Boolean

    private external fun nativeGetXRunCount(): Int

    // Phase 3: Native codec JNI methods
    private external fun nativeConfigureEncoder(
        codecType: Int,
        sampleRate: Int,
        channels: Int,
        opusApp: Int,
        opusBitrate: Int,
        opusComplexity: Int,
        codec2Mode: Int,
    ): Boolean

    private external fun nativeReadEncodedPacket(dest: ByteArray): Int

    private external fun nativeSetCaptureMute(mute: Boolean)

    private external fun nativeDestroyEncoder()
}
