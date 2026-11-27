package com.homelab.app.data.remote.access

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.homelab.app.domain.model.AccessProfile
import com.homelab.app.util.Logger
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts profiles with a key that never leaves the Android Keystore. Never backed up. */
class KeystoreProfileStorage(context: Context) : AccessProfileStorage {
    private val file = AtomicFile(File(context.noBackupFilesDir, "access_profiles"))

    override fun load(): Map<String, AccessProfile> = try {
        if (!file.baseFile.exists()) {
            emptyMap()
        } else {
            val data = file.readFully()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES))
            AccessProfileStore.decode(String(cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES), Charsets.UTF_8))
        }
    } catch (error: Exception) {
        // For example after the key was reset; the user signs in again.
        Logger.w("AccessProfiles", "Stored profiles could not be read: ${error.javaClass.simpleName}")
        emptyMap()
    }

    override fun save(profiles: Map<String, AccessProfile>) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val data = cipher.iv + cipher.doFinal(AccessProfileStore.encode(profiles).toByteArray(Charsets.UTF_8))
            val stream = file.startWrite()
            try {
                stream.write(data)
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        } catch (error: Exception) {
            Logger.w("AccessProfiles", "Profiles could not be stored: ${error.javaClass.simpleName}")
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "rackwarden_access_profiles"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
