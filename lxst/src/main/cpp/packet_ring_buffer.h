/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

#ifndef LXST_PACKET_RING_BUFFER_H
#define LXST_PACKET_RING_BUFFER_H

#include <atomic>
#include <cstdint>
#include <cstring>

/**
 * Lock-free Single-Producer Single-Consumer (SPSC) ring buffer for int16 audio.
 *
 * Producer: Kotlin thread (via JNI writeDecodedSamples)
 * Consumer: Oboe SCHED_FIFO audio callback thread
 *
 * Uses acquire/release memory ordering on read/write indices to ensure
 * correct visibility across threads without mutexes or spinlocks.
 *
 * The buffer stores raw int16 samples in a flat contiguous array.
 * Each "slot" holds one audio frame (variable size set at construction).
 */
class PacketRingBuffer {
public:
    /**
     * @param maxFrames   Maximum number of frames the buffer can hold
     * @param frameSamples Number of int16 samples per frame
     */
    PacketRingBuffer(int maxFrames, int frameSamples);
    ~PacketRingBuffer();

    // Non-copyable, non-movable (owns raw allocation)
    PacketRingBuffer(const PacketRingBuffer&) = delete;
    PacketRingBuffer& operator=(const PacketRingBuffer&) = delete;

    /**
     * Write one frame into the ring buffer (producer side).
     *
     * @param samples  Pointer to int16 samples
     * @param count    Number of samples (must equal frameSamples)
     * @return true if written, false if buffer is full
     */
    bool write(const int16_t* samples, int count);

    /**
     * Read one frame from the ring buffer (consumer side).
     *
     * @param dest   Destination buffer (must hold frameSamples int16s)
     * @param count  Number of samples to read (must equal frameSamples)
     * @return true if read, false if buffer is empty
     */
    bool read(int16_t* dest, int count);

    /** Number of frames available to read. */
    int availableFrames() const;

    /** Maximum number of frames the buffer can hold. */
    int capacity() const { return maxFrames_; }

    /** Number of int16 samples per frame. */
    int frameSamples() const { return frameSamples_; }

    /** Reset buffer to empty state. Not thread-safe — call only when idle. */
    void reset();

    /**
     * Discard frames to keep at most framesToKeep in the buffer.
     *
     * Advances readIndex_ without copying data. Safe to call from the
     * consumer thread (or when the consumer is stopped). The producer
     * only reads readIndex_ to check capacity, so an advanced read
     * index simply appears as "more space available".
     *
     * @param framesToKeep Maximum frames to retain (excess are discarded)
     */
    void drain(int framesToKeep);

private:
    const int maxFrames_;
    const int frameSamples_;
    int16_t* buffer_;  // Flat array: maxFrames * frameSamples

    // Atomic indices for lock-free SPSC protocol.
    // Only the producer writes writeIndex_; only the consumer writes readIndex_.
    std::atomic<int> writeIndex_{0};
    std::atomic<int> readIndex_{0};
};

#endif // LXST_PACKET_RING_BUFFER_H
