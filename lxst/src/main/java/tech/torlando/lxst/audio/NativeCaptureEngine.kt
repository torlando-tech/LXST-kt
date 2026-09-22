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

    /**
     * Attempt to load the native capture engine exactly once.
     *
     * Returns `true` when the library is loaded (or was already loaded) and `false`
     * when it could not be loaded (e.g. JVM unit tests without the `.so` in
     * `java.library.path`, or a missing ABI). This deliberately does NOT throw:
     * the engine degrades to a no-op when unavailable so a missing native library
     * never crashes the process - callers that need to know (e.g. `OboeLineSource`)
     * check the boolean return of [create]/[startStream] instead.
     */
    fun ensureLoaded(): Boolean {
        if (libraryLoaded) return true
        synchronized(this) {
            if (libraryLoaded) return true
            try {
                System.loadLibrary("lxst_capture_engine")
                libraryLoaded = true
                Log.i(TAG, "Native capture engine loaded")
                return true
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Native capture engine unavailable: ${e.message}")
                return false
            }
        }
    }

    /**
     * Create the native capture engine with audio parameters.
     *
     * @return `true` if the engine was created, `false` if it is unavailable (native
     *   lib missing) or creation failed.
     */
    fun create(
        sampleRate: Int,
        channels: Int,
        frameSamples: Int,
        maxBufferFrames: Int,
        enableFilters: Boolean,
    ): Boolean {
        if (!ensureLoaded()) return false
        return nativeCreate(sampleRate, channels, frameSamples, maxBufferFrames, enableFilters)
    }

    /**
     * Read one frame of filtered int16 samples from the native ring buffer.
     *
     * @param dest ShortArray to fill with PCM int16 samples
     * @return true if a frame was read, false if buffer is empty or the engine is unavailable.
     */
    fun readSamples(dest: ShortArray): Boolean {
        if (!ensureLoaded()) return false
        return nativeReadSamples(dest)
    }

    /** Open and start the Oboe input stream. Returns false if unavailable. */
    fun startStream(): Boolean {
        if (!ensureLoaded()) return false
        return nativeStartStream()
    }

    /** Stop and close the Oboe input stream. No-op if unavailable. */
    fun stopStream() {
        if (!ensureLoaded()) return
        nativeStopStream()
    }

    /** Release all native resources. No-op if unavailable. */
    fun destroy() {
        if (!ensureLoaded()) return
        nativeDestroy()
    }

    /** Number of frames currently buffered in the native ring buffer (0 if unavailable). */
    fun getBufferedFrameCount(): Int {
        if (!ensureLoaded()) return 0
        return nativeGetBufferedFrameCount()
    }

    /** True if the Oboe input stream is open and recording (false if unavailable). */
    fun isRecording(): Boolean {
        if (!ensureLoaded()) return false
        return nativeIsRecording()
    }

    /** Cumulative xrun count from the Oboe input stream (0 if unavailable). */
    fun getXRunCount(): Int {
        if (!ensureLoaded()) return 0
        return nativeGetXRunCount()
    }

    // --- Phase 3: Native codec methods ---

    /**
     * Configure a native encoder on the capture engine.
     *
     * When configured, the Oboe callback encodes directly after filtering.
     * Use readEncodedPacket() instead of readSamples() to get encoded output.
     *
     * @return `true` if configured, `false` if the engine is unavailable or configuration failed.
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
        if (!ensureLoaded()) return false
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
     * @return Number of bytes read, or 0 if buffer is empty or the engine is unavailable.
     */
    fun readEncodedPacket(dest: ByteArray): Int {
        if (!ensureLoaded()) return 0
        return nativeReadEncodedPacket(dest)
    }

    /**
     * Set capture mute state.
     *
     * When muted, callback encodes silence so remote still receives packets.
     * No-op if the engine is unavailable.
     */
    fun setCaptureMute(mute: Boolean) {
        if (!ensureLoaded()) return
        nativeSetCaptureMute(mute)
    }

    /**
     * Pause or resume the native AGC stage.
     *
     * When paused, AGC is bypassed so gain state does not drift while half-duplex
     * transmit is squelched (PTT not held). Matches Python LXST AGC.paused.
     * No-op if the filter chain is disabled or the engine is unavailable.
     */
    fun setAgcPaused(paused: Boolean) {
        if (!ensureLoaded()) return
        nativeSetAgcPaused(paused)
    }

    /** Destroy the native encoder, freeing codec resources. No-op if unavailable. */
    fun destroyEncoder() {
        if (!ensureLoaded()) return
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

    private external fun nativeSetAgcPaused(paused: Boolean)

    private external fun nativeDestroyEncoder()
}
