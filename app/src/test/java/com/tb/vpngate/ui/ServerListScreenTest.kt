package com.tb.vpngate.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tb.vpngate.data.Protocol
import com.tb.vpngate.data.VpnServer
import com.tb.vpngate.vpn.VpnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UI tests for the one screen in the app.
 *
 * These run on the JVM through Robolectric rather than as instrumented tests, so they execute
 * with `./gradlew :app:testDebugUnitTest` and need no device or emulator. [ServerListScreen] is
 * a stateless composable over [ServerListUiState] plus callbacks, which is what makes this
 * practical: every case below is just a state value.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ServerListScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val japan = VpnServer(
        hostName = "public-vpn-45", ip = "219.100.37.9", score = 2_522_184, pingMs = 12,
        speedBps = 119_700_641, countryLong = "Japan", countryShort = "JP", numVpnSessions = 80,
        uptimeMs = 482_211_146, totalUsers = 0, totalTraffic = 0, logType = "2weeks",
        operator = "", message = "", configBase64 = "", protocol = Protocol.TCP,
        remoteHost = "219.100.37.9", remotePort = 443,
    )

    private val korea = japan.copy(
        hostName = "vpn2956", ip = "59.26.41.201", countryLong = "Korea Republic of",
        countryShort = "KR", protocol = Protocol.UDP, remoteHost = "59.26.41.201",
        remotePort = 1612, pingMs = null,
    )

    private var connected: VpnServer? = null
    private var quickConnects = 0
    private var disconnects = 0
    private var installs = 0

    private fun show(state: ServerListUiState) {
        compose.setContent {
            VpnGateTheme(dynamicColor = false) {
                ServerListScreen(
                    state = state,
                    snackbarHostState = SnackbarHostState(),
                    onRefresh = {},
                    onConnect = { connected = it },
                    onQuickConnect = { quickConnects++ },
                    onDisconnect = { disconnects++ },
                    onInstallEngine = { installs++ },
                    onCountryFilter = {},
                    onProtocolFilter = {},
                    onSortKey = {},
                    onAcknowledgeNotice = {},
                )
            }
        }
    }

    private fun loaded(vararg servers: VpnServer) = ServerListUiState(
        servers = servers.toList(),
        totalServers = servers.size,
        countries = listOf(CountryOption("JP", "Japan", 1)),
        lastUpdatedMillis = System.currentTimeMillis(),
    )

    @Test
    fun `renders a row per server`() {
        show(loaded(japan, korea))
        compose.onNodeWithText("Japan").assertIsDisplayed()
        compose.onNodeWithText("Korea Republic of").assertIsDisplayed()
    }

    @Test
    fun `tapping a row reports that server`() {
        show(loaded(japan, korea))
        compose.onNodeWithText("Korea Republic of").performClick()
        assertEquals(korea, connected)
    }

    @Test
    fun `a server with no ping renders a dash instead of zero`() {
        show(loaded(korea))
        // "0 ms" would read as an impossibly fast relay and would sort first.
        compose.onAllNodesWithText("0 ms", substring = true).assertCountEquals(0)
    }

    @Test
    fun `the connected server is marked active`() {
        show(loaded(japan, korea).copy(vpnState = VpnState.Connected(japan)))
        compose.onNodeWithText("ACTIVE").assertIsDisplayed()
    }

    @Test
    fun `a tunnel started outside the app is not attributed to a listed server`() {
        show(loaded(japan).copy(vpnState = VpnState.Connected(server = null)))
        compose.onNodeWithText("started outside this app", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("ACTIVE").assertCountEquals(0)
    }

    @Test
    fun `quick connect is offered when disconnected`() {
        show(loaded(japan))
        compose.onNodeWithText("Quick Connect").performClick()
        assertEquals(1, quickConnects)
    }

    @Test
    fun `disconnect is offered when connected`() {
        show(loaded(japan).copy(vpnState = VpnState.Connected(japan)))
        compose.onNodeWithText("Disconnect").performClick()
        assertEquals(1, disconnects)
    }

    @Test
    fun `a missing companion app offers to install it instead of failing quietly`() {
        show(loaded(japan).copy(vpnState = VpnState.EngineMissing))
        compose.onNodeWithText("OpenVPN for Android not installed").assertIsDisplayed()
        compose.onNodeWithText("Install").performClick()
        assertEquals(1, installs)
    }

    @Test
    fun `an untrusted companion app explains itself and refuses to connect`() {
        show(
            loaded(japan).copy(
                vpnState = VpnState.EngineUntrusted("its signing certificate changed"),
            ),
        )
        compose.onNodeWithText("could not be trusted", substring = true).assertIsDisplayed()
        compose.onNodeWithText("No profile was sent", substring = true).assertIsDisplayed()
    }

    @Test
    fun `failover progress names the attempt`() {
        show(
            loaded(japan).copy(
                vpnState = VpnState.Connecting(japan, attempt = VpnState.Attempt(2, 3)),
            ),
        )
        compose.onNodeWithText("Trying Japan (2 of 3)").assertIsDisplayed()
    }

    @Test
    fun `a stale list is labelled offline`() {
        show(loaded(japan).copy(isStale = true))
        compose.onNodeWithText("Offline", substring = true).assertIsDisplayed()
    }

    @Test
    fun `an error over cached data still shows the rows`() {
        show(loaded(japan).copy(error = "no network"))
        compose.onNodeWithText("showing the last saved list", substring = true).assertIsDisplayed()
        // The point of the fix: an error must never blank a good list.
        compose.onNodeWithText("Japan").assertIsDisplayed()
    }

    @Test
    fun `an empty filtered list says so rather than looking broken`() {
        show(
            ServerListUiState(
                servers = emptyList(),
                totalServers = 97,
                countryFilter = "US",
                lastUpdatedMillis = System.currentTimeMillis(),
            ),
        )
        compose.onNodeWithText("No servers match this filter.").assertIsDisplayed()
    }

    @Test
    fun `the privacy notice states the weak cipher plainly`() {
        show(loaded(japan).copy(showNotice = true))
        compose.onNodeWithText("Read this before you rely on it").assertIsDisplayed()
        // The app must not imply more protection than VPN Gate actually provides.
        compose.onNodeWithText("AES-128-CBC", substring = true).assertIsDisplayed()
        compose.onNodeWithText("not as a privacy guarantee", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the privacy notice stays hidden once acknowledged`() {
        show(loaded(japan).copy(showNotice = false))
        compose.onAllNodesWithText("Read this before you rely on it").assertCountEquals(0)
    }

    @Test
    fun `each row shows its own protocol`() {
        show(loaded(japan, korea))
        compose.onNodeWithText("Japan").assertTextContains("TCP", substring = true)
        compose.onNodeWithText("Korea Republic of").assertTextContains("UDP", substring = true)
    }

    @Test
    fun `nothing is reported connected before anything is tapped`() {
        show(loaded(japan))
        assertNull(connected)
    }
}
