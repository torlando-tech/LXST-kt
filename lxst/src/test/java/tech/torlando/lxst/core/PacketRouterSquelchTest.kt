/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package tech.torlando.lxst.core

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for the half-duplex transmit gate in [PacketRouter] (Python LXST
 * Packetizer.squelch port).
 *
 * The critical regression is Greptile P1 "Buffered packets bypass squelch": a
 * transport backlog queued BEFORE squelch() runs must not drain to the wire.
 * [PacketRouter] enforces the gate at two points - sendPacket (enqueue) and the
 * consumer (transport handoff) - and squelch() invalidates every already-buffered
 * packet via a monotonically increasing gate epoch.
 *
 * The consumer normally runs on Dispatchers.IO with an internal channel; for a
 * deterministic test we inject a test-dispatcher scope and a channel we control
 * (PacketRouter.forTesting) so the gate is exercised without real threading. A
 * counting [AudioPacketHandler] records exactly which frames reach the wire.
 */
class PacketRouterSquelchTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var router: PacketRouter
    private lateinit var channel: Channel<QueuedPacket>
    private val delivered = AtomicInteger(0)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = mockk(relaxed = true)
        channel = Channel(capacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val scope = CoroutineScope(testDispatcher + SupervisorJob())
        router = PacketRouter.forTesting(context, scope, channel)
        delivered.set(0)
        router.setPacketHandler(object : AudioPacketHandler {
            override fun receiveAudioPacket(packet: ByteArray) {
                delivered.incrementAndGet()
            }

            override fun receiveSignal(signal: Int) {
                // signalling is intentionally not gated; unused here
            }
        })
    }

    @After
    fun teardown() {
        runTest {
            try {
                router.shutdown()
            } catch (_: Exception) {
                // scope already cancelled - fine
            }
        }
        Dispatchers.resetMain()
    }

    // ===== Enqueue-time gate =====

    @Test
    fun `sendPacket drops frames while squelched`() = runTest {
        router.squelch()
        assertTrue(router.isSquelched())
        // Nothing may be enqueued while the gate is closed: the consumer receives
        // nothing and the channel stays empty (no delivery to the wire).
        router.sendPacket(byteArrayOf(1, 2, 3))
        testDispatcher.scheduler.runCurrent()
        assertEquals("no frame may reach the wire while squelched", 0, delivered.get())
    }

    @Test
    fun `sendPacket enqueues when open`() = runTest {
        assertFalse(router.isSquelched())
        router.sendPacket(byteArrayOf(1, 2, 3))
        // When open the consumer forwards the frame to the wire.
        testDispatcher.scheduler.runCurrent()
        assertEquals("open-window frame reaches the wire", 1, delivered.get())
    }

    // ===== Consumer-time gate (the P1 regression) =====

    @Test
    fun `consumer drops stale backlog queued before squelch`() = runTest {
        // Open: queue a small backlog (simulates a slow transport).
        repeat(4) { router.sendPacket(byteArrayOf(it.toByte())) }

        // PTT released -> squelch. The epoch bumps; the 4 buffered packets are now
        // stale (captured before the gate closed).
        router.squelch()
        assertTrue(router.isSquelched())

        // Let the consumer run. It must drop all 4 stale packets - nothing reaches
        // the transport even though they are already in the channel.
        testDispatcher.scheduler.runCurrent()
        assertEquals("stale backlog must not reach the wire", 0, delivered.get())
    }

    @Test
    fun `unsquelch after squelch does not revalidate a stale backlog`() = runTest {
        // Open, enqueue two packets (captured at epoch 0).
        router.sendPacket(byteArrayOf(0))
        router.sendPacket(byteArrayOf(0))

        // squelch bumps the epoch -> both are stale. Then unsquelch reopens the gate
        // but does NOT bump the epoch back, so the two stale packets are still
        // invalid. The consumer drops them rather than draining stale TX.
        router.squelch()
        router.unsquelch()
        assertFalse(router.isSquelched())

        testDispatcher.scheduler.runCurrent()
        assertEquals("stale backlog must not be revalidated", 0, delivered.get())
    }

    @Test
    fun `fresh packet after squelch-unsquelch cycle is delivered`() = runTest {
        // Establish a squelch/unsquelch cycle so the epoch has advanced to 1.
        router.squelch()
        router.unsquelch()
        assertFalse(router.isSquelched())

        // A packet captured after the cycle carries the current epoch, so the
        // consumer delivers it (gate open + epoch matches).
        router.sendPacket(byteArrayOf(9))
        testDispatcher.scheduler.runCurrent()
        assertEquals("fresh matching-epoch packet should reach the wire", 1, delivered.get())
    }

    @Test
    fun `packets delivered normally while open`() = runTest {
        repeat(3) { router.sendPacket(byteArrayOf(it.toByte())) }
        testDispatcher.scheduler.runCurrent()
        assertEquals("all open-window packets delivered", 3, delivered.get())
    }
}
