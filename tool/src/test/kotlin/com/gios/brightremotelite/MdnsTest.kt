package com.gios.brightremotelite

import com.gios.brightremotelite.discovery.DeviceAssembler
import com.gios.brightremotelite.discovery.Mdns
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The hand-rolled mDNS layer that replaced `NsdManager`. Packets are built byte by byte here, with
 * name compression the way mDNSResponder uses it, so the parser is tested against the wire format
 * rather than against its own writer.
 */
class MdnsTest {

    /** A tiny DNS packet builder that remembers where each name was written, for pointers. */
    private class Packet {
        val out = ByteArrayOutputStream()
        val offsets = HashMap<String, Int>()

        fun u16(v: Int) = apply { out.write(v shr 8 and 0xFF); out.write(v and 0xFF) }
        fun u32(v: Long) = apply { u16((v shr 16).toInt() and 0xFFFF); u16(v.toInt() and 0xFFFF) }

        /** Writes [labels], ending with a pointer to [suffix] if it was written before. */
        fun name(labels: List<String>, tag: String? = null, suffix: String? = null) = apply {
            tag?.let { offsets[it] = out.size() }
            labels.forEach { l -> val b = l.toByteArray(); out.write(b.size); out.write(b) }
            if (suffix != null) u16(0xC000 or offsets.getValue(suffix)) else out.write(0)
        }

        fun pointer(tag: String) = u16(0xC000 or offsets.getValue(tag))

        /** A record header; returns a function to call after writing rdata, which patches the length. */
        fun record(type: Int, cls: Int = 0x8001, ttl: Long = 120): () -> Unit {
            u16(type); u16(cls); u32(ttl)
            val lengthAt = out.size()
            u16(0)
            return {
                val bytes = out.toByteArray()
                val len = bytes.size - lengthAt - 2
                bytes[lengthAt] = (len shr 8).toByte()
                bytes[lengthAt + 1] = len.toByte()
                out.reset()
                out.write(bytes)
            }
        }

        fun bytes(): ByteArray = out.toByteArray()
    }

    private fun reply(ttl: Long, withAddress: Boolean): ByteArray {
        val p = Packet()
        p.u16(0).u16(0x8400).u16(0).u16(1).u16(0).u16(if (withAddress) 3 else 2)
        p.name(listOf("_companion-link", "_tcp", "local"), tag = "service")
        var done = p.record(Mdns.TYPE_PTR, cls = 0x0001, ttl = ttl)
        p.name(listOf("Living Room"), tag = "instance", suffix = "service")
        done()

        p.pointer("instance")
        done = p.record(Mdns.TYPE_SRV)
        p.u16(0).u16(0).u16(49153)
        p.offsets["local"] = p.offsets.getValue("service") + 1 + "_companion-link".length + 1 + "_tcp".length
        p.name(listOf("Living-Room"), tag = "host", suffix = "local")
        done()

        p.pointer("instance")
        done = p.record(Mdns.TYPE_TXT)
        listOf("rpMac=2", "rpmd=AppleTV14,1", "rpfl=0x627B6", "rpVr=715.2").forEach {
            val b = it.toByteArray(); p.out.write(b.size); p.out.write(b)
        }
        done()

        if (withAddress) {
            p.pointer("host")
            done = p.record(Mdns.TYPE_A)
            p.out.write(byteArrayOf(192.toByte(), 168.toByte(), 1, 42))
            done()
        }
        return p.bytes()
    }

    @Test
    fun `a query asks for a unicast reply`() {
        val q = Mdns.query(0x1234, listOf(Mdns.Question(DeviceAssembler.SERVICE, Mdns.TYPE_PTR)))
        val expected = byteArrayOf(
            0x12, 0x34, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            15, *"_companion-link".toByteArray(),
            4, *"_tcp".toByteArray(),
            5, *"local".toByteArray(),
            0,
            0, 12, // PTR
            0x80.toByte(), 1, // IN with the QU bit
        )
        assertContentEquals(expected, q)
        val parsed = Mdns.parse(q)
        assertEquals(1, parsed.questions.size)
        assertTrue(parsed.questions[0].unicastResponse)
        assertEquals("_companion-link._tcp.local", parsed.questions[0].name.toString())
    }

    @Test
    fun `a full reply with compressed names becomes one device`() {
        val message = Mdns.parse(reply(4500, withAddress = true))
        assertTrue(message.isResponse)
        assertEquals(4, message.records.size)
        val srv = message.records.first { it.type == Mdns.TYPE_SRV }.data as Mdns.Data.Srv
        assertEquals(49153, srv.port)
        assertEquals("Living-Room.local", srv.target.toString())

        val assembler = DeviceAssembler()
        assembler.add(message)
        val devices = assembler.devices()
        assertEquals(1, devices.size)
        val tv = devices.single()
        assertEquals("Living Room", tv.name)
        assertEquals("192.168.1.42", tv.host)
        assertEquals(49153, tv.port)
        assertEquals("AppleTV14,1", tv.model)
        assertEquals("Apple TV 4K (3rd gen)", tv.friendlyModel)
        assertTrue(tv.pairable)
        assertEquals(emptyList(), assembler.missing())
    }

    @Test
    fun `a reply without the address asks for it`() {
        val assembler = DeviceAssembler()
        assembler.add(Mdns.parse(reply(4500, withAddress = false)))
        assertEquals(emptyList(), assembler.devices())
        val missing = assembler.missing()
        assertEquals(1, missing.size)
        assertEquals(Mdns.TYPE_A, missing[0].type)
        assertEquals("Living-Room.local", missing[0].name.toString())
    }

    @Test
    fun `a goodbye removes the device`() {
        val assembler = DeviceAssembler()
        assembler.add(Mdns.parse(reply(4500, withAddress = true)))
        assembler.add(Mdns.parse(reply(0, withAddress = true)))
        assertEquals(emptyList(), assembler.devices())
    }

    @Test
    fun `pairing disabled is read from rpfl`() {
        val message = Mdns.parse(reply(4500, withAddress = true))
        val patched = message.copy(
            records = message.records.map { r ->
                if (r.type == Mdns.TYPE_TXT) r.copy(data = Mdns.Data.Txt(mapOf("rpfl" to "0x40000000"))) else r
            },
        )
        val assembler = DeviceAssembler()
        assembler.add(patched)
        assertEquals(false, assembler.devices().single().pairable)
    }

    @Test
    fun `an unanswered question is ignored and other services are not devices`() {
        val p = Packet()
        p.u16(7).u16(0x8400).u16(0).u16(1).u16(0).u16(0)
        p.name(listOf("_airplay", "_tcp", "local"), tag = "service")
        val done = p.record(Mdns.TYPE_PTR, cls = 1)
        p.name(listOf("Living Room"), suffix = "service")
        done()
        val assembler = DeviceAssembler()
        assembler.add(Mdns.parse(p.bytes()))
        assertEquals(emptyList(), assembler.devices())
        assertEquals(emptyList(), assembler.missing())
    }

    @Test
    fun `a pointer loop is rejected rather than followed forever`() {
        val bytes = byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 1, 0, 0, 0, 0, 0, 0, 0xC0.toByte(), 12, 0, 12, 0, 1)
        assertFailsWith<Mdns.FormatException> { Mdns.parse(bytes) }
    }

    @Test
    fun `a truncated packet is rejected`() {
        val full = reply(4500, withAddress = true)
        assertFailsWith<Mdns.FormatException> { Mdns.parse(full.copyOf(full.size - 3)) }
    }

    @Test
    fun `names compare without case`() {
        assertEquals(Mdns.Name.of("Living-Room.LOCAL").key, Mdns.Name.of("living-room.local").key)
    }
}
