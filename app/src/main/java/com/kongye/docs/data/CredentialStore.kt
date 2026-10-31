package com.kongye.docs.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The key stays inside Android Keystore and is never included in document backups. */
object CredentialStore {
    private const val PREFIX = "keystore:v1:"
    private const val ALIAS = "kongye.webdav.v1"
    /** 密码空间用单独的密钥，和 WebDAV 凭据互不影响 */
    const val VAULT_ALIAS = "kongye.vault.v1"
    private fun key(alias: String): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    fun isEncrypted(value: String) = value.startsWith(PREFIX)
    fun encrypt(value: String, alias: String = ALIAS): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(alias))
        return PREFIX + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }
    fun decrypt(value: String, alias: String = ALIAS): String {
        if (!isEncrypted(value)) return value // migrate legacy 2.1 settings on first load
        val fields = value.removePrefix(PREFIX).split(':')
        require(fields.size == 2) { "备份凭据格式损坏" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(alias), GCMParameterSpec(128, Base64.decode(fields[0], Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(fields[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }
}
