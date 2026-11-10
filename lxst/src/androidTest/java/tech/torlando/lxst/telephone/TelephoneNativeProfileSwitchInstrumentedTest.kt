/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package tech.torlando.lxst.telephone

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tech.torlando.lxst.audio.NativeCaptureEngine
import tech.torlando.lxst.audio.NativePlaybackEngine
import tech.torlando.lxst.audio.Signalling
import tech.torlando.lxst.codec.Codec
import tech.torlando.lxst.codec.Codec2
import tech.torlando.lxst.codec.Opus
import tech.torlando.lxst.core.AudioDevice
import tech.torlando.lxst.core.CallCoordinator
import tech.torlando.lxst.core.PacketRouter

/**
 * Instrumented regression for columba#1238 on the PRODUCTION path
 * (useNativeCodec=true, the default both Columba backends construct).
 *
 * A locally initiated cross-codec switch (Opus MQ → Codec2 LBW → back)
 * must reconfigure the native RX decoder together with the TX encoder.
 * Before the fix, switchProfile only reconfigured TX: the peer switched
 * its encoder on our PREFERRED_PROFILE signal, but our decoder stayed
 * pinned to the old codec, every incoming packet failed to decode, and
 * the initiator heard silence until switching back to the original codec.
 *
 * The test drives a real established Telephone call and feeds the native
 * playback engine the exact packets the peer would send after the switch
 * (same writeEncodedPacket entry point LinkSource uses). A stale decoder
 * rejects them (decode <= 0 → false); a reconfigured decoder accepts them
 * into the PCM ring.
 */
@RunWith(AndroidJUnit4::class)
class TelephoneNativeProfileSwitchInstrumentedTest {
    @get:Rule
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private class FakeTransport : NetworkTransport {
        override suspend fun establishLink(destinationHash: ByteArray): Boolean = true

        override fun teardownLink() {}

        override fun sendPacket(encodedFrame: ByteArray) {}

        override fun sendSignal(signal: Int) {}

        override fun setPacketCallback(callback: (ByteArray) -> Unit) {}

        override fun setSignalCallback(callback: (Int) -> Unit) {}

        override val isLinkActive: Boolean get() = true
    }

    private lateinit var telephone: Telephone
    private val codecs = mutableListOf<Codec>()
    private var sinePhase = 0.0

    @Before
    fun setup() {
        sinePhase = 0.0
        val context: Context = ApplicationProvider.getApplicationContext()
        telephone =
            Telephone(
                context = context,
                networkTransport = FakeTransport(),
                audioBridge = AudioDevice(context),
                networkPacketBridge = PacketRouter.getInstance(context),
                callBridge = CallCoordinator.getInstance(),
            )
    }

    @After
    fun cleanup() {
        try {
            telephone.hangup()
        } catch (_: Throwable) {
        }
        try {
            NativePlaybackEngine.destroyDecoder()
            NativePlaybackEngine.stopStream()
            NativePlaybackEngine.destroy()
        } catch (_: Throwable) {
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
        CallCoordinator.resetInstance()
        PacketRouter.resetInstance()
    }

    private fun generateSineFrame(
        sampleRate: Int,
        channels: Int,
        frameTimeMs: Int,
    ): FloatArray {
        val samplesPerChannel = sampleRate * frameTimeMs / 1000
        val frame = FloatArray(samplesPerChannel * channels)
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

    /** Encode one frame the way the peer's TX would produce it for [profile]. */
    private fun peerPacketFor(profile: Profile): ByteArray {
        val encodeParams = profile.nativeEncodeParams()
        val codec = trackCodec(profile.createCodec())
        val pcm = generateSineFrame(encodeParams.sampleRate, encodeParams.channels, profile.frameTimeMs)
        return codec.encode(pcm)
    }

    @Test
    fun localSwitchProfile_crossCodec_reconfiguresNativeReceiveDecoder() {
        // Establish an MQ (Opus) call via the answer path (native codec defaults).
        telephone.prepareForAnswer("abcd1234")
        assertTrue("answer() must succeed after prepareForAnswer", telephone.answer())
        assertEquals(Signalling.STATUS_ESTABLISHED, telephone.callStatus)

        // Sanity: before any switch, the peer's MQ packets decode.
        val mqBefore = peerPacketFor(Profile.MQ)
        assertTrue(
            "MQ opus packet must decode on the established call",
            NativePlaybackEngine.writeEncodedPacket(mqBefore, 0, mqBefore.size),
        )

        // Locally switch Opus MQ → Codec2 LBW. The peer receives our
        // PREFERRED_PROFILE signal and starts sending LBW; our RX decoder
        // must have followed the profile (columba#1238).
        telephone.switchProfile(Profile.LBW)
        assertEquals(Profile.LBW, telephone.activeProfile)

        val lbwPacket = peerPacketFor(Profile.LBW)
        assertTrue(
            "LBW codec2 packet must decode after local switchProfile — RX decoder stayed pinned to the old codec",
            NativePlaybackEngine.writeEncodedPacket(lbwPacket, 0, lbwPacket.size),
        )
        assertEquals(
            "Decoded LBW frame must enter the recreated PCM ring",
            1,
            NativePlaybackEngine.getBufferedFrameCount(),
        )

        // Switch back to MQ: the peer returns to Opus and our decoder must
        // follow again (the reporter's "audio comes right back" workaround).
        telephone.switchProfile(Profile.MQ)
        assertEquals(Profile.MQ, telephone.activeProfile)

        val mqPacket = peerPacketFor(Profile.MQ)
        assertTrue(
            "MQ opus packet must decode after switching back",
            NativePlaybackEngine.writeEncodedPacket(mqPacket, 0, mqPacket.size),
        )
    }
}
