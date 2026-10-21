package app.widgetchat.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.widgetchat.R
import app.widgetchat.model.SpecCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * OpenAI-compatible chat model configuration. The API key is encrypted with a key that never
 * leaves the Android Keystore; only the ciphertext is written to preferences.
 */
data class ModelConfig(
    val provider: String = ModelPresets.DEEPSEEK,
    val baseUrl: String = ModelPresets.DEEPSEEK_BASE_URL,
    val model: String = ModelPresets.DEEPSEEK_MODEL,
    val apiKey: String = ""
) {
    /** Appends /chat/completions unless the user already typed the full endpoint. */
    val endpoint: String
        get() {
            val base = baseUrl.trim().trimEnd('/')
            return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        }

    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && model.isNotBlank() && endpoint.startsWith("https://")
}

object ModelPresets {
    const val DEEPSEEK = "deepseek"
    const val GLM = "glm"
    const val OPENAI = "openai"
    const val CUSTOM = "custom"

    const val DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1"
    const val DEEPSEEK_MODEL = "deepseek-chat"
    const val GLM_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
    const val GLM_MODEL = "glm-4.6"
    const val OPENAI_BASE_URL = "https://api.openai.com/v1"
    const val OPENAI_MODEL = "gpt-4o-mini"

    /** [models] are the common model ids offered as one-tap chips in Settings. */
    data class Preset(val id: String, val labelRes: Int, val baseUrl: String, val models: List<String>) {
        val defaultModel: String get() = models.firstOrNull().orEmpty()
    }

    val all = listOf(
        Preset(
            DEEPSEEK, R.string.model_deepseek, DEEPSEEK_BASE_URL,
            listOf("deepseek-chat", "deepseek-reasoner", "deepseek-4.1-flash")
        ),
        Preset(
            GLM, R.string.model_glm, GLM_BASE_URL,
            listOf("glm-4.6", "glm-4.5-air", "glm-5.3-flash")
        ),
        Preset(
            OPENAI, R.string.model_openai, OPENAI_BASE_URL,
            listOf("gpt-4o-mini", "gpt-4.1-mini")
        ),
        Preset(CUSTOM, R.string.model_custom, "", emptyList())
    )

    fun of(id: String): Preset = all.firstOrNull { it.id == id } ?: all.first()
    fun labelRes(id: String): Int = of(id).labelRes
    fun commonModels(id: String): List<String> = of(id).models
}

class ModelSettings(context: Context) {
    private val prefs = context.getSharedPreferences("widget-chat-model", Context.MODE_PRIVATE)

    fun read(): ModelConfig {
        val provider = prefs.getString(KEY_PROVIDER, ModelPresets.DEEPSEEK) ?: ModelPresets.DEEPSEEK
        val preset = ModelPresets.of(provider)
        val storedKey = prefs.getString(KEY_API_KEY, null)
        return ModelConfig(
            provider = provider,
            baseUrl = prefs.getString(KEY_BASE_URL, null) ?: preset.baseUrl,
            model = prefs.getString(KEY_MODEL, null) ?: preset.defaultModel,
            apiKey = storedKey?.let { runCatching { SecretStore.decrypt(it) }.getOrNull() }.orEmpty()
        )
    }

    /** Saves the active configuration and remembers it for one-tap switching in the chat tab. */
    fun save(config: ModelConfig) {
        val editor = prefs.edit()
            .putString(KEY_PROVIDER, config.provider)
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_MODEL, config.model)
        if (config.apiKey.isBlank()) editor.remove(KEY_API_KEY)
        else editor.putString(KEY_API_KEY, SecretStore.encrypt(config.apiKey))
        editor.apply()
        remember(config)
    }

    fun clearKey() {
        val active = read()
        val remaining = rawProfiles().filterNot { it.matches(active) }
        prefs.edit().remove(KEY_API_KEY).putString(KEY_PROFILES, SpecCodec.json.encodeToString(remaining)).apply()
    }

    /** Every configuration that has an encrypted key, most recent last. */
    fun profiles(): List<ModelConfig> = rawProfiles().mapNotNull { it.toConfig() }

    private fun remember(config: ModelConfig) {
        if (config.apiKey.isBlank()) return
        val blob = runCatching { SecretStore.encrypt(config.apiKey) }.getOrNull() ?: return
        val keyed = StoredProfile(config.provider, config.baseUrl, config.model, blob)
        val next = (rawProfiles().filterNot { it.matches(config) } + keyed).takeLast(MAX_PROFILES)
        prefs.edit().putString(KEY_PROFILES, SpecCodec.json.encodeToString(next)).apply()
    }

    private fun rawProfiles(): List<StoredProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching { SpecCodec.json.decodeFromString<List<StoredProfile>>(raw) }.getOrDefault(emptyList())
    }

    @Serializable
    private data class StoredProfile(
        val provider: String,
        val baseUrl: String,
        val model: String,
        val key: String
    ) {
        fun matches(config: ModelConfig) =
            provider == config.provider && baseUrl == config.baseUrl && model == config.model

        fun toConfig(): ModelConfig? {
            val plain = runCatching { SecretStore.decrypt(key) }.getOrNull() ?: return null
            return ModelConfig(provider, baseUrl, model, plain)
        }
    }

    private companion object {
        const val KEY_PROVIDER = "provider"
        const val KEY_BASE_URL = "base-url"
        const val KEY_MODEL = "model"
        const val KEY_API_KEY = "api-key"
        const val KEY_PROFILES = "profiles"
        const val MAX_PROFILES = 8
    }
}

/** AES/GCM key held by the Android Keystore; the ciphertext is stored in preferences. */
private object SecretStore {
    private const val ALIAS = "widgetchat-model-key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        require(cipher.iv.size == IV_BYTES) { "Unexpected GCM IV size" }
        val payload = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    fun decrypt(blob: String): String {
        val payload = Base64.decode(blob, Base64.NO_WRAP)
        require(payload.size > IV_BYTES) { "Corrupted secret" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload, 0, IV_BYTES))
        return String(cipher.doFinal(payload, IV_BYTES, payload.size - IV_BYTES), Charsets.UTF_8)
    }
}
