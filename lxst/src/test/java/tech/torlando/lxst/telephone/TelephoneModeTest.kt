/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package tech.torlando.lxst.telephone

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tech.torlando.lxst.audio.Signalling
import tech.torlando.lxst.core.AudioDevice
import tech.torlando.lxst.core.CallCoordinator
import tech.torlando.lxst.core.PacketRouter

/**
 * Unit tests for [Telephone] call-mode (full/half duplex) negotiation and the
 * half-duplex transmit gate.
 *
 * Mirrors the Python LXST ground truth (Telephony.py):
 * - switch_mode() signals PREFERRED_MODE + mode and squelches/unsquelches the wire.
 * - squelch_transmit/unsquelch_transmit (PTT) combine with mode so the gate is
 *   `gated = (HDX && !PTT held)`, driving BOTH the wire squelch and AGC pause.
 * - Remote mode signals (0xF0-0xFE) are routed to mode handling, not status/profile.
 * - The gate is re-applied when the TX pipeline opens (Python line 673).
 *
 * Uses MockK for all hardware dependencies, useNativeCodec=false (no JNI).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TelephoneModeTest {
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

    /**
     * Bring the call to ESTABLISHED via the incoming-call path (proven pattern).
     * Note: startPipelines() applies the transmit gate (unsquelch + AGC running in
     * the default FDX), so some unsquelch/AGC calls are expected after this.
     */
    private suspend fun establishCall() {
        telephone.onIncomingCall("abcd1234")
        testDispatcher.scheduler.advanceUntilIdle()
        telephone.answer()
        assertEquals(Signalling.STATUS_ESTABLISHED, telephone.callStatus)
        // Note: answer() sends setup signalling (CONNECTING, PROFILE preference,
        // ESTABLISHED) and startPipelines() applies the initial gate. Per-test
        // assertions target the specific mode signal under test, so the setup
        // calls do not interfere.
    }

    // ===== Initial state =====

    @Test
    fun `initial mode is full duplex default`() {
        assertEquals(Mode.DEFAULT, telephone.activeMode)
        assertEquals(Mode.FULL_DUPLEX, telephone.activeMode)
    }

    @Test
    fun `no squelch before an active call`() {
        // No pipeline, no HDX: the wire must never be squelched.
        verify(exactly = 0) { mockPacketRouter.squelch() }
    }

    // ===== switchMode guards =====

    @Test
    fun `switchMode when not established is ignored`() {
        telephone.switchMode(Mode.HALF_DUPLEX)
        verify(exactly = 0) { mockTransport.sendSignal(any()) }
        assertEquals(Mode.FULL_DUPLEX, telephone.activeMode)
        // No wire gate applied (no pipeline yet).
        verify(exactly = 0) { mockPacketRouter.squelch() }
    }

    @Test
    fun `switchMode to same mode is ignored`() = runTest {
        establishCall()
        // Already FDX; switching to FDX again sends no mode signal.
        telephone.switchMode(Mode.FULL_DUPLEX)
        verify(exactly = 0) { mockTransport.sendSignal(Signalling.PREFERRED_MODE + Mode.FULL_DUPLEX.id) }
    }

    // ===== switchMode when established =====

    @Test
    fun `switchMode to half duplex signals peer and squelches wire`() = runTest {
        establishCall()
        telephone.switchMode(Mode.HALF_DUPLEX)

        // Signalled PREFERRED_MODE + 0x02 to the peer.
        verify { mockTransport.sendSignal(Signalling.PREFERRED_MODE + Mode.HALF_DUPLEX.id) }
        // Wire is now squelched (HDX, PTT not held) and AGC paused.
        verify { mockPacketRouter.squelch() }
        verify { mockAudioBridge.setAgcPaused(true) }
        assertEquals(Mode.HALF_DUPLEX, telephone.activeMode)
    }

    @Test
    fun `switchMode to full duplex unsquelches wire`() = runTest {
        establishCall()
        telephone.switchMode(Mode.HALF_DUPLEX)
        telephone.switchMode(Mode.FULL_DUPLEX)

        verify { mockTransport.sendSignal(Signalling.PREFERRED_MODE + Mode.FULL_DUPLEX.id) }
        // Final state unsquelched + AGC running.
        verify { mockPacketRouter.unsquelch() }
        verify { mockAudioBridge.setAgcPaused(false) }
        assertEquals(Mode.FULL_DUPLEX, telephone.activeMode)
    }

    // ===== PTT gating (Python squelch_transmit / unsquelch_transmit) =====

    @Test
    fun `PTT in full duplex never squelches the wire`() = runTest {
        establishCall()
        // FDX: PTT toggles must not squelch the wire (final state open).
        telephone.setPttActive(true)
        telephone.setPttActive(false)
        verify(exactly = 0) { mockPacketRouter.squelch() }
        // AGC stays running in FDX.
        verify { mockAudioBridge.setAgcPaused(false) }
        verify(exactly = 0) { mockAudioBridge.setAgcPaused(true) }
    }

    @Test
    fun `entering half duplex with PTT released squelches and pauses AGC`() = runTest {
        establishCall()
        telephone.switchMode(Mode.HALF_DUPLEX)
        // Steady state: squelched + AGC paused.
        verify { mockPacketRouter.squelch() }
        verify { mockAudioBridge.setAgcPaused(true) }
    }

    @Test
    fun `holding PTT in half duplex unsquelches and resumes AGC`() = runTest {
        establishCall()
        telephone.switchMode(Mode.HALF_DUPLEX)
        telephone.setPttActive(true)
        verify { mockPacketRouter.unsquelch() }
        verify { mockAudioBridge.setAgcPaused(false) }
    }

    @Test
    fun `releasing PTT in half duplex re-squelches and re-pauses AGC`() = runTest {
        establishCall()
        telephone.switchMode(Mode.HALF_DUPLEX)
        telephone.setPttActive(true)
        telephone.setPttActive(false)
        // Final state after release: squelched + AGC paused.
        verify { mockPacketRouter.squelch() }
        verify { mockAudioBridge.setAgcPaused(true) }
    }

    // ===== Remote mode signalling routing =====

    @Test
    fun `remote half duplex when established squelches without re-signalling`() = runTest {
        establishCall()
        signalCallback?.invoke(Signalling.PREFERRED_MODE + Mode.HALF_DUPLEX.id)

        assertEquals(Mode.HALF_DUPLEX, telephone.activeMode)
        verify { mockPacketRouter.squelch() }
        // from_signalling path must not signal the mode back to the peer.
        verify(exactly = 0) { mockTransport.sendSignal(Signalling.PREFERRED_MODE + Mode.HALF_DUPLEX.id) }
    }

    @Test
    fun `remote full duplex when established unsquelches`() = runTest {
        establishCall()
        telephone.switchMode(Mode.HALF_DUPLEX)
        signalCallback?.invoke(Signalling.PREFERRED_MODE + Mode.FULL_DUPLEX.id)

        assertEquals(Mode.FULL_DUPLEX, telephone.activeMode)
        verify { mockPacketRouter.unsquelch() }
    }

    @Test
    fun `pre-established mode signal selects mode without signalling or gating`() {
        // Before the call is established, a mode preference is applied locally only.
        signalCallback?.invoke(Signalling.PREFERRED_MODE + Mode.HALF_DUPLEX.id)
        assertEquals(Mode.HALF_DUPLEX, telephone.activeMode)
        verify(exactly = 0) { mockTransport.sendSignal(any()) }
        // No wire gate before the TX pipeline exists.
        verify(exactly = 0) { mockPacketRouter.squelch() }
    }

    // ===== Signalling routing discrimination =====

    @Test
    fun `mode signals are not misparsed as profile or status`() {
        // 0xF0+0x01 / 0xF0+0x02 are in the mode band, below the profile base (0xFF).
        // Driving them must not touch the profile or status paths.
        val profileBefore = telephone.activeProfile
        val statusBefore = telephone.callStatus
        signalCallback?.invoke(Signalling.PREFERRED_MODE + Mode.FULL_DUPLEX.id)
        signalCallback?.invoke(Signalling.PREFERRED_MODE + Mode.HALF_DUPLEX.id)
        assertEquals(profileBefore, telephone.activeProfile)
        assertEquals(statusBefore, telephone.callStatus)
    }

    @Test
    fun `status signals still route to status not mode`() {
        signalCallback?.invoke(Signalling.STATUS_AVAILABLE)
        assertEquals(Signalling.STATUS_AVAILABLE, telephone.callStatus)
        assertEquals(Mode.FULL_DUPLEX, telephone.activeMode)
    }

    // ===== Reset on call end =====

    @Test
    fun `hangup resets mode to full duplex and clears the gate`() = runTest {
        establishCall()
        telephone.switchMode(Mode.HALF_DUPLEX)
        assertTrue(telephone.isCallActive())

        telephone.hangup()

        assertEquals(Mode.FULL_DUPLEX, telephone.activeMode)
        // Gate cleared on call end.
        verify { mockPacketRouter.unsquelch() }
        verify { mockAudioBridge.setAgcPaused(false) }
    }

    // ===== Gate re-applied on pipeline open =====

    @Test
    fun `pre-established HDX takes effect when the pipeline opens`() = runTest {
        // Incoming call in RINGING; a mode preference arrives before we answer.
        telephone.onIncomingCall("abcd1234")
        advanceUntilIdle()
        assertEquals(Signalling.STATUS_RINGING, telephone.callStatus)

        signalCallback?.invoke(Signalling.PREFERRED_MODE + Mode.HALF_DUPLEX.id)
        assertEquals(Mode.HALF_DUPLEX, telephone.activeMode)
        // Not gated yet (no pipeline).
        verify(exactly = 0) { mockPacketRouter.squelch() }

        // Now answer; startPipelines() must re-apply the gate (Python line 673).
        telephone.answer()
        assertEquals(Signalling.STATUS_ESTABLISHED, telephone.callStatus)
        verify { mockPacketRouter.squelch() }
        verify { mockAudioBridge.setAgcPaused(true) }
    }
}
