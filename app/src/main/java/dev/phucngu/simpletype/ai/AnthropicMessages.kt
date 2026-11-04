package dev.phucngu.simpletype.ai

import dev.phucngu.simpletype.voice.VoiceLanguage
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Anthropic Messages API wire family (`/v1/messages`), used by the Claude subscription
 * provider. Pure functions over strings so the wire format is unit-testable without a network.
 *
 * Subscription tokens only answer Claude-Code-shaped requests: `system` must open with the
 * billing tag, otherwise the API rejects the call with a masked `429 rate_limit_error`. The
 * proofreader instructions (built-in or the user's override) follow the tag in the same block.
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
        // No sampling params: Claude models reject temperature and friends.
        return JSONObject()
            .put("model", settings.model)
            .put("max_tokens", maxTokensFor(text))
            // The tag stays first even with a user-supplied prompt: without it the token is refused.
            // It gets a block of its own, as Claude Code sends it: the API consumes that block as
            // billing metadata, so instructions placed in it never reach the model.
            .put(
                "system", JSONArray()
                    .put(textBlock(BILLING_TAG))
                    .put(textBlock(settings.systemPrompt)),
            )
            .put("messages", messages)
            // Structured output: decoding is constrained to {"text": ...}, so musings like
            // "Hmm, this is likely Kobe..." cannot reach the reply. (A forced tool_choice would do
            // the same, but Sonnet 5.5 rejects it.)
            .put("output_config", JSONObject().put("format", REPLY_FORMAT))
            .toString()
    }

    private val REPLY_FORMAT: JSONObject
        get() = JSONObject()
            .put("type", "json_schema")
            .put(
                "schema", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject().put("text", JSONObject().put("type", "string")))
                    .put("required", JSONArray().put("text"))
                    .put("additionalProperties", false),
            )

    private fun textBlock(text: String) = JSONObject().put("type", "text").put("text", text)

    /** The Messages API demands max_tokens; output tracks input length with headroom for fixes. */
    fun maxTokensFor(text: String): Int = (text.length * 2 + 512).coerceIn(1024, 8192)

    /** The `text` field of the JSON reply (or the raw text blocks), or throws [AiException] describing an error payload. */
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
        // The reply is {"text": ...}; anything else (should a model ignore the format) passes
        // through as plain text.
        val fixed = runCatching { JSONObject(text.trim()).optString("text", "") }.getOrNull()
        return fixed?.takeIf { it.isNotBlank() } ?: text
    }
}
