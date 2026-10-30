package com.tb.vpngate.data

import okio.ByteString.Companion.decodeBase64

/**
 * Parses the VPN Gate public server list.
 *
 * The payload at https://www.vpngate.net/api/iphone/ is CSV, not JSON, and is shaped like:
 *
 *     *vpn_servers                      <- sentinel
 *     #HostName,IP,Score,Ping,...       <- header, '#'-prefixed
 *     public-vpn-45,219.100.37.9,...    <- data rows
 *     *                                 <- terminator
 *
 * Two things make a naive parse safe here, both verified against the live feed:
 *  - VPN Gate pre-escapes commas inside fields as underscores (e.g. the operator string
 *    "Daiyuu Nobori_ Japan. Academic Use Only."), so there are no quoted fields to handle
 *    and a plain split on ',' cannot shift columns.
 *  - Every row carries exactly 15 columns.
 *
 * Both are upstream conventions rather than guarantees, so rows that violate them are counted
 * and skipped. A single malformed row must never blank the whole list.
 */
object VpnGateCsvParser {

    private const val SENTINEL_HEADER = "*vpn_servers"
    private const val EXPECTED_COLUMNS = 15

    private const val COL_HOSTNAME = 0
    private const val COL_IP = 1
    private const val COL_SCORE = 2
    private const val COL_PING = 3
    private const val COL_SPEED = 4
    private const val COL_COUNTRY_LONG = 5
    private const val COL_COUNTRY_SHORT = 6
    private const val COL_NUM_SESSIONS = 7
    private const val COL_UPTIME = 8
    private const val COL_TOTAL_USERS = 9
    private const val COL_TOTAL_TRAFFIC = 10
    private const val COL_LOG_TYPE = 11
    private const val COL_OPERATOR = 12
    private const val COL_MESSAGE = 13
    private const val COL_CONFIG_B64 = 14

    private val REMOTE_RE = Regex("""^\s*remote\s+(\S+)\s+(\d+)""", RegexOption.MULTILINE)
    private val PROTO_RE = Regex("""^\s*proto\s+(tcp|udp)""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))

    data class Result(
        val servers: List<VpnServer>,
        /** Rows present in the feed that could not be parsed. Surfaced for diagnostics only. */
        val rejectedRows: Int,
    )

    /** True if [body] actually looks like the VPN Gate feed. */
    fun looksLikeFeed(body: String): Boolean =
        body.lineSequence().firstOrNull()?.trim()?.startsWith(SENTINEL_HEADER) == true

    fun parse(csv: String): Result {
        val servers = ArrayList<VpnServer>()
        var rejected = 0

        for (raw in csv.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> continue
                // "*" terminates the feed; "*vpn_servers" opens it. Both are sentinels.
                line.startsWith("*") -> continue
                line.startsWith("#") -> continue
            }
            val server = parseRow(line)
            if (server == null) rejected++ else servers.add(server)
        }
        return Result(servers, rejected)
    }

    private fun parseRow(line: String): VpnServer? {
        val f = line.split(',')
        if (f.size != EXPECTED_COLUMNS) return null

        val configBase64 = f[COL_CONFIG_B64].trim()
        if (configBase64.isEmpty()) return null

        // The profile has to be decodable now: protocol and remote endpoint come from it, and a
        // server whose profile we cannot read is not connectable, so it does not belong in the list.
        val config = configBase64.decodeBase64()?.utf8() ?: return null
        val remote = REMOTE_RE.find(config) ?: return null
        val port = remote.groupValues[2].toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        val protocol = when (PROTO_RE.find(config)?.groupValues?.get(1)?.lowercase()) {
            "udp" -> Protocol.UDP
            // VPN Gate always states the proto explicitly; OpenVPN's own default is udp, so
            // match that rather than guessing tcp if the directive ever goes missing.
            "tcp" -> Protocol.TCP
            else -> Protocol.UDP
        }

        val hostName = f[COL_HOSTNAME].trim()
        val ip = f[COL_IP].trim()
        if (hostName.isEmpty() || ip.isEmpty()) return null

        val countryShort = f[COL_COUNTRY_SHORT].trim().uppercase()

        return VpnServer(
            hostName = hostName,
            ip = ip,
            score = f[COL_SCORE].toLongOrDefault(0),
            pingMs = f[COL_PING].toIntOrNullLenient(),
            speedBps = f[COL_SPEED].toLongOrDefault(0),
            countryLong = f[COL_COUNTRY_LONG].trim(),
            countryShort = countryShort,
            numVpnSessions = f[COL_NUM_SESSIONS].toIntOrNullLenient() ?: 0,
            uptimeMs = f[COL_UPTIME].toLongOrDefault(0),
            totalUsers = f[COL_TOTAL_USERS].toLongOrDefault(0),
            totalTraffic = f[COL_TOTAL_TRAFFIC].toLongOrDefault(0),
            logType = f[COL_LOG_TYPE].trim(),
            operator = f[COL_OPERATOR].trim(),
            message = f[COL_MESSAGE].trim(),
            configBase64 = configBase64,
            protocol = protocol,
            remoteHost = remote.groupValues[1],
            remotePort = port,
        )
    }

    /** Upstream writes "-" or "" for unknown numerics; both mean "no value", not zero. */
    private fun String.toIntOrNullLenient(): Int? = trim().takeIf { it.isNotEmpty() && it != "-" }?.toIntOrNull()

    private fun String.toLongOrDefault(default: Long): Long =
        trim().takeIf { it.isNotEmpty() && it != "-" }?.toLongOrNull() ?: default
}
