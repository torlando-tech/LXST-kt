/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package tech.torlando.lxst.audio

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tech.torlando.lxst.codec.Opus
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Instrumented capture tests for the Oboe audio pipeline.
 *
 * Mirrors [LineSourceCaptureInstrumentedTest] but exercises the Oboe capture path:
 *   - Native SCHED_FIFO capture callback thread
 *   - Native voice filter chain (HPF → LPF → AGC in C++)
 *   - Lock-free SPSC ring buffer (Oboe callback → Kotlin consumer)
 *   - InputPreset::VoiceCommunication for platform AEC
 *
 * Verifies that the Oboe capture engine delivers frames at expected rate,
 * the native filter chain processes audio correctly, and OboeLineSource
 * delivers frames end-to-end without drops under steady state.
 *
 * Requires RECORD_AUDIO permission (auto-granted by GrantPermissionRule).
 * Runs on a single device — no second device or real call needed.
 */
@RunWith(AndroidJUnit4::class)
class OboeLineSourceCaptureInstrumentedTest {
    @get:Rule
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val sampleRate = 48000
    private val channels = 1
    private val samplesPerFrame = 960 // 20ms at 48kHz

    // Ring buffer sizing for tests (matching OboeLineSource defaults)
    private val maxBufferFrames = 75 // 1500ms / 20ms

    @Before
    fun setup() {
        // Ensure no stale engine from previous test
        try {
            NativeCaptureEngine.destroy()
        } catch (_: UnsatisfiedLinkError) {
            // First test — library not loaded yet, that's fine
        }
    }

    @After
    fun cleanup() {
        try {
            NativeCaptureEngine.stopStream()
            NativeCaptureEngine.destroy()
        } catch (_: Exception) {
            // Ignore cleanup errors
        }
    }

    @Test
    fun captureDelivers_framesAtExpectedRate() {
        // Create and start native capture engine
        val created =
            NativeCaptureEngine.create(
                sampleRate,
                channels,
                samplesPerFrame,
                maxBufferFrames,
                enableFilters = true,
            )
        assertTrue("Native capture engine should be created", created)

        val started = NativeCaptureEngine.startStream()
        assertTrue("Oboe input stream should start", started)

        // Wait for capture to stabilize
        Thread.sleep(500)

        // Read 50 frames and measure delivery intervals
        val buffer = ShortArray(samplesPerFrame)
        val timestamps = mutableListOf<Long>()

        // Discard warmup frames
        repeat(5) {
            while (!NativeCaptureEngine.readSamples(buffer)) Thread.sleep(2)
        }

        // Measure 50 frame delivery times
        repeat(50) {
            while (!NativeCaptureEngine.readSamples(buffer)) Thread.sleep(1)
            timestamps.add(System.nanoTime())
        }

        val intervals = timestamps.zipWithNext { a, b -> (b - a) / 1_000_000L }
        assertTrue("Need >=30 intervals, got ${intervals.size}", intervals.size >= 30)

        val avgInterval = intervals.average()
        // Oboe capture with 960-sample frames at 48kHz = 20ms per frame
        // Allow wider range because native ring buffer accumulation adds some jitter
        assertTrue(
            "Average capture interval ${avgInterval}ms should be 10-40ms (target 20ms)",
            avgInterval in 10.0..40.0,
        )
    }

    @Test
    fun nativeFilterChain_producesNonSilentOutput() {
        // Verify the native filter chain doesn't zero out the audio.
        // Real mic input should produce non-zero samples after filtering.
        val created =
            NativeCaptureEngine.create(
                sampleRate,
                channels,
                samplesPerFrame,
                maxBufferFrames,
                enableFilters = true,
            )
        assertTrue("Native capture engine should be created", created)

        val started = NativeCaptureEngine.startStream()
        assertTrue("Oboe input stream should start", started)

        // Capture for 1 second
        Thread.sleep(1000)

        val buffer = ShortArray(samplesPerFrame)
        var nonZeroFrames = 0
        var totalFrames = 0

        // Read all available frames
        while (NativeCaptureEngine.readSamples(buffer)) {
            totalFrames++
            val hasNonZero = buffer.any { it.toInt() != 0 }
            if (hasNonZero) nonZeroFrames++
        }

        assertTrue("Should have captured >=10 frames (got $totalFrames)", totalFrames >= 10)
        // Most frames should have non-zero content (real mic in quiet room still has noise)
        assertTrue(
            "At least 50% of frames should have non-zero audio after filtering " +
                "(got $nonZeroFrames/$totalFrames)",
            nonZeroFrames >= totalFrames / 2,
        )
    }

    @Test
    fun captureWithoutFilters_alsoWorks() {
        // Verify the engine works with filters disabled
        val created =
            NativeCaptureEngine.create(
                sampleRate,
                channels,
                samplesPerFrame,
                maxBufferFrames,
                enableFilters = false,
            )
        assertTrue("Native capture engine should be created", created)

        val started = NativeCaptureEngine.startStream()
        assertTrue("Oboe input stream should start", started)

        Thread.sleep(500)

        val buffer = ShortArray(samplesPerFrame)
        var framesRead = 0
        while (NativeCaptureEngine.readSamples(buffer)) {
            framesRead++
        }

        assertTrue("Should have captured >=10 frames without filters (got $framesRead)", framesRead >= 10)
    }

    @Test
    fun endToEnd_oboeLineSourceDeliversFramesToSink() {
        val opus = Opus(Opus.PROFILE_VOICE_MEDIUM)
        val receivedCount = AtomicInteger(0)
        val firstFrameNs = AtomicLong(0)
        val lastFrameNs = AtomicLong(0)

        // Test sink that counts received frames
        val testSink =
            object : LocalSink() {
                override fun canReceive(fromSource: Source?): Boolean = true

                override fun handleFrame(
                    frame: FloatArray,
                    source: Source?,
                ) {
                    val now = System.nanoTime()
                    firstFrameNs.compareAndSet(0, now)
                    lastFrameNs.set(now)
                    receivedCount.incrementAndGet()
                }

                override fun start() {}

                override fun stop() {}

                override fun isRunning(): Boolean = true
            }

        val source = OboeLineSource(codec = opus, targetFrameMs = 80, gain = 1.0f)
        source.sink = testSink
        source.start()

        // Capture for 3 seconds
        Thread.sleep(3000)

        source.stop()
        source.release()
        opus.release()

        val count = receivedCount.get()
        // At 80ms frames, expect ~37 frames in 3s.
        // Allow wide margin for startup latency and scheduling.
        assertTrue(
            "Should receive >=20 frames in 3s (got $count)",
            count >= 20,
        )

        // Verify frames arrived over a reasonable time span (not all at once)
        val first = firstFrameNs.get()
        val last = lastFrameNs.get()
        if (first > 0 && last > first) {
            val spanMs = (last - first) / 1_000_000L
            assertTrue(
                "Frames should span >=1000ms (got ${spanMs}ms) — not burst-delivered",
                spanMs >= 1000,
            )
        }
    }

    @Test
    fun endToEnd_backpressureHandledGracefully() {
        val opus = Opus(Opus.PROFILE_VOICE_MEDIUM)
        val receivedCount = AtomicInteger(0)

        // Slow sink: blocks for 100ms per frame (5x slower than 20ms frame period).
        // OboeLineSource should detect canReceive() == false and drop, not crash.
        val slowSink =
            object : LocalSink() {
                private val busy =
                    java.util.concurrent.atomic
                        .AtomicBoolean(false)

                override fun canReceive(fromSource: Source?): Boolean = !busy.get()

                override fun handleFrame(
                    frame: FloatArray,
                    source: Source?,
                ) {
                    busy.set(true)
                    receivedCount.incrementAndGet()
                    Thread.sleep(100) // simulate slow consumer
                    busy.set(false)
                }

                override fun start() {}

                override fun stop() {}

                override fun isRunning(): Boolean = true
            }

        val source = OboeLineSource(codec = opus, targetFrameMs = 80, gain = 1.0f)
        source.sink = slowSink
        source.start()

        // Run for 2 seconds — should not crash or OOM
        Thread.sleep(2000)

        source.stop()
        source.release()
        opus.release()

        // Some frames received (slow sink processes ~20 in 2s at 100ms each)
        val count = receivedCount.get()
        assertTrue(
            "Slow sink should still receive some frames (got $count)",
            count >= 5,
        )
        // The key assertion: we got here without crashing
    }

    @Test
    fun engineLifecycle_createStartStopDestroy() {
        // Verify the full native capture engine lifecycle works without crashes.
        val created =
            NativeCaptureEngine.create(
                sampleRate,
                channels,
                samplesPerFrame,
                maxBufferFrames,
                enableFilters = true,
            )
        assertTrue("Engine should be created", created)

        val started = NativeCaptureEngine.startStream()
        assertTrue("Stream should start", started)

        assertTrue("Engine should be recording", NativeCaptureEngine.isRecording())

        // Let it capture for a bit
        Thread.sleep(500)

        // Read some frames to verify data flow
        val buffer = ShortArray(samplesPerFrame)
        var framesRead = 0
        while (NativeCaptureEngine.readSamples(buffer)) framesRead++
        assertTrue("Should have buffered some frames (got $framesRead)", framesRead > 0)

        NativeCaptureEngine.stopStream()
        Thread.sleep(100)

        assertTrue("Engine should stop recording", !NativeCaptureEngine.isRecording())

        NativeCaptureEngine.destroy()
        // No crash = success
    }
}
