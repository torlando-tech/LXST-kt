/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "native_audio_filters.h"
#include <cmath>
#include <algorithm>

// Matches AudioFilters.kt constants
static constexpr float AGC_ATTACK_TIME = 0.0001f;
static constexpr float AGC_RELEASE_TIME = 0.002f;
static constexpr float AGC_HOLD_TIME = 0.001f;
static constexpr float AGC_TRIGGER_LEVEL = 0.003f;
static constexpr float AGC_PEAK_LIMIT = 0.75f;
static constexpr int   AGC_BLOCK_TARGET = 10;

VoiceFilterChain::VoiceFilterChain(int channels, float hpCutoff, float lpCutoff,
                                   float agcTargetDb, float agcMaxGain)
    : channels_(channels),
      hpCutoff_(hpCutoff),
      lpCutoff_(lpCutoff),
      agcTargetDb_(agcTargetDb),
      agcMaxGain_(agcMaxGain) {

    hp_.filterStates = std::make_unique<float[]>(channels);
    hp_.lastInputs = std::make_unique<float[]>(channels);
    lp_.filterStates = std::make_unique<float[]>(channels);
    agc_.currentGain = std::make_unique<float[]>(channels);

    for (int ch = 0; ch < channels; ++ch) {
        hp_.filterStates[ch] = 0.0f;
        hp_.lastInputs[ch] = 0.0f;
        lp_.filterStates[ch] = 0.0f;
        agc_.currentGain[ch] = 1.0f;
    }
}

VoiceFilterChain::~VoiceFilterChain() = default;

void VoiceFilterChain::process(int16_t* samples, int numSamples, int sampleRate) {
    if (numSamples <= 0) return;

    int numFrames = numSamples / channels_;

    // Ensure work buffer is large enough
    if (workBufferSize_ < numSamples) {
        workBuffer_ = std::make_unique<float[]>(numSamples);
        workBufferSize_ = numSamples;
    }

    // Convert int16 → float [-1.0, 1.0]
    for (int i = 0; i < numSamples; ++i) {
        workBuffer_[i] = samples[i] / 32768.0f;
    }

    // Update sample rate for coefficient recalculation
    if (hp_.sampleRate != sampleRate) {
        hp_.sampleRate = sampleRate;
        float dt = 1.0f / sampleRate;
        float rc = 1.0f / (2.0f * static_cast<float>(M_PI) * hpCutoff_);
        hp_.alpha = rc / (rc + dt);
    }
    if (lp_.sampleRate != sampleRate) {
        lp_.sampleRate = sampleRate;
        float dt = 1.0f / sampleRate;
        float rc = 1.0f / (2.0f * static_cast<float>(M_PI) * lpCutoff_);
        lp_.alpha = dt / (rc + dt);
    }
    if (agc_.sampleRate != sampleRate) {
        agc_.sampleRate = sampleRate;
        agc_.attackCoeff = 1.0f - std::exp(-1.0f / (AGC_ATTACK_TIME * sampleRate));
        agc_.releaseCoeff = 1.0f - std::exp(-1.0f / (AGC_RELEASE_TIME * sampleRate));
        agc_.holdSamples = static_cast<int>(AGC_HOLD_TIME * sampleRate);
    }

    // Apply filter chain: HPF → LPF → AGC
    applyHighPass(workBuffer_.get(), numFrames);
    applyLowPass(workBuffer_.get(), numFrames);
    applyAGC(workBuffer_.get(), numFrames);

    // Convert float → int16 with clipping
    for (int i = 0; i < numSamples; ++i) {
        float clamped = std::max(-1.0f, std::min(1.0f, workBuffer_[i]));
        samples[i] = static_cast<int16_t>(clamped * 32767.0f);
    }
}

// --- High-pass filter (matches AudioFilters.kt applyHighPass) ---

void VoiceFilterChain::applyHighPass(float* samples, int numFrames) {
    float alpha = hp_.alpha;

    // Process first sample of each channel
    for (int ch = 0; ch < channels_; ++ch) {
        float inputDiff = samples[ch] - hp_.lastInputs[ch];
        samples[ch] = alpha * (hp_.filterStates[ch] + inputDiff);
    }

    // Process remaining samples
    for (int i = 1; i < numFrames; ++i) {
        for (int ch = 0; ch < channels_; ++ch) {
            int idx = i * channels_ + ch;
            int prevIdx = (i - 1) * channels_ + ch;
            float inputDiff = samples[idx] - samples[prevIdx];
            samples[idx] = alpha * (samples[prevIdx] + inputDiff);
        }
    }

    // Save state for next frame
    for (int ch = 0; ch < channels_; ++ch) {
        int lastIdx = (numFrames - 1) * channels_ + ch;
        hp_.filterStates[ch] = samples[lastIdx];
        hp_.lastInputs[ch] = samples[lastIdx];
    }
}

// --- Low-pass filter (matches AudioFilters.kt applyLowPass) ---

void VoiceFilterChain::applyLowPass(float* samples, int numFrames) {
    float alpha = lp_.alpha;
    float oneMinusAlpha = 1.0f - alpha;

    // Process first sample of each channel
    for (int ch = 0; ch < channels_; ++ch) {
        samples[ch] = alpha * samples[ch] + oneMinusAlpha * lp_.filterStates[ch];
    }

    // Process remaining samples
    for (int i = 1; i < numFrames; ++i) {
        for (int ch = 0; ch < channels_; ++ch) {
            int idx = i * channels_ + ch;
            int prevIdx = (i - 1) * channels_ + ch;
            samples[idx] = alpha * samples[idx] + oneMinusAlpha * samples[prevIdx];
        }
    }

    // Save state for next frame
    for (int ch = 0; ch < channels_; ++ch) {
        int lastIdx = (numFrames - 1) * channels_ + ch;
        lp_.filterStates[ch] = samples[lastIdx];
    }
}

// --- AGC (matches AudioFilters.kt applyAGC) ---

void VoiceFilterChain::applyAGC(float* samples, int numFrames) {
    float targetLinear = std::pow(10.0f, agcTargetDb_ / 10.0f);
    float maxGainLinear = std::pow(10.0f, agcMaxGain_ / 10.0f);

    int blockSize = std::max(1, numFrames / AGC_BLOCK_TARGET);

    for (int block = 0; block < AGC_BLOCK_TARGET; ++block) {
        int blockStart = block * blockSize;
        int blockEnd = (block == AGC_BLOCK_TARGET - 1) ? numFrames : (block + 1) * blockSize;
        if (blockEnd > numFrames) blockEnd = numFrames;

        int blockSamples = blockEnd - blockStart;
        if (blockSamples <= 0) continue;

        for (int ch = 0; ch < channels_; ++ch) {
            // Calculate RMS for this block
            float sumSquares = 0.0f;
            for (int i = blockStart; i < blockEnd; ++i) {
                int idx = i * channels_ + ch;
                sumSquares += samples[idx] * samples[idx];
            }
            float rms = std::sqrt(sumSquares / blockSamples);

            // Calculate target gain
            float targetGain;
            if (rms > 1e-9f && rms > AGC_TRIGGER_LEVEL) {
                targetGain = std::min(targetLinear / rms, maxGainLinear);
            } else {
                targetGain = agc_.currentGain[ch];
            }

            // Smooth gain changes
            if (targetGain < agc_.currentGain[ch]) {
                // Attack: reduce gain quickly
                agc_.currentGain[ch] = agc_.attackCoeff * targetGain +
                    (1.0f - agc_.attackCoeff) * agc_.currentGain[ch];
                agc_.holdCounter = agc_.holdSamples;
            } else {
                // Release: increase gain slowly (with hold)
                if (agc_.holdCounter > 0) {
                    agc_.holdCounter -= blockSamples;
                } else {
                    agc_.currentGain[ch] = agc_.releaseCoeff * targetGain +
                        (1.0f - agc_.releaseCoeff) * agc_.currentGain[ch];
                }
            }

            // Apply gain to this block
            for (int i = blockStart; i < blockEnd; ++i) {
                int idx = i * channels_ + ch;
                samples[idx] *= agc_.currentGain[ch];
            }
        }
    }

    // Peak limiting to prevent clipping
    for (int ch = 0; ch < channels_; ++ch) {
        float peak = 0.0f;
        for (int i = 0; i < numFrames; ++i) {
            int idx = i * channels_ + ch;
            float absVal = std::fabs(samples[idx]);
            if (absVal > peak) peak = absVal;
        }

        if (peak > AGC_PEAK_LIMIT) {
            float scale = AGC_PEAK_LIMIT / peak;
            for (int i = 0; i < numFrames; ++i) {
                int idx = i * channels_ + ch;
                samples[idx] *= scale;
            }
        }
    }
}
