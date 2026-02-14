/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "encoded_ring_buffer.h"
#include <cstring>

EncodedRingBuffer::EncodedRingBuffer(int maxSlots, int maxBytesPerSlot)
    : maxSlots_(maxSlots),
      maxBytesPerSlot_(maxBytesPerSlot),
      slotSize_(static_cast<int>(sizeof(int32_t)) + maxBytesPerSlot),
      buffer_(new uint8_t[maxSlots * (static_cast<int>(sizeof(int32_t)) + maxBytesPerSlot)]) {
    std::memset(buffer_, 0, maxSlots * slotSize_);
}

EncodedRingBuffer::~EncodedRingBuffer() {
    delete[] buffer_;
}

bool EncodedRingBuffer::write(const uint8_t* data, int length) {
    if (length <= 0 || length > maxBytesPerSlot_) return false;

    int w = writeIndex_.load(std::memory_order_relaxed);
    int r = readIndex_.load(std::memory_order_acquire);

    int nextW = (w + 1) % maxSlots_;
    if (nextW == r) {
        return false;  // Buffer full
    }

    // Write length prefix + data into slot
    uint8_t* slot = buffer_ + w * slotSize_;
    std::memcpy(slot, &length, sizeof(int32_t));
    std::memcpy(slot + sizeof(int32_t), data, length);

    writeIndex_.store(nextW, std::memory_order_release);
    return true;
}

bool EncodedRingBuffer::read(uint8_t* dest, int maxLength, int* actualLength) {
    int r = readIndex_.load(std::memory_order_relaxed);
    int w = writeIndex_.load(std::memory_order_acquire);

    if (r == w) {
        return false;  // Buffer empty
    }

    // Read length prefix
    uint8_t* slot = buffer_ + r * slotSize_;
    int32_t length;
    std::memcpy(&length, slot, sizeof(int32_t));

    if (length > maxLength) {
        // Destination too small — skip this packet
        readIndex_.store((r + 1) % maxSlots_, std::memory_order_release);
        *actualLength = 0;
        return false;
    }

    // Copy data
    std::memcpy(dest, slot + sizeof(int32_t), length);
    *actualLength = length;

    readIndex_.store((r + 1) % maxSlots_, std::memory_order_release);
    return true;
}

int EncodedRingBuffer::availableSlots() const {
    int w = writeIndex_.load(std::memory_order_acquire);
    int r = readIndex_.load(std::memory_order_acquire);
    int avail = w - r;
    if (avail < 0) avail += maxSlots_;
    return avail;
}

void EncodedRingBuffer::reset() {
    writeIndex_.store(0, std::memory_order_relaxed);
    readIndex_.store(0, std::memory_order_relaxed);
}
