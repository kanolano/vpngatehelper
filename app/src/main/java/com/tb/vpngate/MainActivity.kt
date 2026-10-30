package com.tb.vpngate

import android.content.ActivityNotFoundException
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tb.vpngate.data.ServerCache
import com.tb.vpngate.data.ServerRepository
import com.tb.vpngate.data.VpnGateApi
import com.tb.vpngate.ui.AppPrefs
import com.tb.vpngate.ui.ServerListScreen
import com.tb.vpngate.ui.ServerListViewModel
import com.tb.vpngate.ui.UiEffect
import com.tb.vpngate.ui.VpnGateTheme
import com.tb.vpngate.vpn.CompanionAppVerifier
import com.tb.vpngate.vpn.OpenVpnAidlEngine
import com.tb.vpngate.vpn.OpenVpnInstallCheck
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // Hand-rolled object graph. A DI container would be more machinery than a four-object
    // graph in a single-screen app justifies. Lazy so applicationContext is available.
    private val engine by lazy {
        OpenVpnAidlEngine(
            context = applicationContext,
            // trustedSigners is intentionally left empty: see CompanionAppVerifier. Populate it
            // with a digest verified against a real release to upgrade from pin-on-first-use.
            verifier = CompanionAppVerifier(applicationContext, prefs),
        )
    }

    private val repository by lazy {
        ServerRepository(
            api = VpnGateApi(),
            cache = ServerCache.inDirectory(applicationContext.filesDir),
        )
    }

    private val prefs by lazy { AppPrefs(applicationContext) }

    private val viewModel: ServerListViewModel by viewModels {
        ServerListViewModel.factory(repository, engine, prefs)
    }

    /**
     * Both consent dialogs return through here. The result code is deliberately ignored:
     * granted or denied, the next step is to ask the engine what it still needs, which is the
     * authoritative answer. The ViewModel caps re-prompts so a denial cannot loop forever.
     */
    private val consentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.onConsentResult()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            VpnGateTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val snackbarHostState = remember { SnackbarHostState() }

                val scope = rememberCoroutineScope()

                LaunchedEffect(Unit) {
                    // Every effect must run to completion, so this stays a plain collect rather
                    // than collectLatest -- dropping a half-delivered consent launch would
                    // strand the connect handshake. But showSnackbar suspends until the snackbar
                    // is dismissed, so messages are shown on a separate coroutine: inline, they
                    // head-of-line blocked the queue and a consent dialog queued behind one
                    // looked like a dead tap for several seconds.
                    viewModel.effects.collect { effect ->
                        when (effect) {
                            is UiEffect.LaunchConsent ->
                                runCatching { consentLauncher.launch(effect.intent) }
                                    .onFailure {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                getString(R.string.msg_consent_dialog_failed),
                                            )
                                        }
                                    }

                            is UiEffect.LaunchIntent -> try {
                                startActivity(effect.intent)
                            } catch (_: ActivityNotFoundException) {
                                // No Play client installed: fall back to the web listing.
                                runCatching {
                                    startActivity(OpenVpnInstallCheck.playStoreWebIntent())
                                }.onFailure {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            getString(R.string.msg_no_handler),
                                        )
                                    }
                                }
                            }

                            is UiEffect.Message -> scope.launch {
                                val args = effect.args.toTypedArray()
                                snackbarHostState.showSnackbar(
                                    effect.quantity?.let {
                                        resources.getQuantityString(effect.resId, it, *args)
                                    } ?: getString(effect.resId, *args),
                                )
                            }
                        }
                    }
                }

                ServerListScreen(
                    state = state,
                    snackbarHostState = snackbarHostState,
                    onRefresh = viewModel::refresh,
                    onConnect = viewModel::connect,
                    onQuickConnect = viewModel::quickConnect,
                    onDisconnect = viewModel::disconnect,
                    onInstallEngine = { viewModel.openInstallPage() },
                    onCountryFilter = viewModel::setCountryFilter,
                    onProtocolFilter = viewModel::setProtocolFilter,
                    onSortKey = viewModel::setSortKey,
                    onAcknowledgeNotice = viewModel::acknowledgeNotice,
                )
            }
        }
    }
}
