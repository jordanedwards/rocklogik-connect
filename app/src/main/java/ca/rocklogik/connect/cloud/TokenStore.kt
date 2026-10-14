package ca.rocklogik.connect.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The rocklogik-cloud session: the bearer token and who it belongs to. The token is encrypted
 * with an AES key held in the Android Keystore, so a copy of the app's files is not a session.
 */
class TokenStore(context: Context) {
    data class Session(val token: String, val name: String, val email: String)

    private val prefs = context.getSharedPreferences("cloud_session", Context.MODE_PRIVATE)

    fun load(): Session? {
        val blob = prefs.getString("token", null) ?: return null
        val token = runCatching { decrypt(blob) }.getOrNull() ?: run { clear(); return null }
        return Session(token, prefs.getString("name", "") ?: "", prefs.getString("email", "") ?: "")
    }

    fun save(s: Session) {
        prefs.edit().putString("token", encrypt(s.token)).putString("name", s.name).putString("email", s.email).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val all = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, IV_LEN))
        }
        return String(c.doFinal(all, IV_LEN, all.size - IV_LEN), Charsets.UTF_8)
    }

    private companion object {
        const val ALIAS = "rocklogik-cloud-session"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
