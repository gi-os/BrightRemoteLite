package com.gios.brightremotelite.ui

/**
 * Whether [text] is a usable IPv4 address, optionally followed by `:port`.
 *
 * This is the way in for a network whose multicast is broken, so it is reached by someone who has
 * already failed to find their TV once. The Pair button should simply not light up for something
 * that will fail at a socket five seconds later.
 */
internal fun isPlausibleIpv4(text: String): Boolean {
    val (host, port) = splitAddress(text)
    if (text.contains(":") && port == null) return false
    val parts = host.split(".")
    if (parts.size != 4) return false
    return parts.all { part ->
        part.isNotEmpty() && part.length <= 3 && (part.toIntOrNull() ?: -1) in 0..255
    }
}

/** Splits `1.2.3.4:49153` into its halves. A missing or unusable port comes back null. */
internal fun splitAddress(text: String): Pair<String, Int?> {
    val colon = text.indexOf(':')
    if (colon < 0) return text to null
    val port = text.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 }
    return text.substring(0, colon) to port
}
