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
import io.mockk.verifyOrder
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    // Final-state tracking: the last gate/AGC action the Telephone applied. Reset per
    // test and driven by mock side-effects below, so tests can assert the gate's FINAL
    // state (the last squelch/unsquelch and last AGC pause) rather than relying on
    // at-least-once verify(), which establishCall's own initial gate calls would
    // satisfy even if a later transition were a no-op (Greptile P2 "earlier calls
    // satisfy transition assertions").
    private var wireSquelched = false
    private var agcPaused = false

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockContext = mockk(relaxed = true)
        mockTransport = mockk(relaxed = true)
        mockAudioBridge = mockk(relaxed = true)
        mockPacketRouter = mockk(relaxed = true)
        mockCallCoordinator = mockk(relaxed = true)

        wireSquelched = false
        agcPaused = false

        val signalSlot = slot<(Int) -> Unit>()
        every { mockTransport.setSignalCallback(capture(signalSlot)) } answers {
            signalCallback = signalSlot.captured
        }
        every { mockTransport.isLinkActive } returns false

        // Record the last gate/AGC action so final-state assertions are discriminating.
        every { mockPacketRouter.squelch() } answers { wireSquelched = true }
        every { mockPacketRouter.unsquelch() } answers { wireSquelched = false }
        every { mockPacketRouter.isSquelched() } answers { wireSquelched }
        every { mockAudioBridge.setAgcPaused(true) } answers { agcPaused = true }
        every { mockAudioBridge.setAgcPaused(false) } answers { agcPaused = false }

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
        // Final state: wire squelched (HDX, PTT not held) + AGC paused.
        assertTrue("HDX switch must squelch", wireSquelched)
        assertTrue("HDX switch must pause AGC", agcPaused)
        assertEquals(Mode.HALF_DUPLEX, telephone.activeMode)
    }

    @Test
    fun `switchMode to full duplex unsquelches wire`() = runTest {
        establishCall()
        telephone.switchMode(Mode.HALF_DUPLEX)
        telephone.switchMode(Mode.FULL_DUPLEX)

        verify { mockTransport.sendSignal(Signalling.PREFERRED_MODE + Mode.FULL_DUPLEX.id) }
        // Final state unsquelched + AGC running. Final-state (not at-least-once) so the
        // FDX switch itself is what unsquelches, not establishCall's initial gate (which
        // is overridden to squelched by the HDX switch just before).
        assertFalse(wireSquelched)
        assertFalse(agcPaused)
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
        // Final gate state: open + AGC running.
        assertFalse("FDX PTT release must leave the wire open", wireSquelched)
        assertFalse("FDX PTT release must leave AGC running", agcPaused)
    }

    @Test
    fun `entering half duplex with PTT released squelches and pauses AGC`() = runTest {
        establishCall()
        telephone.switchMode(Mode.HALF_DUPLEX)
        // Final gate state: squelched + AGC paused.
        assertTrue("entering HDX (PTT released) must squelch", wireSquelched)
        assertTrue("entering HDX (PTT released) must pause AGC", agcPaused)
    }

    @Test
    fun `holding PTT in half duplex unsquelches and resumes AGC`() = runTest {
        establishCall()
        telephone.switchMode(Mode.HALF_DUPLEX)
        telephone.setPttActive(true)
        // Final gate state: open + AGC running.
        assertFalse("PTT held in HDX must leave the wire open", wireSquelched)
        assertFalse("PTT held in HDX must resume AGC", agcPaused)
    }

    @Test
    fun `releasing PTT in half duplex re-squelches and re-pauses AGC`() = runTest {
        establishCall()
        telephone.switchMode(Mode.HALF_DUPLEX)
        telephone.setPttActive(true)
        telephone.setPttActive(false)
        // Final gate state after release: squelched + AGC paused.
        assertTrue("PTT released in HDX must re-squelch", wireSquelched)
        assertTrue("PTT released in HDX must re-pause AGC", agcPaused)
    }

    // ===== Remote mode signalling routing =====

    @Test
    fun `remote half duplex when established squelches without re-signalling`() = runTest {
        establishCall()
        signalCallback?.invoke(Signalling.PREFERRED_MODE + Mode.HALF_DUPLEX.id)

        assertEquals(Mode.HALF_DUPLEX, telephone.activeMode)
        // Final state: remote HDX squelches + pauses AGC (PTT not held).
        assertTrue("remote HDX must squelch", wireSquelched)
        assertTrue("remote HDX must pause AGC", agcPaused)
        // from_signalling path must not signal the mode back to the peer.
        verify(exactly = 0) { mockTransport.sendSignal(Signalling.PREFERRED_MODE + Mode.HALF_DUPLEX.id) }
    }

    @Test
    fun `remote full duplex when established unsquelches`() = runTest {
        establishCall()
        telephone.switchMode(Mode.HALF_DUPLEX)
        signalCallback?.invoke(Signalling.PREFERRED_MODE + Mode.FULL_DUPLEX.id)

        assertEquals(Mode.FULL_DUPLEX, telephone.activeMode)
        // Final state: remote FDX unsquelches + resumes AGC (overrides the HDX squelch).
        assertFalse("remote FDX must unsquelch", wireSquelched)
        assertFalse("remote FDX must resume AGC", agcPaused)
    }

    // ===== Concurrency: gate state must always reflect the latest inputs =====
    // Greptile P1 "Concurrent updates leave stale gates": a remote HDX switch and a
    // local PTT press must not interleave into a stale gate (e.g. PTT held but wire
    // still squelched). Both the signal handler and the public mode/PTT entry points
    // are @Synchronized on the same monitor, so each read-modify-write of
    // (mode, pttHeld) + applyTransmitGate is atomic and the gate always ends in the
    // state that matches the FINAL inputs. We pin that invariant by clearing
    // invocations after setup and asserting the exact ordered gate sequence - a
    // stale intermediate gate (the P1 bug) would reorder or drop a transition.

    @Test
    fun `remote HDX then local PTT press ends open (PTT held)`() = runTest {
        establishCall()
        // Remote switches us to HDX -> squelch (PTT not held).
        signalCallback?.invoke(Signalling.PREFERRED_MODE + Mode.HALF_DUPLEX.id)
        assertEquals(Mode.HALF_DUPLEX, telephone.activeMode)
        // Then the local user presses PTT (transmitting): must re-open the wire.
        // A stale squelch from the remote switch must not linger (the P1 bug - no
        // TX until the next transition). The ordered sequence proves the PTT press
        // re-applied the gate after the remote squelch.
        telephone.setPttActive(true)
        verifyOrder {
            mockPacketRouter.squelch()    // remote HDX, PTT released
            mockPacketRouter.unsquelch()  // PTT held -> open
        }
        verify { mockAudioBridge.setAgcPaused(false) }
    }

    @Test
    fun `local PTT press then remote HDX ends open (PTT held)`() = runTest {
        establishCall()
        // Local PTT press while still FDX: FDX never squelches, so no gate flip.
        telephone.setPttActive(true)
        // Remote switches to HDX. PTT is held, so the gate computes open
        // (HDX && !pttHeld = false) - it must NOT squelch even though mode is HDX.
        signalCallback?.invoke(Signalling.PREFERRED_MODE + Mode.HALF_DUPLEX.id)
        assertEquals(Mode.HALF_DUPLEX, telephone.activeMode)
        // No squelch may have happened; the gate ends open.
        verify(exactly = 0) { mockPacketRouter.squelch() }
        verify { mockPacketRouter.unsquelch() }
        verify { mockAudioBridge.setAgcPaused(false) }
    }

    @Test
    fun `remote HDX then PTT release ends squelched`() = runTest {
        establishCall()
        signalCallback?.invoke(Signalling.PREFERRED_MODE + Mode.HALF_DUPLEX.id)
        telephone.setPttActive(true)   // open (PTT held)
        telephone.setPttActive(false)  // release -> squelch again
        // Final state: HDX + PTT released => squelched + AGC paused.
        verifyOrder {
            mockPacketRouter.squelch()    // remote HDX
            mockPacketRouter.unsquelch()  // PTT held
            mockPacketRouter.squelch()    // PTT released
        }
        assertTrue("final gate must be squelched", wireSquelched)
        assertTrue("final AGC must be paused", agcPaused)
    }

    // ===== True concurrency: overlapping updates must not leave a stale gate =====
    // Greptile P2 "concurrency tests run sequentially": the tests above complete one
    // operation before starting the next, so they would pass even if the @Synchronized
    // annotations were removed. This test genuinely overlaps a remote mode change
    // (the @Synchronized signal handler) with a local PTT press (the @Synchronized
    // public entry point) via a start barrier, then asserts the deterministic final
    // invariant. Whichever block acquires the monitor first, the final inputs are
    // (mode=HDX, pttHeld=true) and the gate MUST end open; an unsynchronized
    // read-modify-write would let a stale squelch linger (the original P1).
    @Test
    fun `overlapping remote HDX and PTT press ends open regardless of order`() = runTest {
        establishCall()
        val go = CountDownLatch(1)
        val done = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            executor.execute {
                go.await()
                signalCallback?.invoke(Signalling.PREFERRED_MODE + Mode.HALF_DUPLEX.id)
                done.countDown()
            }
            executor.execute {
                go.await()
                telephone.setPttActive(true)
                done.countDown()
            }
            // Release both at once so the two synchronized blocks overlap.
            go.countDown()
            assertTrue("both operations must complete", done.await(5, TimeUnit.SECONDS))

            // Final inputs are (HDX, PTT held) regardless of interleaving order, so
            // the gate must end open and AGC running. A stale squelch from the remote
            // switch (the P1 bug) would leave the wire closed here.
            assertEquals(Mode.HALF_DUPLEX, telephone.activeMode)
            assertFalse("PTT held + HDX => wire must be open", wireSquelched)
            assertFalse("PTT held + HDX => AGC must be running", agcPaused)
        } finally {
            executor.shutdownNow()
        }
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
        // Final gate state cleared on call end. Final-state (not at-least-once) so the
        // hangup itself is what clears it, not establishCall's initial gate (which is
        // overridden to squelched by the HDX switch just before).
        assertFalse(wireSquelched)
        assertFalse(agcPaused)
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
