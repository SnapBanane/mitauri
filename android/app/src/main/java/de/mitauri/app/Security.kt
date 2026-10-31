package de.mitauri.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keys live in Android's secure hardware: the app can use them, but nothing can read them out. */
object DeviceKey {
    private const val ALIAS = "mitauri-device"
    private val keyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun publicKeyBytes(): ByteArray {
        val store = keyStore
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").run {
                initialize(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build()
                )
                generateKeyPair()
            }
        }
        return store.getCertificate(ALIAS).publicKey.encoded
    }

    fun publicKeyBase64(): String = Base64.encodeToString(publicKeyBytes(), Base64.NO_WRAP)

    fun delete() {
        keyStore.deleteEntry(ALIAS)
    }

    /** The value of the header that proves this request came from this phone, just now. */
    fun authHeader(method: String, path: String, body: ByteArray = ByteArray(0)): String {
        val publicKey = publicKeyBytes()
        val device = hex(MessageDigest.getInstance("SHA-256").digest(publicKey)).take(16)
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val nonce = hex(ByteArray(16).also { SecureRandom().nextBytes(it) })
        val bodyHash = hex(MessageDigest.getInstance("SHA-256").digest(body))
        val message = listOf(method, path, timestamp, nonce, bodyHash).joinToString("\n").toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyStore.getKey(ALIAS, null) as PrivateKey)
            update(message)
            sign()
        }
        val encoded = Base64.encodeToString(signature, Base64.URL_SAFE or Base64.NO_WRAP)
        return "$device.$timestamp.$nonce.$encoded"
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}

/** Keeps the chat history encrypted on disk, with its key held in the secure hardware. */
class EncryptedFile(context: Context, name: String) {
    private val file = File(context.filesDir, name)

    fun read(): String? {
        if (!file.exists()) return null
        return try {
            val data = file.readBytes()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES))
            String(cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES))
        } catch (_: Exception) {
            null // A damaged history isn't worth crashing for.
        }
    }

    fun write(text: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        file.writeBytes(cipher.iv + cipher.doFinal(text.toByteArray()))
    }

    fun delete() {
        file.delete()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as SecretKey?)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "mitauri-storage"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
