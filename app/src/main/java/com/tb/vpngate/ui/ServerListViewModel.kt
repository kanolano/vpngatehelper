package com.tb.vpngate.ui

import com.tb.vpngate.R
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.tb.vpngate.data.Protocol
import com.tb.vpngate.data.ServerListState
import com.tb.vpngate.data.ServerRepository
import com.tb.vpngate.data.VpnServer
import com.tb.vpngate.scoring.ReachabilityProbe
import com.tb.vpngate.scoring.ServerScorer
import com.tb.vpngate.vpn.OpenVpnAidlEngine
import com.tb.vpngate.vpn.OpenVpnInstallCheck
import com.tb.vpngate.vpn.VpnProfile
import com.tb.vpngate.vpn.VpnState
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns list presentation (filter/sort), the connect/consent handshake, and Quick Connect.
 *
 * Filtering and sorting are pure transforms over the cached list, so changing a filter never
 * triggers a refetch.
 */
class ServerListViewModel(
    private val repository: ServerRepository,
    private val engine: OpenVpnAidlEngine,
    private val prefs: AppPrefs,
    private val savedState: SavedStateHandle,
    private val probe: ReachabilityProbe = ReachabilityProbe(),
) : ViewModel() {

    private data class Controls(
        val countryFilter: String? = null,
        val protocolFilter: Protocol? = null,
        val sortKey: SortKey = SortKey.SCORE,
        val isQuickConnecting: Boolean = false,
    )

    // Restored after process death, so a user who switches away mid-browse comes back to the
    // list they had rather than a silently reset one.
    private val controls = MutableStateFlow(
        Controls(
            countryFilter = savedState[KEY_COUNTRY],
            protocolFilter = savedState.get<String>(KEY_PROTOCOL)
                ?.let { runCatching { Protocol.valueOf(it) }.getOrNull() },
            sortKey = savedState.get<String>(KEY_SORT)
                ?.let { runCatching { SortKey.valueOf(it) }.getOrNull() }
                ?: SortKey.SCORE,
        ),
    )

    private val effectChannel = Channel<UiEffect>(Channel.BUFFERED)
    val effects: Flow<UiEffect> = effectChannel.receiveAsFlow()

    /**
     * Set when a connect attempt was interrupted by a consent dialog, and replayed once the
     * user answers. Re-entering the handshake picks up the next consent in the chain.
     */
    private var pendingAction: (suspend () -> Unit)? = null

    /** Guards against a denied dialog re-prompting forever. */
    private var consentAttempts = 0

    private var quickConnectJob: Job? = null

    private var lastConnectRequestAt = 0L

    /**
     * The projection below re-runs on every upstream emission, including each isRefreshing
     * toggle, so filtering, scoring ~95 servers and regrouping countries would be repeated for
     * state changes that cannot affect any of them. These two caches hold one entry each.
     *
     * The source list is compared by identity, not equality: the repository swaps in a whole new
     * list on refresh, and a deep comparison of ~95 servers would cost more than it saves.
     */
    private class ListingKey(
        val servers: List<VpnServer>,
        val country: String?,
        val protocol: Protocol?,
        val sort: SortKey,
    ) {
        fun sameAs(other: ListingKey): Boolean =
            servers === other.servers &&
                country == other.country &&
                protocol == other.protocol &&
                sort == other.sort
    }

    private var listingKey: ListingKey? = null
    private var listingValue: List<VpnServer> = emptyList()

    private var countryKey: List<VpnServer>? = null
    private var countryValue: List<CountryOption> = emptyList()

    private fun listing(key: ListingKey): List<VpnServer> {
        listingKey?.let { if (it.sameAs(key)) return listingValue }

        val filtered = key.servers
            .filter { key.country == null || it.countryShort == key.country }
            .filter { key.protocol == null || it.protocol == key.protocol }

        val sorted = when (key.sort) {
            SortKey.SCORE -> ServerScorer.rank(filtered)
            // Servers with no reported ping sort last rather than first, which is what a plain
            // ascending sort on a null-as-zero value would do.
            SortKey.PING -> filtered.sortedWith(compareBy(nullsLast<Int>()) { it.pingMs })
            SortKey.SPEED -> filtered.sortedByDescending { it.speedBps }
            SortKey.UPTIME -> filtered.sortedByDescending { it.uptimeMs }
        }
        listingKey = key
        listingValue = sorted
        return sorted
    }

    private fun countryOptions(servers: List<VpnServer>): List<CountryOption> {
        if (countryKey === servers) return countryValue
        val options = servers.groupBy { it.countryShort }
            .map { (code, group) ->
                CountryOption(code = code, name = group.first().countryLong, count = group.size)
            }
            .sortedWith(compareByDescending<CountryOption> { it.count }.thenBy { it.name })
        countryKey = servers
        countryValue = options
        return options
    }

    val uiState: StateFlow<ServerListUiState> =
        combine(
            repository.state,
            engine.state,
            controls,
            prefs.noticeAcknowledged,
        ) { repoState, vpnState, controls, noticeAcknowledged ->
            project(repoState, vpnState, controls, noticeAcknowledged)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ServerListUiState(),
        )

    init {
        viewModelScope.launch {
            repository.loadCache()
            repository.refresh()
        }
        viewModelScope.launch {
            // Bind eagerly so the connection bar can report a missing or untrusted companion
            // app before the user ever taps connect.
            if (engine.bind()) engine.registerStatus()
        }
    }

    // --- list presentation ----------------------------------------------------------------

    private fun project(
        repoState: ServerListState,
        vpnState: VpnState,
        c: Controls,
        noticeAcknowledged: Boolean,
    ): ServerListUiState {
        val sorted = listing(
            ListingKey(repoState.servers, c.countryFilter, c.protocolFilter, c.sortKey),
        )
        val countries = countryOptions(repoState.servers)

        return ServerListUiState(
            servers = sorted,
            totalServers = repoState.servers.size,
            countries = countries,
            countryFilter = c.countryFilter,
            protocolFilter = c.protocolFilter,
            sortKey = c.sortKey,
            lastUpdatedMillis = repoState.lastUpdatedMillis,
            isStale = repoState.isStale,
            isRefreshing = repoState.isRefreshing,
            error = repoState.error,
            vpnState = vpnState,
            showNotice = !noticeAcknowledged,
            isQuickConnecting = c.isQuickConnecting,
        )
    }

    fun setCountryFilter(code: String?) {
        savedState[KEY_COUNTRY] = code
        controls.update { it.copy(countryFilter = code) }
    }

    fun setProtocolFilter(protocol: Protocol?) {
        savedState[KEY_PROTOCOL] = protocol?.name
        controls.update { it.copy(protocolFilter = protocol) }
    }

    fun setSortKey(key: SortKey) {
        savedState[KEY_SORT] = key.name
        controls.update { it.copy(sortKey = key) }
    }

    fun refresh() {
        viewModelScope.launch { repository.refresh() }
    }

    fun acknowledgeNotice() {
        viewModelScope.launch { prefs.acknowledgeNotice() }
    }

    // --- connecting -----------------------------------------------------------------------

    fun connect(server: VpnServer) {
        // A double-tap would otherwise hand the engine two profiles in quick succession.
        val now = System.currentTimeMillis()
        if (now - lastConnectRequestAt < CONNECT_DEBOUNCE_MILLIS) return
        lastConnectRequestAt = now

        cancelQuickConnect()
        viewModelScope.launch {
            withConsent {
                val config = try {
                    VpnProfile.inlineConfigFor(server)
                } catch (e: VpnProfile.DecodeException) {
                    effectChannel.send(
                        UiEffect.Message(R.string.msg_unusable_profile, listOf(e.message.orEmpty())),
                    )
                    return@withConsent
                }
                engine.connect(server, config)
            }
        }
    }

    fun disconnect() {
        cancelQuickConnect()
        viewModelScope.launch { engine.disconnect() }
    }

    fun quickConnect() {
        cancelQuickConnect()
        // Projected fresh from the repository rather than read off uiState: that flow stops
        // collecting when nothing is subscribed, so its value can lag the real list.
        val candidates = currentlyVisibleServers()
        if (candidates.isEmpty()) {
            viewModelScope.launch { effectChannel.send(UiEffect.Message(R.string.msg_no_servers)) }
            return
        }
        quickConnectJob = viewModelScope.launch {
            controls.update { it.copy(isQuickConnecting = true) }
            try {
                withConsent { attemptBestOf(candidates) }
            } finally {
                controls.update { it.copy(isQuickConnecting = false) }
            }
        }
    }

    /** The same filtered set the list is showing, computed without depending on a subscriber. */
    private fun currentlyVisibleServers(): List<VpnServer> {
        val c = controls.value
        return repository.state.value.servers
            .filter { c.countryFilter == null || it.countryShort == c.countryFilter }
            .filter { c.protocolFilter == null || it.protocol == c.protocolFilter }
    }

    private fun cancelQuickConnect() {
        quickConnectJob?.cancel()
        quickConnectJob = null
        controls.update { it.copy(isQuickConnecting = false) }
    }

    /**
     * Scores the candidates, TCP-probes the strongest few to weed out relays that are already
     * gone, then tries them in turn until one tunnel actually comes up.
     */
    private suspend fun attemptBestOf(candidates: List<VpnServer>) {
        val pool = ServerScorer.rank(candidates).take(PROBE_POOL)
        val ordered = probe.orderByReachability(pool).map { it.server }.take(MAX_ATTEMPTS)

        for ((index, server) in ordered.withIndex()) {
            engine.reportAttempt(server, index + 1, ordered.size)
            val config = try {
                VpnProfile.inlineConfigFor(server)
            } catch (_: VpnProfile.DecodeException) {
                continue
            }
            engine.connect(server, config)
            if (awaitConnected()) return
        }
        effectChannel.send(
            UiEffect.Message(
                R.plurals.msg_none_connected,
                listOf(ordered.size),
                quantity = ordered.size,
            ),
        )
    }

    /** Waits for the engine to settle after a connect attempt. True only if a tunnel came up. */
    private suspend fun awaitConnected(): Boolean {
        val startedAt = System.currentTimeMillis()
        val settled = withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) {
            engine.state.first { state ->
                when (state) {
                    is VpnState.Connected -> true
                    is VpnState.AuthFailed,
                    is VpnState.Error,
                    is VpnState.EngineMissing,
                    is VpnState.EngineUntrusted,
                    -> true
                    // OpenVPN for Android commonly reports NOTCONNECTED once while it tears
                    // down a previous session, before it starts dialling ours. Accepting that
                    // as failure would fail over instantly and give no relay a real chance, so
                    // early disconnects are ignored for a short settling window.
                    is VpnState.Disconnected,
                    is VpnState.NoNetwork,
                    -> System.currentTimeMillis() - startedAt > SETTLE_GRACE_MILLIS
                    is VpnState.Connecting, VpnState.Paused -> false
                }
            }
        }
        return settled is VpnState.Connected
    }

    /**
     * Runs [action] once the engine is bound and every consent is granted.
     *
     * OpenVPN for Android needs two separate approvals, in order: its own external-API guard
     * (`prepare`) and then Android's system VPN consent (`prepareVPNService`). Each is an
     * Activity result, so this suspends by stashing [action] and re-entering after the answer;
     * that naturally walks the whole chain however many prompts it turns out to be.
     */
    private suspend fun withConsent(action: suspend () -> Unit) {
        if (!engine.bind()) {
            effectChannel.send(UiEffect.Message(R.string.msg_engine_required))
            return
        }
        engine.registerStatus()

        val consent = engine.nextConsentIntent()
        if (consent != null) {
            if (consentAttempts >= MAX_CONSENT_PROMPTS) {
                consentAttempts = 0
                pendingAction = null
                effectChannel.send(UiEffect.Message(R.string.msg_permission_denied))
                return
            }
            consentAttempts++
            pendingAction = action
            effectChannel.send(UiEffect.LaunchConsent(consent))
            return
        }
        consentAttempts = 0
        action()
    }

    /** Called by the Activity once a consent dialog returns, whatever the result. */
    fun onConsentResult() {
        val action = pendingAction
        pendingAction = null
        viewModelScope.launch {
            engine.onConsentResult()
            if (action != null) withConsent(action)
        }
    }

    fun openInstallPage(preferFdroid: Boolean = false) {
        viewModelScope.launch {
            effectChannel.send(
                UiEffect.LaunchIntent(
                    if (preferFdroid) OpenVpnInstallCheck.fdroidIntent()
                    else OpenVpnInstallCheck.playStoreIntent(),
                ),
            )
        }
    }

    override fun onCleared() {
        // Releases the binder and the status callback; ViewModel.onCleared is empty by contract.
        engine.unbind()
    }

    companion object {
        /** How many scored candidates get probed before picking. */
        private const val PROBE_POOL = 5

        /** How many tunnels Quick Connect will actually attempt. */
        private const val MAX_ATTEMPTS = 3

        /** Generous enough for a TCP handshake over a slow relay, short enough to fail over. */
        private const val CONNECT_TIMEOUT_MILLIS = 25_000L

        private const val MAX_CONSENT_PROMPTS = 3

        /** Long enough to swallow an accidental double-tap, short enough to feel responsive. */
        private const val CONNECT_DEBOUNCE_MILLIS = 700L

        private const val KEY_COUNTRY = "countryFilter"
        private const val KEY_PROTOCOL = "protocolFilter"
        private const val KEY_SORT = "sortKey"

        /** Disconnect reports inside this window after a connect attempt are teardown noise. */
        private const val SETTLE_GRACE_MILLIS = 3_000L

        fun factory(
            repository: ServerRepository,
            engine: OpenVpnAidlEngine,
            prefs: AppPrefs,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ServerListViewModel(repository, engine, prefs, createSavedStateHandle())
            }
        }
    }
}
