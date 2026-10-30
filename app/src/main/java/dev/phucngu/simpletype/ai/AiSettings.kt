package dev.phucngu.simpletype.ai

import android.content.SharedPreferences

/**
 * A chat-completions backend the text fixer can call. Every provider speaks the OpenAI
 * `/chat/completions` format; the GLM presets are first-class and only need an API key.
 */
enum class AiProvider(
    val defaultBaseUrl: String,
    val defaultModel: String,
    /** GLM-5.3 models always think; the request sets their thinking and sampling params. */
    val isGlm: Boolean,
) {
    /** GLM-5.3 Flash on Z.ai's general (pay-as-you-go) API. */
    GLM_FLASH("https://api.z.ai/api/paas/v4", "glm-5.3-flash", isGlm = true),

    /** GLM-5.3 billed against a GLM Coding Plan subscription (its own endpoint). */
    GLM_CODING_PLAN("https://api.z.ai/api/coding/paas/v4", "glm-5.3", isGlm = true),

    /** Any OpenAI-compatible endpoint; base URL and model are user-supplied. */
    CUSTOM("", "", isGlm = false),
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

        private fun keyFor(p: AiProvider) = "api_key_${p.name}"
        private fun modelFor(p: AiProvider) = "model_${p.name}"
        private fun baseUrlFor(p: AiProvider) = "base_url_${p.name}"

        fun load(prefs: SharedPreferences): AiSettings {
            val provider = prefs.getString(KEY_PROVIDER, null)
                ?.let { name -> AiProvider.entries.firstOrNull { it.name == name } }
                ?: AiProvider.GLM_FLASH
            return forProvider(prefs, provider)
        }

        /** The stored settings for [provider], whether or not it is the selected one. */
        fun forProvider(prefs: SharedPreferences, provider: AiProvider) = AiSettings(
            provider = provider,
            apiKey = prefs.getString(keyFor(provider), "") ?: "",
            model = prefs.getString(modelFor(provider), null) ?: provider.defaultModel,
            baseUrl = if (provider == AiProvider.CUSTOM) {
                prefs.getString(baseUrlFor(provider), "") ?: ""
            } else {
                provider.defaultBaseUrl
            },
        )

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
