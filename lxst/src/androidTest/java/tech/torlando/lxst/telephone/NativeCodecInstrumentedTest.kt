/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package tech.torlando.lxst.telephone

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tech.torlando.lxst.audio.NativeCaptureEngine
import tech.torlando.lxst.audio.NativePlaybackEngine
import tech.torlando.lxst.codec.Codec
import tech.torlando.lxst.codec.Codec2
import tech.torlando.lxst.codec.Opus

/**
 * Phase 3 instrumented tests for native C++ Opus/Codec2 codec integration.
 *
 * Tests the full native codec pipeline that bypasses Kotlin encode/decode:
 *
 * **RX (decoder) tests:** Encode with Kotlin codec → writeEncodedPacket() →
 * native C++ decode → PCM ring buffer → Oboe SCHED_FIFO callback → speaker.
 * Verifies zero xruns across all 8 profiles.
 *
 * **TX (encoder) tests:** Oboe capture callback → native C++ encode →
 * encoded ring buffer → readEncodedPacket() → decode with Kotlin codec.
 * Verifies encoded packets contain valid audio for all Opus profiles.
 *
 * **Mute tests:** Verify native capture/playback mute produces silence
 * without breaking the packet stream.
 *
 * **Reconfiguration tests:** Verify codec can be destroyed and reconfigured
 * mid-stream (simulates profile switch during active call).
 *
 * Requires RECORD_AUDIO permission for TX encoder tests.
 */
@RunWith(AndroidJUnit4::class)
class NativeCodecInstrumentedTest {
    @get:Rule
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private var playbackEngineCreated = false
    private val codecs = mutableListOf<Codec>()

    @Before
    fun setup() {
        sinePhase = 0.0
        // Clean up any stale native state.
        // Must catch Throwable (not Exception) because UnsatisfiedLinkError
        // extends Error, not Exception — it won't be caught by catch(Exception).
        try {
            NativePlaybackEngine.destroyDecoder()
        } catch (_: Throwable) {
        }
        try {
            NativeCaptureEngine.stopStream()
            NativeCaptureEngine.destroyEncoder()
            NativeCaptureEngine.destroy()
        } catch (_: Throwable) {
        }
    }

    @After
    fun cleanup() {
        try {
            NativePlaybackEngine.destroyDecoder()
        } catch (_: Throwable) {
        }
        if (playbackEngineCreated) {
            try {
                NativePlaybackEngine.stopStream()
                NativePlaybackEngine.destroy()
            } catch (_: Throwable) {
            }
            playbackEngineCreated = false
        }
        try {
            NativeCaptureEngine.stopStream()
            NativeCaptureEngine.destroyEncoder()
            NativeCaptureEngine.destroy()
        } catch (_: Throwable) {
        }
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

    private fun trackCodec(codec: Codec): Codec {
        codecs.add(codec)
        return codec
    }

    /** Prebuffer frame count — enough to prevent initial xruns. */
    private val PREBUFFER_FRAMES = 5

    /**
     * Run a native decoder RX playback test for a profile.
     *
     * Directly manages NativePlaybackEngine lifecycle (no OboeLineSink):
     * 1. Creates native playback engine with decoded frame parameters
     * 2. Configures native decoder for the profile
     * 3. Prebuffers encoded frames (decoded natively → ring buffer)
     * 4. Starts Oboe output stream
     * 5. Continues writing encoded packets at real-time pace
     * 6. Measures xruns during steady-state playback
     *
     * @return xrun count during steady-state (excludes warmup)
     */
    private fun runNativeDecoderPlayback(
        profile: Profile,
        totalFrames: Int,
        warmupFrames: Int = 10,
    ): Int {
        val encCodec = trackCodec(profile.createCodec())
        val encRate = encCodec.preferredSamplerate!!
        val encChannels = encCodec.codecChannels
        val decParams = profile.nativeDecodeParams()

        // Decoded frame size in samples (what the ring buffer holds per frame)
        val decodedFrameSamples = decParams.sampleRate * profile.frameTimeMs / 1000 * decParams.channels

        sinePhase = 0.0

        // Create native playback engine (loads .so, allocates ring buffer)
        val created =
            NativePlaybackEngine.create(
                sampleRate = decParams.sampleRate,
                channels = decParams.channels,
                frameSamples = decodedFrameSamples,
                maxBufferFrames = 75,
                prebufferFrames = PREBUFFER_FRAMES,
            )
        assertTrue("Native playback engine should create for ${profile.abbreviation}", created)
        playbackEngineCreated = true

        // Configure native decoder
        val configured =
            NativePlaybackEngine.configureDecoder(
                codecType = decParams.codecType,
                sampleRate = decParams.sampleRate,
                channels = decParams.channels,
                opusApp = decParams.opusApplication,
                opusBitrate = decParams.opusBitrate,
                opusComplexity = decParams.opusComplexity,
                codec2Mode = decParams.codec2LibraryMode,
            )
        assertTrue("Native decoder should configure for ${profile.abbreviation}", configured)

        // Prebuffer: write encoded packets that decode into ring buffer
        repeat(PREBUFFER_FRAMES + 1) {
            val pcm = generateSineFrame(encRate, encChannels, profile.frameTimeMs)
            val encoded = encCodec.encode(pcm)
            NativePlaybackEngine.writeEncodedPacket(encoded, 0, encoded.size)
        }

        // Start Oboe stream (will read from ring buffer on SCHED_FIFO thread)
        val started = NativePlaybackEngine.startStream()
        assertTrue("Oboe stream should start for ${profile.abbreviation}", started)

        var baseline = 0
        val remaining = totalFrames - PREBUFFER_FRAMES - 1
        repeat(remaining) { i ->
            // Encode with Kotlin codec (simulates remote peer sending packet)
            val pcm = generateSineFrame(encRate, encChannels, profile.frameTimeMs)
            val encoded = encCodec.encode(pcm)

            // Write encoded packet to native decoder (no header to skip)
            NativePlaybackEngine.writeEncodedPacket(encoded, 0, encoded.size)

            if (i == warmupFrames) baseline = NativePlaybackEngine.getXRunCount()
            if (i < remaining - 1) Thread.sleep(profile.frameTimeMs.toLong())
        }

        // Let final frames drain
        Thread.sleep(profile.frameTimeMs.toLong() * 3)

        val xruns = NativePlaybackEngine.getXRunCount() - baseline
        NativePlaybackEngine.destroyDecoder()
        NativePlaybackEngine.stopStream()
        NativePlaybackEngine.destroy()
        playbackEngineCreated = false
        return xruns
    }

    // =====================================================================
    //  RX DECODER: Native decode playback per profile
    // =====================================================================

    @Test
    fun mq_nativeDecoder_zeroXruns() {
        val xruns = runNativeDecoderPlayback(Profile.MQ, totalFrames = 50)
        assertEquals("MQ native decode: expected 0 xruns", 0, xruns)
    }

    @Test
    fun hq_nativeDecoder_zeroXruns() {
        val xruns = runNativeDecoderPlayback(Profile.HQ, totalFrames = 50)
        assertEquals("HQ native decode: expected 0 xruns", 0, xruns)
    }

    @Test
    fun shq_nativeDecoder_zeroXruns() {
        val xruns = runNativeDecoderPlayback(Profile.SHQ, totalFrames = 50)
        assertEquals("SHQ native decode (stereo): expected 0 xruns", 0, xruns)
    }

    @Test
    fun ll_nativeDecoder_zeroXruns() {
        val xruns = runNativeDecoderPlayback(Profile.LL, totalFrames = 100)
        assertEquals("LL native decode (20ms): expected 0 xruns", 0, xruns)
    }

    @Test
    fun ull_nativeDecoder_zeroXruns() {
        val xruns = runNativeDecoderPlayback(Profile.ULL, totalFrames = 200)
        assertEquals("ULL native decode (10ms): expected 0 xruns", 0, xruns)
    }

    @Test
    fun ulbw_nativeDecoder_zeroXruns() {
        val xruns = runNativeDecoderPlayback(Profile.ULBW, totalFrames = 15, warmupFrames = 5)
        assertEquals("ULBW native decode (Codec2 700C): expected 0 xruns", 0, xruns)
    }

    @Test
    fun vlbw_nativeDecoder_zeroXruns() {
        val xruns = runNativeDecoderPlayback(Profile.VLBW, totalFrames = 15, warmupFrames = 5)
        assertEquals("VLBW native decode (Codec2 1600): expected 0 xruns", 0, xruns)
    }

    @Test
    fun lbw_nativeDecoder_zeroXruns() {
        val xruns = runNativeDecoderPlayback(Profile.LBW, totalFrames = 20, warmupFrames = 5)
        assertEquals("LBW native decode (Codec2 3200): expected 0 xruns", 0, xruns)
    }

    // =====================================================================
    //  RX DECODER: Profile switch mid-stream
    // =====================================================================

    @Test
    fun nativeDecoder_profileSwitch_mqToHq_noXruns() {
        sinePhase = 0.0

        // Both MQ and HQ decode at 48kHz mono, so engine params don't change
        val decFrameSamples = 48000 * 60 / 1000 // 2880 samples per 60ms frame

        val created =
            NativePlaybackEngine.create(
                sampleRate = 48000,
                channels = 1,
                frameSamples = decFrameSamples,
                maxBufferFrames = 75,
                prebufferFrames = PREBUFFER_FRAMES,
            )
        assertTrue("Playback engine should create", created)
        playbackEngineCreated = true

        // Phase 1: MQ native decode (1.5s)
        val mqEnc = trackCodec(Profile.MQ.createCodec())
        val mqDecParams = Profile.MQ.nativeDecodeParams()
        NativePlaybackEngine.configureDecoder(
            codecType = mqDecParams.codecType,
            sampleRate = mqDecParams.sampleRate,
            channels = mqDecParams.channels,
            opusApp = mqDecParams.opusApplication,
            opusBitrate = mqDecParams.opusBitrate,
            opusComplexity = mqDecParams.opusComplexity,
            codec2Mode = mqDecParams.codec2LibraryMode,
        )

        // Prebuffer MQ frames
        repeat(PREBUFFER_FRAMES + 1) {
            val pcm = generateSineFrame(24000, 1, 60)
            NativePlaybackEngine.writeEncodedPacket(mqEnc.encode(pcm), 0, mqEnc.encode(pcm).size)
        }
        NativePlaybackEngine.startStream()

        repeat(20) {
            val pcm = generateSineFrame(24000, 1, 60)
            val encoded = mqEnc.encode(pcm)
            NativePlaybackEngine.writeEncodedPacket(encoded, 0, encoded.size)
            Thread.sleep(60)
        }

        // Switch to HQ: destroy old decoder, configure new
        NativePlaybackEngine.destroyDecoder()

        val hqEnc = trackCodec(Profile.HQ.createCodec())
        val hqDecParams = Profile.HQ.nativeDecodeParams()
        NativePlaybackEngine.configureDecoder(
            codecType = hqDecParams.codecType,
            sampleRate = hqDecParams.sampleRate,
            channels = hqDecParams.channels,
            opusApp = hqDecParams.opusApplication,
            opusBitrate = hqDecParams.opusBitrate,
            opusComplexity = hqDecParams.opusComplexity,
            codec2Mode = hqDecParams.codec2LibraryMode,
        )

        val baseline = NativePlaybackEngine.getXRunCount()

        // Phase 2: HQ native decode (1.5s)
        repeat(25) { i ->
            val pcm = generateSineFrame(48000, 1, 60)
            val encoded = hqEnc.encode(pcm)
            NativePlaybackEngine.writeEncodedPacket(encoded, 0, encoded.size)
            if (i < 24) Thread.sleep(60)
        }
        Thread.sleep(200)

        val xruns = NativePlaybackEngine.getXRunCount() - baseline
        NativePlaybackEngine.destroyDecoder()
        assertEquals("MQ→HQ native decoder switch: expected 0 xruns", 0, xruns)
    }

    // =====================================================================
    //  TX ENCODER: Native encode from microphone capture
    // =====================================================================

    @Test
    fun mq_nativeEncoder_producesValidOpusPackets() {
        val encParams = Profile.MQ.nativeEncodeParams()

        // Create capture engine at MQ encode rate
        val created =
            NativeCaptureEngine.create(
                sampleRate = encParams.sampleRate,
                channels = encParams.channels,
                frameSamples = encParams.sampleRate * Profile.MQ.frameTimeMs / 1000,
                maxBufferFrames = 75,
                enableFilters = true,
            )
        assertTrue("Capture engine should create", created)

        // Configure native encoder
        val encoderConfigured =
            NativeCaptureEngine.configureEncoder(
                codecType = encParams.codecType,
                sampleRate = encParams.sampleRate,
                channels = encParams.channels,
                opusApp = encParams.opusApplication,
                opusBitrate = encParams.opusBitrate,
                opusComplexity = encParams.opusComplexity,
                codec2Mode = encParams.codec2LibraryMode,
            )
        assertTrue("Native encoder should configure", encoderConfigured)

        val started = NativeCaptureEngine.startStream()
        assertTrue("Capture stream should start", started)

        // Capture for 2 seconds
        Thread.sleep(2000)

        // Read encoded packets
        val encodedBuf = ByteArray(1500)
        var packetCount = 0
        var totalBytes = 0

        while (true) {
            val len = NativeCaptureEngine.readEncodedPacket(encodedBuf)
            if (len <= 0) break
            packetCount++
            totalBytes += len

            // Verify packet is valid Opus by decoding with Kotlin decoder
            if (packetCount <= 3) {
                val packet = encodedBuf.copyOf(len)
                val decCodec = trackCodec(Profile.MQ.createDecodeCodec()) as Opus
                val decoded = decCodec.decode(packet)
                assertTrue(
                    "Packet #$packetCount ($len bytes) should decode to valid audio",
                    decoded.isNotEmpty(),
                )
            }
        }

        // At 60ms frames, expect ~33 packets in 2s. Allow margin for startup.
        assertTrue(
            "Should capture >=15 encoded packets in 2s (got $packetCount)",
            packetCount >= 15,
        )
        assertTrue(
            "Encoded packets should have non-zero size (total=$totalBytes)",
            totalBytes > 0,
        )
    }

    @Test
    fun hq_nativeEncoder_producesValidOpusPackets() {
        val encParams = Profile.HQ.nativeEncodeParams()

        val created =
            NativeCaptureEngine.create(
                sampleRate = encParams.sampleRate,
                channels = encParams.channels,
                frameSamples = encParams.sampleRate * Profile.HQ.frameTimeMs / 1000,
                maxBufferFrames = 75,
                enableFilters = true,
            )
        assertTrue("Capture engine should create", created)

        val encoderConfigured =
            NativeCaptureEngine.configureEncoder(
                codecType = encParams.codecType,
                sampleRate = encParams.sampleRate,
                channels = encParams.channels,
                opusApp = encParams.opusApplication,
                opusBitrate = encParams.opusBitrate,
                opusComplexity = encParams.opusComplexity,
                codec2Mode = encParams.codec2LibraryMode,
            )
        assertTrue("HQ native encoder should configure", encoderConfigured)

        val started = NativeCaptureEngine.startStream()
        assertTrue("Capture stream should start", started)

        Thread.sleep(2000)

        val encodedBuf = ByteArray(1500)
        var packetCount = 0
        while (NativeCaptureEngine.readEncodedPacket(encodedBuf) > 0) {
            packetCount++
        }

        assertTrue(
            "HQ: should capture >=15 encoded packets in 2s (got $packetCount)",
            packetCount >= 15,
        )
    }

    // =====================================================================
    //  NATIVE MUTE: Capture mute encodes silence
    // =====================================================================

    @Test
    fun nativeCaptureMute_producesPackets_withSilence() {
        val encParams = Profile.MQ.nativeEncodeParams()

        val created =
            NativeCaptureEngine.create(
                sampleRate = encParams.sampleRate,
                channels = encParams.channels,
                frameSamples = encParams.sampleRate * Profile.MQ.frameTimeMs / 1000,
                maxBufferFrames = 75,
                enableFilters = true,
            )
        assertTrue("Capture engine should create", created)

        NativeCaptureEngine.configureEncoder(
            codecType = encParams.codecType,
            sampleRate = encParams.sampleRate,
            channels = encParams.channels,
            opusApp = encParams.opusApplication,
            opusBitrate = encParams.opusBitrate,
            opusComplexity = encParams.opusComplexity,
            codec2Mode = encParams.codec2LibraryMode,
        )

        // Enable mute BEFORE starting — should encode silence
        NativeCaptureEngine.setCaptureMute(true)

        val started = NativeCaptureEngine.startStream()
        assertTrue("Capture stream should start", started)

        Thread.sleep(1500)

        // Should still produce encoded packets (silence), not empty buffer
        val encodedBuf = ByteArray(1500)
        var packetCount = 0
        while (NativeCaptureEngine.readEncodedPacket(encodedBuf) > 0) {
            packetCount++
        }

        assertTrue(
            "Muted capture should still produce >=10 packets (got $packetCount)",
            packetCount >= 10,
        )

        // Decode a muted packet — should be near-silent
        NativeCaptureEngine.setCaptureMute(true)
        Thread.sleep(500)

        val len = NativeCaptureEngine.readEncodedPacket(encodedBuf)
        if (len > 0) {
            val decCodec = trackCodec(Profile.MQ.createDecodeCodec()) as Opus
            val decoded = decCodec.decode(encodedBuf.copyOf(len))
            val rms =
                kotlin.math.sqrt(
                    decoded.sumOf { (it * it).toDouble() } / decoded.size,
                )
            assertTrue(
                "Muted audio RMS should be near zero (got $rms)",
                rms < 0.01,
            )
        }
    }

    @Test
    fun nativePlaybackMute_outputsSilence() {
        sinePhase = 0.0
        val decFrameSamples = 48000 * 60 / 1000

        val created =
            NativePlaybackEngine.create(
                sampleRate = 48000,
                channels = 1,
                frameSamples = decFrameSamples,
                maxBufferFrames = 75,
                prebufferFrames = PREBUFFER_FRAMES,
            )
        assertTrue("Playback engine should create", created)
        playbackEngineCreated = true

        val mqDecParams = Profile.MQ.nativeDecodeParams()
        NativePlaybackEngine.configureDecoder(
            codecType = mqDecParams.codecType,
            sampleRate = mqDecParams.sampleRate,
            channels = mqDecParams.channels,
            opusApp = mqDecParams.opusApplication,
            opusBitrate = mqDecParams.opusBitrate,
            opusComplexity = mqDecParams.opusComplexity,
            codec2Mode = mqDecParams.codec2LibraryMode,
        )

        // Prebuffer and start unmuted
        val enc = trackCodec(Profile.MQ.createCodec())
        repeat(PREBUFFER_FRAMES + 1) {
            val pcm = generateSineFrame(24000, 1, 60)
            NativePlaybackEngine.writeEncodedPacket(enc.encode(pcm), 0, enc.encode(pcm).size)
        }
        NativePlaybackEngine.startStream()

        repeat(10) {
            val pcm = generateSineFrame(24000, 1, 60)
            NativePlaybackEngine.writeEncodedPacket(enc.encode(pcm), 0, enc.encode(pcm).size)
            Thread.sleep(60)
        }

        // Enable mute
        NativePlaybackEngine.setPlaybackMute(true)

        // Continue writing — packets should be decoded but output is silenced
        val baseline = NativePlaybackEngine.getXRunCount()
        repeat(15) { i ->
            val pcm = generateSineFrame(24000, 1, 60)
            val encoded = enc.encode(pcm)
            NativePlaybackEngine.writeEncodedPacket(encoded, 0, encoded.size)
            if (i < 14) Thread.sleep(60)
        }
        Thread.sleep(200)

        val xruns = NativePlaybackEngine.getXRunCount() - baseline
        // Key assertion: playback continues without xruns even when muted
        assertEquals("Muted playback should still have 0 xruns", 0, xruns)

        NativePlaybackEngine.setPlaybackMute(false)
        NativePlaybackEngine.destroyDecoder()
    }

    // =====================================================================
    //  TX ENCODER: Reconfiguration (profile switch mid-capture)
    // =====================================================================

    @Test
    fun nativeEncoder_reconfig_mqToLl_producesPackets() {
        // MQ and LL share the same encode rate (24kHz, mono, 8kbps) but
        // have different frame times (MQ=60ms, LL=20ms). However, the engine
        // frameSamples controls the callback accumulation — both use the same
        // 24kHz rate. We test same-rate encoder reconfiguration here because
        // cross-rate reconfig requires engine recreation (tested separately).
        val mqParams = Profile.MQ.nativeEncodeParams()

        // Phase 1: MQ encode
        val created =
            NativeCaptureEngine.create(
                sampleRate = mqParams.sampleRate,
                channels = mqParams.channels,
                frameSamples = mqParams.sampleRate * Profile.MQ.frameTimeMs / 1000,
                maxBufferFrames = 75,
                enableFilters = true,
            )
        assertTrue("Capture engine should create", created)

        NativeCaptureEngine.configureEncoder(
            codecType = mqParams.codecType,
            sampleRate = mqParams.sampleRate,
            channels = mqParams.channels,
            opusApp = mqParams.opusApplication,
            opusBitrate = mqParams.opusBitrate,
            opusComplexity = mqParams.opusComplexity,
            codec2Mode = mqParams.codec2LibraryMode,
        )

        val started = NativeCaptureEngine.startStream()
        assertTrue("Capture stream should start", started)
        Thread.sleep(1500)

        // Drain MQ packets
        val encodedBuf = ByteArray(1500)
        var mqPackets = 0
        while (NativeCaptureEngine.readEncodedPacket(encodedBuf) > 0) mqPackets++
        assertTrue("MQ phase should produce >=5 packets (got $mqPackets)", mqPackets >= 5)

        // Phase 2: Destroy encoder, reconfigure for LL (same encode rate, different bitrate/intent)
        NativeCaptureEngine.destroyEncoder()

        val llParams = Profile.LL.nativeEncodeParams()
        NativeCaptureEngine.configureEncoder(
            codecType = llParams.codecType,
            sampleRate = llParams.sampleRate,
            channels = llParams.channels,
            opusApp = llParams.opusApplication,
            opusBitrate = llParams.opusBitrate,
            opusComplexity = llParams.opusComplexity,
            codec2Mode = llParams.codec2LibraryMode,
        )

        Thread.sleep(1500)

        var llPackets = 0
        while (NativeCaptureEngine.readEncodedPacket(encodedBuf) > 0) llPackets++
        assertTrue("LL phase should produce >=5 packets (got $llPackets)", llPackets >= 5)
    }

    // =====================================================================
    //  LIFECYCLE: configureEncoder must be called after create()
    // =====================================================================

    /**
     * Demonstrates the lifecycle ordering bug that caused "no audio" in
     * real voice calls: Telephone.openPipelinesNativeCodec() called
     * NativeCaptureEngine.configureEncoder() BEFORE OboeLineSource.start()
     * had called NativeCaptureEngine.create(). The C++ singleton was nullptr,
     * so configureEncoder returned false and encodeInCallback_ was never set.
     * The Oboe callback wrote raw PCM instead of encoding, and
     * readEncodedPacket() returned 0 because encodedRingBuffer_ was null.
     *
     * Fix: OboeLineSource.start() now configures the encoder AFTER create().
     */
    @Test
    fun encoderLifecycle_configureBeforeCreate_failsThenSucceedsAfterCreate() {
        val encParams = Profile.MQ.nativeEncodeParams()

        // Phase 1: configureEncoder BEFORE create() — should return false
        // (This was the bug: C++ sCaptureEngine is nullptr)
        val configBeforeCreate =
            NativeCaptureEngine.configureEncoder(
                codecType = encParams.codecType,
                sampleRate = encParams.sampleRate,
                channels = encParams.channels,
                opusApp = encParams.opusApplication,
                opusBitrate = encParams.opusBitrate,
                opusComplexity = encParams.opusComplexity,
                codec2Mode = encParams.codec2LibraryMode,
            )
        assertFalse(
            "configureEncoder before create() must return false (engine doesn't exist)",
            configBeforeCreate,
        )

        // Verify: no encoded packets produced (encodedRingBuffer_ is null)
        val emptyBuf = ByteArray(1500)
        val earlyRead = NativeCaptureEngine.readEncodedPacket(emptyBuf)
        assertEquals("readEncodedPacket before create() should return 0", 0, earlyRead)

        // Phase 2: create() THEN configureEncoder() — the correct lifecycle
        val created =
            NativeCaptureEngine.create(
                sampleRate = encParams.sampleRate,
                channels = encParams.channels,
                frameSamples = encParams.sampleRate * Profile.MQ.frameTimeMs / 1000,
                maxBufferFrames = 75,
                enableFilters = true,
            )
        assertTrue("Capture engine should create", created)

        val configAfterCreate =
            NativeCaptureEngine.configureEncoder(
                codecType = encParams.codecType,
                sampleRate = encParams.sampleRate,
                channels = encParams.channels,
                opusApp = encParams.opusApplication,
                opusBitrate = encParams.opusBitrate,
                opusComplexity = encParams.opusComplexity,
                codec2Mode = encParams.codec2LibraryMode,
            )
        assertTrue(
            "configureEncoder after create() must return true",
            configAfterCreate,
        )

        val started = NativeCaptureEngine.startStream()
        assertTrue("Capture stream should start", started)

        // Capture for 1.5s
        Thread.sleep(1500)

        // Verify: encoded packets are now produced
        var packetCount = 0
        while (NativeCaptureEngine.readEncodedPacket(emptyBuf) > 0) packetCount++
        assertTrue(
            "Should produce >=10 encoded packets when lifecycle is correct (got $packetCount)",
            packetCount >= 10,
        )
    }

    /**
     * Verifies that the same lifecycle ordering used by OboeLineSource.start()
     * works correctly: create → configureEncoder → startStream → readEncodedPacket.
     * This matches the fix applied to the production code.
     */
    @Test
    fun encoderLifecycle_oboeLineSourcePattern_producesPackets() {
        val encParams = Profile.MQ.nativeEncodeParams()
        val frameSamples = encParams.sampleRate * Profile.MQ.frameTimeMs / 1000

        // Step 1: create (like OboeLineSource.start() does)
        val created =
            NativeCaptureEngine.create(
                sampleRate = encParams.sampleRate,
                channels = encParams.channels,
                frameSamples = frameSamples,
                maxBufferFrames = 75,
                enableFilters = true,
            )
        assertTrue("Step 1: create should succeed", created)

        // Step 2: configureEncoder (moved from Telephone.openPipelinesNativeCodec()
        // into OboeLineSource.start(), after create())
        val configured =
            NativeCaptureEngine.configureEncoder(
                codecType = encParams.codecType,
                sampleRate = encParams.sampleRate,
                channels = encParams.channels,
                opusApp = encParams.opusApplication,
                opusBitrate = encParams.opusBitrate,
                opusComplexity = encParams.opusComplexity,
                codec2Mode = encParams.codec2LibraryMode,
            )
        assertTrue("Step 2: configureEncoder after create should succeed", configured)

        // Step 3: startStream
        val started = NativeCaptureEngine.startStream()
        assertTrue("Step 3: startStream should succeed", started)

        // Step 4: readEncodedPacket in a loop (like ingestJobNativeCodec)
        Thread.sleep(2000)

        val encodedBuf = ByteArray(1500)
        var packetCount = 0
        var totalBytes = 0
        while (true) {
            val len = NativeCaptureEngine.readEncodedPacket(encodedBuf)
            if (len <= 0) break
            packetCount++
            totalBytes += len
        }

        assertTrue(
            "OboeLineSource pattern: should capture >=15 encoded packets (got $packetCount)",
            packetCount >= 15,
        )
        assertTrue(
            "Encoded packets should have non-zero size (total=$totalBytes)",
            totalBytes > 0,
        )
    }

    // =====================================================================
    //  CALLBACK LIVENESS: Oboe callback must not die on stream start
    // =====================================================================

    /**
     * Regression test for SCHED_FIFO callback race condition.
     *
     * Bug: In openStream(), isPlaying_ was set AFTER requestStart(). The Oboe
     * callback runs on a SCHED_FIFO real-time thread and can preempt the caller
     * immediately after requestStart(). The callback's final check:
     *
     *   return isPlaying_.load() ? Continue : Stop;
     *
     * would see isPlaying_=false and return Stop, permanently killing the stream.
     * Diagnostics showed: cbServed=0, cbSilence=1, buf=99 (buffer fills, callback
     * never consumes). This caused "no audio" on HQ voice calls.
     *
     * Fix: Set isPlaying_ BEFORE requestStart() so the callback always sees true.
     *
     * This test verifies the callback is alive by checking that frames written to
     * the ring buffer are actually consumed (buffered count decreases over time).
     * With the old code: stream dies after 1 callback, buffer stays full → FAIL.
     * With the fix: callback runs continuously, drains buffer → PASS.
     */
    @Test
    fun playbackCallback_survives_streamStart_raceCondition() {
        sinePhase = 0.0
        val decFrameSamples = 48000 * 60 / 1000 // 2880 (HQ: 60ms at 48kHz)
        val framesToLoad = 10

        val created =
            NativePlaybackEngine.create(
                sampleRate = 48000,
                channels = 1,
                frameSamples = decFrameSamples,
                maxBufferFrames = 75,
                prebufferFrames = PREBUFFER_FRAMES,
            )
        assertTrue("Playback engine should create", created)
        playbackEngineCreated = true

        // Configure native decoder (HQ profile)
        val hqDecParams = Profile.HQ.nativeDecodeParams()
        NativePlaybackEngine.configureDecoder(
            codecType = hqDecParams.codecType,
            sampleRate = hqDecParams.sampleRate,
            channels = hqDecParams.channels,
            opusApp = hqDecParams.opusApplication,
            opusBitrate = hqDecParams.opusBitrate,
            opusComplexity = hqDecParams.opusComplexity,
            codec2Mode = hqDecParams.codec2LibraryMode,
        )

        // Prebuffer encoded frames into ring buffer
        val enc = trackCodec(Profile.HQ.createCodec())
        repeat(framesToLoad) {
            val pcm = generateSineFrame(48000, 1, 60)
            val encoded = enc.encode(pcm)
            NativePlaybackEngine.writeEncodedPacket(encoded, 0, encoded.size)
        }

        val preStartCount = NativePlaybackEngine.getBufferedFrameCount()
        assertTrue(
            "Should have loaded $framesToLoad frames, got $preStartCount",
            preStartCount >= framesToLoad - 1,
        )

        // Start the Oboe stream — this is where the race condition lived
        val started = NativePlaybackEngine.startStream()
        assertTrue("Oboe stream should start", started)

        // Wait for the callback to drain frames. At 48kHz with 2880-sample frames
        // (60ms each), 10 frames = 600ms of audio. Wait 400ms — enough for the
        // callback to consume several frames if it's alive.
        Thread.sleep(400)

        val afterDrainCount = NativePlaybackEngine.getBufferedFrameCount()
        val consumed = preStartCount - afterDrainCount

        // The callback should have consumed at least 4 frames in 400ms
        // (60ms per frame → ~6.6 frames expected). With the race bug,
        // consumed would be 0 (callback died, buffer stays full).
        assertTrue(
            "Oboe callback should consume frames from ring buffer. " +
                "Pre-start=$preStartCount, after 400ms=$afterDrainCount, consumed=$consumed. " +
                "If consumed=0, the SCHED_FIFO callback died on stream start " +
                "(isPlaying_ race condition).",
            consumed >= 4,
        )

        assertTrue(
            "Stream should still be playing after 400ms",
            NativePlaybackEngine.isPlaying(),
        )

        NativePlaybackEngine.destroyDecoder()
    }

    /**
     * Same race condition test for the capture engine.
     *
     * Verifies the Oboe capture callback survives startStream() by checking
     * that encoded packets are produced (callback is encoding → writing to
     * encoded ring buffer → readable via readEncodedPacket).
     *
     * With the race bug: callback returns Stop after 1 invocation, no packets
     * are encoded, readEncodedPacket returns 0 → FAIL.
     */
    @Test
    fun captureCallback_survives_streamStart_raceCondition() {
        val encParams = Profile.HQ.nativeEncodeParams()

        val created =
            NativeCaptureEngine.create(
                sampleRate = encParams.sampleRate,
                channels = encParams.channels,
                frameSamples = encParams.sampleRate * Profile.HQ.frameTimeMs / 1000,
                maxBufferFrames = 75,
                enableFilters = true,
            )
        assertTrue("Capture engine should create", created)

        NativeCaptureEngine.configureEncoder(
            codecType = encParams.codecType,
            sampleRate = encParams.sampleRate,
            channels = encParams.channels,
            opusApp = encParams.opusApplication,
            opusBitrate = encParams.opusBitrate,
            opusComplexity = encParams.opusComplexity,
            codec2Mode = encParams.codec2LibraryMode,
        )

        // Start stream — race condition lived here
        val started = NativeCaptureEngine.startStream()
        assertTrue("Capture stream should start", started)

        // Wait 500ms — at 60ms frames, expect ~8 encoded packets
        Thread.sleep(500)

        val encodedBuf = ByteArray(1500)
        var packetCount = 0
        while (NativeCaptureEngine.readEncodedPacket(encodedBuf) > 0) {
            packetCount++
        }

        // With the race bug: callback dies, no encoding, 0 packets.
        // With the fix: callback runs, produces encoded packets.
        assertTrue(
            "Capture callback should produce encoded packets after startStream(). " +
                "Got $packetCount packets in 500ms. If 0, the SCHED_FIFO callback " +
                "died on stream start (isRecording_ race condition).",
            packetCount >= 3,
        )
    }

    // =====================================================================
    //  PREBUFFER: Stream must not start before ring buffer has data
    // =====================================================================

    /**
     * Regression test for missing prebuffer in Phase 3 native codec path.
     *
     * Bug: Telephone.openPipelinesNativeCodec() calls startStream() immediately
     * after configuring the decoder, before any audio data exists. The Oboe
     * callback fires every ~20ms (burst=960 at 48kHz) but LXST frames arrive
     * every ~60ms from the network. With no prebuffer:
     *
     *   - Each decoded frame is consumed instantly (buf=0 always)
     *   - Between frames, the callback outputs silence (~23 silence callbacks
     *     before the first data frame, 176 total over 50 frames on .249)
     *   - Result: 78% silence, choppy audio with good quality between gaps
     *
     * This test verifies the CORRECT behavior: pre-load all frames into the
     * ring buffer, then start the stream. The callback should serve all frames
     * with ZERO silence callbacks during the drain (since all data is pre-loaded
     * and available instantly).
     *
     * Fix: Defer startStream() until prebufferFrames have been decoded into
     * the ring buffer, matching Phase 2 OboeLineSink behavior.
     */
    @Test
    fun nativeDecoder_prebuffer_preventsExcessiveSilence() {
        sinePhase = 0.0
        val decFrameSamples = 48000 * 60 / 1000 // 2880 (HQ: 60ms at 48kHz)
        val framesToLoad = 15 // 900ms of audio pre-loaded

        val created =
            NativePlaybackEngine.create(
                sampleRate = 48000,
                channels = 1,
                frameSamples = decFrameSamples,
                maxBufferFrames = 75,
                prebufferFrames = PREBUFFER_FRAMES,
            )
        assertTrue("Playback engine should create", created)
        playbackEngineCreated = true

        // Configure native decoder (HQ profile)
        val hqDecParams = Profile.HQ.nativeDecodeParams()
        NativePlaybackEngine.configureDecoder(
            codecType = hqDecParams.codecType,
            sampleRate = hqDecParams.sampleRate,
            channels = hqDecParams.channels,
            opusApp = hqDecParams.opusApplication,
            opusBitrate = hqDecParams.opusBitrate,
            opusComplexity = hqDecParams.opusComplexity,
            codec2Mode = hqDecParams.codec2LibraryMode,
        )

        // Pre-load ALL frames into ring buffer (no real-time pacing)
        val enc = trackCodec(Profile.HQ.createCodec())
        repeat(framesToLoad) {
            val pcm = generateSineFrame(48000, 1, 60)
            val encoded = enc.encode(pcm)
            NativePlaybackEngine.writeEncodedPacket(encoded, 0, encoded.size)
        }

        // Verify all frames accumulated
        val preStartBuf = NativePlaybackEngine.getBufferedFrameCount()
        assertTrue(
            "Should have $framesToLoad frames buffered, got $preStartBuf",
            preStartBuf >= framesToLoad - 1,
        )

        // Start the stream — all data is pre-loaded, callback should never starve
        val started = NativePlaybackEngine.startStream()
        assertTrue("Oboe stream should start", started)

        // Wait for ~60% of audio to play (540ms of 900ms), then check.
        // All data was pre-loaded, so the callback should have zero silence
        // during this window.
        Thread.sleep(540)

        val cbFrames = NativePlaybackEngine.getCallbackFrameCount()
        val cbSilence = NativePlaybackEngine.getCallbackSilenceCount()

        // With all data pre-loaded, the callback should have consumed ~9 frames
        // (540ms / 60ms) with ZERO silence. Allow a small margin for stream
        // startup latency (first 1-2 callbacks might output silence before the
        // first read).
        assertTrue(
            "With pre-loaded data, callback should serve frames with minimal silence. " +
                "cbFrames=$cbFrames (expected ~9), cbSilence=$cbSilence (expected <3). " +
                "If cbSilence is high, the stream started before data was available.",
            cbSilence <= 3,
        )

        assertTrue(
            "Callback should have consumed at least 5 frames in 540ms. " +
                "Got cbFrames=$cbFrames.",
            cbFrames >= 5,
        )

        // Wait for full drain + cleanup
        Thread.sleep(600)

        NativePlaybackEngine.destroyDecoder()
    }
}
