package com.gios.brightremotelite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

const val KEY_DELETE = "DEL"

/** A phone-style keypad. [rows] holds the key labels; an empty label is a blank cell. */
@Composable
fun Keypad(rows: List<List<String>>, enabled: Boolean = true, onKey: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.Center) {
                row.forEach { key ->
                    val cell = Modifier.size(width = 7f.gridUnitsAsDp(), height = 3.5f.gridUnitsAsDp())
                    Box(
                        modifier = if (key.isEmpty()) cell else cell.lightClickable(enabled = enabled) { onKey(key) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (key.isNotEmpty()) {
                            LightText(
                                text = key,
                                variant = if (key == KEY_DELETE) LightTextVariant.Fine else LightTextVariant.Subheading,
                                lighten = !enabled,
                            )
                        }
                    }
                }
            }
        }
    }
}

val PIN_KEYS = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf("", "0", KEY_DELETE),
)

val ADDRESS_KEYS = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf(".", "0", ":"),
    listOf("", "", KEY_DELETE),
)
