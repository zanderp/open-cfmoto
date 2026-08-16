package dev.zanderp.opencfmoto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAutoServiceTest {
    @Test
    fun bikeTransportIsLiveForApOrP2p() {
        assertFalse(AndroidAutoService.bikeTransportConnected(false, false))
        assertTrue(AndroidAutoService.bikeTransportConnected(true, false))
        assertTrue(AndroidAutoService.bikeTransportConnected(false, true))
        assertTrue(AndroidAutoService.bikeTransportConnected(true, true))
    }
}
