package com.gios.brightremotelite.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.gios.brightremotelite.app.ConnectionState
import com.gios.brightremotelite.app.Remote
import com.gios.brightremotelite.companion.InstalledApp
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton

class AppsViewModel : WheelViewModel<Unit>()

/** The TV's launchable apps. Pinned ones sit at the top; tapping one opens it on the TV. */
class AppsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, AppsViewModel>(sealedActivity) {

    override val viewModelClass = AppsViewModel::class.java
    override fun createViewModel() = AppsViewModel()

    override fun willShow() {
        if (Remote.state.value.apps.isEmpty()) Remote.loadApps()
    }

    @Composable
    override fun Content() {
        val state by Remote.state.collectAsState()
        val pinned = state.apps.filter { it.bundleId in state.pinned }
        val rest = state.apps.filter { it.bundleId !in state.pinned }

        @Composable
        fun row(app: InstalledApp) {
            val isPinned = app.bundleId in state.pinned
            ListRow(
                title = app.name,
                action = if (isPinned) "UNPIN" else "PIN",
                onAction = { Remote.togglePin(app) },
                onClick = {
                    Remote.launchApp(app)
                    goBack()
                },
            )
        }

        Page(
            title = "Apps",
            left = backButton { goBack() },
            right = LightBarButton.Text(text = "RELOAD", onClick = { Remote.loadApps() }),
            wheel = viewModel.wheel,
            banner = state.error,
            onBannerTap = { Remote.dismissError() },
        ) {
            when {
                state.connection != ConnectionState.Connected -> Note("Not connected to an Apple TV.")
                state.appsLoading && state.apps.isEmpty() -> Note("Asking the TV for its apps")
                state.apps.isEmpty() -> Note("The TV did not list any apps.")
            }
            if (pinned.isNotEmpty()) {
                SectionLabel("Pinned")
                pinned.forEach { row(it) }
                SectionLabel("All apps")
            }
            rest.forEach { row(it) }
        }
    }
}
