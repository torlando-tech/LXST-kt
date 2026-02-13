/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package tech.torlando.lxst.telephone

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tech.torlando.lxst.audio.NativePlaybackEngine
import tech.torlando.lxst.audio.OboeLineSink
import tech.torlando.lxst.codec.Codec
import tech.torlando.lxst.codec.Codec2
import tech.torlando.lxst.codec.Opus

/**
 * Real-time Oboe playback tests for all 8 telephony profiles.
 *
 * Mirrors [ProfilePlaybackInstrumentedTest] but uses OboeLineSink (native Oboe)
 * instead of LineSink (Java AudioTrack). Compares xrun counts to verify that
 * the native SCHED_FIFO audio path achieves the same or better pacing quality.
 *
 * Each profile test pushes decoded audio to OboeLineSink at the profile's native
 * decode rate and frame time, then counts Oboe xruns. This catches:
 *   - Oboe stream refusing a sample rate
 *   - Ring buffer sizing issues for different frame times
 *   - SPSC contention under high frame rates (ULL: 100 frames/sec)
 *   - CPU contention between encode and decode in full duplex
 *
 * Test duration: ~40s total (each profile plays 2-6s of real audio).
 */
@RunWith(AndroidJUnit4::class)
class OboeProfilePlaybackInstrumentedTest {
    private var sink: OboeLineSink? = null
    private val codecs = mutableListOf<Codec>()

    @Before
    fun setup() {
        sinePhase = 0.0
    }

    @After
    fun cleanup() {
        sink?.stop()
        sink?.release()
        sink = null
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

    private fun generateSineFrame(
        sampleRate: Int,
        channels: Int,
        frameTimeMs: Int,
    ): FloatArray {
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
     * Run a steady-state playback test for a profile using OboeLineSink.
     */
    private fun runSteadyPlayback(
        profile: Profile,
        totalFrames: Int,
        warmupFrames: Int = 10,
    ): Int {
        val decCodec = profile.createDecodeCodec()
        codecs.add(decCodec)
        val decRate = decCodec.preferredSamplerate!!
        val decChannels =
            when (decCodec) {
                is Opus -> {
                    val opusProfile =
                        when (profile) {
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
        sink = OboeLineSink(autodigest = true)
        sink!!.configure(decRate, decChannels)

        var baseline = 0
        repeat(totalFrames) { i ->
            val frame = generateSineFrame(decRate, decChannels, profile.frameTimeMs)
            sink!!.handleFrame(frame)
            if (i == warmupFrames) baseline = NativePlaybackEngine.getXRunCount()
            if (i < totalFrames - 1) Thread.sleep(profile.frameTimeMs.toLong())
        }

        // Let final frames drain
        Thread.sleep(profile.frameTimeMs.toLong() * 3)

        val xruns = NativePlaybackEngine.getXRunCount() - baseline
        sink!!.stop()
        sink!!.release()
        sink = null
        return xruns
    }

    /**
     * Run a full-duplex test: encode (TX) and decode playback (RX) simultaneously.
     */
    private fun runFullDuplex(
        profile: Profile,
        totalFrames: Int,
        warmupFrames: Int = 10,
    ): Int {
        val encCodec = profile.createCodec()
        val decCodec = profile.createDecodeCodec()
        codecs.add(encCodec)
        codecs.add(decCodec)

        val encRate = encCodec.preferredSamplerate!!
        val decRate = decCodec.preferredSamplerate!!
        val encChannels =
            when (encCodec) {
                is Opus -> {
                    val opusProfile =
                        when (profile) {
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
        val decChannels =
            when (decCodec) {
                is Opus -> {
                    val opusProfile =
                        when (profile) {
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
        sink = OboeLineSink(autodigest = true)
        sink!!.configure(decRate, decChannels)

        // TX thread: continuously encodes frames (simulates microphone capture)
        var txPhase = 0.0
        val txPhaseInc = 440.0 * 2.0 * Math.PI / encRate
        val txRunning =
            java.util.concurrent.atomic
                .AtomicBoolean(true)
        val txThread =
            Thread {
                while (txRunning.get()) {
                    val txSamples = encRate * profile.frameTimeMs / 1000 * encChannels
                    val txFrame =
                        FloatArray(txSamples) { i ->
                            val si = i / encChannels
                            (kotlin.math.sin(txPhase + si * txPhaseInc) * 0.3).toFloat()
                        }
                    txPhase += (txSamples / encChannels) * txPhaseInc
                    try {
                        encCodec.encode(txFrame)
                    } catch (_: Exception) {
                    }
                    Thread.sleep(profile.frameTimeMs.toLong())
                }
            }
        txThread.start()

        // RX: push decoded frames to OboeLineSink
        var baseline = 0
        repeat(totalFrames) { i ->
            val rxFrame = generateSineFrame(decRate, decChannels, profile.frameTimeMs)
            sink!!.handleFrame(rxFrame)
            if (i == warmupFrames) baseline = NativePlaybackEngine.getXRunCount()
            if (i < totalFrames - 1) Thread.sleep(profile.frameTimeMs.toLong())
        }

        txRunning.set(false)
        txThread.join(1000)

        Thread.sleep(profile.frameTimeMs.toLong() * 3)

        val xruns = NativePlaybackEngine.getXRunCount() - baseline
        sink!!.stop()
        sink!!.release()
        sink = null
        return xruns
    }

    // =====================================================================
    //  BASELINE: Steady-state playback, zero xruns expected
    // =====================================================================

    // -- Opus Standard --

    @Test
    fun mq_steadyPlayback_zeroXruns() {
        val xruns = runSteadyPlayback(Profile.MQ, totalFrames = 50)
        assertEquals("MQ (48kHz/60ms): expected 0 xruns during steady Oboe playback", 0, xruns)
    }

    @Test
    fun hq_steadyPlayback_zeroXruns() {
        val xruns = runSteadyPlayback(Profile.HQ, totalFrames = 50)
        assertEquals("HQ (48kHz/60ms): expected 0 xruns during steady Oboe playback", 0, xruns)
    }

    @Test
    fun shq_steadyPlayback_zeroXruns() {
        val xruns = runSteadyPlayback(Profile.SHQ, totalFrames = 50)
        assertEquals("SHQ (48kHz stereo/60ms): expected 0 xruns during steady Oboe playback", 0, xruns)
    }

    // -- Opus Low-Latency --

    @Test
    fun ll_steadyPlayback_zeroXruns() {
        val xruns = runSteadyPlayback(Profile.LL, totalFrames = 100)
        assertEquals("LL (48kHz/20ms): expected 0 xruns during steady Oboe playback", 0, xruns)
    }

    @Test
    fun ull_steadyPlayback_zeroXruns() {
        val xruns = runSteadyPlayback(Profile.ULL, totalFrames = 200)
        assertEquals("ULL (48kHz/10ms): expected 0 xruns during steady Oboe playback", 0, xruns)
    }

    // -- Codec2 --

    @Test
    fun ulbw_steadyPlayback_zeroXruns() {
        val xruns = runSteadyPlayback(Profile.ULBW, totalFrames = 15, warmupFrames = 5)
        assertEquals("ULBW (8kHz/400ms): expected 0 xruns during steady Oboe playback", 0, xruns)
    }

    @Test
    fun vlbw_steadyPlayback_zeroXruns() {
        val xruns = runSteadyPlayback(Profile.VLBW, totalFrames = 15, warmupFrames = 5)
        assertEquals("VLBW (8kHz/320ms): expected 0 xruns during steady Oboe playback", 0, xruns)
    }

    @Test
    fun lbw_steadyPlayback_zeroXruns() {
        val xruns = runSteadyPlayback(Profile.LBW, totalFrames = 20, warmupFrames = 5)
        assertEquals("LBW (8kHz/200ms): expected 0 xruns during steady Oboe playback", 0, xruns)
    }

    // =====================================================================
    //  SUSTAINED GAP + RE-BUFFER
    // =====================================================================

    @Test
    fun mq_sustainedGap_rebufferThenCleanPlayback() {
        sinePhase = 0.0
        sink = OboeLineSink(autodigest = true)
        sink!!.configure(48000, 1)

        // Phase 1: steady delivery (1.5s)
        repeat(25) {
            sink!!.handleFrame(generateSineFrame(48000, 1, 60))
            Thread.sleep(60)
        }

        // Sustained gap
        Thread.sleep(400)

        // Burst fill for re-buffer
        repeat(OboeLineSink.AUTOSTART_MIN) {
            sink!!.handleFrame(generateSineFrame(48000, 1, 60))
        }
        Thread.sleep(200)

        // Phase 2: measure steady-state xruns
        val baseline = NativePlaybackEngine.getXRunCount()
        repeat(25) { i ->
            sink!!.handleFrame(generateSineFrame(48000, 1, 60))
            if (i < 24) Thread.sleep(60)
        }
        Thread.sleep(200)

        val xruns = NativePlaybackEngine.getXRunCount() - baseline
        assertEquals("MQ: expected 0 xruns in post-rebuffer steady state (Oboe)", 0, xruns)
    }

    @Test
    fun ll_sustainedGap_rebufferThenCleanPlayback() {
        sinePhase = 0.0
        sink = OboeLineSink(autodigest = true)
        sink!!.configure(48000, 1)

        // Phase 1: steady delivery (50 frames = 1s)
        repeat(50) {
            sink!!.handleFrame(generateSineFrame(48000, 1, 20))
            Thread.sleep(20)
        }

        // Sustained gap
        Thread.sleep(200)

        // Burst fill for re-buffer
        repeat(OboeLineSink.AUTOSTART_MIN) {
            sink!!.handleFrame(generateSineFrame(48000, 1, 20))
        }
        Thread.sleep(100)

        // Phase 2: measure
        val baseline = NativePlaybackEngine.getXRunCount()
        repeat(50) { i ->
            sink!!.handleFrame(generateSineFrame(48000, 1, 20))
            if (i < 49) Thread.sleep(20)
        }
        Thread.sleep(100)

        val xruns = NativePlaybackEngine.getXRunCount() - baseline
        assertEquals("LL: expected 0 xruns in post-rebuffer steady state (Oboe)", 0, xruns)
    }

    @Test
    fun ulbw_sustainedGap_rebufferThenCleanPlayback() {
        sinePhase = 0.0
        sink = OboeLineSink(autodigest = true)
        sink!!.configure(8000, 1)

        // Phase 1: steady delivery (8 frames = 3.2s)
        repeat(8) {
            sink!!.handleFrame(generateSineFrame(8000, 1, 400))
            Thread.sleep(400)
        }

        // Sustained gap (1.5s)
        Thread.sleep(1500)

        // Burst fill for re-buffer
        repeat(OboeLineSink.AUTOSTART_MIN) {
            sink!!.handleFrame(generateSineFrame(8000, 1, 400))
        }
        Thread.sleep(1000)

        // Phase 2: measure
        val baseline = NativePlaybackEngine.getXRunCount()
        repeat(8) { i ->
            sink!!.handleFrame(generateSineFrame(8000, 1, 400))
            if (i < 7) Thread.sleep(400)
        }
        Thread.sleep(500)

        val xruns = NativePlaybackEngine.getXRunCount() - baseline
        assertEquals("ULBW: expected 0 xruns in post-rebuffer steady state (Oboe)", 0, xruns)
    }

    // =====================================================================
    //  FULL DUPLEX: Simultaneous encode (TX) + decode playback (RX)
    // =====================================================================

    @Test
    fun mq_fullDuplex_rxXrunsZero() {
        val xruns = runFullDuplex(Profile.MQ, totalFrames = 50)
        assertEquals("MQ full duplex: expected 0 RX xruns (Oboe)", 0, xruns)
    }

    @Test
    fun hq_fullDuplex_rxXrunsZero() {
        val xruns = runFullDuplex(Profile.HQ, totalFrames = 50)
        assertEquals("HQ full duplex: expected 0 RX xruns (Oboe)", 0, xruns)
    }

    @Test
    fun ll_fullDuplex_rxXrunsZero() {
        val xruns = runFullDuplex(Profile.LL, totalFrames = 100)
        assertEquals("LL full duplex: expected 0 RX xruns (Oboe)", 0, xruns)
    }

    @Test
    fun ull_fullDuplex_rxXrunsZero() {
        val xruns = runFullDuplex(Profile.ULL, totalFrames = 200)
        assertEquals("ULL full duplex: expected 0 RX xruns (Oboe)", 0, xruns)
    }

    @Test
    fun shq_fullDuplex_rxXrunsZero() {
        val xruns = runFullDuplex(Profile.SHQ, totalFrames = 50)
        assertEquals("SHQ full duplex (stereo): expected 0 RX xruns (Oboe)", 0, xruns)
    }

    // =====================================================================
    //  PROFILE SWITCH MID-PLAYBACK
    // =====================================================================

    @Test
    fun profileSwitch_mqToLl_noXrunsDuringTransition() {
        sinePhase = 0.0

        // Phase 1: MQ playback (1.5s at 48kHz/60ms)
        sink = OboeLineSink(autodigest = true)
        sink!!.configure(48000, 1)

        repeat(25) {
            sink!!.handleFrame(generateSineFrame(48000, 1, 60))
            Thread.sleep(60)
        }

        val baseline = NativePlaybackEngine.getXRunCount()

        // Phase 2: Switch to LL frame size (20ms)
        repeat(75) { i ->
            sink!!.handleFrame(generateSineFrame(48000, 1, 20))
            if (i < 74) Thread.sleep(20)
        }
        Thread.sleep(100)

        val xruns = NativePlaybackEngine.getXRunCount() - baseline
        assertEquals("MQ→LL transition: expected 0 xruns (Oboe)", 0, xruns)
    }

    @Test
    fun profileSwitch_hqToUlbw_rateChangeCleanly() {
        sinePhase = 0.0

        // Phase 1: HQ playback (1.5s)
        sink = OboeLineSink(autodigest = true)
        sink!!.configure(48000, 1)

        repeat(25) {
            sink!!.handleFrame(generateSineFrame(48000, 1, 60))
            Thread.sleep(60)
        }

        // Tear down and reconfigure (same as Telephone.switchProfile does)
        sink!!.stop()
        sink!!.release()

        sink = OboeLineSink(autodigest = true)
        sink!!.configure(8000, 1)

        // Phase 2: ULBW playback (3.2s at 8kHz/400ms)
        var baseline = 0
        repeat(8) { i ->
            sink!!.handleFrame(generateSineFrame(8000, 1, 400))
            if (i == 3) baseline = NativePlaybackEngine.getXRunCount()
            if (i < 7) Thread.sleep(400)
        }
        Thread.sleep(500)

        val xruns = NativePlaybackEngine.getXRunCount() - baseline
        assertEquals("HQ→ULBW transition: expected 0 xruns after reconfigure (Oboe)", 0, xruns)
    }
}
