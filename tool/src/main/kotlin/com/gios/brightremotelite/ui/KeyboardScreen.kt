package com.gios.brightremotelite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.gios.brightremotelite.app.Remote
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * Type into whatever field is focused on the TV, with the Light keyboard.
 *
 * Text goes in one go on Send rather than per keystroke: each `_tiC` restarts the remote-text
 * session to get a current session id, which would be three round trips per letter. Replace
 * clears the TV's field first.
 */
class KeyboardScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val remote by Remote.state.collectAsState()
        val keyboardOptions = rememberKeyboardOptions()
        val text = rememberTextFieldState()

        LaunchedEffect(Unit) { Remote.loadFieldText() }

        val title = remote.error ?: when (val field = remote.fieldText) {
            null -> "No field focused on the TV"
            "" -> "TV field is empty"
            else -> "TV: " + if (field.length > 24) field.take(23) + "…" else field
        }

        Themed {
            LightTextInputEditor(
                title = title,
                state = text,
                onSubmit = { typed ->
                    if (typed.isNotEmpty()) {
                        Remote.sendText(typed.toString(), replace = false)
                        text.clearText()
                    }
                },
                onBack = { goBack() },
                keyboardOptionsFlow = keyboardOptions,
                modifier = Modifier.background(LightThemeTokens.colors.background),
                submitLabel = "SEND",
                singleLine = true,
                rightBottomBarItem = LightBarButton.Text(text = "SET", onClick = {
                    Remote.sendText(text.text.toString(), replace = true)
                    text.clearText()
                }),
            )
        }
    }
}
