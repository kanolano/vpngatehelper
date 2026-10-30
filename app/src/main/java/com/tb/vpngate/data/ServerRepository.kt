package com.tb.vpngate.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Source of truth for the server list.
 *
 * Policy: cache first, network second. On start the cached feed is emitted immediately so the
 * list is usable before any request completes; a failed refresh never clears a good cached
 * list, it only attaches [ServerListState.error] so the UI can show a banner over real data.
 */
class ServerRepository(
    private val api: FeedSource,
    private val cache: ServerCache,
) {
    private val _state = MutableStateFlow(ServerListState())
    val state: StateFlow<ServerListState> = _state.asStateFlow()

    /** Serialises refreshes so a pull-to-refresh during startup cannot interleave. */
    private val refreshMutex = Mutex()

    /** Emits the cached feed if there is one. Safe to call repeatedly; it is a no-op once loaded. */
    suspend fun loadCache() {
        if (_state.value.servers.isNotEmpty()) return
        val cached = cache.read() ?: return
        val parsed = parseOffMainThread(cached.csv)
        if (parsed.servers.isEmpty()) return
        // Don't clobber a fresher network result that landed while we were reading the file.
        _state.update { current ->
            if (current.servers.isNotEmpty()) current
            else current.copy(
                servers = parsed.servers,
                lastUpdatedMillis = cached.fetchedAtMillis,
                isStale = true,
                rejectedRows = parsed.rejectedRows,
            )
        }
    }

    suspend fun refresh() = refreshMutex.withLock {
        _state.update { it.copy(isRefreshing = true, error = null) }
        try {
            val csv = api.fetchCsv()
            val parsed = parseOffMainThread(csv)
            if (parsed.servers.isEmpty()) {
                // Feed shape was right but yielded nothing usable. Keep whatever we had.
                _state.update {
                    it.copy(isRefreshing = false, error = "VPN Gate returned no usable servers")
                }
                return@withLock
            }
            cache.write(csv)
            _state.update {
                it.copy(
                    servers = parsed.servers,
                    lastUpdatedMillis = System.currentTimeMillis(),
                    isStale = false,
                    isRefreshing = false,
                    error = null,
                    rejectedRows = parsed.rejectedRows,
                )
            }
        } catch (t: Throwable) {
            _state.update {
                it.copy(isRefreshing = false, error = t.message ?: "Could not reach VPN Gate")
            }
        }
    }

}

/**
 * Parsing is CPU work, not I/O: splitting ~1.3 MB and base64-decoding ~95 profiles measures in
 * the tens of milliseconds on a mid-range device. Callers run on the main dispatcher, so doing
 * this inline dropped frames on every load and refresh.
 */
private suspend fun parseOffMainThread(csv: String): VpnGateCsvParser.Result =
    withContext(Dispatchers.Default) { VpnGateCsvParser.parse(csv) }

data class ServerListState(
    val servers: List<VpnServer> = emptyList(),
    val lastUpdatedMillis: Long? = null,
    /** True when [servers] came from cache and no successful network refresh has happened yet. */
    val isStale: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val rejectedRows: Int = 0,
)
