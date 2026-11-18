package com.acremote.bluestar.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.acremote.bluestar.core.AcCommands
import com.acremote.bluestar.core.AcDevice
import com.acremote.bluestar.core.AcMode
import com.acremote.bluestar.core.AcState
import com.acremote.bluestar.core.FanSpeed
import com.acremote.bluestar.core.Redact
import com.acremote.bluestar.core.Swing
import com.acremote.bluestar.data.ApiException
import com.acremote.bluestar.data.BlueStarClient
import com.acremote.bluestar.data.CredentialStore
import com.acremote.bluestar.data.SavedLogin
import com.acremote.bluestar.data.toMap
import com.acremote.bluestar.BuildConfig
import org.json.JSONObject
import com.acremote.bluestar.data.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface UiState {
    data object Starting : UiState

    data class SignedOut(
        val authId: String = "",
        val isEmail: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,
    ) : UiState

    data class Ready(
        val userName: String,
        val devices: List<AcDevice>,
        val selectedId: String?,
        val refreshing: Boolean = false,
        /** Set while the cloud can't be reached; cleared on the next successful refresh. */
        val cloudError: String? = null,
    ) : UiState {
        val selected: AcDevice? get() = devices.firstOrNull { it.id == selectedId } ?: devices.firstOrNull()
    }
}

class RemoteViewModel(app: Application) : AndroidViewModel(app) {
    private val client = BlueStarClient()
    private val store = CredentialStore(app)

    private var login: SavedLogin? = null
    private var session: Session? = null

    private val _state = MutableStateFlow<UiState>(UiState.Starting)
    val state: StateFlow<UiState> = _state

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** Commands go out one at a time, in tap order. */
    private val commandLock = Mutex()
    private var refreshJob: Job? = null
    private var tempJob: Job? = null

    /** After a command, ignore polled state for a moment so the UI doesn't flick back. */
    private val holdUntil = mutableMapOf<String, Long>()

    init {
        val saved = store.load()
        if (saved != null) {
            viewModelScope.launch { doSignIn(saved) }
        } else {
            val last = store.lastAuthId()
            _state.value = UiState.SignedOut(authId = last?.first.orEmpty(), isEmail = last?.second ?: false)
        }
    }

    // ---- Sign in / out ------------------------------------------------------

    fun signIn(authId: String, password: String, isEmail: Boolean) {
        if (authId.isBlank() || password.isEmpty()) return
        viewModelScope.launch { doSignIn(SavedLogin(authId.trim(), password, isEmail)) }
    }

    private suspend fun doSignIn(creds: SavedLogin) {
        _state.value = UiState.SignedOut(creds.authId, creds.isEmail, busy = true)
        try {
            val s = io { client.login(creds.authId, creds.password, creds.isEmail) }
            val devices = io { client.fetchDevices(s) }
            login = creds
            session = s
            store.save(creds)
            val remembered = store.selectedDeviceId
            _state.value = UiState.Ready(
                userName = s.userName,
                devices = devices,
                selectedId = devices.firstOrNull { it.id == remembered }?.id ?: devices.firstOrNull()?.id,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = UiState.SignedOut(creds.authId, creds.isEmail, error = friendly(e, signingIn = true))
        }
    }

    fun signOut() {
        refreshJob?.cancel()
        tempJob?.cancel()
        store.clearPassword()
        val l = login
        login = null
        session = null
        _state.value = UiState.SignedOut(l?.authId.orEmpty(), l?.isEmail ?: false)
    }

    fun selectDevice(id: String) {
        store.selectedDeviceId = id
        _state.update { if (it is UiState.Ready) it.copy(selectedId = id) else it }
    }

    // ---- Status -------------------------------------------------------------

    /** [manual] shows a spinner and reports errors; polling refreshes are quiet. */
    fun refresh(manual: Boolean) {
        if (_state.value !is UiState.Ready || refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            if (manual) updateReady { it.copy(refreshing = true) }
            try {
                val previous = (readyState()?.devices ?: emptyList()).associate { it.id to it.state }
                val fresh = withSession { client.fetchDevices(it, previous) }
                val now = System.currentTimeMillis()
                updateReady { ready ->
                    val merged = fresh.map { d ->
                        val held = (holdUntil[d.id] ?: 0L) > now
                        if (held) d.copy(state = previous[d.id] ?: d.state) else d
                    }
                    ready.copy(devices = merged, refreshing = false, cloudError = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateReady { it.copy(refreshing = false, cloudError = "Can't reach the Blue Star cloud") }
                if (manual) _messages.trySend(friendly(e))
            }
        }
    }

    // ---- Controls -----------------------------------------------------------

    fun togglePower() = send { if (it.power) AcCommands.powerOff(it) else AcCommands.powerOn(it) }
    fun setMode(mode: AcMode) = send { AcCommands.setMode(it, mode) }
    fun setFan(fan: FanSpeed) = send { AcCommands.setFan(it, fan) }
    fun setSwing(swing: Swing) = send { AcCommands.setSwing(it, swing) }

    /** Updates the display at once but waits for taps to settle before sending. */
    fun changeTemperature(delta: Int) {
        val device = readyState()?.selected ?: return
        val cmd = AcCommands.setTemperature(device.state, device.state.targetTemp + delta)
        applyLocally(device.id, cmd.expected, extraHoldMs = TEMP_DEBOUNCE_MS)
        tempJob?.cancel()
        tempJob = viewModelScope.launch {
            delay(TEMP_DEBOUNCE_MS)
            dispatch(device.id, cmd.desired)
        }
    }

    private fun send(build: (AcState) -> AcCommands.Command) {
        // Don't block on the cloud's "connected" flag: it can be stale while the AC is
        // reachable, and the shadow delivers the command as soon as the AC is online.
        val device = readyState()?.selected ?: return
        val cmd = build(device.state)
        applyLocally(device.id, cmd.expected)
        viewModelScope.launch { dispatch(device.id, cmd.desired) }
    }

    private suspend fun dispatch(deviceId: String, desired: Map<String, Any>) {
        commandLock.withLock {
            try {
                withSession { client.sendCommand(it, deviceId, desired) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.trySend("Command not sent: ${friendly(e)}")
                holdUntil.remove(deviceId)
                refresh(manual = false) // show the AC's real state again
            }
        }
    }

    private fun applyLocally(deviceId: String, expected: AcState, extraHoldMs: Long = 0) {
        holdUntil[deviceId] = System.currentTimeMillis() + HOLD_MS + extraHoldMs
        updateReady { ready ->
            ready.copy(devices = ready.devices.map { if (it.id == deviceId) it.copy(state = expected) else it })
        }
    }

    // ---- Diagnostics --------------------------------------------------------

    /** What the Blue Star cloud and AWS return, with secrets and personal details masked. */
    suspend fun diagnosticsReport(): String {
        val sb = StringBuilder()
        sb.appendLine("AC Remote ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        session?.iot?.let { sb.appendLine("IoT region: ${it.region}") }
        sb.appendLine()
        val thingIds = mutableListOf<String>()
        sb.appendLine("== GET /things ==")
        try {
            val raw = withSession { client.fetchThingsRaw(it) }
            val obj = JSONObject(raw)
            obj.optJSONArray("things")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.optString("thing_id")?.takeIf { it.isNotBlank() }?.let(thingIds::add)
            }
            sb.appendLine(pretty(obj.toMap()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sb.appendLine("ERROR: ${e.javaClass.simpleName}: ${e.message}")
        }
        if (thingIds.isEmpty()) readyState()?.devices?.mapTo(thingIds) { it.id }
        for (id in thingIds.distinct()) {
            sb.appendLine()
            sb.appendLine("== Shadow of $id ==")
            try {
                val raw = withSession { client.getShadowRaw(it, id) }
                sb.appendLine(pretty(JSONObject(raw).toMap()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sb.appendLine("ERROR: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return sb.toString()
    }

    private fun pretty(map: Map<String, Any?>): String =
        (Redact.tree(map) as? Map<*, *>)?.let { JSONObject(it).toString(2) } ?: "{}"

    // ---- Helpers ------------------------------------------------------------

    /** Runs [block] with a valid session, signing in again once if it has expired. */
    private suspend fun <T> withSession(block: (Session) -> T): T {
        val current = session ?: relogin()
        return try {
            io { block(current) }
        } catch (e: ApiException) {
            if (!e.isAuthError) throw e
            val fresh = relogin()
            io { block(fresh) }
        }
    }

    private suspend fun relogin(): Session {
        val creds = login ?: throw ApiException(401, "Please sign in again")
        return try {
            io { client.login(creds.authId, creds.password, creds.isEmail) }.also { session = it }
        } catch (e: ApiException) {
            if (e.isAuthError) {
                // Password changed elsewhere: go back to the login screen.
                signOut()
                _state.value = UiState.SignedOut(creds.authId, creds.isEmail, error = "Please sign in again")
            }
            throw e
        }
    }

    private fun readyState(): UiState.Ready? = _state.value as? UiState.Ready

    private fun updateReady(f: (UiState.Ready) -> UiState.Ready) =
        _state.update { if (it is UiState.Ready) f(it) else it }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun friendly(e: Exception, signingIn: Boolean = false): String = when {
        e is ApiException && e.isAuthError && signingIn -> "Wrong mobile number/email or password"
        e is ApiException -> e.message ?: "Server error"
        e is IOException -> e.message?.takeIf { "credentials" in it } ?: "No internet connection"
        else -> e.message ?: "Something went wrong"
    }

    private companion object {
        const val HOLD_MS = 4_000L
        const val TEMP_DEBOUNCE_MS = 600L
    }
}
