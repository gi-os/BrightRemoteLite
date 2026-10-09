package com.gios.brightremotelite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.gios.brightremotelite.app.Remote
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.launch

/**
 * Pair with a TV by its IP address, for networks where the search finds nothing.
 *
 * Without a port, the TV is asked directly for its Companion service (a unicast mDNS question to
 * port 5353). If that goes unanswered the port has to be typed after a colon.
 */
class AddressScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Boolean>(sealedActivity) {

    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        var text by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        val plausible = isPlausibleIpv4(text)

        fun pair() {
            val (host, port) = splitAddress(text)
            busy = true
            message = if (port == null) "Asking $host for its remote port" else null
            scope.launch {
                val device = Remote.resolveAddress(host, port)
                busy = false
                if (device == null) {
                    message = "Nothing at $host answered with a remote port. If you know the port, " +
                        "add it after a colon, for example $host:49153."
                    return@launch
                }
                message = null
                navigateTo({ PairScreen(it, device) }) { paired -> if (paired == true) goBack(true) }
            }
        }

        Themed {
            Column(Modifier.fillMaxSize()) {
                LightTopBar(
                    leftButton = backButton { goBack(false) },
                    center = LightTopBarCenter.Text("Address"),
                )
                Column(Modifier.fillMaxWidth().padding(horizontal = 2f.gridUnitsAsDp())) {
                    LightText(
                        text = text.ifEmpty { "192.168.1.20" },
                        variant = LightTextVariant.Subheading,
                        lighten = text.isEmpty(),
                    )
                    LightText(
                        text = message ?: "The TV's IP is in Settings > General > About. A port is optional.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Keypad(ADDRESS_KEYS, enabled = !busy) { key ->
                        message = null
                        text = when {
                            key == KEY_DELETE -> text.dropLast(1)
                            text.length >= 21 -> text
                            else -> text + key
                        }
                    }
                }
                LightBottomBar(
                    listOf(
                        LightBarButton.Text(
                            text = if (busy) "LOOKING" else "PAIR",
                            onClick = if (plausible && !busy) ({ pair() }) else null,
                        ),
                    ),
                )
            }
        }
    }
}
