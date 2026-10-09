package com.gios.brightremotelite.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.gios.brightremotelite.R
import com.gios.brightremotelite.app.ConnectionState
import com.gios.brightremotelite.app.Remote
import com.gios.brightremotelite.app.RemoteState
import com.gios.brightremotelite.companion.CompanionClient
import com.gios.brightremotelite.companion.HidCommand
import com.gios.brightremotelite.companion.PowerState
import com.gios.brightremotelite.companion.TouchPhase
import com.gios.brightremotelite.hw.LightKeys
import com.gios.brightremotelite.hw.NotchGuard
import com.gios.brightremotelite.hw.StepBank
import com.gios.brightremotelite.proto.NowPlayingInfo
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightVibrationUsage
import com.thelightphone.sdk.LightVibrationWaveform
import com.thelightphone.sdk.LightVibrator
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.VibrationAmplitude
import com.thelightphone.sdk.ui.LightProgressBar
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.time.Duration.Companion.milliseconds

/**
 * Hears the wheel and the volume rocker while the remote is on screen.
 *
 * The wheel walks the tvOS focus, two notches a row, no faster than one step per
 * [MIN_STEP_INTERVAL_MS]. Each step is awaited, so a slow link slows the wheel down instead of
 * queueing presses that keep moving the focus after the thumb stops.
 */
class RemoteViewModel : LightViewModel<Unit>() {

    private companion object {
        const val MIN_STEP_INTERVAL_MS = 110L
    }

    private val guard = NotchGuard()
    private val bank = StepBank()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val connected: Boolean get() = Remote.state.value.connection == ConnectionState.Connected

    init {
        viewModelScope.launch {
            while (true) {
                wake.receive()
                while (true) {
                    val step = bank.take()
                    if (step == 0) break
                    val started = System.nanoTime()
                    val horizontal = Remote.state.value.wheelHorizontal
                    Remote.pressAwait(
                        when {
                            horizontal -> if (step > 0) HidCommand.Left else HidCommand.Right
                            else -> if (step > 0) HidCommand.Up else HidCommand.Down
                        },
                    )
                    val elapsed = (System.nanoTime() - started) / 1_000_000
                    if (elapsed < MIN_STEP_INTERVAL_MS) delay(MIN_STEP_INTERVAL_MS - elapsed)
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        LightKeys.wheel(keyCode, event)?.let { notch ->
            if (!connected) return true
            val emit = guard.accept(notch, System.nanoTime() / 1_000_000)
            if (emit != 0) {
                bank.add(emit)
                wake.trySend(Unit)
            }
            return true
        }
        // The rocker drives the TV only while one is connected; otherwise the phone keeps it.
        LightKeys.volume(keyCode)?.let { delta ->
            if (!connected) return false
            if (delta > 0) Remote.volumeUp() else Remote.volumeDown()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (LightKeys.wheel(keyCode, event) != null) return true
        // Swallow the release too, or Android pops its own volume panel over the remote.
        if (LightKeys.volume(keyCode) != null && connected) return true
        return super.onKeyUp(keyCode, event)
    }
}

/**
 * The remote. Nearly the whole panel is the touch surface; Back, Home and More sit along the
 * bottom, and everything else slides up from behind More.
 */
@InitialScreen
class RemoteScreen(private val sealedActivity: SealedLightActivity) :
    LightScreen<Unit, RemoteViewModel>(sealedActivity) {

    override val viewModelClass = RemoteViewModel::class.java
    override fun createViewModel() = RemoteViewModel()

    init {
        Remote.start(lightContext)
    }

    override fun willShow() {
        Remote.onForeground()
    }

    private fun openDevices() = navigateTo(::DevicesScreen)
    private fun openApps() = navigateTo(::AppsScreen)
    private fun openKeyboard() = navigateTo(::KeyboardScreen)

    @Composable
    override fun Content() {
        val state by Remote.state.collectAsState()
        var showMore by remember { mutableStateOf(false) }
        val connected = state.connection == ConnectionState.Connected
        val vibrator = sealedActivity.vibrator

        Themed {
            Column(Modifier.fillMaxSize()) {
                TopRow(state, vibrator, onDevices = ::openDevices)
                NowPlayingBar(state.nowPlaying)
                Banner(state.error ?: state.status) { Remote.dismissError() }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (state.connection) {
                        ConnectionState.Connecting -> Centered("Connecting", state.activeName)
                        ConnectionState.Disconnected -> Disconnected(state, ::openDevices)
                        ConnectionState.Connected ->
                            if (state.preferTouchpad) Touchpad(vibrator, Modifier.fillMaxSize())
                            else DirectionPad(state, Modifier.fillMaxSize())
                    }
                }

                if (showMore && connected) {
                    MorePanel(
                        state = state,
                        onApps = { showMore = false; openApps() },
                        onKeyboard = { showMore = false; openKeyboard() },
                    )
                }
                BottomRow(
                    enabled = connected,
                    vibrator = vibrator,
                    moreOpen = showMore,
                    onMore = { showMore = !showMore },
                    onMoreHold = { showMore = false; openKeyboard() },
                )
            }
        }
    }
}

@Composable
private fun TopRow(state: RemoteState, vibrator: LightVibrator, onDevices: () -> Unit) {
    val connected = state.connection == ConnectionState.Connected
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(4f.gridUnitsAsDp())
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = "TVs",
            variant = LightTextVariant.Fine,
            modifier = Modifier.lightClickable(onClick = onDevices).padding(vertical = 1f.gridUnitsAsDp()),
        )
        LightText(
            text = state.activeName ?: "",
            variant = LightTextVariant.Fine,
            lighten = !connected,
            align = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 1f.gridUnitsAsDp()),
        )
        PowerButton(
            enabled = connected,
            lit = state.power == PowerState.On || state.power == PowerState.Screensaver,
            vibrator = vibrator,
        )
    }
}

/**
 * Power is held for three seconds rather than tapped: it sits where a thumb passes, and putting
 * the TV to sleep by accident is the most annoying thing this tool could do. The vibration is the
 * progress bar. It climbs while the finger stays down, stops if it lifts early, and ends in a
 * firm pulse when it fires.
 */
@Composable
private fun PowerButton(enabled: Boolean, lit: Boolean, vibrator: LightVibrator) {
    val scope = rememberCoroutineScope()
    val active by rememberUpdatedState(enabled)
    Box(
        modifier = Modifier
            .size(3f.gridUnitsAsDp())
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    if (!active) return@awaitEachGesture
                    vibrator.vibrate(POWER_RAMP, LightVibrationUsage.Touch)
                    val fire: Job = scope.launch {
                        delay(POWER_HOLD_MS)
                        vibrator.vibrate(120.milliseconds, VibrationAmplitude(255))
                        Remote.togglePower()
                    }
                    waitForUpOrCancellation()
                    if (fire.isActive) {
                        fire.cancel()
                        vibrator.cancel()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        RemoteIcon(
            R.drawable.rl_power_white,
            if (enabled) "Power, hold for three seconds" else "Power",
            dim = !enabled || !lit,
        )
    }
}

private const val POWER_HOLD_MS = 3_000L

/** Thirty 100 ms steps from faint to full: a buzz that climbs across the three-second hold. */
private val POWER_RAMP = LightVibrationWaveform(
    durations = List(30) { 100.milliseconds },
    amplitudes = List(30) { VibrationAmplitude(40 + it * 7) },
)

@Composable
private fun Centered(text: String, detail: String? = null) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 2f.gridUnitsAsDp()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LightText(text = text, variant = LightTextVariant.Copy, align = TextAlign.Center)
        if (detail != null) {
            LightText(text = detail, variant = LightTextVariant.Detail, lighten = true, align = TextAlign.Center)
        }
    }
}

@Composable
private fun Disconnected(state: RemoteState, onDevices: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 1f.gridUnitsAsDp())) {
        Box(Modifier.weight(1f)) {
            when {
                !state.loaded -> Centered("Starting")
                state.activeName == null && state.paired.isEmpty() ->
                    Centered("No Apple TV paired", "Open TVs to find one on your network.")
                state.activeName == null -> Centered("Not connected", "Pick a TV to connect.")
                else -> Centered("Not connected", "Tap Retry to reconnect.")
            }
        }
        if (state.activeName != null || state.paired.isNotEmpty()) {
            ListRow(title = "Retry", onClick = { Remote.reconnect() })
        }
        ListRow(title = "TVs", detail = "Find, pair or switch Apple TVs", onClick = onDevices)
    }
}

/**
 * How much of the TV's touch surface one panel-width drag covers. At 0.5 a drag across the whole
 * panel is half the surface: the panel is far bigger than a Siri Remote's pad, and a one-to-one
 * mapping arrived as a long fast stroke that tvOS reads as a flick.
 */
private const val SWIPE_GAIN = 0.5f
private const val TOUCH_INTERVAL_MS = 16L

/**
 * The swipe surface: the whole area, nothing drawn on it.
 *
 * Coordinates are relative to where the finger landed, starting from the middle of the TV's
 * surface. Nothing is sent until the finger passes the touch slop, so a tap that wobbles a few
 * pixels is still a tap and never a small swipe. The drag then opens from where the finger
 * started. Samples are throttled to about one per 16 ms.
 */
@Composable
private fun Touchpad(vibrator: LightVibrator, modifier: Modifier) {
    Box(
        modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    vibrator.click()
                    val slop = viewConfiguration.touchSlop
                    val pad = CompanionClient.TOUCHPAD_SIZE
                    val centre = pad / 2f
                    val scaleX = pad / size.width.toFloat() * SWIPE_GAIN
                    val scaleY = pad / size.height.toFloat() * SWIPE_GAIN
                    val startX = down.position.x
                    val startY = down.position.y

                    fun padX(x: Float) = (centre + (x - startX) * scaleX).toInt().coerceIn(0, pad)
                    fun padY(y: Float) = (centre + (y - startY) * scaleY).toInt().coerceIn(0, pad)

                    var dragging = false
                    var lastSent = 0L
                    var lastX = startX
                    var lastY = startY
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            lastX = change.position.x
                            lastY = change.position.y
                            if (!change.pressed) {
                                if (dragging) {
                                    Remote.touch(padX(lastX), padY(lastY), TouchPhase.Release)
                                    dragging = false
                                } else {
                                    Remote.click()
                                }
                                break
                            }
                            if (!dragging) {
                                if (hypot(lastX - startX, lastY - startY) < slop) continue
                                dragging = true
                                Remote.touch(padX(startX), padY(startY), TouchPhase.Press)
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastSent >= TOUCH_INTERVAL_MS) {
                                Remote.touch(padX(lastX), padY(lastY), TouchPhase.Hold)
                                lastSent = now
                            }
                        }
                    } finally {
                        // A cancelled pointer must still lift the finger, or the TV believes it is
                        // still down: the touchpad's version of a stuck key.
                        if (dragging) Remote.touch(padX(lastX), padY(lastY), TouchPhase.Release)
                    }
                }
            },
    )
}

/**
 * Up, down, left and right around a centre Select, filling the panel. Skip back and forward 15
 * seconds sit either side of Down so that row reads as a timeline; they dim when the TV reports no
 * playback controls. The corners stay dead: a diagonal thumb means "not sure".
 */
@Composable
private fun DirectionPad(state: RemoteState, modifier: Modifier) {
    val playing = state.controls.anyPlayback
    Column(modifier) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Spacer(Modifier.weight(1f))
            PadCell(Modifier.weight(2f), onClick = { Remote.press(HidCommand.Up) }) {
                RemoteIcon(R.drawable.rl_up_white, "Up", sizeUnits = 3f)
            }
            Spacer(Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            PadCell(Modifier.weight(1f), onClick = { Remote.press(HidCommand.Left) }) {
                RemoteIcon(R.drawable.rl_back_white, "Left", sizeUnits = 3f)
            }
            PadCell(Modifier.weight(2f), onClick = { Remote.press(HidCommand.Select) }) {
                LightText(text = "OK", variant = LightTextVariant.Heading)
            }
            PadCell(Modifier.weight(1f), onClick = { Remote.press(HidCommand.Right) }) {
                RemoteIcon(R.drawable.rl_arrow_right_white, "Right", sizeUnits = 3f)
            }
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            PadCell(Modifier.weight(1f), enabled = playing, onClick = { Remote.skipBackward() }) {
                RemoteIcon(R.drawable.rl_skip_backward_fifteen_white, "Back 15 seconds", sizeUnits = 3f, dim = !playing)
            }
            PadCell(Modifier.weight(2f), onClick = { Remote.press(HidCommand.Down) }) {
                RemoteIcon(R.drawable.rl_down_white, "Down", sizeUnits = 3f)
            }
            PadCell(Modifier.weight(1f), enabled = playing, onClick = { Remote.skipForward() }) {
                RemoteIcon(R.drawable.rl_skip_forward_fifteen_white, "Forward 15 seconds", sizeUnits = 3f, dim = !playing)
            }
        }
    }
}

@Composable
private fun PadCell(
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxHeight().lightClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * The drawer: playback, then volume, then the face toggle, the wheel axis, apps and typing.
 * Playback dims when the TV reports no media controls; mute dims when it reports no volume
 * control, since Companion's mute is SetVolume(0) and only works where the TV owns the volume.
 */
@Composable
private fun MorePanel(state: RemoteState, onApps: () -> Unit, onKeyboard: () -> Unit) {
    val playing = state.controls.anyPlayback
    Column(
        Modifier
            .fillMaxWidth()
            .background(LightThemeTokens.colors.background)
            .padding(vertical = 0.5f.gridUnitsAsDp()),
    ) {
        PanelRow {
            PanelIcon(R.drawable.rl_skip_backward_fifteen_white, "Back 15 seconds", dim = !playing) { Remote.skipBackward() }
            PanelIcon(R.drawable.rl_play_white, "Play or pause") { Remote.playPause() }
            PanelIcon(R.drawable.rl_skip_forward_fifteen_white, "Forward 15 seconds", dim = !playing) { Remote.skipForward() }
        }
        PanelRow {
            PanelIcon(R.drawable.rl_volume_down_white, "Volume down") { Remote.volumeDown() }
            PanelIcon(
                R.drawable.rl_mute_white,
                if (state.muted) "Unmute" else "Mute",
                dim = state.muted || !state.controls.volume,
            ) { Remote.toggleMute() }
            PanelIcon(R.drawable.rl_volume_up_white, "Volume up") { Remote.volumeUp() }
        }
        PanelRow {
            PanelIcon(
                if (state.preferTouchpad) R.drawable.rl_dpad_white else R.drawable.rl_trackpad_white,
                if (state.preferTouchpad) "Switch to D-pad" else "Switch to touchpad",
            ) { Remote.setPreferTouchpad(!state.preferTouchpad) }
            PanelIcon(
                if (state.wheelHorizontal) R.drawable.rl_wheel_horizontal_white else R.drawable.rl_wheel_vertical_white,
                if (state.wheelHorizontal) "Wheel moves sideways, tap for up and down" else "Wheel moves up and down, tap for sideways",
            ) { Remote.setWheelHorizontal(!state.wheelHorizontal) }
            PanelIcon(R.drawable.rl_apps_grid_white, "Apps", onClick = onApps)
            PanelIcon(R.drawable.rl_keyboard_white, "Type", onClick = onKeyboard)
        }
    }
}

@Composable
private fun PanelRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 0.5f.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun PanelIcon(resource: Int, label: String, dim: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(4f.gridUnitsAsDp()).lightClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        RemoteIcon(resource, label, dim = dim)
    }
}

/**
 * Back, Home and More, each with a hold. Home held is Control Centre, which is what tvOS does with
 * it. More held skips the drawer and goes straight to typing, since searching is the one thing you
 * pick the phone up already meaning to do. tvOS has no separate Menu button; the HID command Apple
 * calls Menu is Back.
 */
@Composable
private fun BottomRow(
    enabled: Boolean,
    vibrator: LightVibrator,
    moreOpen: Boolean,
    onMore: () -> Unit,
    onMoreHold: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(4f.gridUnitsAsDp())
            .background(LightThemeTokens.colors.background)
            .padding(horizontal = 2f.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarIcon(R.drawable.rl_back_white, "Back", enabled, vibrator, onTap = { Remote.press(HidCommand.Menu) })
        BarIcon(
            R.drawable.rl_home_white,
            "Home, hold for Control Centre",
            enabled,
            vibrator,
            onTap = { Remote.press(HidCommand.Home) },
            onHold = { Remote.controlCenter() },
        )
        BarIcon(
            R.drawable.rl_more_white,
            if (moreOpen) "Hide controls, hold to type" else "Show controls, hold to type",
            enabled,
            vibrator,
            onTap = onMore,
            onHold = onMoreHold,
        )
    }
}

@Composable
private fun BarIcon(
    resource: Int,
    label: String,
    enabled: Boolean,
    vibrator: LightVibrator,
    onTap: () -> Unit,
    onHold: (() -> Unit)? = null,
) {
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    Box(
        Modifier
            .size(4f.gridUnitsAsDp())
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    // Ticks on finger-down: eyes are on the TV, so the press confirms in the hand.
                    onPress = { vibrator.click() },
                    onTap = { tap() },
                    onLongPress = if (onHold != null) ({ hold?.invoke() }) else null,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        RemoteIcon(resource, label, dim = !enabled)
    }
}

/**
 * A thin bar under the top row: how far into the current item the TV is (tvOS 18 and later push
 * this). Between pushes the position is carried forward locally from the playback rate, the way
 * Apple's own remote does it. Nothing is drawn when nothing measurable is playing.
 */
@Composable
private fun NowPlayingBar(info: NowPlayingInfo?) {
    if (info == null || info.duration <= 0.0) return
    var now by remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(info) {
        while (info.rate != 0.0) {
            delay(1_000)
            now = System.nanoTime()
        }
    }
    val position = info.extrapolate(now - info.receivedAtNanos)
    Box(Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.25f.gridUnitsAsDp())) {
        LightProgressBar(LightThemeTokens.colors, (position / info.duration).toFloat())
    }
}
