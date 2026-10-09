package com.gios.brightremotelite.app

import com.gios.brightremotelite.companion.AuthenticationException
import com.gios.brightremotelite.companion.CompanionAuth
import com.gios.brightremotelite.companion.CompanionClient
import com.gios.brightremotelite.companion.HidCommand
import com.gios.brightremotelite.companion.InstalledApp
import com.gios.brightremotelite.companion.MediaControlFlags
import com.gios.brightremotelite.companion.PowerState
import com.gios.brightremotelite.companion.TouchPhase
import com.gios.brightremotelite.companion.Trace
import com.gios.brightremotelite.data.KeystoreKey
import com.gios.brightremotelite.data.PairedDevice
import com.gios.brightremotelite.data.Store
import com.gios.brightremotelite.data.Vault
import com.gios.brightremotelite.discovery.DiscoveredDevice
import com.gios.brightremotelite.discovery.MdnsBrowser
import com.gios.brightremotelite.proto.NowPlayingInfo
import com.gios.brightremotelite.ui.isPlausibleIpv4
import com.thelightphone.sdk.SealedLightContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ConnectionState { Disconnected, Connecting, Connected }

/** Everything the screens draw, in one place. */
data class RemoteState(
    val loaded: Boolean = false,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val activeId: String? = null,
    val activeName: String? = null,
    val power: PowerState = PowerState.Unknown,
    val controls: MediaControlFlags = MediaControlFlags.None,
    val muted: Boolean = false,
    val nowPlaying: NowPlayingInfo? = null,
    /** Something went wrong; shown as a banner until dismissed or replaced. */
    val error: String? = null,
    /** Progress worth narrating, e.g. looking for a TV that moved. */
    val status: String? = null,
    val paired: List<PairedDevice> = emptyList(),
    val discovered: List<DiscoveredDevice> = emptyList(),
    val scanning: Boolean = false,
    val scannedOnce: Boolean = false,
    val apps: List<InstalledApp> = emptyList(),
    val appsLoading: Boolean = false,
    val pinned: Set<String> = emptySet(),
    /** Text in the TV's focused field, or null when nothing is focused. */
    val fieldText: String? = null,
    val preferTouchpad: Boolean = true,
    val wheelHorizontal: Boolean = false,
)

/**
 * The one Companion link the tool holds, shared by every screen.
 *
 * Screens come and go; the connection should not. Holding it here, outside any view model, lets
 * the remote, the app list and the keyboard all talk to the same session.
 */
object Remote {

    /** Pair-verify is refused for a moment while the TV tears down its last session. */
    private const val CONNECT_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 1_200L

    /** Automatic reconnects after the TV drops the link on its own. */
    private const val LOST_RECONNECTS = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(RemoteState())
    val state: StateFlow<RemoteState> = _state.asStateFlow()

    private lateinit var store: Store
    private val ready = CompletableDeferred<CompanionClient>()
    @Volatile
    private var clientRef: CompanionClient? = null
    private val browser = MdnsBrowser()
    private var started = false

    private var connectJob: Job? = null
    private var scanJob: Job? = null
    private var lostReconnects = 0

    private var pendingDevice: DiscoveredDevice? = null
    private var pendingSetup: CompanionAuth.PairSetup? = null

    /** Called by the first screen. Loads storage and, if a TV is paired, connects to it. */
    fun start(context: SealedLightContext) {
        if (started) return
        started = true
        store = Store(context.dataStore, Vault(KeystoreKey::get))
        scope.launch {
            val identity = store.identity()
            val client = CompanionClient(identity, scope)
            client.onStateChanged = { publishClientState(client) }
            client.onDisconnected = { cause -> onLinkLost(cause) }
            client.onPressesIgnored = {
                _state.update { it.copy(error = "The Apple TV is not answering button presses. Try reconnecting.") }
            }
            _state.update {
                it.copy(
                    loaded = true,
                    paired = store.devices(),
                    pinned = store.pinnedApps(),
                    preferTouchpad = store.preferTouchpad(),
                    wheelHorizontal = store.wheelHorizontal(),
                )
            }
            clientRef = client
            ready.complete(client)
            autoConnect()
        }
    }

    private fun publishClientState(client: CompanionClient) {
        _state.update {
            it.copy(
                power = client.powerState,
                controls = client.mediaControlFlags,
                muted = client.isMuted,
                nowPlaying = client.nowPlaying,
            )
        }
    }

    // ------------------------------------------------------------------ connection

    private suspend fun autoConnect() {
        val devices = store.devices()
        if (devices.isEmpty()) return
        val last = store.lastDeviceId()
        connect(devices.firstOrNull { it.id == last } ?: devices.first())
    }

    /**
     * Connect to [device]. A tap ([byHand]) cancels an automatic attempt that is still grinding
     * through timeouts; automatic attempts defer to each other so a dropped link cannot stack
     * sockets.
     */
    fun connect(device: PairedDevice, byHand: Boolean = false) {
        if (connectJob?.isActive == true) {
            if (!byHand) return
            connectJob?.cancel()
        }
        val current = _state.value
        if (byHand && current.activeId == device.id && current.connection == ConnectionState.Connected &&
            clientRef?.isConnected == true
        ) {
            return
        }
        _state.update {
            it.copy(
                connection = ConnectionState.Connecting,
                activeId = device.id,
                activeName = device.name,
                error = null,
                status = null,
                apps = emptyList(),
            )
        }
        connectJob = scope.launch {
            val client = ready.await()
            store.setLastDeviceId(device.id)
            var host = device.host
            var port = device.port
            var lastError: Throwable? = null

            suspend fun attempt(): Boolean {
                val error = runCatching { client.connect(host, port, device.credentials) }.exceptionOrNull()
                if (error is CancellationException) throw error
                if (error != null) {
                    lastError = error
                    Trace.problem("connect to ${device.name} failed", error)
                    return false
                }
                lostReconnects = 0
                _state.update {
                    it.copy(
                        connection = ConnectionState.Connected,
                        status = null,
                        power = client.powerState,
                        controls = client.mediaControlFlags,
                        nowPlaying = client.nowPlaying,
                        muted = client.isMuted,
                        error = client.connectWarnings.takeIf { w -> w.isNotEmpty() }
                            ?.joinToString("; ", prefix = "Connected, but "),
                    )
                }
                return true
            }

            repeat(CONNECT_ATTEMPTS) { n ->
                if (n > 0) delay(RETRY_DELAY_MS shl (n - 1))
                if (attempt()) return@launch
            }

            // Nobody answered at the stored address. The usual reason is a DHCP lease that moved
            // while the TV was unplugged, so look its name up once and try the new address.
            if (lastError.looksLikeMovedAddress() && !isPlausibleIpv4(device.name)) {
                _state.update { it.copy(status = "Looking for ${device.name} on the network") }
                val found = browser.find(device.name)
                if (found != null && (found.host != host || found.port != port)) {
                    Trace.step("${device.name} moved from $host:$port to ${found.host}:${found.port}")
                    store.updateAddress(device.id, found.host, found.port)
                    host = found.host
                    port = found.port
                    _state.update { it.copy(paired = store.devices()) }
                    if (attempt()) return@launch
                }
            }

            _state.update {
                it.copy(
                    connection = ConnectionState.Disconnected,
                    status = null,
                    error = lastError?.friendlyMessage(),
                )
            }
        }
    }

    /** Retry by hand, cancelling whatever attempt is in flight. */
    fun reconnect() {
        scope.launch {
            lostReconnects = 0
            val devices = store.devices()
            val id = _state.value.activeId ?: store.lastDeviceId()
            val device = devices.firstOrNull { it.id == id } ?: devices.firstOrNull()
            if (device == null) {
                _state.update { it.copy(error = "No paired Apple TV to reconnect to") }
                return@launch
            }
            connect(device, byHand = true)
        }
    }

    /**
     * The tool came back on screen. A socket does not survive the phone sleeping, so this is the
     * moment to pick the link back up, if there is a TV to go back to and nothing is in flight.
     */
    fun onForeground() {
        if (clientRef == null) return
        if (_state.value.connection != ConnectionState.Disconnected) return
        if (connectJob?.isActive == true) return
        val id = _state.value.activeId ?: return
        scope.launch {
            val device = store.devices().firstOrNull { it.id == id } ?: return@launch
            lostReconnects = 0
            connect(device)
        }
    }

    private fun onLinkLost(cause: Throwable?) {
        val wasConnected = _state.value.connection == ConnectionState.Connected
        _state.update {
            it.copy(
                connection = if (connectJob?.isActive == true) it.connection else ConnectionState.Disconnected,
                power = PowerState.Unknown,
                controls = MediaControlFlags.None,
                nowPlaying = null,
            )
        }
        // A close we asked for reports no cause. A TV that hung up on its own gets two quiet
        // retries before it becomes the user's problem.
        if (cause == null || !wasConnected) return
        val id = _state.value.activeId ?: return
        if (lostReconnects >= LOST_RECONNECTS) {
            _state.update { it.copy(error = "Lost the connection to the Apple TV") }
            return
        }
        lostReconnects++
        scope.launch {
            delay(RETRY_DELAY_MS)
            val device = store.devices().firstOrNull { it.id == id } ?: return@launch
            connect(device)
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        _state.update {
            it.copy(connection = ConnectionState.Disconnected, activeId = null, activeName = null)
        }
        clientRef?.disconnect()
    }

    fun forget(device: PairedDevice) {
        if (_state.value.activeId == device.id) disconnect()
        scope.launch {
            store.forget(device.id)
            _state.update { it.copy(paired = store.devices()) }
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    // ------------------------------------------------------------------ discovery

    /** One user-started browse of a few seconds. Never repeats on its own. */
    fun scan() {
        if (scanJob?.isActive == true) return
        _state.update { it.copy(scanning = true, error = null) }
        scanJob = scope.launch {
            try {
                browser.browse().collect { found ->
                    val paired = _state.value.paired.map { it.name }.toSet()
                    _state.update { s -> s.copy(discovered = found.filter { it.name !in paired }) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "Could not search the network: ${e.friendlyMessage()}") }
            } finally {
                _state.update { it.copy(scanning = false, scannedOnce = true) }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    /**
     * Resolve a typed address. With no port given, ask the TV itself for its Companion service
     * (a unicast mDNS question to port 5353). Returns null when nothing answered.
     */
    suspend fun resolveAddress(host: String, port: Int?): DiscoveredDevice? {
        if (port != null) return DiscoveredDevice(name = host, host = host, port = port)
        val found = browser.probe(host) ?: return null
        // Filed under the typed address: that is the identity the user chose for it.
        return found.copy(host = host)
    }

    // ------------------------------------------------------------------ pairing

    /** Opens a connection and makes the TV show a four-digit code. */
    suspend fun beginPairing(device: DiscoveredDevice): Result<Unit> {
        stopScan()
        val client = ready.await()
        connectJob?.cancel()
        pendingDevice = device
        pendingSetup = null
        return runCatching {
            pendingSetup = client.startPairing(device.host, device.port)
        }.onFailure { if (it is CancellationException) throw it }
    }

    /** Finishes pairing with [pin], stores the credentials and connects. */
    suspend fun finishPairing(pin: String): Result<PairedDevice> {
        val client = ready.await()
        val setup = pendingSetup ?: return Result.failure(AuthenticationException("Pairing was not started"))
        val device = pendingDevice ?: return Result.failure(AuthenticationException("Pairing was not started"))
        val result = runCatching {
            val credentials = client.finishPairing(setup, pin)
            val paired = PairedDevice(
                id = device.name,
                name = device.name,
                host = device.host,
                port = device.port,
                credentials = credentials,
            )
            store.save(paired)
            paired
        }
        pendingSetup = null
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        result.getOrNull()?.let { paired ->
            pendingDevice = null
            _state.update { s ->
                s.copy(
                    paired = store.devices(),
                    discovered = s.discovered.filter { it.name != paired.name },
                )
            }
            connect(paired, byHand = true)
        }
        return result
    }

    /** Drops a pairing that did not finish. A no-op once pairing has succeeded. */
    fun cancelPairing() {
        if (pendingDevice == null && pendingSetup == null) return
        pendingSetup = null
        pendingDevice = null
        if (_state.value.connection != ConnectionState.Connected) clientRef?.disconnect()
    }

    // ------------------------------------------------------------------ commands

    /** Every command runs here, so a dropped link shows up once, in the banner. */
    private fun command(block: suspend (CompanionClient) -> Unit) {
        scope.launch {
            val client = ready.await()
            runCatching { block(client) }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                _state.update { it.copy(error = error.friendlyMessage()) }
            }
            publishClientState(client)
        }
    }

    fun press(button: HidCommand) = command { it.press(button) }

    /** A press the caller waits for; the wheel paces itself off how long one takes. */
    suspend fun pressAwait(button: HidCommand) {
        val client = ready.await()
        runCatching { client.press(button) }.onFailure { error ->
            if (error is CancellationException) throw error
            _state.update { it.copy(error = error.friendlyMessage()) }
        }
    }

    fun playPause() = command { it.press(HidCommand.PlayPause) }
    fun skipForward() = command { it.skipBy(15.0) }
    fun skipBackward() = command { it.skipBy(-15.0) }
    fun volumeUp() = command { it.press(HidCommand.VolumeUp) }
    fun volumeDown() = command { it.press(HidCommand.VolumeDown) }
    fun toggleMute() = command { it.toggleMute() }
    fun controlCenter() = command { it.controlCenter() }
    fun click() = command { it.click() }

    fun togglePower() = command { client ->
        if (client.powerState == PowerState.Off) client.turnOn() else client.turnOff()
        runCatching { client.refreshPowerState() }
    }

    /**
     * Not routed through [command]: a coroutine per touch sample is what scrambled their order on
     * the way to the socket. The client queues these itself, on one consumer.
     */
    fun touch(x: Int, y: Int, phase: TouchPhase) {
        clientRef?.touch(x, y, phase)
    }

    fun loadApps() {
        if (_state.value.appsLoading) return
        _state.update { it.copy(appsLoading = true) }
        scope.launch {
            val client = ready.await()
            runCatching { client.appList() }
                .onSuccess { apps -> _state.update { it.copy(apps = apps, appsLoading = false) } }
                .onFailure { error ->
                    _state.update { it.copy(appsLoading = false, error = error.friendlyMessage()) }
                }
        }
    }

    fun launchApp(app: InstalledApp) = command { it.launchApp(app.bundleId) }

    fun togglePin(app: InstalledApp) {
        scope.launch {
            val pinned = store.togglePin(app.bundleId)
            _state.update { it.copy(pinned = pinned) }
        }
    }

    fun loadFieldText() {
        scope.launch {
            val client = ready.await()
            val text = runCatching { client.currentText() }.getOrNull()
            _state.update { it.copy(fieldText = text) }
        }
    }

    fun sendText(text: String, replace: Boolean) {
        _state.update { it.copy(error = null) }
        scope.launch {
            val client = ready.await()
            runCatching { client.typeText(text, clearFirst = replace) }
                .onSuccess { updated ->
                    _state.update {
                        it.copy(
                            fieldText = updated,
                            error = if (updated == null) "No text field is focused on the Apple TV" else null,
                        )
                    }
                }
                .onFailure { error -> _state.update { it.copy(error = error.friendlyMessage()) } }
        }
    }

    fun setPreferTouchpad(value: Boolean) {
        _state.update { it.copy(preferTouchpad = value) }
        scope.launch { store.setPreferTouchpad(value) }
    }

    fun setWheelHorizontal(value: Boolean) {
        _state.update { it.copy(wheelHorizontal = value) }
        scope.launch { store.setWheelHorizontal(value) }
    }
}

/** Nothing answering at all, which is what a moved address looks like. */
private fun Throwable?.looksLikeMovedAddress(): Boolean = when (this) {
    is java.net.ConnectException,
    is java.net.NoRouteToHostException,
    is java.net.SocketTimeoutException,
    is java.net.UnknownHostException,
    -> true
    else -> false
}

/** Protocol errors already read well; anything else loses its class name. */
internal fun Throwable.friendlyMessage(): String = when (this) {
    is AuthenticationException -> message ?: "Authentication failed"
    is java.net.SocketTimeoutException -> "The Apple TV did not respond"
    is java.net.ConnectException -> "Could not reach the Apple TV. Is it on this network?"
    is java.net.UnknownHostException -> "Could not find the Apple TV on the network"
    else -> message?.takeIf { it.isNotBlank() } ?: (this::class.simpleName ?: "Something went wrong")
}
