package com.tb.vpngate.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tb.vpngate.R
import com.tb.vpngate.data.VpnServer
import com.tb.vpngate.vpn.VpnState

/**
 * Pinned status header: the one thing a user glances at to answer "am I protected right now".
 *
 * Deliberately states when a tunnel was started elsewhere rather than attributing it to the
 * last server tapped here. The companion app's status stream is global, so claiming ownership
 * of a foreign session would misreport the user's actual exit point.
 */
@Composable
fun ConnectionBar(
    state: VpnState,
    isQuickConnecting: Boolean,
    onQuickConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onInstallEngine: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val info = state.describe()

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The dot and spinner only restate the headline below, so they are hidden from
                // screen readers rather than announced as unlabelled decoration.
                if (info.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(14.dp)
                            .clearAndSetSemantics { },
                        strokeWidth = 2.dp,
                        color = info.color,
                    )
                } else {
                    Spacer(
                        Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(info.color)
                            .clearAndSetSemantics { },
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = info.headline,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    info.detail?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                when {
                    state is VpnState.EngineMissing || state is VpnState.EngineUntrusted ->
                        Button(onClick = onInstallEngine) {
                            Text(stringResource(R.string.action_install))
                        }

                    info.canDisconnect ->
                        OutlinedButton(onClick = onDisconnect) {
                            Text(stringResource(R.string.action_disconnect))
                        }

                    else ->
                        Button(onClick = onQuickConnect, enabled = !isQuickConnecting) {
                            Text(stringResource(R.string.action_quick_connect))
                        }
                }
            }

            AnimatedVisibility(visible = state is VpnState.EngineMissing) {
                Text(
                    text = stringResource(R.string.state_engine_missing_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

private data class StateInfo(
    val headline: String,
    val detail: String?,
    val color: Color,
    val busy: Boolean = false,
    val canDisconnect: Boolean = false,
)

@Composable
private fun VpnServer.location(): String = stringResource(
    R.string.server_location,
    Format.flagEmoji(countryShort),
    countryLong,
    remoteHost,
)

@Composable
private fun VpnServer.locationFull(): String = stringResource(
    R.string.server_location_full,
    Format.flagEmoji(countryShort),
    countryLong,
    remoteHost,
    remotePort,
    protocol.name,
)

@Composable
private fun VpnState.describe(): StateInfo = when (this) {
    is VpnState.Disconnected -> StateInfo(
        headline = stringResource(R.string.state_not_connected),
        detail = stringResource(R.string.state_not_connected_detail),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    is VpnState.Connecting -> StateInfo(
        headline = attempt?.let {
            stringResource(
                R.string.state_connecting_attempt,
                server?.countryLong.orEmpty(),
                it.index,
                it.total,
            )
        } ?: stringResource(R.string.state_connecting),
        detail = server?.location() ?: detail,
        color = StateColors.connecting,
        busy = true,
        canDisconnect = true,
    )

    is VpnState.Connected -> StateInfo(
        headline = stringResource(R.string.state_connected),
        // No server attached means this tunnel was not started by this app.
        detail = server?.locationFull() ?: stringResource(R.string.state_connected_elsewhere),
        color = StateColors.connected,
        canDisconnect = true,
    )

    is VpnState.Paused -> StateInfo(
        headline = stringResource(R.string.state_paused),
        detail = stringResource(R.string.state_paused_detail),
        color = StateColors.connecting,
        canDisconnect = true,
    )

    is VpnState.NoNetwork -> StateInfo(
        headline = stringResource(R.string.state_no_network),
        detail = stringResource(R.string.state_no_network_detail),
        color = StateColors.connecting,
        canDisconnect = true,
    )

    is VpnState.AuthFailed -> StateInfo(
        headline = stringResource(R.string.state_auth_failed),
        detail = detail ?: stringResource(R.string.state_auth_failed_detail),
        color = StateColors.failed,
    )

    is VpnState.Error -> StateInfo(
        headline = stringResource(R.string.state_error),
        detail = message,
        color = StateColors.failed,
    )

    is VpnState.EngineMissing -> StateInfo(
        headline = stringResource(R.string.state_engine_missing),
        detail = null,
        color = StateColors.failed,
    )

    is VpnState.EngineUntrusted -> StateInfo(
        headline = stringResource(R.string.state_engine_untrusted),
        detail = stringResource(R.string.state_engine_untrusted_detail, reason),
        color = StateColors.failed,
    )
}
