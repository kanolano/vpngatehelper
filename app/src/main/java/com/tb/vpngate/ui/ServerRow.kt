package com.tb.vpngate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tb.vpngate.R
import com.tb.vpngate.data.Protocol
import com.tb.vpngate.data.VpnServer

@Composable
fun ServerRow(
    server: VpnServer,
    isConnected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sessions = pluralStringResource(
        R.plurals.sessions,
        server.numVpnSessions,
        server.numVpnSessions,
    )
    val metrics = stringResource(
        R.string.row_metrics,
        Format.ping(server.pingMs),
        Format.speed(server.speedBps),
        Format.uptime(server.uptimeMs),
        sessions,
    )
    val connectLabel = stringResource(R.string.action_connect_to, server.countryLong)

    Row(
        modifier = modifier
            .fillMaxWidth()
            // 48dp is the minimum comfortable touch target.
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = connectLabel, onClick = onClick)
            // Without merging, a screen reader reads five disconnected fragments per row.
            // Merged, each row announces as one item.
            .semantics(mergeDescendants = true) { }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = Format.flagEmoji(server.countryShort), fontSize = 26.sp)
        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = server.countryLong.ifBlank { server.countryShort },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Badge(server.protocol.name)
                if (isConnected) {
                    Spacer(Modifier.width(6.dp))
                    Badge(stringResource(R.string.badge_active), highlight = true)
                }
            }
            Spacer(Modifier.padding(top = 2.dp))
            Text(
                text = metrics,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.row_endpoint, server.remoteHost, server.remotePort),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Badge(text: String, highlight: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (highlight) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlight) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}
