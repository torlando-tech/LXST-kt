/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package tech.torlando.lxst.telephone

/**
 * Call duplex mode for LXST telephony.
 *
 * Matches Python LXST Telephony.py Profiles call-mode constants exactly for wire
 * compatibility:
 * - [FULL_DUPLEX] = 0x01 (Profiles.MODE_FULL_DUPLEX) - both ends transmit continuously
 * - [HALF_DUPLEX] = 0x02 (Profiles.MODE_HALF_DUPLEX) - transmit is PTT-gated (squelched
 *   by default; holding PTT unsquelches)
 *
 * Mode IDs are used in [Signalling.PREFERRED_MODE] negotiation signalling and must
 * match Python. The default for new calls is [FULL_DUPLEX]
 * (Python `Profiles.DEFAULT_MODE = MODE_FULL_DUPLEX`).
 *
 * Unlike [Profile] (codec/quality), mode does not carry codec configuration - it only
 * controls whether transmit is squelched. Changing mode signals the peer and squelches
 * or unsquelches the local transmit path; the codec profile is unchanged.
 */
sealed class Mode(
    /** Mode ID byte (matches Python LXST). */
    val id: Int,
    /** Human-readable mode name (matches Python `mode_name`). */
    val name: String,
    /** Short abbreviation for UI display (matches Python `mode_abbrevation`). */
    val abbreviation: String,
) {
    /** Full duplex - both ends transmit continuously (default). */
    data object FULL_DUPLEX : Mode(0x01, "Full Duplex", "FDX")

    /** Half duplex - transmit is PTT-gated; squelched unless PTT is held. */
    data object HALF_DUPLEX : Mode(0x02, "Half Duplex", "HDX")

    companion object {
        /** Default mode for new calls (Python `DEFAULT_MODE`). */
        val DEFAULT: Mode get() = FULL_DUPLEX

        /** All modes in order. */
        val all: List<Mode> get() = listOf(FULL_DUPLEX, HALF_DUPLEX)

        /**
         * Look up a mode by its wire ID byte.
         *
         * @param id Mode ID byte (0x01 full, 0x02 half)
         * @return Mode or null if not a valid call mode
         */
        fun fromId(id: Int): Mode? = all.find { it.id == id }

        /**
         * Get the next mode in cycle (wraps around). Used by the UI cycle button.
         *
         * @param mode Current mode
         * @return The other mode
         */
        fun next(mode: Mode): Mode {
            val idx = all.indexOf(mode)
            return all[(idx + 1) % all.size]
        }
    }
}
