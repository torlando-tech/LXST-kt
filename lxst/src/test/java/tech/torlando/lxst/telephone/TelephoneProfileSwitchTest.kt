/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package tech.torlando.lxst.telephone

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import tech.torlando.lxst.audio.LinkSource
import tech.torlando.lxst.audio.Signalling
import tech.torlando.lxst.core.AudioDevice
import tech.torlando.lxst.core.CallCoordinator
import tech.torlando.lxst.core.PacketRouter

/**
 * Unit tests for mid-call profile switching: BOTH switch paths — local
 * [Telephone.switchProfile] and remote [Signalling]-driven
 * [Telephone.switchProfileFromRemote] — must reconfigure the RECEIVE
 * pipeline to the new profile's decode codec, not just the transmit one.
 *
 * Regression for columba#1238: a locally initiated cross-codec switch
 * (e.g. Codec2 → Opus) reconfigured only the TX encoder and signalled the
 * peer, which then switched its TX. The initiator's RX decoder stayed
 * pinned to the old codec, so every incoming packet failed to decode and
 * the initiator heard silence for the rest of the call (audio returned
 * only when switching back to the original codec).
 *
 * Uses the Phase 2 (useNativeCodec=false) path so no JNI is required.
 * MQ decodes as Opus VOICE_HIGH (mono); SHQ decodes as Opus VOICE_MAX
 * (stereo) — the decode-channel count is the JNI-free observable that
 * distinguishes a reconfigured RX decoder from a stale one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TelephoneProfileSwitchTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var mockContext: Context
    private lateinit var mockTransport: NetworkTransport
    private lateinit var mockAudioBridge: AudioDevice
    private lateinit var mockPacketRouter: PacketRouter
    private lateinit var mockCallCoordinator: CallCoordinator
    private lateinit var telephone: Telephone

    private var signalCallback: ((Int) -> Unit)? = null

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockContext = mockk(relaxed = true)
        mockTransport = mockk(relaxed = true)
        mockAudioBridge = mockk(relaxed = true)
        mockPacketRouter = mockk(relaxed = true)
        mockCallCoordinator = mockk(relaxed = true)

        val signalSlot = slot<(Int) -> Unit>()
        every { mockTransport.setSignalCallback(capture(signalSlot)) } answers {
            signalCallback = signalSlot.captured
        }
        every { mockTransport.isLinkActive } returns false

        telephone =
            Telephone(
                context = mockContext,
                networkTransport = mockTransport,
                audioBridge = mockAudioBridge,
                networkPacketBridge = mockPacketRouter,
                callBridge = mockCallCoordinator,
                useNativeCodec = false,
            )
    }

    @After
    fun teardown() {
        telephone.shutdown()
        Dispatchers.resetMain()
        unmockkAll()
    }

    /** Bring the call to ESTABLISHED via the incoming-call path (proven pattern). */
    private suspend fun establishCall() {
        telephone.onIncomingCall("abcd1234")
        testDispatcher.scheduler.advanceUntilIdle()
        telephone.answer()
        assertEquals(Signalling.STATUS_ESTABLISHED, telephone.callStatus)
    }

    /** The Telephone's private receive pipeline, for observing RX decoder state. */
    private fun receiveLinkSource(): LinkSource {
        val field = Telephone::class.java.getDeclaredField("linkSource")
        field.isAccessible = true
        return field.get(telephone) as LinkSource
    }

    @Test
    fun `established call decodes with the active profile decode codec`() =
        runTest {
            establishCall()
            // Default profile MQ decodes as Opus VOICE_HIGH: mono at 48 kHz.
            assertEquals(1, receiveLinkSource().codec.codecChannels)
            assertEquals(48000, receiveLinkSource().sampleRate)
        }

    @Test
    fun `switchProfile reconfigures the receive decoder to the new profile`() =
        runTest {
            establishCall()

            // SHQ decodes as Opus VOICE_MAX: stereo at 48 kHz. The RX decoder
            // must follow the profile, exactly as switchProfileFromRemote does —
            // otherwise the peer's new-codec packets never decode and the
            // initiator hears silence (columba#1238).
            telephone.switchProfile(Profile.SHQ)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(Profile.SHQ, telephone.activeProfile)
            assertEquals(2, receiveLinkSource().codec.codecChannels)
            assertEquals(48000, receiveLinkSource().sampleRate)
        }

    @Test
    fun `remote profile switch reconfigures the receive decoder to the new profile`() =
        runTest {
            establishCall()

            signalCallback?.invoke(Signalling.PREFERRED_PROFILE + Profile.SHQ.id)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(Profile.SHQ, telephone.activeProfile)
            assertEquals(2, receiveLinkSource().codec.codecChannels)
            assertEquals(48000, receiveLinkSource().sampleRate)
        }
}
