package com.tb.vpngate.scoring

import com.tb.vpngate.data.Protocol
import com.tb.vpngate.data.VpnServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Cheap liveness check for a relay, used to stop Quick Connect handing a dead server to the
 * OpenVPN engine and waiting out its timeout.
 *
 * A meaningful share of listed relays are already gone by the time the feed is published (they
 * are volunteer machines on consumer connections), so a sub-second TCP connect is far cheaper
 * than discovering it through a failed tunnel. This only proves something is listening, not
 * that the OpenVPN handshake will succeed -- that is still the engine's verdict.
 */
class ReachabilityProbe(
    private val timeoutMillis: Int = 1_500,
) {
    /**
     * Round-trip time to open a TCP connection to the relay's endpoint, or null if it did not
     * answer in time. Also null for UDP relays, which cannot be probed this way -- null means
     * "unknown", never "dead", and callers must not treat the two as the same thing.
     */
    suspend fun probe(server: VpnServer): Long? {
        if (server.protocol != Protocol.TCP) return null
        return withContext(Dispatchers.IO) {
            val startedAt = System.nanoTime()
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(server.remoteHost, server.remotePort), timeoutMillis)
                }
            }.fold(
                onSuccess = { (System.nanoTime() - startedAt) / 1_000_000 },
                onFailure = { null },
            )
        }
    }

    /**
     * Probes [servers] concurrently and returns them ordered best-first: TCP relays that
     * answered, fastest first, then everything unproven (UDP, or no answer) in the order given.
     *
     * Unproven servers are kept rather than dropped -- discarding every UDP relay would quietly
     * remove a whole transport from Quick Connect.
     */
    suspend fun orderByReachability(servers: List<VpnServer>): List<ProbeResult> = coroutineScope {
        servers
            .map { server -> async { ProbeResult(server, probe(server)) } }
            .map { it.await() }
            .sortedWith(
                // answered-first, then by latency; unproven keep their incoming relative order
                compareBy({ it.latencyMillis == null }, { it.latencyMillis ?: Long.MAX_VALUE }),
            )
    }

    data class ProbeResult(val server: VpnServer, val latencyMillis: Long?) {
        val answered: Boolean get() = latencyMillis != null
    }
}
