package com.tb.vpngate.scoring

import com.tb.vpngate.data.VpnServer

/**
 * Ranks relays for Quick Connect.
 *
 * VPN Gate publishes its own `Score`, which already blends throughput and stability and is the
 * most informative single signal, so it carries the most weight. The remaining metrics break
 * ties and stop a server with a huge historical score but currently terrible latency from
 * winning outright.
 *
 * Every metric is min-max normalised across the candidate set rather than scaled against fixed
 * constants, because the absolute ranges in the feed vary by orders of magnitude between
 * refreshes. Pure function, no I/O: liveness is [ReachabilityProbe]'s job.
 */
object ServerScorer {

    private const val W_SCORE = 0.50
    private const val W_SPEED = 0.25
    private const val W_PING = 0.15
    private const val W_UPTIME = 0.10

    /** Servers that do not report a ping are scored neutrally on that axis, not penalised. */
    private const val NEUTRAL = 0.5

    fun rank(servers: List<VpnServer>): List<VpnServer> {
        // Normalisers are derived from the whole candidate set, so build them once. Computing
        // them inside the comparator would rebuild them on every comparison.
        val normalisers = Normalisers.of(servers)
        return servers
            .map { it to scoreOf(it, normalisers) }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /** Composite desirability in 0..1. Exposed for tests and for debugging the ordering. */
    fun score(server: VpnServer, among: List<VpnServer>): Double =
        scoreOf(server, Normalisers.of(among))

    private fun scoreOf(s: VpnServer, n: Normalisers): Double =
        W_SCORE * n.score(s.score.toDouble()) +
            W_SPEED * n.speed(s.speedBps.toDouble()) +
            // Lower ping is better, hence the inversion.
            W_PING * (s.pingMs?.let { 1.0 - n.ping(it.toDouble()) } ?: NEUTRAL) +
            W_UPTIME * n.uptime(s.uptimeMs.toDouble())

    private class Normalisers(
        val score: (Double) -> Double,
        val speed: (Double) -> Double,
        val ping: (Double) -> Double,
        val uptime: (Double) -> Double,
    ) {
        companion object {
            fun of(servers: List<VpnServer>) = Normalisers(
                score = minMax(servers.map { it.score.toDouble() }),
                speed = minMax(servers.map { it.speedBps.toDouble() }),
                ping = minMax(servers.mapNotNull { it.pingMs?.toDouble() }),
                uptime = minMax(servers.map { it.uptimeMs.toDouble() }),
            )

            /**
             * Maps [values]' observed range onto 0..1. When every value is identical (or there
             * are none) the axis carries no information, so it collapses to neutral instead of
             * dividing by zero.
             */
            private fun minMax(values: List<Double>): (Double) -> Double {
                if (values.isEmpty()) return { NEUTRAL }
                val min = values.min()
                val max = values.max()
                if (max <= min) return { NEUTRAL }
                val span = max - min
                return { v -> ((v - min) / span).coerceIn(0.0, 1.0) }
            }
        }
    }
}
