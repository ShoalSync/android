package org.tidefell.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.tidefell.ffi.KeyVault
import org.tidefell.ffi.TidefellException
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the device key sealed with an AES key held in the Android Keystore, which never leaves
 * it (ADR 0038): a copy of the app's storage holds only the sealed key, which nothing but this
 * phone's Keystore can open.
 *
 * [file] holds the IV followed by the sealed key.
 */
class KeystoreVault(private val file: File) : KeyVault {
    // Not "nothing stored" when opening fails: a new key would make this a different device.
    override fun load(): ByteArray? {
        if (!file.exists()) return null
        return failing("opened") {
            val sealed = file.readBytes()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        }
    }

    override fun store(seed: ByteArray) = failing("stored") {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        val sealed = cipher.iv + cipher.doFinal(seed)
        // Written whole or not at all.
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.outputStream().use { out ->
            out.write(sealed)
            out.fd.sync()
        }
        check(tmp.renameTo(file)) { "renaming ${tmp.name} failed" }
    }

    /** Runs [block], reporting what goes wrong to the library as the key not being [done]. */
    private fun <T> failing(done: String, block: () -> T): T {
        val error = try {
            return block()
        } catch (e: GeneralSecurityException) {
            e
        } catch (e: IOException) {
            e
        } catch (e: IllegalStateException) {
            e
        }
        throw TidefellException.Failed("the device key cannot be $done: $error")
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "the Keystore holds no key $ALIAS" }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "tidefell-device-key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
