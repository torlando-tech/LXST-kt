/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

#ifndef LXST_SAMPLE_RATE_CONVERTER_H
#define LXST_SAMPLE_RATE_CONVERTER_H

#include <cstdint>
#include <cmath>

/**
 * Streaming mono linear-interpolation sample-rate converter.
 *
 * Port of the resample step the Python LXST codecs perform inside
 * `encode()` (Codecs/Codec2.py:66-69, Codecs/Opus.py:142-145): the mic
 * capture stays at the hardware rate and the codec resamples to its native
 * rate before encoding. The native C++ capture engine (Phase 3) previously
 * fed raw hardware-rate frames straight into the encoder, so a profile whose
 * native rate differs from the capture rate (e.g. LBW Codec2 @8k on a 24k
 * capture stream, or HQ Opus @48k on a 24k capture) encoded audio at the
 * wrong pitch (one octave low for a 2x rate mismatch).
 *
 * Streaming: feed Oboe bursts of arbitrary size and read back however many
 * output samples are ready. The fractional input position AND the previous
 * burst's last sample are carried across calls so interpolation has no
 * discontinuity at a burst boundary. Linear interpolation matches Python's
 * pydub `set_frame_rate` (used by `resample_bytes`).
 */
class SampleRateConverter {
public:
    SampleRateConverter() = default;

    /**
     * Configure the conversion ratio. Resets any buffered state.
     *
     * @param inputRate  Rate of the samples fed to process() (the Oboe
     *                   capture stream's actual rate).
     * @param outputRate Rate the samples are resampled to (the encoder's
     *                   native rate).
     */
    void configure(int inputRate, int outputRate) {
        if (inputRate <= 0 || outputRate <= 0) {
            enabled_ = false;
            return;
        }
        // How far the fractional input position advances per output sample.
        invRatio_ = static_cast<float>(inputRate) / static_cast<float>(outputRate);
        enabled_ = (inputRate != outputRate);
        reset();
    }

    /** True when samples actually get resampled (input and output differ). */
    bool enabled() const { return enabled_; }

    /** Clear carried position + previous sample (e.g. on a capture restart). */
    void reset() {
        pos_ = 0.0f;
        prev_ = 0;
    }

    /**
     * Feed a burst of input samples (capture rate) and write resampled output
     * (encoder rate) to out.
     *
     * @param in      Input samples at the configured input rate.
     * @param inLen   Number of input samples.
     * @param out     Output buffer (encoder rate).
     * @param outCap  Capacity of out in samples.
     * @return Number of output samples written, or -1 if outCap was too
     *         small (pos_/prev_ are left unchanged on that path; caller must
     *         pass a large-enough buffer).
     */
    int process(const int16_t* in, int inLen, int16_t* out, int outCap) {
        if (inLen <= 0) return 0;

        if (!enabled_) {
            int n = (inLen < outCap) ? inLen : outCap;
            for (int i = 0; i < n; i++) out[i] = in[i];
            return (inLen > outCap) ? -1 : n;
        }

        float pos = pos_;  // fractional index into this input burst
        int outN = 0;

        while (outN < outCap) {
            int i0 = static_cast<int>(std::floorf(pos));
            // Need the sample at i0 (or the carried prev when i0 == -1) and at
            // i0+1. i0+1 must be a real index in this burst.
            if (i0 < -1 || (i0 + 1) >= inLen) break;
            float frac = pos - static_cast<float>(i0);
            int16_t lo = (i0 == -1) ? prev_ : in[i0];
            int16_t hi = in[i0 + 1];
            int32_t sample = static_cast<int32_t>(
                static_cast<float>(lo) + (static_cast<float>(hi) - static_cast<float>(lo)) * frac + 0.5f);
            if (sample > 32767) sample = 32767;
            if (sample < -32768) sample = -32768;
            out[outN++] = static_cast<int16_t>(sample);
            pos += invRatio_;
            if (pos >= static_cast<float>(inLen - 1)) break;
        }

        pos_ = pos - static_cast<float>(inLen);  // carry remainder into next burst
        prev_ = in[inLen - 1];                   // last sample of this burst
        return outN;
    }

private:
    bool enabled_ = false;
    float invRatio_ = 1.0f;  // input-position advance per output sample
    float pos_ = 0.0f;       // fractional input position (relative to current burst)
    int16_t prev_ = 0;       // last sample of the previous burst (cross-boundary interp)
};

#endif  // LXST_SAMPLE_RATE_CONVERTER_H
