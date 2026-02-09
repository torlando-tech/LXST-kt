/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package tech.torlando.lxst.core

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// TODO: Future reorganization (Option B) — redistribute core/ classes by domain:
//   CallCoordinator, CallState → telephone/
//   PacketRouter → transport/
//   AudioDevice, AudioFilters → audio/

/**
 * Call state sealed class for type-safe call state management.
 */
sealed class CallState {
    /** No active call */
    data object Idle : CallState()

    /** Initiating outgoing call, waiting for link establishment */
    data class Connecting(
        val identityHash: String,
    ) : CallState()

    /** Remote is ringing (outgoing call) */
    data class Ringing(
        val identityHash: String,
    ) : CallState()

    /** Incoming call from remote peer */
    data class Incoming(
        val identityHash: String,
    ) : CallState()

    /** Call is active and audio is flowing */
    data class Active(
        val identityHash: String,
    ) : CallState()

    /** Remote rejected the call or is busy */
    data object Busy : CallState()

    /** Call was rejected by us or remote */
    data object Rejected : CallState()

    /** Call ended normally */
    data object Ended : CallState()
}

/**
 * Interface for call management actions (call, answer, hangup, mute, speaker).
 *
 * Implemented by the transport layer (e.g., PythonWrapperManager or a future
 * Kotlin Reticulum manager). Allows for mocking in unit tests.
 */
interface CallController {
    fun call(destinationHash: String)

    fun answer()

    fun hangup()

    fun muteMicrophone(muted: Boolean)

    fun setSpeaker(enabled: Boolean)
}

/**
 * Bridge for call state between the network transport and the Kotlin UI.
 *
 * Manages bidirectional communication:
 * - Network → UI: Call state changes, incoming calls, call ended
 * - UI → Network: Initiate call, answer, decline, end call, mute, speaker
 *
 * **Thread Safety**: All state flows are thread-safe. Python callbacks are
 * invoked on the bridge's coroutine scope.
 */
@Suppress("TooManyFunctions")
class CallCoordinator private constructor(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    companion object {
        private const val TAG = "LXST:CallCoordinator"

        @Volatile
        private var instance: CallCoordinator? = null

        /**
         * Get singleton instance.
         */
        fun getInstance(): CallCoordinator =
            instance ?: synchronized(this) {
                instance ?: CallCoordinator().also { instance = it }
            }

        /**
         * Get singleton instance with custom dispatcher (for testing).
         */
        internal fun getInstance(dispatcher: CoroutineDispatcher): CallCoordinator =
            instance ?: synchronized(this) {
                instance ?: CallCoordinator(dispatcher).also { instance = it }
            }

        /**
         * Reset singleton instance (for testing).
         */
        internal fun resetInstance() {
            synchronized(this) {
                instance?.shutdown()
                instance = null
            }
        }
    }

    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    // Call state flows for UI observation
    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _remoteIdentity = MutableStateFlow<String?>(null)
    val remoteIdentity: StateFlow<String?> = _remoteIdentity.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private val _isPttMode = MutableStateFlow(false)
    val isPttMode: StateFlow<Boolean> = _isPttMode.asStateFlow()

    private val _isPttActive = MutableStateFlow(false)
    val isPttActive: StateFlow<Boolean> = _isPttActive.asStateFlow()

    private val _callDuration = MutableStateFlow(0L)
    val callDuration: StateFlow<Long> = _callDuration.asStateFlow()

    private val _callStartTime = MutableStateFlow<Long?>(null)
    val callStartTime: StateFlow<Long?> = _callStartTime.asStateFlow()

    // Call controller reference (set by transport layer, e.g. PythonWrapperManager)
    @Volatile
    private var callController: CallController? = null

    // Callback for incoming calls (for IPC notification to UI process)
    @Volatile
    private var incomingCallListener: ((String) -> Unit)? = null

    // Callback for call ended (for IPC notification to UI process)
    @Volatile
    private var callEndedListener: ((String?) -> Unit)? = null

    // Callback for call state changes (for IPC notification to UI process)
    // Parameters: state (String), identityHash (String?)
    @Volatile
    private var callStateChangedListener: ((String, String?) -> Unit)? = null

    // ===== Call Manager Setup =====

    /**
     * Set the call controller implementation.
     *
     * Called by the transport layer after initialization.
     * Accepts the CallController so `:lxst` has no transport dependency.
     */
    fun setCallManager(manager: CallController?) {
        callController = manager
        Log.i(TAG, "CallController ${if (manager != null) "set" else "cleared"}")
    }

    /**
     * Set listener for incoming calls (for IPC notification).
     * Called by the service binder to broadcast to UI process.
     */
    fun setIncomingCallListener(listener: ((String) -> Unit)?) {
        incomingCallListener = listener
    }

    /**
     * Set listener for call ended events (for IPC notification).
     * Called by the service binder to broadcast to UI process.
     */
    fun setCallEndedListener(listener: ((String?) -> Unit)?) {
        callEndedListener = listener
    }

    /**
     * Set listener for call state changes (for IPC notification).
     * Called by the service binder to broadcast to UI process.
     * Listener receives (state: String, identityHash: String?)
     */
    fun setCallStateChangedListener(listener: ((String, String?) -> Unit)?) {
        callStateChangedListener = listener
    }

    // ===== Called by network transport =====

    /**
     * Notify of incoming call.
     *
     * Called by the transport layer when an incoming call arrives.
     */
    fun onIncomingCall(identityHash: String) {
        Log.i(TAG, "Incoming call from: ${identityHash.take(16)}...")
        scope.launch {
            _remoteIdentity.value = identityHash
            _callState.value = CallState.Incoming(identityHash)
        }
        // Notify listener for IPC broadcast to UI process
        incomingCallListener?.invoke(identityHash)
    }

    /**
     * Notify that remote is ringing.
     *
     * Called by the transport layer when the remote is ringing.
     */
    fun onCallRinging(identityHash: String) {
        Log.d(TAG, "Call ringing: ${identityHash.take(16)}...")
        scope.launch {
            _callState.value = CallState.Ringing(identityHash)
        }
        // Notify listener for IPC broadcast to UI process
        callStateChangedListener?.invoke("ringing", identityHash)
    }

    /**
     * Notify that call is established.
     *
     * Called by the transport layer when the call is answered and audio is flowing.
     */
    fun onCallEstablished(identityHash: String) {
        Log.i(TAG, "Call established with: ${identityHash.take(16)}...")
        scope.launch {
            _callState.value = CallState.Active(identityHash)
            _callStartTime.value = System.currentTimeMillis()
        }
        // Notify listener for IPC broadcast to UI process
        callStateChangedListener?.invoke("established", identityHash)
    }

    /**
     * Notify that call has ended.
     *
     * Called by the transport layer when the call ends (either side hangs up).
     */
    fun onCallEnded(identityHash: String?) {
        Log.i(TAG, "Call ended: ${identityHash?.take(16) ?: "unknown"}")
        // Notify listener for IPC broadcast to UI process
        callEndedListener?.invoke(identityHash)
        scope.launch {
            // Calculate final duration before resetting
            _callStartTime.value?.let { startTime ->
                _callDuration.value = (System.currentTimeMillis() - startTime) / 1000
            }

            _callState.value = CallState.Ended
            // Reset after a short delay to allow UI to show "Call Ended"
            kotlinx.coroutines.delay(2000)
            resetState()
        }
    }

    /**
     * Notify that remote is busy.
     *
     * Called by the transport layer when the remote party is already on a call.
     */
    fun onCallBusy() {
        Log.d(TAG, "Remote is busy")
        scope.launch {
            _callState.value = CallState.Busy
            kotlinx.coroutines.delay(3000)
            resetState()
        }
        // Notify listener for IPC broadcast to UI process
        callStateChangedListener?.invoke("busy", null)
    }

    /**
     * Notify that call was rejected.
     *
     * Called by the transport layer when the remote party rejects the call.
     */
    fun onCallRejected() {
        Log.d(TAG, "Call rejected")
        scope.launch {
            _callState.value = CallState.Rejected
            kotlinx.coroutines.delay(2000)
            resetState()
        }
        // Notify listener for IPC broadcast to UI process
        callStateChangedListener?.invoke("rejected", null)
    }

    // ===== Called by Kotlin UI =====

    /**
     * Initiate an outgoing call.
     *
     * @param destinationHash 32-character hex hash of the destination identity
     */
    fun initiateCall(destinationHash: String) {
        Log.i(TAG, "Initiating call to: ${destinationHash.take(16)}...")

        scope.launch {
            _remoteIdentity.value = destinationHash
            _callState.value = CallState.Connecting(destinationHash)

            try {
                callController?.call(destinationHash)
            } catch (e: Exception) {
                Log.e(TAG, "Error initiating call", e)
                _callState.value = CallState.Ended
                kotlinx.coroutines.delay(1000)
                resetState()
            }
        }
    }

    /**
     * Answer an incoming call.
     */
    fun answerCall() {
        Log.d(TAG, "Answering call")
        scope.launch {
            try {
                callController?.answer()
            } catch (e: Exception) {
                Log.e(TAG, "Error answering call", e)
            }
        }
    }

    /**
     * Decline an incoming call.
     */
    fun declineCall() {
        Log.d(TAG, "Declining call")
        endCall()
    }

    /**
     * End the current call.
     */
    fun endCall() {
        Log.d(TAG, "Ending call")
        scope.launch {
            try {
                callController?.hangup()
            } catch (e: Exception) {
                Log.e(TAG, "Error ending call", e)
            }
            // Python will notify us via onCallEnded
        }
    }

    /**
     * Toggle microphone mute.
     */
    fun toggleMute() {
        val newMuted = !_isMuted.value
        _isMuted.value = newMuted
        Log.d(TAG, "Mute toggled: $newMuted")

        scope.launch {
            try {
                callController?.muteMicrophone(newMuted)
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling mute", e)
            }
        }
    }

    /**
     * Set microphone mute state.
     */
    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        scope.launch {
            try {
                callController?.muteMicrophone(muted)
            } catch (e: Exception) {
                Log.e(TAG, "Error setting mute", e)
            }
        }
    }

    /**
     * Toggle speaker/earpiece.
     */
    fun toggleSpeaker() {
        val newSpeaker = !_isSpeakerOn.value
        _isSpeakerOn.value = newSpeaker
        Log.d(TAG, "Speaker toggled: $newSpeaker")

        scope.launch {
            try {
                callController?.setSpeaker(newSpeaker)
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling speaker", e)
            }
        }
    }

    /**
     * Set speaker state.
     */
    fun setSpeaker(enabled: Boolean) {
        _isSpeakerOn.value = enabled
        scope.launch {
            try {
                callController?.setSpeaker(enabled)
            } catch (e: Exception) {
                Log.e(TAG, "Error setting speaker", e)
            }
        }
    }

    // ===== Push-to-Talk =====

    /**
     * Enable or disable PTT mode.
     *
     * When PTT mode is enabled, transmit is muted by default.
     * The user must press (and hold) the PTT button to transmit.
     * When PTT mode is disabled, transmit returns to full duplex.
     */
    fun setPttMode(enabled: Boolean) {
        Log.d(TAG, "PTT mode: $enabled")
        _isPttMode.value = enabled
        if (enabled) {
            // Enter PTT mode: mute transmit by default
            _isPttActive.value = false
            setMuted(true)
        } else {
            // Leave PTT mode: unmute transmit (back to full duplex)
            _isPttActive.value = false
            setMuted(false)
        }
    }

    /**
     * Set PTT transmit state (press/release).
     *
     * Only has effect when PTT mode is enabled and call is active.
     * @param active true when the PTT button is pressed (transmitting),
     *               false when released (listening)
     */
    fun setPttActive(active: Boolean) {
        if (!_isPttMode.value) return
        if (_callState.value !is CallState.Active) return
        Log.d(TAG, "PTT active: $active")
        _isPttActive.value = active
        setMuted(!active) // Pressed = unmuted (transmitting), released = muted (listening)
    }

    // ===== Helper Methods =====

    /**
     * Check if there's an active or pending call.
     */
    fun hasActiveCall(): Boolean =
        when (_callState.value) {
            is CallState.Connecting,
            is CallState.Ringing,
            is CallState.Incoming,
            is CallState.Active,
            -> true
            else -> false
        }

    /**
     * Get current call duration in seconds.
     */
    fun getCurrentDuration(): Long {
        val startTime = _callStartTime.value ?: return 0
        return (System.currentTimeMillis() - startTime) / 1000
    }

    /**
     * Reset all state to idle.
     */
    private fun resetState() {
        _callState.value = CallState.Idle
        _remoteIdentity.value = null
        _isMuted.value = false
        _isSpeakerOn.value = false
        _isPttMode.value = false
        _isPttActive.value = false
        _callDuration.value = 0L
        _callStartTime.value = null
    }

    // ===== Local State Helpers (for UI layer) =====

    /**
     * Set local connecting state. Used by UI when initiating call via protocol.
     */
    fun setConnecting(destinationHash: String) {
        _remoteIdentity.value = destinationHash
        _callState.value = CallState.Connecting(destinationHash)
    }

    /**
     * Set local ended state. Used by UI after calling protocol.hangupCall().
     */
    fun setEnded() {
        scope.launch {
            _callState.value = CallState.Ended
            kotlinx.coroutines.delay(2000)
            resetState()
        }
    }

    /**
     * Update local mute state without calling Python.
     * Used by UI when mute is controlled via protocol.
     */
    fun setMutedLocally(muted: Boolean) {
        _isMuted.value = muted
    }

    /**
     * Update local speaker state without calling Python.
     * Used by UI when speaker is controlled via protocol.
     */
    fun setSpeakerLocally(enabled: Boolean) {
        _isSpeakerOn.value = enabled
    }

    /**
     * Update local PTT mode state.
     */
    fun setPttModeLocally(enabled: Boolean) {
        _isPttMode.value = enabled
    }

    /**
     * Update local PTT active state.
     */
    fun setPttActiveLocally(active: Boolean) {
        _isPttActive.value = active
    }

    /**
     * Cleanup resources.
     */
    fun shutdown() {
        Log.i(TAG, "Shutting down CallCoordinator")
        endCall()
        resetState()
    }
}
