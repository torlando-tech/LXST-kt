/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "packet_ring_buffer.h"

PacketRingBuffer::PacketRingBuffer(int maxFrames, int frameSamples)
    : maxFrames_(maxFrames),
      frameSamples_(frameSamples),
      buffer_(new int16_t[maxFrames * frameSamples]) {
    std::memset(buffer_, 0, sizeof(int16_t) * maxFrames * frameSamples);
}

PacketRingBuffer::~PacketRingBuffer() {
    delete[] buffer_;
}

bool PacketRingBuffer::write(const int16_t* samples, int count) {
    if (count != frameSamples_) return false;

    int w = writeIndex_.load(std::memory_order_relaxed);
    int r = readIndex_.load(std::memory_order_acquire);

    int nextW = (w + 1) % maxFrames_;
    if (nextW == r) {
        // Buffer full — caller should handle (drop frame or overwrite oldest)
        return false;
    }

    std::memcpy(buffer_ + w * frameSamples_, samples, sizeof(int16_t) * frameSamples_);
    writeIndex_.store(nextW, std::memory_order_release);
    return true;
}

bool PacketRingBuffer::read(int16_t* dest, int count) {
    if (count != frameSamples_) return false;

    int r = readIndex_.load(std::memory_order_relaxed);
    int w = writeIndex_.load(std::memory_order_acquire);

    if (r == w) {
        // Buffer empty
        return false;
    }

    std::memcpy(dest, buffer_ + r * frameSamples_, sizeof(int16_t) * frameSamples_);
    readIndex_.store((r + 1) % maxFrames_, std::memory_order_release);
    return true;
}

int PacketRingBuffer::availableFrames() const {
    int w = writeIndex_.load(std::memory_order_acquire);
    int r = readIndex_.load(std::memory_order_acquire);
    int avail = w - r;
    if (avail < 0) avail += maxFrames_;
    return avail;
}

void PacketRingBuffer::reset() {
    writeIndex_.store(0, std::memory_order_relaxed);
    readIndex_.store(0, std::memory_order_relaxed);
}

void PacketRingBuffer::drain(int framesToKeep) {
    int avail = availableFrames();
    int toDrain = avail - framesToKeep;
    if (toDrain <= 0) return;

    int r = readIndex_.load(std::memory_order_relaxed);
    readIndex_.store((r + toDrain) % maxFrames_, std::memory_order_release);
}
