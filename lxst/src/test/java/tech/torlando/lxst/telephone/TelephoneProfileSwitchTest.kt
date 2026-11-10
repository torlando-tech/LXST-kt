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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import tech.torlando.lxst.audio.LinkSource
import tech.torlando.lxst.audio.Mixer
import tech.torlando.lxst.audio.OboeLineSink
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

    /** The Telephone's private receive mixer (caches the playback channel count). */
    private fun receiveMixer(): Mixer {
        val field = Telephone::class.java.getDeclaredField("receiveMixer")
        field.isAccessible = true
        return field.get(telephone) as Mixer
    }

    /** The channel count configured on the audio output sink. */
    private fun outputSinkChannels(): Int {
        val sinkField = Telephone::class.java.getDeclaredField("audioOutput")
        sinkField.isAccessible = true
        val sink = requireNotNull(sinkField.get(telephone)) { "audioOutput must exist once established" }
        val chField = OboeLineSink::class.java.getDeclaredField("channels")
        chField.isAccessible = true
        return chField.getInt(sink)
    }

    /** Everything downstream of the RX decoder must match a stereo SHQ decode. */
    private fun assertStereoReceive(tag: String) {
        assertEquals("$tag: linkSource codec channels", 2, receiveLinkSource().codec.codecChannels)
        assertEquals("$tag: linkSource channels", 2, receiveLinkSource().channels)
        assertEquals("$tag: linkSource sampleRate", 48000, receiveLinkSource().sampleRate)
        assertEquals("$tag: mixer cached channels", 2, receiveMixer().channels)
        assertEquals("$tag: audio output channels", 2, outputSinkChannels())
    }

    @Test
    fun `established call decodes with the active profile decode codec`() =
        runTest {
            establishCall()
            // Default profile MQ decodes as Opus VOICE_HIGH: mono at 48 kHz.
            assertEquals(1, receiveLinkSource().codec.codecChannels)
            assertEquals(1, receiveLinkSource().channels)
            assertEquals(48000, receiveLinkSource().sampleRate)
        }

    @Test
    fun `switchProfile reconfigures the receive decoder to the new profile`() =
        runTest {
            establishCall()

            // SHQ decodes as Opus VOICE_MAX: stereo at 48 kHz. The RX decoder
            // and everything downstream of it (link source channel count,
            // mixer's cached channel count, audio output) must follow the
            // profile, exactly as switchProfileFromRemote does — otherwise the
            // peer's new-codec packets never decode or stereo frames play as
            // garbled mono (columba#1238).
            telephone.switchProfile(Profile.SHQ)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(Profile.SHQ, telephone.activeProfile)
            assertStereoReceive("local switchProfile")
        }

    @Test
    fun `remote profile switch reconfigures the receive decoder to the new profile`() =
        runTest {
            establishCall()

            signalCallback?.invoke(Signalling.PREFERRED_PROFILE + Profile.SHQ.id)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(Profile.SHQ, telephone.activeProfile)
            assertStereoReceive("remote PREFERRED_PROFILE")
        }

    @Test
    fun `overlapping local and remote switches leave receive on the final profile`() =
        runTest {
            establishCall()

            // Both profiles must be JVM-constructible (Opus codecs init lazily;
            // a Codec2 switch would throw UnsatisfiedLinkError on the JVM and
            // mask the race). SHQ decodes stereo, HQ decodes mono — the
            // receive channel count reveals which switch last owned receive.
            //
            // Block the local switch mid-flight: after it set activeProfile=SHQ
            // and sent its signal, but before it reconfigures transmit/receive.
            val insideLocalSwitch = CountDownLatch(1)
            val releaseLocalSwitch = CountDownLatch(1)
            every { mockTransport.sendSignal(Signalling.PREFERRED_PROFILE + Profile.SHQ.id) } answers {
                insideLocalSwitch.countDown()
                assertTrue("local switch must be released", releaseLocalSwitch.await(5, TimeUnit.SECONDS))
            }

            var localError: Throwable? = null
            val local =
                Thread {
                    try {
                        telephone.switchProfile(Profile.SHQ)
                    } catch (e: Throwable) {
                        localError = e
                    }
                }.apply {
                    name = "local-profile-switch"
                    start()
                }
            assertTrue("local switch must reach sendSignal", insideLocalSwitch.await(5, TimeUnit.SECONDS))

            // A remote PREFERRED_PROFILE(HQ) callback lands while the local
            // switch is mid-flight — Python delivers signals on its own thread.
            var remoteError: Throwable? = null
            val remote =
                Thread {
                    try {
                        signalCallback?.invoke(Signalling.PREFERRED_PROFILE + Profile.HQ.id)
                    } catch (e: Throwable) {
                        remoteError = e
                    }
                }.apply {
                    name = "remote-profile-signal"
                    start()
                }

            // Let the remote callback either run to completion (unserialized)
            // or block on the Telephone monitor (serialized), then finish both.
            Thread.sleep(100)
            releaseLocalSwitch.countDown()
            local.join(5000)
            remote.join(5000)
            assertFalse("local switch thread must finish", local.isAlive)
            assertFalse("remote signal thread must finish", remote.isAlive)
            localError?.let { throw it }
            remoteError?.let { throw it }

            // The remote switch ran last (serialization guarantees it; without
            // serialization it completes first and the local receive step
            // overwrites it). Either way activeProfile and receive must agree:
            // HQ decodes mono, so receive must be mono. Without serialization
            // activeProfile=HQ but receive stays SHQ stereo, and incoming mono
            // HQ packets play as garbled half-speed stereo (Greptile P1 on #26).
            assertEquals(Profile.HQ, telephone.activeProfile)
            assertEquals(1, receiveLinkSource().channels)
            assertEquals(1, receiveLinkSource().codec.codecChannels)
        }
}
