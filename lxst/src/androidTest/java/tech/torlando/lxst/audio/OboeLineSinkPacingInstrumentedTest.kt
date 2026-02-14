/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package tech.torlando.lxst.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented pacing test for OboeLineSink with native Oboe output.
 *
 * Mirrors [LineSinkPacingInstrumentedTest] but exercises the Oboe playback path:
 *   - Native SCHED_FIFO audio callback thread
 *   - Lock-free SPSC ring buffer
 *   - Oboe xrun counting (instead of AudioTrack underrun count)
 *
 * Feeds synthetic sine wave frames to OboeLineSink and asserts zero xruns
 * during sustained playback. This verifies that the native engine correctly
 * paces output from the ring buffer without starving the Oboe callback.
 *
 * Runs on a single device — no second device or real call needed.
 */
@RunWith(AndroidJUnit4::class)
class OboeLineSinkPacingInstrumentedTest {
    private lateinit var sink: OboeLineSink

    // MQ profile: 48kHz mono, 20ms frames = 960 samples
    private val sampleRate = 48000
    private val channels = 1
    private val frameSize = 960 // 20ms at 48kHz

    // Warmup: 25 frames = 500ms — enough for prebuffer to fill and stream to start
    private val warmupFrames = 25

    @Before
    fun setup() {
        sinePhase = 0.0
    }

    @After
    fun cleanup() {
        if (::sink.isInitialized) {
            sink.stop()
            sink.release()
        }
    }

    private var sinePhase = 0.0

    private fun generateSineFrame(): FloatArray {
        val frame = FloatArray(frameSize)
        val phaseIncrement = 440.0 * 2.0 * Math.PI / sampleRate
        for (i in frame.indices) {
            frame[i] = (kotlin.math.sin(sinePhase) * 0.3).toFloat()
            sinePhase += phaseIncrement
        }
        return frame
    }

    @Test
    fun steadyStatePlayback_zeroXruns() {
        sink = OboeLineSink(autodigest = true)
        sink.configure(sampleRate, channels)

        // Push 175 frames (3.5s) at 20ms: capture baseline at frame 25
        var baseline = 0
        repeat(175) { i ->
            sink.handleFrame(generateSineFrame())
            if (i == warmupFrames) baseline = NativePlaybackEngine.getXRunCount()
            if (i < 174) Thread.sleep(20)
        }

        val steadyStateXruns = NativePlaybackEngine.getXRunCount() - baseline
        assertEquals(
            "Expected 0 xruns during steady-state Oboe playback (baseline=$baseline)",
            0,
            steadyStateXruns,
        )
    }

    @Test
    fun burstThenSteady_zeroXruns() {
        sink = OboeLineSink(autodigest = true)
        sink.configure(sampleRate, channels)

        // Initial burst: push MAX_FRAMES instantly
        repeat(OboeLineSink.MAX_FRAMES) {
            sink.handleFrame(generateSineFrame())
        }

        // Then steady for 2.5s, baseline after warmup
        var baseline = 0
        repeat(125) { i ->
            sink.handleFrame(generateSineFrame())
            if (i == warmupFrames) baseline = NativePlaybackEngine.getXRunCount()
            if (i < 124) Thread.sleep(20)
        }

        val steadyStateXruns = NativePlaybackEngine.getXRunCount() - baseline
        assertEquals(
            "Expected 0 xruns after burst-then-steady Oboe playback (baseline=$baseline)",
            0,
            steadyStateXruns,
        )
    }

    @Test
    fun fastProducer_zeroXruns() {
        // Frames arrive at 1.5x real-time (simulates Reticulum burst delivery).
        sink = OboeLineSink(autodigest = true)
        sink.configure(sampleRate, channels)

        var baseline = 0
        repeat(125) { i ->
            sink.handleFrame(generateSineFrame())
            if (i == warmupFrames) baseline = NativePlaybackEngine.getXRunCount()
            if (i < 124) Thread.sleep(13) // 1.5x faster than 20ms
        }

        val steadyStateXruns = NativePlaybackEngine.getXRunCount() - baseline
        assertEquals(
            "Expected 0 xruns during fast-producer Oboe playback (baseline=$baseline)",
            0,
            steadyStateXruns,
        )
    }

    @Test
    fun underrunRecovery_resumesCleanly() {
        // Play, starve, resume. Xruns during the gap are expected.
        // What matters is that recovery doesn't cause additional xruns.
        sink = OboeLineSink(autodigest = true)
        sink.configure(sampleRate, channels)

        // Phase 1: continuous playback for 1.5s
        repeat(75) {
            sink.handleFrame(generateSineFrame())
            Thread.sleep(20)
        }
        Thread.sleep(300) // drain + starve -> xrun expected

        val xrunsAfterGap = NativePlaybackEngine.getXRunCount()

        // Phase 2: resume for 1.5s
        repeat(75) {
            sink.handleFrame(generateSineFrame())
            Thread.sleep(20)
        }

        val recoveryXruns = NativePlaybackEngine.getXRunCount() - xrunsAfterGap
        assertTrue(
            "Recovery should add at most 1 xrun (transient), got $recoveryXruns",
            recoveryXruns <= 1,
        )
    }

    @Test
    fun partialFrameAlignment_bufferDrainsAtAudioRate() {
        // Regression test for partial frame discard bug in onAudioReady().
        //
        // When the Oboe hardware burst size is smaller than the LXST frame size, the
        // callback's partial-frame path reads a full LXST frame from the ring buffer
        // but only copies burst-many samples to the output, discarding the remainder.
        // Example: burst=960, frameSamples=3840 → copies 960, discards 2880 (75% loss).
        //
        // The fix must track a partial frame offset across callbacks so that one LXST
        // frame is consumed over multiple burst-sized callbacks.
        //
        // IMPORTANT: We use 3840-sample frames (80ms at 48kHz) instead of the default
        // 960-sample frames (20ms). Some devices (e.g., Samsung S21 Ultra in AAudio
        // exclusive mode) negotiate burst=960, matching the default LXST frame size
        // and never triggering the partial path. Using 3840 guarantees burst < frameSamples
        // on all hardware, since no device has burst >= 3840 for voice communication.
        //
        // We detect the bug by measuring ring buffer drain rate:
        //   CORRECT: 16 frames * 80ms = 1280ms to drain at real-time audio rate
        //   BUGGY:   Each burst callback (~20ms) eats a full 80ms frame → 4x faster drain
        //
        // After 600ms (~47% of expected drain), we check remaining frame count.

        val testFrameSize = 3840 // 80ms at 48kHz — always > any hardware burst

        sink = OboeLineSink(autodigest = false) // Manual start for precise control
        sink.configure(sampleRate, channels)

        // Buffer capacity for 80ms frames: 1500ms / 80ms = 18 slots.
        // Load 16 frames (1280ms of audio) — fits comfortably in the ring buffer.
        val framesToLoad = 16

        // Generate and push large frames
        var phase = 0.0
        val phaseInc = 440.0 * 2.0 * Math.PI / sampleRate
        repeat(framesToLoad) {
            val frame =
                FloatArray(testFrameSize) { i ->
                    val sample = (kotlin.math.sin(phase) * 0.3).toFloat()
                    phase += phaseInc
                    sample
                }
            sink.handleFrame(frame)
        }

        // Verify frames are buffered
        val initialBuffered = NativePlaybackEngine.getBufferedFrameCount()
        assertTrue(
            "Should have loaded ~$framesToLoad frames, got $initialBuffered",
            initialBuffered >= framesToLoad - 2,
        )

        // Start stream — the Oboe callback begins draining the ring buffer
        sink.start()
        assertTrue("Sink should be running", sink.isRunning())

        // Wait 600ms — ~47% of expected 1280ms real-time drain duration
        Thread.sleep(600)

        val remaining = NativePlaybackEngine.getBufferedFrameCount()

        // With correct playback (real-time rate):
        //   600ms / 80ms-per-frame ≈ 7.5 frames consumed → ~8 remaining
        // With partial frame discard bug (burst=960 at 48kHz):
        //   Each ~20ms callback eats a full 80ms frame → 16 gone in ~320ms → 0 remaining
        // With partial frame discard bug (burst=192 at 48kHz):
        //   Each ~4ms callback eats a full frame → 16 gone in ~64ms → 0 remaining
        //
        // Threshold: at least 25% of loaded frames must remain after ~47% of
        // expected drain time. Generous enough for timing jitter while catching
        // the discard bug on any device where burst < frameSamples.
        val minRemaining = framesToLoad / 4 // 4 frames
        assertTrue(
            "After 600ms, at least $minRemaining of $framesToLoad 80ms-frames should remain " +
                "(got $remaining). If near 0, the Oboe callback is consuming full LXST " +
                "frames per burst instead of tracking partial frame offsets across callbacks.",
            remaining >= minRemaining,
        )
    }

    @Test
    fun engineLifecycle_createStartStopDestroy() {
        // Verify the full native engine lifecycle works without crashes.
        sink = OboeLineSink(autodigest = true)
        sink.configure(sampleRate, channels)

        // Runtime autostart threshold: PREBUFFER_MS / frameTimeMs = 500/20 = 25
        // Push enough frames to exceed that threshold.
        val autostartThreshold = (OboeLineSink.PREBUFFER_MS / 20).toInt()
        repeat(autostartThreshold + 2) {
            sink.handleFrame(generateSineFrame())
        }
        Thread.sleep(200) // Let autostart kick in

        assertTrue("Sink should be running after autostart (pushed ${autostartThreshold + 2} frames, threshold=$autostartThreshold)", sink.isRunning())
        assertTrue("Native engine should be playing", NativePlaybackEngine.isPlaying())

        sink.stop()
        Thread.sleep(100)

        assertTrue("Sink should stop", !sink.isRunning())

        sink.release()
        // No crash = success
    }
}
