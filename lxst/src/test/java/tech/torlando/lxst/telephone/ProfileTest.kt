/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package tech.torlando.lxst.telephone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Profile class configuration logic.
 *
 * Tests profile IDs, frame times, and lookup functions without requiring
 * Android or JNI (no codec instantiation - that requires instrumented tests).
 *
 * Profile IDs must match Python LXST Telephony.py exactly for wire compatibility.
 */
class ProfileTest {
    // ===== Profile IDs (must match Python LXST exactly) =====

    @Test
    fun `ULBW has correct ID`() {
        assertEquals(0x10, Profile.ULBW.id)
    }

    @Test
    fun `VLBW has correct ID`() {
        assertEquals(0x20, Profile.VLBW.id)
    }

    @Test
    fun `LBW has correct ID`() {
        assertEquals(0x30, Profile.LBW.id)
    }

    @Test
    fun `MQ has correct ID`() {
        assertEquals(0x40, Profile.MQ.id)
    }

    @Test
    fun `HQ has correct ID`() {
        assertEquals(0x50, Profile.HQ.id)
    }

    @Test
    fun `SHQ has correct ID`() {
        assertEquals(0x60, Profile.SHQ.id)
    }

    @Test
    fun `LL has correct ID`() {
        assertEquals(0x80, Profile.LL.id)
    }

    @Test
    fun `ULL has correct ID`() {
        assertEquals(0x70, Profile.ULL.id)
    }

    // ===== Frame Times (ms) =====

    @Test
    fun `ULBW has 400ms frame time`() {
        assertEquals(400, Profile.ULBW.frameTimeMs)
    }

    @Test
    fun `VLBW has 320ms frame time`() {
        assertEquals(320, Profile.VLBW.frameTimeMs)
    }

    @Test
    fun `LBW has 200ms frame time`() {
        assertEquals(200, Profile.LBW.frameTimeMs)
    }

    @Test
    fun `MQ has 60ms frame time`() {
        assertEquals(60, Profile.MQ.frameTimeMs)
    }

    @Test
    fun `HQ has 60ms frame time`() {
        assertEquals(60, Profile.HQ.frameTimeMs)
    }

    @Test
    fun `SHQ has 60ms frame time`() {
        assertEquals(60, Profile.SHQ.frameTimeMs)
    }

    @Test
    fun `LL has 20ms frame time`() {
        assertEquals(20, Profile.LL.frameTimeMs)
    }

    @Test
    fun `ULL has 10ms frame time`() {
        assertEquals(10, Profile.ULL.frameTimeMs)
    }

    // ===== Profile.all list =====

    @Test
    fun `all profiles has 8 profiles`() {
        assertEquals(8, Profile.all.size)
    }

    @Test
    fun `profiles are in expected order`() {
        val expected =
            listOf(
                Profile.ULBW,
                Profile.VLBW,
                Profile.LBW,
                Profile.MQ,
                Profile.HQ,
                Profile.SHQ,
                Profile.LL,
                Profile.ULL,
            )
        assertEquals(expected, Profile.all)
    }

    @Test
    fun `all profiles have unique IDs`() {
        val ids = Profile.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    // ===== fromId() lookup =====

    @Test
    fun `fromId returns correct profile for ULBW`() {
        assertEquals(Profile.ULBW, Profile.fromId(0x10))
    }

    @Test
    fun `fromId returns correct profile for MQ`() {
        assertEquals(Profile.MQ, Profile.fromId(0x40))
    }

    @Test
    fun `fromId returns correct profile for ULL`() {
        assertEquals(Profile.ULL, Profile.fromId(0x70))
    }

    @Test
    fun `fromId returns correct profile for LL`() {
        assertEquals(Profile.LL, Profile.fromId(0x80))
    }

    @Test
    fun `fromId returns null for invalid ID 0x99`() {
        assertNull(Profile.fromId(0x99))
    }

    @Test
    fun `fromId returns null for invalid ID 0x00`() {
        assertNull(Profile.fromId(0x00))
    }

    @Test
    fun `fromId returns null for invalid ID 0x15`() {
        // Between ULBW (0x10) and VLBW (0x20)
        assertNull(Profile.fromId(0x15))
    }

    @Test
    fun `fromId works for all profiles`() {
        Profile.all.forEach { profile ->
            assertEquals(profile, Profile.fromId(profile.id))
        }
    }

    // ===== next() cycling =====

    @Test
    fun `next cycles from ULBW to VLBW`() {
        assertEquals(Profile.VLBW, Profile.next(Profile.ULBW))
    }

    @Test
    fun `next cycles from ULL to ULBW (wrap around)`() {
        assertEquals(Profile.ULBW, Profile.next(Profile.ULL))
    }

    @Test
    fun `next cycles through all profiles correctly`() {
        var current: Profile = Profile.ULBW
        val visited = mutableListOf<Profile>(current)

        repeat(Profile.all.size - 1) {
            current = Profile.next(current)
            visited.add(current)
        }

        assertEquals(Profile.all, visited)
    }

    @Test
    fun `next wraps correctly after full cycle`() {
        var current: Profile = Profile.ULBW

        // Cycle through all profiles
        repeat(Profile.all.size) {
            current = Profile.next(current)
        }

        // Should be back at ULBW
        assertEquals(Profile.ULBW, current)
    }

    // ===== DEFAULT profile =====

    @Test
    fun `DEFAULT is MQ`() {
        assertEquals(Profile.MQ, Profile.DEFAULT)
    }

    @Test
    fun `DEFAULT has ID 0x40`() {
        assertEquals(0x40, Profile.DEFAULT.id)
    }

    @Test
    fun `DEFAULT has 60ms frame time`() {
        assertEquals(60, Profile.DEFAULT.frameTimeMs)
    }

    // ===== Abbreviations =====

    @Test
    fun `all profiles have non-empty abbreviations`() {
        Profile.all.forEach { profile ->
            assertTrue("${profile.name} should have abbreviation", profile.abbreviation.isNotEmpty())
        }
    }

    @Test
    fun `abbreviations are short (max 4 chars)`() {
        Profile.all.forEach { profile ->
            assertTrue(
                "${profile.name} abbreviation '${profile.abbreviation}' too long",
                profile.abbreviation.length <= 4,
            )
        }
    }

    @Test
    fun `ULBW abbreviation is ULBW`() {
        assertEquals("ULBW", Profile.ULBW.abbreviation)
    }

    @Test
    fun `MQ abbreviation is MQ`() {
        assertEquals("MQ", Profile.MQ.abbreviation)
    }

    // ===== Names =====

    @Test
    fun `all profiles have non-empty names`() {
        Profile.all.forEach { profile ->
            assertTrue("Profile should have name", profile.name.isNotEmpty())
        }
    }

    @Test
    fun `ULBW name is Ultra Low Bandwidth`() {
        assertEquals("Ultra Low Bandwidth", Profile.ULBW.name)
    }

    @Test
    fun `MQ name is Medium Quality`() {
        assertEquals("Medium Quality", Profile.MQ.name)
    }

    // ===== Data object equality (Kotlin data object provides automatic equals) =====

    @Test
    fun `profile instances are singletons`() {
        // data objects are singletons
        assertTrue(Profile.MQ === Profile.MQ)
        assertTrue(Profile.ULBW === Profile.ULBW)
    }

    @Test
    fun `fromId returns same instance`() {
        assertTrue(Profile.MQ === Profile.fromId(0x40))
        assertTrue(Profile.ULBW === Profile.fromId(0x10))
    }

    // ===== nativeEncodeParams - Codec2 profiles =====

    @Test
    fun `ULBW nativeEncodeParams has Codec2 type`() {
        val params = Profile.ULBW.nativeEncodeParams()
        assertEquals(Profile.CODEC_TYPE_CODEC2, params.codecType)
    }

    @Test
    fun `ULBW nativeEncodeParams has 8000Hz sample rate`() {
        assertEquals(8000, Profile.ULBW.nativeEncodeParams().sampleRate)
    }

    @Test
    fun `ULBW nativeEncodeParams has MODE_700C`() {
        // NativeCodec2.MODE_700C = 8
        assertEquals(8, Profile.ULBW.nativeEncodeParams().codec2LibraryMode)
    }

    @Test
    fun `ULBW nativeEncodeParams has codec2 header byte`() {
        // Packetizer.CODEC_CODEC2 = 0x02
        assertEquals(0x02.toByte(), Profile.ULBW.nativeEncodeParams().codecHeaderByte)
    }

    @Test
    fun `VLBW nativeEncodeParams has MODE_1600`() {
        // NativeCodec2.MODE_1600 = 2
        assertEquals(2, Profile.VLBW.nativeEncodeParams().codec2LibraryMode)
    }

    @Test
    fun `LBW nativeEncodeParams has MODE_3200`() {
        // NativeCodec2.MODE_3200 = 0
        assertEquals(0, Profile.LBW.nativeEncodeParams().codec2LibraryMode)
    }

    @Test
    fun `Codec2 profiles share encode and decode params`() {
        // Codec2 profiles don't override nativeDecodeParams, so they equal nativeEncodeParams
        listOf(Profile.ULBW, Profile.VLBW, Profile.LBW).forEach { profile ->
            assertEquals(
                "Codec2 profile ${profile.abbreviation} should have encode==decode params",
                profile.nativeEncodeParams(),
                profile.nativeDecodeParams(),
            )
        }
    }

    // ===== nativeEncodeParams - Opus profiles =====

    @Test
    fun `MQ nativeEncodeParams has Opus type`() {
        assertEquals(Profile.CODEC_TYPE_OPUS, Profile.MQ.nativeEncodeParams().codecType)
    }

    @Test
    fun `MQ nativeEncodeParams has 24000Hz sample rate`() {
        assertEquals(24000, Profile.MQ.nativeEncodeParams().sampleRate)
    }

    @Test
    fun `MQ nativeEncodeParams has 8000bps bitrate`() {
        assertEquals(8000, Profile.MQ.nativeEncodeParams().opusBitrate)
    }

    @Test
    fun `MQ nativeEncodeParams has VOIP application`() {
        // NativeOpus.OPUS_APPLICATION_VOIP = 2048
        assertEquals(2048, Profile.MQ.nativeEncodeParams().opusApplication)
    }

    @Test
    fun `MQ nativeEncodeParams has opus header byte`() {
        // Packetizer.CODEC_OPUS = 0x01
        assertEquals(0x01.toByte(), Profile.MQ.nativeEncodeParams().codecHeaderByte)
    }

    @Test
    fun `HQ nativeEncodeParams has 48000Hz and 16000bps`() {
        val params = Profile.HQ.nativeEncodeParams()
        assertEquals(48000, params.sampleRate)
        assertEquals(16000, params.opusBitrate)
        assertEquals(1, params.channels)
    }

    @Test
    fun `SHQ nativeEncodeParams has stereo channels`() {
        val params = Profile.SHQ.nativeEncodeParams()
        assertEquals(2, params.channels)
        assertEquals(32000, params.opusBitrate)
        assertEquals(48000, params.sampleRate)
    }

    @Test
    fun `HQ and SHQ share encode and decode params`() {
        // HQ and SHQ don't override nativeDecodeParams
        assertEquals(Profile.HQ.nativeEncodeParams(), Profile.HQ.nativeDecodeParams())
        assertEquals(Profile.SHQ.nativeEncodeParams(), Profile.SHQ.nativeDecodeParams())
    }

    // ===== nativeDecodeParams - Asymmetric profiles (MQ, LL, ULL) =====

    @Test
    fun `MQ nativeDecodeParams has 48000Hz decode rate`() {
        val decode = Profile.MQ.nativeDecodeParams()
        assertEquals(48000, decode.sampleRate)
        assertEquals(16000, decode.opusBitrate)
    }

    @Test
    fun `MQ encode and decode params differ`() {
        val encode = Profile.MQ.nativeEncodeParams()
        val decode = Profile.MQ.nativeDecodeParams()
        // Encode at 24kHz/8kbps, decode at 48kHz/16kbps
        assertTrue(
            "MQ encode and decode params should differ",
            encode != decode,
        )
        assertEquals(24000, encode.sampleRate)
        assertEquals(48000, decode.sampleRate)
    }

    @Test
    fun `LL nativeDecodeParams has 48000Hz decode rate`() {
        val decode = Profile.LL.nativeDecodeParams()
        assertEquals(48000, decode.sampleRate)
        assertEquals(16000, decode.opusBitrate)
    }

    @Test
    fun `LL encode and decode params differ`() {
        val encode = Profile.LL.nativeEncodeParams()
        val decode = Profile.LL.nativeDecodeParams()
        assertTrue("LL encode and decode should differ", encode != decode)
        assertEquals(24000, encode.sampleRate)
        assertEquals(48000, decode.sampleRate)
    }

    @Test
    fun `ULL nativeDecodeParams has 48000Hz decode rate`() {
        val decode = Profile.ULL.nativeDecodeParams()
        assertEquals(48000, decode.sampleRate)
        assertEquals(16000, decode.opusBitrate)
    }

    @Test
    fun `ULL encode and decode params differ`() {
        val encode = Profile.ULL.nativeEncodeParams()
        val decode = Profile.ULL.nativeDecodeParams()
        assertTrue("ULL encode and decode should differ", encode != decode)
    }

    // ===== nativeEncodeParams - Low latency profiles =====

    @Test
    fun `LL and ULL nativeEncodeParams match MQ config`() {
        // LL and ULL use same codec config as MQ (24kHz, 8kbps VOIP) but different frame time
        val mqEncode = Profile.MQ.nativeEncodeParams()
        val llEncode = Profile.LL.nativeEncodeParams()
        val ullEncode = Profile.ULL.nativeEncodeParams()
        assertEquals(mqEncode.sampleRate, llEncode.sampleRate)
        assertEquals(mqEncode.opusBitrate, llEncode.opusBitrate)
        assertEquals(mqEncode.sampleRate, ullEncode.sampleRate)
        assertEquals(mqEncode.opusBitrate, ullEncode.opusBitrate)
    }

    // ===== Codec type constants =====

    @Test
    fun `CODEC_TYPE_OPUS is 1`() {
        assertEquals(1, Profile.CODEC_TYPE_OPUS)
    }

    @Test
    fun `CODEC_TYPE_CODEC2 is 2`() {
        assertEquals(2, Profile.CODEC_TYPE_CODEC2)
    }

    // ===== All profiles have valid nativeEncodeParams =====

    @Test
    fun `all profiles return non-null nativeEncodeParams`() {
        Profile.all.forEach { profile ->
            val params = profile.nativeEncodeParams()
            assertTrue(
                "${profile.abbreviation} should have valid codecType",
                params.codecType == Profile.CODEC_TYPE_OPUS || params.codecType == Profile.CODEC_TYPE_CODEC2,
            )
            assertTrue(
                "${profile.abbreviation} should have positive sampleRate",
                params.sampleRate > 0,
            )
            assertTrue(
                "${profile.abbreviation} should have positive channels",
                params.channels > 0,
            )
        }
    }

    @Test
    fun `all profiles return non-null nativeDecodeParams`() {
        Profile.all.forEach { profile ->
            val params = profile.nativeDecodeParams()
            assertTrue(
                "${profile.abbreviation} decode should have valid codecType",
                params.codecType == Profile.CODEC_TYPE_OPUS || params.codecType == Profile.CODEC_TYPE_CODEC2,
            )
        }
    }
}
