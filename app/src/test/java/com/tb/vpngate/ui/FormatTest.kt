package com.tb.vpngate.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun `country codes become flag emoji`() {
        assertEquals("🇯🇵", Format.flagEmoji("JP"))
        assertEquals("🇰🇷", Format.flagEmoji("kr"))
        assertEquals("🇺🇸", Format.flagEmoji("US"))
    }

    @Test
    fun `malformed country codes fall back to a globe instead of mojibake`() {
        assertEquals("🌐", Format.flagEmoji(""))
        assertEquals("🌐", Format.flagEmoji("X"))
        assertEquals("🌐", Format.flagEmoji("USA"))
        assertEquals("🌐", Format.flagEmoji("12"))
    }

    @Test
    fun `speed scales by magnitude`() {
        assertEquals("–", Format.speed(0))
        assertEquals("120 Mbps", Format.speed(119_700_641))
        assertEquals("22.7 Mbps", Format.speed(22_748_864))
        assertEquals("500 kbps", Format.speed(500_000))
    }

    @Test
    fun `ping renders unknown as a dash rather than zero`() {
        assertEquals("–", Format.ping(null))
        assertEquals("12 ms", Format.ping(12))
    }

    @Test
    fun `uptime is reported in the largest useful unit`() {
        assertEquals("–", Format.uptime(0))
        assertEquals("5d 13h", Format.uptime(482_211_146))
        assertEquals("2h 0m", Format.uptime(7_200_000))
        assertEquals("30m", Format.uptime(1_800_000))
    }

    @Test
    fun `relative time reads naturally`() {
        val now = 1_000_000_000_000L
        assertEquals("just now", Format.relativeTime(now - 1_000, now))
        assertEquals("4 min ago", Format.relativeTime(now - 4 * 60_000, now))
        assertEquals("3 h ago", Format.relativeTime(now - 3 * 3_600_000L, now))
        assertEquals("2 d ago", Format.relativeTime(now - 2 * 86_400_000L, now))
    }

    @Test
    fun `a clock skew into the future does not produce a negative duration`() {
        val now = 1_000_000_000_000L
        assertEquals("just now", Format.relativeTime(now + 60_000, now))
    }
}
