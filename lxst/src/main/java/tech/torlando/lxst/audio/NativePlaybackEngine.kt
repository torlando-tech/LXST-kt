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
    fun startStream(): Boolean = nativeStartStream()

    /** Stop and close the Oboe output stream. */
    fun stopStream() = nativeStopStream()

    /** Release all native resources. */
    fun destroy() = nativeDestroy()

    /** Number of frames currently buffered in the native ring buffer. */
    fun getBufferedFrameCount(): Int = nativeGetBufferedFrameCount()

    /** True if the Oboe stream is open and playing. */
    fun isPlaying(): Boolean = nativeIsPlaying()

    /** Cumulative underrun (xrun) count from the Oboe stream. */
    fun getXRunCount(): Int = nativeGetXRunCount()

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

    private external fun nativeStopStream()

    private external fun nativeDestroy()

    private external fun nativeGetBufferedFrameCount(): Int

    private external fun nativeIsPlaying(): Boolean

    private external fun nativeGetXRunCount(): Int
}
