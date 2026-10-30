package com.tb.vpngate.vpn

import android.content.Intent
import com.tb.vpngate.data.VpnServer
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over whatever actually runs the tunnel.
 *
 * v1 has exactly one implementation ([OpenVpnAidlEngine], which drives the installed
 * OpenVPN for Android app over its AIDL API). The interface exists so that swapping in an
 * embedded engine later does not reach into the UI or data layers.
 */
interface VpnEngine {
    val state: StateFlow<VpnState>

    /** True once the companion app is installed and the service is bound. */
    val isReady: StateFlow<Boolean>

    /**
     * Consent the engine still needs before [connect] can work, or null if it has everything.
     * Must be launched by an Activity and the result awaited; see [onConsentResult].
     */
    suspend fun nextConsentIntent(): Intent?

    suspend fun onConsentResult()

    /** Hands a complete, inlined OpenVPN profile to the engine. */
    suspend fun connect(server: VpnServer, inlineConfig: String)

    suspend fun disconnect()

    /** Reports progress during multi-candidate Quick Connect without faking a real level. */
    fun reportAttempt(server: VpnServer, index: Int, total: Int)
}
