package com.tb.vpngate.vpn

import com.tb.vpngate.data.VpnServer

/**
 * What the tunnel is doing, as far as this app can tell.
 *
 * [server] is the relay *this app* last asked for. It is null when a tunnel is up that we did
 * not start -- OpenVPN for Android is a shared, user-facing app and its status stream reports
 * whatever it is doing, including sessions started from its own UI or another client. Labelling
 * a foreign tunnel with our own last selection would be a lie, so the UI distinguishes them.
 */
sealed interface VpnState {
    data object Disconnected : VpnState

    /** Engine bound and a profile handed over, but no tunnel yet. */
    data class Connecting(
        val server: VpnServer?,
        val detail: String? = null,
        /**
         * Set during Quick Connect failover. Carried as numbers rather than a formatted string
         * so the UI owns the wording and it can be translated.
         */
        val attempt: Attempt? = null,
    ) : VpnState

    /** Position in a Quick Connect failover sequence, 1-based. */
    data class Attempt(val index: Int, val total: Int)

    data class Connected(val server: VpnServer?) : VpnState

    data object Paused : VpnState

    data object NoNetwork : VpnState

    data class AuthFailed(val server: VpnServer?, val detail: String?) : VpnState

    data class Error(val message: String) : VpnState

    /** The companion app is not installed, so nothing can be started at all. */
    data object EngineMissing : VpnState

    /**
     * The companion app is installed but failed its identity check, so no profile was sent.
     * Distinct from [Error] because the user needs a specific instruction, not a retry.
     */
    data class EngineUntrusted(val reason: String) : VpnState
}

/** Maps an ics-openvpn ConnectionStatus enum name onto [VpnState]. */
internal fun levelToState(
    level: String?,
    message: String?,
    intendedServer: VpnServer?,
): VpnState = when (level) {
    "LEVEL_CONNECTED" -> VpnState.Connected(intendedServer)
    "LEVEL_START",
    "LEVEL_CONNECTING_NO_SERVER_REPLY_YET",
    "LEVEL_CONNECTING_SERVER_REPLIED",
    "LEVEL_WAITING_FOR_USER_INPUT",
    -> VpnState.Connecting(intendedServer, detail = message)
    "LEVEL_VPNPAUSED" -> VpnState.Paused
    "LEVEL_NONETWORK" -> VpnState.NoNetwork
    "LEVEL_AUTH_FAILED" -> VpnState.AuthFailed(intendedServer, message)
    "LEVEL_NOTCONNECTED" -> VpnState.Disconnected
    // UNKNOWN_LEVEL and anything added upstream later: don't invent a state, stay put.
    else -> VpnState.Disconnected
}
