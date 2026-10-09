package com.gios.brightremotelite.discovery

import java.io.ByteArrayOutputStream

/**
 * Just enough DNS to find an Apple TV: build a multicast DNS query, and read back the PTR, SRV,
 * TXT and A records the TV answers with.
 *
 * The full app used Android's `NsdManager` for this. A Light SDK tool cannot reach it (it lives
 * behind `getSystemService`), so the query goes out over a plain UDP socket instead and the
 * answer is parsed here. Pure Kotlin, no Android types, so the tests run on the JVM.
 *
 * Wire format per RFC 1035 section 4, with the mDNS changes from RFC 6762: the top bit of a
 * question's class asks for a unicast reply (QU), and the top bit of a record's class is the
 * cache-flush flag, which a reader has to mask off.
 */
object Mdns {
    const val TYPE_A = 1
    const val TYPE_PTR = 12
    const val TYPE_TXT = 16
    const val TYPE_AAAA = 28
    const val TYPE_SRV = 33

    private const val CLASS_IN = 1
    private const val QU_BIT = 0x8000
    private const val CLASS_MASK = 0x7FFF

    /** Compression pointers can loop; a real name is never this deep. */
    private const val MAX_POINTER_HOPS = 32

    /** A domain name as its labels, e.g. `["Living Room", "_companion-link", "_tcp", "local"]`. */
    data class Name(val labels: List<String>) {
        /** DNS names compare case-insensitively. Dots inside a label are escaped so they can't collide. */
        val key: String = labels.joinToString(".") { it.lowercase().replace(".", "\\.") }

        /** The first label, which for a service instance is the name a person gave the device. */
        val first: String get() = labels.firstOrNull() ?: ""

        override fun toString(): String = labels.joinToString(".")

        companion object {
            fun of(dotted: String): Name = Name(dotted.trimEnd('.').split('.').filter { it.isNotEmpty() })
        }
    }

    data class Question(val name: Name, val type: Int, val unicastResponse: Boolean = true)

    sealed interface Data {
        data class Ptr(val target: Name) : Data
        data class Srv(val priority: Int, val weight: Int, val port: Int, val target: Name) : Data
        data class Txt(val entries: Map<String, String>) : Data
        data class A(val address: String) : Data
        data class Aaaa(val address: ByteArray) : Data {
            override fun equals(other: Any?) = other is Aaaa && address.contentEquals(other.address)
            override fun hashCode() = address.contentHashCode()
        }
        data class Other(val type: Int) : Data
    }

    data class Record(val name: Name, val type: Int, val ttl: Long, val data: Data)

    data class Message(
        val id: Int,
        val isResponse: Boolean,
        val questions: List<Question>,
        /** Answers, authority and additional sections together; nothing here cares which. */
        val records: List<Record>,
    )

    class FormatException(message: String) : Exception(message)

    // ------------------------------------------------------------------ writing

    /**
     * A query for [questions]. Names are written uncompressed; a query this small does not need it.
     */
    fun query(id: Int, questions: List<Question>): ByteArray {
        val out = ByteArrayOutputStream()
        out.u16(id)
        out.u16(0) // standard query, no flags
        out.u16(questions.size)
        out.u16(0)
        out.u16(0)
        out.u16(0)
        for (q in questions) {
            writeName(out, q.name)
            out.u16(q.type)
            out.u16(if (q.unicastResponse) CLASS_IN or QU_BIT else CLASS_IN)
        }
        return out.toByteArray()
    }

    private fun writeName(out: ByteArrayOutputStream, name: Name) {
        for (label in name.labels) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            require(bytes.size in 1..63) { "DNS label must be 1-63 bytes: $label" }
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
    }

    private fun ByteArrayOutputStream.u16(value: Int) {
        write((value shr 8) and 0xFF)
        write(value and 0xFF)
    }

    // ------------------------------------------------------------------ reading

    fun parse(packet: ByteArray): Message {
        val r = Reader(packet)
        val id = r.u16()
        val flags = r.u16()
        val qd = r.u16()
        val an = r.u16()
        val ns = r.u16()
        val ar = r.u16()
        val questions = List(qd) {
            val name = r.name()
            val type = r.u16()
            val cls = r.u16()
            Question(name, type, unicastResponse = (cls and QU_BIT) != 0)
        }
        val records = ArrayList<Record>(an + ns + ar)
        repeat(an + ns + ar) {
            val name = r.name()
            val type = r.u16()
            val cls = r.u16() and CLASS_MASK
            val ttl = r.u32()
            val length = r.u16()
            val start = r.pos
            val end = start + length
            if (end > packet.size) throw FormatException("record runs past the end of the packet")
            val data = when (type) {
                TYPE_PTR -> Data.Ptr(r.name())
                TYPE_SRV -> Data.Srv(r.u16(), r.u16(), r.u16(), r.name())
                TYPE_TXT -> Data.Txt(readTxt(packet, start, end))
                TYPE_A -> {
                    if (length != 4) throw FormatException("A record of $length bytes")
                    Data.A((0 until 4).joinToString(".") { (packet[start + it].toInt() and 0xFF).toString() })
                }
                TYPE_AAAA -> Data.Aaaa(packet.copyOfRange(start, end))
                else -> Data.Other(type)
            }
            r.pos = end
            if (cls == CLASS_IN) records.add(Record(name, type, ttl, data))
        }
        return Message(id, isResponse = (flags and 0x8000) != 0, questions = questions, records = records)
    }

    /** TXT rdata: a run of length-prefixed `key=value` strings. A key with no `=` maps to "". */
    private fun readTxt(packet: ByteArray, start: Int, end: Int): Map<String, String> {
        val entries = LinkedHashMap<String, String>()
        var p = start
        while (p < end) {
            val len = packet[p].toInt() and 0xFF
            p++
            if (p + len > end) break
            val text = String(packet, p, len, Charsets.UTF_8)
            p += len
            if (text.isEmpty()) continue
            val eq = text.indexOf('=')
            if (eq < 0) entries.putIfAbsent(text.lowercase(), "")
            else entries.putIfAbsent(text.substring(0, eq).lowercase(), text.substring(eq + 1))
        }
        return entries
    }

    private class Reader(val bytes: ByteArray) {
        var pos = 0

        fun u8(): Int {
            if (pos >= bytes.size) throw FormatException("packet truncated")
            return bytes[pos++].toInt() and 0xFF
        }

        fun u16(): Int = (u8() shl 8) or u8()

        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()

        fun name(): Name {
            val labels = ArrayList<String>()
            var p = pos
            var jumped = false
            var hops = 0
            while (true) {
                if (p >= bytes.size) throw FormatException("name runs past the end of the packet")
                val len = bytes[p].toInt() and 0xFF
                when {
                    len == 0 -> {
                        p++
                        break
                    }
                    len and 0xC0 == 0xC0 -> {
                        if (p + 1 >= bytes.size) throw FormatException("truncated name pointer")
                        val target = ((len and 0x3F) shl 8) or (bytes[p + 1].toInt() and 0xFF)
                        if (!jumped) pos = p + 2
                        jumped = true
                        if (++hops > MAX_POINTER_HOPS) throw FormatException("name pointer loop")
                        p = target
                    }
                    len and 0xC0 != 0 -> throw FormatException("unsupported label type")
                    else -> {
                        if (p + 1 + len > bytes.size) throw FormatException("label runs past the end of the packet")
                        labels.add(String(bytes, p + 1, len, Charsets.UTF_8))
                        p += 1 + len
                    }
                }
            }
            if (!jumped) pos = p
            return Name(labels)
        }
    }
}
