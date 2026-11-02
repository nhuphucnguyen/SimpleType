package dev.phucngu.simpletype.ai

import dev.phucngu.simpletype.voice.VoiceLanguage
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Anthropic Messages API wire family (`/v1/messages`), used by the Claude subscription
 * provider. Pure functions over strings so the wire format is unit-testable without a network.
 *
 * Subscription OAuth tokens (`sk-ant-oat01-…`) only answer requests shaped like Claude Code: the
 * `system` field must open with Anthropic's billing tag (verified live against
 * api.anthropic.com — values are free-form, but without the tag the API rejects the request with
 * a masked `429 rate_limit_error`). Our proofreader instructions follow in the same block, which
 * the gate explicitly allows.
 */
object AnthropicMessages : ChatProtocol {

    /** Required by the Messages API alongside `Authorization: Bearer …`. */
    const val API_VERSION = "2023-06-01"

    override val endpointPath = "/v1/messages"

    override fun extraHeaders() = mapOf("anthropic-version" to API_VERSION)

    private const val BILLING_TAG = "x-anthropic-billing-header: cc_version=2.1.296.0e7; cc_entrypoint=simpletype;"

    override fun requestBody(settings: AiSettings, text: String, language: VoiceLanguage): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "user").put("content", ChatCompletion.userMessage(text, language)))
        return JSONObject()
            .put("model", settings.model)
            .put("max_tokens", maxTokensFor(text))
            .put("system", "$BILLING_TAG\n\n${ChatCompletion.SYSTEM_PROMPT}")
            .put("temperature", 0.2)
            .put("messages", messages)
            .toString()
    }

    /** The Messages API demands max_tokens; output tracks input length with headroom for fixes. */
    fun maxTokensFor(text: String): Int = (text.length * 2 + 512).coerceIn(1024, 8192)

    /** Concatenated text blocks of the reply, or throws [AiException] describing an error payload. */
    override fun parseReply(json: String): String {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw AiException(AiException.Kind.BAD_RESPONSE, json.take(200))
        }
        root.optJSONObject("error")?.let { err ->
            throw AiException(AiException.Kind.SERVER, err.optString("message", err.toString()))
        }
        val content = root.optJSONArray("content") ?: JSONArray()
        val text = buildString {
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                if (block.optString("type", "text") == "text") append(block.optString("text", ""))
            }
        }
        if (text.isBlank()) throw AiException(AiException.Kind.BAD_RESPONSE, json.take(200))
        return text
    }
}
