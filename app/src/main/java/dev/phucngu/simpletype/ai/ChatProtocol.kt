package dev.phucngu.simpletype.ai

/**
 * One model call, independent of wire format: the instructions, the user turn, the output
 * budget and, where the provider takes one, a sampling temperature. Each AI feature builds its
 * own request ([AiTextFixer], [AiWriter]); [AiClient] sends it.
 */
data class AiRequest(
    val system: String,
    val user: String,
    val maxTokens: Int,
    /** Used by providers that accept sampling params; GLM and Claude requests set their own. */
    val temperature: Double,
)

/**
 * The wire protocol an [AiProvider] speaks: how an [AiRequest] is encoded and how a successful
 * reply is decoded. Implement this for a new backend family and reference the instance from an
 * [AiProvider] entry; [AiClient] picks it up with no further changes.
 *
 * Cross-protocol concerns live elsewhere on purpose: prompts in the feature classes, reply
 * cleanup and error-message extraction in [ChatCompletion], HTTP-status mapping in [AiClient]
 * (both families speak `error.message` and the same status semantics).
 */
interface ChatProtocol {
    /** Path appended to the provider's base URL, e.g. "/v1/messages". */
    val endpointPath: String

    /** Headers this protocol needs beyond the shared auth/content-type set. */
    fun extraHeaders(): Map<String, String> = emptyMap()

    fun requestBody(settings: AiSettings, request: AiRequest): String

    /** The assistant's reply text, or throws [AiException] describing an error payload. */
    fun parseReply(json: String): String
}
