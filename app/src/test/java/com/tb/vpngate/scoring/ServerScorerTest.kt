package com.tb.vpngate.scoring

import com.tb.vpngate.data.Protocol
import com.tb.vpngate.data.VpnServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerScorerTest {

    private fun server(
        name: String,
        score: Long = 1_000,
        pingMs: Int? = 50,
        speedBps: Long = 10_000_000,
        uptimeMs: Long = 86_400_000,
    ) = VpnServer(
        hostName = name, ip = "1.2.3.4", score = score, pingMs = pingMs, speedBps = speedBps,
        countryLong = "Testland", countryShort = "TL", numVpnSessions = 1, uptimeMs = uptimeMs,
        totalUsers = 0, totalTraffic = 0, logType = "", operator = "", message = "",
        configBase64 = "", protocol = Protocol.TCP, remoteHost = "1.2.3.4", remotePort = 443,
    )

    @Test
    fun `higher vpngate score wins when all else is equal`() {
        val weak = server("weak", score = 100)
        val strong = server("strong", score = 10_000)
        assertEquals(listOf("strong", "weak"), ServerScorer.rank(listOf(weak, strong)).map { it.hostName })
    }

    @Test
    fun `lower ping is preferred`() {
        val slow = server("slow", pingMs = 400)
        val fast = server("fast", pingMs = 10)
        assertEquals(listOf("fast", "slow"), ServerScorer.rank(listOf(slow, fast)).map { it.hostName })
    }

    @Test
    fun `faster link is preferred`() {
        val slow = server("slow", speedBps = 1_000_000)
        val fast = server("fast", speedBps = 90_000_000)
        assertEquals(listOf("fast", "slow"), ServerScorer.rank(listOf(slow, fast)).map { it.hostName })
    }

    @Test
    fun `a missing ping is scored neutrally rather than as zero latency`() {
        // If null ping were treated as 0 ms it would beat a genuinely fast server, which would
        // let servers with no measurement dominate Quick Connect.
        val unknownPing = server("unknown", pingMs = null)
        val bestPing = server("best", pingMs = 1)
        val worstPing = server("worst", pingMs = 500)
        val ranked = ServerScorer.rank(listOf(unknownPing, bestPing, worstPing)).map { it.hostName }
        assertEquals("best", ranked.first())
        assertTrue("unknown ping should sit between the extremes", ranked.indexOf("unknown") == 1)
    }

    @Test
    fun `identical servers do not blow up on a zero-width range`() {
        val servers = List(3) { server("s$it") }
        val ranked = ServerScorer.rank(servers)
        assertEquals(3, ranked.size)
        ranked.forEach { assertTrue(ServerScorer.score(it, servers).isFinite()) }
    }

    @Test
    fun `scores stay inside the unit interval`() {
        val servers = listOf(
            server("a", score = 1, pingMs = 1, speedBps = 1, uptimeMs = 1),
            server("b", score = 9_999_999, pingMs = 999, speedBps = 999_999_999, uptimeMs = 999_999_999),
            server("c", pingMs = null),
        )
        servers.forEach {
            val s = ServerScorer.score(it, servers)
            assertTrue("score $s out of range for ${it.hostName}", s in 0.0..1.0)
        }
    }

    @Test
    fun `empty and single-element inputs are handled`() {
        assertEquals(0, ServerScorer.rank(emptyList()).size)
        assertEquals(1, ServerScorer.rank(listOf(server("only"))).size)
    }

    @Test
    fun `ranking preserves the candidate set`() {
        val servers = List(20) { server("s$it", score = (it * 37L) % 500) }
        assertEquals(
            servers.map { it.hostName }.toSet(),
            ServerScorer.rank(servers).map { it.hostName }.toSet(),
        )
    }
}
