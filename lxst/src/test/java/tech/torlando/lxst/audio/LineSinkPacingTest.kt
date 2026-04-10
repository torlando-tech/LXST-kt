/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package tech.torlando.lxst.audio

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tech.torlando.lxst.core.AudioDevice

/**
 * Tests for LineSink's buffering, underrun recovery, and re-buffer behavior.
 *
 * Uses real wall-clock time with short frame periods (10ms at 16kHz).
 * MockK stubs writeAudio to capture timestamps without touching real AudioTrack.
 */
class LineSinkPacingTest {

    private companion object {
        const val SAMPLE_RATE_16K = 16_000
        const val MONO_CHANNELS = 1
        const val FRAME_SIZE_10MS_16K = 160
    }

    private val writeTimestampsNs = mutableListOf<Long>()
    private lateinit var mockBridge: AudioDevice

    @Before
    fun setup() {
        writeTimestampsNs.clear()
        mockBridge = mockk(relaxed = true)
        every { mockBridge.writeAudio(any()) } answers {
            writeTimestampsNs.add(System.nanoTime())
        }
    }

    @Test
    fun `startup drain removes excess burst frames`() {
        val sink = LineSink(mockBridge, autodigest = true, lowLatency = false)
        sink.configure(SAMPLE_RATE_16K, MONO_CHANNELS)
        val prebufferFrames = effectiveAutostartMin(FRAME_SIZE_10MS_16K, SAMPLE_RATE_16K)

        repeat(prebufferFrames + 5) {
            sink.handleFrame(FloatArray(FRAME_SIZE_10MS_16K))
        }
        Thread.sleep(300)

        sink.stop()
        sink.release()

        assertTrue(
            "Writes (${writeTimestampsNs.size}) should stay close to the prebuffer target $prebufferFrames after startup drain",
            writeTimestampsNs.size in (prebufferFrames - 2)..(prebufferFrames + 2),
        )
    }

    @Test
    fun `underrun recovery resets clock and paces correctly`() {
        val sink = LineSink(mockBridge, autodigest = true, lowLatency = false)
        sink.configure(SAMPLE_RATE_16K, MONO_CHANNELS)
        val prebufferFrames = effectiveAutostartMin(FRAME_SIZE_10MS_16K, SAMPLE_RATE_16K)

        repeat(prebufferFrames) { sink.handleFrame(FloatArray(FRAME_SIZE_10MS_16K)) }
        Thread.sleep(150)

        val preRecoveryCount = writeTimestampsNs.size
        repeat(55) {
            sink.handleFrame(FloatArray(FRAME_SIZE_10MS_16K))
            Thread.sleep(10)
        }
        Thread.sleep(500)

        sink.stop()
        sink.release()

        val recoveryWrites = writeTimestampsNs.drop(preRecoveryCount)
        if (recoveryWrites.size >= 3) {
            val spanMs = (recoveryWrites.last() - recoveryWrites.first()) / 1_000_000L
            assertTrue(
                "Recovery span ${spanMs}ms should be >=15ms (paced, not burst-drained)",
                spanMs >= 15,
            )
        }
    }

    @Test
    fun `brief underrun resumes immediately without rebuffer`() {
        val sink = LineSink(mockBridge, autodigest = true, lowLatency = false)
        sink.configure(SAMPLE_RATE_16K, MONO_CHANNELS)
        val prebufferFrames = effectiveAutostartMin(FRAME_SIZE_10MS_16K, SAMPLE_RATE_16K)

        repeat(prebufferFrames) { sink.handleFrame(FloatArray(FRAME_SIZE_10MS_16K)) }
        Thread.sleep(100)

        val preRecovery = writeTimestampsNs.size

        repeat(8) {
            sink.handleFrame(FloatArray(FRAME_SIZE_10MS_16K))
            Thread.sleep(12)
        }
        Thread.sleep(200)

        val afterRecovery = writeTimestampsNs.size
        val newWrites = afterRecovery - preRecovery

        assertTrue(
            "Brief underrun (~50ms) should not trigger re-buffer; expected >= 5 writes, got $newWrites",
            newWrites >= 5,
        )

        sink.stop()
        sink.release()
    }

    @Test
    fun `sustained underrun triggers rebuffer not immediate resume`() {
        val sink = LineSink(mockBridge, autodigest = true, lowLatency = false)
        sink.configure(SAMPLE_RATE_16K, MONO_CHANNELS)
        val prebufferFrames = effectiveAutostartMin(FRAME_SIZE_10MS_16K, SAMPLE_RATE_16K)

        repeat(prebufferFrames) { sink.handleFrame(FloatArray(FRAME_SIZE_10MS_16K)) }
        Thread.sleep(650)

        val postFirstBatch = writeTimestampsNs.size

        sink.handleFrame(FloatArray(FRAME_SIZE_10MS_16K))
        Thread.sleep(20)
        sink.handleFrame(FloatArray(FRAME_SIZE_10MS_16K))
        Thread.sleep(100)

        val afterTwoFrames = writeTimestampsNs.size
        assertTrue(
            "No writes should occur during re-buffer; got ${afterTwoFrames - postFirstBatch}",
            afterTwoFrames == postFirstBatch,
        )

        repeat(LineSink.REBUFFER_FRAMES) {
            sink.handleFrame(FloatArray(FRAME_SIZE_10MS_16K))
            Thread.sleep(10)
        }
        Thread.sleep(300)

        val afterRebuffer = writeTimestampsNs.size
        assertTrue(
            "Writes should resume after re-buffer; got ${afterRebuffer - postFirstBatch}",
            afterRebuffer - postFirstBatch >= LineSink.REBUFFER_FRAMES,
        )

        sink.stop()
        sink.release()
    }

    @Test
    fun `repeated sustained underruns each trigger rebuffer`() {
        val sink = LineSink(mockBridge, autodigest = true, lowLatency = false)
        sink.configure(SAMPLE_RATE_16K, MONO_CHANNELS)
        val prebufferFrames = effectiveAutostartMin(FRAME_SIZE_10MS_16K, SAMPLE_RATE_16K)

        repeat(prebufferFrames) { sink.handleFrame(FloatArray(FRAME_SIZE_10MS_16K)) }
        Thread.sleep(650)
        val afterCycle1 = writeTimestampsNs.size

        repeat(15) {
            sink.handleFrame(FloatArray(FRAME_SIZE_10MS_16K))
            Thread.sleep(10)
        }
        Thread.sleep(800)
        val afterCycle2 = writeTimestampsNs.size

        repeat(15) {
            sink.handleFrame(FloatArray(FRAME_SIZE_10MS_16K))
            Thread.sleep(10)
        }
        Thread.sleep(400)
        val afterCycle3 = writeTimestampsNs.size

        assertTrue(
            "Cycle 2 should write >= 10 frames (got ${afterCycle2 - afterCycle1})",
            afterCycle2 - afterCycle1 >= 10,
        )
        assertTrue(
            "Cycle 3 should write >= 10 frames (got ${afterCycle3 - afterCycle2})",
            afterCycle3 - afterCycle2 >= 10,
        )

        sink.stop()
        sink.release()
    }

    private fun effectiveMaxFrames(frameSize: Int, sampleRate: Int, channels: Int = MONO_CHANNELS): Int {
        val frameTimeMs = ((frameSize.toFloat() / (sampleRate * channels)) * 1000).toLong().coerceAtLeast(1L)
        return (LineSink.BUFFER_CAPACITY_MS / frameTimeMs)
            .toInt()
            .coerceIn(LineSink.MAX_FRAMES, LineSink.MAX_QUEUE_SLOTS)
    }

    private fun effectiveAutostartMin(frameSize: Int, sampleRate: Int, channels: Int = MONO_CHANNELS): Int {
        val frameTimeMs = ((frameSize.toFloat() / (sampleRate * channels)) * 1000).toLong().coerceAtLeast(1L)
        val maxFrames = effectiveMaxFrames(frameSize, sampleRate, channels)
        return (LineSink.PREBUFFER_MS / frameTimeMs)
            .toInt()
            .coerceIn(LineSink.AUTOSTART_MIN, maxFrames / 2)
    }
}
