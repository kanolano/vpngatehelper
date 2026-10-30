package com.tb.vpngate.ui

import android.content.Intent
import androidx.annotation.StringRes
import com.tb.vpngate.R
import com.tb.vpngate.data.Protocol
import com.tb.vpngate.data.VpnServer
import com.tb.vpngate.vpn.VpnState

enum class SortKey(@StringRes val labelRes: Int) {
    SCORE(R.string.sort_score),
    PING(R.string.sort_ping),
    SPEED(R.string.sort_speed),
    UPTIME(R.string.sort_uptime),
}

data class CountryOption(val code: String, val name: String, val count: Int)

data class ServerListUiState(
    /** Already filtered and sorted; the list is rendered straight from this. */
    val servers: List<VpnServer> = emptyList(),
    val totalServers: Int = 0,
    val countries: List<CountryOption> = emptyList(),
    val countryFilter: String? = null,
    val protocolFilter: Protocol? = null,
    val sortKey: SortKey = SortKey.SCORE,
    val lastUpdatedMillis: Long? = null,
    val isStale: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val vpnState: VpnState = VpnState.Disconnected,
    val showNotice: Boolean = false,
    val isQuickConnecting: Boolean = false,
) {
    val hasServers: Boolean get() = servers.isNotEmpty()
    val isFiltered: Boolean get() = countryFilter != null || protocolFilter != null
}

/** One-shot things the Activity must do, since only it can launch intents for a result. */
sealed interface UiEffect {
    /** A consent dialog from OpenVPN for Android or the system; the result must come back. */
    data class LaunchConsent(val intent: Intent) : UiEffect

    /** Fire-and-forget, e.g. opening a store listing. */
    data class LaunchIntent(val intent: Intent) : UiEffect

    /**
     * A localised message. The ViewModel passes a resource plus arguments rather than finished
     * prose, so no layer below the UI has to know the user's language.
     *
     * When [quantity] is set, [resId] is read as a plurals resource.
     */
    data class Message(
        val resId: Int,
        val args: List<Any> = emptyList(),
        val quantity: Int? = null,
    ) : UiEffect
}
