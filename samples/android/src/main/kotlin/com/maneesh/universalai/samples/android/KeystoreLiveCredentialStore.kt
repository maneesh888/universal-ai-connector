package com.maneesh.universalai.samples.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only authenticated ciphertext is retained, in app-private no-backup storage. */
class KeystoreLiveCredentialStore(
    context: Context,
    storageName: String = "live-credentials",
    private val keyAlias: String = "uac.sample.live.credentials.v1",
) : LiveCredentialStore {
    private val directory = File(context.noBackupFilesDir, storageName)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    override fun read(key: String): String? {
        val file = file(key)
        val input = try { file.openRead() } catch (failure: java.io.FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw failure
            return null
        }
        val encrypted = input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                check(output.size() + count <= 8300)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        check(encrypted.size >= 29 && encrypted[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, checkNotNull(keyStore.getKey(keyAlias, null)), GCMParameterSpec(128, encrypted.copyOfRange(1, 13)))
        cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
        val plaintext = cipher.doFinal(encrypted, 13, encrypted.size - 13)
        return try { plaintext.toString(Charsets.UTF_8).also(::validateLiveCredential) } finally { plaintext.fill(0) }
    }

    @Synchronized
    override fun save(key: String, credential: String) {
        validateLiveCredential(credential)
        check(directory.isDirectory || directory.mkdirs())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        check(cipher.iv.size == 12)
        cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
        val plaintext = credential.toByteArray(Charsets.UTF_8)
        val encrypted = try { cipher.doFinal(plaintext) } finally { plaintext.fill(0) }
        val file = file(key)
        val stream = file.startWrite()
        try {
            stream.write(byteArrayOf(1) + cipher.iv + encrypted)
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }

    @Synchronized
    override fun clearAll() {
        // Delete the key first, so any ciphertext left by a failed filesystem removal is unusable.
        keyStore.deleteEntry(keyAlias)
        check(!directory.exists() || directory.deleteRecursively())
    }

    private fun file(key: String): AtomicFile {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return AtomicFile(File(directory, digest.joinToString("") { "%02x".format(it) }))
    }

    private fun encryptionKey(): SecretKey = (keyStore.getKey(keyAlias, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
        init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        generateKey()
    }

}
