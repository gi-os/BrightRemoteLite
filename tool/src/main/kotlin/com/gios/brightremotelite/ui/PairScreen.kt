package com.gios.brightremotelite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.gios.brightremotelite.app.Remote
import com.gios.brightremotelite.app.friendlyMessage
import com.gios.brightremotelite.discovery.DiscoveredDevice
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.launch

/**
 * HAP pair-setup with the four-digit code the TV puts on screen. Returns true once the TV is
 * paired; the connection starts on its own.
 */
class PairScreen(
    sealedActivity: SealedLightActivity,
    private val device: DiscoveredDevice,
) : SimpleLightScreen<Boolean>(sealedActivity) {

    override fun onScreenDestroy() {
        Remote.cancelPairing()
    }

    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        var attempt by remember { mutableIntStateOf(0) }
        var ready by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(true) }
        var pin by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(attempt) {
            busy = true
            ready = false
            error = null
            pin = ""
            Remote.beginPairing(device)
                .onSuccess { ready = true }
                .onFailure { error = it.friendlyMessage() }
            busy = false
        }

        fun submit(code: String) {
            busy = true
            scope.launch {
                Remote.finishPairing(code)
                    .onSuccess { goBack(true) }
                    .onFailure {
                        // A wrong code spends the whole exchange; the TV wants a fresh one.
                        error = it.friendlyMessage()
                        ready = false
                        pin = ""
                    }
                busy = false
            }
        }

        Themed {
            Column(Modifier.fillMaxSize()) {
                LightTopBar(
                    leftButton = backButton { goBack(false) },
                    center = LightTopBarCenter.Text("Pair"),
                )
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 2f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LightText(text = device.name, variant = LightTextVariant.Copy, align = TextAlign.Center)
                    LightText(
                        text = when {
                            error != null -> error!!
                            busy && !ready -> "Asking the TV for a code"
                            busy -> "Checking the code"
                            else -> "Type the code shown on the TV"
                        },
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        align = TextAlign.Center,
                    )
                    LightText(
                        text = (pin + "____").take(4).toCharArray().joinToString(" "),
                        variant = LightTextVariant.Heading,
                        modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Keypad(PIN_KEYS, enabled = ready && !busy) { key ->
                        if (key == KEY_DELETE) {
                            pin = pin.dropLast(1)
                        } else if (pin.length < 4) {
                            pin += key
                            if (pin.length == 4) submit(pin)
                        }
                    }
                }
                if (!ready && !busy) {
                    LightBottomBar(listOf(LightBarButton.Text(text = "TRY AGAIN", onClick = { attempt++ })))
                }
            }
        }
    }
}
