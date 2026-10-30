package com.tb.vpngate.vpn

import com.tb.vpngate.data.Protocol
import com.tb.vpngate.data.VpnGateCsvParser
import com.tb.vpngate.data.VpnServer
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnProfileTest {

    private fun fixture(): String =
        requireNotNull(javaClass.getResourceAsStream("/vpngate_sample.csv")).bufferedReader().readText()

    private fun server(configText: String) = VpnServer(
        hostName = "test", ip = "1.2.3.4", score = 1, pingMs = 1, speedBps = 1,
        countryLong = "Testland", countryShort = "TL", numVpnSessions = 0, uptimeMs = 0,
        totalUsers = 0, totalTraffic = 0, logType = "", operator = "", message = "",
        configBase64 = configText.encodeUtf8().base64(),
        protocol = Protocol.TCP, remoteHost = "1.2.3.4", remotePort = 443,
    )

    private val minimalConfig = """
        client
        dev tun
        proto tcp
        remote 1.2.3.4 443
        cipher AES-128-CBC
        auth SHA1
        <ca>
        -----BEGIN CERTIFICATE-----
        -----END CERTIFICATE-----
        </ca>
    """.trimIndent()

    @Test
    fun `adds fast-fail timeouts so failover can move on`() {
        val out = VpnProfile.inlineConfigFor(server(minimalConfig))
        assertTrue(out.contains("connect-timeout 10"))
        assertTrue(out.contains("connect-retry-max 1"))
    }

    @Test
    fun `preserves the original directives verbatim`() {
        val out = VpnProfile.inlineConfigFor(server(minimalConfig))
        // The whole original profile must survive untouched; we only append.
        assertTrue(out.startsWith(minimalConfig))
    }

    @Test
    fun `does not rewrite the weak cipher suite`() {
        // Rewriting these to something modern breaks the handshake, because the relay only
        // speaks AES-128-CBC/SHA1. This test exists to stop a well-meaning "hardening" change.
        val out = VpnProfile.inlineConfigFor(server(minimalConfig))
        assertTrue(out.contains("cipher AES-128-CBC"))
        assertTrue(out.contains("auth SHA1"))
        assertTrue(!out.contains("AES-256-GCM"))
    }

    @Test
    fun `does not duplicate a timeout the profile already sets`() {
        val withTimeout = "$minimalConfig\nconnect-timeout 30"
        val out = VpnProfile.inlineConfigFor(server(withTimeout))
        assertEquals(1, Regex("connect-timeout").findAll(out).count())
        assertTrue(out.contains("connect-timeout 30"))
    }

    @Test
    fun `ignores a commented-out timeout when deciding to append`() {
        // A '#connect-timeout' line in the preamble must not be mistaken for an active setting.
        val commented = "#connect-timeout 99\n$minimalConfig"
        val out = VpnProfile.inlineConfigFor(server(commented))
        assertTrue(out.contains("connect-timeout 10"))
    }

    @Test
    fun `rejects a profile that is not valid base64`() {
        val bad = server(minimalConfig).copy(configBase64 = "!!!!")
        assertThrows(VpnProfile.DecodeException::class.java) {
            VpnProfile.inlineConfigFor(bad)
        }
    }

    @Test
    fun `rejects a profile whose inline ca block is never closed`() {
        // Appending directives after a truncated block would splice them into the certificate.
        val truncated = "client\nremote 1.2.3.4 443\n<ca>\n-----BEGIN CERTIFICATE-----"
        assertThrows(VpnProfile.DecodeException::class.java) {
            VpnProfile.inlineConfigFor(server(truncated))
        }
    }

    @Test
    fun `rejects a profile with no certificates`() {
        assertThrows(VpnProfile.DecodeException::class.java) {
            VpnProfile.inlineConfigFor(server("client\nremote 1.2.3.4 443"))
        }
    }

    @Test
    fun `every server in the real feed produces a usable profile`() {
        val servers = VpnGateCsvParser.parse(fixture()).servers
        assertEquals(97, servers.size)
        for (s in servers) {
            val config = VpnProfile.inlineConfigFor(s)
            assertTrue("${s.hostName} lost its ca", config.contains("<ca>"))
            assertTrue("${s.hostName} lost its key", config.contains("<key>"))
            assertTrue("${s.hostName} has no remote", config.contains("remote ${s.remoteHost}"))
            assertTrue("${s.hostName} missing fast-fail", config.contains("connect-timeout"))
        }
    }
}
