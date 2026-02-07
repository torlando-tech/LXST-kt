/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package tech.torlando.lxst.telephone

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tech.torlando.lxst.audio.LineSink
import tech.torlando.lxst.codec.Codec
import tech.torlando.lxst.codec.Codec2
import tech.torlando.lxst.codec.Opus

/**
 * Real-time AudioTrack playback tests for all 8 telephony profiles.
 *
 * Unlike ProfileInstrumentedTest (pure codec math), these tests exercise
 * the actual audio pipeline with real hardware:
 *   - Real AudioTrack via AudioDevice
 *   - Real pacing at each profile's frame rate
 *   - Hardware underrun counting
 *   - Full-duplex encode+decode simultaneously
 *
 * Each profile test pushes decoded audio to LineSink at the profile's native
 * decode rate and frame time, then counts hardware underruns. This catches:
 *   - AudioTrack refusing a sample rate
 *   - Pacing drift causing underruns at certain frame times
 *   - Buffer sizing issues for stereo or high-rate profiles
 *   - CPU contention between encode and decode in full duplex
 *
 * Test duration: ~40s total (each profile plays 2-6s of real audio).
 */
@RunWith(AndroidJUnit4::class)
class ProfilePlaybackInstrumentedTest {

    private lateinit var bridge: tech.torlando.lxst.core.AudioDevice
    private var sink: LineSink? = null
    private val codecs = mutableListOf<Codec>()

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        bridge = tech.torlando.lxst.core.AudioDevice(context)
    }

    @After
    fun cleanup() {
        sink?.stop()
        sink?.release()
        sink = null
        bridge.shutdown()
        codecs.forEach { codec ->
            when (codec) {
                is Opus -> codec.release()
                is Codec2 -> codec.close()
            }
        }
        codecs.clear()
    }

    // -- Helpers --

    private var sinePhase = 0.0

    private fun generateSineFrame(sampleRate: Int, channels: Int, frameTimeMs: Int): FloatArray {
        val samplesPerChannel = sampleRate * frameTimeMs / 1000
        val totalSamples = samplesPerChannel * channels
        val frame = FloatArray(totalSamples)
        val phaseIncrement = 440.0 * 2.0 * Math.PI / sampleRate
        for (i in frame.indices) {
            val sampleIndex = i / channels
            frame[i] = (kotlin.math.sin(sinePhase + sampleIndex * phaseIncrement) * 0.3).toFloat()
        }
        sinePhase += samplesPerChannel * phaseIncrement
        return frame
    }

    /**
     * Run a steady-state playback test for a profile.
     *
     * Pushes [totalFrames] decoded audio frames to LineSink at the profile's
     * native decode rate and frame time. After [warmupFrames] frames, starts
     * counting hardware underruns. Returns the underrun count.
     */
    private fun runSteadyPlayback(
        profile: Profile,
        totalFrames: Int,
        warmupFrames: Int = 10
    ): Int {
        // Use the decode codec's parameters (what LineSink/AudioTrack receives)
        val decCodec = profile.createDecodeCodec()
        codecs.add(decCodec)
        val decRate = decCodec.preferredSamplerate!!
        val decChannels = when (decCodec) {
            is Opus -> {
                val opusProfile = when (profile) {
                    Profile.MQ, Profile.LL, Profile.ULL -> Opus.PROFILE_VOICE_HIGH
                    Profile.HQ -> Opus.PROFILE_VOICE_HIGH
                    Profile.SHQ -> Opus.PROFILE_VOICE_MAX
                    else -> error("Not Opus: ${profile.abbreviation}")
                }
                Opus.profileChannels(opusProfile)
            }
            is Codec2 -> 1
            else -> 1
        }

        sinePhase = 0.0
        sink = LineSink(bridge, autodigest = true, lowLatency = false)
        sink!!.configure(decRate, decChannels)

        var baseline = 0
        repeat(totalFrames) { i ->
            val frame = generateSineFrame(decRate, decChannels, profile.frameTimeMs)
            sink!!.handleFrame(frame)
            if (i == warmupFrames) baseline = bridge.getUnderrunCount()
            if (i < totalFrames - 1) Thread.sleep(profile.frameTimeMs.toLong())
        }

        // Let final frames drain
        Thread.sleep(profile.frameTimeMs.toLong() * 3)

        val underruns = bridge.getUnderrunCount() - baseline
        sink!!.stop()
        sink!!.release()
        sink = null
        return underruns
    }

    /**
     * Run a full-duplex test: encode (TX) and decode playback (RX) simultaneously.
     *
     * Simulates a real call where the device encodes microphone audio while
     * simultaneously decoding and playing received audio. Returns RX underruns.
     */
    private fun runFullDuplex(
        profile: Profile,
        totalFrames: Int,
        warmupFrames: Int = 10
    ): Int {
        val encCodec = profile.createCodec()
        val decCodec = profile.createDecodeCodec()
        codecs.add(encCodec)
        codecs.add(decCodec)

        val encRate = encCodec.preferredSamplerate!!
        val decRate = decCodec.preferredSamplerate!!
        val encChannels = when (encCodec) {
            is Opus -> {
                val opusProfile = when (profile) {
                    Profile.MQ, Profile.LL, Profile.ULL -> Opus.PROFILE_VOICE_MEDIUM
                    Profile.HQ -> Opus.PROFILE_VOICE_HIGH
                    Profile.SHQ -> Opus.PROFILE_VOICE_MAX
                    else -> error("Not Opus: ${profile.abbreviation}")
                }
                Opus.profileChannels(opusProfile)
            }
            is Codec2 -> 1
            else -> 1
        }
        val decChannels = when (decCodec) {
            is Opus -> {
                val opusProfile = when (profile) {
                    Profile.MQ, Profile.LL, Profile.ULL -> Opus.PROFILE_VOICE_HIGH
                    Profile.HQ -> Opus.PROFILE_VOICE_HIGH
                    Profile.SHQ -> Opus.PROFILE_VOICE_MAX
                    else -> error("Not Opus: ${profile.abbreviation}")
                }
                Opus.profileChannels(opusProfile)
            }
            is Codec2 -> 1
            else -> 1
        }

        sinePhase = 0.0
        sink = LineSink(bridge, autodigest = true, lowLatency = false)
        sink!!.configure(decRate, decChannels)

        // TX thread: continuously encodes frames (simulates microphone capture)
        var txPhase = 0.0
        val txPhaseInc = 440.0 * 2.0 * Math.PI / encRate
        val txRunning = java.util.concurrent.atomic.AtomicBoolean(true)
        val txThread = Thread {
            while (txRunning.get()) {
                val txSamples = encRate * profile.frameTimeMs / 1000 * encChannels
                val txFrame = FloatArray(txSamples) { i ->
                    val si = i / encChannels
                    (kotlin.math.sin(txPhase + si * txPhaseInc) * 0.3).toFloat()
                }
                txPhase += (txSamples / encChannels) * txPhaseInc
                try {
                    encCodec.encode(txFrame)
                } catch (_: Exception) { }
                Thread.sleep(profile.frameTimeMs.toLong())
            }
        }
        txThread.start()

        // RX: push decoded frames to LineSink (same as steady-state test)
        var baseline = 0
        repeat(totalFrames) { i ->
            val rxFrame = generateSineFrame(decRate, decChannels, profile.frameTimeMs)
            sink!!.handleFrame(rxFrame)
            if (i == warmupFrames) baseline = bridge.getUnderrunCount()
            if (i < totalFrames - 1) Thread.sleep(profile.frameTimeMs.toLong())
        }

        txRunning.set(false)
        txThread.join(1000)

        Thread.sleep(profile.frameTimeMs.toLong() * 3)

        val underruns = bridge.getUnderrunCount() - baseline
        sink!!.stop()
        sink!!.release()
        sink = null
        return underruns
    }

    // =====================================================================
    //  BASELINE: Steady-state playback, zero underruns expected
    // =====================================================================

    // -- Opus Standard --

    @Test
    fun mq_steadyPlayback_zeroUnderruns() {
        // MQ: 48kHz mono, 60ms frames, 50 frames = 3s
        val underruns = runSteadyPlayback(Profile.MQ, totalFrames = 50)
        assertEquals(
            "MQ (48kHz/60ms): expected 0 underruns during steady playback",
            0, underruns
        )
    }

    @Test
    fun hq_steadyPlayback_zeroUnderruns() {
        // HQ: 48kHz mono, 60ms frames, 50 frames = 3s
        val underruns = runSteadyPlayback(Profile.HQ, totalFrames = 50)
        assertEquals(
            "HQ (48kHz/60ms): expected 0 underruns during steady playback",
            0, underruns
        )
    }

    @Test
    fun shq_steadyPlayback_zeroUnderruns() {
        // SHQ: 48kHz stereo, 60ms frames, 50 frames = 3s
        val underruns = runSteadyPlayback(Profile.SHQ, totalFrames = 50)
        assertEquals(
            "SHQ (48kHz stereo/60ms): expected 0 underruns during steady playback",
            0, underruns
        )
    }

    // -- Opus Low-Latency --

    @Test
    fun ll_steadyPlayback_zeroUnderruns() {
        // LL: 48kHz mono, 20ms frames, 100 frames = 2s
        val underruns = runSteadyPlayback(Profile.LL, totalFrames = 100)
        assertEquals(
            "LL (48kHz/20ms): expected 0 underruns during steady playback",
            0, underruns
        )
    }

    @Test
    fun ull_steadyPlayback_zeroUnderruns() {
        // ULL: 48kHz mono, 10ms frames, 200 frames = 2s
        val underruns = runSteadyPlayback(Profile.ULL, totalFrames = 200)
        assertEquals(
            "ULL (48kHz/10ms): expected 0 underruns during steady playback",
            0, underruns
        )
    }

    // -- Codec2 --

    @Test
    fun ulbw_steadyPlayback_zeroUnderruns() {
        // ULBW: 8kHz mono, 400ms frames, 15 frames = 6s
        val underruns = runSteadyPlayback(Profile.ULBW, totalFrames = 15, warmupFrames = 5)
        assertEquals(
            "ULBW (8kHz/400ms): expected 0 underruns during steady playback",
            0, underruns
        )
    }

    @Test
    fun vlbw_steadyPlayback_zeroUnderruns() {
        // VLBW: 8kHz mono, 320ms frames, 15 frames = 4.8s
        val underruns = runSteadyPlayback(Profile.VLBW, totalFrames = 15, warmupFrames = 5)
        assertEquals(
            "VLBW (8kHz/320ms): expected 0 underruns during steady playback",
            0, underruns
        )
    }

    @Test
    fun lbw_steadyPlayback_zeroUnderruns() {
        // LBW: 8kHz mono, 200ms frames, 20 frames = 4s
        val underruns = runSteadyPlayback(Profile.LBW, totalFrames = 20, warmupFrames = 5)
        assertEquals(
            "LBW (8kHz/200ms): expected 0 underruns during steady playback",
            0, underruns
        )
    }

    // =====================================================================
    //  SUSTAINED GAP + RE-BUFFER: Key profiles across the frame-time range
    // =====================================================================

    @Test
    fun mq_sustainedGap_rebufferThenCleanPlayback() {
        // MQ (60ms): 400ms gap → re-buffer → verify clean resumption
        sink = LineSink(bridge, autodigest = true, lowLatency = false)
        sink!!.configure(48000, 1)
        sinePhase = 0.0

        // Phase 1: steady delivery (1.5s)
        repeat(25) {
            sink!!.handleFrame(generateSineFrame(48000, 1, 60))
            Thread.sleep(60)
        }

        // Sustained gap
        Thread.sleep(400)

        // Burst fill for re-buffer
        repeat(LineSink.AUTOSTART_MIN) {
            sink!!.handleFrame(generateSineFrame(48000, 1, 60))
        }
        Thread.sleep(200)

        // Phase 2: measure steady-state underruns
        val baseline = bridge.getUnderrunCount()
        repeat(25) { i ->
            sink!!.handleFrame(generateSineFrame(48000, 1, 60))
            if (i < 24) Thread.sleep(60)
        }
        Thread.sleep(200)

        val underruns = bridge.getUnderrunCount() - baseline
        assertEquals(
            "MQ: expected 0 underruns in post-rebuffer steady state",
            0, underruns
        )
    }

    @Test
    fun ll_sustainedGap_rebufferThenCleanPlayback() {
        // LL (20ms): shorter frames mean faster re-buffer (5×20ms=100ms vs 5×60ms=300ms)
        sink = LineSink(bridge, autodigest = true, lowLatency = false)
        sink!!.configure(48000, 1)
        sinePhase = 0.0

        // Phase 1: steady delivery (50 frames = 1s)
        repeat(50) {
            sink!!.handleFrame(generateSineFrame(48000, 1, 20))
            Thread.sleep(20)
        }

        // Sustained gap
        Thread.sleep(200)

        // Burst fill for re-buffer
        repeat(LineSink.AUTOSTART_MIN) {
            sink!!.handleFrame(generateSineFrame(48000, 1, 20))
        }
        Thread.sleep(100)

        // Phase 2: measure
        val baseline = bridge.getUnderrunCount()
        repeat(50) { i ->
            sink!!.handleFrame(generateSineFrame(48000, 1, 20))
            if (i < 49) Thread.sleep(20)
        }
        Thread.sleep(100)

        val underruns = bridge.getUnderrunCount() - baseline
        assertEquals(
            "LL: expected 0 underruns in post-rebuffer steady state",
            0, underruns
        )
    }

    @Test
    fun ulbw_sustainedGap_rebufferThenCleanPlayback() {
        // ULBW (400ms): huge frames, long re-buffer (5×400ms=2000ms)
        sink = LineSink(bridge, autodigest = true, lowLatency = false)
        sink!!.configure(8000, 1)
        sinePhase = 0.0

        // Phase 1: steady delivery (8 frames = 3.2s)
        repeat(8) {
            sink!!.handleFrame(generateSineFrame(8000, 1, 400))
            Thread.sleep(400)
        }

        // Sustained gap (1.5s)
        Thread.sleep(1500)

        // Burst fill for re-buffer
        repeat(LineSink.AUTOSTART_MIN) {
            sink!!.handleFrame(generateSineFrame(8000, 1, 400))
        }
        Thread.sleep(1000) // re-buffer needs time with 400ms frames

        // Phase 2: measure
        val baseline = bridge.getUnderrunCount()
        repeat(8) { i ->
            sink!!.handleFrame(generateSineFrame(8000, 1, 400))
            if (i < 7) Thread.sleep(400)
        }
        Thread.sleep(500)

        val underruns = bridge.getUnderrunCount() - baseline
        assertEquals(
            "ULBW: expected 0 underruns in post-rebuffer steady state",
            0, underruns
        )
    }

    // =====================================================================
    //  FULL DUPLEX: Simultaneous encode (TX) + decode playback (RX)
    // =====================================================================

    @Test
    fun mq_fullDuplex_rxUnderrunsZero() {
        // MQ full duplex: encode at 24kHz while playing decoded at 48kHz.
        // TX encode runs on a background thread competing for CPU.
        // RX playback should still have 0 underruns.
        val underruns = runFullDuplex(Profile.MQ, totalFrames = 50)
        assertEquals(
            "MQ full duplex: expected 0 RX underruns while TX encodes concurrently",
            0, underruns
        )
    }

    @Test
    fun hq_fullDuplex_rxUnderrunsZero() {
        // HQ full duplex: encode AND decode both at 48kHz — higher CPU load.
        val underruns = runFullDuplex(Profile.HQ, totalFrames = 50)
        assertEquals(
            "HQ full duplex: expected 0 RX underruns while TX encodes concurrently",
            0, underruns
        )
    }

    @Test
    fun ll_fullDuplex_rxUnderrunsZero() {
        // LL full duplex: 20ms frames = 50 encode+decode cycles per second.
        // Higher throughput pressure than MQ's 16.7 cycles/sec.
        val underruns = runFullDuplex(Profile.LL, totalFrames = 100)
        assertEquals(
            "LL full duplex: expected 0 RX underruns with 50 encode+decode cycles/sec",
            0, underruns
        )
    }

    @Test
    fun ull_fullDuplex_rxUnderrunsZero() {
        // ULL full duplex: 10ms frames = 100 encode+decode cycles per second.
        // Maximum CPU pressure — if any profile will show contention, it's this one.
        val underruns = runFullDuplex(Profile.ULL, totalFrames = 200)
        assertEquals(
            "ULL full duplex: expected 0 RX underruns with 100 encode+decode cycles/sec",
            0, underruns
        )
    }

    @Test
    fun shq_fullDuplex_rxUnderrunsZero() {
        // SHQ full duplex: 48kHz stereo — double the sample data per frame.
        val underruns = runFullDuplex(Profile.SHQ, totalFrames = 50)
        assertEquals(
            "SHQ full duplex (stereo): expected 0 RX underruns",
            0, underruns
        )
    }

    // =====================================================================
    //  PROFILE SWITCH MID-PLAYBACK: Codec and rate change without pops
    // =====================================================================

    @Test
    fun profileSwitch_mqToLl_noUnderrunsDuringTransition() {
        // Simulate mid-call profile switch: MQ (60ms) → LL (20ms).
        // The LineSink must handle the frame size change gracefully.
        sinePhase = 0.0

        // Phase 1: MQ playback (1.5s at 48kHz/60ms)
        sink = LineSink(bridge, autodigest = true, lowLatency = false)
        sink!!.configure(48000, 1)

        repeat(25) {
            sink!!.handleFrame(generateSineFrame(48000, 1, 60))
            Thread.sleep(60)
        }

        val baseline = bridge.getUnderrunCount()

        // Phase 2: Switch to LL frame size (20ms) — same rate, smaller frames.
        // LineSink should adapt to the new frame size without underrunning.
        repeat(75) { i ->
            sink!!.handleFrame(generateSineFrame(48000, 1, 20))
            if (i < 74) Thread.sleep(20)
        }
        Thread.sleep(100)

        val underruns = bridge.getUnderrunCount() - baseline
        assertEquals(
            "MQ→LL transition: expected 0 underruns (same rate, smaller frames)",
            0, underruns
        )
    }

    @Test
    fun profileSwitch_hqToUlbw_rateChangeCleanly() {
        // Extreme switch: HQ (48kHz/60ms) → ULBW (8kHz/400ms).
        // Requires tearing down and reconfiguring LineSink with new sample rate.
        sinePhase = 0.0

        // Phase 1: HQ playback (1.5s)
        sink = LineSink(bridge, autodigest = true, lowLatency = false)
        sink!!.configure(48000, 1)

        repeat(25) {
            sink!!.handleFrame(generateSineFrame(48000, 1, 60))
            Thread.sleep(60)
        }

        // Tear down and reconfigure (same as Telephone.switchProfile does)
        sink!!.stop()
        sink!!.release()

        sink = LineSink(bridge, autodigest = true, lowLatency = false)
        sink!!.configure(8000, 1)

        // Phase 2: ULBW playback (3.2s at 8kHz/400ms)
        var baseline = 0
        repeat(8) { i ->
            sink!!.handleFrame(generateSineFrame(8000, 1, 400))
            if (i == 3) baseline = bridge.getUnderrunCount()
            if (i < 7) Thread.sleep(400)
        }
        Thread.sleep(500)

        val underruns = bridge.getUnderrunCount() - baseline
        assertEquals(
            "HQ→ULBW transition: expected 0 underruns after reconfigure to 8kHz",
            0, underruns
        )
    }
}
