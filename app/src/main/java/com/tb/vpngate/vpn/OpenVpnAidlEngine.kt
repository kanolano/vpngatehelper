package com.tb.vpngate.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import com.tb.vpngate.data.VpnServer
import de.blinkt.openvpn.api.IOpenVPNAPIService
import de.blinkt.openvpn.api.IOpenVPNStatusCallback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives the installed "OpenVPN for Android" app (`de.blinkt.openvpn`) over its AIDL API.
 *
 * Why this rather than embedding the engine: ics-openvpn is GPLv2, but its own documentation
 * exempts the AIDL API ("The use of the AIDL API to control OpenVPN for Android from an
 * external app is not subject to the license"). It also means the companion app owns the
 * VpnService and its notification, so this app needs no foreground service, no
 * FOREGROUND_SERVICE_* permission and no Android 14 service-type declaration.
 *
 * The cost is that the companion app must be installed, that its identity has to be checked
 * before it is trusted with a profile ([CompanionAppVerifier]), and that its status stream is
 * global: see [VpnState] on why a reported tunnel is not necessarily ours.
 *
 * Threading: every call into the remote service is a blocking cross-process transaction, so all
 * of them run off the main thread. Status callbacks arrive on a Binder thread and are published
 * through a StateFlow, which is safe from any thread.
 */
class OpenVpnAidlEngine(
    private val context: Context,
    private val verifier: CompanionAppVerifier,
) : VpnEngine {

    private val _state = MutableStateFlow<VpnState>(VpnState.Disconnected)
    override val state: StateFlow<VpnState> = _state.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    @Volatile
    private var service: IOpenVPNAPIService? = null

    /** The relay we most recently asked for, used to label an incoming status as ours. */
    @Volatile
    private var intendedServer: VpnServer? = null

    /**
     * Completes when onServiceConnected fires, so callers can await a usable binding.
     * Reassigned from a Binder thread on disconnect, hence volatile.
     */
    @Volatile
    private var binding = CompletableDeferred<Boolean>()

    /** True once bindService has been accepted, so unbind stays balanced. */
    @Volatile
    private var bindRequested = false

    /**
     * Serialises bind(). Without it the eager bind at startup and a fast user tap can both see
     * a null service and call bindService with the same ServiceConnection, which increments the
     * bind count twice while unbind() only decrements it once -- a leaked binding.
     */
    private val bindMutex = Mutex()

    /** For teardown, which cannot suspend but must not do IPC on the main thread. */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val statusCallback = object : IOpenVPNStatusCallback.Stub() {
        override fun newStatus(uuid: String?, status: String?, message: String?, level: String?) {
            // Binder thread. Never touch UI state directly from here; the flow is thread-safe.
            val next = levelToState(level, message, intendedServer)
            if (next is VpnState.Disconnected) intendedServer = null
            _state.value = next
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val bound = IOpenVPNAPIService.Stub.asInterface(binder)
            service = bound
            _isReady.value = bound != null
            if (!binding.isCompleted) binding.complete(bound != null)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            _isReady.value = false
            // Re-arm so a later bind can be awaited again.
            binding = CompletableDeferred()
            _state.update { current ->
                if (current is VpnState.Connected || current is VpnState.Connecting) {
                    VpnState.Error("Lost contact with OpenVPN for Android")
                } else {
                    current
                }
            }
        }
    }

    /** Whether the companion app is installed at all. */
    fun isEngineInstalled(): Boolean = try {
        // Requires the <queries> entry in the manifest; without it this always throws
        // NameNotFoundException on API 30+ even when the app is present.
        context.packageManager.getPackageInfo(OPENVPN_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Verifies and binds to the companion app. Returns false if it is missing, fails its
     * identity check, or the bind is refused; [state] then explains which.
     */
    suspend fun bind(): Boolean = bindMutex.withLock {
        if (service != null) return@withLock true

        when (val result = verifier.verify()) {
            is CompanionAppVerifier.Result.NotInstalled -> {
                _state.value = VpnState.EngineMissing
                return@withLock false
            }
            is CompanionAppVerifier.Result.Untrusted -> {
                _state.value = VpnState.EngineUntrusted(result.reason)
                return@withLock false
            }
            is CompanionAppVerifier.Result.Trusted -> Unit
        }

        val intent = Intent(IOpenVPNAPIService::class.java.name).apply {
            setPackage(OPENVPN_PACKAGE)
        }
        val requested = runCatching {
            withContext(Dispatchers.IO) {
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }
        }.getOrDefault(false)

        if (!requested) {
            _state.value = VpnState.EngineMissing
            return@withLock false
        }
        bindRequested = true

        // bindService returning true only means the request was accepted; the binder arrives
        // asynchronously, so wait for it rather than letting the first connect() NPE.
        val ok = withTimeoutOrNull(BIND_TIMEOUT_MILLIS) { binding.await() } ?: false
        if (!ok) _state.value = VpnState.Error("Could not bind to OpenVPN for Android")
        ok
    }

    /**
     * Releases the binder. Safe to call from a non-suspending teardown such as
     * ViewModel.onCleared: the remote calls are handed to [ioScope] rather than run inline,
     * because unregisterStatusCallback is a cross-process transaction.
     */
    fun unbind() {
        val bound = service
        val wasRequested = bindRequested
        service = null
        bindRequested = false
        _isReady.value = false
        ioScope.launch {
            runCatching { bound?.unregisterStatusCallback(statusCallback) }
            if (wasRequested) runCatching { context.unbindService(connection) }
        }
    }

    /** Subscribes to status updates. Safe to call again; duplicate registration is harmless. */
    suspend fun registerStatus() {
        val bound = service ?: return
        withContext(Dispatchers.IO) {
            runCatching { bound.registerStatusCallback(statusCallback) }
        }
    }

    override suspend fun nextConsentIntent(): Intent? = withContext(Dispatchers.IO) {
        val bound = service ?: return@withContext null
        try {
            // Order matters. prepare() is ics-openvpn's own confused-deputy guard: it asks the
            // user to allow THIS app to drive the VPN. Only once that is granted is it
            // meaningful to ask Android for the system VPN consent via prepareVPNService().
            bound.prepare(context.packageName)?.let { return@withContext it }
            bound.prepareVPNService()
        } catch (e: RemoteException) {
            _state.value = VpnState.Error("OpenVPN for Android rejected the request: ${e.message}")
            null
        }
    }

    override suspend fun onConsentResult() {
        // Consent state lives in the companion app; nothing to cache here. Re-registering is
        // what actually matters, since a freshly granted API permission enables callbacks.
        registerStatus()
    }

    override suspend fun connect(server: VpnServer, inlineConfig: String) {
        val bound = service
        if (bound == null) {
            _state.value = VpnState.EngineMissing
            return
        }
        intendedServer = server
        _state.value = VpnState.Connecting(server)
        withContext(Dispatchers.IO) {
            try {
                bound.startVPN(inlineConfig)
            } catch (e: RemoteException) {
                _state.value = VpnState.Error("Failed to start the tunnel: ${e.message}")
            } catch (_: SecurityException) {
                // Thrown when the external-API permission was never granted or was revoked.
                _state.value =
                    VpnState.Error("OpenVPN for Android has not granted this app access yet")
            }
        }
    }

    override suspend fun disconnect() {
        val bound = service ?: return
        withContext(Dispatchers.IO) {
            runCatching { bound.disconnect() }
        }
        intendedServer = null
        _state.value = VpnState.Disconnected
    }

    override fun reportAttempt(server: VpnServer, index: Int, total: Int) {
        intendedServer = server
        _state.value = VpnState.Connecting(server, attempt = VpnState.Attempt(index, total))
    }

    companion object {
        const val OPENVPN_PACKAGE = "de.blinkt.openvpn"
        private const val BIND_TIMEOUT_MILLIS = 5_000L
    }
}
