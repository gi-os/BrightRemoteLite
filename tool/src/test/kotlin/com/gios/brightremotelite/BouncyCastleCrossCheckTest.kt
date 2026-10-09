package com.gios.brightremotelite

import com.gios.brightremotelite.crypto.ChaCha20Poly1305
import com.gios.brightremotelite.crypto.Curve25519
import com.gios.brightremotelite.crypto.Digest
import com.gios.brightremotelite.crypto.Srp
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.agreement.srp.SRP6Server
import org.bouncycastle.crypto.agreement.srp.SRP6VerifierGenerator
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305 as BcChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.math.BigInteger
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * The hand-written crypto checked against BouncyCastle on random inputs.
 *
 * The golden vectors in [CryptoTest] pin these primitives to the exact bytes pyatv's libraries
 * produce. This adds a second, independent implementation: BouncyCastle is allowlisted by the
 * Light SDK, so it is available as a test-only dependency even though the tool itself ships the
 * small pure-Kotlin versions (the APK does not need an 8 MB provider for a dozen operations).
 * Random inputs from a fixed seed, so a failure is reproducible.
 */
class BouncyCastleCrossCheckTest {

    private val random = Random(0x5EED)

    private fun bytes(n: Int) = random.nextBytes(n)

    @Test
    fun `ed25519 public keys and signatures match bouncycastle`() {
        repeat(8) {
            val seed = bytes(32)
            val message = bytes(random.nextInt(0, 200))
            val bcPrivate = Ed25519PrivateKeyParameters(seed, 0)
            val bcPublic = bcPrivate.generatePublicKey().encoded
            assertContentEquals(bcPublic, Curve25519.ed25519PublicKey(seed))

            val signer = Ed25519Signer().apply {
                init(true, bcPrivate)
                update(message, 0, message.size)
            }
            val bcSignature = signer.generateSignature()
            assertContentEquals(bcSignature, Curve25519.ed25519Sign(seed, message))

            // And each side accepts the other's signature.
            assertTrue(Curve25519.ed25519Verify(bcPublic, message, bcSignature))
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(bcPublic, 0))
            val ours = Curve25519.ed25519Sign(seed, message)
            verifier.update(message, 0, message.size)
            assertTrue(verifier.verifySignature(ours))
        }
    }

    @Test
    fun `x25519 agreement matches bouncycastle`() {
        repeat(8) {
            val ours = bytes(32)
            val theirs = bytes(32)
            val bcOurs = X25519PrivateKeyParameters(ours, 0)
            val bcTheirs = X25519PrivateKeyParameters(theirs, 0)
            assertContentEquals(bcOurs.generatePublicKey().encoded, Curve25519.x25519PublicKey(ours))

            val theirPublic = bcTheirs.generatePublicKey().encoded
            val shared = ByteArray(32)
            X25519Agreement().apply {
                init(bcOurs)
                calculateAgreement(X25519PublicKeyParameters(theirPublic, 0), shared, 0)
            }
            assertContentEquals(shared, Curve25519.x25519(ours, theirPublic))
        }
    }

    @Test
    fun `chacha20-poly1305 matches bouncycastle`() {
        for (length in listOf(0, 1, 15, 16, 17, 63, 64, 65, 300)) {
            val key = bytes(32)
            val nonce = bytes(12)
            val aad = bytes(random.nextInt(0, 24))
            val plain = bytes(length)

            val bc = BcChaCha20Poly1305()
            bc.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
            val out = ByteArray(bc.getOutputSize(plain.size))
            val n = bc.processBytes(plain, 0, plain.size, out, 0)
            bc.doFinal(out, n)

            assertContentEquals(out, ChaCha20Poly1305.encrypt(key, nonce, plain, aad))
            assertContentEquals(plain, ChaCha20Poly1305.decrypt(key, nonce, out, aad))
        }
    }

    @Test
    fun `hkdf-sha512 matches bouncycastle`() {
        for ((salt, info) in listOf(
            "Pair-Verify-Encrypt-Salt" to "Pair-Verify-Encrypt-Info",
            "" to "ClientEncrypt-main",
            "" to "ServerEncrypt-main",
        )) {
            val ikm = bytes(64)
            val bc = HKDFBytesGenerator(SHA512Digest())
            bc.init(HKDFParameters(ikm, salt.toByteArray(), info.toByteArray()))
            val expected = ByteArray(32)
            bc.generateBytes(expected, 0, expected.size)
            assertContentEquals(expected, Digest.hkdfSha512(salt, info, ikm))
        }
    }

    /**
     * The SRP premaster secret, computed by BouncyCastle's SRP-6a server against our client.
     *
     * Only the shared secret is compared. BouncyCastle's evidence messages use a simplified
     * M1 = H(A | B | S) rather than the RFC 2945 form HAP uses, so the proofs themselves are
     * covered by the pyatv vectors instead. Agreeing on S proves x, k, u, A and the modular
     * arithmetic all match an independent implementation.
     */
    @Test
    fun `srp shared secret matches a bouncycastle server`() {
        val secure = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) }
        repeat(3) {
            val salt = bytes(16)
            val pin = "%04d".format(random.nextInt(0, 10_000))
            val verifier = SRP6VerifierGenerator().apply { init(Srp.N, Srp.G, SHA512Digest()) }
                .generateVerifier(salt, "Pair-Setup".toByteArray(), pin.toByteArray())
            val server = SRP6Server().apply { init(Srp.N, Srp.G, verifier, SHA512Digest(), secure) }
            val serverPublic: BigInteger = server.generateServerCredentials()

            val session = Srp.Session(bytes(32))
            session.process(salt, Srp.minimalBytes(serverPublic), pin)

            val secret = server.calculateSecret(session.publicKey)
            assertContentEquals(Digest.sha512(Srp.minimalBytes(secret)), session.sessionKey)
        }
    }
}
