package com.gios.brightremotelite.discovery

/** One Apple TV found on the network. */
data class DiscoveredDevice(
    val name: String,
    val host: String,
    val port: Int,
    /** Raw `rpmd` model string from the TXT record, e.g. "AppleTV14,1". */
    val model: String? = null,
    /** False when the device has pairing turned off in Settings. */
    val pairable: Boolean = true,
) {
    /** "Apple TV 4K" reads better on a narrow screen than "AppleTV14,1". */
    val friendlyModel: String?
        get() = when {
            model == null -> null
            model.startsWith("AppleTV5,3") -> "Apple TV HD"
            model.startsWith("AppleTV6,2") -> "Apple TV 4K"
            model.startsWith("AppleTV11,1") -> "Apple TV 4K (2nd gen)"
            model.startsWith("AppleTV14,1") -> "Apple TV 4K (3rd gen)"
            model.startsWith("AudioAccessory") -> "HomePod"
            model.startsWith("AppleTV") -> "Apple TV"
            else -> model
        }
}

/**
 * Collects mDNS records as they arrive and turns them into devices.
 *
 * A complete answer takes four records: the PTR names the instance, the SRV gives its port and
 * host name, the A record gives that host's address, and the TXT carries the model and flags.
 * Apple TVs normally put all four in one reply, but nothing guarantees it, so [missing] says what
 * to ask for next.
 */
class DeviceAssembler(private val serviceType: Mdns.Name = SERVICE) {

    companion object {
        val SERVICE: Mdns.Name = Mdns.Name.of("_companion-link._tcp.local")

        /** Bit in `rpfl` that means "pairing is disabled on this device". */
        private const val PAIRING_DISABLED_MASK = 0x40000000L
    }

    private val instances = LinkedHashMap<String, Mdns.Name>()
    private val srv = HashMap<String, Mdns.Data.Srv>()
    private val txt = HashMap<String, Map<String, String>>()
    private val addresses = HashMap<String, String>()

    fun add(message: Mdns.Message) {
        for (record in message.records) {
            when (val data = record.data) {
                is Mdns.Data.Ptr -> if (record.name.key == serviceType.key) {
                    // A TTL of zero is a goodbye: the device is leaving the network.
                    if (record.ttl == 0L) instances.remove(data.target.key)
                    else instances[data.target.key] = data.target
                }
                is Mdns.Data.Srv -> srv[record.name.key] = data
                is Mdns.Data.Txt -> txt[record.name.key] = data.entries
                is Mdns.Data.A -> addresses.putIfAbsent(record.name.key, data.address)
                else -> Unit
            }
        }
    }

    /** Every instance with an address and a port, sorted by name. */
    fun devices(): List<DiscoveredDevice> = instances.values.mapNotNull { instance ->
        val service = srv[instance.key] ?: return@mapNotNull null
        val host = addresses[service.target.key] ?: return@mapNotNull null
        val attributes = txt[instance.key].orEmpty()
        val flags = attributes["rpfl"]?.removePrefix("0x")?.toLongOrNull(16) ?: 0L
        DiscoveredDevice(
            name = instance.first,
            host = host,
            port = service.port,
            model = attributes["rpmd"],
            pairable = (flags and PAIRING_DISABLED_MASK) == 0L,
        )
    }.sortedBy { it.name.lowercase() }

    /** Follow-up questions for instances that are still missing their SRV, TXT or address. */
    fun missing(): List<Mdns.Question> {
        val out = ArrayList<Mdns.Question>()
        for (instance in instances.values) {
            val service = srv[instance.key]
            if (service == null) {
                out.add(Mdns.Question(instance, Mdns.TYPE_SRV))
            } else if (service.target.key !in addresses) {
                out.add(Mdns.Question(service.target, Mdns.TYPE_A))
            }
            if (instance.key !in txt) out.add(Mdns.Question(instance, Mdns.TYPE_TXT))
        }
        return out.distinctBy { it.name.key to it.type }
    }
}
