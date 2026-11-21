package io.gitshard.gitshard.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) {
    private val keyLock = Any()

    fun encrypt(plaintext: ByteArray, aad: ByteArray? = null): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        if (aad != null && aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        val ciphertext = cipher.doFinal(plaintext)
        val iv = cipher.iv
        require(iv.size == GCM_IV_LENGTH) { "Unexpected GCM IV size: ${iv.size}" }

        return ByteArray(HEADER_LENGTH + ciphertext.size).apply {
            this[0] = FORMAT_VERSION
            iv.copyInto(this, destinationOffset = 1)
            ciphertext.copyInto(this, destinationOffset = HEADER_LENGTH)
        }
    }

    fun decrypt(payload: ByteArray, aad: ByteArray? = null): ByteArray {
        if (payload.size < MIN_PAYLOAD_LENGTH) {
            throw GeneralSecurityException("Encrypted payload is truncated")
        }
        if (payload[0] != FORMAT_VERSION) {
            throw GeneralSecurityException("Unsupported cipher payload version: ${payload[0]}")
        }

        val iv = payload.copyOfRange(1, HEADER_LENGTH)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        if (aad != null && aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        return cipher.doFinal(payload, HEADER_LENGTH, payload.size - HEADER_LENGTH)
    }

    fun encryptString(plaintext: String, aad: String? = null): String {
        val encrypted = encrypt(
            plaintext = plaintext.toByteArray(Charsets.UTF_8),
            aad = aad?.toByteArray(Charsets.UTF_8),
        )
        return Base64.getEncoder().encodeToString(encrypted)
    }

    fun decryptString(encodedPayload: String, aad: String? = null): String {
        val payload = Base64.getDecoder().decode(encodedPayload)
        val decrypted = decrypt(
            payload = payload,
            aad = aad?.toByteArray(Charsets.UTF_8),
        )
        return decrypted.toString(Charsets.UTF_8)
    }

    fun deleteKey() {
        synchronized(keyLock) {
            val ks = loadKeyStore()
            if (ks.containsAlias(keyAlias)) {
                ks.deleteEntry(keyAlias)
            }
        }
    }

    private fun getExistingKey(): SecretKey {
        val ks = loadKeyStore()
        return (ks.getKey(keyAlias, null) as? SecretKey)
            ?: throw GeneralSecurityException("Keystore key '$keyAlias' not found")
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = loadKeyStore()
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        return synchronized(keyLock) {
            val lockedKs = loadKeyStore()
            (lockedKs.getKey(keyAlias, null) as? SecretKey)?.let { return it }

            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            val spec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(AES_KEY_SIZE_BITS)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()

            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val DEFAULT_KEY_ALIAS = "io.gitshard.gitshard.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val AES_KEY_SIZE_BITS = 256
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH_BITS = 128
        const val GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8
        const val FORMAT_VERSION: Byte = 1
        const val HEADER_LENGTH = 1 + GCM_IV_LENGTH
        const val MIN_PAYLOAD_LENGTH = HEADER_LENGTH + GCM_TAG_LENGTH_BYTES
    }
}
