package com.tb.vpngate.data

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class VpnGateApiTest {

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun api() = VpnGateApi(url = server.url("/api/iphone/").toString())

    /**
     * Captures the thrown exception. Not assertThrows: that takes a non-suspending lambda, and
     * starting a second runTest inside the outer one fails with "nested runTest".
     */
    private suspend fun failureOf(block: suspend () -> Unit): Throwable =
        runCatching { block() }.exceptionOrNull()
            ?: error("expected the call to fail, but it succeeded")

    private val feed = """
        *vpn_servers
        #HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64
        *
    """.trimIndent()

    @Test
    fun `returns the body when the response is the feed`() = runTest {
        server.enqueue(MockResponse(body = feed))
        assertTrue(VpnGateCsvParser.looksLikeFeed(api().fetchCsv()))
    }

    @Test
    fun `an http error is reported rather than parsed`() = runTest {
        server.enqueue(MockResponse(code = 503, body = "unavailable"))
        val error = failureOf { api().fetchCsv() }
        assertTrue("expected an IOException, got $error", error is IOException)
        assertTrue(error.message!!.contains("503"))
    }

    @Test
    fun `a 200 that is not the feed is rejected`() = runTest {
        // VPN Gate's rate-limit page and captive portals both answer 200 with HTML. Parsing it
        // would silently yield zero servers and look like "there are no servers available".
        server.enqueue(MockResponse(body = "<html><body>Login required</body></html>"))
        val error = failureOf { api().fetchCsv() }
        assertTrue("expected an IOException, got $error", error is IOException)
        assertTrue(error.message!!.contains("not the VPN Gate feed"))
    }

    @Test
    fun `an oversized body is refused instead of being read into memory`() = runTest {
        // body.string() is unbounded, so without a cap a hostile or broken endpoint could
        // exhaust memory. The real feed is ~1.3 MB; this sends 9 MB against an 8 MB limit.
        val huge = "*vpn_servers\n" + "x".repeat(9 * 1024 * 1024)
        server.enqueue(MockResponse(body = huge))
        val error = failureOf { api().fetchCsv() }
        assertTrue("expected an IOException, got $error", error is IOException)
        assertTrue(
            "unexpected message: ${error.message}",
            error.message!!.contains("limit") || error.message!!.contains("over the"),
        )
    }

    @Test
    fun `a body just under the cap is still accepted`() = runTest {
        // ~1 MB, well under the cap.
        val sized = feed + "\n" + "y".repeat(1024 * 1024)
        server.enqueue(MockResponse(body = sized))
        assertTrue(api().fetchCsv().startsWith("*vpn_servers"))
    }

    @Test
    fun `the request targets the configured endpoint`() = runTest {
        server.enqueue(MockResponse(body = feed))
        api().fetchCsv()
        assertEquals("/api/iphone/", server.takeRequest().target)
    }
}
