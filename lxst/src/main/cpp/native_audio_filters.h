/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

#ifndef LXST_NATIVE_AUDIO_FILTERS_H
#define LXST_NATIVE_AUDIO_FILTERS_H

#include <cstdint>
#include <memory>

/**
 * Native voice filter chain for LXST audio capture.
 *
 * C++ port of AudioFilters.kt — runs in the Oboe capture callback thread
 * (SCHED_FIFO) to avoid JNI overhead and Kotlin heap allocations on the
 * capture hot path.
 *
 * Filter order: HighPass (300Hz) → LowPass (3400Hz) → AGC
 *
 * Processes int16 samples in-place. Internally converts to float for
 * filter math and back to int16 on output.
 */
class VoiceFilterChain {
public:
    /**
     * @param channels      Number of audio channels (1=mono)
     * @param hpCutoff      High-pass cutoff frequency (Hz)
     * @param lpCutoff      Low-pass cutoff frequency (Hz)
     * @param agcTargetDb   AGC target level in dBFS
     * @param agcMaxGain    AGC maximum gain in dB
     */
    VoiceFilterChain(int channels, float hpCutoff, float lpCutoff,
                     float agcTargetDb, float agcMaxGain);
    ~VoiceFilterChain();

    // Non-copyable
    VoiceFilterChain(const VoiceFilterChain&) = delete;
    VoiceFilterChain& operator=(const VoiceFilterChain&) = delete;

    /**
     * Process audio samples through the filter chain (in-place).
     *
     * @param samples    int16 PCM samples (modified in-place)
     * @param numSamples Total number of samples (frames * channels)
     * @param sampleRate Sample rate in Hz (for coefficient calculation)
     */
    void process(int16_t* samples, int numSamples, int sampleRate);

private:
    // --- High-pass filter (first-order RC) ---
    struct HighPassState {
        std::unique_ptr<float[]> filterStates;
        std::unique_ptr<float[]> lastInputs;
        float alpha = 0;
        int sampleRate = 0;
    };

    // --- Low-pass filter (first-order RC) ---
    struct LowPassState {
        std::unique_ptr<float[]> filterStates;
        float alpha = 0;
        int sampleRate = 0;
    };

    // --- Automatic Gain Control ---
    struct AGCState {
        std::unique_ptr<float[]> currentGain;
        int holdCounter = 0;
        int sampleRate = 0;
        float attackCoeff = 0;
        float releaseCoeff = 0;
        int holdSamples = 0;
    };

    void applyHighPass(float* samples, int numFrames);
    void applyLowPass(float* samples, int numFrames);
    void applyAGC(float* samples, int numFrames);

    int channels_;
    float hpCutoff_;
    float lpCutoff_;
    float agcTargetDb_;
    float agcMaxGain_;

    HighPassState hp_;
    LowPassState lp_;
    AGCState agc_;

    std::unique_ptr<float[]> workBuffer_;
    int workBufferSize_ = 0;
};

#endif // LXST_NATIVE_AUDIO_FILTERS_H
