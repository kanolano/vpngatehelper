package com.tb.vpngate.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.tb.vpngate.data.Protocol
import com.tb.vpngate.data.VpnServer
import com.tb.vpngate.vpn.VpnState

/**
 * Previews live in the debug source set, not alongside the composables they render.
 *
 * Two reasons. Preview code and the tooling it needs stay out of the release APK, and AGP 9's
 * Compose Preview tooling only generates its runfiles for variants that have unit tests enabled
 * -- with @Preview in `main` it tried to do so for release, where unit tests are off by default,
 * and failed to configure (`./gradlew :app:tasks` broke). Keeping previews debug-only avoids
 * that instead of forcing release unit tests on to satisfy a debug-time tool.
 *
 * Everything referenced here is public API of the ui package, so nothing had to be loosened to
 * make the move.
 */

private val japanServer = VpnServer(
    hostName = "public-vpn-45", ip = "219.100.37.9", score = 2_522_184, pingMs = 12,
    speedBps = 119_700_641, countryLong = "Japan", countryShort = "JP", numVpnSessions = 80,
    uptimeMs = 482_211_146, totalUsers = 15_080_679, totalTraffic = 0, logType = "2weeks",
    operator = "", message = "", configBase64 = "", protocol = Protocol.TCP,
    remoteHost = "219.100.37.9", remotePort = 443,
)

private val koreaServer = VpnServer(
    hostName = "vpn295649515", ip = "59.26.41.201", score = 439_021, pingMs = null,
    speedBps = 22_748_864, countryLong = "Korea Republic of", countryShort = "KR",
    numVpnSessions = 1, uptimeMs = 1_000, totalUsers = 0, totalTraffic = 0, logType = "2weeks",
    operator = "", message = "", configBase64 = "", protocol = Protocol.UDP,
    remoteHost = "59.26.41.201", remotePort = 1612,
)

@Preview(name = "Bar · connected", showBackground = true)
@Composable
private fun PreviewConnected() = VpnGateTheme(dynamicColor = false) {
    ConnectionBar(VpnState.Connected(japanServer), false, {}, {}, {})
}

@Preview(name = "Bar · tunnel started elsewhere", showBackground = true)
@Composable
private fun PreviewConnectedElsewhere() = VpnGateTheme(dynamicColor = false) {
    ConnectionBar(VpnState.Connected(server = null), false, {}, {}, {})
}

@Preview(name = "Bar · failing over", showBackground = true)
@Composable
private fun PreviewFailingOver() = VpnGateTheme(dynamicColor = false) {
    ConnectionBar(
        VpnState.Connecting(japanServer, attempt = VpnState.Attempt(2, 3)),
        true, {}, {}, {},
    )
}

@Preview(name = "Bar · disconnected", showBackground = true)
@Composable
private fun PreviewDisconnected() = VpnGateTheme(dynamicColor = false) {
    ConnectionBar(VpnState.Disconnected, false, {}, {}, {})
}

@Preview(name = "Bar · engine missing", showBackground = true)
@Composable
private fun PreviewEngineMissing() = VpnGateTheme(dynamicColor = false) {
    ConnectionBar(VpnState.EngineMissing, false, {}, {}, {})
}

@Preview(name = "Bar · engine untrusted", showBackground = true)
@Composable
private fun PreviewEngineUntrusted() = VpnGateTheme(dynamicColor = false) {
    ConnectionBar(
        VpnState.EngineUntrusted("its signing certificate changed since it was first used"),
        false, {}, {}, {},
    )
}

@Preview(name = "Rows", showBackground = true)
@Composable
private fun PreviewServerRows() = VpnGateTheme(dynamicColor = false) {
    Column {
        ServerRow(server = japanServer, isConnected = true, onClick = {})
        ServerRow(server = koreaServer, isConnected = false, onClick = {})
    }
}

@Preview(name = "Rows · dark", showBackground = true)
@Composable
private fun PreviewServerRowsDark() = VpnGateTheme(darkTheme = true, dynamicColor = false) {
    Column {
        ServerRow(server = japanServer, isConnected = true, onClick = {})
        ServerRow(server = koreaServer, isConnected = false, onClick = {})
    }
}
