package com.tb.vpngate.data

/** Transport the relay's OpenVPN profile uses. VPN Gate gives exactly one per server. */
enum class Protocol { TCP, UDP }

/**
 * One VPN Gate relay, as listed by the public API.
 *
 * [protocol], [remoteHost] and [remotePort] are not CSV columns -- they are lifted out of the
 * decoded OpenVPN profile at parse time so the UI and the reachability probe never have to
 * base64-decode a ~10 KB profile again. [configBase64] is kept undecoded because it is only
 * needed at the moment we actually connect.
 */
data class VpnServer(
    val hostName: String,
    val ip: String,
    val score: Long,
    /** Null when upstream reports the ping as blank or "-"; it is not always present. */
    val pingMs: Int?,
    val speedBps: Long,
    val countryLong: String,
    /** ISO-3166 alpha-2, e.g. "JP". */
    val countryShort: String,
    val numVpnSessions: Int,
    val uptimeMs: Long,
    val totalUsers: Long,
    val totalTraffic: Long,
    val logType: String,
    val operator: String,
    val message: String,
    val configBase64: String,
    val protocol: Protocol,
    val remoteHost: String,
    val remotePort: Int,
) {
    /**
     * Stable identity for list keys. HostName alone is not reliably unique across the feed, and
     * a single IP can appear on several ports, so all three are needed.
     */
    val id: String get() = "$hostName|$ip|$remotePort"

    val speedMbps: Double get() = speedBps / 1_000_000.0
}
