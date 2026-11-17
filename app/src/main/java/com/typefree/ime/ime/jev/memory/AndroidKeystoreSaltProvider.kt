package com.typefree.ime.ime.jev.memory

import android.content.Context
import com.typefree.ime.data.AndroidKeyStoreAesGcmCipher
import com.typefree.ime.data.SecretVault
import java.io.File
import java.security.SecureRandom
import java.util.Base64

class AndroidKeystoreSaltProvider(
    context: Context,
    private val secretVault: SecretVault = SecretVault.fromContext(
        context.applicationContext ?: context,
        AndroidKeyStoreAesGcmCipher()
    )
) : AnchorSaltProvider {

    private val appContext: Context = context.applicationContext ?: context
    private val lock = Any()
    private val random = SecureRandom()

    @Volatile
    private var cachedSalt: ByteArray? = null

    override fun getOrCreateDeviceSalt(): ByteArray {
        cachedSalt?.let { return it.copyOf() }
        synchronized(lock) {
            cachedSalt?.let { return it.copyOf() }

            val storedB64 = runCatching { secretVault.getSecret(KEY_JEV_DEVICE_SALT) }.getOrDefault("")
            if (storedB64.isNotBlank()) {
                val decoded = runCatching { Base64.getDecoder().decode(storedB64) }.getOrNull()
                if (decoded != null && decoded.size == SALT_BYTES) {
                    cachedSalt = decoded
                    return decoded.copyOf()
                }
            }

            // Fallback file when hardware Keystore fails on rare custom ROMs
            val fallbackFile = File(appContext.filesDir, FALLBACK_SALT_FILE)
            if (fallbackFile.isFile && fallbackFile.length() == SALT_BYTES.toLong()) {
                val bytes = runCatching { fallbackFile.readBytes() }.getOrNull()
                if (bytes != null && bytes.size == SALT_BYTES) {
                    cachedSalt = bytes
                    return bytes.copyOf()
                }
            }

            val fresh = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
            persistSaltLocked(fresh)
            cachedSalt = fresh
            return fresh.copyOf()
        }
    }

    override fun rotateDeviceSalt(): ByteArray {
        synchronized(lock) {
            val fresh = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
            persistSaltLocked(fresh)
            cachedSalt = fresh
            return fresh.copyOf()
        }
    }

    fun adoptSalt(importedSalt: ByteArray) {
        require(importedSalt.size == SALT_BYTES) { "Invalid salt length" }
        synchronized(lock) {
            val copy = importedSalt.copyOf()
            persistSaltLocked(copy)
            cachedSalt = copy
        }
    }

    private fun persistSaltLocked(salt: ByteArray) {
        val b64 = Base64.getEncoder().encodeToString(salt)
        secretVault.putSecret(KEY_JEV_DEVICE_SALT, b64)
        val roundTrip = runCatching { secretVault.getSecret(KEY_JEV_DEVICE_SALT) }.getOrDefault("")
        val fallbackFile = File(appContext.filesDir, FALLBACK_SALT_FILE)
        if (roundTrip != b64) {
            runCatching {
                appContext.openFileOutput(FALLBACK_SALT_FILE, Context.MODE_PRIVATE).use { out ->
                    out.write(salt)
                }
            }
        } else if (fallbackFile.exists()) {
            runCatching { fallbackFile.delete() }
        }
    }

    companion object {
        private const val KEY_JEV_DEVICE_SALT = "sec_jev_memory_salt_v2"
        private const val FALLBACK_SALT_FILE = ".jev_salt_fallback_v2"
        private const val SALT_BYTES = 32
    }
}
