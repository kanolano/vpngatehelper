package com.tb.vpngate.ui

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import com.tb.vpngate.vpn.PinnedSignerStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * Small persisted settings: the privacy-notice acknowledgement, plus the pinned signing
 * certificate of the companion VPN app.
 */
class AppPrefs(private val context: Context) : PinnedSignerStore {

    val noticeAcknowledged: Flow<Boolean> =
        context.dataStore.data.map { it[NOTICE_ACKNOWLEDGED] ?: false }

    suspend fun acknowledgeNotice() {
        context.dataStore.edit { it[NOTICE_ACKNOWLEDGED] = true }
    }

    override suspend fun pinnedSigner(): String? =
        context.dataStore.data.first()[OPENVPN_SIGNER]

    override suspend fun pinSigner(sha256: String) {
        context.dataStore.edit { it[OPENVPN_SIGNER] = sha256 }
    }

    private companion object {
        val NOTICE_ACKNOWLEDGED = booleanPreferencesKey("notice_acknowledged")
        val OPENVPN_SIGNER = stringPreferencesKey("openvpn_signer_sha256")
    }
}
