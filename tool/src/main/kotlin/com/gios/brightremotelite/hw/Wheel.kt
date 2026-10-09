package com.gios.brightremotelite.hw

import android.view.KeyEvent
import kotlin.math.sign

/**
 * The Light Phone III's scroll wheel and volume rocker, as key events.
 *
 * The wheel is an optical sensor (`Pixart pat9126ja`) that emits one DOWN+UP pair per notch.
 * LightOS relabels its two scancodes in `Generic.kl`:
 *
 * ```
 * key 19    WHEEL_CCW      # wheel up
 * key 20    WHEEL_CW       # wheel down
 * ```
 *
 * Those labels are Light's own additions rather than AOSP keycodes, so their integer values are
 * looked up at runtime from the label. The raw scancode is a fallback, trusted only when it comes
 * from the wheel's own device, since scancode 19 is also `r` on a Bluetooth keyboard.
 */
object LightKeys {
    private const val SCAN_WHEEL_UP = 19
    private const val SCAN_WHEEL_DOWN = 20
    private const val WHEEL_DEVICE = "Pixart pat9126ja"

    private val byKeyCode: Map<Int, Int> = buildMap {
        label("WHEEL_CCW")?.let { put(it, 1) }
        label("WHEEL_CW")?.let { put(it, -1) }
    }

    private fun label(name: String): Int? {
        val code = runCatching { KeyEvent.keyCodeFromString(name) }.getOrDefault(KeyEvent.KEYCODE_UNKNOWN)
        return code.takeIf { it != KeyEvent.KEYCODE_UNKNOWN }
    }

    /** +1 for a notch up, -1 for a notch down, null if [event] is not the wheel. */
    fun wheel(keyCode: Int, event: KeyEvent): Int? {
        byKeyCode[keyCode]?.let { return it }
        if (event.device?.name != WHEEL_DEVICE) return null
        return when (event.scanCode) {
            SCAN_WHEEL_UP -> 1
            SCAN_WHEEL_DOWN -> -1
            else -> null
        }
    }

    /** +1 for volume up, -1 for volume down, null for anything else. */
    fun volume(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> 1
        KeyEvent.KEYCODE_VOLUME_DOWN -> -1
        else -> null
    }
}

/**
 * Notches needed to start a turn, and how long a turn stays live.
 *
 * The wheel sits under a thumb and catches stray brushes. The first notch after a pause buys
 * nothing on its own; a second one releases both. Once turning, everything applies until
 * [IDLE_MS] passes with the wheel still.
 */
private const val ARM_NOTCHES = 2
private const val IDLE_MS = 1_500L

/**
 * Notches against the turn needed to call it a reversal.
 *
 * The sensor reads a moving surface, not a detent, so a steady turn is not a clean run of
 * same-sign notches: a few percent come back inverted, mostly at the start and end of a spin.
 * On the remote one notch can be one row of tvOS focus, so a stray one sends the highlight the
 * wrong way. Strays cluster, which makes two in a row ordinary; three is a real change of mind.
 */
private const val REVERSE_NOTCHES = 3

/**
 * Which notches survive, as plain arithmetic over (notch, clock), so it can be unit tested
 * without a sensor or a frame clock.
 */
internal class NotchGuard {
    private var armed = false
    private var held = 0
    private var count = 0
    private var last = 0L

    /** Which way the turn in progress is going, and how many notches have come against it. */
    private var turn = 0
    private var against = 0

    /** @return the notches to act on, or 0 to swallow this one. */
    fun accept(notches: Int, nowMs: Long): Int {
        if (nowMs - last > IDLE_MS) {
            armed = false
            held = 0
            count = 0
            turn = 0
            against = 0
        }
        last = nowMs

        if (armed) {
            if (turn != 0 && notches.sign != 0 && notches.sign != turn) {
                against++
                if (against < REVERSE_NOTCHES) return 0
                turn = notches.sign
                against = 0
            } else {
                against = 0
                if (notches.sign != 0) turn = notches.sign
            }
            return notches
        }

        held += notches
        count++
        if (count < ARM_NOTCHES) return 0
        armed = true
        val release = if (held != 0) held else notches.sign
        turn = release.sign
        against = 0
        held = 0
        return release
    }
}

/**
 * Banks guarded notches and pays them out as focus steps for the television.
 *
 * Two notches per row: one-to-one overshot by exactly double, because what reads as one flick of
 * the thumb is two sensor notches. The remainder is kept between turns, otherwise a wheel turned
 * one notch at a time would never move anything. The bank is clamped at two rows so a hard spin
 * does not keep walking the focus after the thumb stops.
 */
internal class StepBank(
    private val notchesPerStep: Int = 2,
    private val maxBanked: Int = 4,
) {
    private var bank = 0

    fun add(notches: Int) {
        bank = (bank + notches).coerceIn(-maxBanked, maxBanked)
    }

    /** The next step (+1 or -1) if a whole one is banked, else 0. */
    fun take(): Int {
        if (bank >= notchesPerStep) {
            bank -= notchesPerStep
            return 1
        }
        if (bank <= -notchesPerStep) {
            bank += notchesPerStep
            return -1
        }
        return 0
    }
}
