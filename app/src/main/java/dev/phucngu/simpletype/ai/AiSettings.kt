package dev.phucngu.simpletype.ai

import android.content.SharedPreferences

/**
 * A chat-completions backend the text fixer can call. Every provider speaks the OpenAI
 * `/chat/completions` format; the GLM Coding Plan is first-class and only needs an API key.
 */
enum class AiProvider(
    val defaultBaseUrl: String,
    /** Models offered as a choice; empty means the user types a model ID. */
    val models: List<String>,
    /** GLM-5.3 models always think; the request sets their thinking and sampling params. */
    val isGlm: Boolean,
) {
    /** GLM models billed against a GLM Coding Plan subscription. Flash first: fastest in testing. */
    GLM_CODING_PLAN("https://api.z.ai/api/coding/paas/v4", listOf("glm-5.3-flash", "glm-5.3"), isGlm = true),

    /** Any OpenAI-compatible endpoint; base URL and model are user-supplied. */
    CUSTOM("", emptyList(), isGlm = false);

    val defaultModel: String get() = models.firstOrNull() ?: ""
}

/** The user's AI configuration: which provider, and the key/model/URL to call it with. */
data class AiSettings(
    val provider: AiProvider,
    val apiKey: String,
    val model: String,
    val baseUrl: String,
) {
    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && model.isNotBlank() && baseUrl.isNotBlank()

    /** Full chat-completions URL, tolerating a trailing slash or a pasted full path. */
    val endpoint: String
        get() {
            val base = baseUrl.trim().trimEnd('/')
            return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        }

    companion object {
        /** Separate from the keyboard prefs so API keys can be excluded from backups. */
        const val PREFS_NAME = "simpletype_ai"
        private const val KEY_PROVIDER = "provider"

        /** Pre-release preset (GLM-5.3 Flash on the general API); its key works on the Coding Plan. */
        private const val LEGACY_GLM_FLASH = "GLM_FLASH"

        private fun keyFor(p: AiProvider) = "api_key_${p.name}"
        private fun modelFor(p: AiProvider) = "model_${p.name}"
        private fun baseUrlFor(p: AiProvider) = "base_url_${p.name}"

        fun load(prefs: SharedPreferences): AiSettings {
            val provider = prefs.getString(KEY_PROVIDER, null)
                ?.let { name -> AiProvider.entries.firstOrNull { it.name == name } }
                ?: AiProvider.GLM_CODING_PLAN
            return forProvider(prefs, provider)
        }

        /** The stored settings for [provider], whether or not it is the selected one. */
        fun forProvider(prefs: SharedPreferences, provider: AiProvider): AiSettings {
            val storedKey = prefs.getString(keyFor(provider), "") ?: ""
            val apiKey = if (storedKey.isEmpty() && provider == AiProvider.GLM_CODING_PLAN) {
                prefs.getString("api_key_$LEGACY_GLM_FLASH", "") ?: ""
            } else {
                storedKey
            }
            val storedModel = prefs.getString(modelFor(provider), null)
            // A preset only offers its listed models; anything else falls back to the default.
            val model = when {
                provider.models.isEmpty() -> storedModel ?: ""
                storedModel in provider.models -> storedModel!!
                else -> provider.defaultModel
            }
            return AiSettings(
                provider = provider,
                apiKey = apiKey,
                model = model,
                baseUrl = if (provider == AiProvider.CUSTOM) {
                    prefs.getString(baseUrlFor(provider), "") ?: ""
                } else {
                    provider.defaultBaseUrl
                },
            )
        }

        fun save(prefs: SharedPreferences, settings: AiSettings) {
            val p = settings.provider
            prefs.edit()
                .putString(KEY_PROVIDER, p.name)
                .putString(keyFor(p), settings.apiKey.trim())
                .putString(modelFor(p), settings.model.trim())
                .apply { if (p == AiProvider.CUSTOM) putString(baseUrlFor(p), settings.baseUrl.trim()) }
                .apply()
        }
    }
}
