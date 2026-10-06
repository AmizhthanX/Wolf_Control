package com.wolfpc.remote.ui

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wolfpc.remote.WolfApplication
import com.wolfpc.remote.data.model.CommandRecord
import com.wolfpc.remote.data.model.LoginChallenge
import com.wolfpc.remote.data.model.PairingPayload
import com.wolfpc.remote.data.model.PcStatus
import com.wolfpc.remote.security.BiometricSigner
import com.wolfpc.remote.security.SignResult
import com.wolfpc.remote.data.model.WolfCommand
import com.wolfpc.remote.data.net.WolfApiException
import com.wolfpc.remote.domain.SendOutcome
import com.wolfpc.remote.security.KeyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Stage { LOADING, SETUP, SIGN_IN, PAIR, READY }

data class WolfUiState(
    val stage: Stage = Stage.LOADING,
    val pcName: String = "",
    val pcId: String = "",
    val email: String = "",
    val status: PcStatus? = null,
    val history: List<CommandRecord> = emptyList(),
    val realtimeConnected: Boolean = false,
    /** False once a cloud read has failed for lack of a network. */
    val cloudReachable: Boolean = true,
    /** Answered a direct probe on the home network just now. */
    val lanControllerUp: Boolean = false,
    val lanAgentUp: Boolean = false,
    // What is actually stored, so the settings screen shows the truth rather
    // than its own defaults.
    val lanControllerHost: String = "",
    val lanAgentHost: String = "",
    val lanFingerprint: String = "",
    val preferLan: Boolean = true,
    /** False when Windows has classified the PC's network Public. */
    val agentNetworkPrivate: Boolean = true,
    /** An open Windows login prompt waiting on this phone (Phase 8). */
    val pendingLogin: LoginChallenge? = null,
    val refreshing: Boolean = false,
    val busy: Boolean = false,
    /** Prefills the pairing screen after a scan. */
    val scannedCode: String? = null,
    val message: String? = null,
    val error: String? = null,
)

class WolfViewModel(private val app: WolfApplication) : ViewModel() {

    private companion object { const val TAG = "WolfState" }

    private val _state = MutableStateFlow(WolfUiState())
    val state: StateFlow<WolfUiState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private val refreshMutex = Mutex()

    init {
        viewModelScope.launch { resolveStage() }
        viewModelScope.launch {
            app.realtime.connected.collect { connected ->
                _state.value = _state.value.copy(realtimeConnected = connected)
            }
        }
        viewModelScope.launch {
            // Realtime tells us *that* something changed; the authoritative row
            // still comes from a read, which keeps RLS as the single source of
            // truth rather than trusting the payload on the socket.
            app.realtime.changes.collect { refresh() }
        }
    }

    private suspend fun resolveStage() {
        val snapshot = app.sessionStore.snapshot()
        val stage = when {
            snapshot.supabaseUrl.isBlank() || snapshot.anonKey.isBlank() -> Stage.SETUP
            snapshot.accessToken.isBlank() -> Stage.SIGN_IN
            snapshot.pcId.isBlank() -> Stage.PAIR
            else -> Stage.READY
        }

        _state.value = _state.value.copy(
            stage = stage,
            pcId = snapshot.pcId,
            pcName = snapshot.pcName.ifBlank { "Wolf PC" },
            email = app.sessionStore.userEmail.first(),
            scannedCode = if (stage == Stage.PAIR) scannedCode else null,
            lanControllerHost = snapshot.lanControllerHost,
            lanAgentHost = snapshot.lanAgentHost,
            lanFingerprint = snapshot.lanAgentFingerprint,
            preferLan = snapshot.preferLan,
        )

        if (stage == Stage.READY) startWatching(snapshot.pcId)
    }

    // ----------------------------------------------------------- setup ----

    fun saveProject(url: String, anonKey: String) = launchGuarded {
        if (!url.startsWith("https://")) {
            fail("The project URL must start with https://")
            return@launchGuarded
        }
        app.sessionStore.setProject(url, anonKey)
        resolveStage()
    }

    fun signIn(email: String, password: String) = launchGuarded {
        app.supabase.signIn(email, password)
        resolveStage()
    }

    /**
     * Apply everything a scanned pairing QR carried.
     *
     * The scan can arrive at either the setup screen or the pair screen, and a
     * payload may be partial, so each piece is applied independently and the
     * stage is recomputed afterwards. The pairing code is held rather than
     * redeemed here: the user usually still has to sign in first, and a code
     * redeemed against no session would be burnt for nothing.
     */
    fun applyScannedPayload(payload: PairingPayload) = launchGuarded {
        if (payload.hasProject) {
            app.sessionStore.setProject(payload.supabaseUrl!!, payload.anonKey!!)
        }

        if (payload.hasLan) {
            val snapshot = app.sessionStore.snapshot()
            app.sessionStore.setLan(
                controllerHost = payload.controllerHost ?: snapshot.lanControllerHost,
                agentHost = payload.agentHost!!,
                agentFingerprint = payload.fingerprint!!,
            )
        }

        scannedCode = payload.code

        resolveStage()

        _state.value = _state.value.copy(
            scannedCode = payload.code,
            message = when {
                payload.code != null && payload.hasLan ->
                    "Scanned. Project and home network settings applied."
                payload.code != null -> "Scanned pairing code ${payload.code}"
                payload.hasProject -> "Scanned project settings"
                else -> "Scanned"
            },
        )
    }

    /** Held between the scan and the moment there is a session to redeem it with. */
    private var scannedCode: String? = null

    fun pair(activity: FragmentActivity, code: String) = launchGuarded {
        if (!KeyManager.ensureKey(activity)) {
            fail("This phone could not create a secure signing key.")
            return@launchGuarded
        }
        val publicKey = KeyManager.publicKeyB64Url()
        if (publicKey == null) {
            fail("Could not read the signing key.")
            return@launchGuarded
        }

        val result = app.supabase.pairDevice(
            code = code.trim().uppercase(),
            publicKey = publicKey,
            deviceName = android.os.Build.MODEL ?: "Android phone",
        )
        app.sessionStore.setPairing(result.pcId, result.pcName, result.deviceId)
        scannedCode = null
        _state.value = _state.value.copy(message = "Paired with ${result.pcName}")
        resolveStage()
    }

    // -------------------------------------------------------- commands ----

    fun send(activity: FragmentActivity, command: WolfCommand) = launchGuarded {
        when (val outcome = app.commandSender.send(activity, command)) {
            is SendOutcome.Sent -> {
                val via = if (outcome.path == SendOutcome.Path.LAN) "over Wi-Fi" else "through the cloud"
                _state.value = _state.value.copy(message = "${label(command)} sent $via")
                // Give the device a beat to pick the command up before the
                // history read, so the user sees SENT rather than QUEUED.
                delay(700)
                refresh()
            }
            SendOutcome.Cancelled -> Unit
            SendOutcome.ReEnrolNeeded -> fail(
                "Your biometric enrolment changed, so this phone's key was destroyed. " +
                    "Pair the phone again in Settings.",
            )
            is SendOutcome.Failed -> fail(outcome.message)
        }
    }

    // ------------------------------------------------------ phone unlock ----

    /**
     * Approve a Windows login (PRD section 15).
     *
     * The signature is the entire approval. It is produced by a Keystore key
     * that demands a fresh biometric for every single operation, so it is
     * evidence that a fingerprint was presented to *this* phone seconds ago -
     * not that an app said so.
     */
    fun approveLogin(activity: FragmentActivity) = launchGuarded {
        val challenge = _state.value.pendingLogin ?: return@launchGuarded

        if (!challenge.isOpen) {
            _state.value = _state.value.copy(pendingLogin = null)
            fail("That login request expired. Click the tile on the PC again.")
            return@launchGuarded
        }

        when (val outcome = BiometricSigner.sign(
            activity,
            title = "Unlock ${_state.value.pcName.ifBlank { "your PC" }}",
            subtitle = "Confirm to sign in on Windows",
            message = challenge.signingString(),
        )) {
            is SignResult.Ok -> {
                runCatching {
                    app.supabase.respondToLogin(challenge.id, outcome.signatureB64Url, approve = true)
                }.onSuccess {
                    _state.value = _state.value.copy(
                        pendingLogin = null,
                        message = "Approved. Windows is signing you in.",
                    )
                }.onFailure { fail(readable(it)) }
            }
            // Cancelling the prompt is not a denial. The user may have fumbled
            // the sensor, and the challenge is still open for them to retry;
            // denying on their behalf would send them back to the PC.
            SignResult.Cancelled -> Unit
            SignResult.KeyInvalidated -> fail(
                "Your biometric enrolment changed, so this phone's key was destroyed. " +
                    "Pair the phone again in Settings.",
            )
            is SignResult.Failed -> fail(outcome.message)
        }
    }

    /** Refuse the login. No signature: a denial proves nothing and needs to prove nothing. */
    fun denyLogin() = launchGuarded {
        val challenge = _state.value.pendingLogin ?: return@launchGuarded
        _state.value = _state.value.copy(pendingLogin = null)
        runCatching { app.supabase.respondToLogin(challenge.id, signature = null, approve = false) }
            .onFailure { android.util.Log.w(TAG, "deny failed: $it") }
    }

    private fun readable(t: Throwable): String =
        (t as? WolfApiException)?.message ?: t.message ?: "that did not work"

    private fun label(command: WolfCommand) = when (command) {
        WolfCommand.POWER_ON -> "Power on"
        WolfCommand.SHUTDOWN -> "Shutdown"
        WolfCommand.RESTART -> "Restart"
        WolfCommand.HARD_RESET -> "Hard reset"
        WolfCommand.LOGIN -> "Unlock"
        WolfCommand.LOCK -> "Lock"
    }

    // ---------------------------------------------------------- status ----

    private fun startWatching(pcId: String) {
        app.realtime.start(pcId)
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                refresh()
                // Realtime carries the load; this is the safety net for a
                // dropped socket, and it is why the UI is never stale for more
                // than a few seconds even on a flaky connection.
                delay(if (_state.value.realtimeConnected) 20_000 else 4_000)
            }
        }
    }

    fun refresh() = viewModelScope.launch {
        val pcId = _state.value.pcId
        if (pcId.isBlank()) return@launch
        if (!refreshMutex.tryLock()) return@launch
        _state.value = _state.value.copy(refreshing = true)
        try {
            runCatching {
                val status = app.supabase.fetchStatus(pcId)
                val history = app.supabase.fetchHistory(pcId, limit = 30)
                _state.value = _state.value.copy(status = status, history = history)

                // Notifications come from what the app observes, since there is no
                // push in this build. Driven from here rather than from the
                // Realtime callback so the polling fallback produces them too — a
                // dropped socket should cost latency, not notifications.
                status?.let { app.stateNotifier.onStatus(it) }
                app.stateNotifier.onCommands(history)
                _state.value = _state.value.copy(cloudReachable = true)

                // The cloud answered, but it can only report what the devices
                // managed to tell *it*. A controller that is on your Wi-Fi but
                // cannot reach Supabase reads as offline here and is perfectly
                // reachable from the sofa, so check before believing it.
                android.util.Log.i(
                    TAG,
                    "cloud ok: controller=${status?.controllerOnline} agent=${status?.agentOnline} " +
                        "windows=${status?.windowsStateRaw}",
                )

                // Adopt whatever the devices last reported. This is the only thing
                // that keeps the offline path working across a DHCP change: the
                // address is learned while the cloud is up and cached for when it
                // is not.
                status?.let { adoptReportedAddresses(it) }

                // A login prompt has 60 seconds and somebody is standing at a
                // login screen for all of them, so this is read on every refresh
                // rather than only when Realtime says the table moved. Realtime
                // makes it arrive in about a second; this makes it arrive at all
                // when the socket has dropped.
                _state.value = _state.value.copy(
                    pendingLogin = runCatching { app.supabase.fetchPendingChallenge(pcId) }
                        .getOrNull(),
                )

                if (status == null || !status.controllerOnline || !status.agentOnline) {
                    probeLan()
                }
            }.onFailure {
                if (it is CancellationException) throw it
                if (it is WolfApiException && it.code == "offline") {
                    // The cloud is unreachable, so every presence flag it would
                    // have supplied is unknowable — not false. Ask the devices
                    // directly instead; that is the whole point of the LAN path,
                    // and without this the UI disables exactly the buttons that
                    // still work.
                    android.util.Log.i(TAG, "cloud unreachable; falling back to the LAN")
                    _state.value = _state.value.copy(cloudReachable = false)
                    probeLan()
                    return@onFailure
                }
                // Anything else used to vanish here without trace, which made an
                // expired session and a broken query look identical from outside.
                android.util.Log.w(TAG, "cloud read failed: $it")
                fail(readable(it))
            }
        } finally {
            _state.value = _state.value.copy(refreshing = false)
            refreshMutex.unlock()
        }
    }

    /**
     * Keep the stored LAN addresses in step with what the devices report.
     *
     * Addresses are stored without a scheme and dialled directly, so the
     * offline path never needs a name resolved. That matters more than it
     * sounds: a phone with strict Private DNS resolves nothing at all once the
     * internet is down, mDNS names included, so an app that only knows
     * "wolfpc.local" has no way to reach a controller sitting two metres away.
     */
    private suspend fun adoptReportedAddresses(status: PcStatus) {
        val snapshot = app.sessionStore.snapshot()

        val controller = status.controllerLanAddress?.takeIf { it.isNotBlank() }
            ?: snapshot.lanControllerHost
        val agent = status.agentLanAddress?.takeIf { it.isNotBlank() }
            ?: snapshot.lanAgentHost

        if (controller != snapshot.lanControllerHost || agent != snapshot.lanAgentHost) {
            android.util.Log.i(TAG, "lan addresses updated: controller=$controller agent=$agent")
            app.sessionStore.setLan(controller, agent, snapshot.lanAgentFingerprint)
            _state.value = _state.value.copy(
                lanControllerHost = controller,
                lanAgentHost = agent,
            )
        }

        if (status.agentNetworkPrivate != _state.value.agentNetworkPrivate) {
            _state.value = _state.value.copy(agentNetworkPrivate = status.agentNetworkPrivate)
        }
    }

    private var lastProbe = 0L

    /**
     * Ask the controller and the agent directly whether they are there.
     *
     * Rate limited because each probe costs a two second timeout when nothing
     * answers, and the offline poll runs every four seconds — without this the
     * probes would overlap and the UI would sit permanently mid-check.
     */
    private suspend fun probeLan() {
        val now = System.currentTimeMillis()
        if (now - lastProbe < 8_000) return
        lastProbe = now

        val snapshot = app.sessionStore.snapshot()
        if (!snapshot.preferLan) {
            _state.value = _state.value.copy(lanControllerUp = false, lanAgentUp = false)
            return
        }

        val controller = app.lan.controllerReachable(snapshot.lanControllerHost)
        val agent = app.lan.agentReachable(snapshot.lanAgentHost, snapshot.lanAgentFingerprint)

        android.util.Log.i(
            TAG,
            "lan probe: controller(${snapshot.lanControllerHost})=$controller " +
                "agent(${snapshot.lanAgentHost})=$agent",
        )

        _state.value = _state.value.copy(lanControllerUp = controller, lanAgentUp = agent)
    }

    // ------------------------------------------------------- settings ----

    fun setPreferLan(value: Boolean) = viewModelScope.launch {
        app.sessionStore.setPreferLan(value)
    }

    fun setLanHosts(controllerHost: String, agentHost: String, fingerprint: String) =
        viewModelScope.launch {
            // Blank means "left alone", never "erase". These arrive from text
            // fields, and a field that failed to load its value must not be
            // able to destroy a working configuration by being saved.
            val current = app.sessionStore.snapshot()
            app.sessionStore.setLan(
                controllerHost = controllerHost.trim().ifBlank { current.lanControllerHost },
                agentHost = agentHost.trim().ifBlank { current.lanAgentHost },
                agentFingerprint = fingerprint.trim().ifBlank { current.lanAgentFingerprint },
            )
            resolveStage()
            _state.value = _state.value.copy(message = "Local network settings saved")
        }

    fun signOut() = viewModelScope.launch {
        app.stateNotifier.reset()
        app.realtime.stop()
        pollJob?.cancel()
        app.sessionStore.clearSession()
        resolveStage()
    }

    /**
     * Full unpair: revoke server-side, then forget the PC and destroy the key.
     *
     * Revoking first is the whole point. Destroying the local key stops *this*
     * phone signing anything, but the devices pin the public key they were
     * given and go on trusting it forever — so a local-only unpair leaves a
     * trusted identity nobody can use and nobody can withdraw, and "Unpair"
     * would be no use at all for the case it exists for: a phone that is gone.
     *
     * If the revoke cannot be delivered, nothing local is cleared. Wiping the
     * session first would take away the credentials needed to revoke at all,
     * stranding a live key with no way to reach it from the app.
     */
    fun unpair() = launchGuarded {
        val snapshot = app.sessionStore.snapshot()

        if (snapshot.phoneDeviceId.isNotBlank()) {
            try {
                app.supabase.revokePhone(snapshot.phoneDeviceId)
            } catch (e: WolfApiException) {
                fail(
                    "Could not revoke this phone on the server, so it has not been " +
                        "unpaired. Its key would stay trusted by your PC. Reconnect " +
                        "and try again.",
                )
                return@launchGuarded
            }
        }

        app.stateNotifier.reset()
        app.realtime.stop()
        pollJob?.cancel()
        KeyManager.deleteKey()
        app.sessionStore.clearAll()
        _state.value = WolfUiState(message = "Unpaired. This phone can no longer control the PC.")
        resolveStage()
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null, error = null)
    }

    // -------------------------------------------------------- plumbing ----

    private fun launchGuarded(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                block()
            } catch (e: WolfApiException) {
                fail(e.message ?: "Something went wrong")
            } catch (e: Exception) {
                fail(e.message ?: "Something went wrong")
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(error = message)
    }

    override fun onCleared() {
        app.realtime.stop()
        super.onCleared()
    }

    class Factory(private val app: WolfApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = WolfViewModel(app) as T
    }
}
