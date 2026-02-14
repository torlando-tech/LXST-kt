/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

#ifndef LXST_CODEC_WRAPPER_H
#define LXST_CODEC_WRAPPER_H

#include <cstdint>

// Forward declarations (avoid pulling full headers into every translation unit)
struct OpusEncoder;
struct OpusDecoder;
struct CODEC2;

/**
 * Unified C++ codec abstraction wrapping both libopus and libcodec2.
 *
 * Used by OboePlaybackEngine (decode) and OboeCaptureEngine (encode) to
 * perform codec operations directly in native code, eliminating JNI
 * crossings and Kotlin heap allocations on the audio hot path.
 *
 * Codec2 quirks handled natively:
 * - Multi-frame: loops floor(encodedLen / bytesPerFrame) times
 * - Mode header: first byte of encoded data; switch mode if different
 * - Mode↔library mapping: wire headers 0x00-0x06 ↔ library modes 8,5,4,3,2,1,0
 *
 * Opus quirks handled natively:
 * - Mono→stereo upmix: when encoder has channels=2 but capture is mono,
 *   duplicate each sample: stereo[2i]=stereo[2i+1]=mono[i]
 */

enum class CodecType { NONE = 0, OPUS = 1, CODEC2 = 2 };

class CodecWrapper {
public:
    CodecWrapper();
    ~CodecWrapper();

    // Non-copyable
    CodecWrapper(const CodecWrapper&) = delete;
    CodecWrapper& operator=(const CodecWrapper&) = delete;

    /**
     * Create an Opus encoder+decoder pair.
     *
     * @param sampleRate   Sample rate (8000, 12000, 24000, 48000)
     * @param channels     Number of channels (1=mono, 2=stereo)
     * @param application  OPUS_APPLICATION_VOIP or OPUS_APPLICATION_AUDIO
     * @param bitrate      Target bitrate in bps
     * @param complexity   Encoder complexity (0-10)
     * @return true on success
     */
    bool createOpus(int sampleRate, int channels, int application,
                    int bitrate, int complexity);

    /**
     * Create a Codec2 encoder+decoder.
     *
     * @param libraryMode  Codec2 library mode (0=3200, 1=2400, ..., 8=700C)
     * @return true on success
     */
    bool createCodec2(int libraryMode);

    /** Destroy the codec and release all resources. */
    void destroy();

    /**
     * Decode encoded bytes to PCM int16.
     *
     * Codec2: strips mode header byte, loops over sub-frames.
     * Opus: single decode call.
     *
     * @param encoded         Encoded data (Codec2: with mode header; Opus: raw)
     * @param encodedBytes    Length of encoded data
     * @param output          Output PCM int16 buffer
     * @param maxOutputSamples Maximum samples that fit in output buffer
     * @return Decoded sample count (total, including all channels), or -1 on error
     */
    int decode(const uint8_t* encoded, int encodedBytes,
               int16_t* output, int maxOutputSamples);

    /**
     * Encode PCM int16 to encoded bytes.
     *
     * Codec2: prepends mode header byte, loops over sub-frames.
     * Opus: single encode call. Handles mono→stereo upmix if needed.
     *
     * @param pcm             Input PCM int16 samples (mono from capture)
     * @param pcmSamples      Number of input samples
     * @param output          Output buffer for encoded data
     * @param maxOutputBytes  Maximum bytes that fit in output buffer
     * @return Encoded byte count, or -1 on error
     */
    int encode(const int16_t* pcm, int pcmSamples,
               uint8_t* output, int maxOutputBytes);

    CodecType type() const { return type_; }
    int channels() const { return channels_; }
    int sampleRate() const { return sampleRate_; }

private:
    CodecType type_ = CodecType::NONE;
    int channels_ = 1;
    int sampleRate_ = 0;

    // Opus
    OpusEncoder* opusEnc_ = nullptr;
    OpusDecoder* opusDec_ = nullptr;

    // Codec2
    struct CODEC2* codec2_ = nullptr;
    int c2SamplesPerFrame_ = 0;
    int c2BytesPerFrame_ = 0;
    uint8_t c2ModeHeader_ = 0;
    int c2LibraryMode_ = 0;

    // Codec2 mode header ↔ library mode mapping (matches Kotlin Codec2.kt)
    // Wire headers: 0x00=700C, 0x01=1200, 0x02=1300, 0x03=1400,
    //               0x04=1600, 0x05=2400, 0x06=3200
    // Library modes: 8=700C, 5=1200, 4=1300, 3=1400, 2=1600, 1=2400, 0=3200
    static int headerToLibraryMode(uint8_t header);
    static uint8_t libraryModeToHeader(int libraryMode);
};

#endif // LXST_CODEC_WRAPPER_H
