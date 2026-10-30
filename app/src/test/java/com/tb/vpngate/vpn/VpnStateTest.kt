package com.tb.vpngate.vpn

import com.tb.vpngate.data.Protocol
import com.tb.vpngate.data.VpnServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Maps the ics-openvpn ConnectionStatus level names onto this app's state model. */
class VpnStateTest {

    private val server = VpnServer(
        hostName = "h", ip = "1.2.3.4", score = 1, pingMs = 1, speedBps = 1,
        countryLong = "Japan", countryShort = "JP", numVpnSessions = 0, uptimeMs = 0,
        totalUsers = 0, totalTraffic = 0, logType = "", operator = "", message = "",
        configBase64 = "", protocol = Protocol.TCP, remoteHost = "1.2.3.4", remotePort = 443,
    )

    @Test
    fun `connected carries the server this app asked for`() {
        val state = levelToState("LEVEL_CONNECTED", null, server)
        assertEquals(VpnState.Connected(server), state)
    }

    @Test
    fun `a tunnel started outside this app has no server attached`() {
        // The companion app's status stream is global, so this is a real case, not a defect.
        val state = levelToState("LEVEL_CONNECTED", null, null)
        assertNull((state as VpnState.Connected).server)
    }

    @Test
    fun `all connecting levels map to Connecting`() {
        listOf(
            "LEVEL_START",
            "LEVEL_CONNECTING_NO_SERVER_REPLY_YET",
            "LEVEL_CONNECTING_SERVER_REPLIED",
            "LEVEL_WAITING_FOR_USER_INPUT",
        ).forEach { level ->
            assertTrue("$level should be Connecting", levelToState(level, "msg", server) is VpnState.Connecting)
        }
    }

    @Test
    fun `terminal and transport levels map as expected`() {
        assertEquals(VpnState.Paused, levelToState("LEVEL_VPNPAUSED", null, server))
        assertEquals(VpnState.NoNetwork, levelToState("LEVEL_NONETWORK", null, server))
        assertEquals(VpnState.Disconnected, levelToState("LEVEL_NOTCONNECTED", null, server))
        assertTrue(levelToState("LEVEL_AUTH_FAILED", "bad", server) is VpnState.AuthFailed)
    }

    @Test
    fun `unknown and future levels do not invent a state`() {
        // ics-openvpn may add levels; an unrecognised one must not read as Connected.
        assertEquals(VpnState.Disconnected, levelToState("UNKNOWN_LEVEL", null, server))
        assertEquals(VpnState.Disconnected, levelToState("LEVEL_SOMETHING_NEW", null, server))
        assertEquals(VpnState.Disconnected, levelToState(null, null, server))
    }

    @Test
    fun `the status message is surfaced on connecting and auth failure`() {
        assertEquals("handshaking", (levelToState("LEVEL_START", "handshaking", server) as VpnState.Connecting).detail)
        assertEquals("rejected", (levelToState("LEVEL_AUTH_FAILED", "rejected", server) as VpnState.AuthFailed).detail)
    }
}
