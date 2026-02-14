/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

#ifndef LXST_ENCODED_RING_BUFFER_H
#define LXST_ENCODED_RING_BUFFER_H

#include <atomic>
#include <cstdint>

/**
 * Lock-free SPSC ring buffer for variable-length encoded audio packets.
 *
 * Used by OboeCaptureEngine to pass encoded packets from the Oboe callback
 * (producer, SCHED_FIFO) to the Kotlin consumer (JNI readEncodedPacket).
 *
 * Each slot has a fixed maximum size but tracks actual length. This wastes
 * some memory per slot but keeps the lock-free protocol identical to
 * PacketRingBuffer (same SPSC acquire/release pattern).
 *
 * Slot layout (flat array):
 *   [int32 length][uint8 data[maxBytesPerSlot]] × maxSlots
 */
class EncodedRingBuffer {
public:
    /**
     * @param maxSlots        Maximum number of packets the buffer can hold
     * @param maxBytesPerSlot Maximum encoded packet size per slot
     */
    EncodedRingBuffer(int maxSlots, int maxBytesPerSlot);
    ~EncodedRingBuffer();

    // Non-copyable
    EncodedRingBuffer(const EncodedRingBuffer&) = delete;
    EncodedRingBuffer& operator=(const EncodedRingBuffer&) = delete;

    /**
     * Write an encoded packet into the next available slot.
     *
     * @param data    Encoded packet bytes
     * @param length  Actual packet length (must be <= maxBytesPerSlot)
     * @return true if written, false if buffer full or length exceeds slot size
     */
    bool write(const uint8_t* data, int length);

    /**
     * Read the next encoded packet from the buffer.
     *
     * @param dest         Destination buffer
     * @param maxLength    Size of destination buffer
     * @param actualLength [out] Actual number of bytes read
     * @return true if a packet was read, false if buffer empty
     */
    bool read(uint8_t* dest, int maxLength, int* actualLength);

    /** Number of packets available to read. */
    int availableSlots() const;

    /** Reset buffer to empty state. Not thread-safe — call only when idle. */
    void reset();

private:
    const int maxSlots_;
    const int maxBytesPerSlot_;
    const int slotSize_;  // sizeof(int32_t) + maxBytesPerSlot_

    uint8_t* buffer_;  // Flat: maxSlots * slotSize

    std::atomic<int> writeIndex_{0};
    std::atomic<int> readIndex_{0};
};

#endif // LXST_ENCODED_RING_BUFFER_H
