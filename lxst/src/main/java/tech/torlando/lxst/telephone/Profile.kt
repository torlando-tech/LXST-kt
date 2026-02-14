/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package tech.torlando.lxst.telephone

import tech.torlando.lxst.audio.Packetizer
import tech.torlando.lxst.codec.Codec
import tech.torlando.lxst.codec.Codec2
import tech.torlando.lxst.codec.NativeCodec2
import tech.torlando.lxst.codec.NativeOpus
import tech.torlando.lxst.codec.Opus

/**
 * Parameters for configuring a native C++ codec via JNI.
 *
 * Used by [Profile.nativeEncodeParams] and [Profile.nativeDecodeParams] to pass
 * codec configuration to C++ CodecWrapper through NativePlaybackEngine/NativeCaptureEngine.
 *
 * @param codecType      Codec type (1=Opus, 2=Codec2) matching C++ CodecType enum
 * @param sampleRate     Audio sample rate in Hz
 * @param channels       Number of audio channels (1=mono, 2=stereo)
 * @param opusApplication Opus application type (VOIP/AUDIO/RESTRICTED_LOWDELAY), 0 if Codec2
 * @param opusBitrate    Opus target bitrate in bps, 0 if Codec2
 * @param opusComplexity Opus encoder complexity (0-10), 10 if Codec2
 * @param codec2LibraryMode Codec2 library mode constant, 0 if Opus
 * @param codecHeaderByte Wire protocol codec header byte for Packetizer
 */
data class NativeCodecParams(
    val codecType: Int,
    val sampleRate: Int,
    val channels: Int,
    val opusApplication: Int = 0,
    val opusBitrate: Int = 0,
    val opusComplexity: Int = 10,
    val codec2LibraryMode: Int = 0,
    val codecHeaderByte: Byte = 0x00,
)

/**
 * Quality profile definitions for LXST telephony.
 *
 * Matches Python LXST Telephony.py Profiles class exactly for wire compatibility.
 * Profile IDs are used in signalling and must match Python:
 * - ULBW=0x10, VLBW=0x20, LBW=0x30 (Codec2)
 * - MQ=0x40, HQ=0x50, SHQ=0x60 (Opus standard)
 * - ULL=0x70, LL=0x80 (Opus low-latency)
 *
 * Each profile encapsulates codec configuration and frame timing parameters.
 * Profile.createCodec() returns a properly configured codec instance.
 */
sealed class Profile(
    /** Profile ID byte (matches Python LXST) */
    val id: Int,
    /** Human-readable profile name */
    val name: String,
    /** Short abbreviation for UI display */
    val abbreviation: String,
    /** Target frame time in milliseconds */
    val frameTimeMs: Int,
) {
    /**
     * Create a codec instance configured for this profile.
     *
     * @return Codec instance (Opus or Codec2) configured for this profile
     */
    abstract fun createCodec(): Codec

    /**
     * Create a codec configured for decoding received audio.
     *
     * For Opus profiles, the decoder outputs at 48000 Hz to match the
     * AudioTrack native output rate, regardless of the encoder's sample rate.
     * Opus decoders resample internally (RFC 6716 §4.3).
     *
     * For Codec2 profiles, uses the native codec rate.
     *
     * @return Codec instance configured for decode at the output sample rate
     */
    open fun createDecodeCodec(): Codec = createCodec()

    /**
     * Native codec parameters for Phase 3 native codec integration.
     *
     * Returns parameters needed by C++ CodecWrapper to create an encoder
     * or decoder that matches this profile's configuration.
     *
     * @return NativeCodecParams for the encode side
     */
    abstract fun nativeEncodeParams(): NativeCodecParams

    /**
     * Native codec parameters for the decode side.
     *
     * Profiles where encode and decode use different configurations
     * (e.g., MQ encodes at 24kHz but decodes at 48kHz) override this.
     *
     * @return NativeCodecParams for the decode side
     */
    open fun nativeDecodeParams(): NativeCodecParams = nativeEncodeParams()

    // ====== Codec2 Profiles (Low Bandwidth) ======

    /** Ultra Low Bandwidth - Codec2 700C (700 bps) */
    data object ULBW : Profile(0x10, "Ultra Low Bandwidth", "ULBW", 400) {
        override fun createCodec(): Codec = Codec2(mode = Codec2.CODEC2_700C)

        override fun nativeEncodeParams() =
            NativeCodecParams(
                codecType = CODEC_TYPE_CODEC2,
                sampleRate = 8000,
                channels = 1,
                codec2LibraryMode = NativeCodec2.MODE_700C,
                codecHeaderByte = Packetizer.CODEC_CODEC2,
            )
    }

    /** Very Low Bandwidth - Codec2 1600 (1600 bps) */
    data object VLBW : Profile(0x20, "Very Low Bandwidth", "VLBW", 320) {
        override fun createCodec(): Codec = Codec2(mode = Codec2.CODEC2_1600)

        override fun nativeEncodeParams() =
            NativeCodecParams(
                codecType = CODEC_TYPE_CODEC2,
                sampleRate = 8000,
                channels = 1,
                codec2LibraryMode = NativeCodec2.MODE_1600,
                codecHeaderByte = Packetizer.CODEC_CODEC2,
            )
    }

    /** Low Bandwidth - Codec2 3200 (3200 bps) */
    data object LBW : Profile(0x30, "Low Bandwidth", "LBW", 200) {
        override fun createCodec(): Codec = Codec2(mode = Codec2.CODEC2_3200)

        override fun nativeEncodeParams() =
            NativeCodecParams(
                codecType = CODEC_TYPE_CODEC2,
                sampleRate = 8000,
                channels = 1,
                codec2LibraryMode = NativeCodec2.MODE_3200,
                codecHeaderByte = Packetizer.CODEC_CODEC2,
            )
    }

    // ====== Opus Profiles (Standard Quality) ======

    /** Medium Quality - Opus voice medium (8000 bps, 24kHz) */
    data object MQ : Profile(0x40, "Medium Quality", "MQ", 60) {
        override fun createCodec(): Codec = Opus(profile = Opus.PROFILE_VOICE_MEDIUM)

        override fun createDecodeCodec(): Codec = Opus(profile = Opus.PROFILE_VOICE_HIGH)

        override fun nativeEncodeParams() =
            NativeCodecParams(
                codecType = CODEC_TYPE_OPUS,
                sampleRate = 24000,
                channels = 1,
                opusApplication = NativeOpus.OPUS_APPLICATION_VOIP,
                opusBitrate = 8000,
                codecHeaderByte = Packetizer.CODEC_OPUS,
            )

        override fun nativeDecodeParams() =
            NativeCodecParams(
                codecType = CODEC_TYPE_OPUS,
                sampleRate = 48000,
                channels = 1,
                opusApplication = NativeOpus.OPUS_APPLICATION_VOIP,
                opusBitrate = 16000,
                codecHeaderByte = Packetizer.CODEC_OPUS,
            )
    }

    /** High Quality - Opus voice high (16000 bps, 48kHz) */
    data object HQ : Profile(0x50, "High Quality", "HQ", 60) {
        override fun createCodec(): Codec = Opus(profile = Opus.PROFILE_VOICE_HIGH)

        override fun nativeEncodeParams() =
            NativeCodecParams(
                codecType = CODEC_TYPE_OPUS,
                sampleRate = 48000,
                channels = 1,
                opusApplication = NativeOpus.OPUS_APPLICATION_VOIP,
                opusBitrate = 16000,
                codecHeaderByte = Packetizer.CODEC_OPUS,
            )
    }

    /** Super High Quality - Opus voice max (32000 bps, 48kHz stereo) */
    data object SHQ : Profile(0x60, "Super High Quality", "SHQ", 60) {
        override fun createCodec(): Codec = Opus(profile = Opus.PROFILE_VOICE_MAX)

        override fun nativeEncodeParams() =
            NativeCodecParams(
                codecType = CODEC_TYPE_OPUS,
                sampleRate = 48000,
                channels = 2,
                opusApplication = NativeOpus.OPUS_APPLICATION_VOIP,
                opusBitrate = 32000,
                codecHeaderByte = Packetizer.CODEC_OPUS,
            )
    }

    // ====== Opus Profiles (Low Latency) ======

    /** Low Latency - Opus voice medium with 20ms frames */
    data object LL : Profile(0x80, "Low Latency", "LL", 20) {
        override fun createCodec(): Codec = Opus(profile = Opus.PROFILE_VOICE_MEDIUM)

        override fun createDecodeCodec(): Codec = Opus(profile = Opus.PROFILE_VOICE_HIGH)

        override fun nativeEncodeParams() =
            NativeCodecParams(
                codecType = CODEC_TYPE_OPUS,
                sampleRate = 24000,
                channels = 1,
                opusApplication = NativeOpus.OPUS_APPLICATION_VOIP,
                opusBitrate = 8000,
                codecHeaderByte = Packetizer.CODEC_OPUS,
            )

        override fun nativeDecodeParams() =
            NativeCodecParams(
                codecType = CODEC_TYPE_OPUS,
                sampleRate = 48000,
                channels = 1,
                opusApplication = NativeOpus.OPUS_APPLICATION_VOIP,
                opusBitrate = 16000,
                codecHeaderByte = Packetizer.CODEC_OPUS,
            )
    }

    /** Ultra Low Latency - Opus voice medium with 10ms frames */
    data object ULL : Profile(0x70, "Ultra Low Latency", "ULL", 10) {
        override fun createCodec(): Codec = Opus(profile = Opus.PROFILE_VOICE_MEDIUM)

        override fun createDecodeCodec(): Codec = Opus(profile = Opus.PROFILE_VOICE_HIGH)

        override fun nativeEncodeParams() =
            NativeCodecParams(
                codecType = CODEC_TYPE_OPUS,
                sampleRate = 24000,
                channels = 1,
                opusApplication = NativeOpus.OPUS_APPLICATION_VOIP,
                opusBitrate = 8000,
                codecHeaderByte = Packetizer.CODEC_OPUS,
            )

        override fun nativeDecodeParams() =
            NativeCodecParams(
                codecType = CODEC_TYPE_OPUS,
                sampleRate = 48000,
                channels = 1,
                opusApplication = NativeOpus.OPUS_APPLICATION_VOIP,
                opusBitrate = 16000,
                codecHeaderByte = Packetizer.CODEC_OPUS,
            )
    }

    companion object {
        /** Codec type constants matching C++ CodecType enum */
        const val CODEC_TYPE_OPUS = 1
        const val CODEC_TYPE_CODEC2 = 2

        /** Default profile for new calls */
        val DEFAULT: Profile get() = MQ

        /** All profiles in order (low bandwidth to low latency) */
        val all: List<Profile> get() = listOf(ULBW, VLBW, LBW, MQ, HQ, SHQ, LL, ULL)

        /**
         * Look up profile by ID.
         *
         * @param id Profile ID byte
         * @return Profile or null if not found
         */
        fun fromId(id: Int): Profile? = all.find { it.id == id }

        /**
         * Get next profile in cycle (wraps around).
         *
         * @param profile Current profile
         * @return Next profile in the list
         */
        fun next(profile: Profile): Profile {
            val idx = all.indexOf(profile)
            return all[(idx + 1) % all.size]
        }
    }
}
