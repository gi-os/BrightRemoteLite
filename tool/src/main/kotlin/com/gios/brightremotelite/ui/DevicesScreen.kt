package com.gios.brightremotelite.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.gios.brightremotelite.app.ConnectionState
import com.gios.brightremotelite.app.Remote
import com.gios.brightremotelite.discovery.DiscoveredDevice
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton

class DevicesViewModel : WheelViewModel<Unit>()

/**
 * Paired TVs, and the ones a search found. Searching is a short burst started by opening this
 * screen or tapping Search; it never repeats on its own.
 */
class DevicesScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, DevicesViewModel>(sealedActivity) {

    override val viewModelClass = DevicesViewModel::class.java
    override fun createViewModel() = DevicesViewModel()

    override fun willShow() {
        if (!Remote.state.value.scannedOnce) Remote.scan()
    }

    override fun willHide() {
        Remote.stopScan()
    }

    private fun pair(device: DiscoveredDevice) {
        navigateTo({ PairScreen(it, device) }) { paired -> if (paired == true) goBack() }
    }

    @Composable
    override fun Content() {
        val state by Remote.state.collectAsState()
        Page(
            title = "Apple TVs",
            left = backButton { goBack() },
            bottom = listOf(
                LightBarButton.Text(text = if (state.scanning) "SEARCHING" else "SEARCH", onClick = { Remote.scan() }),
                LightBarButton.Text(text = "ADDRESS", onClick = {
                    navigateTo(::AddressScreen) { paired -> if (paired == true) goBack() }
                }),
            ),
            wheel = viewModel.wheel,
            banner = state.error,
            onBannerTap = { Remote.dismissError() },
        ) {
            if (state.paired.isNotEmpty()) {
                SectionLabel("Paired")
                state.paired.forEach { device ->
                    val here = state.activeId == device.id
                    val status = when {
                        !here -> null
                        state.connection == ConnectionState.Connected -> "connected"
                        state.connection == ConnectionState.Connecting -> "connecting"
                        else -> null
                    }
                    ListRow(
                        title = device.name,
                        detail = listOfNotNull(status, "${device.host}:${device.port}").joinToString(" · "),
                        action = "FORGET",
                        onAction = { Remote.forget(device) },
                        onClick = {
                            Remote.connect(device, byHand = true)
                            goBack()
                        },
                    )
                }
            }

            SectionLabel("Found")
            if (state.scanning && state.discovered.isEmpty()) Note("Looking for Apple TVs on this network")
            state.discovered.forEach { device ->
                ListRow(
                    title = device.name,
                    detail = if (device.pairable) {
                        listOfNotNull(device.friendlyModel, device.host).joinToString(" · ")
                    } else {
                        "Pairing is off on this TV: Settings > AirPlay and HomeKit"
                    },
                    lighten = !device.pairable,
                    onClick = if (device.pairable) ({ pair(device) }) else null,
                )
            }
            if (!state.scanning && state.scannedOnce && state.discovered.isEmpty()) {
                Note(
                    "No new Apple TV answered. The phone and the TV need to be on the same Wi-Fi " +
                        "network. Guest and IoT networks often block this kind of search; use Address " +
                        "to enter the TV's IP from Settings > General > About.",
                )
            }
        }
    }
}
