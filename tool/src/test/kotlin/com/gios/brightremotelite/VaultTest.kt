package com.gios.brightremotelite

import com.gios.brightremotelite.data.Vault
import java.util.Base64
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The sealed-credential format, with a software AES key standing in for the Keystore one. The
 * code under test is the same code that runs on the phone.
 */
class VaultTest {

    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `round trips and never repeats a ciphertext`() {
        val k = key()
        val vault = Vault { k }
        val secret = "aa:bb:cc:dd"
        val one = vault.seal(secret)
        val two = vault.seal(secret)
        assertNotEquals(one, two, "a fresh IV per seal")
        assertEquals(secret, vault.open(one))
        assertEquals(secret, vault.open(two))
    }

    @Test
    fun `a flipped bit fails to open`() {
        val k = key()
        val vault = Vault { k }
        val bytes = Base64.getDecoder().decode(vault.seal("credentials"))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        assertNull(vault.open(Base64.getEncoder().encodeToString(bytes)))
    }

    @Test
    fun `another key cannot open it, which is how a restored install looks`() {
        val sealed = Vault { key() }.seal("credentials")
        val other = key()
        assertNull(Vault { other }.open(sealed))
        assertNull(Vault { other }.open("not base64 at all!"))
    }
}
