/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package tech.torlando.lxst.audio

import android.util.Log
import tech.torlando.lxst.core.AudioDevice
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * OboeLineSink - Oboe-backed speaker playback for LXST audio pipeline.
 *
 * Drop-in replacement for [LineSink] that routes decoded audio through a native
 * Oboe output stream instead of Java AudioTrack. The benefits:
 *
 * 1. **SCHED_FIFO thread**: Oboe callback runs on a real-time priority thread,
 *    eliminating scheduling jitter from the CFS scheduler.
 * 2. **Zero per-frame allocations**: The native ring buffer is pre-allocated.
 *    No `ByteBuffer.allocate()` or `float32ToBytes()` on every frame.
 * 3. **Native jitter buffer**: Prebuffer/rebuffer logic runs in C++ with
 *    lock-free SPSC ring buffer — no `LinkedBlockingQueue` or GC pauses.
 * 4. **Low-latency HAL**: Oboe requests `PerformanceMode::LowLatency` +
 *    `SharingMode::Exclusive` for direct HAL access on supported devices.
 *
 * The sink receives float32 frames from Mixer, converts to int16 (one
 * allocation per frame for the ShortArray — eliminated in Phase 3), and
 * pushes into the native ring buffer via JNI.
 *
 * @param autodigest Auto-start playback when buffer reaches prebuffer threshold
 */
class OboeLineSink(
    private val autodigest: Boolean = true,
) : LocalSink() {
    companion object {
        private const val TAG = "Columba:OboeLineSink"

        // Time-based buffer targets (same policy as LineSink)
        const val BUFFER_CAPACITY_MS = 1500L
        const val PREBUFFER_MS = 500L
        const val MAX_QUEUE_SLOTS = 150
        const val MAX_FRAMES = 15
        const val AUTOSTART_MIN = 5
    }

    // Audio configuration (detected from first frame)
    @Volatile private var sampleRate: Int = 0

    @Volatile private var channels: Int = 1

    @Volatile private var frameSamples: Int = 0

    @Volatile private var frameTimeMs: Long = 20

    // Effective limits (recomputed when frame time is known)
    @Volatile private var effectiveMaxFrames: Int = MAX_FRAMES

    @Volatile private var effectiveAutostartMin: Int = AUTOSTART_MIN

    @Volatile private var bufferLimitsInitialized: Boolean = false

    // State flags
    private val isRunningFlag = AtomicBoolean(false)
    private val releasedFlag = AtomicBoolean(false)
    private val nativeCreated = AtomicBoolean(false)
    private val handleFrameCount = AtomicLong(0)

    override fun canReceive(fromSource: Source?): Boolean {
        if (!nativeCreated.get()) return true // Accept frames before native is ready
        return NativePlaybackEngine.getBufferedFrameCount() < effectiveMaxFrames - 1
    }

    override fun handleFrame(
        frame: FloatArray,
        source: Source?,
    ) {
        if (releasedFlag.get()) return

        // Detect sample rate and frame size from first frame.
        // frameSamples is checked separately because configure() may have
        // pre-set sampleRate without knowing the frame size.
        if (frameSamples == 0) {
            frameSamples = frame.size
            if (sampleRate == 0) {
                sampleRate = source?.sampleRate ?: AudioDevice.DEFAULT_SAMPLE_RATE
                channels = source?.channels ?: 1
            }
            Log.i(TAG, "Detected: rate=$sampleRate, ch=$channels, frameSamples=$frameSamples")
        }

        // Compute buffer limits from frame time on first frame
        if (!bufferLimitsInitialized && sampleRate > 0) {
            val detectedFrameTimeMs = ((frame.size.toFloat() / (sampleRate * channels)) * 1000).toLong()
            if (detectedFrameTimeMs > 0) {
                frameTimeMs = detectedFrameTimeMs
                updateBufferLimits(detectedFrameTimeMs)
                bufferLimitsInitialized = true
            }
        }

        // Lazily create native engine on first frame (needs sampleRate + frameSamples)
        if (!nativeCreated.get()) {
            createNativeEngine()
        }

        // Convert float32 → int16 and push to native ring buffer.
        // This is the one remaining per-frame allocation (ShortArray) — eliminated in Phase 3
        // when codecs move to native and output int16 directly.
        val samples = float32ToShortArray(frame)
        NativePlaybackEngine.writeSamples(samples)

        val count = handleFrameCount.incrementAndGet()
        if (count % 100L == 0L) {
            Log.d(TAG, "handleFrame #$count, native buf=${NativePlaybackEngine.getBufferedFrameCount()}")
        }

        // Auto-start when prebuffer threshold is reached
        if (autodigest &&
            !isRunningFlag.get() &&
            NativePlaybackEngine.getBufferedFrameCount() >= effectiveAutostartMin
        ) {
            start()
        }
    }

    override fun start() {
        if (isRunningFlag.getAndSet(true)) return

        if (!nativeCreated.get()) {
            Log.w(TAG, "Cannot start: native engine not created yet")
            isRunningFlag.set(false)
            return
        }

        Log.i(TAG, "Starting Oboe stream: rate=$sampleRate, ch=$channels")
        val started = NativePlaybackEngine.startStream()
        if (!started) {
            Log.e(TAG, "Failed to start Oboe stream")
            isRunningFlag.set(false)
        }
    }

    override fun stop() {
        if (!isRunningFlag.getAndSet(false)) return

        Log.i(TAG, "Stopping Oboe stream")
        NativePlaybackEngine.stopStream()
    }

    override fun isRunning(): Boolean = isRunningFlag.get()

    /**
     * Configure sample rate explicitly (if not auto-detected from source).
     */
    fun configure(
        sampleRate: Int,
        channels: Int = 1,
    ) {
        this.sampleRate = sampleRate
        this.channels = channels
        Log.d(TAG, "Configured: rate=$sampleRate, ch=$channels")
    }

    /**
     * Release all resources.
     */
    fun release() {
        releasedFlag.set(true)
        stop()
        if (nativeCreated.getAndSet(false)) {
            NativePlaybackEngine.destroy()
        }
    }

    // --- Internal ---

    private fun createNativeEngine() {
        if (nativeCreated.get()) return

        try {
            val created =
                NativePlaybackEngine.create(
                    sampleRate = sampleRate,
                    channels = channels,
                    frameSamples = frameSamples,
                    maxBufferFrames = effectiveMaxFrames.coerceAtMost(MAX_QUEUE_SLOTS),
                    prebufferFrames = effectiveAutostartMin,
                )
            nativeCreated.set(created)
            Log.i(TAG, "Native engine created: $created (max=$effectiveMaxFrames, prebuf=$effectiveAutostartMin)")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native engine not available: ${e.message}")
        }
    }

    private fun updateBufferLimits(detectedFrameTimeMs: Long) {
        effectiveMaxFrames =
            (BUFFER_CAPACITY_MS / detectedFrameTimeMs)
                .toInt()
                .coerceIn(MAX_FRAMES, MAX_QUEUE_SLOTS)
        effectiveAutostartMin =
            (PREBUFFER_MS / detectedFrameTimeMs)
                .toInt()
                .coerceIn(AUTOSTART_MIN, effectiveMaxFrames / 2)
        Log.i(
            TAG,
            "Buffer limits: max=$effectiveMaxFrames, prebuffer=$effectiveAutostartMin " +
                "(${effectiveMaxFrames * detectedFrameTimeMs}ms/${effectiveAutostartMin * detectedFrameTimeMs}ms)",
        )
    }
}

/**
 * Convert float32 audio samples to int16 ShortArray for native engine.
 *
 * Performs clamping to [-1.0, 1.0] and scaling to int16 range.
 * This is a single allocation (the ShortArray) vs LineSink's float32ToBytes()
 * which allocates a ByteBuffer + ByteArray.
 */
internal fun float32ToShortArray(samples: FloatArray): ShortArray =
    ShortArray(samples.size) { i ->
        val clamped = samples[i].coerceIn(-1f, 1f)
        (clamped * 32767f).toInt().toShort()
    }
