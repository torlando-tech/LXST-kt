/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package tech.torlando.lxst.core

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Routing policy tests for issue #1167: call audio must reach Bluetooth and wired
 * headsets instead of being hard-wired to the builtin earpiece/speaker.
 */
class CallAudioRoutingTest {
    private val earpiece = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
    private val speaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    private val btSco = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    private val wiredHeadset = AudioDeviceInfo.TYPE_WIRED_HEADSET
    private val wiredHeadphones = AudioDeviceInfo.TYPE_WIRED_HEADPHONES
    private val usbHeadset = AudioDeviceInfo.TYPE_USB_HEADSET

    // ===== Output routing =====

    @Test
    fun `speaker on selects builtin speaker`() {
        val route = CallAudioRouting.resolve(true, listOf(earpiece, speaker), listOf(earpiece, speaker))

        assertEquals(CallAudioRoute.Select(speaker), route)
    }

    @Test
    fun `speaker on wins over connected headset`() {
        val route = CallAudioRouting.resolve(true, listOf(earpiece, speaker, btSco), listOf(earpiece, speaker, btSco))

        assertEquals(CallAudioRoute.Select(speaker), route)
    }

    @Test
    fun `bluetooth SCO is selected when speaker is off`() {
        val route = CallAudioRouting.resolve(false, listOf(earpiece, btSco), listOf(earpiece, btSco))

        assertEquals(CallAudioRoute.Select(btSco), route)
    }

    @Test
    fun `wired headset is selected when speaker is off`() {
        val route = CallAudioRouting.resolve(false, listOf(earpiece, wiredHeadset), listOf(earpiece, wiredHeadset))

        assertEquals(CallAudioRoute.Select(wiredHeadset), route)
    }

    @Test
    fun `usb headset is selected when speaker is off`() {
        val route = CallAudioRouting.resolve(false, listOf(earpiece, usbHeadset), listOf(earpiece, usbHeadset))

        assertEquals(CallAudioRoute.Select(usbHeadset), route)
    }

    @Test
    fun `bluetooth SCO outranks wired headset`() {
        val route =
            CallAudioRouting.resolve(
                false,
                listOf(earpiece, wiredHeadset, btSco),
                listOf(earpiece, wiredHeadset, btSco),
            )

        assertEquals(CallAudioRoute.Select(btSco), route)
    }

    @Test
    fun `output-only wired headphones clear the selection so the platform routes to them`() {
        // Headphones without a mic are not selectable communication devices, but the
        // platform routes call audio to them once the app stops pinning the earpiece.
        val route = CallAudioRouting.resolve(false, listOf(earpiece), listOf(earpiece, wiredHeadphones))

        assertEquals(CallAudioRoute.PlatformDefault, route)
    }

    @Test
    fun `earpiece is selected when no headset is connected`() {
        val route = CallAudioRouting.resolve(false, listOf(earpiece, speaker), listOf(earpiece, speaker))

        assertEquals(CallAudioRoute.Select(earpiece), route)
    }

    @Test
    fun `clears the selection when no earpiece is offered`() {
        val route = CallAudioRouting.resolve(false, listOf(speaker), listOf(speaker))

        assertEquals(CallAudioRoute.PlatformDefault, route)
    }

    @Test
    fun `speaker falls back to legacy API when no speaker is offered`() {
        val route = CallAudioRouting.resolve(true, listOf(earpiece), listOf(earpiece))

        assertEquals(CallAudioRoute.LegacyFallback, route)
    }
}
