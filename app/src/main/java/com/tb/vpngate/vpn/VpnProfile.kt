package com.tb.vpngate.vpn

import com.tb.vpngate.data.VpnServer
import okio.ByteString.Companion.decodeBase64

/**
 * Turns a listed relay into the inline OpenVPN profile handed to `startVPN`.
 *
 * The decoded profile is passed through essentially untouched, on purpose. VPN Gate's servers
 * dictate AES-128-CBC with SHA1 auth, and there is no `remote-cert-tls server` or
 * `verify-x509-name` directive: rewriting the cipher suite or adding certificate checks the
 * relays cannot satisfy breaks the handshake rather than hardening it. The weak crypto is a
 * property of the service and is surfaced to the user in the UI instead.
 *
 * The only addition is a fast-fail timeout, so Quick Connect's failover can move on from a dead
 * relay in seconds rather than sitting through OpenVPN's default retry behaviour.
 */
object VpnProfile {

    /** Appended only when the profile does not already set them. */
    private const val CONNECT_TIMEOUT_SECONDS = 10
    private const val CONNECT_RETRY_LIMIT = 1

    class DecodeException(message: String) : Exception(message)

    @Throws(DecodeException::class)
    fun inlineConfigFor(server: VpnServer): String {
        val decoded = server.configBase64.decodeBase64()?.utf8()
            ?: throw DecodeException("Profile for ${server.hostName} is not valid base64")
        // Both tags, not just the opening one: appending directives after a truncated inline
        // block would splice them into the certificate and corrupt the whole profile.
        if (!decoded.contains("<ca>") || !decoded.contains("</ca>")) {
            throw DecodeException("Profile for ${server.hostName} has no usable <ca> block")
        }
        return withFastFailTimeouts(decoded)
    }

    private fun withFastFailTimeouts(config: String): String {
        val directives = config.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith(";") }
            .toList()

        val additions = buildList {
            if (directives.none { it.startsWith("connect-timeout") }) {
                add("connect-timeout $CONNECT_TIMEOUT_SECONDS")
            }
            if (directives.none { it.startsWith("connect-retry-max") }) {
                add("connect-retry-max $CONNECT_RETRY_LIMIT")
            }
        }
        if (additions.isEmpty()) return config

        return buildString {
            append(config)
            if (!config.endsWith("\n")) append('\n')
            append("# Added by VpnGateClient so a dead relay fails fast enough to fail over.\n")
            additions.forEach { append(it).append('\n') }
        }
    }
}
