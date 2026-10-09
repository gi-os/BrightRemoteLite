package com.gios.brightremotelite.ui

import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import com.gios.brightremotelite.hw.LightKeys
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** The tool's theme, following LightOS's light or dark setting. */
@Composable
fun Themed(content: @Composable () -> Unit) {
    val colors by LightThemeController.colors.collectAsState()
    LightTheme(colors = colors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            content()
        }
    }
}

fun backButton(onBack: () -> Unit): LightBarButton =
    LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onBack, contentDescription = "Back")

/**
 * A view model for a list screen: wheel notches arrive as +1 (down) and -1 (up). Both halves of a
 * notch are consumed so nothing else reacts to the key-up.
 */
abstract class WheelViewModel<R> : LightViewModel<R>() {
    private val _wheel = MutableSharedFlow<Int>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val wheel: SharedFlow<Int> = _wheel.asSharedFlow()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val notch = LightKeys.wheel(keyCode, event) ?: return super.onKeyDown(keyCode, event)
        _wheel.tryEmit(-notch)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (LightKeys.wheel(keyCode, event) != null) return true
        return super.onKeyUp(keyCode, event)
    }
}

/** How far one notch scrolls a list, in pixels. */
private const val NOTCH_PX = 140

@Composable
fun WheelScroll(wheel: SharedFlow<Int>, state: ScrollState) {
    LaunchedEffect(wheel, state) {
        wheel.collect { step -> state.scrollTo((state.value + step * NOTCH_PX).coerceIn(0, state.maxValue)) }
    }
}

/** A standard page: top bar, a scrolling body (wheel-driven when [wheel] is set), optional bottom bar. */
@Composable
fun Page(
    title: String?,
    left: LightBarButton? = null,
    right: LightBarButton? = null,
    bottom: List<LightBarButton?>? = null,
    wheel: SharedFlow<Int>? = null,
    banner: String? = null,
    onBannerTap: () -> Unit = {},
    scrollState: ScrollState = rememberScrollState(),
    body: @Composable ColumnScope.() -> Unit,
) {
    Themed {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = left,
                center = title?.let { LightTopBarCenter.Text(it) },
                rightButton = right,
                modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
            )
            Banner(banner, onBannerTap)
            if (wheel != null) WheelScroll(wheel, scrollState)
            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp()),
                scrollState = scrollState,
            ) {
                body()
                Box(modifier = Modifier.height(1f.gridUnitsAsDp()))
            }
            if (bottom != null) LightBottomBar(bottom)
        }
    }
}

/** An error or status line across the top. Tapping it dismisses it. */
@Composable
fun Banner(text: String?, onTap: () -> Unit) {
    if (text == null) return
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        lighten = true,
        maxLines = 3,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onTap)
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
    )
}

/** A tappable row of copy with an optional detail line, and an optional small action on the right. */
@Composable
fun ListRow(
    title: String,
    detail: String? = null,
    lighten: Boolean = false,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 0.5f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val base = Modifier.weight(1f)
        Column(modifier = if (onClick != null) base.lightClickable(onClick = onClick) else base) {
            LightText(text = title, variant = LightTextVariant.Copy, lighten = lighten, maxLines = 2)
            if (detail != null) LightText(text = detail, variant = LightTextVariant.Detail, lighten = true)
        }
        if (action != null && onAction != null) {
            LightText(
                text = action,
                variant = LightTextVariant.Fine,
                lighten = true,
                modifier = Modifier
                    .lightClickable(onClick = onAction)
                    .padding(start = 1f.gridUnitsAsDp(), top = 0.5f.gridUnitsAsDp(), bottom = 0.5f.gridUnitsAsDp()),
            )
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Fine,
        lighten = true,
        modifier = Modifier.padding(top = 1f.gridUnitsAsDp(), bottom = 0.25f.gridUnitsAsDp()),
    )
}

@Composable
fun Note(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        lighten = true,
        modifier = Modifier.padding(vertical = 0.5f.gridUnitsAsDp()),
    )
}

/** One of the tool's own white vector icons, tinted to the theme. */
@Composable
fun RemoteIcon(
    resource: Int,
    description: String?,
    sizeUnits: Float = 2f,
    dim: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val color: Color = if (dim) LightThemeTokens.colors.contentSecondary else LightThemeTokens.colors.content
    Image(
        painter = painterResource(resource),
        contentDescription = description,
        colorFilter = ColorFilter.tint(color),
        modifier = modifier.size(sizeUnits.gridUnitsAsDp()),
    )
}
