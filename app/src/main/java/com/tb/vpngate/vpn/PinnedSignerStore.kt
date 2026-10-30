package com.tb.vpngate.vpn

/**
 * Remembers which signing certificate the companion app had when we first bound to it.
 *
 * The app hands complete VPN profiles to whatever is installed under the package name
 * `de.blinkt.openvpn`. An impostor holding that name could report a healthy tunnel while
 * routing traffic wherever it liked, so the signer is checked before any profile is sent.
 */
interface PinnedSignerStore {
    suspend fun pinnedSigner(): String?
    suspend fun pinSigner(sha256: String)
}
