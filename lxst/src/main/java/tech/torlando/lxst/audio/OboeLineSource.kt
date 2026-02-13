/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package tech.torlando.lxst.audio

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.torlando.lxst.codec.Codec
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

/**
 * OboeLineSource - Oboe-backed microphone capture for LXST audio pipeline.
 *
 * Drop-in replacement for [LineSource] that routes microphone audio through a
 * native Oboe input stream instead of Java AudioRecord. The benefits:
 *
 * 1. **SCHED_FIFO thread**: Oboe capture callback runs on a real-time priority
 *    thread, eliminating scheduling jitter from the CFS scheduler.
 * 2. **Native voice filters**: HPF/LPF/AGC run in C++ on the capture callback
 *    thread — no JNI crossings or Kotlin allocations for filter processing.
 * 3. **InputPreset::VoiceCommunication**: Tells the platform to apply AEC with
 *    explicit reference to the output stream (better than MIC + MODE_IN_COMMUNICATION).
 * 4. **Zero per-frame allocations on capture side**: Lock-free SPSC ring buffer
 *    is pre-allocated. No LinkedBlockingQueue or ByteArray.copyOf().
 *
 * The source reads filtered int16 frames from the native ring buffer via JNI,
 * converts to float32 (one ShortArray read per frame), applies gain, and pushes
 * to the transmit Mixer.
 *
 * @param codec        Codec instance (determines sample rate, frame time alignment)
 * @param targetFrameMs Target frame duration in milliseconds (default 80ms)
 * @param gain         Audio gain multiplier (1.0 = unity)
 */
class OboeLineSource(
    private val codec: Codec,
    targetFrameMs: Int = 80,
    private val gain: Float = 1.0f,
) : LocalSource() {
    companion object {
        private const val TAG = "Columba:OboeLineSource"
        private const val DEFAULT_SAMPLE_RATE = 48000
        private const val DEFAULT_CHANNELS = 1

        // Ring buffer sizing (same policy as OboeLineSink)
        const val BUFFER_CAPACITY_MS = 1500L
        const val MAX_QUEUE_SLOTS = 150
        const val MAX_FRAMES = 15
    }

    /** Sink to push captured frames to (set by Telephone/Pipeline) */
    var sink: Sink? = null

    // Audio configuration (derived from codec, same as LineSource)
    override var sampleRate: Int = DEFAULT_SAMPLE_RATE
    override var channels: Int = DEFAULT_CHANNELS

    private val samplesPerFrame: Int
    private val frameTimeMs: Int
    private val effectiveMaxFrames: Int

    // Coroutine management
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRunningFlag = AtomicBoolean(false)
    private val nativeCreated = AtomicBoolean(false)
    private val releasedFlag = AtomicBoolean(false)

    init {
        // Query codec for preferred sample rate (mirrors LineSource init)
        sampleRate = codec.preferredSamplerate ?: DEFAULT_SAMPLE_RATE

        // Adjust frame time based on codec constraints
        frameTimeMs = adjustFrameTime(targetFrameMs, codec)

        // Calculate samples per frame
        samplesPerFrame = ((frameTimeMs / 1000f) * sampleRate).toInt()

        // Compute buffer limits from frame time
        effectiveMaxFrames =
            (BUFFER_CAPACITY_MS / frameTimeMs)
                .toInt()
                .coerceIn(MAX_FRAMES, MAX_QUEUE_SLOTS)

        Log.d(
            TAG,
            "Init: rate=$sampleRate, frameMs=$frameTimeMs, samples=$samplesPerFrame, " +
                "gain=$gain, maxFrames=$effectiveMaxFrames",
        )
    }

    override fun start() {
        if (isRunningFlag.getAndSet(true)) {
            Log.w(TAG, "OboeLineSource already running")
            return
        }

        Log.i(TAG, "Starting OboeLineSource: rate=$sampleRate, samples=$samplesPerFrame")

        // Create native capture engine
        if (!nativeCreated.get()) {
            try {
                val created =
                    NativeCaptureEngine.create(
                        sampleRate = sampleRate,
                        channels = channels,
                        frameSamples = samplesPerFrame,
                        maxBufferFrames = effectiveMaxFrames.coerceAtMost(MAX_QUEUE_SLOTS),
                        enableFilters = true,
                    )
                nativeCreated.set(created)
                Log.i(TAG, "Native capture engine created: $created")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Native capture engine not available: ${e.message}")
                isRunningFlag.set(false)
                return
            }
        }

        // Start Oboe input stream
        val started = NativeCaptureEngine.startStream()
        if (!started) {
            Log.e(TAG, "Failed to start Oboe input stream")
            isRunningFlag.set(false)
            return
        }

        // Launch consumer coroutine
        scope.launch { ingestJob() }
    }

    override fun stop() {
        if (!isRunningFlag.getAndSet(false)) {
            Log.w(TAG, "OboeLineSource not running")
            return
        }

        Log.i(TAG, "Stopping OboeLineSource")
        NativeCaptureEngine.stopStream()
    }

    override fun isRunning(): Boolean = isRunningFlag.get()

    /**
     * Release all resources.
     */
    fun release() {
        releasedFlag.set(true)
        stop()
        if (nativeCreated.getAndSet(false)) {
            NativeCaptureEngine.destroy()
        }
        scope.cancel()
    }

    // --- Internal ---

    /**
     * Main consumer loop — reads filtered frames from native ring buffer.
     *
     * Runs on Dispatchers.IO. The native Oboe callback (SCHED_FIFO) is the
     * producer; this coroutine is the consumer. The lock-free SPSC ring buffer
     * ensures zero contention between threads.
     */
    private suspend fun ingestJob() {
        Log.d(TAG, "Ingest job started")
        val shortBuffer = ShortArray(samplesPerFrame)
        var frameCount = 0L

        while (isRunningFlag.get() && !releasedFlag.get()) {
            if (NativeCaptureEngine.readSamples(shortBuffer)) {
                frameCount++

                // Convert int16 → float32
                val floatSamples = shortToFloat32(shortBuffer)

                // Apply gain if not unity
                val gained =
                    if (gain != 1.0f) {
                        FloatArray(floatSamples.size) { i -> floatSamples[i] * gain }
                    } else {
                        floatSamples
                    }

                // Push to sink (transmit Mixer)
                val currentSink = sink
                if (currentSink != null && currentSink.canReceive(this)) {
                    currentSink.handleFrame(gained, this)
                } else if (currentSink != null && frameCount % 50L == 0L) {
                    Log.w(TAG, "Sink backpressure, dropping frame #$frameCount")
                }

                if (frameCount % 100L == 0L) {
                    Log.d(TAG, "ingestJob #$frameCount, buf=${NativeCaptureEngine.getBufferedFrameCount()}")
                }
            } else {
                // Ring buffer empty — brief yield to avoid busy-spin
                delay(2)
            }
        }

        Log.d(TAG, "Ingest job ended, captured $frameCount frames")
    }

    /**
     * Adjust frame time to match codec constraints.
     * (Same logic as LineSource.adjustFrameTime)
     */
    private fun adjustFrameTime(
        targetMs: Int,
        codec: Codec,
    ): Int {
        var adjusted = targetMs

        codec.frameQuantaMs?.let { quanta ->
            if (adjusted % quanta.toInt() != 0) {
                adjusted = (ceil(adjusted / quanta) * quanta).toInt()
            }
        }

        codec.frameMaxMs?.let { maxMs ->
            if (adjusted > maxMs.toInt()) {
                adjusted = maxMs.toInt()
            }
        }

        codec.validFrameMs?.let { validSizes ->
            val closest = validSizes.minByOrNull { kotlin.math.abs(it - adjusted) }
            if (closest != null && closest.toInt() != adjusted) {
                adjusted = closest.toInt()
            }
        }

        return adjusted
    }
}

/**
 * Convert int16 ShortArray to float32 FloatArray.
 *
 * Inverse of [float32ToShortArray] in OboeLineSink.kt.
 */
internal fun shortToFloat32(samples: ShortArray): FloatArray =
    FloatArray(samples.size) { i ->
        samples[i] / 32768f
    }
