package com.gios.brightremotelite.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals small secrets with AES-256-GCM. The output is `iv || ciphertext+tag`, Base64.
 *
 * The key comes from [keyProvider]. On the phone that is a key held in the Android Keystore, which
 * never leaves it; in the unit tests it is an ordinary software key, so the format is tested by
 * the same code that runs on the device.
 */
class Vault(private val keyProvider: () -> SecretKey) {

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128
    }

    fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        val iv = cipher.iv
        check(iv.size == IV_LENGTH) { "unexpected GCM IV length ${iv.size}" }
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + sealed)
    }

    /** Null when the blob is damaged or was sealed with a key this install no longer has. */
    fun open(blob: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(blob)
        require(bytes.size > IV_LENGTH)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            keyProvider(),
            GCMParameterSpec(TAG_BITS, bytes, 0, IV_LENGTH),
        )
        String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
    }.getOrNull()
}

/**
 * The Android Keystore key the pairing credentials are sealed with. Created on first use.
 *
 * If the app's data is restored onto another phone the key does not come with it, the sealed
 * credentials fail to open, and the TV simply shows up as unpaired. That is the intended outcome:
 * the pairing belongs to this phone.
 */
object KeystoreKey {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "brightremotelite.credentials"

    @Synchronized
    fun get(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
