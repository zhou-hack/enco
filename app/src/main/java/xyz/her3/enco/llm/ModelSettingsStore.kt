package xyz.her3.enco.llm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.modelSettings by preferencesDataStore("model_settings")

internal class ModelSettingsStore(
    context: Context,
) {
    private val store = context.applicationContext.modelSettings

    suspend fun load(): ModelConfig? =
        withContext(Dispatchers.IO) {
            val values = store.data.first()
            val endpoint = values[ENDPOINT]?.let(::chatEndpoint) ?: return@withContext null
            val model = values[MODEL] ?: return@withContext null
            val secret = values[SECRET]?.let(::decrypt).orEmpty()
            ModelConfig(endpoint, model, secret)
        }

    suspend fun save(config: ModelConfig) =
        withContext(Dispatchers.IO) {
            store.edit { values ->
                values[ENDPOINT] = config.endpoint.toString()
                values[MODEL] = config.model
                if (config.apiKey.isEmpty()) {
                    values.remove(SECRET)
                } else {
                    values[SECRET] = encrypt(config.apiKey)
                }
            }
        }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, bytes, 0, IV_LENGTH))
        return cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH).decodeToString()
    }

    private fun key(create: Boolean): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keys.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        check(create) { "模型设置密钥不可用" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec
                    .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        val ENDPOINT = stringPreferencesKey("endpoint")
        val MODEL = stringPreferencesKey("model")
        val SECRET = stringPreferencesKey("secret")
        const val KEY_ALIAS = "enco.model_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
    }
}
