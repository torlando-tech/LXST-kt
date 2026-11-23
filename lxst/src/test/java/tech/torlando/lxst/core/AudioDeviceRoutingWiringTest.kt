/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package tech.torlando.lxst.core

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wiring tests for issue #1167: `setSpeakerphoneOn(false)` must hand the connected
 * headset to `setCommunicationDevice()` instead of the builtin earpiece.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AudioDeviceRoutingWiringTest {
    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var audioBridge: AudioDevice

    private val earpieceInfo =
        mockk<AudioDeviceInfo> {
            every { type } returns AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            every { productName } returns "Earpiece"
        }
    private val speakerInfo =
        mockk<AudioDeviceInfo> {
            every { type } returns AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            every { productName } returns "Speaker"
        }
    private val btScoInfo =
        mockk<AudioDeviceInfo> {
            every { type } returns AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            every { productName } returns "Headset"
        }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = mockk()
        audioBridge = AudioDevice(context, audioManager)

        every { audioManager.communicationDevice } returns earpieceInfo
        every { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } returns
            arrayOf(earpieceInfo, speakerInfo)
        every { audioManager.setCommunicationDevice(any()) } returns true
        every { audioManager.clearCommunicationDevice() } returns Unit
    }

    @After
    fun tearDown() {
        audioBridge.shutdown()
    }

    @Test
    fun `speaker off routes call audio to connected bluetooth headset`() {
        every { audioManager.availableCommunicationDevices } returns
            listOf(earpieceInfo, speakerInfo, btScoInfo)

        audioBridge.setSpeakerphoneOn(false)

        verify(exactly = 1) { audioManager.setCommunicationDevice(btScoInfo) }
        verify(exactly = 0) { audioManager.setCommunicationDevice(earpieceInfo) }
        verify(exactly = 0) { audioManager.clearCommunicationDevice() }
    }

    @Test
    fun `speaker on routes call audio to the builtin speaker`() {
        every { audioManager.availableCommunicationDevices } returns
            listOf(earpieceInfo, speakerInfo, btScoInfo)

        audioBridge.setSpeakerphoneOn(true)

        verify(exactly = 1) { audioManager.setCommunicationDevice(speakerInfo) }
    }

    @Test
    fun `speaker off keeps earpiece when no headset is connected`() {
        every { audioManager.availableCommunicationDevices } returns
            listOf(earpieceInfo, speakerInfo)

        audioBridge.setSpeakerphoneOn(false)

        verify(exactly = 1) { audioManager.setCommunicationDevice(earpieceInfo) }
    }
}
