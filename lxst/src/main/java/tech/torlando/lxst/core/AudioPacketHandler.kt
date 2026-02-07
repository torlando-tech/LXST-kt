/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package tech.torlando.lxst.core

// TODO: Future reorganization (Option B) — redistribute core/ classes by domain:
//   CallCoordinator, CallState → telephone/
//   PacketRouter → transport/
//   AudioDevice, AudioFilters → audio/

/**
 * Abstraction for sending audio packets and signals to the network layer.
 *
 * Replaces direct PyObject dependency in PacketRouter so `:lxst` module
 * has no Chaquopy coupling. The implementation wrapping Chaquopy lives in `:app`.
 *
 * Methods are called on [kotlinx.coroutines.Dispatchers.IO] — implementations
 * must be safe for off-main-thread invocation.
 */
interface AudioPacketHandler {
    /**
     * Send encoded audio packet to the remote peer via the network layer.
     *
     * @param packet Encoded audio data (codec header byte + encoded frame)
     */
    fun receiveAudioPacket(packet: ByteArray)

    /**
     * Send signalling value to the remote peer via the network layer.
     *
     * @param signal Signalling code (STATUS_BUSY, STATUS_RINGING, etc.)
     */
    fun receiveSignal(signal: Int)
}
