/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package tech.torlando.lxst.audio

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.torlando.lxst.codec.Codec
import tech.torlando.lxst.codec.Null
import tech.torlando.lxst.core.PacketRouter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * RemoteSource that receives encoded audio frames from Python Reticulum.
 *
 * Matches Python LXST Network.py LinkSource (lines 98-145).
 * Receives packets via [PacketRouter] callback, decodes them,
 * and pushes decoded float32 frames to downstream [Sink].
 *
 * **Threading Model:**
 * - [onPacketReceived]: Called by Python via bridge callback (GIL held) - MUST BE FAST
 *   Just queues packet, no processing, no logging
 * - [processingLoop]: Runs on [Dispatchers.IO] coroutine, does actual decode + sink push
 *
 * **Codec Header Protocol:**
 * First byte of each packet indicates codec type:
 * - 0xFF = Null (raw int16 PCM)
 * - 0x00 = Raw (alias for Null in Python, but we map to Null for simplicity)
 * - 0x01 = Opus
 * - 0x02 = Codec2
 *
 * Dynamic codec switching is supported - remote can change codec mid-call.
 */
class LinkSource(
    private val bridge: PacketRouter,
    var sink: Sink? = null,
) : RemoteSource() {
    companion object {
        private const val TAG = "Columba:LinkSource"

        /** Maximum packets in queue before dropping oldest (backpressure) */
        const val MAX_PACKETS = 32

        /** Minimum prebuffer frames (floor for any profile). */
        const val MIN_PREBUFFER_FRAMES = 5

        /**
         * Target prebuffer time in milliseconds.
         *
         * Used by [computePrebufferFrames] to calculate the number of decoded
         * frames to accumulate before auto-starting the Oboe playback stream.
         * Must be large enough to absorb Reticulum network jitter (~100-150ms)
         * plus Oboe startup latency.
         */
        const val PREBUFFER_TARGET_MS = 300

        /**
         * Compute the prebuffer frame count for a given profile frame time.
         *
         * Returns at least [MIN_PREBUFFER_FRAMES] frames, targeting
         * [PREBUFFER_TARGET_MS] of buffered audio. For profiles with large
         * frames (MQ: 60ms → 5 frames = 300ms) this matches the old fixed
         * value. For low-latency profiles (ULL: 10ms → 30 frames = 300ms)
         * this provides adequate jitter absorption.
         */
        fun computePrebufferFrames(frameTimeMs: Int): Int = maxOf(MIN_PREBUFFER_FRAMES, PREBUFFER_TARGET_MS / frameTimeMs)
    }

    // RemoteSource properties
    override var sampleRate: Int = 48000
    override var channels: Int = 1

    // State
    private val shouldRun = AtomicBoolean(false)
    private var debugPacketCount = 0 // TEMP: diagnostic counter
    private val inboundCount = AtomicInteger(0) // Fast counter for GIL-held path

    /**
     * Codec for decoding received frames (Phase 2 path).
     *
     * Set by Telephone based on the active call profile. This ensures the decoder
     * uses the correct sample rate and channel configuration to match the remote
     * encoder. Without this, the decoder defaults to 8000 Hz and can't decode
     * frames encoded at 24000 Hz or 48000 Hz.
     *
     * Unused when [useNativeCodec] is true — decoding happens in native code.
     */
    @Volatile
    var codec: Codec = Null()

    /**
     * Phase 3: When true, bypass Kotlin codec decode and push encoded packets
     * directly to NativePlaybackEngine for native decode.
     */
    @Volatile
    var useNativeCodec: Boolean = false

    /**
     * Number of decoded frames to accumulate before auto-starting the Oboe stream.
     *
     * Set by Telephone based on the active profile's frame time via
     * [computePrebufferFrames]. Low-latency profiles (ULL: 10ms) need many
     * more frames (30) than standard profiles (MQ: 60ms → 5) to achieve
     * the same ~300ms of jitter absorption.
     */
    @Volatile
    var prebufferFrames: Int = MIN_PREBUFFER_FRAMES

    /**
     * Phase 3: When true, auto-start the native playback stream after
     * [prebufferFrames] decoded frames have accumulated in the ring buffer.
     *
     * Mirrors the Phase 2 pattern where OboeLineSink defers startStream() until
     * the buffer has enough data to prevent callback starvation.
     */
    @Volatile
    var deferPlaybackStart: Boolean = false
    private val playbackStarted = AtomicBoolean(false)
    private val packetQueue = ArrayDeque<ByteArray>(MAX_PACKETS)
    private val receiveLock = Any()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        bridge.setPacketCallback { packetData ->
            onPacketReceived(packetData)
        }
    }

    /**
     * Called by Python via [PacketRouter] when packet arrives.
     *
     * **CRITICAL:** Must be fast - Python GIL is held during this call.
     * Just queues the packet for processing. No decode, no logging.
     *
     * Implements backpressure by dropping oldest packet when queue is full.
     *
     * @param packetData Raw packet data (codec header byte + encoded frame)
     */
    fun onPacketReceived(packetData: ByteArray) {
        if (!shouldRun.get()) return
        inboundCount.incrementAndGet()

        synchronized(receiveLock) {
            // Drop oldest if full (backpressure)
            if (packetQueue.size >= MAX_PACKETS) {
                packetQueue.removeFirst()
            }
            packetQueue.addLast(packetData)
        }
    }

    /**
     * Process a single packet: strip header, decode, push to sink.
     *
     * The codec is pre-configured by Telephone based on the negotiated profile,
     * ensuring the decoder sample rate matches the encoder. The per-packet codec
     * header byte (first byte) is stripped but not used for codec selection —
     * both sides agree on the codec during signalling.
     *
     * Decode errors are caught and the frame is dropped. This prevents a single
     * corrupted packet from crashing the entire service process.
     *
     * @param data Packet data (codec header byte + encoded frame)
     */
    private fun processPacket(data: ByteArray) {
        if (data.size < 2) return // Need header + at least 1 byte of frame

        // Diagnostic logging
        debugPacketCount++
        if (debugPacketCount <= 5) {
            val header = data[0].toInt() and 0xFF
            val preview = data.take(8).joinToString(",") { "0x${(it.toInt() and 0xFF).toString(16).padStart(2, '0')}" }
            Log.w(TAG, "PKT#$debugPacketCount: size=${data.size} hdr=0x${header.toString(16).padStart(2, '0')} codec=$codec native=$useNativeCodec")
        } else if (debugPacketCount % 100 == 0) {
            val received = inboundCount.get()
            Log.d(TAG, "RX: decoded=$debugPacketCount received=$received dropped=${received - debugPacketCount}")
        }

        if (useNativeCodec) {
            // Phase 3: Send encoded data directly to native playback engine.
            // Skip header byte via offset parameter (no copyOfRange allocation).
            try {
                NativePlaybackEngine.writeEncodedPacket(data, 1, data.size - 1)

                // Auto-start playback stream once prebuffer has accumulated.
                // Mirrors Phase 2's OboeLineSink pattern: defer startStream() until
                // the ring buffer has enough data to prevent callback starvation.
                if (deferPlaybackStart && !playbackStarted.get()) {
                    val buffered = NativePlaybackEngine.getBufferedFrameCount()
                    if (buffered >= prebufferFrames) {
                        if (playbackStarted.compareAndSet(false, true)) {
                            val started = NativePlaybackEngine.startStream()
                            Log.i(TAG, "Auto-started native playback: prebuf=$buffered/$prebufferFrames, ok=$started")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Native decode error, dropping frame: ${e.message}")
            }
        } else {
            // Phase 2: Kotlin codec decode → float32 → Mixer → sink
            val currentSink = sink ?: return
            val frameData = data.copyOfRange(1, data.size)

            try {
                val decodedFrame = codec.decode(frameData)
                if (debugPacketCount <= 5) {
                    val maxAmp = decodedFrame.maxOrNull() ?: 0f
                    val minAmp = decodedFrame.minOrNull() ?: 0f
                    Log.w(TAG, "DEC#$debugPacketCount: samples=${decodedFrame.size} range=[$minAmp,$maxAmp]")
                }
                currentSink.handleFrame(decodedFrame, this)
            } catch (e: Exception) {
                Log.w(TAG, "Decode error, dropping frame: ${e.message}")
            }
        }
    }

    /**
     * Main processing loop running on [Dispatchers.IO].
     *
     * Continuously dequeues packets and processes them.
     * Brief delay when queue is empty to avoid busy-spinning.
     */
    private suspend fun processingLoop() {
        while (shouldRun.get()) {
            val packet: ByteArray?
            synchronized(receiveLock) {
                packet = packetQueue.removeFirstOrNull()
            }
            if (packet != null) {
                processPacket(packet)
            } else {
                delay(2) // Brief sleep when queue empty
            }
        }
    }

    /**
     * Start receiving and processing packets.
     *
     * Launches processing coroutine on [Dispatchers.IO].
     */
    override fun start() {
        if (shouldRun.getAndSet(true)) return
        scope.launch { processingLoop() }
    }

    /**
     * Stop receiving and processing packets.
     *
     * Clears packet queue to prevent stale data on restart.
     */
    override fun stop() {
        shouldRun.set(false)
        playbackStarted.set(false)
        synchronized(receiveLock) {
            packetQueue.clear()
        }
    }

    /**
     * Check if source is currently running.
     */
    override fun isRunning(): Boolean = shouldRun.get()

    /**
     * Shutdown and release resources.
     *
     * Cancels coroutine scope. Call when LinkSource is no longer needed.
     */
    fun shutdown() {
        stop()
        scope.cancel()
    }
}
