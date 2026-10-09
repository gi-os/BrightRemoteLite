package com.gios.brightremotelite.discovery

import com.gios.brightremotelite.companion.Trace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * Finds Apple TVs by asking for `_companion-link._tcp.local` over multicast DNS.
 *
 * The question goes to 224.0.0.251:5353 from an ordinary ephemeral port, with the
 * unicast-response bit set. A query that does not come from port 5353 is a "legacy unicast"
 * query (RFC 6762 section 6.7), and responders answer it directly to the port it came from. So
 * the replies arrive as plain unicast UDP and nothing has to listen on the multicast group. That
 * matters here: Wi-Fi chips drop multicast frames unless an app holds a `MulticastLock`, and the
 * lock lives behind `WifiManager`, which a Light SDK tool cannot reach.
 *
 * Each browse is a short, user-started burst: three queries over a few seconds, and follow-up
 * questions for anything a reply left out. Nothing runs in the background.
 */
class MdnsBrowser {

    companion object {
        private const val MDNS_PORT = 5353
        private val MDNS_GROUP: InetAddress = InetAddress.getByAddress(byteArrayOf(224.toByte(), 0, 0, 251.toByte()))

        /** When the repeated PTR query goes out, in ms from the start. Spaced like RFC 6762 suggests. */
        private val PTR_SCHEDULE = longArrayOf(0, 1_000, 3_000)
        private const val RECEIVE_SLICE_MS = 200
        private const val FOLLOW_UP_GAP_MS = 400L
    }

    /**
     * Browse the local network for [durationMs], emitting the device list every time it changes.
     * Completes when the time is up.
     */
    fun browse(durationMs: Long = 5_000): Flow<List<DiscoveredDevice>> =
        exchange(InetSocketAddress(MDNS_GROUP, MDNS_PORT), durationMs, multicast = true)

    /**
     * Ask one host directly, for a television typed in by address. mDNS responders answer a
     * unicast question sent to port 5353, so this finds the Companion port without any multicast.
     */
    suspend fun probe(host: String, timeoutMs: Long = 2_500): DiscoveredDevice? {
        val target = runCatching { InetSocketAddress(InetAddress.getByName(host), MDNS_PORT) }.getOrNull() ?: return null
        val found = withTimeoutOrNull(timeoutMs) {
            exchange(target, timeoutMs, multicast = false).firstOrNull { it.isNotEmpty() }
        }.orEmpty()
        return found.firstOrNull { it.host == target.address.hostAddress } ?: found.firstOrNull()
    }

    /** Wait for the television called [name] to answer, for a stored address that has gone stale. */
    suspend fun find(name: String, timeoutMs: Long = 5_000): DiscoveredDevice? =
        withTimeoutOrNull(timeoutMs) {
            browse(timeoutMs).first { list -> list.any { it.name == name } }.first { it.name == name }
        }

    private fun exchange(
        target: InetSocketAddress,
        durationMs: Long,
        multicast: Boolean,
    ): Flow<List<DiscoveredDevice>> = flow {
        val assembler = DeviceAssembler()
        val socket = openSocket(multicast)
        try {
            socket.soTimeout = RECEIVE_SLICE_MS
            val start = System.currentTimeMillis()
            val deadline = start + durationMs.coerceAtLeast(RECEIVE_SLICE_MS.toLong())
            var nextPtr = 0
            var lastFollowUp = 0L
            var published: List<DiscoveredDevice> = emptyList()
            val buffer = ByteArray(9_000)

            fun send(questions: List<Mdns.Question>) {
                if (questions.isEmpty()) return
                val bytes = Mdns.query(Random.nextInt(1, 0xFFFF), questions)
                runCatching { socket.send(DatagramPacket(bytes, bytes.size, target)) }
                    .onFailure { Trace.problem("mDNS query to $target failed", it) }
            }

            while (true) {
                currentCoroutineContext().ensureActive()
                val now = System.currentTimeMillis()
                if (now >= deadline) break
                if (nextPtr < PTR_SCHEDULE.size && now - start >= PTR_SCHEDULE[nextPtr]) {
                    send(listOf(Mdns.Question(DeviceAssembler.SERVICE, Mdns.TYPE_PTR)))
                    nextPtr++
                }
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (timeout: SocketTimeoutException) {
                    continue
                }
                val message = runCatching { Mdns.parse(packet.data.copyOf(packet.length)) }.getOrNull() ?: continue
                if (!message.isResponse) continue
                assembler.add(message)
                val devices = assembler.devices()
                if (devices != published) {
                    published = devices
                    emit(devices)
                }
                val missing = assembler.missing()
                if (missing.isNotEmpty() && System.currentTimeMillis() - lastFollowUp > FOLLOW_UP_GAP_MS) {
                    lastFollowUp = System.currentTimeMillis()
                    send(missing)
                }
            }
            if (published.isEmpty()) emit(emptyList())
        } finally {
            socket.close()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * A socket on an ephemeral port. For multicast, pinned to the Wi-Fi interface when one can be
     * picked out, so the query does not leave by the cellular route.
     */
    private fun openSocket(multicast: Boolean): DatagramSocket {
        if (!multicast) return DatagramSocket()
        val socket = MulticastSocket(0)
        runCatching { socket.timeToLive = 255 }
        lanInterface()?.let { iface -> runCatching { socket.networkInterface = iface } }
        return socket
    }

    /** The interface with a private IPv4 address, preferring the one Android names `wlan`. */
    private fun lanInterface(): NetworkInterface? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { iface ->
                iface.isUp && !iface.isLoopback && iface.supportsMulticast() &&
                    iface.inetAddresses.toList().any { it is Inet4Address && it.isSiteLocalAddress }
            }
            .sortedByDescending { it.name.startsWith("wlan") }
            .firstOrNull()
    }.getOrNull()
}
