/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "codec_wrapper.h"
#include "include/opus/opus.h"
#include "include/codec2/codec2.h"
#include <android/log.h>
#include <cstring>

#define LOG_TAG "LXST:CodecWrapper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

CodecWrapper::CodecWrapper() = default;

CodecWrapper::~CodecWrapper() {
    destroy();
}

bool CodecWrapper::createOpus(int sampleRate, int channels, int application,
                              int bitrate, int complexity) {
    destroy();

    int encErr, decErr;

    opusEnc_ = opus_encoder_create(sampleRate, channels, application, &encErr);
    if (encErr != OPUS_OK || !opusEnc_) {
        LOGE("Opus encoder create failed: %s", opus_strerror(encErr));
        return false;
    }

    opus_encoder_ctl(opusEnc_, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(opusEnc_, OPUS_SET_COMPLEXITY(complexity));

    opusDec_ = opus_decoder_create(sampleRate, channels, &decErr);
    if (decErr != OPUS_OK || !opusDec_) {
        LOGE("Opus decoder create failed: %s", opus_strerror(decErr));
        opus_encoder_destroy(opusEnc_);
        opusEnc_ = nullptr;
        return false;
    }

    type_ = CodecType::OPUS;
    channels_ = channels;
    sampleRate_ = sampleRate;

    LOGI("Opus created: rate=%d ch=%d bitrate=%d complexity=%d app=%d",
         sampleRate, channels, bitrate, complexity, application);
    return true;
}

bool CodecWrapper::createCodec2(int libraryMode) {
    destroy();

    codec2_ = codec2_create(libraryMode);
    if (!codec2_) {
        LOGE("Codec2 create failed for library mode %d", libraryMode);
        return false;
    }

    c2LibraryMode_ = libraryMode;
    c2SamplesPerFrame_ = codec2_samples_per_frame(codec2_);
    c2BytesPerFrame_ = codec2_bytes_per_frame(codec2_);
    c2ModeHeader_ = libraryModeToHeader(libraryMode);

    type_ = CodecType::CODEC2;
    channels_ = 1;
    sampleRate_ = 8000;  // Codec2 is always 8kHz

    LOGI("Codec2 created: libMode=%d header=0x%02x samplesPerFrame=%d bytesPerFrame=%d",
         libraryMode, c2ModeHeader_, c2SamplesPerFrame_, c2BytesPerFrame_);
    return true;
}

void CodecWrapper::destroy() {
    if (opusEnc_) { opus_encoder_destroy(opusEnc_); opusEnc_ = nullptr; }
    if (opusDec_) { opus_decoder_destroy(opusDec_); opusDec_ = nullptr; }
    if (codec2_)  { codec2_destroy(codec2_); codec2_ = nullptr; }
    type_ = CodecType::NONE;
    channels_ = 1;
    sampleRate_ = 0;
}

int CodecWrapper::decode(const uint8_t* encoded, int encodedBytes,
                         int16_t* output, int maxOutputSamples) {
    if (type_ == CodecType::OPUS) {
        if (!opusDec_) return -1;

        // Max samples per channel for decode
        int maxPerChannel = maxOutputSamples / channels_;
        int decoded = opus_decode(opusDec_,
                                  encoded, encodedBytes,
                                  output, maxPerChannel, 0);
        if (decoded < 0) {
            LOGW("Opus decode error: %s", opus_strerror(decoded));
            return -1;
        }
        return decoded * channels_;  // Return total samples

    } else if (type_ == CodecType::CODEC2) {
        if (!codec2_ || encodedBytes < 1) return -1;

        // First byte is mode header — check if mode changed
        uint8_t header = encoded[0];
        if (header != c2ModeHeader_) {
            int newMode = headerToLibraryMode(header);
            if (newMode >= 0) {
                LOGI("Codec2 mode switch: header 0x%02x → libMode %d", header, newMode);
                codec2_destroy(codec2_);
                codec2_ = codec2_create(newMode);
                if (!codec2_) {
                    LOGE("Codec2 mode switch failed");
                    return -1;
                }
                c2LibraryMode_ = newMode;
                c2SamplesPerFrame_ = codec2_samples_per_frame(codec2_);
                c2BytesPerFrame_ = codec2_bytes_per_frame(codec2_);
                c2ModeHeader_ = header;
            } else {
                LOGW("Unknown Codec2 header: 0x%02x", header);
                return -1;
            }
        }

        // Skip header byte, decode remaining sub-frames
        const uint8_t* data = encoded + 1;
        int dataLen = encodedBytes - 1;
        int numFrames = dataLen / c2BytesPerFrame_;
        int totalSamples = numFrames * c2SamplesPerFrame_;

        if (totalSamples > maxOutputSamples) {
            LOGW("Codec2 decode: output buffer too small (%d > %d)",
                 totalSamples, maxOutputSamples);
            return -1;
        }

        for (int i = 0; i < numFrames; i++) {
            codec2_decode(codec2_,
                          output + i * c2SamplesPerFrame_,
                          data + i * c2BytesPerFrame_);
        }

        return totalSamples;

    }

    return -1;  // NONE
}

int CodecWrapper::encode(const int16_t* pcm, int pcmSamples,
                         uint8_t* output, int maxOutputBytes) {
    if (type_ == CodecType::OPUS) {
        if (!opusEnc_) return -1;

        // Handle mono→stereo upmix for stereo profiles (e.g., SHQ)
        // Capture is always mono; if codec expects stereo, duplicate samples
        const int16_t* encodeInput = pcm;
        int encodeSamples = pcmSamples;

        // Temporary stack buffer for stereo upmix (max 60ms * 48kHz * 2ch = 5760)
        int16_t stereoBuf[5760];

        if (channels_ == 2 && pcmSamples <= 2880) {
            // Input is mono, upmix: [s0,s1,...] → [s0,s0,s1,s1,...]
            for (int i = 0; i < pcmSamples; i++) {
                stereoBuf[2 * i] = pcm[i];
                stereoBuf[2 * i + 1] = pcm[i];
            }
            encodeInput = stereoBuf;
            encodeSamples = pcmSamples * 2;
        }

        int framesPerChannel = encodeSamples / channels_;
        int encoded = opus_encode(opusEnc_, encodeInput, framesPerChannel,
                                  output, maxOutputBytes);
        if (encoded < 0) {
            LOGW("Opus encode error: %s", opus_strerror(encoded));
            return -1;
        }
        return encoded;

    } else if (type_ == CodecType::CODEC2) {
        if (!codec2_) return -1;

        int numFrames = pcmSamples / c2SamplesPerFrame_;
        int encodedSize = 1 + numFrames * c2BytesPerFrame_;  // header + data

        if (encodedSize > maxOutputBytes) {
            LOGW("Codec2 encode: output buffer too small (%d > %d)",
                 encodedSize, maxOutputBytes);
            return -1;
        }

        // Prepend mode header byte
        output[0] = c2ModeHeader_;

        for (int i = 0; i < numFrames; i++) {
            codec2_encode(codec2_,
                          output + 1 + i * c2BytesPerFrame_,
                          const_cast<int16_t*>(pcm + i * c2SamplesPerFrame_));
        }

        return encodedSize;
    }

    return -1;  // NONE
}

// --- Static helpers: Codec2 mode header ↔ library mode mapping ---
// Wire format (matches Python LXST and Kotlin Codec2.kt):
//   header 0x00 = 700C  → library mode 8
//   header 0x01 = 1200  → library mode 5
//   header 0x02 = 1300  → library mode 4
//   header 0x03 = 1400  → library mode 3
//   header 0x04 = 1600  → library mode 2
//   header 0x05 = 2400  → library mode 1
//   header 0x06 = 3200  → library mode 0

int CodecWrapper::headerToLibraryMode(uint8_t header) {
    switch (header) {
        case 0x00: return 8;  // 700C
        case 0x01: return 5;  // 1200
        case 0x02: return 4;  // 1300
        case 0x03: return 3;  // 1400
        case 0x04: return 2;  // 1600
        case 0x05: return 1;  // 2400
        case 0x06: return 0;  // 3200
        default:   return -1; // Unknown
    }
}

uint8_t CodecWrapper::libraryModeToHeader(int libraryMode) {
    switch (libraryMode) {
        case 8:  return 0x00;  // 700C
        case 5:  return 0x01;  // 1200
        case 4:  return 0x02;  // 1300
        case 3:  return 0x03;  // 1400
        case 2:  return 0x04;  // 1600
        case 1:  return 0x05;  // 2400
        case 0:  return 0x06;  // 3200
        default: return 0xFF;  // Unknown
    }
}
