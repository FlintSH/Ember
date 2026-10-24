package dev.fl1nt.ember.data.account

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets, such as the Flare token, before they're written to disk. */
interface SecretCipher {
    fun encrypt(plaintext: String): String

    /** @throws GeneralSecurityException when the value can't be decrypted on this device. */
    fun decrypt(encoded: String): String
}

/**
 * AES-256-GCM with a key that lives in the Android Keystore. The key can't be
 * exported or backed up, so stored ciphertext is useless on any other device.
 */
class KeystoreSecretCipher(private val alias: String = "ember.credentials") : SecretCipher {

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return encode(cipher.iv) + SEPARATOR + encode(sealed)
    }

    override fun decrypt(encoded: String): String {
        val parts = encoded.split(SEPARATOR)
        if (parts.size != 2) throw GeneralSecurityException("Malformed secret")
        // A new key can't decrypt anything, so a missing key is an error, not a reason to make one.
        val key = existingKey() ?: throw GeneralSecurityException("The key for this secret is gone")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, decode(parts[0])))
        return String(cipher.doFinal(decode(parts[1])), Charsets.UTF_8)
    }

    private fun existingKey(): SecretKey? {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun key(): SecretKey {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val SEPARATOR = ":"
    }
}
