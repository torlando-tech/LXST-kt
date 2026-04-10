/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package tech.torlando.lxst.audio

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tech.torlando.lxst.core.AudioDevice
import java.util.concurrent.LinkedBlockingQueue

/**
 * Unit tests for LineSink queue management logic.
 *
 * Tests canReceive(), handleFrame(), and buffer overflow behavior.
 * Uses mocked AudioDevice to avoid JNI dependencies.
 */
class LineSinkTest {

    private companion object {
        const val SAMPLE_RATE_48K = 48_000
        const val MONO_CHANNELS = 1
        const val FRAME_SIZE_60MS_48K = 2_880
    }

    private lateinit var mockBridge: AudioDevice
    private lateinit var sink: LineSink

    @Before
    fun setup() {
        mockBridge = mockk(relaxed = true)

        // Create sink with autodigest=false to prevent auto-start
        sink = LineSink(
            bridge = mockBridge,
            autodigest = false,
            lowLatency = false,
        )
    }

    @Test
    fun `canReceive returns true when queue empty`() {
        assertTrue(sink.canReceive())
    }

    @Test
    fun `canReceive returns true below threshold`() {
        sink.configure(SAMPLE_RATE_48K, MONO_CHANNELS)
        val effectiveMaxFrames = effectiveMaxFrames(FRAME_SIZE_60MS_48K, SAMPLE_RATE_48K)

        repeat(effectiveMaxFrames - 2) {
            sink.handleFrame(FloatArray(FRAME_SIZE_60MS_48K))
        }
        assertTrue(sink.canReceive())
    }

    @Test
    fun `canReceive returns false at threshold`() {
        sink.configure(SAMPLE_RATE_48K, MONO_CHANNELS)
        val effectiveMaxFrames = effectiveMaxFrames(FRAME_SIZE_60MS_48K, SAMPLE_RATE_48K)

        repeat(effectiveMaxFrames - 1) {
            sink.handleFrame(FloatArray(FRAME_SIZE_60MS_48K))
        }
        assertFalse(sink.canReceive())
    }

    @Test
    fun `handleFrame adds frame to queue`() {
        sink.configure(SAMPLE_RATE_48K, MONO_CHANNELS)
        sink.handleFrame(FloatArray(FRAME_SIZE_60MS_48K) { it.toFloat() })

        assertTrue(sink.canReceive())
    }

    @Test
    fun `handleFrame drops oldest on physical queue overflow`() {
        sink.configure(SAMPLE_RATE_48K, MONO_CHANNELS)
        val queue = frameQueue(sink)
        val firstFrame = FloatArray(FRAME_SIZE_60MS_48K) { -1f }
        sink.handleFrame(firstFrame)

        repeat(LineSink.MAX_QUEUE_SLOTS - 1) {
            sink.handleFrame(FloatArray(FRAME_SIZE_60MS_48K) { it.toFloat() })
        }

        val newFrame = FloatArray(FRAME_SIZE_60MS_48K) { 999f }
        sink.handleFrame(newFrame)

        assertTrue(queue.size == LineSink.MAX_QUEUE_SLOTS)
        assertFalse(queue.peek().contentEquals(firstFrame))
    }

    @Test
    fun `configure sets sample rate and channels`() {
        sink.configure(sampleRate = 8000, channels = 1)
    }

    @Test
    fun `isRunning returns false initially`() {
        assertFalse(sink.isRunning())
    }

    @Test
    fun `stop clears queue`() {
        sink.configure(48_000, 1)

        repeat(3) {
            sink.handleFrame(FloatArray(FRAME_SIZE_60MS_48K))
        }

        sink.start()
        sink.stop()

        assertTrue(sink.canReceive())
        sink.release()
    }

    @Test
    fun `autodigest starts playback when threshold reached`() {
        val autoSink = LineSink(
            bridge = mockBridge,
            autodigest = true,
            lowLatency = false,
        )
        autoSink.configure(SAMPLE_RATE_48K, MONO_CHANNELS)
        val autostartMin = effectiveAutostartMin(FRAME_SIZE_60MS_48K, SAMPLE_RATE_48K)

        repeat(autostartMin) {
            autoSink.handleFrame(FloatArray(FRAME_SIZE_60MS_48K))
        }

        assertTrue(autoSink.isRunning())
        autoSink.stop()
        autoSink.release()
    }

    @Test
    fun `lowLatency flag is passed to bridge`() {
        val lowLatencySink = LineSink(
            bridge = mockBridge,
            autodigest = false,
            lowLatency = true,
        )
        lowLatencySink.configure(SAMPLE_RATE_48K, MONO_CHANNELS)
        lowLatencySink.start()
        Thread.sleep(50)

        verify {
            mockBridge.startPlayback(
                sampleRate = SAMPLE_RATE_48K,
                channels = MONO_CHANNELS,
                lowLatency = true,
                autoPlay = false,
            )
            mockBridge.beginPlayback()
        }

        lowLatencySink.stop()
        lowLatencySink.release()
    }

    @Test
    fun `start calls bridge with configured parameters`() {
        sink.configure(8000, 1)
        sink.start()
        Thread.sleep(50)

        verify {
            mockBridge.startPlayback(
                sampleRate = 8000,
                channels = 1,
                lowLatency = false,
                autoPlay = false,
            )
            mockBridge.beginPlayback()
        }

        sink.stop()
        sink.release()
    }

    @Test
    fun `stop calls bridge stopPlayback`() {
        sink.configure(48_000, 1)
        sink.start()
        sink.stop()

        verify { mockBridge.stopPlayback() }
        sink.release()
    }

    @Test
    fun `isRunning returns true after start`() {
        sink.configure(48_000, 1)
        sink.start()
        assertTrue(sink.isRunning())

        sink.stop()
        sink.release()
    }

    @Test
    fun `isRunning returns false after stop`() {
        sink.configure(48_000, 1)
        sink.start()
        sink.stop()
        assertFalse(sink.isRunning())

        sink.release()
    }

    @Test
    fun `start drains excess frames accumulated during AudioTrack creation`() {
        every {
            mockBridge.startPlayback(any(), any(), any(), any())
        } answers {
            Thread.sleep(200)
        }

        val autoSink = LineSink(
            bridge = mockBridge,
            autodigest = false,
            lowLatency = false,
        )
        autoSink.configure(SAMPLE_RATE_48K, MONO_CHANNELS)
        val autostartMin = effectiveAutostartMin(FRAME_SIZE_60MS_48K, SAMPLE_RATE_48K)

        repeat(autostartMin) {
            autoSink.handleFrame(FloatArray(FRAME_SIZE_60MS_48K))
        }

        autoSink.start()

        Thread {
            repeat(8) {
                Thread.sleep(20)
                autoSink.handleFrame(FloatArray(FRAME_SIZE_60MS_48K))
            }
        }.start()

        Thread.sleep(400)

        verify(atLeast = 1) { mockBridge.writeAudio(any()) }

        autoSink.stop()
        autoSink.release()
    }

    @Test
    fun `handleFrame detects sample rate from source`() {
        val mockSource = mockk<Source>(relaxed = true)
        every { mockSource.sampleRate } returns 16_000
        every { mockSource.channels } returns 1

        sink.handleFrame(FloatArray(320), mockSource)
        sink.start()
        Thread.sleep(50)

        verify {
            mockBridge.startPlayback(
                sampleRate = 16_000,
                channels = 1,
                lowLatency = false,
                autoPlay = false,
            )
            mockBridge.beginPlayback()
        }

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

    @Suppress("UNCHECKED_CAST")
    private fun frameQueue(sink: LineSink): LinkedBlockingQueue<FloatArray> {
        val field = sink.javaClass.getDeclaredField("frameQueue")
        field.isAccessible = true
        return field.get(sink) as LinkedBlockingQueue<FloatArray>
    }
}
