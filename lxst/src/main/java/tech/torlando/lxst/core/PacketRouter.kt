/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package tech.torlando.lxst.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

// TODO: Future reorganization (Option B) — redistribute core/ classes by domain:
//   CallCoordinator, CallState → telephone/
//   PacketRouter → transport/
//   AudioDevice, AudioFilters → audio/

/**
 * Low-level coordination layer for packet transfer between the audio pipeline
 * and the network transport.
 *
 * All packet and signalling traffic flows through this single point.
 *
 * **Threading Model:**
 * - Outbound (audio pipeline -> network): Uses [Dispatchers.IO] coroutine scope to avoid blocking audio thread
 * - Inbound (network -> audio pipeline): Fast callback invocation (caller may hold transport locks)
 *
 * **Critical Performance Note:**
 * No synchronous logging in [sendPacket] or [onInboundPacket] methods.
 * Logging blocks the audio thread and causes choppiness.
 *
 * @see AudioDevice Reference implementation for singleton pattern
 * @see CallCoordinator Reference implementation for Python callback pattern
 */
// Pairs (frame, capturedEpoch) for in-flight packets so the consumer can detect
// a squelch that happened after the packet was enqueued. Internal so the unit
// tests can enqueue packets directly to exercise the consumer's epoch gate.
internal class QueuedPacket(val frame: ByteArray, val epoch: Int)

@Suppress("TooManyFunctions")
class PacketRouter private constructor(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val packetChannel: Channel<QueuedPacket> = Channel(
        capacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    ),
) {
    companion object {
        private const val TAG = "LXST:PacketRouter"

        @Volatile
        private var instance: PacketRouter? = null

        /**
         * Get or create singleton instance.
         *
         * @param context Application context (used for consistency with other bridges)
         */
        fun getInstance(context: Context): PacketRouter {
            return instance ?: synchronized(this) {
                instance ?: PacketRouter(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Reset singleton instance (for testing).
         */
        internal fun resetInstance() {
            synchronized(this) {
                instance?.shutdown()
                instance = null
            }
        }

        /**
         * Test-only constructor: inject a [scope] (typically a [StandardTestDispatcher]
         * scope) and a [packetChannel] the test can observe and drive deterministically.
         * Production always uses the defaults (Dispatchers.IO + a 16-deep DROP_OLDEST
         * channel) via [getInstance]; this is only for the unit tests that assert the
         * consumer's squelch gate against a controlled backlog.
         */
        internal fun forTesting(
            context: Context,
            scope: CoroutineScope,
            packetChannel: Channel<QueuedPacket>,
        ): PacketRouter = PacketRouter(context, scope, packetChannel)
    }

    // Half-duplex transmit squelch. When true, sendPacket() drops audio frames
    // so nothing is transmitted on the wire (matches Python LXST Packetizer.squelch -
    // the frame is dropped before encoding/sending, not sent as silence).
    // Signalling (sendSignal) is intentionally NOT squelched: mode/profile
    // negotiation and call status must flow even while transmit is gated.
    private val squelched = AtomicBoolean(false)

    // Gate epoch. Monotonically incremented ONLY on squelch() (NOT on unsquelch),
    // so every squelch invalidates all packets already buffered in [packetChannel]
    // (captured before the gate closed). The consumer drops any packet whose
    // captured epoch no longer matches the current one - this prevents a
    // transport backlog from draining stale TX after PTT release. Reopening
    // (unsquelch) does not bump the epoch, so packets captured during the open
    // window remain valid after a squelch -> unsquelch cycle.
    private val gateEpoch = java.util.concurrent.atomic.AtomicInteger(0)

    // TEMP: Diagnostic counter for consumer coroutine
    @Volatile
    private var consumerDeliveryCount = 0

    init {
        // Single consumer drains audio packets sequentially.
        // This prevents multiple concurrent handler invocations
        // from the Dispatchers.IO thread pool.
        scope.launch {
            for (queued in packetChannel) {
                try {
                    // Enforce the half-duplex squelch at the transport handoff, not just
                    // at enqueue: packets already buffered when squelch() ran must not
                    // reach the wire. Drop when the gate is currently closed, or when
                    // a squelch happened after this packet was captured (epoch mismatch)
                    // - either way it is stale TX that would delay the peer's turn.
                    if (squelched.get() || queued.epoch != gateEpoch.get()) continue
                    val handler = packetHandler
                    if (handler == null) {
                        if (consumerDeliveryCount < 5) Log.w(TAG, "Consumer: handler null, dropping packet")
                    } else {
                        handler.receiveAudioPacket(queued.frame)
                        consumerDeliveryCount++
                        if (consumerDeliveryCount <= 5 || consumerDeliveryCount % 100 == 0) {
                            Log.w(TAG, "Consumer delivered #$consumerDeliveryCount (${queued.frame.size} bytes)")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Consumer error: ${e.message}")
                }
            }
            Log.e(TAG, "Consumer coroutine exited!")
        }
    }

    // Network packet handler (set by PythonWrapperManager via AudioPacketHandler)
    @Volatile
    private var packetHandler: AudioPacketHandler? = null

    // Kotlin callback for incoming packets (set by LinkSource)
    @Volatile
    private var onPacketReceived: ((ByteArray) -> Unit)? = null

    // Kotlin callback for incoming signals (set by SignallingReceiver)
    @Volatile
    private var onSignalReceived: ((Int) -> Unit)? = null

    // ===== Outbound Methods (audio pipeline -> network) =====

    /**
     * Send encoded audio packet to the network transport.
     *
     * Called by Packetizer (RemoteSink) when encoded audio frame is ready.
     * Non-blocking: uses buffered channel to avoid blocking audio thread.
     *
     * **CRITICAL:** No Log.d() calls in this method - blocks audio thread.
     *
     * @param encodedFrame Encoded audio data (Opus/Codec2/Null bytes with codec header)
     */
    fun sendPacket(encodedFrame: ByteArray) {
        if (squelched.get()) return
        // Capture the current gate epoch so the consumer can invalidate this
        // packet if a squelch lands between enqueue and transport handoff.
        packetChannel.trySend(QueuedPacket(encodedFrame, gateEpoch.get()))
    }

    /**
     * Squelch (gate) outbound audio transmission.
     *
     * While squelched, [sendPacket] drops frames so nothing is transmitted on the
     * wire - this is the half-duplex PTT "not talking" state. Matches Python LXST
     * `Packetizer.squelch()` (frame dropped, no TX). Signalling is unaffected.
     *
     * Bumping [gateEpoch] additionally invalidates any packets already buffered in
     * [packetChannel] so the consumer drops them rather than draining a stale TX
     * backlog onto the wire.
     *
     * **CRITICAL:** No Log.d() - may be called from the audio thread.
     */
    fun squelch() {
        squelched.set(true)
        gateEpoch.incrementAndGet()
    }

    /**
     * Resume outbound audio transmission (clear the half-duplex squelch).
     *
     * Does not touch [gateEpoch] (it only advances on [squelch]), so this does not
     * invalidate packets captured during the open window.
     *
     * **CRITICAL:** No Log.d() - may be called from the audio thread.
     */
    fun unsquelch() {
        squelched.set(false)
    }

    /**
     * @return true while outbound audio is squelched (half-duplex not transmitting).
     */
    fun isSquelched(): Boolean = squelched.get()

    /**
     * Send signalling to the network transport.
     *
     * Called by SignallingReceiver when signal needs to be sent to remote peer.
     * Non-blocking: launches coroutine on [Dispatchers.IO] to avoid blocking audio thread.
     *
     * @param signal Signalling value (see Signalling constants: STATUS_BUSY, STATUS_RINGING, etc.)
     */
    fun sendSignal(signal: Int) {
        scope.launch {
            try {
                packetHandler?.receiveSignal(signal)
            } catch (_: Exception) {
                // Silent failure - signalling is fire-and-forget
            }
        }
    }

    // ===== Inbound Methods (network -> audio pipeline) =====

    // TEMP: Diagnostic counter for inbound packets
    @Volatile
    private var inboundPacketCount = 0

    /**
     * Deliver an inbound encoded packet from the network transport.
     *
     * Called by the transport layer when a packet arrives from the remote peer.
     * **MUST BE FAST** — caller may hold transport-level locks.
     *
     * Simply invokes the registered callback; no processing, no logging.
     * Decoding and mixing happen on the Kotlin audio thread via the callback.
     *
     * Periodic logging (every 100 packets) for diagnostics — negligible overhead.
     *
     * @param packetData Encoded packet data (codec header byte + encoded frame)
     */
    fun onInboundPacket(packetData: ByteArray) {
        inboundPacketCount++
        if (inboundPacketCount <= 5 || inboundPacketCount % 100 == 0) {
            Log.d(TAG, "Inbound #$inboundPacketCount (${packetData.size} bytes) cb=${onPacketReceived != null}")
        }
        onPacketReceived?.invoke(packetData)
    }

    /**
     * Deliver an inbound signal from the network transport.
     *
     * Called by the transport layer when a signal arrives from the remote peer.
     * **MUST BE FAST** — caller may hold transport-level locks.
     *
     * Note: Debug logging is acceptable here (unlike packet path) because signals
     * are infrequent (state transitions only, not continuous audio).
     *
     * @param signal Signalling value received from remote
     */
    fun onInboundSignal(signal: Int) {
        Log.d(TAG, "Inbound signal: 0x${signal.toString(16).padStart(2, '0')}")
        onSignalReceived?.invoke(signal)
    }

    // ===== Setup Methods =====

    /**
     * Set the network packet handler.
     *
     * Called by PythonWrapperManager after initializing the network handler.
     * The handler sends audio packets and signals to the remote peer.
     *
     * @param handler AudioPacketHandler implementation
     */
    fun setPacketHandler(handler: AudioPacketHandler) {
        packetHandler = handler
        Log.i(TAG, "Packet handler set")
    }

    /**
     * Set callback for incoming packets.
     *
     * Called by LinkSource (RemoteSource) to receive packets from remote peer.
     * The callback receives raw packet data (codec header byte + encoded frame).
     *
     * @param callback Function to invoke when packet received from Python
     */
    fun setPacketCallback(callback: (ByteArray) -> Unit) {
        onPacketReceived = callback
        Log.i(TAG, "Packet callback set")
    }

    /**
     * Set callback for incoming signals.
     *
     * Called by SignallingReceiver to receive signals from remote peer.
     *
     * @param callback Function to invoke when signal received from Python
     */
    fun setSignalCallback(callback: (Int) -> Unit) {
        onSignalReceived = callback
        Log.i(TAG, "Signal callback set")
    }

    // ===== Lifecycle =====

    /**
     * Check if Python handler is set.
     */
    fun isHandlerSet(): Boolean = packetHandler != null

    /**
     * Check if packet callback is registered.
     */
    fun hasPacketCallback(): Boolean = onPacketReceived != null

    /**
     * Check if signal callback is registered.
     */
    fun hasSignalCallback(): Boolean = onSignalReceived != null

    /**
     * Shutdown the network bridge.
     *
     * Cancels the coroutine scope and clears all references.
     * Called during app shutdown or when call ends.
     */
    fun shutdown() {
        Log.i(TAG, "Shutting down network bridge")
        squelched.set(false)
        gateEpoch.set(0)
        packetChannel.close()
        scope.cancel()
        packetHandler = null
        onPacketReceived = null
        onSignalReceived = null
    }
}
