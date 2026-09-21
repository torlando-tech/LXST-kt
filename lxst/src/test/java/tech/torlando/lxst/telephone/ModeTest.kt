/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package tech.torlando.lxst.telephone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Mode] call duplex-mode definitions.
 *
 * Mode IDs must match Python LXST Telephony.py Profiles constants exactly
 * (wire protocol - changing them breaks interop):
 * - FULL_DUPLEX = 0x01 (MODE_FULL_DUPLEX)
 * - HALF_DUPLEX = 0x02 (MODE_HALF_DUPLEX)
 */
class ModeTest {

    // ===== Mode IDs (must match Python LXST exactly) =====

    @Test
    fun `full duplex has correct ID`() {
        assertEquals(0x01, Mode.FULL_DUPLEX.id)
    }

    @Test
    fun `half duplex has correct ID`() {
        assertEquals(0x02, Mode.HALF_DUPLEX.id)
    }

    // ===== Names / abbreviations (must match Python mode_name / mode_abbrevation) =====

    @Test
    fun `full duplex name matches python`() {
        assertEquals("Full Duplex", Mode.FULL_DUPLEX.name)
    }

    @Test
    fun `full duplex abbreviation matches python`() {
        assertEquals("FDX", Mode.FULL_DUPLEX.abbreviation)
    }

    @Test
    fun `half duplex name matches python`() {
        assertEquals("Half Duplex", Mode.HALF_DUPLEX.name)
    }

    @Test
    fun `half duplex abbreviation matches python`() {
        assertEquals("HDX", Mode.HALF_DUPLEX.abbreviation)
    }

    // ===== DEFAULT =====

    @Test
    fun `default mode is full duplex`() {
        assertEquals(Mode.FULL_DUPLEX, Mode.DEFAULT)
    }

    // ===== all =====

    @Test
    fun `all contains both modes in order`() {
        assertEquals(listOf(Mode.FULL_DUPLEX, Mode.HALF_DUPLEX), Mode.all)
    }

    // ===== fromId =====

    @Test
    fun `fromId returns full duplex for 0x01`() {
        assertEquals(Mode.FULL_DUPLEX, Mode.fromId(0x01))
    }

    @Test
    fun `fromId returns half duplex for 0x02`() {
        assertEquals(Mode.HALF_DUPLEX, Mode.fromId(0x02))
    }

    @Test
    fun `fromId returns null for 0x00`() {
        assertNull(Mode.fromId(0x00))
    }

    @Test
    fun `fromId returns null for invalid ID 0x03`() {
        assertNull(Mode.fromId(0x03))
    }

    @Test
    fun `fromId returns null for profile-range ID`() {
        // Profile IDs (0x10+) and signalling bases (0xF0+) are not modes.
        assertNull(Mode.fromId(0x10))
        assertNull(Mode.fromId(0xF0))
        assertNull(Mode.fromId(0xFF))
    }

    @Test
    fun `fromId is stable for valid modes`() {
        assertTrue(Mode.FULL_DUPLEX === Mode.fromId(0x01))
        assertTrue(Mode.HALF_DUPLEX === Mode.fromId(0x02))
    }

    // ===== next (UI cycle) =====

    @Test
    fun `next cycles full to half`() {
        assertEquals(Mode.HALF_DUPLEX, Mode.next(Mode.FULL_DUPLEX))
    }

    @Test
    fun `next cycles half to full`() {
        assertEquals(Mode.FULL_DUPLEX, Mode.next(Mode.HALF_DUPLEX))
    }
}
