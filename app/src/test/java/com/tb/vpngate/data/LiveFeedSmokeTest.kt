package com.tb.vpngate.data

import com.tb.vpngate.vpn.VpnProfile
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hits the real VPN Gate endpoint to confirm the live feed still has the shape this app relies
 * on. Opt-in, because unit tests must not depend on the network: it is skipped unless
 * VPNGATE_LIVE_TEST=1 is set.
 *
 *     VPNGATE_LIVE_TEST=1 ./gradlew :app:testDebugUnitTest --tests '*LiveFeedSmokeTest'
 *
 * Run it when a connect failure might be an upstream format change rather than an app bug.
 */
class LiveFeedSmokeTest {

    private val enabled = System.getenv("VPNGATE_LIVE_TEST") == "1"

    @Test
    fun `the live feed parses and yields connectable profiles`() = runTest {
        assumeTrue("set VPNGATE_LIVE_TEST=1 to run the live smoke test", enabled)

        val csv = VpnGateApi().fetchCsv()
        assertTrue("response did not look like the feed", VpnGateCsvParser.looksLikeFeed(csv))

        val result = VpnGateCsvParser.parse(csv)
        println(
            "live feed: ${result.servers.size} servers, ${result.rejectedRows} rejected, " +
                "countries=${result.servers.groupingBy { it.countryShort }.eachCount()
                    .toList().sortedByDescending { it.second }.take(8)}",
        )

        assertTrue("live feed returned no servers", result.servers.isNotEmpty())
        // A handful of rejects would be tolerable; a majority means the format moved.
        assertTrue(
            "too many rows rejected (${result.rejectedRows} vs ${result.servers.size} accepted)",
            result.rejectedRows < result.servers.size / 10,
        )

        // The real assertion: every listed server must turn into something startVPN can take.
        for (server in result.servers) {
            val config = VpnProfile.inlineConfigFor(server)
            assertTrue("${server.hostName} has no inline ca", config.contains("<ca>"))
            assertTrue("${server.hostName} has no remote", config.contains("remote "))
        }
        println("live feed: all ${result.servers.size} profiles decoded and are connectable")
    }
}
