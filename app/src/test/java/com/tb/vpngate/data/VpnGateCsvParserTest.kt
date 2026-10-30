package com.tb.vpngate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import okio.ByteString.Companion.decodeBase64

/**
 * Parser tests run against a real captured feed (app/src/test/resources/vpngate_sample.csv),
 * so the structural assertions describe the actual upstream format rather than a mock of it.
 *
 * The fixture is a snapshot: counts below are properties of that file, not of the live feed.
 * If you re-capture it, these numbers are expected to change -- update them from the new file.
 */
class VpnGateCsvParserTest {

    private fun fixture(): String =
        requireNotNull(javaClass.getResourceAsStream("/vpngate_sample.csv")) {
            "fixture vpngate_sample.csv missing from test resources"
        }.bufferedReader().readText()

    /** Config text with comment and blank lines stripped, so tests assert on real directives. */
    private fun activeDirectives(config: String): String =
        config.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith(";") }
            .joinToString("\n")

    @Test
    fun `parses every row of the captured feed`() {
        val result = VpnGateCsvParser.parse(fixture())
        assertEquals("all 97 data rows should parse", 97, result.servers.size)
        assertEquals("no row should be rejected", 0, result.rejectedRows)
    }

    @Test
    fun `skips the opening and closing sentinel lines and the header`() {
        val result = VpnGateCsvParser.parse(fixture())
        // If a sentinel or the '#' header leaked through it would appear as a bogus server.
        assertTrue(result.servers.none { it.hostName.startsWith("*") })
        assertTrue(result.servers.none { it.hostName.startsWith("#") })
        assertTrue(result.servers.none { it.hostName == "HostName" })
    }

    @Test
    fun `lifts protocol out of the decoded profile`() {
        val result = VpnGateCsvParser.parse(fixture())
        val tcp = result.servers.count { it.protocol == Protocol.TCP }
        val udp = result.servers.count { it.protocol == Protocol.UDP }
        assertEquals("tcp count in fixture", 85, tcp)
        assertEquals("udp count in fixture", 12, udp)
        assertEquals(result.servers.size, tcp + udp)
    }

    @Test
    fun `every server exposes a usable remote endpoint`() {
        val result = VpnGateCsvParser.parse(fixture())
        for (s in result.servers) {
            assertTrue("blank remoteHost for ${s.hostName}", s.remoteHost.isNotBlank())
            assertTrue("port ${s.remotePort} out of range for ${s.hostName}", s.remotePort in 1..65535)
        }
    }

    @Test
    fun `every profile is self-contained with inline ca cert and key`() {
        // This is what makes startVPN(inlineconfig) viable with no assembly step; if upstream
        // ever stopped inlining credentials, connecting would break and this test should catch it.
        val result = VpnGateCsvParser.parse(fixture())
        for (s in result.servers) {
            val config = requireNotNull(s.configBase64.decodeBase64()?.utf8())
            assertTrue("${s.hostName} missing <ca>", config.contains("<ca>"))
            assertTrue("${s.hostName} missing <cert>", config.contains("<cert>"))
            assertTrue("${s.hostName} missing <key>", config.contains("<key>"))
            // Must check ACTIVE directives, not a substring: every profile ships a commented
            // "#auth-user-pass" line in its preamble, which a plain contains() would match.
            assertTrue(
                "${s.hostName} unexpectedly needs credentials",
                "auth-user-pass" !in activeDirectives(config),
            )
        }
    }

    @Test
    fun `underscore-escaped operator fields do not shift columns`() {
        // VPN Gate replaces commas inside fields with underscores. The canonical example is
        // "Daiyuu Nobori_ Japan. Academic Use Only." -- if that escaping ever stopped, country
        // codes would slide into the wrong column, so assert the columns still line up.
        val result = VpnGateCsvParser.parse(fixture())
        val withEscapes = result.servers.filter { it.operator.contains('_') }
        assertTrue("fixture should contain underscore-escaped operators", withEscapes.isNotEmpty())
        for (s in result.servers) {
            assertEquals(
                "country code should be ISO alpha-2, got '${s.countryShort}' for ${s.hostName}",
                2, s.countryShort.length,
            )
            assertTrue(s.countryShort.all { it in 'A'..'Z' })
        }
    }

    @Test
    fun `country distribution matches the captured feed`() {
        val result = VpnGateCsvParser.parse(fixture())
        val byCountry = result.servers.groupingBy { it.countryShort }.eachCount()
        assertEquals(40, byCountry["JP"])
        assertEquals(33, byCountry["KR"])
    }

    @Test
    fun `ids are unique so list keys are stable`() {
        val result = VpnGateCsvParser.parse(fixture())
        assertEquals(result.servers.size, result.servers.map { it.id }.toSet().size)
    }

    @Test
    fun `profiles pin the weak cipher suite VPN Gate requires`() {
        // Documents reality rather than endorsing it: VPN Gate's servers dictate
        // AES-128-CBC/SHA1. The app must pass these through untouched -- rewriting them to
        // something modern breaks the handshake. If upstream ever upgrades, this test fails
        // and the comment in VpnGateCsvParser/OpenVpnAidlEngine should be revisited.
        val result = VpnGateCsvParser.parse(fixture())
        for (s in result.servers) {
            val active = activeDirectives(requireNotNull(s.configBase64.decodeBase64()?.utf8()))
            assertTrue("${s.hostName} cipher changed", active.contains("cipher AES-128-CBC"))
            assertTrue("${s.hostName} auth changed", active.contains("auth SHA1"))
            // Exactly one remote, so there is no ambiguity about which endpoint we probe.
            assertEquals(
                "${s.hostName} should declare exactly one remote",
                1, active.lines().count { it.startsWith("remote ") },
            )
        }
    }

    // --- hostile / malformed input -------------------------------------------------------

    private val goodRow: String by lazy {
        fixture().lineSequence().first { !it.startsWith("*") && !it.startsWith("#") && it.isNotBlank() }
    }

    private fun feed(vararg rows: String) = buildString {
        appendLine("*vpn_servers")
        appendLine("#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64")
        rows.forEach { appendLine(it) }
        appendLine("*")
    }

    @Test
    fun `one malformed row never blanks the list`() {
        val result = VpnGateCsvParser.parse(feed("truncated,row,with,too,few", goodRow))
        assertEquals(1, result.servers.size)
        assertEquals(1, result.rejectedRows)
    }

    @Test
    fun `rejects rows with the wrong column count`() {
        val fourteen = goodRow.split(',').dropLast(1).joinToString(",")
        val sixteen = "$goodRow,extra"
        val result = VpnGateCsvParser.parse(feed(fourteen, sixteen))
        assertEquals(0, result.servers.size)
        assertEquals(2, result.rejectedRows)
    }

    @Test
    fun `rejects a row whose config is not valid base64`() {
        val f = goodRow.split(',').toMutableList()
        f[14] = "!!!not base64!!!"
        val result = VpnGateCsvParser.parse(feed(f.joinToString(",")))
        assertEquals(0, result.servers.size)
        assertEquals(1, result.rejectedRows)
    }

    @Test
    fun `rejects a row with an empty config`() {
        val f = goodRow.split(',').toMutableList()
        f[14] = ""
        val result = VpnGateCsvParser.parse(feed(f.joinToString(",")))
        assertEquals(0, result.servers.size)
        assertEquals(1, result.rejectedRows)
    }

    @Test
    fun `treats a blank ping as unknown rather than zero`() {
        val f = goodRow.split(',').toMutableList()
        f[3] = ""
        val blank = VpnGateCsvParser.parse(feed(f.joinToString(",")))
        assertEquals(1, blank.servers.size)
        assertNull("blank ping must be null, not 0", blank.servers.single().pingMs)

        f[3] = "-"
        val dash = VpnGateCsvParser.parse(feed(f.joinToString(",")))
        assertNull("'-' ping must be null, not 0", dash.servers.single().pingMs)
    }

    @Test
    fun `tolerates CRLF line endings`() {
        val result = VpnGateCsvParser.parse(feed(goodRow).replace("\n", "\r\n"))
        assertEquals(1, result.servers.size)
        assertEquals(0, result.rejectedRows)
    }

    @Test
    fun `empty and garbage input yield no servers instead of throwing`() {
        assertEquals(0, VpnGateCsvParser.parse("").servers.size)
        assertEquals(0, VpnGateCsvParser.parse("<html>rate limited</html>").servers.size)
    }

    @Test
    fun `looksLikeFeed distinguishes the feed from an interception page`() {
        assertTrue(VpnGateCsvParser.looksLikeFeed(fixture()))
        assertTrue(!VpnGateCsvParser.looksLikeFeed("<html><body>Login required</body></html>"))
        assertTrue(!VpnGateCsvParser.looksLikeFeed(""))
    }

    @Test
    fun `parses the known first row of the fixture exactly`() {
        val s = VpnGateCsvParser.parse(fixture()).servers.first()
        assertEquals("public-vpn-45", s.hostName)
        assertEquals("219.100.37.9", s.ip)
        assertEquals("JP", s.countryShort)
        assertEquals("Japan", s.countryLong)
        assertEquals(Protocol.TCP, s.protocol)
        assertEquals("219.100.37.9", s.remoteHost)
        assertEquals(443, s.remotePort)
        assertNotNull(s.pingMs)
    }
}
