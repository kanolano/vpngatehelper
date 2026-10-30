package com.tb.vpngate.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tb.vpngate.R
import com.tb.vpngate.data.Protocol
import com.tb.vpngate.data.VpnServer
import com.tb.vpngate.vpn.VpnState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    state: ServerListUiState,
    snackbarHostState: SnackbarHostState,
    onRefresh: () -> Unit,
    onConnect: (VpnServer) -> Unit,
    onQuickConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onInstallEngine: () -> Unit,
    onCountryFilter: (String?) -> Unit,
    onProtocolFilter: (Protocol?) -> Unit,
    onSortKey: (SortKey) -> Unit,
    onAcknowledgeNotice: () -> Unit,
) {
    val connectedServerId = (state.vpnState as? VpnState.Connected)?.server?.id

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            ConnectionBar(
                state = state.vpnState,
                isQuickConnecting = state.isQuickConnecting,
                onQuickConnect = onQuickConnect,
                onDisconnect = onDisconnect,
                onInstallEngine = onInstallEngine,
            )

            AnimatedVisibility(visible = state.showNotice) {
                PrivacyNotice(onAcknowledge = onAcknowledgeNotice)
            }

            state.error?.let { ErrorBanner(it, state.hasServers) }

            FilterRow(
                state = state,
                onCountryFilter = onCountryFilter,
                onProtocolFilter = onProtocolFilter,
                onSortKey = onSortKey,
            )

            StatusLine(state)

            HorizontalDivider()

            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (!state.hasServers) {
                    EmptyState(state)
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(state.servers, key = { it.id }) { server ->
                            ServerRow(
                                server = server,
                                isConnected = server.id == connectedServerId,
                                onClick = { onConnect(server) },
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                thickness = 0.5.dp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivacyNotice(onAcknowledge: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.notice_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                // Being straight about this matters: the cipher suite is fixed by VPN Gate's
                // servers and cannot be improved from the client side, so the honest move is to
                // say so rather than let a padlock icon imply more than the service delivers.
                text = stringResource(R.string.notice_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onAcknowledge) { Text(stringResource(R.string.action_got_it)) }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, hasCachedData: Boolean) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Text(
            text = if (hasCachedData) stringResource(R.string.error_with_cache, message) else message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(
    state: ServerListUiState,
    onCountryFilter: (String?) -> Unit,
    onProtocolFilter: (Protocol?) -> Unit,
    onSortKey: (SortKey) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SortMenu(current = state.sortKey, onSelect = onSortKey)

            ProtocolChip(
                stringResource(R.string.filter_all),
                state.protocolFilter == null,
            ) { onProtocolFilter(null) }
            ProtocolChip(
                stringResource(R.string.filter_tcp),
                state.protocolFilter == Protocol.TCP,
            ) { onProtocolFilter(Protocol.TCP) }
            ProtocolChip(
                stringResource(R.string.filter_udp),
                state.protocolFilter == Protocol.UDP,
            ) { onProtocolFilter(Protocol.UDP) }
        }

        // Country chips are built from the countries actually present in the feed. The mix is
        // heavily skewed (mostly JP and KR), so offering a fixed world list would advertise
        // choices that usually do not exist.
        if (state.countries.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = state.countryFilter == null,
                    onClick = { onCountryFilter(null) },
                    label = { Text(stringResource(R.string.filter_all_count, state.totalServers)) },
                )
                state.countries.forEach { option ->
                    FilterChip(
                        selected = state.countryFilter == option.code,
                        onClick = {
                            onCountryFilter(if (state.countryFilter == option.code) null else option.code)
                        },
                        label = {
                            Text(
                                stringResource(
                                    R.string.filter_country,
                                    Format.flagEmoji(option.code),
                                    option.code,
                                    option.count,
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProtocolChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortMenu(current: SortKey, onSelect: (SortKey) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(stringResource(R.string.sort_label, stringResource(current.labelRes))) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortKey.entries.forEach { key ->
                DropdownMenuItem(
                    text = { Text(stringResource(key.labelRes)) },
                    onClick = {
                        onSelect(key)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusLine(state: ServerListUiState) {
    val text = when {
        state.isRefreshing && !state.hasServers -> stringResource(R.string.status_loading)
        state.lastUpdatedMillis == null -> stringResource(R.string.status_not_loaded)
        state.isStale -> stringResource(
            R.string.status_offline,
            Format.relativeTime(state.lastUpdatedMillis),
        )
        else -> stringResource(
            R.string.status_updated,
            Format.relativeTime(state.lastUpdatedMillis),
        )
    }
    val shown = if (state.isFiltered) {
        stringResource(R.string.status_showing_filtered, text, state.servers.size, state.totalServers)
    } else {
        pluralStringResource(
            R.plurals.status_showing_all,
            state.servers.size,
            text,
            state.servers.size,
        )
    }
    Text(
        text = shown,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun EmptyState(state: ServerListUiState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                text = when {
                    state.isRefreshing -> stringResource(R.string.empty_fetching)
                    state.isFiltered -> stringResource(R.string.empty_filtered)
                    state.error != null -> stringResource(R.string.empty_error)
                    else -> stringResource(R.string.empty_none)
                },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            if (!state.isRefreshing) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.empty_pull_to_retry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
