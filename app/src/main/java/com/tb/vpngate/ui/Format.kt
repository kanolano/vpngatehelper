package com.tb.vpngate.ui

import com.tb.vpngate.data.VpnServer
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Display formatting. Kept out of the data layer so [VpnServer] stays free of presentation
 * concerns, and pure so it can be exercised without a device.
 */
object Format {

    /**
     * ISO-3166 alpha-2 to its flag emoji, by offsetting each letter into the Unicode regional
     * indicator block. Avoids shipping ~200 flag assets for a list that is mostly two countries.
     * Returns a globe for anything that is not two A-Z letters.
     */
    fun flagEmoji(countryCode: String): String {
        val cc = countryCode.trim().uppercase()
        if (cc.length != 2 || cc.any { it !in 'A'..'Z' }) return "🌐"
        val base = 0x1F1E6
        return buildString {
            cc.forEach { appendCodePoint(base + (it - 'A')) }
        }
    }

    fun speed(bps: Long): String {
        val mbps = bps / 1_000_000.0
        return when {
            bps <= 0L -> "–"
            mbps >= 100 -> "${mbps.roundToInt()} Mbps"
            mbps >= 10 -> String.format(Locale.US, "%.1f Mbps", mbps)
            mbps >= 1 -> String.format(Locale.US, "%.1f Mbps", mbps)
            else -> "${(bps / 1_000.0).roundToInt()} kbps"
        }
    }

    fun ping(pingMs: Int?): String = pingMs?.let { "$it ms" } ?: "–"

    /** Upstream reports uptime in milliseconds. */
    fun uptime(uptimeMillis: Long): String {
        if (uptimeMillis <= 0L) return "–"
        val totalMinutes = uptimeMillis / 60_000
        val days = totalMinutes / 1_440
        val hours = (totalMinutes % 1_440) / 60
        val minutes = totalMinutes % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }

    /** "just now" / "4 min ago" / "3 h ago" / "2 d ago". */
    fun relativeTime(thenMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val deltaSeconds = ((nowMillis - thenMillis) / 1000).coerceAtLeast(0)
        val minutes = deltaSeconds / 60
        val hours = minutes / 60
        val days = hours / 24
        return when {
            deltaSeconds < 45 -> "just now"
            minutes < 60 -> "$minutes min ago"
            hours < 24 -> "$hours h ago"
            else -> "$days d ago"
        }
    }
}
